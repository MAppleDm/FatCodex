import pytest
from fastapi.testclient import TestClient

from nutrition.config import Settings
from nutrition.main import create_app


@pytest.fixture()
def client(seeded_db_path):
    app = create_app(Settings(db_path=seeded_db_path, min_coverage=0.75))
    with TestClient(app) as c:
        yield c


def lookup(client, *items):
    resp = client.post("/v1/lookup", json={"items": list(items)})
    assert resp.status_code == 200, resp.text
    return resp.json()


def test_healthz_reports_food_count(client):
    assert client.get("/healthz").json() == {"status": "ok", "foods": 11}


def test_lookup_computes_kbzhu_for_grams(client):
    body = lookup(client, {"name": "куриная грудка", "query": "chicken breast cooked", "grams": 150})
    item = body["items"][0]
    assert item["matched"] is True
    assert item["food"]["name"].startswith("Chicken, broilers or fryers, breast")
    assert item["nutrients"] == {"kcal": 247.5, "protein": 46.5, "fat": 5.4, "carbs": 0.0}
    assert body["totals"]["kcal"] == 247.5


def test_lookup_unmatched_item_has_no_numbers_and_is_excluded_from_totals(client):
    body = lookup(
        client,
        {"name": "гречка", "query": "buckwheat cooked", "grams": 200},
        {"name": "суши", "query": "sushi", "grams": 300},
    )
    buckwheat, sushi = body["items"]
    assert buckwheat["nutrients"]["kcal"] == 184.0
    assert sushi["matched"] is False
    assert sushi["food"] is None and sushi["nutrients"] is None
    assert body["totals"]["kcal"] == 184.0


def test_lookup_near_miss_offers_alternatives(client):
    # "chicken feet" shares only one word with the chicken foods: not a match, but worth suggesting
    item = lookup(client, {"name": "куриные лапки", "query": "chicken feet", "grams": 100})["items"][0]
    assert item["matched"] is False
    assert item["alternatives"]
    assert all("Chicken" in a for a in item["alternatives"])


def test_lookup_uses_name_when_query_is_absent(client):
    item = lookup(client, {"name": "растишка клубника", "grams": 125})["items"][0]
    assert item["matched"] is True
    assert item["food"]["source"] == "off"
    assert item["nutrients"]["kcal"] == 115.0  # 92 * 1.25


def test_lookup_preserves_order_and_sums_several_items(client):
    body = lookup(
        client,
        {"name": "egg", "query": "egg boiled", "grams": 100},
        {"name": "butter", "query": "butter", "grams": 10},
    )
    assert [i["name"] for i in body["items"]] == ["egg", "butter"]
    assert body["totals"]["kcal"] == pytest.approx(155 + 71.7, abs=0.05)


def test_min_coverage_is_configurable(seeded_db_path):
    app = create_app(Settings(db_path=seeded_db_path, min_coverage=0.5))
    with TestClient(app) as c:
        item = lookup(c, {"name": "chicken feet", "query": "chicken feet", "grams": 100})["items"][0]
    assert item["matched"] is True  # 1 of 2 words is now enough


@pytest.mark.parametrize(
    "payload",
    [
        {"items": []},
        {"items": [{"name": "x", "grams": 0}]},
        {"items": [{"name": "x", "grams": -5}]},
        {"items": [{"name": "x", "grams": 5001}]},
        {"items": [{"name": "", "grams": 10}]},
        {"items": [{"grams": 10}]},
        {},
    ],
)
def test_lookup_validates_input(client, payload):
    assert client.post("/v1/lookup", json=payload).status_code == 422


def test_search_endpoint(client):
    hits = client.get("/v1/foods/search", params={"q": "banana"}).json()
    assert hits[0]["food"]["name"] == "Bananas, raw"
    assert hits[0]["coverage"] == 1.0
    assert hits[0]["food"]["per100g"]["kcal"] == 89


def test_search_endpoint_validates_params(client):
    assert client.get("/v1/foods/search").status_code == 422
    assert client.get("/v1/foods/search", params={"q": "egg", "limit": 0}).status_code == 422


def test_empty_database_degrades_gracefully(tmp_path):
    app = create_app(Settings(db_path=str(tmp_path / "empty.db")))
    with TestClient(app) as c:
        assert c.get("/healthz").json()["foods"] == 0
        item = lookup(c, {"name": "egg", "grams": 50})["items"][0]
    assert item["matched"] is False
