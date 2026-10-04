"""The re-ranker's rules, on USDA-shaped names. Each test is a mistake seen on the real database."""

import pytest

from nutrition.search import SYNONYMS, analyse, expand_query, score_match
from nutrition.text import stem, stem_set


def s(query: str, food: str, brand: str | None = None) -> float:
    # through expand_query, like the real search (synonyms: "french fries" -> "potatoes french fried")
    return score_match(stem_set(expand_query(query)), analyse(food, brand))[0]


# ---------- how a name is read ----------

def test_first_segment_is_what_the_food_is():
    info = analyse("Apples, raw, with skin")
    assert info.head == {"apple"}
    assert info.second == {"raw"}
    assert info.later == {"skin"}


def test_food_group_prefixes_are_transparent():
    info = analyse("Beverages, coffee, brewed, prepared with tap water")
    assert info.category == {"beverage"}
    assert info.head == {"coffee"}
    assert "beverage" in info.all  # still searchable
    assert analyse("Fish, salmon, Atlantic, farmed, cooked").head == {"salmon"}
    assert analyse("Salad dressing, mayonnaise, regular").head == {"mayonnaise"}


def test_a_real_head_is_not_mistaken_for_a_food_group():
    assert analyse("Cheese, cottage, creamed").head == {"cheese"}
    assert analyse("Milk, whole, 3.25% milkfat").head == {"milk"}


def test_noise_segments_do_not_push_the_identity_down():
    info = analyse("Chicken, broilers or fryers, thigh, meat only, cooked, roasted")
    assert info.head == {"chicken"}
    assert info.second == {"thigh"}
    assert {"broiler", "fryer", "meat"} <= info.all  # but they still count for coverage


def test_a_group_word_inside_a_food_name_does_not_make_it_a_group():
    assert analyse("Guava sauce, cooked").head == {"guava", "sauce"}
    assert analyse("Sauce, pasta, spaghetti/marinara").head == {"pasta"}
    assert analyse("Cereals ready-to-eat, granola, homemade").head == {"granola"}
    assert analyse("Fast foods, hamburger, large").head == {"hamburger"}


def test_a_name_with_only_a_state_has_no_head():
    assert analyse("Cooked, stewed").head == frozenset()


@pytest.mark.parametrize(
    "name, branded",
    [
        ("Rice, brown, parboiled, cooked, UNCLE BENS", True),
        ("McDONALD'S, french fries", True),
        ("Beverages, The COCA-COLA company, Minute Maid, Lemonade", True),
        ("Rice, brown, long-grain, cooked (Includes foods for USDA's Food Distribution Program)", False),
        ("Milk, whole, 3.25% milkfat, with added vitamin D", False),
        ("Milk, NFS", False),
    ],
)
def test_brands_are_spotted_but_usda_abbreviations_are_not(name, branded):
    assert analyse(name).branded is branded


# ---------- the mistakes ----------

def test_a_food_beats_a_product_that_merely_mentions_it():
    assert s("apple", "Apples, raw, with skin") > s("apple", "Croissants, apple")
    assert s("apple", "Apples, raw, with skin") > s("apple", "Rose-apples, raw")
    assert s("apple", "Apples, raw") > s("apple", "Babyfood, apples, dices, toddler")
    assert s("egg", "Egg, whole, raw, fresh") > s("egg", "Bagels, egg")
    assert s("egg", "Egg, whole, raw, fresh") > s("egg", "Fast foods, egg, scrambled")


def test_milk_is_not_cheese_made_from_milk():
    assert s("milk whole", "Milk, whole, 3.25% milkfat, with added vitamin D") > s("milk whole", "Cheese, mozzarella, whole milk")


def test_word_order_in_the_query_does_not_matter():
    assert s("olive oil", "Oil, olive, salad or cooking") > s("olive oil", "Oil, corn, peanut, and olive")
    assert s("oil olive", "Oil, olive, salad or cooking") == s("olive oil", "Oil, olive, salad or cooking")


def test_missing_part_of_the_query_loses_to_having_all_of_it():
    # raw buckwheat is 343 kcal, cooked is 92: dropping "cooked" is not a small mistake
    assert s("buckwheat cooked", "Buckwheat groats, roasted, cooked") > s("buckwheat cooked", "Buckwheat")
    assert s("chicken breast cooked", "Chicken, broilers or fryers, breast, meat only, cooked, roasted") > s(
        "chicken breast cooked", "Chicken, broilers or fryers, breast, meat only, raw")


def test_a_match_on_the_cooking_word_alone_is_no_match():
    assert score_match(stem_set("oats cooked"), analyse("Guava sauce, cooked")) == (0.0, 0.0)
    assert score_match(stem_set("lentils cooked"), analyse("Pasta, cooked")) == (0.0, 0.0)


def test_processed_versions_lose_unless_asked_for():
    assert s("chicken breast", "Chicken, broilers or fryers, breast, meat only, raw") > s(
        "chicken breast", "Chicken breast tenders, breaded, uncooked")
    assert s("tomato", "Tomatoes, red, ripe, raw") > s("tomato", "Tomato powder")
    assert s("tomato powder", "Tomato powder") > s("tomato powder", "Tomatoes, red, ripe, raw")
    assert s("orange juice", "Orange juice, raw") > s("orange", "Orange juice, raw")


def test_brands_lose_to_the_generic_food():
    assert s("rice brown cooked", "Rice, brown, long-grain, cooked") > s("rice brown cooked", "Rice, brown, parboiled, cooked, UNCLE BENS")
    assert s("french fries", "Potatoes, french fried, frozen") > s("french fries", "McDONALD'S, french fries")


def test_unusual_variants_lose_to_the_plain_one():
    assert s("milk", "Milk, whole, 3.25% milkfat") > s("milk", "Milk, sheep, fluid")
    assert s("milk", "Milk, whole, 3.25% milkfat") > s("milk", "Milk, dry, whole")
    assert s("sour cream", "Cream, sour, cultured") > s("sour cream", "Sour cream, reduced fat")
    assert s("mayonnaise", "Salad dressing, mayonnaise, regular") > s("mayonnaise", "Mayonnaise, reduced fat, with olive oil")
    assert s("bacon", "Pork, cured, bacon, cooked") >= 0 and s("pork cured bacon", "Pork, cured, bacon, cooked") > s(
        "pork cured bacon", "Canadian bacon, cooked")
    assert s("chicken", "Chicken, broilers or fryers, thigh, meat only, raw") > s("chicken", "Chicken, skin (drumsticks and thighs), raw")


def test_a_bare_name_prefers_the_raw_food():
    assert s("tomato", "Tomatoes, red, ripe, raw") > s("tomato", "Tomatoes, red, ripe, cooked")
    assert s("potato boiled", "Potatoes, boiled, cooked without skin") > s("potato boiled", "Potatoes, raw")


# ---------- synonyms ----------

def test_synonyms_bridge_everyday_words_and_usda_names():
    assert expand_query("Oatmeal cooked") == "oats cooked"
    assert expand_query("bacon") == "pork cured bacon"
    assert expand_query("Kiwi") == "kiwifruit"
    assert expand_query("ketchup") == "catsup"
    assert expand_query("hot dog") == "frankfurter meat"
    assert expand_query("French fries") == "potatoes french fried"


def test_synonyms_only_replace_whole_words():
    assert expand_query("kiwis") == "kiwis"
    assert expand_query("turkey bacon bits") == "turkey pork cured bacon bits"
    assert expand_query("baconator") == "baconator"


def test_the_longest_phrase_wins():
    assert "corn flakes" in SYNONYMS and expand_query("corn flakes") == "cereals corn flakes"


def test_plain_queries_pass_through_cleaned():
    assert expand_query("  Chicken, Breast  (cooked)! ") == "chicken breast cooked"


# ---------- stemming ----------

@pytest.mark.parametrize(
    "word, stem_of",
    [("peaches", "peach"), ("radishes", "radish"), ("boxes", "box"), ("glasses", "glass"), ("cheeses", "cheese"),
     ("potatoes", "potato"), ("berries", "berry"), ("eggs", "egg"), ("apples", "apple")],
)
def test_english_plurals(word, stem_of):
    assert stem(word) == stem_of
