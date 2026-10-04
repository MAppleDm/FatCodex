"""Per-100g -> per-portion arithmetic. Must stay identical to nutrition/calc.py and to the Android code:
multiply, then round half up to one decimal."""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal


def round1(value: float) -> float:
    return float(Decimal(repr(value)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def scale(per100: float, grams: float) -> float:
    return round1(per100 * grams / 100.0)
