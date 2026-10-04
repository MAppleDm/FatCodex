"""Test harness: the real app on in-memory SQLite, with faked mailer / ai-parser / nutrition / stt."""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from gateway import nutrients
from gateway.config import Settings
from gateway.errors import ApiError
from gateway.main import create_app
from gateway.upstream import (
    UFood,
    UItemResult,
    ULookupItem,
    UNutrients,
    UParsedItem,
    UParseRequest,
    UParseResponse,
    UTranscribeResponse,
)

SECRET = "test-secret-" + "x" * 32
DAY = "2026-09-30"
AT = "2026-09-30T08:15:00+03:00"

# query -> (food name, kcal, protein, fat, carbs) per 100 g
FOODS = {
    "buckwheat cooked": ("Buckwheat groats, roasted, cooked", 92, 3.38, 0.62, 19.94),
    "chicken breast cooked": ("Chicken, broilers or fryers, breast, meat only, cooked, roasted", 165, 31.02, 3.57, 0.0),
    "butter": ("Butter, salted", 717, 0.85, 81.11, 0.06),
    "egg boiled": ("Egg, whole, cooked, hard-boiled", 155, 12.58, 10.61, 1.12),
    "bread white": ("Bread, white", 265, 9.0, 3.2, 49.0),
}


class Clock:
    def __init__(self) -> None:
        self.now = datetime.now(timezone.utc).replace(microsecond=0)

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **kw) -> None:
        self.now += timedelta(**kw)


class FakeMailer:
    def __init__(self) -> None:
        self.sent: list[tuple[str, str]] = []
        self.fail = False

    async def send_code(self, email: str, code: str) -> None:
        if self.fail:
            raise ApiError(502, "mail_failed", "Не удалось отправить письмо с кодом. Попробуй позже.")
        self.sent.append((email, code))

    @property
    def last_code(self) -> str:
        return self.sent[-1][1]


class FakeAi:
    """Returns scripted UParseResponse objects (or raises); remembers every request."""

    def __init__(self) -> None:
        self.queue: list[UParseResponse | Exception] = []
        self.requests: list[UParseRequest] = []

    def will_return(self, *items: UParsedItem, question: str | None = None) -> None:
        # like the real parser: the response-level question is the chosen item's question
        question = question or next((i.clarify_question for i in items if i.clarify_question), None)
        self.queue.append(UParseResponse(items=list(items), clarify_question=question))

    def will_raise(self, error: Exception) -> None:
        self.queue.append(error)

    async def parse(self, request: UParseRequest) -> UParseResponse:
        self.requests.append(request)
        outcome = self.queue.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


class FakeNutrition:
    def __init__(self) -> None:
        self.calls: list[list[ULookupItem]] = []
        self.fail: Exception | None = None

    async def lookup(self, items: list[ULookupItem]) -> list[UItemResult]:
        self.calls.append(items)
        if self.fail:
            raise self.fail
        out = []
        for item in items:
            hit = FOODS.get(item.query or item.name)
            if not hit:
                out.append(UItemResult(name=item.name, grams=item.grams, matched=False))
                continue
            name, kcal, p, f, c = hit
            out.append(UItemResult(
                name=item.name, grams=item.grams, matched=True, score=1.0,
                food=UFood(id=1, source="usda_sr_legacy", name=name, per100g=UNutrients(kcal=kcal, protein=p, fat=f, carbs=c)),
                nutrients=UNutrients(kcal=nutrients.scale(kcal, item.grams), protein=nutrients.scale(p, item.grams),
                                     fat=nutrients.scale(f, item.grams), carbs=nutrients.scale(c, item.grams)),
            ))
        return out


class FakeStt:
    def __init__(self) -> None:
        self.calls: list[tuple[bytes, str, str, str | None]] = []
        self.error: Exception | None = None

    async def transcribe(self, audio, filename, content_type, language):
        self.calls.append((audio, filename, content_type, language))
        if self.error:
            raise self.error
        return UTranscribeResponse(text="два яйца", language=language or "ru", duration_s=2.0)


def add(name="гречка", query="buckwheat cooked", grams=200, conf=0.9, q=None) -> UParsedItem:
    return UParsedItem(action="add", name=name, query_en=query, grams=grams, confidence=conf, clarify_question=q)


def update(target_id, name, grams, conf=0.9, query=None, q=None) -> UParsedItem:
    return UParsedItem(action="update", target_id=str(target_id), name=name, query_en=query, grams=grams,
                       confidence=conf, clarify_question=q)


def remove(target_id, name="x") -> UParsedItem:
    return UParsedItem(action="remove", target_id=str(target_id), name=name, grams=0, confidence=0.9)


def message(text="гречка 200 г", *, client_id=None, day=DAY, at=AT, **extra) -> dict:
    return {"client_id": str(client_id or uuid.uuid4()), "text": text, "day": day, "eaten_at": at, **extra}


class Env:
    """Everything a test needs, bundled."""

    def __init__(self) -> None:
        self.clock, self.mailer, self.ai, self.nutrition, self.stt = Clock(), FakeMailer(), FakeAi(), FakeNutrition(), FakeStt()
        self.settings = Settings(database_url="sqlite+aiosqlite://", jwt_secret=SECRET)
        self.app = create_app(self.settings, mailer=self.mailer, ai=self.ai, nutrition=self.nutrition,
                              stt=self.stt, clock=self.clock)
        self.client: TestClient | None = None

    def login(self, email: str = "user@example.com") -> dict[str, str]:
        """Full email-code login; returns Authorization headers."""
        r = self.client.post("/v1/auth/request-code", json={"email": email})
        assert r.status_code == 204, r.text
        r = self.client.post("/v1/auth/verify", json={"email": email, "code": self.mailer.last_code})
        assert r.status_code == 200, r.text
        self.clock.advance(seconds=61)  # so the next login by the same address is not rate limited
        return {"Authorization": f"Bearer {r.json()['access_token']}"}

    def post_message(self, headers, text="гречка 200 г", **kw):
        return self.client.post("/v1/messages", json=message(text, **kw), headers=headers)


@pytest.fixture()
def env():
    e = Env()
    with TestClient(e.app) as client:
        e.client = client
        yield e


@pytest.fixture()
def auth(env):
    return env.login()
