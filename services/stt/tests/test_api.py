import asyncio
import json
import os
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from stt.config import Settings
from stt.main import create_app
from stt.transcriber import AudioTooLong, AudioUnreadable, ModelUnavailable, Transcription

from .conftest import FakeTranscriber

WAV = ("note.wav", b"RIFF....fake-audio", "audio/wav")


def client_for(fake, **settings):
    settings.setdefault("preload", False)
    return TestClient(create_app(Settings(**settings), transcriber=fake))


def test_transcribe_returns_text():
    fake = FakeTranscriber()
    with client_for(fake) as c:
        r = c.post("/v1/transcribe", files={"audio": WAV})
    assert r.status_code == 200
    assert r.json() == {"text": "две вареные яйца", "language": "ru", "duration_s": 2.5}
    assert fake.calls == [(b"RIFF....fake-audio", None)]


def test_language_form_field_is_forwarded():
    fake = FakeTranscriber()
    with client_for(fake) as c:
        assert c.post("/v1/transcribe", files={"audio": WAV}, data={"language": "en"}).status_code == 200
    assert fake.calls[0][1] == "en"


@pytest.mark.parametrize("language", ["RU", "russian", "r", "ru-RU"])
def test_bad_language_code_is_rejected(language):
    fake = FakeTranscriber()
    with client_for(fake) as c:
        r = c.post("/v1/transcribe", files={"audio": WAV}, data={"language": language})
    assert r.status_code == 422
    assert fake.calls == []


def test_missing_file_is_rejected():
    with client_for(FakeTranscriber()) as c:
        assert c.post("/v1/transcribe").status_code == 422


def test_oversized_upload_is_rejected_without_calling_the_model():
    fake = FakeTranscriber()
    with client_for(fake, max_bytes=1000) as c:
        r = c.post("/v1/transcribe", files={"audio": ("big.wav", b"x" * 1001, "audio/wav")})
        ok = c.post("/v1/transcribe", files={"audio": ("ok.wav", b"x" * 1000, "audio/wav")})
    assert r.status_code == 413 and r.json()["error"]["code"] == "audio_too_large"
    assert ok.status_code == 200
    assert len(fake.calls) == 1


@pytest.mark.parametrize(
    "error, status, code",
    [
        (AudioTooLong("70s > 60s"), 413, "audio_too_long"),
        (AudioUnreadable("InvalidDataError"), 422, "audio_unreadable"),
        (ModelUnavailable("OSError"), 503, "stt_unavailable"),
    ],
)
def test_domain_errors_become_structured_http_errors(error, status, code):
    with client_for(FakeTranscriber(error=error)) as c:
        r = c.post("/v1/transcribe", files={"audio": WAV})
    assert r.status_code == status
    assert r.json()["error"]["code"] == code
    assert "70s" not in r.text and "InvalidDataError" not in r.text  # internals stay in the log


def test_unexpected_errors_are_json_500_without_internals():
    fake = FakeTranscriber(error=TypeError("unexpected keyword argument 'metadata_errors'"))
    app = create_app(Settings(preload=False), transcriber=fake)
    with TestClient(app, raise_server_exceptions=False) as c:
        r = c.post("/v1/transcribe", files={"audio": WAV})
    assert r.status_code == 500
    assert r.json()["error"]["code"] == "stt_internal"
    assert "metadata_errors" not in r.text


def test_healthz_reports_model_state():
    with client_for(FakeTranscriber(loaded=False), model="small") as c:
        assert c.get("/healthz").json() == {"status": "ok", "model": "small", "loaded": False}
    with client_for(FakeTranscriber(loaded=True)) as c:
        assert c.get("/healthz").json()["loaded"] is True


@pytest.mark.anyio
async def test_transcriptions_are_serialised_by_the_concurrency_limit():
    fake = FakeTranscriber(delay=0.05)
    app = create_app(Settings(preload=False, concurrency=1), transcriber=fake)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
            rs = await asyncio.gather(*[c.post("/v1/transcribe", files={"audio": WAV}) for _ in range(4)])
    assert all(r.status_code == 200 for r in rs)
    assert fake.max_active == 1


@pytest.mark.anyio
async def test_concurrency_limit_is_configurable():
    fake = FakeTranscriber(delay=0.1)
    app = create_app(Settings(preload=False, concurrency=2), transcriber=fake)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
            await asyncio.gather(*[c.post("/v1/transcribe", files={"audio": WAV}) for _ in range(4)])
    assert fake.max_active == 2


@pytest.mark.anyio
async def test_model_is_preloaded_in_the_background():
    fake = FakeTranscriber(loaded=False)
    app = create_app(Settings(preload=True), transcriber=fake)
    async with app.router.lifespan_context(app):
        await app.state.preload_task
        assert fake.loaded is True


def test_transcription_result_type_is_what_the_endpoint_serialises():
    t = Transcription(text="a", language="en", duration_s=1.0)
    with client_for(FakeTranscriber(result=t)) as c:
        assert c.post("/v1/transcribe", files={"audio": WAV}).json() == {"text": "a", "language": "en", "duration_s": 1.0}


CONTRACT = Path(__file__).resolve().parents[3] / "contracts" / "stt.openapi.json"


def test_openapi_matches_committed_contract():
    schema = create_app(Settings(preload=False)).openapi()
    rendered = json.dumps(schema, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    if os.environ.get("UPDATE_CONTRACTS"):
        CONTRACT.parent.mkdir(parents=True, exist_ok=True)
        CONTRACT.write_text(rendered, encoding="utf-8")
    assert CONTRACT.exists(), "contract missing: run with UPDATE_CONTRACTS=1"
    assert CONTRACT.read_text(encoding="utf-8") == rendered
