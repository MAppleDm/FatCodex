"""Public API models. These define the OpenAPI contract in /contracts/stt.openapi.json."""

from __future__ import annotations

from pydantic import BaseModel, Field


class TranscribeResponse(BaseModel):
    text: str = Field(description="Recognised speech; empty when nothing intelligible was said")
    language: str = Field(description="ISO 639-1 code used or detected")
    duration_s: float = Field(description="Length of the audio in seconds")


class ErrorDetail(BaseModel):
    code: str = Field(
        description="audio_too_large | audio_too_long | audio_unreadable | stt_unavailable | stt_internal"
    )
    message: str


class ErrorResponse(BaseModel):
    error: ErrorDetail


class Health(BaseModel):
    status: str
    model: str
    loaded: bool = Field(description="False until the model has been loaded (first start downloads it)")
