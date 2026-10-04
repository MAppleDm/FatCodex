"""Prompt/tool invariants, cache keys and the HTTP layer."""

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from ai_parser.cache import MemoryCache, RedisCache, make_key
from ai_parser.config import Settings
from ai_parser.llm import LLMAuth, LLMBadOutput, LLMRejected, LLMTimeout, LLMUnavailable
from ai_parser.main import create_app
from ai_parser.prompts import SYSTEM_PROMPT, TOOL_NAME, TOOL_SPEC, build_messages

from .conftest import FakeLLM, item, payload, req


# ---------- prompts and tool ----------

def property_names(schema) -> set[str]:
    names: set[str] = set()
    if isinstance(schema, dict):
        names |= set(schema.get("properties", {}))
        for v in schema.values():
            names |= property_names(v)
    elif isinstance(schema, list):
        for v in schema:
            names |= property_names(v)
    return names


def test_tool_schema_has_no_nutrition_fields():
    names = property_names(TOOL_SPEC)
    assert {"action", "target_id", "name", "query_en", "grams", "confidence", "clarify_question"} <= names
    assert not {n for n in names if any(w in n.lower() for w in ("kcal", "calor", "protein", "fat", "carb"))}


def test_tool_requires_the_essential_fields():
    item_schema = TOOL_SPEC["function"]["parameters"]["properties"]["items"]["items"]
    assert set(item_schema["required"]) == {"action", "name", "grams", "confidence"}
    assert TOOL_SPEC["function"]["name"] == TOOL_NAME


def test_system_prompt_forbids_inventing_calories():
    assert "Never estimate or mention calories" in SYSTEM_PROMPT


def test_messages_text_only():
    msgs = build_messages(req("овсянка 200 г"))
    assert msgs[0]["role"] == "system"
    parts = msgs[1]["content"]
    assert len(parts) == 1 and "message: овсянка 200 г" in parts[0]["text"]


def test_messages_with_photo_only():
    from ai_parser.schemas import ParseRequest

    parts = build_messages(ParseRequest(image_base64="aGk="))[1]["content"]
    assert "(photo only)" in parts[0]["text"]
    assert parts[1]["image_url"]["url"] == "data:image/jpeg;base64,aGk="


# ---------- cache ----------

def test_cache_key_is_stable_and_sensitive():
    base = make_key(req("a"), "m1")
    assert make_key(req("a"), "m1") == base
    assert make_key(req("b"), "m1") != base
    assert make_key(req("a"), "m2") != base
    assert make_key(req("a", local_time="10:00"), "m1") != base


class FakeRedis:
    def __init__(self, fail=False):
        self.store, self.ttl, self.fail = {}, {}, fail

    async def get(self, key):
        if self.fail:
            raise ConnectionError("down")
        return self.store.get(key)

    async def set(self, key, value, ex=None):
        if self.fail:
            raise ConnectionError("down")
        self.store[key], self.ttl[key] = value, ex


@pytest.mark.anyio
async def test_redis_cache_roundtrip_sets_ttl():
    fake = FakeRedis()
    cache = RedisCache(fake)
    await cache.set("k", "v", 60)
    assert fake.ttl["k"] == 60
    assert await cache.get("k") == "v"
    assert await cache.get("missing") is None


@pytest.mark.anyio
async def test_redis_failures_are_swallowed():
    cache = RedisCache(FakeRedis(fail=True))
    assert await cache.get("k") is None
    await cache.set("k", "v", 60)  # must not raise


# ---------- HTTP ----------

def client_for(llm, **settings):
    app = create_app(Settings(deepseek_api_key="sk-test", retries=0, **settings), llm=llm, cache=MemoryCache())
    return TestClient(app)


def test_parse_endpoint_ok():
    with client_for(FakeLLM(payload(item("суп", 300, 0.4, clarify_question="Какой суп?")))) as c:
        r = c.post("/v1/parse", json={"text": "суп"})
    assert r.status_code == 200
    body = r.json()
    assert body["clarify_question"] == "Какой суп?"
    assert body["items"][0]["name"] == "суп" and body["cached"] is False


def test_parse_endpoint_cache_flag():
    with client_for(FakeLLM(payload(item()))) as c:
        c.post("/v1/parse", json={"text": "гречка"})
        assert c.post("/v1/parse", json={"text": "гречка"}).json()["cached"] is True


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"text": "   "},
        {"text": "x" * 2001},
        {"image_base64": "***not base64***"},
        {"text": "x", "image_mime": "image/gif"},
        {"text": "x", "context": {"local_time": "8:30"}},
        {"text": "x", "context": {"entries": [{"id": "", "name": "a", "grams": 1}]}},
    ],
)
def test_parse_endpoint_validates_input(body):
    with client_for(FakeLLM(payload())) as c:
        assert c.post("/v1/parse", json=body).status_code == 422


@pytest.mark.parametrize(
    "error, status, code",
    [
        (LLMTimeout(), 504, "llm_timeout"),
        (LLMUnavailable(), 503, "llm_unavailable"),
        (LLMBadOutput(), 502, "llm_bad_output"),
        (LLMAuth("HTTP 401"), 502, "llm_auth"),
        (LLMRejected("HTTP 400"), 422, "llm_rejected"),
    ],
)
def test_llm_errors_become_structured_http_errors(error, status, code):
    with client_for(FakeLLM(error)) as c:
        r = c.post("/v1/parse", json={"text": "гречка"})
    assert r.status_code == status
    assert r.json()["error"]["code"] == code
    assert r.json()["error"]["message"]
    assert "HTTP 401" not in r.text  # internal detail stays in the log


def test_healthz_reports_configuration():
    with client_for(FakeLLM(payload())) as c:
        assert c.get("/healthz").json() == {"status": "ok", "model": "deepseek-flash", "configured": True,
                                            "cache": "memory"}
    app = create_app(Settings(deepseek_api_key=""))
    with TestClient(app) as c:
        h = c.get("/healthz").json()
    assert h["configured"] is False and h["cache"] == "none"


def test_unconfigured_service_answers_with_a_clear_error():
    with TestClient(create_app(Settings(deepseek_api_key=""))) as c:
        r = c.post("/v1/parse", json={"text": "гречка"})
    assert r.status_code == 502 and r.json()["error"]["code"] == "llm_auth"


# ---------- contract ----------

CONTRACT = Path(__file__).resolve().parents[3] / "contracts" / "ai-parser.openapi.json"


def test_openapi_matches_committed_contract():
    import os

    schema = create_app(Settings()).openapi()
    rendered = json.dumps(schema, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    if os.environ.get("UPDATE_CONTRACTS"):
        CONTRACT.parent.mkdir(parents=True, exist_ok=True)
        CONTRACT.write_text(rendered, encoding="utf-8")
    assert CONTRACT.exists(), "contract missing: run with UPDATE_CONTRACTS=1"
    assert CONTRACT.read_text(encoding="utf-8") == rendered
