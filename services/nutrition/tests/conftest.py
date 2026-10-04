"""Shared fixtures: tiny USDA/OFF samples written on the fly (values are realistic, not authoritative)."""

from __future__ import annotations

import csv
import gzip
from pathlib import Path

import pytest

from nutrition import db
from nutrition.importers import off, usda

# fdc_id, data_type, description, {nutrient_id: amount}
USDA_FOODS = [
    ("100001", "sr_legacy_food", "Chicken, broilers or fryers, breast, meat only, cooked, roasted",
     {1008: 165, 1003: 31.02, 1004: 3.57, 1005: 0}),
    ("100002", "sr_legacy_food", "Chicken, broilers or fryers, drumstick, meat only, cooked, roasted",
     {1008: 175, 1003: 28.3, 1004: 5.7, 1005: 0}),
    ("100003", "sr_legacy_food", "Buckwheat groats, roasted, cooked",
     {1008: 92, 1003: 3.38, 1004: 0.62, 1005: 19.94}),
    ("100004", "sr_legacy_food", "Egg, whole, cooked, hard-boiled",
     {1008: 155, 1003: 12.58, 1004: 10.61, 1005: 1.12}),
    ("100005", "sr_legacy_food", "Butter, salted",
     {1008: 717, 1003: 0.85, 1004: 81.11, 1005: 0.06}),
    ("100006", "sr_legacy_food", "Bananas, raw",
     {1008: 89, 1003: 1.09, 1004: 0.33, 1005: 22.84}),
    # no energy row at all: kcal must be derived from macros
    ("100007", "sr_legacy_food", "Lettuce, raw",
     {1003: 1.36, 1004: 0.15, 1005: 2.87}),
    # Foundation foods often carry only the Atwater energy ids
    ("200001", "foundation_food", "Oil, olive, extra virgin",
     {2047: 884, 1003: 0, 1004: 100, 1005: 0}),
    # missing carbs: unusable
    ("200002", "foundation_food", "Mystery food", {1008: 100, 1003: 5, 1004: 5}),
    ("300001", "survey_fndds_food", "Omelet, plain",
     {1008: 154, 1003: 10.6, 1004: 11.7, 1005: 0.8}),
    # branded foods are not imported
    ("400001", "branded_food", "BRANDED CHICKEN NUGGETS",
     {1008: 250, 1003: 14, 1004: 15, 1005: 13}),
]

OFF_COLUMNS = [
    "code", "product_name", "brands", "countries_tags",
    "energy-kcal_100g", "energy_100g", "proteins_100g", "fat_100g", "carbohydrates_100g",
]
OFF_ROWS = [
    ["4600000000001", "Растишка клубника", "Danone,Растишка", "en:russia", "92", "385", "3.1", "2.8", "14"],
    # only kJ on the label: 400 kJ = 95.6 kcal
    ["4600000000002", "Кефир 2.5%", "Простоквашино", "en:russia,en:belarus", "", "400", "2.9", "2.5", "4"],
    # not sold in Russia
    ["3000000000003", "Crème fraîche", "Isigny", "en:france", "292", "1200", "2.4", "30", "2.8"],
    # no name
    ["4600000000004", "", "", "en:russia", "100", "418", "5", "5", "5"],
    # impossible energy
    ["4600000000005", "Волшебный батончик", "", "en:russia", "5000", "", "10", "10", "10"],
    # macros add up to more than 100 g
    ["4600000000006", "Невозможный продукт", "", "en:russia", "300", "", "60", "50", "40"],
]


def _write_usda(directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    with open(directory / "food.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["fdc_id", "data_type", "description", "food_category_id", "publication_date"])
        for fdc_id, data_type, description, _ in USDA_FOODS:
            w.writerow([fdc_id, data_type, description, "", "2019-04-01"])
    with open(directory / "food_nutrient.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id", "fdc_id", "nutrient_id", "amount"])
        n = 1
        for fdc_id, _, _, nutrients in USDA_FOODS:
            for nutrient_id, amount in nutrients.items():
                w.writerow([n, fdc_id, nutrient_id, amount])
                n += 1
            w.writerow([n, fdc_id, 1093, 42])  # sodium: must be ignored
            n += 1
    return directory


def _off_text() -> str:
    lines = ["\t".join(OFF_COLUMNS)] + ["\t".join(row) for row in OFF_ROWS]
    return "\n".join(lines) + "\n"


@pytest.fixture(scope="session")
def usda_dir(tmp_path_factory) -> Path:
    return _write_usda(tmp_path_factory.mktemp("usda"))


@pytest.fixture(scope="session")
def off_file(tmp_path_factory) -> Path:
    path = tmp_path_factory.mktemp("off") / "products.csv"
    path.write_text(_off_text(), encoding="utf-8")
    return path


@pytest.fixture(scope="session")
def off_gz_file(tmp_path_factory) -> Path:
    path = tmp_path_factory.mktemp("offgz") / "products.csv.gz"
    with gzip.open(path, "wt", encoding="utf-8", newline="") as f:
        f.write(_off_text())
    return path


@pytest.fixture(scope="session")
def seeded_db_path(tmp_path_factory, usda_dir, off_file) -> str:
    """A DB with both samples imported. Read-only for tests."""
    path = str(tmp_path_factory.mktemp("db") / "foods.db")
    db.init_db(path)
    conn = db.connect(path)
    try:
        db.upsert_foods(conn, usda.iter_usda(usda_dir))
        db.upsert_foods(conn, off.iter_off(off_file))
    finally:
        conn.close()
    return path


@pytest.fixture()
def conn(seeded_db_path):
    c = db.connect(seeded_db_path)
    yield c
    c.close()
