from nutrition.text import fold, search_text, stem, stem_set, tokenize


def test_fold_strips_diacritics_and_case():
    assert fold("Ёжик Йогурт Crème") == "ежик иогурт creme"


def test_tokenize_drops_stopwords_punctuation_and_duplicates():
    assert tokenize("Chicken, broilers or fryers, breast, meat only, cooked, cooked") == [
        "chicken", "broilers", "fryers", "breast", "meat", "cooked",
    ]


def test_tokenize_splits_hyphens_and_keeps_numbers():
    assert tokenize("hard-boiled 2.5%") == ["hard", "boiled", "2", "5"]


def test_stem_english_plurals():
    assert stem("eggs") == "egg"
    assert stem("berries") == "berry"
    assert stem("potatoes") == "potato"
    assert stem("glass") == "glass"
    assert stem("couscous") == "couscous"


def test_stem_russian_inflections_collapse():
    assert stem("гречка") == stem("гречки") == stem("гречку")
    assert stem("яйцо") == stem("яйца")
    assert stem("масло") == stem("масла") == stem("масле")


def test_stem_set_is_case_and_plural_insensitive():
    assert stem_set("Bananas, RAW") == stem_set("banana raw")


def test_search_text_includes_brand():
    assert search_text("Растишка клубника", "Danone") == "растишка клубника danone"
