"""Search quality on real USDA names.

`data/sr_legacy_subset.csv` holds 1 337 rows of USDA FoodData Central SR Legacy (April 2018, public domain):
the candidates for every query below, including the confusable ones ("Croissants, apple", "Tomato powder",
"Canadian bacon"...). Each expected pattern was judged by hand. If you change the ranking and one of these
fails, the app would now put the wrong calories on someone's plate.
"""

import csv
import json
import os
import re
from pathlib import Path

import pytest

from nutrition import db
from nutrition.records import build_record
from nutrition.search import FoodSearcher

SUBSET = Path(__file__).parent / "data" / "sr_legacy_subset.csv"

_SHARED = json.loads((Path(__file__).parent / "data" / "expected_matches.json").read_text(encoding="utf-8"))
# query (as the language model is asked to write it) -> pattern the best food must match.
# The same file is read by the Android tests, so both implementations are held to the same answers.
EXPECTED = _SHARED["expected"]
# Not in the database. They must come back as "no match" (a question to the user), never as some other food.
ABSENT = _SHARED["absent"]
GOLDEN = Path(__file__).parent / "data" / "golden_top1.json"


@pytest.fixture(scope="module")
def searcher(tmp_path_factory):
    path = tmp_path_factory.mktemp("real") / "foods.db"
    db.init_db(path)
    conn = db.connect(path)
    with open(SUBSET, newline="", encoding="utf-8") as f:
        records = []
        for row in csv.DictReader(f):
            rec = build_record(
                "usda_sr_legacy", row["fdc_id"], row["description"], None,
                float(row["kcal"]), float(row["protein"]), float(row["fat"]), float(row["carbs"]),
            )
            if rec:
                records.append(rec)
    db.upsert_foods(conn, records)
    yield FoodSearcher(conn)
    conn.close()


def test_the_fixture_is_the_real_thing(searcher):
    assert SUBSET.stat().st_size > 50_000
    assert len(EXPECTED) >= 60


@pytest.mark.parametrize("query, pattern", list(EXPECTED.items()), ids=list(EXPECTED))
def test_best_match_is_the_right_food(searcher, query, pattern):
    top = searcher.search([query], limit=3)
    assert top, f"nothing found for {query!r}"
    best = top[0]
    assert best.coverage >= 0.75, f"{query!r}: best is only a partial match: {best.food.name}"
    assert re.search(pattern, best.food.name, re.I), (
        f"{query!r} -> {best.food.name!r}; runners-up: {[m.food.name for m in top[1:]]}"
    )


@pytest.mark.parametrize("query", ABSENT)
def test_absent_foods_are_not_replaced_by_something_else(searcher, query):
    top = searcher.search([query], limit=1)
    assert not top or top[0].coverage < 0.75


def test_calories_of_staples_are_the_expected_ones(searcher):
    """The end result of all of the above: numbers a person would recognise."""
    expected_kcal = {
        "buckwheat cooked": (85, 100),
        "chicken breast cooked": (150, 175),
        "egg boiled": (150, 160),
        "banana": (85, 95),
        "butter": (700, 730),
        "bacon cooked": (500, 600),
        "olive oil": (880, 890),
        "apple": (48, 56),
        "rice brown cooked": (105, 130),
    }
    for query, (low, high) in expected_kcal.items():
        kcal = searcher.search([query], limit=1)[0].food.kcal
        assert low <= kcal <= high, f"{query}: {kcal} kcal"


def _best(searcher, query):
    top = searcher.search([query], limit=1)
    if not top or top[0].coverage < 0.75:
        return None
    return {"name": top[0].food.name, "score": round(top[0].score, 3)}


def test_golden_top1_is_pinned(searcher):
    """Exact best match per query. The Kotlin port must reproduce this file, so change it deliberately:
    UPDATE_GOLDEN=1 pytest tests/test_real_data.py"""
    queries = [*EXPECTED, *ABSENT, *_SHARED["extra"]]
    current = {q: _best(searcher, q) for q in queries}
    if os.environ.get("UPDATE_GOLDEN"):
        text = json.dumps(current, indent=1, ensure_ascii=False, sort_keys=True) + "\n"
        GOLDEN.write_text(text, encoding="utf-8", newline="\n")
    assert json.loads(GOLDEN.read_text(encoding="utf-8")) == current
