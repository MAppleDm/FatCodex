"""WhisperTranscriber with a fake model and decoder: no faster-whisper, no downloads."""

import io
from types import SimpleNamespace

import pytest

from stt.config import Settings
from stt.transcriber import (
    SAMPLE_RATE,
    AudioTooLong,
    AudioUnreadable,
    ModelUnavailable,
    WhisperTranscriber,
)


class FakeModel:
    def __init__(self, texts=(" две ", "  вареные яйца"), language="ru"):
        self.texts, self.language = texts, language
        self.calls = []

    def transcribe(self, samples, **kwargs):
        self.calls.append((samples, kwargs))
        return (SimpleNamespace(text=t) for t in self.texts), SimpleNamespace(language=self.language)


def seconds(n):
    return [0.0] * int(SAMPLE_RATE * n)


def make(model=None, samples=None, settings=None, decoder=None):
    model = model or FakeModel()
    loads = []

    def factory(s):
        loads.append(s)
        return model

    t = WhisperTranscriber(
        settings or Settings(),
        model_factory=factory,
        decoder=decoder or (lambda audio: samples if samples is not None else seconds(2)),
    )
    return t, model, loads


def test_joins_and_strips_segments_and_reports_duration():
    t, _, _ = make(samples=seconds(2.5))
    result = t.transcribe(io.BytesIO(b"x"), None)
    assert result.text == "две вареные яйца"
    assert result.language == "ru"
    assert result.duration_s == 2.5


def test_silence_gives_empty_text_not_an_error():
    t, _, _ = make(model=FakeModel(texts=()))
    assert t.transcribe(io.BytesIO(b"x"), None).text == ""


def test_decoding_options_favour_short_clips():
    t, model, _ = make(settings=Settings(beam_size=1))
    t.transcribe(io.BytesIO(b"x"), None)
    _, kw = model.calls[0]
    assert kw["beam_size"] == 1
    assert kw["vad_filter"] is True
    assert kw["condition_on_previous_text"] is False


@pytest.mark.parametrize(
    "request_lang, configured, expected",
    [("en", "ru", "en"), (None, "ru", "ru"), (None, "", None)],
)
def test_language_precedence(request_lang, configured, expected):
    t, model, _ = make(settings=Settings(language=configured))
    t.transcribe(io.BytesIO(b"x"), request_lang)
    assert model.calls[0][1]["language"] == expected


def test_too_long_audio_is_rejected_before_the_model_is_touched():
    t, model, loads = make(samples=seconds(61), settings=Settings(max_seconds=60))
    with pytest.raises(AudioTooLong):
        t.transcribe(io.BytesIO(b"x"), None)
    assert model.calls == [] and loads == []


def test_audio_at_the_limit_is_accepted():
    t, _, _ = make(samples=seconds(60), settings=Settings(max_seconds=60))
    assert t.transcribe(io.BytesIO(b"x"), None).duration_s == 60.0


def test_undecodable_audio_is_unreadable():
    def boom(audio):
        raise ValueError("Invalid data found when processing input")

    t, model, loads = make(decoder=boom)
    with pytest.raises(AudioUnreadable):
        t.transcribe(io.BytesIO(b"garbage"), None)
    assert loads == []


@pytest.mark.parametrize("error", [TypeError("unexpected keyword argument"), RuntimeError("boom"), KeyError("x")])
def test_non_decoding_errors_are_not_blamed_on_the_audio(error):
    # e.g. a PyAV/faster-whisper version mismatch: must be a server error, not "bad audio"
    def broken(audio):
        raise error

    t, _, _ = make(decoder=broken)
    with pytest.raises(type(error)):
        t.transcribe(io.BytesIO(b"x"), None)


def test_real_pyav_errors_count_as_unreadable():
    from av.error import InvalidDataError

    def bad(audio):
        raise InvalidDataError(1, "Invalid data found when processing input")

    t, _, _ = make(decoder=bad)
    with pytest.raises(AudioUnreadable):
        t.transcribe(io.BytesIO(b"x"), None)


def test_empty_audio_is_unreadable():
    t, _, _ = make(samples=[])
    with pytest.raises(AudioUnreadable):
        t.transcribe(io.BytesIO(b""), None)


def test_model_loads_lazily_and_only_once():
    t, _, loads = make()
    assert t.loaded is False and loads == []
    t.transcribe(io.BytesIO(b"x"), None)
    t.transcribe(io.BytesIO(b"x"), None)
    t.load()
    assert t.loaded is True and len(loads) == 1


def test_failed_load_is_reported_and_retried_next_time():
    attempts = []

    def flaky(s):
        attempts.append(1)
        if len(attempts) == 1:
            raise OSError("no network")
        return FakeModel()

    t = WhisperTranscriber(Settings(), model_factory=flaky, decoder=lambda a: seconds(1))
    with pytest.raises(ModelUnavailable):
        t.transcribe(io.BytesIO(b"x"), None)
    assert t.loaded is False
    assert t.transcribe(io.BytesIO(b"x"), None).text == "две вареные яйца"
    assert len(attempts) == 2
