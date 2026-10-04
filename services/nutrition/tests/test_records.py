import pytest

from nutrition.records import build_record


def rec(**kw):
    base = dict(source="off", source_id="1", name="Кефир", brand=None, kcal=50.0, protein=3.0, fat=2.5, carbs=4.0)
    base.update(kw)
    return build_record(**base)


def test_valid_record_passes_through():
    r = rec()
    assert (r.kcal, r.protein, r.fat, r.carbs, r.derived) == (50.0, 3.0, 2.5, 4.0, False)


def test_name_and_brand_whitespace_is_collapsed():
    r = rec(name="  Кефир \t 2.5%  ", brand="  Простоквашино  ")
    assert r.name == "Кефир 2.5%"
    assert r.brand == "Простоквашино"
    assert rec(brand="   ").brand is None


def test_kj_is_converted_when_kcal_missing():
    r = rec(kcal=None, kj=418.4)
    assert r.kcal == pytest.approx(100.0)
    assert r.derived is False


def test_kcal_is_derived_from_macros_as_last_resort():
    r = rec(kcal=None, protein=10, fat=10, carbs=10)
    assert r.kcal == 170
    assert r.derived is True


@pytest.mark.parametrize(
    "overrides",
    [
        dict(name=""),
        dict(name="x"),
        dict(name=None),
        dict(protein=None),
        dict(fat=None),
        dict(carbs=None),
        dict(protein=-1),
        dict(fat=101),
        dict(protein=60, fat=50, carbs=40),
        dict(kcal=901),
        dict(kcal=-5),
    ],
)
def test_implausible_rows_are_rejected(overrides):
    assert rec(**overrides) is None
