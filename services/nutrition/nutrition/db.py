"""SQLite storage: a `foods` table plus an FTS5 index over folded, stopword-free names."""

from __future__ import annotations

import sqlite3
from collections.abc import Iterable
from pathlib import Path

from .records import FoodRecord
from .text import search_text

SCHEMA = """
CREATE TABLE IF NOT EXISTS foods (
    id        INTEGER PRIMARY KEY,
    source    TEXT NOT NULL,
    source_id TEXT NOT NULL,
    name      TEXT NOT NULL,
    brand     TEXT,
    kcal      REAL NOT NULL,
    protein   REAL NOT NULL,
    fat       REAL NOT NULL,
    carbs     REAL NOT NULL,
    derived   INTEGER NOT NULL DEFAULT 0,
    UNIQUE (source, source_id)
);
CREATE VIRTUAL TABLE IF NOT EXISTS foods_fts USING fts5(
    search,
    tokenize = 'porter unicode61 remove_diacritics 2'
);
"""


def connect(path: str | Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    return conn


def init_db(path: str | Path) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = connect(path)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript(SCHEMA)
        conn.commit()
    finally:
        conn.close()


def upsert_foods(conn: sqlite3.Connection, records: Iterable[FoodRecord], batch: int = 5000) -> int:
    """Insert or update records (keyed by source+source_id), keeping FTS in sync. Returns rows written."""
    written = 0
    with conn:
        for rec in records:
            row = conn.execute(
                "SELECT id FROM foods WHERE source = ? AND source_id = ?", (rec.source, rec.source_id)
            ).fetchone()
            values = (rec.name, rec.brand, rec.kcal, rec.protein, rec.fat, rec.carbs, int(rec.derived))
            if row is None:
                cur = conn.execute(
                    "INSERT INTO foods (name, brand, kcal, protein, fat, carbs, derived, source, source_id)"
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (*values, rec.source, rec.source_id),
                )
                food_id = cur.lastrowid
            else:
                food_id = row["id"]
                conn.execute(
                    "UPDATE foods SET name = ?, brand = ?, kcal = ?, protein = ?, fat = ?, carbs = ?, derived = ?"
                    " WHERE id = ?",
                    (*values, food_id),
                )
                conn.execute("DELETE FROM foods_fts WHERE rowid = ?", (food_id,))
            conn.execute(
                "INSERT INTO foods_fts (rowid, search) VALUES (?, ?)", (food_id, search_text(rec.name, rec.brand))
            )
            written += 1
            if written % batch == 0:
                conn.commit()
    return written


def count_foods(conn: sqlite3.Connection) -> int:
    return conn.execute("SELECT COUNT(*) FROM foods").fetchone()[0]
