"""Speech recognition behind a small interface, so the HTTP layer is testable without a model."""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from dataclasses import dataclass
from typing import BinaryIO, Protocol

from .config import Settings

log = logging.getLogger(__name__)

SAMPLE_RATE = 16_000


class STTError(Exception):
    code = "stt_error"
    status = 500
    message = "Speech recognition failed"

    def __init__(self, detail: str | None = None) -> None:
        super().__init__(detail or self.message)
        self.detail = detail or self.message


class AudioTooLong(STTError):
    code, status, message = "audio_too_long", 413, "The recording is too long"


class AudioUnreadable(STTError):
    code, status, message = "audio_unreadable", 422, "The audio could not be decoded"


class ModelUnavailable(STTError):
    code, status, message = "stt_unavailable", 503, "Speech recognition is not ready yet"


@dataclass(frozen=True, slots=True)
class Transcription:
    text: str
    language: str
    duration_s: float


class Transcriber(Protocol):
    @property
    def loaded(self) -> bool: ...
    def load(self) -> None: ...
    def transcribe(self, audio: BinaryIO, language: str | None) -> Transcription: ...


def _default_model_factory(s: Settings):
    from faster_whisper import WhisperModel

    return WhisperModel(
        s.model,
        device="cpu",
        compute_type=s.compute_type,
        cpu_threads=s.cpu_threads,
        download_root=s.model_dir,
    )


def _default_decoder(audio: BinaryIO):
    from faster_whisper.audio import decode_audio

    return decode_audio(audio, sampling_rate=SAMPLE_RATE)


def _decode_error_types() -> tuple[type[BaseException], ...]:
    """What a bad recording can raise while decoding. Anything else is a bug or a broken install
    and must surface as a server error, not be blamed on the user's audio."""
    types: tuple[type[BaseException], ...] = (ValueError, OSError, EOFError)
    try:
        from av.error import FFmpegError
    except ImportError:
        return types
    return (FFmpegError, *types)


class WhisperTranscriber:
    """faster-whisper on CPU. The model loads once, on first use or via `load()`."""

    def __init__(
        self,
        settings: Settings,
        model_factory: Callable[[Settings], object] = _default_model_factory,
        decoder: Callable[[BinaryIO], object] = _default_decoder,
    ) -> None:
        self._s = settings
        self._factory = model_factory
        self._decode = decoder
        self._model = None
        self._lock = threading.Lock()

    @property
    def loaded(self) -> bool:
        return self._model is not None

    def load(self) -> None:
        self._get_model()

    def _get_model(self):
        with self._lock:
            if self._model is None:
                log.info("loading whisper model %r", self._s.model)
                try:
                    self._model = self._factory(self._s)
                except Exception as e:  # noqa: BLE001 - e.g. no network for the first download
                    log.exception("model load failed")
                    raise ModelUnavailable(type(e).__name__) from e
            return self._model

    def transcribe(self, audio: BinaryIO, language: str | None) -> Transcription:
        try:
            samples = self._decode(audio)
        except _decode_error_types() as e:
            raise AudioUnreadable(type(e).__name__) from e

        duration = len(samples) / SAMPLE_RATE
        if duration <= 0:
            raise AudioUnreadable("no audio samples")
        if duration > self._s.max_seconds:
            raise AudioTooLong(f"{duration:.0f}s > {self._s.max_seconds:.0f}s")

        model = self._get_model()
        segments, info = model.transcribe(
            samples,
            language=language or self._s.language or None,
            beam_size=self._s.beam_size,
            vad_filter=True,  # skips silence, which is where whisper tends to hallucinate
            condition_on_previous_text=False,
        )
        text = " ".join(seg.text.strip() for seg in segments).strip()
        return Transcription(text=text, language=info.language, duration_s=round(duration, 2))
