import json

import pytest

from ai_parser.llm import LLMAuth, LLMBadOutput, LLMRejected, LLMResult, LLMTimeout, LLMUnavailable
from ai_parser.parser import UNCLEAR_TARGET_QUESTION

from .conftest import FakeLLM, item, payload, req

pytestmark = pytest.mark.anyio

ENTRIES = [
    {"id": "e1", "name": "сливочное масло", "grams": 20},
    {"id": "e2", "name": "хлеб", "grams": 60},
]


# ---------- extraction and normalisation ----------

async def test_happy_path_returns_normalised_items(make_parser):
    llm = FakeLLM(payload(
        {"action": "add", "name": "  куриная   грудка ", "query_en": "Chicken Breast Cooked", "grams": "150",
         "confidence": 0.85},
        item("гречка", 200, 0.9),
    ))
    resp = await make_parser(llm).parse(req("курица 150 и гречка 200"))
    assert [i.name for i in resp.items] == ["куриная грудка", "гречка"]
    assert resp.items[0].query_en == "chicken breast cooked"
    assert resp.items[0].grams == 150.0
    assert resp.items[0].action == "add" and resp.items[0].target_id is None
    assert resp.clarify_question is None and resp.cached is False


async def test_no_food_gives_empty_items_not_an_error(make_parser):
    llm = FakeLLM(payload())
    resp = await make_parser(llm).parse(req("привет"))
    assert resp.items == [] and resp.clarify_question is None
    assert len(llm.calls) == 1


async def test_falls_back_to_json_in_plain_content(make_parser):
    text = 'Sure!\n```json\n{"items":[{"action":"add","name":"яблоко","grams":150,"confidence":0.9}]}\n```'
    llm = FakeLLM(LLMResult(tool_arguments=None, content=text))
    resp = await make_parser(llm).parse(req("яблоко"))
    assert resp.items[0].name == "яблоко"


async def test_bare_list_payload_is_accepted(make_parser):
    llm = FakeLLM(LLMResult(tool_arguments=json.dumps([item()]), content=None))
    assert (await make_parser(llm).parse(req())).items[0].name == "гречка"


@pytest.mark.parametrize(
    "bad_item",
    [
        item(grams=0),
        item(grams=-10),
        item(grams=5001),
        item(grams="много"),
        item(grams=None),
        item(grams=float("nan")),
        item(name=""),
        item(name=None),
        {"action": "eat", "name": "x", "grams": 10, "confidence": 1},
        "just a string",
        42,
    ],
)
async def test_invalid_items_are_dropped(make_parser, bad_item):
    llm = FakeLLM(payload(bad_item, item("рис", 150, 0.9)))
    resp = await make_parser(llm).parse(req())
    assert [i.name for i in resp.items] == ["рис"]


async def test_output_with_only_invalid_items_is_retried_then_fails(make_parser, sleeps):
    llm = FakeLLM(payload(item(grams=0)))
    with pytest.raises(LLMBadOutput):
        await make_parser(llm).parse(req())
    assert len(llm.calls) == 3  # 1 + 2 retries
    assert sleeps == [0.5, 1.0]


async def test_malformed_json_then_valid_succeeds(make_parser):
    llm = FakeLLM(LLMResult(tool_arguments="{oops", content=None), payload(item()))
    resp = await make_parser(llm).parse(req())
    assert resp.items[0].name == "гречка"
    assert len(llm.calls) == 2


async def test_missing_confidence_is_treated_as_unsure(make_parser):
    llm = FakeLLM(payload({"action": "add", "name": "суп", "grams": 300}))
    resp = await make_parser(llm).parse(req("суп"))
    assert resp.items[0].confidence == 0.5
    assert resp.clarify_question  # 0.5 < 0.6 -> asks


async def test_confidence_is_clamped(make_parser):
    llm = FakeLLM(payload(item("a", confidence=7), item("b", confidence=-3)))
    resp = await make_parser(llm).parse(req())
    assert [i.confidence for i in resp.items] == [1.0, 0.0]


# ---------- clarifying question ----------

async def test_exactly_one_question_for_the_least_sure_item(make_parser):
    llm = FakeLLM(payload(
        item("суп", 300, 0.5, clarify_question="Какой суп?"),
        item("хлеб", 50, 0.4, clarify_question="Сколько ломтиков?"),
        item("чай", 250, 0.95),
    ))
    resp = await make_parser(llm).parse(req("суп, хлеб и чай"))
    assert resp.clarify_question == "Сколько ломтиков?"
    asked = [i.name for i in resp.items if i.clarify_question]
    assert asked == ["хлеб"]


async def test_question_is_not_asked_at_the_threshold(make_parser):
    llm = FakeLLM(payload(item("суп", 300, 0.6, clarify_question="Какой суп?")))
    resp = await make_parser(llm).parse(req("суп"))
    assert resp.clarify_question is None
    assert resp.items[0].clarify_question is None  # stray model question is stripped


async def test_question_is_asked_just_below_threshold(make_parser):
    llm = FakeLLM(payload(item("суп", 300, 0.59, clarify_question="Какой суп?")))
    resp = await make_parser(llm).parse(req("суп"))
    assert resp.clarify_question == "Какой суп?"


async def test_fallback_question_when_model_forgot_to_write_one(make_parser):
    llm = FakeLLM(payload(item("суп", 300, 0.3)))
    resp = await make_parser(llm).parse(req("суп"))
    assert "суп" in resp.clarify_question
    assert resp.items[0].clarify_question == resp.clarify_question


# ---------- corrections ----------

async def test_update_targets_an_existing_entry(make_parser):
    llm = FakeLLM(payload({"action": "update", "target_id": "e1", "name": "сливочное масло", "grams": 10,
                           "confidence": 0.65}))
    resp = await make_parser(llm).parse(req("масла было меньше", entries=ENTRIES))
    [i] = resp.items
    assert (i.action, i.target_id, i.grams) == ("update", "e1", 10.0)
    assert resp.clarify_question is None


async def test_update_name_defaults_to_the_entrys_name(make_parser):
    llm = FakeLLM(payload({"action": "update", "target_id": "e2", "grams": 30, "confidence": 0.9}))
    resp = await make_parser(llm).parse(req("это была половина", entries=ENTRIES))
    assert resp.items[0].name == "хлеб"


async def test_remove_has_zero_grams(make_parser):
    llm = FakeLLM(payload({"action": "remove", "target_id": "e2", "name": "хлеб", "grams": 999, "confidence": 0.9}))
    resp = await make_parser(llm).parse(req("убери хлеб", entries=ENTRIES))
    assert (resp.items[0].action, resp.items[0].grams) == ("remove", 0.0)


async def test_update_of_unknown_entry_is_dropped_and_user_is_asked(make_parser):
    llm = FakeLLM(payload({"action": "update", "target_id": "ghost", "name": "х", "grams": 10, "confidence": 0.9}))
    resp = await make_parser(llm).parse(req("поменьше", entries=ENTRIES))
    assert resp.items == []
    assert resp.clarify_question == UNCLEAR_TARGET_QUESTION
    assert len(llm.calls) == 1  # a real ambiguity, not a malformed answer: no retry


async def test_update_without_any_context_is_dropped(make_parser):
    llm = FakeLLM(payload({"action": "update", "target_id": "e1", "name": "масло", "grams": 10, "confidence": 0.9},
                          item("рис", 100, 0.9)))
    resp = await make_parser(llm).parse(req("рис и меньше масла"))
    assert [i.name for i in resp.items] == ["рис"]
    assert resp.clarify_question == UNCLEAR_TARGET_QUESTION


async def test_add_ignores_a_stray_target_id(make_parser):
    llm = FakeLLM(payload(item(target_id="e1")))
    resp = await make_parser(llm).parse(req(entries=ENTRIES))
    assert resp.items[0].target_id is None


async def test_context_reaches_the_model(make_parser):
    llm = FakeLLM(payload(item()))
    r = req("200", entries=ENTRIES, local_time="08:30",
            pending_question={"question": "Сколько грамм?", "target_id": "e1"},
            frequent=[{"label": "завтрак", "items": [{"name": "овсянка", "grams": 200}]}])
    await make_parser(llm).parse(r)
    user_text = llm.calls[0]["messages"][1]["content"][0]["text"]
    for needle in ("e1", "сливочное масло", "08:30", "Сколько грамм?", "овсянка", "message: 200"):
        assert needle in user_text


# ---------- retries ----------

async def test_timeout_twice_then_success(make_parser, sleeps):
    llm = FakeLLM(LLMTimeout(), LLMUnavailable(), payload(item()))
    resp = await make_parser(llm).parse(req())
    assert resp.items
    assert len(llm.calls) == 3
    assert sleeps == [0.5, 1.0]


async def test_gives_up_after_two_retries_with_the_last_error(make_parser, sleeps):
    llm = FakeLLM(LLMTimeout())
    with pytest.raises(LLMTimeout):
        await make_parser(llm).parse(req())
    assert len(llm.calls) == 3
    assert sleeps == [0.5, 1.0]  # no pointless sleep after the last attempt


@pytest.mark.parametrize("error", [LLMAuth("HTTP 401"), LLMRejected("HTTP 400")])
async def test_non_retryable_errors_fail_immediately(make_parser, sleeps, error):
    llm = FakeLLM(error)
    with pytest.raises(type(error)):
        await make_parser(llm).parse(req())
    assert len(llm.calls) == 1 and sleeps == []


# ---------- cache ----------

async def test_identical_request_is_served_from_cache(make_parser):
    llm = FakeLLM(payload(item()))
    parser = make_parser(llm)
    first = await parser.parse(req())
    second = await parser.parse(req())
    assert len(llm.calls) == 1
    assert first.cached is False and second.cached is True
    assert second.items == first.items


@pytest.mark.parametrize(
    "other",
    [
        req("гречка 300 г"),
        req("гречка 200 г", entries=ENTRIES),
        req("гречка 200 г", local_time="09:00"),
    ],
)
async def test_any_difference_in_request_misses_the_cache(make_parser, other):
    llm = FakeLLM(payload(item()))
    parser = make_parser(llm)
    await parser.parse(req())
    await parser.parse(other)
    assert len(llm.calls) == 2


async def test_failures_are_not_cached(make_parser):
    llm = FakeLLM(LLMAuth(), payload(item()))
    parser = make_parser(llm)
    with pytest.raises(LLMAuth):
        await parser.parse(req())
    assert (await parser.parse(req())).items
    assert len(llm.calls) == 2


async def test_corrupt_cache_entry_is_ignored(make_parser):
    from ai_parser.cache import MemoryCache, make_key

    cache = MemoryCache()
    cache.data[make_key(req(), "deepseek-flash")] = "not json"
    llm = FakeLLM(payload(item()))
    resp = await make_parser(llm, cache).parse(req())
    assert resp.items and len(llm.calls) == 1
