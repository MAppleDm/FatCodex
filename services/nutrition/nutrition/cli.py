"""Data management commands:  python -m nutrition.cli <command>

  import-usda DIR [DIR...]   load extracted FDC CSV folders (food.csv + food_nutrient.csv)
  import-off FILE            load an Open Food Facts CSV dump (.csv or .csv.gz)
  seed                       download USDA SR Legacy (~7 MB) and import it
  export-tsv OUT             write the catalog bundled into the Android app (local mode)
  stats                      print row counts per source
"""

from __future__ import annotations

import argparse
import gzip
import tempfile
import urllib.request
from pathlib import Path

from . import db
from .config import Settings
from .importers import off, usda

# SR Legacy is frozen (April 2018), so this URL is stable. Newer datasets (Foundation, FNDDS)
# change name with every release: download them from https://fdc.nal.usda.gov/download-datasets
# and pass the extracted folder to `import-usda`, or pass their URL via `seed --url`.
SR_LEGACY_URL = "https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip"


def _import(records, db_path: str) -> int:
    db.init_db(db_path)
    conn = db.connect(db_path)
    try:
        return db.upsert_foods(conn, records)
    finally:
        conn.close()


def cmd_import_usda(args: argparse.Namespace) -> None:
    total = 0
    for directory in args.dirs:
        n = _import(usda.iter_usda(directory, args.types), args.db)
        print(f"{directory}: {n} foods")
        total += n
    print(f"imported {total} USDA foods into {args.db}")


def cmd_import_off(args: argparse.Namespace) -> None:
    countries = [] if args.all_countries else args.countries
    n = _import(off.iter_off(args.file, countries, args.limit), args.db)
    print(f"imported {n} Open Food Facts products into {args.db}")


def seed(db_path: str, urls: list[str] | None = None) -> int:
    """Download FDC CSV archives (default: SR Legacy) and import them. Returns the number of foods."""
    total = 0
    with tempfile.TemporaryDirectory() as tmp:
        for i, url in enumerate(urls or [SR_LEGACY_URL]):
            zip_path = Path(tmp) / f"usda{i}.zip"
            print(f"downloading {url}", flush=True)
            urllib.request.urlretrieve(url, zip_path)
            folder = usda.extract_needed(zip_path, Path(tmp) / f"usda{i}")
            total += _import(usda.iter_usda(folder), db_path)
    return total


def cmd_seed(args: argparse.Namespace) -> None:
    print(f"seeded {seed(args.db, args.url)} foods into {args.db}")


def export_tsv(db_path: str, out_path: str, sources: list[str] | None = None) -> int:
    """Write foods as gzipped TSV (id, name, kcal, protein, fat, carbs): the catalog the Android app ships.

    Only generic sources are exported by default; branded Open Food Facts rows would bloat the APK.
    """
    wanted = sources or ["usda_sr_legacy"]
    conn = db.connect(db_path)
    try:
        marks = ",".join("?" * len(wanted))
        rows = conn.execute(
            f"SELECT source_id, name, kcal, protein, fat, carbs FROM foods WHERE source IN ({marks}) ORDER BY source_id",
            wanted,
        ).fetchall()
    finally:
        conn.close()
    # no file name and mtime=0 in the gzip header, fixed level: regenerating gives byte-identical output
    with open(out_path, "wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=9, mtime=0) as gz:
        for r in rows:
            line = "\t".join([r["source_id"], r["name"].replace("\t", " "), f"{r['kcal']:g}", f"{r['protein']:g}",
                              f"{r['fat']:g}", f"{r['carbs']:g}"])
            gz.write((line + "\n").encode("utf-8"))
    return len(rows)


def cmd_export_tsv(args: argparse.Namespace) -> None:
    print(f"exported {export_tsv(args.db, str(args.out), args.source)} foods to {args.out}")


def cmd_stats(args: argparse.Namespace) -> None:
    db.init_db(args.db)
    conn = db.connect(args.db)
    try:
        rows = conn.execute("SELECT source, COUNT(*) AS n FROM foods GROUP BY source ORDER BY source").fetchall()
    finally:
        conn.close()
    for row in rows:
        print(f"{row['source']}: {row['n']}")
    print(f"total: {sum(r['n'] for r in rows)}")


def build_parser() -> argparse.ArgumentParser:
    default_db = Settings.from_env().db_path
    parser = argparse.ArgumentParser(prog="nutrition.cli", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", default=default_db, help=f"SQLite path (default: {default_db})")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("import-usda")
    p.add_argument("dirs", nargs="+", type=Path)
    p.add_argument("--types", nargs="+", choices=list(usda.DATA_TYPES), default=list(usda.DEFAULT_TYPES))
    p.set_defaults(func=cmd_import_usda)

    p = sub.add_parser("import-off")
    p.add_argument("file", type=Path)
    p.add_argument("--countries", nargs="+", default=["en:russia"], help="countries_tags values to keep")
    p.add_argument("--all-countries", action="store_true", help="keep every country (very large)")
    p.add_argument("--limit", type=int, default=None)
    p.set_defaults(func=cmd_import_off)

    p = sub.add_parser("seed")
    p.add_argument("--url", action="append", help="FDC CSV zip URL (repeatable); default: SR Legacy")
    p.set_defaults(func=cmd_seed)

    p = sub.add_parser("export-tsv", help="write the Android app's bundled catalog")
    p.add_argument("out", type=Path)
    p.add_argument("--source", action="append", help="source to export (repeatable); default: usda_sr_legacy")
    p.set_defaults(func=cmd_export_tsv)

    p = sub.add_parser("stats")
    p.set_defaults(func=cmd_stats)
    return parser


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
