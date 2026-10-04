from __future__ import annotations

import asyncio
import io
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

from .config import Settings
from .schemas import ErrorResponse, Health, TranscribeResponse
from .transcriber import STTError, Transcriber, WhisperTranscriber

log = logging.getLogger(__name__)

ERROR_RESPONSES = {
    413: {"model": ErrorResponse, "description": "audio_too_large | audio_too_long"},
    422: {"model": ErrorResponse, "description": "audio_unreadable"},
    500: {"model": ErrorResponse, "description": "stt_internal"},
    503: {"model": ErrorResponse, "description": "stt_unavailable"},
}


class AudioTooLarge(STTError):
    code, status, message = "audio_too_large", 413, "The audio file is too large"


async def read_limited(upload: UploadFile, limit: int) -> bytes:
    """Read the upload in chunks and stop as soon as it exceeds `limit`."""
    buf = io.BytesIO()
    while chunk := await upload.read(64 * 1024):
        if buf.tell() + len(chunk) > limit:
            raise AudioTooLarge(f"more than {limit} bytes")
        buf.write(chunk)
    return buf.getvalue()


def create_app(settings: Settings | None = None, transcriber: Transcriber | None = None) -> FastAPI:
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        the_transcriber = transcriber or WhisperTranscriber(settings)
        app.state.transcriber = the_transcriber
        app.state.slots = asyncio.Semaphore(max(1, settings.concurrency))
        if settings.preload and not the_transcriber.loaded:
            async def preload() -> None:
                try:
                    await run_in_threadpool(the_transcriber.load)
                except STTError:
                    log.warning("preload failed; will retry on first request")

            app.state.preload_task = asyncio.create_task(preload())
        yield

    app = FastAPI(title="DietApp stt", version="0.1.0", lifespan=lifespan)

    @app.exception_handler(STTError)
    async def stt_error_handler(_: Request, exc: STTError) -> JSONResponse:
        log.warning("transcription failed: %s (%s)", exc.code, exc.detail)
        return JSONResponse(status_code=exc.status, content={"error": {"code": exc.code, "message": exc.message}})

    @app.exception_handler(Exception)
    async def unexpected_error_handler(_: Request, exc: Exception) -> JSONResponse:
        log.error("unexpected error", exc_info=exc)
        return JSONResponse(
            status_code=500, content={"error": {"code": "stt_internal", "message": STTError.message}}
        )

    @app.get("/healthz", response_model=Health, tags=["ops"])
    def healthz(request: Request) -> Health:
        return Health(status="ok", model=settings.model, loaded=request.app.state.transcriber.loaded)

    @app.post("/v1/transcribe", response_model=TranscribeResponse, responses=ERROR_RESPONSES, tags=["stt"])
    async def transcribe(
        request: Request,
        audio: UploadFile = File(description="wav, m4a/aac, mp3, ogg/opus, flac, webm, 3gp"),
        language: str | None = Form(default=None, pattern=r"^[a-z]{2,3}$", description="ISO 639-1; omit to auto-detect"),
    ) -> TranscribeResponse:
        """Transcribe one short voice note (up to STT_MAX_SECONDS)."""
        data = await read_limited(audio, settings.max_bytes)
        async with request.app.state.slots:
            result = await run_in_threadpool(request.app.state.transcriber.transcribe, io.BytesIO(data), language)
        return TranscribeResponse(text=result.text, language=result.language, duration_s=result.duration_s)

    return app


app = create_app()
