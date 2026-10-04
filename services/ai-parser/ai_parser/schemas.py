"""Public API models. These define the OpenAPI contract in /contracts/ai-parser.openapi.json."""

from __future__ import annotations

import base64
import binascii
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator

MAX_GRAMS = 5000.0


class ContextEntry(BaseModel):
    """An entry already in today's diary. Lets the model turn "масла было меньше" into an update."""

    id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=200)
    grams: float = Field(ge=0, le=MAX_GRAMS)


class FrequentItem(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    query_en: str | None = Field(default=None, max_length=100)
    grams: float = Field(gt=0, le=MAX_GRAMS)


class FrequentMeal(BaseModel):
    """A habitual meal, for "как обычно"."""

    label: str = Field(min_length=1, max_length=100)
    items: list[FrequentItem] = Field(min_length=1, max_length=20)


class PendingQuestion(BaseModel):
    """The clarifying question the app asked last; the next message is most likely its answer."""

    question: str = Field(min_length=1, max_length=300)
    target_id: str | None = Field(default=None, max_length=64, description="Entry the question was about")


class ParseContext(BaseModel):
    entries: list[ContextEntry] = Field(default_factory=list, max_length=100)
    frequent: list[FrequentMeal] = Field(default_factory=list, max_length=10)
    pending_question: PendingQuestion | None = None
    local_time: str | None = Field(default=None, pattern=r"^\d{2}:\d{2}$", description="HH:MM, user's clock")


class ParseRequest(BaseModel):
    text: str | None = Field(default=None, max_length=2000)
    image_base64: str | None = Field(
        default=None, max_length=6_000_000, description="Photo bytes, base64 without a data: prefix"
    )
    image_mime: Literal["image/jpeg", "image/png", "image/webp"] = "image/jpeg"
    context: ParseContext = Field(default_factory=ParseContext)

    @field_validator("text")
    @classmethod
    def _strip_text(cls, v: str | None) -> str | None:
        v = v.strip() if v else None
        return v or None

    @field_validator("image_base64")
    @classmethod
    def _valid_base64(cls, v: str | None) -> str | None:
        if not v:
            return None
        try:
            base64.b64decode(v, validate=True)
        except (binascii.Error, ValueError) as e:
            raise ValueError("image_base64 is not valid base64") from e
        return v

    @model_validator(mode="after")
    def _needs_input(self) -> ParseRequest:
        if not self.text and not self.image_base64:
            raise ValueError("provide text and/or image_base64")
        return self


class ParsedItem(BaseModel):
    action: Literal["add", "update", "remove"] = Field(
        description="add: new food. update/remove: change an existing entry given by target_id"
    )
    target_id: str | None = Field(default=None, description="Entry id from context.entries (update/remove only)")
    name: str = Field(description="Short food name in the user's language")
    query_en: str | None = Field(default=None, description="English USDA-style name for nutrition lookup")
    grams: float = Field(ge=0, le=MAX_GRAMS, description="Total grams for add/update (the NEW total); 0 for remove")
    confidence: float = Field(ge=0, le=1)
    clarify_question: str | None = Field(
        default=None, description="Set on at most one item, the one the response-level question is about"
    )


class ParseResponse(BaseModel):
    items: list[ParsedItem]
    clarify_question: str | None = Field(
        default=None, description="ONE short question, present iff some item has confidence below the threshold"
    )
    cached: bool = False


class ErrorDetail(BaseModel):
    code: str = Field(description="llm_timeout | llm_unavailable | llm_bad_output | llm_rejected | llm_auth")
    message: str


class ErrorResponse(BaseModel):
    error: ErrorDetail


class Health(BaseModel):
    status: str
    model: str
    configured: bool = Field(description="False when DEEPSEEK_API_KEY is not set")
    cache: str = Field(description="redis | none")
