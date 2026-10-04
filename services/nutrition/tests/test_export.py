"""The catalog the Android app bundles (local mode) is exported from the same database."""

import gzip

from nutrition.cli import export_tsv


def rows(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f]


def test_export_contains_generic_foods_only(seeded_db_path, tmp_path):
    out = tmp_path / "foods.tsv.gz"
    count = export_tsv(seeded_db_path, str(out), ["usda_sr_legacy"])
    data = rows(out)
    assert count == len(data) == 7  # the SR Legacy sample; no FNDDS, Foundation or Open Food Facts rows
    assert {len(r) for r in data} == {6}
    chicken = next(r for r in data if r[1].startswith("Chicken, broilers or fryers, breast"))
    assert chicken[2:] == ["165", "31.02", "3.57", "0"]  # kcal, protein, fat, carbs; trailing zeros trimmed


def test_other_sources_can_be_requested(seeded_db_path, tmp_path):
    out = tmp_path / "all.tsv.gz"
    assert export_tsv(seeded_db_path, str(out), ["usda_sr_legacy", "usda_foundation", "usda_fndds", "off"]) == 11


def test_export_is_reproducible(seeded_db_path, tmp_path):
    a, b = tmp_path / "a.tsv.gz", tmp_path / "b.tsv.gz"
    export_tsv(seeded_db_path, str(a))
    export_tsv(seeded_db_path, str(b))
    assert a.read_bytes() == b.read_bytes()  # no timestamps: regenerating the asset gives a clean diff


def test_names_with_tabs_do_not_break_the_format(tmp_path):
    from nutrition import db
    from nutrition.records import FoodRecord

    path = tmp_path / "t.db"
    db.init_db(path)
    conn = db.connect(path)
    db.upsert_foods(conn, [FoodRecord("usda_sr_legacy", "1", "Odd\tname, raw", None, 10, 1, 1, 1)])
    conn.close()
    out = tmp_path / "t.tsv.gz"
    export_tsv(str(path), str(out))
    assert rows(out) == [["1", "Odd name, raw", "10", "1", "1", "1"]]
