"""First-start seeding: the API must come up at once and fill the database in the background."""

from fastapi.testclient import TestClient

from nutrition import db
from nutrition.config import Settings
from nutrition.importers import usda
from nutrition.main import create_app


class Seeder:
    def __init__(self, usda_dir=None, fail=False):
        self.calls = []
        self.usda_dir = usda_dir
        self.fail = fail

    def __call__(self, db_path):
        self.calls.append(db_path)
        if self.fail:
            raise OSError("no network")
        conn = db.connect(db_path)
        try:
            return db.upsert_foods(conn, usda.iter_usda(self.usda_dir))
        finally:
            conn.close()


def run(tmp_path, seeder, **settings):
    path = str(tmp_path / "foods.db")
    app = create_app(Settings(db_path=path, **settings), seeder=seeder)
    with TestClient(app) as client:
        thread = app.state.seed_thread
        if thread:
            thread.join(timeout=10)
        return client.get("/healthz").json(), thread


def test_empty_database_is_seeded_in_the_background(tmp_path, usda_dir):
    seeder = Seeder(usda_dir)
    health, thread = run(tmp_path, seeder, auto_seed=True)
    assert thread is not None
    assert len(seeder.calls) == 1
    assert health["foods"] == 9


def test_existing_data_is_left_alone(tmp_path, usda_dir):
    path = tmp_path / "foods.db"
    db.init_db(path)
    conn = db.connect(path)
    db.upsert_foods(conn, usda.iter_usda(usda_dir))
    conn.close()
    seeder = Seeder(usda_dir)
    health, thread = run(tmp_path, seeder, auto_seed=True)
    assert thread is None and seeder.calls == []
    assert health["foods"] == 9


def test_seeding_can_be_switched_off(tmp_path, usda_dir):
    seeder = Seeder(usda_dir)
    health, thread = run(tmp_path, seeder, auto_seed=False)
    assert thread is None and seeder.calls == []
    assert health["foods"] == 0


def test_a_failed_download_does_not_take_the_service_down(tmp_path):
    seeder = Seeder(fail=True)
    health, thread = run(tmp_path, seeder, auto_seed=True)
    assert len(seeder.calls) == 1
    assert health == {"status": "ok", "foods": 0}


def test_auto_seed_is_on_by_default_only_from_the_environment(monkeypatch):
    assert Settings().auto_seed is False
    monkeypatch.delenv("NUTRITION_AUTO_SEED", raising=False)
    assert Settings.from_env().auto_seed is True
    monkeypatch.setenv("NUTRITION_AUTO_SEED", "false")
    assert Settings.from_env().auto_seed is False
