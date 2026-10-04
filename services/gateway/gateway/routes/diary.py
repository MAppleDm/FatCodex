"""Everything behind the login: profile, messages, in-place edits, weights, sync, voice."""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, File, Form, Query, Request, Response, UploadFile
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import Entry, User, Weight
from ..deps import current_user, get_session, now
from ..errors import ApiError
from ..processing import MessageProcessor, apply_lookup, entry_out, rescale
from ..schemas import (
    EntryOut,
    EntryPatchIn,
    ErrorResponse,
    GoalIn,
    MeOut,
    MessageIn,
    MessageOut,
    SttOut,
    SyncOut,
    WeightIn,
    WeightOut,
)
from ..upstream import ULookupItem

router = APIRouter(prefix="/v1", tags=["diary"], dependencies=[Depends(current_user)])

ERR = {"model": ErrorResponse}
SYNC_PAGE = 2000
SYNC_SAFETY_MARGIN = timedelta(seconds=5)  # re-delivery is harmless, a missed in-flight commit is not


# ---------- profile ----------

@router.get("/me", response_model=MeOut)
async def me(user: User = Depends(current_user)) -> MeOut:
    return MeOut(email=user.email, calorie_goal=user.calorie_goal)


@router.put("/me/goal", response_model=MeOut, responses={422: ERR})
async def set_goal(
    body: GoalIn, request: Request, user: User = Depends(current_user), session: AsyncSession = Depends(get_session)
) -> MeOut:
    """Set the daily calorie goal. Goals under the safety minimum are refused, never adjusted silently."""
    s = request.app.state.settings
    if body.calorie_goal < s.min_calorie_goal:
        raise ApiError(422, "goal_too_low",
                       f"Цель ниже {s.min_calorie_goal:,} ккал в день без наблюдения врача не рекомендуется. "
                       f"Выбери не меньше {s.min_calorie_goal:,}.".replace(",", " "))
    if body.calorie_goal > s.max_calorie_goal:
        raise ApiError(422, "goal_too_high", f"Цель выше {s.max_calorie_goal:,} ккал похожа на опечатку.".replace(",", " "))
    user.calorie_goal = body.calorie_goal
    await session.commit()
    return MeOut(email=user.email, calorie_goal=user.calorie_goal)


# ---------- messages ----------

@router.post("/messages", response_model=MessageOut,
             responses={422: ERR, 502: ERR, 503: ERR, 504: ERR, 409: ERR})
async def post_message(
    body: MessageIn, request: Request, user: User = Depends(current_user), session: AsyncSession = Depends(get_session)
) -> MessageOut:
    """Turn a text and/or photo message into diary entries (or changes to today's entries).

    Idempotent on `client_id`: a retry returns the stored answer. 5xx/504 mean "try again later";
    the same client_id is safe to reuse.
    """
    processor: MessageProcessor = request.app.state.processor
    return await processor.process(session, user, body)


# ---------- entries ----------

async def _own_entry(session: AsyncSession, user: User, entry_id: uuid.UUID) -> Entry:
    entry = await session.get(Entry, entry_id)
    if entry is None or entry.user_id != user.id:
        raise ApiError(404, "not_found", "Запись не найдена.")
    return entry


@router.patch("/entries/{entry_id}", response_model=EntryOut, responses={404: ERR, 503: ERR})
async def patch_entry(
    entry_id: uuid.UUID,
    body: EntryPatchIn,
    request: Request,
    user: User = Depends(current_user),
    session: AsyncSession = Depends(get_session),
    at: datetime = Depends(now),
) -> EntryOut:
    """Edit a row in place. Changing only grams is pure arithmetic; changing the name looks the food up again."""
    entry = await _own_entry(session, user, entry_id)
    if entry.deleted_at is not None:
        raise ApiError(404, "not_found", "Запись не найдена.")
    grams = body.grams if body.grams is not None else entry.grams

    if body.name is not None and " ".join(body.name.lower().split()) != " ".join(entry.name.lower().split()):
        [result] = await request.app.state.nutrition.lookup([ULookupItem(name=body.name, query=None, grams=grams)])
        entry.name = body.name.strip()
        entry.query_en = None
        entry.grams = grams
        apply_lookup(entry, result, 1.0, request.app.state.settings.clarify_threshold)
    else:
        rescale(entry, grams)
        if entry.status == "uncertain":
            entry.status = "ok"  # the user just confirmed the amount by hand
            entry.confidence = 1.0
    entry.updated_at = at
    await session.commit()
    return entry_out(entry)


@router.delete("/entries/{entry_id}", status_code=204, responses={404: ERR})
async def delete_entry(
    entry_id: uuid.UUID,
    user: User = Depends(current_user),
    session: AsyncSession = Depends(get_session),
    at: datetime = Depends(now),
) -> Response:
    entry = await _own_entry(session, user, entry_id)
    if entry.deleted_at is None:
        entry.deleted_at = entry.updated_at = at
        await session.commit()
    return Response(status_code=204)


# ---------- weights ----------

def _weight_out(w: Weight) -> WeightOut:
    return WeightOut(id=w.id, day=w.day, kg=w.kg, updated_at=w.updated_at, deleted=w.deleted_at is not None)


@router.put("/weights/{weight_id}", response_model=WeightOut, responses={409: ERR})
async def put_weight(
    weight_id: uuid.UUID,
    body: WeightIn,
    user: User = Depends(current_user),
    session: AsyncSession = Depends(get_session),
    at: datetime = Depends(now),
) -> WeightOut:
    """Create or replace a weight by its client-generated id (safe to repeat)."""
    weight = await session.get(Weight, weight_id)
    if weight is not None and weight.user_id != user.id:
        raise ApiError(409, "id_conflict", "Этот идентификатор уже занят.")
    if weight is None:
        weight = Weight(id=weight_id, user_id=user.id, created_at=at)
        session.add(weight)
    weight.day, weight.kg, weight.updated_at, weight.deleted_at = body.day, body.kg, at, None
    await session.commit()
    return _weight_out(weight)


@router.delete("/weights/{weight_id}", status_code=204, responses={404: ERR})
async def delete_weight(
    weight_id: uuid.UUID,
    user: User = Depends(current_user),
    session: AsyncSession = Depends(get_session),
    at: datetime = Depends(now),
) -> Response:
    weight = await session.get(Weight, weight_id)
    if weight is None or weight.user_id != user.id:
        raise ApiError(404, "not_found", "Запись не найдена.")
    if weight.deleted_at is None:
        weight.deleted_at = weight.updated_at = at
        await session.commit()
    return Response(status_code=204)


# ---------- sync ----------

@router.get("/sync", response_model=SyncOut)
async def sync(
    since: datetime | None = Query(default=None, description="`next_since` from the previous call; omit for everything"),
    user: User = Depends(current_user),
    session: AsyncSession = Depends(get_session),
    at: datetime = Depends(now),
) -> SyncOut:
    """Everything changed at or after `since`, including deletions (deleted=true). Upsert by id on the client."""
    if since is not None and since.tzinfo is None:
        raise ApiError(422, "bad_since", "Параметр since должен содержать часовой пояс.")

    async def page(model):
        stmt = select(model).where(model.user_id == user.id)
        if since is not None:
            stmt = stmt.where(model.updated_at >= since)
        rows = (await session.scalars(stmt.order_by(model.updated_at, model.id).limit(SYNC_PAGE + 1))).all()
        return rows[:SYNC_PAGE], len(rows) > SYNC_PAGE

    entries, more_entries = await page(Entry)
    weights, more_weights = await page(Weight)

    cursors = [rows[-1].updated_at for rows, more in ((entries, more_entries), (weights, more_weights)) if more]
    next_since = min(cursors) if cursors else at - SYNC_SAFETY_MARGIN
    return SyncOut(
        server_time=at,
        next_since=next_since,
        has_more=bool(cursors),
        calorie_goal=user.calorie_goal,
        entries=[entry_out(e) for e in entries],
        weights=[_weight_out(w) for w in weights],
    )


# ---------- voice ----------

@router.post("/stt", response_model=SttOut, responses={413: ERR, 422: ERR, 502: ERR, 503: ERR})
async def speech_to_text(
    request: Request,
    audio: UploadFile = File(description="Voice note: wav, m4a/aac, mp3, ogg/opus, flac, webm, 3gp"),
    language: str | None = Form(default=None, pattern=r"^[a-z]{2,3}$", description="ISO 639-1; omit to auto-detect"),
) -> SttOut:
    """Fallback for devices without on-device recognition. The text goes back to the app, which sends it as a message."""
    limit = request.app.state.settings.max_audio_bytes
    data = await audio.read(limit + 1)
    if len(data) > limit:
        raise ApiError(413, "audio_too_large", "Запись слишком большая.")
    result = await request.app.state.stt.transcribe(
        data, audio.filename or "audio", audio.content_type or "application/octet-stream", language
    )
    return SttOut(text=result.text, language=result.language, duration_s=result.duration_s)
