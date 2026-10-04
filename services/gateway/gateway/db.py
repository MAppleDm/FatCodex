"""Tables. Portable on purpose (Uuid, JSON-free, plain timestamps) so tests can use SQLite.

Schema is created with create_all at startup; there are no migrations yet. Adding a column to an
existing deployment needs a manual ALTER TABLE (or Alembic, if this ever grows).
"""

from __future__ import annotations

import uuid
from datetime import date, datetime, timezone

from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKey, Index, Integer, String, Text, Uuid
from sqlalchemy.engine import Dialect
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import TypeDecorator


class UTCDateTime(TypeDecorator):
    """Always timezone-aware UTC in Python, whatever the database hands back (SQLite drops tzinfo)."""

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect: Dialect) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("naive datetime is not allowed")
        return value.astimezone(timezone.utc)

    def process_result_value(self, value: datetime | None, dialect: Dialect) -> datetime | None:
        if value is None:
            return None
        return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(254), unique=True, index=True)
    calorie_goal: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)


class LoginCode(Base):
    __tablename__ = "login_codes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    email: Mapped[str] = mapped_column(String(254), index=True)
    code_hash: Mapped[str] = mapped_column(String(64))
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    consumed: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)


class Entry(Base):
    """One food item in the diary. A chat message can produce several (they share meal_id)."""

    __tablename__ = "entries"
    __table_args__ = (
        Index("ix_entries_user_updated", "user_id", "updated_at"),
        Index("ix_entries_user_day", "user_id", "day"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id"), index=True)
    meal_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, nullable=True)
    day: Mapped[date] = mapped_column(Date)  # the user's local date, as sent by the client
    eaten_at: Mapped[datetime] = mapped_column(UTCDateTime)
    local_minutes: Mapped[int] = mapped_column(Integer, default=0)  # local time of day, for "как обычно"
    position: Mapped[int] = mapped_column(Integer, default=0)  # order within one message

    name: Mapped[str] = mapped_column(String(200))
    query_en: Mapped[str | None] = mapped_column(String(100), nullable=True)
    grams: Mapped[float] = mapped_column(Float)
    food_name: Mapped[str | None] = mapped_column(String(300), nullable=True)  # what nutrition matched

    # Per 100 g, so the client can rescale offline with the same arithmetic. Null when unmatched.
    kcal100: Mapped[float | None] = mapped_column(Float, nullable=True)
    protein100: Mapped[float | None] = mapped_column(Float, nullable=True)
    fat100: Mapped[float | None] = mapped_column(Float, nullable=True)
    carbs100: Mapped[float | None] = mapped_column(Float, nullable=True)
    kcal: Mapped[float | None] = mapped_column(Float, nullable=True)
    protein: Mapped[float | None] = mapped_column(Float, nullable=True)
    fat: Mapped[float | None] = mapped_column(Float, nullable=True)
    carbs: Mapped[float | None] = mapped_column(Float, nullable=True)

    status: Mapped[str] = mapped_column(String(16))  # ok | uncertain | unmatched
    confidence: Mapped[float] = mapped_column(Float, default=1.0)
    source: Mapped[str] = mapped_column(String(16), default="text")  # text | voice | photo

    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime)
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)


class Weight(Base):
    __tablename__ = "weights"
    __table_args__ = (Index("ix_weights_user_updated", "user_id", "updated_at"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id"), index=True)
    day: Mapped[date] = mapped_column(Date)
    kg: Mapped[float] = mapped_column(Float)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime)
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime, nullable=True)


class ProcessedMessage(Base):
    """Makes POST /v1/messages idempotent: a retry of the same client_id gets the stored answer back
    instead of applying "масла было меньше" a second time."""

    __tablename__ = "processed_messages"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id"), index=True)
    response_json: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
