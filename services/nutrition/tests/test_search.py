import pytest

from nutrition.search import FoodSearcher, analyse, score_match
from nutrition.text import stem_set


def best(conn, *queries):
    matches = FoodSearcher(conn).search(list(queries))
    return matches[0] if matches else None


def score(query: str, food: str, brand: str | None = None) -> tuple[float, float]:
    return score_match(stem_set(query), analyse(food, brand))


def test_score_full_coverage_beats_partial():
    full, cov_full = score("chicken breast cooked", "Chicken, breast, cooked")
    part, cov_part = score("chicken breast cooked", "Chicken, feet, cooked")
    assert cov_full == 1.0 and cov_part == pytest.approx(0.6667, abs=1e-3)
    assert full > part


def test_score_prefers_concise_names_at_equal_coverage():
    short, _ = score("egg", "Egg, raw")
    long, _ = score("egg", "Egg, whole, cooked, hard-boiled, peeled")
    assert short > long


def test_score_empty_inputs():
    assert score_match(frozenset(), analyse("egg")) == (0.0, 0.0)
    assert score_match(stem_set("egg"), analyse("")) == (0.0, 0.0)


def test_specific_cut_wins(conn):
    m = best(conn, "chicken breast cooked")
    assert "breast" in m.food.name.lower()
    assert m.coverage == 1.0


def test_other_cut_is_ranked_lower_not_dropped(conn):
    names = [m.food.name for m in FoodSearcher(conn).search(["chicken breast cooked"], limit=5)]
    assert "drumstick" in names[1].lower()


@pytest.mark.parametrize(
    "query, expected",
    [
        ("buckwheat cooked", "Buckwheat groats, roasted, cooked"),
        ("egg boiled", "Egg, whole, cooked, hard-boiled"),
        ("eggs", "Egg, whole, cooked, hard-boiled"),  # plural
        ("banana", "Bananas, raw"),
        ("olive oil", "Oil, olive, extra virgin"),
        ("butter", "Butter, salted"),
    ],
)
def test_english_queries(conn, query, expected):
    m = best(conn, query)
    assert m.food.name == expected
    assert m.coverage == 1.0


@pytest.mark.parametrize("query", ["растишка клубника", "растишку", "РАСТИШКА"])
def test_russian_query_matches_off_product_by_stem(conn, query):
    m = best(conn, query)
    assert m.food.source == "off"
    assert m.food.name == "Растишка клубника"


def test_query_and_name_variants_are_combined(conn):
    # the Russian name alone finds nothing in USDA; the English query carries it
    m = best(conn, "chicken breast cooked", "куриная грудка")
    assert "breast" in m.food.name.lower()
    # and the other way round: English query finds nothing, Russian name finds the OFF product
    m = best(conn, "strawberry yogurt drink", "растишка клубника")
    assert m.food.source == "off"


def test_unknown_food_has_no_full_match(conn):
    m = best(conn, "sushi roll")
    assert m is None or m.coverage < 0.75


def test_empty_and_stopword_only_queries_return_nothing(conn):
    assert FoodSearcher(conn).search([None, "", "   "]) == []
    assert FoodSearcher(conn).search(["of the and"]) == []


def test_query_with_fts_syntax_characters_is_safe(conn):
    # quotes, parentheses, NEAR/AND/OR must not reach FTS5 as operators
    assert FoodSearcher(conn).search(['egg" OR (butter) NEAR/2 AND *']) is not None


def test_limit_is_respected(conn):
    assert len(FoodSearcher(conn).search(["chicken"], limit=1)) == 1
