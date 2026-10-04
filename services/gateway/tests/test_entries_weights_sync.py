"""In-place edits, weights, sync, goal, voice proxy."""

import uuid

import pytest

import gateway.routes.diary as diary
from gateway.errors import ApiError

from .conftest import add, update


def new_entry(env, headers, *items, text="еда", **kw):
    env.ai.will_return(*items)
    return env.post_message(headers, text, **kw).json()["entries"]


# ---------- PATCH / DELETE entries ----------

def test_patch_grams_is_pure_arithmetic(env, auth):
    [butter] = new_entry(env, auth, add("масло", "butter", 20))
    calls = len(env.nutrition.calls)
    env.clock.advance(seconds=5)
    r = env.client.patch(f"/v1/entries/{butter['id']}", headers=auth, json={"grams": 10})
    assert r.status_code == 200
    e = r.json()
    assert (e["grams"], e["kcal"], e["fat"]) == (10, 71.7, 8.1)
    assert e["per100"] == butter["per100"] and e["updated_at"] > butter["updated_at"]
    assert len(env.nutrition.calls) == calls


def test_patch_confirms_an_uncertain_amount(env, auth):
    [e] = new_entry(env, auth, add("гречка", "buckwheat cooked", 200, conf=0.4, q="Сколько грамм?"))
    assert e["status"] == "uncertain"
    r = env.client.patch(f"/v1/entries/{e['id']}", headers=auth, json={"grams": 150}).json()
    assert r["status"] == "ok" and r["confidence"] == 1.0 and r["kcal"] == 138.0


def test_patch_name_looks_the_food_up_again(env, auth):
    [e] = new_entry(env, auth, add("гречка", "buckwheat cooked", 200))
    env.nutrition.calls.clear()
    r = env.client.patch(f"/v1/entries/{e['id']}", headers=auth, json={"name": "butter"}).json()
    assert r["name"] == "butter" and r["food_name"] == "Butter, salted" and r["kcal"] == 1434.0
    assert len(env.nutrition.calls) == 1


def test_patch_to_an_unknown_food_clears_the_numbers(env, auth):
    [e] = new_entry(env, auth, add("гречка", "buckwheat cooked", 200))
    r = env.client.patch(f"/v1/entries/{e['id']}", headers=auth, json={"name": "неведомая штука"}).json()
    assert r["status"] == "unmatched" and r["kcal"] is None and r["per100"] is None


def test_patch_with_the_same_name_does_not_look_up(env, auth):
    [e] = new_entry(env, auth, add("гречка", "buckwheat cooked", 200))
    env.nutrition.calls.clear()
    env.client.patch(f"/v1/entries/{e['id']}", headers=auth, json={"name": " ГРЕЧКА ", "grams": 100})
    assert env.nutrition.calls == []


@pytest.mark.parametrize("body", [{}, {"grams": 0}, {"grams": -1}, {"grams": 5001}, {"name": ""}])
def test_patch_validation(env, auth, body):
    [e] = new_entry(env, auth, add())
    assert env.client.patch(f"/v1/entries/{e['id']}", headers=auth, json=body).status_code == 422


def test_entries_of_other_users_do_not_exist(env, auth):
    other = env.login("other@example.com")
    [theirs] = new_entry(env, other, add())
    assert env.client.patch(f"/v1/entries/{theirs['id']}", headers=auth, json={"grams": 1}).status_code == 404
    assert env.client.delete(f"/v1/entries/{theirs['id']}", headers=auth).status_code == 404
    assert env.client.get("/v1/sync", headers=other).json()["entries"][0]["deleted"] is False


def test_unknown_entry_is_404(env, auth):
    r = env.client.patch(f"/v1/entries/{uuid.uuid4()}", headers=auth, json={"grams": 1})
    assert r.status_code == 404 and r.json()["error"]["code"] == "not_found"


def test_delete_is_idempotent_and_leaves_a_tombstone(env, auth):
    [e] = new_entry(env, auth, add())
    assert env.client.delete(f"/v1/entries/{e['id']}", headers=auth).status_code == 204
    assert env.client.delete(f"/v1/entries/{e['id']}", headers=auth).status_code == 204
    [tomb] = env.client.get("/v1/sync", headers=auth).json()["entries"]
    assert tomb["id"] == e["id"] and tomb["deleted"] is True
    assert env.client.patch(f"/v1/entries/{e['id']}", headers=auth, json={"grams": 5}).status_code == 404


# ---------- weights ----------

def test_weight_create_update_delete(env, auth):
    wid = uuid.uuid4()
    r = env.client.put(f"/v1/weights/{wid}", headers=auth, json={"day": "2026-09-30", "kg": 82.4})
    assert r.status_code == 200 and r.json()["kg"] == 82.4 and r.json()["deleted"] is False

    env.clock.advance(seconds=10)
    r2 = env.client.put(f"/v1/weights/{wid}", headers=auth, json={"day": "2026-09-30", "kg": 82.1})
    assert r2.json()["kg"] == 82.1 and r2.json()["updated_at"] > r.json()["updated_at"]
    assert len(env.client.get("/v1/sync", headers=auth).json()["weights"]) == 1

    assert env.client.delete(f"/v1/weights/{wid}", headers=auth).status_code == 204
    assert env.client.delete(f"/v1/weights/{wid}", headers=auth).status_code == 204
    assert env.client.get("/v1/sync", headers=auth).json()["weights"][0]["deleted"] is True


def test_putting_a_deleted_weight_again_revives_it(env, auth):
    wid = uuid.uuid4()
    env.client.put(f"/v1/weights/{wid}", headers=auth, json={"day": "2026-09-30", "kg": 80})
    env.client.delete(f"/v1/weights/{wid}", headers=auth)
    assert env.client.put(f"/v1/weights/{wid}", headers=auth, json={"day": "2026-09-30", "kg": 80}).json()["deleted"] is False


def test_weight_id_of_another_user_is_a_conflict(env, auth):
    other = env.login("other@example.com")
    wid = uuid.uuid4()
    env.client.put(f"/v1/weights/{wid}", headers=other, json={"day": "2026-09-30", "kg": 70})
    assert env.client.put(f"/v1/weights/{wid}", headers=auth, json={"day": "2026-09-30", "kg": 1e2}).status_code == 409
    assert env.client.delete(f"/v1/weights/{wid}", headers=auth).status_code == 404
    assert env.client.get("/v1/sync", headers=other).json()["weights"][0]["kg"] == 70


@pytest.mark.parametrize("kg", [0, 19.9, 400.1, -5])
def test_implausible_weights_are_rejected(env, auth, kg):
    assert env.client.put(f"/v1/weights/{uuid.uuid4()}", headers=auth, json={"day": "2026-09-30", "kg": kg}).status_code == 422


# ---------- sync ----------

def test_sync_returns_everything_then_only_changes(env, auth):
    new_entry(env, auth, add("гречка"), add("масло", "butter", 10))
    env.clock.advance(seconds=30)
    first = env.client.get("/v1/sync", headers=auth).json()
    assert len(first["entries"]) == 2 and first["has_more"] is False

    env.clock.advance(minutes=10)
    [changed] = new_entry(env, auth, add("хлеб", "bread white", 50))
    second = env.client.get("/v1/sync", headers=auth, params={"since": first["next_since"]}).json()
    assert [e["id"] for e in second["entries"]] == [changed["id"]]


def test_sync_cursor_lags_by_a_few_seconds_so_in_flight_commits_are_not_missed(env, auth):
    new_entry(env, auth, add("гречка"))
    first = env.client.get("/v1/sync", headers=auth).json()  # same instant as the write
    assert first["next_since"] < first["server_time"]
    again = env.client.get("/v1/sync", headers=auth, params={"since": first["next_since"]}).json()
    assert [e["id"] for e in again["entries"]] == [e["id"] for e in first["entries"]]  # re-delivered, harmless


def test_sync_since_is_inclusive_so_nothing_at_the_boundary_is_lost(env, auth):
    [e] = new_entry(env, auth, add())
    r = env.client.get("/v1/sync", headers=auth, params={"since": e["updated_at"]}).json()
    assert [x["id"] for x in r["entries"]] == [e["id"]]


def test_sync_includes_goal_and_is_scoped_to_the_user(env, auth):
    other = env.login("other@example.com")
    new_entry(env, other, add())
    env.client.put("/v1/me/goal", headers=auth, json={"calorie_goal": 1900})
    r = env.client.get("/v1/sync", headers=auth).json()
    assert r["calorie_goal"] == 1900 and r["entries"] == [] and r["weights"] == []


def test_sync_pages_through_large_changesets(env, auth, monkeypatch):
    monkeypatch.setattr(diary, "SYNC_PAGE", 2)
    for i in range(5):
        env.clock.advance(seconds=1)
        new_entry(env, auth, add(f"еда {i}"))

    seen, since, rounds = {}, None, 0
    while True:
        rounds += 1
        r = env.client.get("/v1/sync", headers=auth, params={"since": since} if since else None).json()
        seen.update({e["id"]: e for e in r["entries"]})
        if not r["has_more"]:
            break
        since = r["next_since"]
        assert rounds < 10, "paging must make progress"
    assert len(seen) == 5 and rounds >= 2


def test_sync_rejects_a_since_without_timezone(env, auth):
    r = env.client.get("/v1/sync", headers=auth, params={"since": "2026-09-30T08:00:00"})
    assert r.status_code == 422


def test_sync_requires_login(env):
    assert env.client.get("/v1/sync").status_code == 401


# ---------- goal ----------

def test_goal_roundtrip(env, auth):
    assert env.client.put("/v1/me/goal", headers=auth, json={"calorie_goal": 1900}).json()["calorie_goal"] == 1900
    assert env.client.get("/v1/me", headers=auth).json()["calorie_goal"] == 1900


def test_goal_at_the_floor_is_fine(env, auth):
    assert env.client.put("/v1/me/goal", headers=auth, json={"calorie_goal": 1200}).status_code == 200


@pytest.mark.parametrize("goal", [1199, 800, 0, -100])
def test_goal_below_the_floor_is_refused_politely(env, auth, goal):
    r = env.client.put("/v1/me/goal", headers=auth, json={"calorie_goal": goal})
    assert r.status_code == 422
    err = r.json()["error"]
    assert err["code"] == "goal_too_low"
    assert "1 200" in err["message"] and "врач" in err["message"]
    assert env.client.get("/v1/me", headers=auth).json()["calorie_goal"] is None


def test_absurdly_high_goal_is_refused(env, auth):
    r = env.client.put("/v1/me/goal", headers=auth, json={"calorie_goal": 6001})
    assert r.status_code == 422 and r.json()["error"]["code"] == "goal_too_high"


def test_goal_must_be_an_integer(env, auth):
    assert env.client.put("/v1/me/goal", headers=auth, json={"calorie_goal": "много"}).status_code == 422


# ---------- voice ----------

def test_stt_proxies_the_upload(env, auth):
    r = env.client.post("/v1/stt", headers=auth, files={"audio": ("note.m4a", b"AUDIO", "audio/mp4")}, data={"language": "ru"})
    assert r.status_code == 200
    assert r.json() == {"text": "два яйца", "language": "ru", "duration_s": 2.0}
    assert env.stt.calls == [(b"AUDIO", "note.m4a", "audio/mp4", "ru")]


def test_stt_upstream_errors_pass_through(env, auth):
    env.stt.error = ApiError(422, "audio_unreadable", "Не удалось прочитать запись.")
    r = env.client.post("/v1/stt", headers=auth, files={"audio": ("x.wav", b"?", "audio/wav")})
    assert r.status_code == 422 and r.json()["error"]["code"] == "audio_unreadable"


def test_stt_size_limit_is_enforced_here_too(env, auth):
    big = b"x" * (env.settings.max_audio_bytes + 1)
    r = env.client.post("/v1/stt", headers=auth, files={"audio": ("big.wav", big, "audio/wav")})
    assert r.status_code == 413 and env.stt.calls == []


def test_stt_requires_login_and_a_file(env, auth):
    assert env.client.post("/v1/stt", files={"audio": ("x.wav", b"?", "audio/wav")}).status_code == 401
    assert env.client.post("/v1/stt", headers=auth).status_code == 422
    assert env.client.post("/v1/stt", headers=auth, files={"audio": ("x.wav", b"?", "audio/wav")},
                           data={"language": "RUS"}).status_code == 422


def test_healthz(env):
    assert env.client.get("/healthz").json() == {"status": "ok"}
