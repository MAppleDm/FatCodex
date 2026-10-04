"""Habitual meals for "как обычно": meals (items logged from one message) that repeat."""

from __future__ import annotations

from collections import Counter, defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime

from .upstream import UFrequentItem, UFrequentMeal

MIN_REPEATS = 3
MAX_MEALS = 5
MAX_LABEL_ITEMS = 3


@dataclass(frozen=True, slots=True)
class MealRow:
    meal_id: object
    name: str
    query_en: str | None
    grams: float
    local_minutes: int
    eaten_at: datetime
    position: int = 0


def time_of_day(local_minutes: int) -> str:
    if 5 * 60 <= local_minutes < 11 * 60:
        return "утром"
    if 11 * 60 <= local_minutes < 16 * 60:
        return "днём"
    if 16 * 60 <= local_minutes < 22 * 60:
        return "вечером"
    return "ночью"


def frequent_meals(rows: Iterable[MealRow], min_repeats: int = MIN_REPEATS, limit: int = MAX_MEALS) -> list[UFrequentMeal]:
    """Group rows into meals, call two meals the same when they hold the same foods (by name),
    and return the most repeated ones, using the latest occurrence for the grams."""
    meals: dict[object, list[MealRow]] = defaultdict(list)
    for row in rows:
        meals[row.meal_id].append(row)

    by_signature: dict[tuple[str, ...], list[list[MealRow]]] = defaultdict(list)
    for items in meals.values():
        signature = tuple(sorted({i.name.strip().lower() for i in items}))
        by_signature[signature].append(items)

    ranked = sorted(
        (occurrences for occurrences in by_signature.values() if len(occurrences) >= min_repeats),
        key=lambda occ: (-len(occ), -max(i.eaten_at for items in occ for i in items).timestamp()),
    )

    result: list[UFrequentMeal] = []
    for occurrences in ranked[:limit]:
        latest = max(occurrences, key=lambda items: max(i.eaten_at for i in items))
        usual_time = Counter(time_of_day(items[0].local_minutes) for items in occurrences).most_common(1)[0][0]
        ordered = sorted(latest, key=lambda i: (i.eaten_at, i.position))
        names = ", ".join(i.name for i in ordered[:MAX_LABEL_ITEMS])
        result.append(
            UFrequentMeal(
                label=f"{names} ({usual_time})",
                items=[UFrequentItem(name=i.name, query_en=i.query_en, grams=i.grams) for i in ordered],
            )
        )
    return result
