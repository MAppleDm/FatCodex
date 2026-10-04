"""Lookup use-case: search each item, scale by grams, total up. No HTTP in here."""

from __future__ import annotations

import sqlite3

from . import calc
from .schemas import FoodOut, ItemResult, LookupItem, LookupResponse, Nutrients
from .search import Food, FoodSearcher

ALTERNATIVES = 3


def food_out(food: Food) -> FoodOut:
    return FoodOut(
        id=food.id,
        source=food.source,
        name=food.name,
        brand=food.brand,
        per100g=Nutrients(kcal=food.kcal, protein=food.protein, fat=food.fat, carbs=food.carbs),
        derived=food.derived,
    )


def lookup(conn: sqlite3.Connection, items: list[LookupItem], min_coverage: float) -> LookupResponse:
    searcher = FoodSearcher(conn)
    results: list[ItemResult] = []
    matched_parts: list[Nutrients] = []

    for item in items:
        matches = searcher.search([item.query, item.name], limit=1 + ALTERNATIVES)
        best = matches[0] if matches else None
        if best is None or best.coverage < min_coverage:
            near = [m.food.name for m in matches[:ALTERNATIVES]]
            results.append(
                ItemResult(name=item.name, grams=item.grams, matched=False, score=best.score if best else 0.0,
                           alternatives=near)
            )
            continue
        exact = calc.scale(food_out(best.food).per100g, item.grams)
        matched_parts.append(exact)
        results.append(
            ItemResult(
                name=item.name,
                grams=item.grams,
                matched=True,
                score=best.score,
                food=food_out(best.food),
                nutrients=calc.rounded(exact),
                alternatives=[m.food.name for m in matches[1 : 1 + ALTERNATIVES]],
            )
        )

    return LookupResponse(items=results, totals=calc.rounded(calc.total(matched_parts)))
