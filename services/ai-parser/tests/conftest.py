from __future__ import annotations

import json

import pytest

from ai_parser.cache import MemoryCache
from ai_parser.config import Settings
from ai_parser.llm import LLMResult
from ai_parser.parser import Parser
from ai_parser.schemas import ParseContext, ParseRequest


class FakeLLM:
    """Plays back scripted outcomes: an LLMResult / dict payload is returned, an exception is raised."""

    def __init__(self, *outcomes) -> None:
        self.outcomes = list(outcomes)
        self.calls: list[dict] = []

    async def complete(self, messages, tools, tool_name):
        self.calls.append({"messages": messages, "tools": tools, "tool_name": tool_name})
        outcome = self.outcomes.pop(0) if len(self.outcomes) > 1 else self.outcomes[0]
        if isinstance(outcome, Exception):
            raise outcome
        if isinstance(outcome, dict):
            return LLMResult(tool_arguments=json.dumps(outcome, ensure_ascii=False), content=None)
        return outcome


def item(name="гречка", grams=200, confidence=0.9, **extra):
    return {"action": "add", "name": name, "query_en": "buckwheat cooked", "grams": grams,
            "confidence": confidence, **extra}


def payload(*items):
    return {"items": list(items)}


def req(text="гречка 200 г", **ctx) -> ParseRequest:
    return ParseRequest(text=text, context=ParseContext(**ctx))


@pytest.fixture()
def settings() -> Settings:
    return Settings(deepseek_api_key="sk-test", retries=2, retry_backoff_s=0.5)


@pytest.fixture()
def sleeps() -> list[float]:
    return []


@pytest.fixture()
def make_parser(settings, sleeps):
    def factory(llm, cache=None) -> Parser:
        async def fake_sleep(seconds: float) -> None:
            sleeps.append(seconds)

        return Parser(llm, cache or MemoryCache(), settings, sleep=fake_sleep)

    return factory


@pytest.fixture()
def anyio_backend():
    return "asyncio"
