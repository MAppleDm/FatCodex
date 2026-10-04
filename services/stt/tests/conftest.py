from __future__ import annotations

import threading
import time

import pytest

from stt.transcriber import Transcription


class FakeTranscriber:
    """Stands in for WhisperTranscriber in HTTP tests."""

    def __init__(self, result: Transcription | None = None, error: Exception | None = None,
                 loaded: bool = True, delay: float = 0.0) -> None:
        self.result = result or Transcription(text="две вареные яйца", language="ru", duration_s=2.5)
        self.error = error
        self._loaded = loaded
        self.delay = delay
        self.calls: list[tuple[bytes, str | None]] = []
        self.active = 0
        self.max_active = 0
        self._lock = threading.Lock()

    @property
    def loaded(self) -> bool:
        return self._loaded

    def load(self) -> None:
        self._loaded = True

    def transcribe(self, audio, language):
        with self._lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        try:
            self.calls.append((audio.read(), language))
            time.sleep(self.delay)
            if self.error:
                raise self.error
            return self.result
        finally:
            with self._lock:
                self.active -= 1


@pytest.fixture()
def anyio_backend():
    return "asyncio"
