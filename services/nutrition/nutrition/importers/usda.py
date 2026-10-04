"""USDA FoodData Central CSV import (Foundation, SR Legacy, FNDDS).

Expects a directory with the extracted `food.csv` and `food_nutrient.csv` from a FDC CSV download.
"""

from __future__ import annotations

import csv
import io
import zipfile
from collections.abc import Iterable, Iterator
from pathlib import Path

from ..records import FoodRecord, build_record

DATA_TYPES = {
    "foundation_food": "usda_foundation",
    "sr_legacy_food": "usda_sr_legacy",
    "survey_fndds_food": "usda_fndds",
}
DEFAULT_TYPES = tuple(DATA_TYPES)

# Energy: SR Legacy/FNDDS use 1008; Foundation often only has the Atwater variants.
KCAL_IDS = (1008, 2047, 2048)
PROTEIN_ID, FAT_ID, CARBS_ID = 1003, 1004, 1005
NEEDED_FILES = ("food.csv", "food_nutrient.csv")


def _number(raw: str | None) -> float | None:
    try:
        return float(raw) if raw not in (None, "") else None
    except ValueError:
        return None


def iter_usda(directory: str | Path, types: Iterable[str] = DEFAULT_TYPES) -> Iterator[FoodRecord]:
    directory = Path(directory)
    wanted = {t: DATA_TYPES[t] for t in types}

    foods: dict[str, tuple[str, str]] = {}  # fdc_id -> (source, description)
    with open(directory / "food.csv", newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            source = wanted.get(row["data_type"])
            if source:
                foods[row["fdc_id"]] = (source, row["description"])

    wanted_ids = {PROTEIN_ID, FAT_ID, CARBS_ID, *KCAL_IDS}
    values: dict[str, dict[int, float]] = {}
    with open(directory / "food_nutrient.csv", newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            fdc_id = row["fdc_id"]
            if fdc_id not in foods:
                continue
            try:
                nutrient_id = int(row["nutrient_id"])
            except ValueError:
                continue
            amount = _number(row["amount"])
            if nutrient_id in wanted_ids and amount is not None:
                values.setdefault(fdc_id, {})[nutrient_id] = amount

    for fdc_id, (source, description) in foods.items():
        v = values.get(fdc_id, {})
        kcal = next((v[i] for i in KCAL_IDS if i in v), None)
        record = build_record(
            source,
            fdc_id,
            description,
            None,
            kcal,
            v.get(PROTEIN_ID),
            v.get(FAT_ID),
            v.get(CARBS_ID),
        )
        if record:
            yield record


def extract_needed(zip_path: str | Path, dest: str | Path) -> Path:
    """Extract only food.csv and food_nutrient.csv (wherever they sit in the archive) into `dest`."""
    dest = Path(dest)
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as zf:
        by_name = {Path(n).name: n for n in zf.namelist() if not n.endswith("/")}
        for name in NEEDED_FILES:
            if name not in by_name:
                raise FileNotFoundError(f"{name} not found in {zip_path}")
            with zf.open(by_name[name]) as src, open(dest / name, "wb") as out:
                while chunk := src.read(io.DEFAULT_BUFFER_SIZE * 16):
                    out.write(chunk)
    return dest
