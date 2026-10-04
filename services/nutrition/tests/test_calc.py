import pytest

from nutrition import calc
from nutrition.schemas import Nutrients

CHICKEN = Nutrients(kcal=165, protein=31.02, fat=3.57, carbs=0)


def test_scale_is_linear_in_grams():
    n = calc.scale(CHICKEN, 150)
    assert n.kcal == pytest.approx(247.5)
    assert n.protein == pytest.approx(46.53)
    assert n.fat == pytest.approx(5.355)
    assert n.carbs == 0


def test_scale_100g_is_identity():
    assert calc.scale(CHICKEN, 100) == CHICKEN


def test_round1_rounds_half_up_not_bankers():
    assert calc.round1(0.25) == 0.3
    assert calc.round1(0.35) == 0.4
    assert calc.round1(2.5) == 2.5
    assert calc.round1(247.49999999999997) == 247.5
    assert calc.round1(0.04) == 0.0


def test_rounded_applies_to_all_fields():
    n = calc.rounded(Nutrients(kcal=10.04, protein=1.25, fat=0.05, carbs=99.94))
    assert (n.kcal, n.protein, n.fat, n.carbs) == (10.0, 1.3, 0.1, 99.9)


def test_total_sums_unrounded_then_caller_rounds():
    a = Nutrients(kcal=0.04, protein=0.04, fat=0.04, carbs=0.04)
    t = calc.total([a, a, a])  # 0.12 -> 0.1, whereas rounding parts first would give 0.0
    assert calc.rounded(t).kcal == 0.1


def test_total_of_nothing_is_zero():
    assert calc.total([]) == Nutrients(kcal=0, protein=0, fat=0, carbs=0)


def test_atwater():
    assert calc.atwater_kcal(protein=10, fat=10, carbs=10) == 170
