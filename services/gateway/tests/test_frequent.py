from datetime import datetime, timedelta, timezone

from gateway.frequent import MealRow, frequent_meals, time_of_day

T0 = datetime(2026, 9, 1, 8, 0, tzinfo=timezone.utc)


def meal(meal_id, day, items, minutes=8 * 60):
    return [
        MealRow(meal_id, name, None, grams, minutes, T0 + timedelta(days=day), position=i)
        for i, (name, grams) in enumerate(items)
    ]


def rows(*meals):
    return [r for m in meals for r in m]


def test_time_of_day_buckets():
    assert [time_of_day(m) for m in (4 * 60 + 59, 5 * 60, 10 * 60 + 59, 11 * 60, 15 * 60 + 59, 16 * 60, 21 * 60 + 59, 22 * 60, 0)] == [
        "ночью", "утром", "утром", "днём", "днём", "вечером", "вечером", "ночью", "ночью"]


def test_meal_repeated_three_times_is_habitual():
    result = frequent_meals(rows(*(meal(d, d, [("овсянка", 200), ("банан", 100)]) for d in range(3))))
    [m] = result
    assert m.label == "овсянка, банан (утром)"
    assert [(i.name, i.grams) for i in m.items] == [("овсянка", 200), ("банан", 100)]


def test_two_repeats_are_not_enough():
    assert frequent_meals(rows(*(meal(d, d, [("овсянка", 200)]) for d in range(2)))) == []


def test_min_repeats_is_configurable():
    data = rows(*(meal(d, d, [("овсянка", 200)]) for d in range(2)))
    assert len(frequent_meals(data, min_repeats=2)) == 1


def test_same_foods_match_regardless_of_case_order_and_grams():
    data = rows(
        meal(1, 1, [("Овсянка", 150), ("банан", 100)]),
        meal(2, 2, [("банан", 120), ("овсянка", 200)]),
        meal(3, 3, [("овсянка", 250), ("Банан", 90)]),
    )
    [m] = frequent_meals(data)
    assert len(m.items) == 2


def test_different_foods_are_different_meals():
    data = rows(
        meal(1, 1, [("овсянка", 200)]), meal(2, 2, [("овсянка", 200)]),
        meal(3, 3, [("овсянка", 200), ("банан", 100)]),
    )
    assert frequent_meals(data) == []


def test_latest_occurrence_supplies_the_grams():
    data = rows(meal(1, 1, [("овсянка", 100)]), meal(2, 2, [("овсянка", 300)]), meal(3, 3, [("овсянка", 200)]))
    assert frequent_meals(data)[0].items[0].grams == 200
    data = rows(meal(3, 3, [("овсянка", 200)]), meal(1, 1, [("овсянка", 100)]), meal(2, 2, [("овсянка", 300)]))
    assert frequent_meals(data)[0].items[0].grams == 200  # input order does not matter


def test_most_repeated_first_and_limited():
    data = rows(
        *(meal(f"a{d}", d, [("кофе", 200)], 8 * 60) for d in range(3)),
        *(meal(f"b{d}", d, [("борщ", 300)], 13 * 60) for d in range(5)),
        *(meal(f"c{d}", d, [("чай", 250)], 20 * 60) for d in range(4)),
    )
    labels = [m.label for m in frequent_meals(data)]
    assert labels == ["борщ (днём)", "чай (вечером)", "кофе (утром)"]
    assert [m.label for m in frequent_meals(data, limit=1)] == ["борщ (днём)"]


def test_usual_time_is_the_most_common_one():
    data = rows(meal(1, 1, [("кофе", 200)], 8 * 60), meal(2, 2, [("кофе", 200)], 9 * 60), meal(3, 3, [("кофе", 200)], 15 * 60))
    assert frequent_meals(data)[0].label == "кофе (утром)"


def test_label_lists_at_most_three_foods_but_items_keep_all():
    foods = [("а", 1), ("б", 1), ("в", 1), ("г", 1)]
    [m] = frequent_meals(rows(*(meal(d, d, foods) for d in range(3))))
    assert m.label == "а, б, в (утром)" and len(m.items) == 4


def test_nothing_in_nothing_out():
    assert frequent_meals([]) == []
