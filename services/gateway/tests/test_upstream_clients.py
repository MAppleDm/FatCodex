"""The HTTP clients for ai-parser / nutrition / stt, against a mocked transport."""

import json

import httpx
import pytest

from gateway.errors import ApiError
from gateway.upstream import (
    HttpAiParser,
    HttpNutrition,
    HttpStt,
    ULookupItem,
    UParseContext,
    UParseRequest,
)

pytestmark = pytest.mark.anyio


@pytest.fixture()
def anyio_backend():
    return "asyncio"


def client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def error_body(code):
    return {"error": {"code": code, "message": "internal detail"}}


# ---------- ai-parser ----------

async def test_parse_sends_the_request_and_reads_the_answer():
    seen = {}

    def handler(request):
        seen["url"], seen["body"] = str(request.url), json.loads(request.content)
        return httpx.Response(200, json={
            "items": [{"action": "add", "name": "гречка", "query_en": "buckwheat cooked", "grams": 200,
                       "confidence": 0.9, "clarify_question": None}],
            "clarify_question": None, "cached": True})

    ai = HttpAiParser("http://ai-parser:8000/", client(handler))
    out = await ai.parse(UParseRequest(text="гречка 200", context=UParseContext(local_time="08:15")))
    assert seen["url"] == "http://ai-parser:8000/v1/parse"
    assert seen["body"]["text"] == "гречка 200" and seen["body"]["context"]["local_time"] == "08:15"
    assert "image_base64" not in seen["body"]  # None fields are not sent
    assert out.items[0].name == "гречка" and out.items[0].grams == 200


@pytest.mark.parametrize(
    "code, status",
    [("llm_timeout", 504), ("llm_unavailable", 503), ("llm_bad_output", 502), ("llm_rejected", 422), ("llm_auth", 502)],
)
async def test_parser_errors_are_translated(code, status):
    ai = HttpAiParser("http://p", client(lambda r: httpx.Response(status, json=error_body(code))))
    with pytest.raises(ApiError) as e:
        await ai.parse(UParseRequest(text="x"))
    assert (e.value.status, e.value.code) == (status, code)
    assert "internal detail" not in e.value.message  # the upstream text is never shown to users
    assert any("а" <= ch <= "я" for ch in e.value.message.lower())  # it is Russian


async def test_unknown_parser_failure_becomes_unavailable():
    ai = HttpAiParser("http://p", client(lambda r: httpx.Response(500, text="<html>oops</html>")))
    with pytest.raises(ApiError) as e:
        await ai.parse(UParseRequest(text="x"))
    assert (e.value.status, e.value.code) == (503, "parser_unavailable")


async def test_parser_timeout_and_connection_errors():
    def slow(request):
        raise httpx.ReadTimeout("slow", request=request)

    def down(request):
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(ApiError) as e:
        await HttpAiParser("http://p", client(slow)).parse(UParseRequest(text="x"))
    assert (e.value.status, e.value.code) == (504, "llm_timeout")
    with pytest.raises(ApiError) as e:
        await HttpAiParser("http://p", client(down)).parse(UParseRequest(text="x"))
    assert (e.value.status, e.value.code) == (503, "parser_unavailable")


# ---------- nutrition ----------

async def test_lookup_posts_items_and_returns_results():
    seen = {}

    def handler(request):
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"items": [
            {"name": "гречка", "grams": 200, "matched": True, "score": 1.0,
             "food": {"id": 1, "source": "usda_sr_legacy", "name": "Buckwheat", "brand": None, "derived": False,
                      "per100g": {"kcal": 92, "protein": 3.38, "fat": 0.62, "carbs": 19.94}},
             "nutrients": {"kcal": 184, "protein": 6.8, "fat": 1.2, "carbs": 39.9}, "alternatives": []},
            {"name": "суши", "grams": 100, "matched": False, "score": 0.0, "food": None, "nutrients": None,
             "alternatives": ["Fish, raw"]},
        ], "totals": {"kcal": 184, "protein": 6.8, "fat": 1.2, "carbs": 39.9}})

    out = await HttpNutrition("http://n", client(handler)).lookup([
        ULookupItem(name="гречка", query="buckwheat cooked", grams=200), ULookupItem(name="суши", grams=100)])
    assert seen["body"] == {"items": [{"name": "гречка", "query": "buckwheat cooked", "grams": 200},
                                      {"name": "суши", "grams": 100}]}
    assert out[0].matched and out[0].food.per100g.kcal == 92 and out[0].nutrients.kcal == 184
    assert not out[1].matched and out[1].food is None and out[1].alternatives == ["Fish, raw"]


@pytest.mark.parametrize("failure", ["status", "timeout", "connect"])
async def test_nutrition_failures_are_reported_as_unavailable(failure):
    def handler(request):
        if failure == "timeout":
            raise httpx.ReadTimeout("slow", request=request)
        if failure == "connect":
            raise httpx.ConnectError("refused", request=request)
        return httpx.Response(500)

    with pytest.raises(ApiError) as e:
        await HttpNutrition("http://n", client(handler)).lookup([ULookupItem(name="x", grams=1)])
    assert (e.value.status, e.value.code) == (503, "nutrition_unavailable")


# ---------- stt ----------

async def test_stt_uploads_multipart():
    seen = {}

    def handler(request):
        seen["url"], seen["type"], seen["body"] = str(request.url), request.headers["content-type"], request.content
        return httpx.Response(200, json={"text": "привет", "language": "ru", "duration_s": 1.5})

    out = await HttpStt("http://s", client(handler)).transcribe(b"AUDIO", "n.m4a", "audio/mp4", "ru")
    assert seen["url"] == "http://s/v1/transcribe" and seen["type"].startswith("multipart/form-data")
    assert b"AUDIO" in seen["body"] and b'name="language"' in seen["body"] and b"ru" in seen["body"]
    assert out.text == "привет"


async def test_stt_language_is_omitted_when_not_given():
    seen = {}

    def handler(request):
        seen["body"] = request.content
        return httpx.Response(200, json={"text": "", "language": "ru", "duration_s": 1.0})

    await HttpStt("http://s", client(handler)).transcribe(b"A", "n.wav", "audio/wav", None)
    assert b'name="language"' not in seen["body"]


@pytest.mark.parametrize(
    "code, status",
    [("audio_too_large", 413), ("audio_too_long", 413), ("audio_unreadable", 422), ("stt_unavailable", 503),
     ("stt_internal", 502)],
)
async def test_stt_errors_are_translated(code, status):
    stt = HttpStt("http://s", client(lambda r: httpx.Response(status, json=error_body(code))))
    with pytest.raises(ApiError) as e:
        await stt.transcribe(b"A", "n.wav", "audio/wav", None)
    assert (e.value.status, e.value.code) == (status, code)


async def test_stt_connection_error():
    def down(request):
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(ApiError) as e:
        await HttpStt("http://s", client(down)).transcribe(b"A", "n.wav", "audio/wav", None)
    assert (e.value.status, e.value.code) == (503, "stt_unavailable")
