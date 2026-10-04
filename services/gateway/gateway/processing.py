"""POST /v1/messages: text/photo in, diary changes out.

  context (today's entries, habitual meals)  ->  ai-parser  ->  nutrition  ->  entries

The model only names foods and grams; every number comes from nutrition. Anything it cannot
ground in the food database is stored as `unmatched` without numbers and turned into a question.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from . import nutrients
from .config import Settings
from .db import Entry, ProcessedMessage, User
from .errors import ApiError
from .frequent import MealRow, frequent_meals
from .schemas import EntryOut, MessageIn, MessageOut, Per100
from .upstream import (
    AiParser,
    Nutrition,
    UContextEntry,
    UItemResult,
    ULookupItem,
    UParseContext,
    UParsedItem,
    UParseRequest,
    UPendingQuestion,
)

HISTORY_DAYS = 60
MAX_CONTEXT_ENTRIES = 100


def entry_out(e: Entry) -> EntryOut:
    per100 = None
    if e.kcal100 is not None:
        per100 = Per100(kcal=e.kcal100, protein=e.protein100, fat=e.fat100, carbs=e.carbs100)
    return EntryOut(
        id=e.id, meal_id=e.meal_id, day=e.day, eaten_at=e.eaten_at, position=e.position, name=e.name, grams=e.grams,
        kcal=e.kcal, protein=e.protein, fat=e.fat, carbs=e.carbs, per100=per100, food_name=e.food_name,
        status=e.status, confidence=e.confidence, source=e.source, updated_at=e.updated_at,
        deleted=e.deleted_at is not None,
    )


def rescale(entry: Entry, grams: float) -> None:
    """Recompute the portion from the stored per-100g values. No lookup needed."""
    entry.grams = grams
    if entry.kcal100 is not None:
        entry.kcal = nutrients.scale(entry.kcal100, grams)
        entry.protein = nutrients.scale(entry.protein100, grams)
        entry.fat = nutrients.scale(entry.fat100, grams)
        entry.carbs = nutrients.scale(entry.carbs100, grams)


def apply_lookup(entry: Entry, result: UItemResult | None, confidence: float, threshold: float) -> None:
    """Fill nutrition fields from a lookup result; an absent or unmatched result clears them."""
    entry.confidence = confidence
    if result is not None and result.matched and result.food is not None and result.nutrients is not None:
        p = result.food.per100g
        entry.kcal100, entry.protein100, entry.fat100, entry.carbs100 = p.kcal, p.protein, p.fat, p.carbs
        n = result.nutrients
        entry.kcal, entry.protein, entry.fat, entry.carbs = n.kcal, n.protein, n.fat, n.carbs
        entry.food_name = result.food.name
        entry.status = "ok" if confidence >= threshold else "uncertain"
    else:
        entry.kcal100 = entry.protein100 = entry.fat100 = entry.carbs100 = None
        entry.kcal = entry.protein = entry.fat = entry.carbs = None
        entry.food_name = None
        entry.status = "unmatched"


def _same(a: str, b: str) -> bool:
    return " ".join(a.lower().split()) == " ".join(b.lower().split())


def _uuid_or_none(value: str | None) -> uuid.UUID | None:
    try:
        return uuid.UUID(value) if value else None
    except ValueError:
        return None


class MessageProcessor:
    def __init__(self, settings: Settings, ai: AiParser, nutrition: Nutrition, clock: Callable[[], datetime]) -> None:
        self._s = settings
        self._ai = ai
        self._nutrition = nutrition
        self._clock = clock

    async def process(self, session: AsyncSession, user: User, msg: MessageIn) -> MessageOut:
        done = await session.get(ProcessedMessage, msg.client_id)
        if done is not None:
            if done.user_id != user.id:
                raise ApiError(409, "id_conflict", "Этот идентификатор уже занят.")
            return MessageOut.model_validate_json(done.response_json)

        now = self._clock()
        known = await self._todays_entries(session, user, msg)
        parsed = await self._ai.parse(
            UParseRequest(
                text=msg.text,
                image_base64=msg.image_base64,
                image_mime=msg.image_mime,
                context=UParseContext(
                    entries=[UContextEntry(id=str(e.id), name=e.name, grams=e.grams) for e in known.values()],
                    frequent=await self._habitual_meals(session, user, msg),
                    pending_question=(
                        UPendingQuestion(
                            question=msg.pending_question.question,
                            target_id=str(msg.pending_question.target_id) if msg.pending_question.target_id else None,
                        )
                        if msg.pending_question
                        else None
                    ),
                    local_time=msg.eaten_at.strftime("%H:%M"),
                ),
            )
        )

        # 1. decide what each parsed item means and which ones need a nutrition lookup
        actions: list[tuple[int, UParsedItem, Entry | None]] = []
        lookups: dict[int, ULookupItem] = {}
        for idx, item in enumerate(parsed.items):
            target = known.get(_uuid_or_none(item.target_id)) if item.action != "add" else None
            if item.action != "add" and target is None:
                continue  # the parser is told to only reference listed entries; ignore anything else
            actions.append((idx, item, target))
            if item.action == "add":
                lookups[idx] = ULookupItem(name=item.name, query=item.query_en, grams=item.grams)
            elif item.action == "update" and not (target.kcal100 is not None and _same(item.name, target.name)):
                lookups[idx] = ULookupItem(name=item.name, query=item.query_en or target.query_en, grams=item.grams)

        results: dict[int, UItemResult] = {}
        if lookups:
            found = await self._nutrition.lookup(list(lookups.values()))
            if len(found) != len(lookups):
                raise ApiError(502, "nutrition_unavailable", "База продуктов ответила неожиданно. Попробуй позже.")
            results = dict(zip(lookups, found, strict=True))

        # 2. apply
        touched: list[tuple[int, Entry]] = []
        removed: list[uuid.UUID] = []
        for idx, item, target in actions:
            if item.action == "remove":
                target.deleted_at = target.updated_at = now
                removed.append(target.id)
            elif item.action == "update":
                if idx in results:
                    target.name = item.name
                    target.query_en = item.query_en or target.query_en
                    target.grams = item.grams
                    apply_lookup(target, results[idx], item.confidence, self._s.clarify_threshold)
                else:
                    rescale(target, item.grams)
                    target.confidence = item.confidence
                    target.status = "ok" if item.confidence >= self._s.clarify_threshold else "uncertain"
                target.updated_at = now
                touched.append((idx, target))
            else:
                entry = Entry(
                    id=uuid.uuid5(msg.client_id, str(idx)),
                    user_id=user.id,
                    meal_id=msg.client_id,
                    day=msg.day,
                    eaten_at=msg.eaten_at,
                    local_minutes=msg.eaten_at.hour * 60 + msg.eaten_at.minute,
                    position=idx,
                    name=item.name,
                    query_en=item.query_en,
                    grams=item.grams,
                    source="photo" if msg.image_base64 else msg.source,
                    created_at=now,
                    updated_at=now,
                )
                apply_lookup(entry, results.get(idx), item.confidence, self._s.clarify_threshold)
                session.add(entry)
                touched.append((idx, entry))

        # 3. at most one question: the parser's, else one about something we could not find
        question, target_id = parsed.clarify_question, None
        if question:
            asked = next((e for idx, e in touched if parsed.items[idx].clarify_question), None)
            target_id = asked.id if asked else None
        else:
            missing = next((e for _, e in touched if e.status == "unmatched"), None)
            if missing is not None:
                question = f"Не нашёл «{missing.name}» в базе продуктов. Что это точнее?"
                target_id = missing.id

        response = MessageOut(
            entries=[entry_out(e) for _, e in touched],
            removed_ids=removed,
            clarify_question=question,
            clarify_target_id=target_id,
        )
        session.add(ProcessedMessage(id=msg.client_id, user_id=user.id, response_json=response.model_dump_json(),
                                     created_at=now))
        await session.commit()
        return response

    async def _todays_entries(self, session: AsyncSession, user: User, msg: MessageIn) -> dict[uuid.UUID, Entry]:
        rows = await session.scalars(
            select(Entry)
            .where(Entry.user_id == user.id, Entry.day == msg.day, Entry.deleted_at.is_(None))
            .order_by(Entry.eaten_at, Entry.position, Entry.id)
            .limit(MAX_CONTEXT_ENTRIES)
        )
        known = {e.id: e for e in rows}
        pending = msg.pending_question
        if pending and pending.target_id and pending.target_id not in known:
            older = await session.get(Entry, pending.target_id)  # the question may be about an earlier day
            if older is not None and older.user_id == user.id and older.deleted_at is None:
                known[older.id] = older
        return known

    async def _habitual_meals(self, session: AsyncSession, user: User, msg: MessageIn):
        rows = await session.execute(
            select(Entry.meal_id, Entry.name, Entry.query_en, Entry.grams, Entry.local_minutes, Entry.eaten_at,
                   Entry.position)
            .where(
                Entry.user_id == user.id,
                Entry.deleted_at.is_(None),
                Entry.meal_id.is_not(None),
                Entry.status != "unmatched",
                Entry.day >= msg.day - timedelta(days=HISTORY_DAYS),
                Entry.day < msg.day,
            )
        )
        return frequent_meals(MealRow(*r) for r in rows)
