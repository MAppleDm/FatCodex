"""Candidate retrieval (FTS5) and re-ranking.

Retrieval is deliberately loose (bm25 over an AND query, widened to OR when too few hits). The
decisions live in the re-ranker, which knows how USDA names foods: "<what it is>, <qualifiers>".
"Apples, raw" is an apple; "Croissants, apple" merely mentions one. So a query word counts most in the
first segment of the name, less in the second, least later, and names that are processed, branded,
exotic or a rarely-meant part (skin, wings...) lose points unless the query asked for that.

`coverage` (share of query words found anywhere in the name) still decides whether there is a match at all.
"""

from __future__ import annotations

import re
import sqlite3
from collections.abc import Sequence
from dataclasses import dataclass

from .text import stem, stem_set, tokenize

CANDIDATES = 200
MIN_CANDIDATES = 10
GENERIC_SOURCES = frozenset({"usda_foundation", "usda_sr_legacy"})


def _stems(words: str) -> frozenset[str]:
    return frozenset(stem(w) for w in words.split())


# What people say -> what USDA calls it. Applied to queries only, longest phrase first.
SYNONYMS = {
    "hot dog": "frankfurter meat", "hotdog": "frankfurter meat", "french fries": "potatoes french fried",
    "corn flakes": "cereals corn flakes", "cornflakes": "cereals corn flakes", "cream cheese": "cheese cream",
    "oatmeal": "oats", "porridge": "oats", "yoghurt": "yogurt", "courgette": "zucchini", "aubergine": "eggplant",
    "mince": "ground", "minced": "ground", "coriander": "cilantro", "prawn": "shrimp", "prawns": "shrimp",
    "crisps": "snacks potato chips", "beetroot": "beets", "rocket": "arugula", "biscuit": "cookies",
    "biscuits": "cookies", "ketchup": "catsup", "kiwi": "kiwifruit", "bacon": "pork cured bacon",
}
_SYNONYM_RE = re.compile(r"\b(" + "|".join(sorted(map(re.escape, SYNONYMS), key=len, reverse=True)) + r")\b")

# USDA prefixes that name a food group, not the food: "Beverages, coffee", "Fish, salmon", "Nuts, walnuts".
CATEGORY_HEADS = _stems(
    "beverages cereals nuts seeds candies snacks spices soup soups babyfood fast restaurant beans legumes game sauce "
    "sweeteners desserts fish dressing crustaceans mollusks"
)
CATEGORY_FILLER = _stems("ready eat food foods alcoholic baby salad meat")
# Variants that are almost never what someone means by the plain word, so they must be asked for.
# Hits in the first two segments or the food group cost more than hits further into the name.
PROCESSED = _stems(
    "breaded battered frozen canned microwaved prepared restaurant babyfood fast imitation substitute artificial "
    "dehydrated powder mix syrup flavored sweetened instant candies snacks dessert pie sandwich nuggets tenders patty "
    "sticks rolls croissants cake cookies muffin pastry gravy spread dip juice drink smoothie shake paste flour bran "
    "oil extract concentrate mayonnaise crackers bagels bread baby formula marmalade jam jelly kimchi soymilk "
    "skin feet neck giblets wing wings dry evaporated condensed buttermilk chocolate yolk deli rotisserie seasoned "
    "prepackaged fermented"
)
# Animals nobody means by a bare "milk" or "meat".
EXOTIC = _stems("sheep goat human buffalo camel mare donkey deer elk bison moose canadian")
# Diet, low-fat and fried variants: fine when asked for, wrong as a default.
LESS_COMMON = _stems("light lite diet reduced low lowfat nonfat free decaffeinated fried")
# Plain, typical variants win ties.
PREFERRED = _stems("whole raw fresh regular plain red ripe granulated cultured black roasted enriched")
# Cooked/dried/etc. are fine when asked for; for a bare "tomato" the plain food is the better guess.
STATE_WORDS = _stems("cooked boiled fried roasted baked dried stewed braised grilled broiled steamed smoked pickled")
# Segments that say nothing about what the food is.
NOISE_SEGMENTS = (
    frozenset({"broiler", "fryer"}),
    frozenset({"meat"}),
    frozenset({"roasting"}),
    frozenset({"all", "commercial", "variety"}),
    frozenset({"year", "round", "average"}),
    frozenset({"cured"}),
    frozenset({"fresh"}),
    frozenset({"carbonated"}),
)
# USDA spells brands in capitals, sometimes inside a word: "UNCLE BENS", "McDONALD'S", "COCA-COLA".
_BRAND = re.compile(r"[A-Z]{3,}")
_NOT_BRANDS = frozenset({"USDA", "NFS", "UHT", "DHA", "ARA", "PUFA", "MUFA", "RTE", "FDA"})


def _is_branded(name: str) -> bool:
    return any(word not in _NOT_BRANDS for word in _BRAND.findall(name))


@dataclass(frozen=True, slots=True)
class Food:
    id: int
    source: str
    name: str
    brand: str | None
    kcal: float
    protein: float
    fat: float
    carbs: float
    derived: bool


@dataclass(frozen=True, slots=True)
class Match:
    food: Food
    score: float
    coverage: float


@dataclass(frozen=True, slots=True)
class NameInfo:
    head: frozenset[str]        # what the food is: the first real segment
    second: frozenset[str]
    later: frozenset[str]
    category: frozenset[str]    # a food-group prefix such as "Beverages" or "Babyfood"
    all: frozenset[str]         # every word, including noise and category
    branded: bool


def analyse(name: str, brand: str | None = None) -> NameInfo:
    everything = stem_set(name) | (stem_set(brand) if brand else frozenset())
    segments = [seg for seg in (stem_set(raw) for raw in name.split(",")) if seg and seg not in NOISE_SEGMENTS]
    category: frozenset[str] = frozenset()
    # "Beverages, coffee" / "Cereals ready-to-eat, granola": the prefix is a food group, the food comes next.
    # A segment with any other word ("Guava sauce") is a food, not a group.
    if len(segments) > 1 and segments[0] & CATEGORY_HEADS and segments[0] <= CATEGORY_HEADS | CATEGORY_FILLER:
        category, segments = segments[0], segments[1:]
    if brand:
        segments.append(stem_set(brand))
    if not segments or segments[0] <= STATE_WORDS:  # nothing that says what it is
        segments = [frozenset(), *segments]
    return NameInfo(
        head=segments[0],
        second=segments[1] if len(segments) > 1 else frozenset(),
        later=frozenset().union(*segments[2:]) if len(segments) > 2 else frozenset(),
        category=category,
        all=everything,
        branded=_is_branded(name),
    )


def expand_query(query: str) -> str:
    folded = " ".join(tokenize(query))  # lowercase, no punctuation, no stopwords
    return _SYNONYM_RE.sub(lambda m: SYNONYMS[m.group(1)], folded)


def score_match(query: frozenset[str], info: NameInfo) -> tuple[float, float]:
    """(score, coverage) of a food name for a query, both 0..1."""
    if not query or not info.all:
        return 0.0, 0.0
    in_name = query & info.all
    content = query - STATE_WORDS
    # "oats cooked" must not match "Guava sauce, cooked": at least one word that names a food has to be there
    if not in_name or (content and not content & info.all):
        return 0.0, 0.0
    coverage = len(in_name) / len(query)

    def position(token: str) -> float:
        if token in info.head:
            return 1.0
        if token in info.second:
            return 0.6
        return 0.3 if token in info.later else 0.0

    where = sum(position(t) for t in query) / len(query)
    head_precision = len(query & info.head) / len(info.head) if info.head else 0.0
    name_precision = len(in_name) / len(info.all)
    score = 0.5 * where + 0.3 * head_precision + 0.2 * name_precision

    zone = info.head | info.second | info.category
    in_zone = (zone & PROCESSED) - query
    elsewhere = ((info.all & PROCESSED) - query) - in_zone
    score *= 0.85 ** min(2, len(in_zone)) * 0.93 ** min(2, len(elsewhere))
    score *= 0.85 ** min(1, len((zone & EXOTIC) - query))
    score *= 0.9 ** min(2, len((info.all & LESS_COMMON) - query))
    score *= 1.03 ** min(3, len((info.all & PREFERRED) - query))
    if info.branded:
        score *= 0.7  # a brand's product must not beat the generic food with the same name
    if not (query & STATE_WORDS) and (info.all & STATE_WORDS):
        score *= 0.93
    # A food that is missing part of what was asked for ("buckwheat" for "buckwheat cooked": 343 vs 92 kcal)
    # must lose to any food that has all of it, however good its name looks otherwise.
    score *= coverage**2
    return round(min(score, 1.0), 4), round(coverage, 4)


def _fts_term(token: str) -> str:
    # Tokens are [^\W_]+ so quoting is enough to keep the FTS5 query syntax safe.
    if token.isascii():
        return f'"{token}"'
    return f'"{stem(token)}"*'  # crude Russian stemming: prefix match on the stem


def _row_to_food(row: sqlite3.Row) -> Food:
    return Food(
        id=row["id"], source=row["source"], name=row["name"], brand=row["brand"], kcal=row["kcal"],
        protein=row["protein"], fat=row["fat"], carbs=row["carbs"], derived=bool(row["derived"]),
    )


class FoodSearcher:
    def __init__(self, conn: sqlite3.Connection) -> None:
        self._conn = conn

    def search(self, queries: Sequence[str | None], limit: int = 5) -> list[Match]:
        """Best matches for any of `queries` (e.g. English query + original name), best first."""
        variants = [expand_query(q) for q in queries if q and q.strip()]
        candidates: dict[int, Food] = {}
        for q in variants:
            terms = [_fts_term(t) for t in tokenize(q)]
            if not terms:
                continue
            self._collect(candidates, " AND ".join(terms))
            if len(candidates) < MIN_CANDIDATES and len(terms) > 1:
                self._collect(candidates, " OR ".join(terms))

        query_sets = [s for s in (stem_set(q) for q in variants) if s]
        ranked: list[tuple[tuple, Match]] = []
        for food in candidates.values():
            info = analyse(food.name, food.brand)
            score = coverage = 0.0
            for qs in query_sets:
                s, c = score_match(qs, info)
                if (s, c) > (score, coverage):
                    score, coverage = s, c
            if coverage == 0.0:
                continue
            key = (-score, len(info.all), 0 if food.source in GENERIC_SOURCES else 1, food.name)
            ranked.append((key, Match(food=food, score=score, coverage=coverage)))
        ranked.sort(key=lambda kv: kv[0])
        return [m for _, m in ranked[:limit]]

    def _collect(self, into: dict[int, Food], fts_query: str) -> None:
        rows = self._conn.execute(
            "SELECT f.* FROM foods_fts JOIN foods f ON f.id = foods_fts.rowid"
            " WHERE foods_fts MATCH ? ORDER BY bm25(foods_fts) LIMIT ?",
            (fts_query, CANDIDATES),
        ).fetchall()
        for row in rows:
            into.setdefault(row["id"], _row_to_food(row))
