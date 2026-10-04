"""KBZHU arithmetic. Pure functions, no I/O."""

from __future__ import annotations

from collections.abc import Iterable
from decimal import ROUND_HALF_UP, Decimal

from .schemas import Nutrients

ATWATER = {"protein": 4.0, "fat": 9.0, "carbs": 4.0}


def round1(value: float) -> float:
    """Round half up to one decimal (plain round() is banker's rounding on floats)."""
    return float(Decimal(repr(value)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def atwater_kcal(protein: float, fat: float, carbs: float) -> float:
    return ATWATER["protein"] * protein + ATWATER["fat"] * fat + ATWATER["carbs"] * carbs


def scale(per100g: Nutrients, grams: float) -> Nutrients:
    """Nutrients for `grams` of a food, not rounded."""
    k = grams / 100.0
    return Nutrients(
        kcal=per100g.kcal * k,
        protein=per100g.protein * k,
        fat=per100g.fat * k,
        carbs=per100g.carbs * k,
    )


def total(parts: Iterable[Nutrients]) -> Nutrients:
    kcal = protein = fat = carbs = 0.0
    for p in parts:
        kcal += p.kcal
        protein += p.protein
        fat += p.fat
        carbs += p.carbs
    return Nutrients(kcal=kcal, protein=protein, fat=fat, carbs=carbs)


def rounded(n: Nutrients) -> Nutrients:
    return Nutrients(kcal=round1(n.kcal), protein=round1(n.protein), fat=round1(n.fat), carbs=round1(n.carbs))
