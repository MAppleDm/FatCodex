from __future__ import annotations

import logging
import sqlite3
import threading
from collections.abc import Callable, Iterator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Query, Request

from . import db
from .config import Settings
from .schemas import Health, LookupRequest, LookupResponse, SearchHit
from .search import FoodSearcher
from .service import food_out, lookup

log = logging.getLogger(__name__)


def _default_seeder(db_path: str) -> int:
    from .cli import seed

    return seed(db_path)


def create_app(settings: Settings | None = None, seeder: Callable[[str], int] = _default_seeder) -> FastAPI:
    settings = settings or Settings.from_env()

    def run_seed() -> None:
        try:
            log.warning("food database is empty: downloading USDA SR Legacy in the background")
            log.warning("seeded %d foods", seeder(settings.db_path))
        except Exception:  # noqa: BLE001 - the API keeps working (and says foods=0) if the download fails
            log.exception("seeding failed; run `python -m nutrition.cli seed` later")

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        db.init_db(settings.db_path)
        app.state.seed_thread = None
        if settings.auto_seed:
            conn = db.connect(settings.db_path)
            try:
                empty = db.count_foods(conn) == 0
            finally:
                conn.close()
            if empty:
                app.state.seed_thread = threading.Thread(target=run_seed, name="seed", daemon=True)
                app.state.seed_thread.start()
        yield

    app = FastAPI(title="DietApp nutrition", version="0.1.0", lifespan=lifespan)
    app.state.settings = settings

    def get_conn(request: Request) -> Iterator[sqlite3.Connection]:
        conn = db.connect(request.app.state.settings.db_path)
        try:
            yield conn
        finally:
            conn.close()

    @app.get("/healthz", response_model=Health, tags=["ops"])
    def healthz(conn: sqlite3.Connection = Depends(get_conn)) -> Health:
        return Health(status="ok", foods=db.count_foods(conn))

    @app.get("/v1/foods/search", response_model=list[SearchHit], tags=["foods"])
    def search_foods(
        q: str = Query(min_length=1, max_length=200),
        limit: int = Query(default=5, ge=1, le=20),
        conn: sqlite3.Connection = Depends(get_conn),
    ) -> list[SearchHit]:
        matches = FoodSearcher(conn).search([q], limit=limit)
        return [SearchHit(food=food_out(m.food), score=m.score, coverage=m.coverage) for m in matches]

    @app.post("/v1/lookup", response_model=LookupResponse, tags=["foods"])
    def lookup_items(body: LookupRequest, conn: sqlite3.Connection = Depends(get_conn)) -> LookupResponse:
        """Match each item to a food and compute its kcal/protein/fat/carbs for the given grams.

        Unmatched items come back with `matched=false` and no numbers.
        """
        return lookup(conn, body.items, settings.min_coverage)

    return app


app = create_app()
