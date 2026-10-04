"""Open Food Facts CSV import.

Reads the official tab-separated dump (`en.openfoodfacts.org.products.csv[.gz]`) as a stream,
keeping only rows from the requested countries with usable per-100g values.
"""

from __future__ import annotations

import csv
import gzip
import sys
from collections.abc import Iterable, Iterator
from pathlib import Path

from ..records import FoodRecord, build_record

SOURCE = "off"


def _number(raw: str | None) -> float | None:
    try:
        return float(raw) if raw not in (None, "") else None
    except ValueError:
        return None


def iter_off(
    path: str | Path,
    countries: Iterable[str] = ("en:russia",),
    limit: int | None = None,
) -> Iterator[FoodRecord]:
    """Yield validated records. An empty `countries` keeps every country (huge for the full dump)."""
    path = Path(path)
    wanted = {c.strip().lower() for c in countries if c.strip()}
    opener = gzip.open if path.suffix == ".gz" else open
    csv.field_size_limit(min(sys.maxsize, 2**31 - 1))

    produced = 0
    with opener(path, "rt", encoding="utf-8", newline="") as f:
        # The dump is unquoted TSV; QUOTE_NONE avoids choking on stray quote characters in names.
        for row in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            if wanted:
                row_countries = {c.strip().lower() for c in (row.get("countries_tags") or "").split(",")}
                if not wanted & row_countries:
                    continue
            code = (row.get("code") or "").strip()
            if not code:
                continue
            brand = (row.get("brands") or "").split(",")[0]
            record = build_record(
                SOURCE,
                code,
                row.get("product_name"),
                brand,
                _number(row.get("energy-kcal_100g")),
                _number(row.get("proteins_100g")),
                _number(row.get("fat_100g")),
                _number(row.get("carbohydrates_100g")),
                kj=_number(row.get("energy_100g")),
            )
            if record:
                yield record
                produced += 1
                if limit is not None and produced >= limit:
                    return
