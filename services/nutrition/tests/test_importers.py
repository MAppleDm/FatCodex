import zipfile

import pytest

from nutrition import db
from nutrition.importers import off, usda


def by_id(records):
    return {r.source_id: r for r in records}


def test_usda_imports_only_supported_types(usda_dir):
    recs = by_id(usda.iter_usda(usda_dir))
    assert "400001" not in recs  # branded
    assert "200002" not in recs  # no carbs
    assert set(recs) == {"100001", "100002", "100003", "100004", "100005", "100006", "100007", "200001", "300001"}


def test_usda_maps_sources_and_values(usda_dir):
    recs = by_id(usda.iter_usda(usda_dir))
    chicken = recs["100001"]
    assert chicken.source == "usda_sr_legacy"
    assert (chicken.kcal, chicken.protein, chicken.fat, chicken.carbs) == (165, 31.02, 3.57, 0)
    assert recs["200001"].source == "usda_foundation"
    assert recs["300001"].source == "usda_fndds"


def test_usda_uses_atwater_energy_id_when_1008_missing(usda_dir):
    assert by_id(usda.iter_usda(usda_dir))["200001"].kcal == 884


def test_usda_derives_kcal_when_no_energy_row(usda_dir):
    lettuce = by_id(usda.iter_usda(usda_dir))["100007"]
    assert lettuce.derived is True
    assert lettuce.kcal == pytest.approx(4 * 1.36 + 9 * 0.15 + 4 * 2.87, abs=0.01)


def test_usda_types_filter(usda_dir):
    recs = by_id(usda.iter_usda(usda_dir, types=["foundation_food"]))
    assert set(recs) == {"200001"}


def test_usda_extract_needed_finds_files_in_nested_folder(usda_dir, tmp_path):
    archive = tmp_path / "fdc.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        for name in ("food.csv", "food_nutrient.csv"):
            zf.write(usda_dir / name, f"FoodData_Central_csv_2018-04/{name}")
        zf.writestr("FoodData_Central_csv_2018-04/nutrient.csv", "id,name\n")
    out = usda.extract_needed(archive, tmp_path / "out")
    assert sorted(p.name for p in out.iterdir()) == ["food.csv", "food_nutrient.csv"]
    assert len(list(usda.iter_usda(out))) == 9


def test_usda_extract_needed_reports_missing_file(tmp_path):
    archive = tmp_path / "bad.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("food.csv", "fdc_id\n")
    with pytest.raises(FileNotFoundError, match="food_nutrient.csv"):
        usda.extract_needed(archive, tmp_path / "out")


def test_off_keeps_russia_and_drops_bad_rows(off_file):
    recs = by_id(off.iter_off(off_file))
    assert set(recs) == {"4600000000001", "4600000000002"}


def test_off_brand_is_first_of_list_and_kj_converted(off_file):
    recs = by_id(off.iter_off(off_file))
    assert recs["4600000000001"].brand == "Danone"
    kefir = recs["4600000000002"]
    assert kefir.kcal == pytest.approx(400 / 4.184, abs=0.01)
    assert kefir.derived is False


def test_off_country_filter_is_configurable(off_file):
    assert set(by_id(off.iter_off(off_file, countries=["en:france"]))) == {"3000000000003"}
    assert len(list(off.iter_off(off_file, countries=[]))) == 3  # all valid rows, any country


def test_off_reads_gzip_and_honours_limit(off_gz_file):
    assert len(list(off.iter_off(off_gz_file))) == 2
    assert len(list(off.iter_off(off_gz_file, limit=1))) == 1


def test_upsert_is_idempotent_and_updates_in_place(usda_dir, tmp_path):
    path = tmp_path / "foods.db"
    db.init_db(path)
    conn = db.connect(path)
    try:
        assert db.upsert_foods(conn, usda.iter_usda(usda_dir)) == 9
        assert db.upsert_foods(conn, usda.iter_usda(usda_dir)) == 9
        assert db.count_foods(conn) == 9
        fts_rows = conn.execute("SELECT COUNT(*) FROM foods_fts").fetchone()[0]
        assert fts_rows == 9  # no stale index rows after re-import
    finally:
        conn.close()
