"""Orchestration: cache -> LLM (with retries) -> validation -> at most one clarifying question.

The model only identifies foods and amounts. Whatever it returns is treated as untrusted input
and normalised here before anything leaves the service.
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import re
from collections.abc import Awaitable, Callable

from .cache import Cache, make_key
from .config import Settings
from .llm import LLMBadOutput, LLMClient, LLMError, LLMResult
from .prompts import TOOL_SPEC, TOOL_NAME, build_messages
from .schemas import MAX_GRAMS, ParsedItem, ParseRequest, ParseResponse

log = logging.getLogger(__name__)

ACTIONS = ("add", "update", "remove")
UNCLEAR_TARGET_QUESTION = "Не понял, какую запись поправить. Уточни, пожалуйста."
_FENCE = re.compile(r"```(?:json)?\s*(.*?)```", re.DOTALL)


def _clean_str(value: object, limit: int) -> str | None:
    if not isinstance(value, str):
        return None
    value = " ".join(value.split())[:limit].strip()
    return value or None


def _number(value: object) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, str):
        try:
            value = float(value.replace(",", "."))
        except ValueError:
            return None
    if isinstance(value, (int, float)) and math.isfinite(value):
        return float(value)
    return None


def _loads(raw: str) -> object | None:
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        start, end = raw.find("{"), raw.rfind("}")
        if 0 <= start < end:
            try:
                return json.loads(raw[start : end + 1])
            except json.JSONDecodeError:
                return None
        return None


def extract_items(result: LLMResult) -> list:
    """Raw item list from the forced tool call, or from JSON in plain content as a fallback."""
    candidates: list[str] = []
    if result.tool_arguments:
        candidates.append(result.tool_arguments)
    if result.content:
        fenced = _FENCE.search(result.content)
        candidates.append(fenced.group(1) if fenced else result.content)
    for raw in candidates:
        data = _loads(raw)
        if isinstance(data, list):
            return data
        if isinstance(data, dict) and isinstance(data.get("items"), list):
            return data["items"]
    raise LLMBadOutput("no items payload in model output")


def _fallback_question(item: ParsedItem) -> str:
    if item.action == "add":
        return f"Уточни, пожалуйста: сколько граммов «{item.name}»?"
    return f"Уточни, пожалуйста, что именно поправить в «{item.name}»?"


def normalize(raw_items: list, req: ParseRequest, threshold: float) -> ParseResponse:
    entries = {e.id: e for e in req.context.entries}
    items: list[ParsedItem] = []
    bad_target = False

    for raw in raw_items:
        if not isinstance(raw, dict):
            continue
        action = str(raw.get("action") or "add").strip().lower()
        if action not in ACTIONS:
            continue

        target_id = _clean_str(raw.get("target_id"), 64)
        entry = entries.get(target_id) if target_id else None
        if action != "add" and entry is None:
            bad_target = True  # update/remove of something that is not in the diary: never guess
            continue

        name = _clean_str(raw.get("name"), 100) or (entry.name if entry else None)
        if not name:
            continue

        if action == "remove":
            grams = 0.0
        else:
            grams = _number(raw.get("grams"))
            if grams is None or not 0 < grams <= MAX_GRAMS:
                continue

        confidence = _number(raw.get("confidence"))
        confidence = 0.5 if confidence is None else min(1.0, max(0.0, confidence))
        query = _clean_str(raw.get("query_en"), 100)

        items.append(
            ParsedItem(
                action=action,
                target_id=target_id if action != "add" else None,
                name=name,
                query_en=query.lower() if query else None,
                grams=grams,
                confidence=confidence,
                clarify_question=_clean_str(raw.get("clarify_question"), 200),
            )
        )

    if raw_items and not items and not bad_target:
        raise LLMBadOutput("every item in the model output was invalid")

    question: str | None = None
    low = [i for i, item in enumerate(items) if item.confidence < threshold]
    if low:
        chosen = min(low, key=lambda i: items[i].confidence)
        question = items[chosen].clarify_question or _fallback_question(items[chosen])
        items = [
            item.model_copy(update={"clarify_question": question if i == chosen else None})
            for i, item in enumerate(items)
        ]
    else:
        items = [item.model_copy(update={"clarify_question": None}) for item in items]
    if bad_target and question is None:
        question = UNCLEAR_TARGET_QUESTION

    return ParseResponse(items=items, clarify_question=question)


class Parser:
    def __init__(
        self,
        llm: LLMClient,
        cache: Cache,
        settings: Settings,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._llm = llm
        self._cache = cache
        self._settings = settings
        self._sleep = sleep

    async def parse(self, req: ParseRequest) -> ParseResponse:
        key = make_key(req, self._settings.deepseek_model)
        hit = await self._cache.get(key)
        if hit:
            try:
                return ParseResponse.model_validate_json(hit).model_copy(update={"cached": True})
            except ValueError:
                log.warning("discarding corrupt cache entry")

        response = await self._ask(req)
        await self._cache.set(key, response.model_dump_json(), self._settings.cache_ttl_s)
        return response

    async def _ask(self, req: ParseRequest) -> ParseResponse:
        s = self._settings
        messages = build_messages(req)
        last: LLMError | None = None
        for attempt in range(s.retries + 1):
            try:
                result = await self._llm.complete(messages, [TOOL_SPEC], TOOL_NAME)
                return normalize(extract_items(result), req, s.clarify_threshold)
            except LLMError as e:
                if not e.retryable:
                    raise
                last = e
                log.warning("llm attempt %d/%d failed: %s", attempt + 1, s.retries + 1, e.code)
                if attempt < s.retries:
                    await self._sleep(s.retry_backoff_s * 2**attempt)
        assert last is not None
        raise last
