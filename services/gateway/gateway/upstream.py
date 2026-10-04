"""Clients for ai-parser, nutrition and stt.

The DTOs here are hand-written mirrors of the other services' OpenAPI schemas (see /contracts);
tests/test_upstream_contracts.py fails if they drift apart. Upstream failures are translated into
ApiError with a code the app knows and a Russian message that is safe to show.
"""

from __future__ import annotations

from typing import Literal, Protocol

import httpx
from pydantic import BaseModel, Field

from .errors import ApiError

# ---------- DTOs: ai-parser ----------


class UContextEntry(BaseModel):
    id: str
    name: str
    grams: float


class UFrequentItem(BaseModel):
    name: str
    query_en: str | None = None
    grams: float


class UFrequentMeal(BaseModel):
    label: str
    items: list[UFrequentItem]


class UPendingQuestion(BaseModel):
    question: str
    target_id: str | None = None


class UParseContext(BaseModel):
    entries: list[UContextEntry] = Field(default_factory=list)
    frequent: list[UFrequentMeal] = Field(default_factory=list)
    pending_question: UPendingQuestion | None = None
    local_time: str | None = None


class UParseRequest(BaseModel):
    text: str | None = None
    image_base64: str | None = None
    image_mime: str = "image/jpeg"
    context: UParseContext = Field(default_factory=UParseContext)


class UParsedItem(BaseModel):
    action: Literal["add", "update", "remove"]
    target_id: str | None = None
    name: str
    query_en: str | None = None
    grams: float
    confidence: float
    clarify_question: str | None = None


class UParseResponse(BaseModel):
    items: list[UParsedItem]
    clarify_question: str | None = None


# ---------- DTOs: nutrition ----------


class ULookupItem(BaseModel):
    name: str
    query: str | None = None
    grams: float


class ULookupRequest(BaseModel):
    items: list[ULookupItem]


class UNutrients(BaseModel):
    kcal: float
    protein: float
    fat: float
    carbs: float


class UFood(BaseModel):
    id: int
    source: str
    name: str
    brand: str | None = None
    per100g: UNutrients
    derived: bool = False


class UItemResult(BaseModel):
    name: str
    grams: float
    matched: bool
    score: float = 0.0
    food: UFood | None = None
    nutrients: UNutrients | None = None
    alternatives: list[str] = Field(default_factory=list)


class ULookupResponse(BaseModel):
    items: list[UItemResult]


# ---------- DTOs: stt ----------


class UTranscribeResponse(BaseModel):
    text: str
    language: str
    duration_s: float


# ---------- interfaces (faked in tests) ----------


class AiParser(Protocol):
    async def parse(self, request: UParseRequest) -> UParseResponse: ...


class Nutrition(Protocol):
    async def lookup(self, items: list[ULookupItem]) -> list[UItemResult]: ...


class Stt(Protocol):
    async def transcribe(self, audio: bytes, filename: str, content_type: str, language: str | None) -> UTranscribeResponse: ...


# ---------- error translation ----------

# upstream error code -> (HTTP status we answer with, message for the user)
PARSER_ERRORS: dict[str, tuple[int, str]] = {
    "llm_timeout": (504, "Разбор занял слишком много времени. Попробуй ещё раз."),
    "llm_unavailable": (503, "Сервис разбора сейчас недоступен. Попробуй чуть позже."),
    "llm_bad_output": (502, "Не удалось разобрать сообщение. Попробуй сформулировать иначе."),
    "llm_rejected": (422, "Не удалось обработать это сообщение. Попробуй переформулировать."),
    "llm_auth": (502, "Сервис разбора не настроен на сервере."),
}
STT_ERRORS: dict[str, tuple[int, str]] = {
    "audio_too_large": (413, "Запись слишком большая."),
    "audio_too_long": (413, "Запись слишком длинная."),
    "audio_unreadable": (422, "Не удалось прочитать запись."),
    "stt_unavailable": (503, "Распознавание речи ещё не готово. Попробуй через минуту."),
    "stt_internal": (502, "Не удалось распознать речь."),
}


def _error_code(response: httpx.Response) -> str | None:
    try:
        return response.json()["error"]["code"]
    except (ValueError, KeyError, TypeError):
        return None


def _raise_for_status(response: httpx.Response, table: dict[str, tuple[int, str]], fallback: ApiError) -> None:
    if response.is_success:
        return
    known = table.get(_error_code(response) or "")
    if known:
        raise ApiError(known[0], _error_code(response) or "", known[1])
    raise fallback


class HttpAiParser:
    def __init__(self, base_url: str, client: httpx.AsyncClient) -> None:
        self._url, self._client = base_url.rstrip("/"), client

    async def parse(self, request: UParseRequest) -> UParseResponse:
        try:
            r = await self._client.post(f"{self._url}/v1/parse", json=request.model_dump(exclude_none=True))
        except httpx.TimeoutException as e:
            raise ApiError(*_PARSER_TIMEOUT) from e
        except httpx.TransportError as e:
            raise ApiError(*_PARSER_DOWN) from e
        _raise_for_status(r, PARSER_ERRORS, ApiError(*_PARSER_DOWN))
        return UParseResponse.model_validate(r.json())


class HttpNutrition:
    def __init__(self, base_url: str, client: httpx.AsyncClient) -> None:
        self._url, self._client = base_url.rstrip("/"), client

    async def lookup(self, items: list[ULookupItem]) -> list[UItemResult]:
        body = ULookupRequest(items=items).model_dump(exclude_none=True)
        try:
            r = await self._client.post(f"{self._url}/v1/lookup", json=body)
        except httpx.TransportError as e:  # includes timeouts
            raise ApiError(*_NUTRITION_DOWN) from e
        _raise_for_status(r, {}, ApiError(*_NUTRITION_DOWN))
        return ULookupResponse.model_validate(r.json()).items


class HttpStt:
    def __init__(self, base_url: str, client: httpx.AsyncClient) -> None:
        self._url, self._client = base_url.rstrip("/"), client

    async def transcribe(self, audio: bytes, filename: str, content_type: str, language: str | None) -> UTranscribeResponse:
        try:
            r = await self._client.post(
                f"{self._url}/v1/transcribe",
                files={"audio": (filename, audio, content_type)},
                data={"language": language} if language else None,
            )
        except httpx.TransportError as e:
            raise ApiError(*_STT_DOWN) from e
        _raise_for_status(r, STT_ERRORS, ApiError(*_STT_DOWN))
        return UTranscribeResponse.model_validate(r.json())


_PARSER_TIMEOUT = (504, "llm_timeout", PARSER_ERRORS["llm_timeout"][1])
_PARSER_DOWN = (503, "parser_unavailable", PARSER_ERRORS["llm_unavailable"][1])
_NUTRITION_DOWN = (503, "nutrition_unavailable", "База продуктов сейчас недоступна. Попробуй чуть позже.")
_STT_DOWN = (503, "stt_unavailable", STT_ERRORS["stt_unavailable"][1])
