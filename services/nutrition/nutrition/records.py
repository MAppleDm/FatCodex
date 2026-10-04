"""FoodRecord: one validated food row, produced by the importers."""

from __future__ import annotations

import re
from dataclasses import dataclass

from .calc import atwater_kcal

KJ_PER_KCAL = 4.184
_WS = re.compile(r"\s+")


@dataclass(frozen=True, slots=True)
class FoodRecord:
    source: str
    source_id: str
    name: str
    brand: str | None
    kcal: float  # per 100 g
    protein: float
    fat: float
    carbs: float
    derived: bool = False


def build_record(
    source: str,
    source_id: str,
    name: str | None,
    brand: str | None,
    kcal: float | None,
    protein: float | None,
    fat: float | None,
    carbs: float | None,
    kj: float | None = None,
) -> FoodRecord | None:
    """Validate raw per-100g values. Returns None for rows that are unusable or implausible.

    Macros are mandatory (we report Б/Ж/У). Missing kcal is taken from kJ if present,
    otherwise derived from macros with Atwater factors and flagged `derived`.
    """
    name = _WS.sub(" ", name or "").strip()
    if not (2 <= len(name) <= 200):
        return None
    if protein is None or fat is None or carbs is None:
        return None
    if not all(0 <= v <= 100 for v in (protein, fat, carbs)):
        return None
    if protein + fat + carbs > 101:  # small slack for rounding in source data
        return None

    derived = False
    if kcal is None and kj is not None:
        kcal = kj / KJ_PER_KCAL
    if kcal is None:
        kcal = atwater_kcal(protein, fat, carbs)
        derived = True
    if not (0 <= kcal <= 900):  # pure fat is ~884 kcal/100 g
        return None

    brand = _WS.sub(" ", brand or "").strip() or None
    return FoodRecord(
        source=source,
        source_id=source_id,
        name=name,
        brand=brand,
        kcal=round(kcal, 2),
        protein=protein,
        fat=fat,
        carbs=carbs,
        derived=derived,
    )
