from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    db_path: str = "/data/foods.db"
    min_coverage: float = 0.75
    # When the database is empty at startup, download USDA SR Legacy (~7 MB) in the background.
    # Off by default so tests and library use never touch the network; from_env() turns it on.
    auto_seed: bool = False

    @classmethod
    def from_env(cls) -> Settings:
        raw = os.environ.get("NUTRITION_AUTO_SEED", "true").strip().lower()
        return cls(
            db_path=os.environ.get("NUTRITION_DB_PATH", cls.db_path),
            min_coverage=float(os.environ.get("NUTRITION_MIN_COVERAGE", cls.min_coverage)),
            auto_seed=raw in {"1", "true", "yes", "on"},
        )
