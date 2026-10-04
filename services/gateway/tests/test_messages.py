"""POST /v1/messages: the heart of the gateway."""

import uuid

import pytest

from gateway.errors import ApiError

from .conftest import DAY, add, message, remove, update


def ids(entries):
    return [e["id"] for e in entries]


# ---------- adding ----------

def test_items_become_entries_with_numbers_from_nutrition(env, auth):
    env.ai.will_return(add("куриная грудка", "chicken breast cooked", 150), add("гречка", "buckwheat cooked", 200))
    cid = uuid.uuid4()
    r = env.post_message(auth, "курица 150 и гречка 200", client_id=cid)
    assert r.status_code == 200, r.text
    chicken, buckwheat = r.json()["entries"]

    assert chicken["name"] == "куриная грудка" and chicken["grams"] == 150
    assert (chicken["kcal"], chicken["protein"], chicken["fat"], chicken["carbs"]) == (247.5, 46.5, 5.4, 0.0)
    assert chicken["per100"] == {"kcal": 165, "protein": 31.02, "fat": 3.57, "carbs": 0}
    assert chicken["food_name"].startswith("Chicken, broilers")
    assert chicken["status"] == "ok" and chicken["deleted"] is False
    assert buckwheat["kcal"] == 184.0
    assert chicken["meal_id"] == buckwheat["meal_id"] == str(cid)
    assert chicken["id"] != buckwheat["id"]
    assert r.json()["clarify_question"] is None and r.json()["removed_ids"] == []


def test_one_lookup_call_for_the_whole_message(env, auth):
    env.ai.will_return(add(), add("масло", "butter", 10))
    env.post_message(auth, "гречка и масло")
    assert len(env.nutrition.calls) == 1 and len(env.nutrition.calls[0]) == 2


def test_nothing_recognised_gives_an_empty_answer(env, auth):
    env.ai.will_return()
    r = env.post_message(auth, "привет")
    assert r.status_code == 200
    assert r.json() == {"entries": [], "removed_ids": [], "clarify_question": None, "clarify_target_id": None}
    assert env.nutrition.calls == []


def test_photo_messages_are_marked_as_photo(env, auth):
    env.ai.will_return(add())
    r = env.client.post("/v1/messages", headers=auth, json={
        "client_id": str(uuid.uuid4()), "image_base64": "aGVsbG8=", "day": DAY,
        "eaten_at": "2026-09-30T13:00:00+03:00"})
    assert r.status_code == 200, r.text
    assert r.json()["entries"][0]["source"] == "photo"
    assert env.ai.requests[0].image_base64 == "aGVsbG8="
    assert env.ai.requests[0].text is None


def test_voice_source_is_kept(env, auth):
    env.ai.will_return(add())
    assert env.post_message(auth, source="voice").json()["entries"][0]["source"] == "voice"


# ---------- what the parser is told ----------

def test_parser_context_has_todays_entries_and_local_time(env, auth):
    env.ai.will_return(add("гречка", "buckwheat cooked", 200))
    first = env.post_message(auth).json()["entries"][0]
    env.ai.will_return(add("хлеб", "bread white", 50))
    env.post_message(auth, "хлеб 50", at="2026-09-30T19:40:00+03:00")

    ctx = env.ai.requests[1].context
    assert ctx.local_time == "19:40"
    assert [(e.id, e.name, e.grams) for e in ctx.entries] == [(first["id"], "гречка", 200)]


def test_context_excludes_other_days_deleted_entries_and_other_users(env, auth):
    other = env.login("other@example.com")
    env.ai.will_return(add("чужая еда"))
    env.post_message(other)
    env.ai.will_return(add("вчерашняя", "butter", 10))
    env.post_message(auth, day="2026-09-29", at="2026-09-29T09:00:00+03:00")
    env.ai.will_return(add("удалённая", "butter", 10))
    gone = env.post_message(auth).json()["entries"][0]
    env.client.delete(f"/v1/entries/{gone['id']}", headers=auth)

    env.ai.will_return()
    env.post_message(auth, "привет")
    assert env.ai.requests[-1].context.entries == []


def test_pending_question_is_forwarded_and_its_entry_is_in_context_even_from_another_day(env, auth):
    env.ai.will_return(add("суп", "unknown soup", 300, conf=0.4, q="Какой суп?"))
    yesterday = env.post_message(auth, "суп", day="2026-09-29", at="2026-09-29T13:00:00+03:00").json()
    soup_id = yesterday["entries"][0]["id"]
    assert yesterday["clarify_target_id"] == soup_id

    env.ai.will_return(update(soup_id, "борщ", 300, query="buckwheat cooked"))
    env.post_message(auth, "борщ", pending_question={"question": "Какой суп?", "target_id": soup_id})
    req = env.ai.requests[-1]
    assert req.context.pending_question.question == "Какой суп?"
    assert req.context.pending_question.target_id == soup_id
    assert [e.id for e in req.context.entries] == [soup_id]


def test_habitual_meals_reach_the_parser_after_three_repeats(env, auth):
    for d in (24, 25, 26):
        env.ai.will_return(add("овсянка", "buckwheat cooked", 200), add("банан", "bread white", 100))
        env.post_message(auth, "овсянка и банан", day=f"2026-09-{d}", at=f"2026-09-{d}T08:00:00+03:00")
    env.ai.will_return()
    env.post_message(auth, "как обычно")
    [meal] = env.ai.requests[-1].context.frequent
    assert meal.label == "овсянка, банан (утром)"
    assert [(i.name, i.grams) for i in meal.items] == [("овсянка", 200), ("банан", 100)]


# ---------- clarifying questions ----------

def test_unmatched_food_is_kept_without_numbers_and_asked_about(env, auth):
    env.ai.will_return(add("суши", "sushi", 300, conf=0.9))
    r = env.post_message(auth, "суши").json()
    [entry] = r["entries"]
    assert entry["status"] == "unmatched"
    assert entry["kcal"] is None and entry["per100"] is None and entry["food_name"] is None
    assert "суши" in r["clarify_question"]
    assert r["clarify_target_id"] == entry["id"]


def test_low_confidence_match_keeps_numbers_but_is_uncertain(env, auth):
    env.ai.will_return(add("гречка", "buckwheat cooked", 200, conf=0.4, q="Сколько примерно грамм?"))
    r = env.post_message(auth).json()
    [entry] = r["entries"]
    assert entry["status"] == "uncertain" and entry["kcal"] == 184.0 and entry["confidence"] == 0.4
    assert r["clarify_question"] == "Сколько примерно грамм?"
    assert r["clarify_target_id"] == entry["id"]


def test_parser_question_wins_over_not_found_question(env, auth):
    env.ai.will_return(add("суши", "sushi", 300), add("гречка", "buckwheat cooked", 200, conf=0.4, q="Сколько грамм?"))
    r = env.post_message(auth).json()
    assert r["clarify_question"] == "Сколько грамм?"
    assert r["clarify_target_id"] == r["entries"][1]["id"]


# ---------- corrections ----------

def test_update_rescales_without_another_lookup(env, auth):
    env.ai.will_return(add("сливочное масло", "butter", 20))
    butter = env.post_message(auth, "масло 20 г").json()["entries"][0]
    assert butter["kcal"] == 143.4

    env.clock.advance(seconds=30)
    env.ai.will_return(update(butter["id"], "сливочное масло", 10, conf=0.65))
    r = env.post_message(auth, "масла было меньше").json()
    [changed] = r["entries"]
    assert changed["id"] == butter["id"] and changed["grams"] == 10
    assert changed["kcal"] == 71.7 and changed["fat"] == 8.1
    assert changed["updated_at"] > butter["updated_at"]
    assert len(env.nutrition.calls) == 1  # only the first message looked anything up
    # and no duplicate was created
    sync = env.client.get("/v1/sync", headers=auth).json()
    assert len(sync["entries"]) == 1


def test_update_with_a_new_name_looks_the_food_up_again(env, auth):
    env.ai.will_return(add("суп", "sushi", 300, conf=0.4, q="Какой суп?"))
    soup = env.post_message(auth, "суп").json()["entries"][0]
    assert soup["status"] == "unmatched"

    env.ai.will_return(update(soup["id"], "гречка", 300, query="buckwheat cooked"))
    fixed = env.post_message(auth, "это гречка", pending_question={"question": "Какой суп?", "target_id": soup["id"]})
    [entry] = fixed.json()["entries"]
    assert entry["id"] == soup["id"] and entry["name"] == "гречка"
    assert entry["status"] == "ok" and entry["kcal"] == 276.0
    assert fixed.json()["clarify_question"] is None


def test_remove_deletes_the_entry(env, auth):
    env.ai.will_return(add("хлеб", "bread white", 60))
    bread = env.post_message(auth).json()["entries"][0]
    env.ai.will_return(remove(bread["id"], "хлеб"))
    r = env.post_message(auth, "убери хлеб").json()
    assert r["removed_ids"] == [bread["id"]] and r["entries"] == []
    [tomb] = env.client.get("/v1/sync", headers=auth).json()["entries"]
    assert tomb["id"] == bread["id"] and tomb["deleted"] is True


def test_parser_cannot_touch_entries_that_are_not_in_context(env, auth):
    """A hallucinated or hostile target_id (even another user's real entry) must be ignored."""
    other = env.login("victim@example.com")
    env.ai.will_return(add("чужая гречка", "buckwheat cooked", 500))
    victim_entry = env.post_message(other).json()["entries"][0]

    env.ai.will_return(update(victim_entry["id"], "моя", 1), remove(victim_entry["id"]),
                       update(uuid.uuid4(), "призрак", 5))
    r = env.post_message(auth, "изменить").json()
    assert r["entries"] == [] and r["removed_ids"] == []

    untouched = env.client.get("/v1/sync", headers=other).json()["entries"][0]
    assert untouched["grams"] == 500 and untouched["deleted"] is False


def test_malformed_target_id_is_ignored(env, auth):
    env.ai.will_return(update("not-a-uuid", "x", 5))
    assert env.post_message(auth).json()["entries"] == []


# ---------- idempotency ----------

def test_retry_with_same_client_id_returns_the_same_answer_and_changes_nothing(env, auth):
    env.ai.will_return(add(), add("масло", "butter", 10))
    cid = uuid.uuid4()
    first = env.post_message(auth, client_id=cid).json()
    again = env.post_message(auth, client_id=cid).json()
    assert again == first
    assert len(env.ai.requests) == 1
    assert len(env.client.get("/v1/sync", headers=auth).json()["entries"]) == 2


def test_retried_correction_is_not_applied_twice(env, auth):
    env.ai.will_return(add("масло", "butter", 40))
    butter = env.post_message(auth).json()["entries"][0]
    env.ai.will_return(update(butter["id"], "масло", 20))
    cid = uuid.uuid4()
    env.post_message(auth, "вдвое меньше", client_id=cid)
    env.post_message(auth, "вдвое меньше", client_id=cid)
    [e] = env.client.get("/v1/sync", headers=auth).json()["entries"]
    assert e["grams"] == 20
    assert len(env.ai.requests) == 2


def test_client_id_of_another_user_is_a_conflict(env, auth):
    other = env.login("other@example.com")
    env.ai.will_return(add())
    cid = uuid.uuid4()
    assert env.post_message(other, client_id=cid).status_code == 200
    r = env.post_message(auth, client_id=cid)
    assert r.status_code == 409 and r.json()["error"]["code"] == "id_conflict"


# ---------- failures leave no trace ----------

@pytest.mark.parametrize("status, code", [(504, "llm_timeout"), (503, "llm_unavailable"), (502, "llm_bad_output")])
def test_parser_failure_is_reported_and_saves_nothing_so_the_retry_works(env, auth, status, code):
    env.ai.will_raise(ApiError(status, code, "текст для пользователя"))
    cid = uuid.uuid4()
    r = env.post_message(auth, client_id=cid)
    assert r.status_code == status and r.json()["error"] == {"code": code, "message": "текст для пользователя"}
    assert env.client.get("/v1/sync", headers=auth).json()["entries"] == []

    env.ai.will_return(add())
    assert env.post_message(auth, client_id=cid).status_code == 200  # same id is fine to reuse


def test_nutrition_failure_saves_nothing(env, auth):
    env.nutrition.fail = ApiError(503, "nutrition_unavailable", "База продуктов сейчас недоступна.")
    env.ai.will_return(add())
    r = env.post_message(auth)
    assert r.status_code == 503 and r.json()["error"]["code"] == "nutrition_unavailable"
    assert env.client.get("/v1/sync", headers=auth).json()["entries"] == []


# ---------- validation ----------

@pytest.mark.parametrize(
    "patch",
    [
        {"text": None},
        {"text": "   "},
        {"text": "x" * 2001},
        {"eaten_at": "2026-09-30T08:15:00"},  # no UTC offset
        {"eaten_at": "yesterday"},
        {"day": "30.09.2026"},
        {"client_id": "not-a-uuid"},
        {"image_base64": "***"},
        {"source": "telepathy"},
        {"pending_question": {"question": ""}},
    ],
)
def test_invalid_messages_are_rejected(env, auth, patch):
    body = {**message("гречка"), **patch}
    assert env.client.post("/v1/messages", headers=auth, json=body).status_code == 422
    assert env.ai.requests == []


def test_messages_require_login(env):
    assert env.client.post("/v1/messages", json=message()).status_code == 401
