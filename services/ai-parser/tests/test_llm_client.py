"""DeepSeekClient against a mocked HTTP transport: checks what actually goes over the wire."""

import json

import httpx
import pytest

from ai_parser.config import Settings
from ai_parser.llm import DeepSeekClient, LLMAuth, LLMBadOutput, LLMRejected, LLMTimeout, LLMUnavailable
from ai_parser.prompts import TOOL_NAME, TOOL_SPEC

pytestmark = pytest.mark.anyio

MESSAGES = [{"role": "system", "content": "s"}, {"role": "user", "content": "u"}]


def completion(tool_args=None, content=None):
    message = {"role": "assistant", "content": content}
    if tool_args is not None:
        message["tool_calls"] = [{"id": "call_1", "type": "function",
                                  "function": {"name": TOOL_NAME, "arguments": tool_args}}]
    return {"id": "x", "object": "chat.completion", "created": 0, "model": "deepseek-flash",
            "choices": [{"index": 0, "finish_reason": "stop", "message": message}]}


def make_client(handler, **settings):
    s = Settings(deepseek_api_key="sk-test", **settings)
    return DeepSeekClient(s, http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))


async def test_request_shape_and_tool_call_result():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers["authorization"]
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=completion(tool_args='{"items": []}'))

    result = await make_client(handler).complete(MESSAGES, [TOOL_SPEC], TOOL_NAME)

    assert result.tool_arguments == '{"items": []}'
    assert seen["url"] == "https://api.deepseek.com/chat/completions"
    assert seen["auth"] == "Bearer sk-test"
    body = seen["body"]
    assert body["model"] == "deepseek-flash"
    assert body["tool_choice"] == {"type": "function", "function": {"name": TOOL_NAME}}
    assert body["tools"][0]["function"]["name"] == TOOL_NAME
    assert body["temperature"] == 0
    assert body["thinking"] == {"type": "disabled"}


async def test_thinking_field_can_be_left_out():
    seen = {}

    def handler(request):
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=completion(tool_args="{}"))

    await make_client(handler, disable_thinking=False).complete(MESSAGES, [TOOL_SPEC], TOOL_NAME)
    assert "thinking" not in seen["body"]


async def test_base_url_and_model_come_from_settings():
    seen = {}

    def handler(request):
        seen["url"] = str(request.url)
        seen["model"] = json.loads(request.content)["model"]
        return httpx.Response(200, json=completion(tool_args="{}"))

    await make_client(handler, deepseek_base_url="https://llm.example/v1", deepseek_model="m-1").complete(
        MESSAGES, [TOOL_SPEC], TOOL_NAME)
    assert seen == {"url": "https://llm.example/v1/chat/completions", "model": "m-1"}


async def test_image_part_is_sent_as_data_url():
    from ai_parser.prompts import build_messages
    from ai_parser.schemas import ParseRequest

    seen = {}

    def handler(request):
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=completion(tool_args="{}"))

    messages = build_messages(ParseRequest(text="обед", image_base64="aGVsbG8=", image_mime="image/png"))
    await make_client(handler).complete(messages, [TOOL_SPEC], TOOL_NAME)
    parts = seen["body"]["messages"][1]["content"]
    assert parts[1] == {"type": "image_url", "image_url": {"url": "data:image/png;base64,aGVsbG8="}}


async def test_plain_content_is_returned_when_there_is_no_tool_call():
    client = make_client(lambda r: httpx.Response(200, json=completion(content='{"items": []}')))
    result = await client.complete(MESSAGES, [TOOL_SPEC], TOOL_NAME)
    assert result.tool_arguments is None and result.content == '{"items": []}'


async def test_empty_choices_is_bad_output():
    client = make_client(lambda r: httpx.Response(200, json={"id": "x", "object": "chat.completion", "created": 0,
                                                               "model": "m", "choices": []}))
    with pytest.raises(LLMBadOutput):
        await client.complete(MESSAGES, [TOOL_SPEC], TOOL_NAME)


@pytest.mark.parametrize(
    "status, expected",
    [(401, LLMAuth), (402, LLMAuth), (403, LLMAuth), (429, LLMUnavailable), (500, LLMUnavailable),
     (503, LLMUnavailable), (400, LLMRejected), (422, LLMRejected)],
)
async def test_http_errors_map_to_domain_errors(status, expected):
    client = make_client(lambda r: httpx.Response(status, json={"error": {"message": "nope"}}))
    with pytest.raises(expected):
        await client.complete(MESSAGES, [TOOL_SPEC], TOOL_NAME)


async def test_timeout_maps_to_llm_timeout():
    def handler(request):
        raise httpx.ReadTimeout("slow", request=request)

    with pytest.raises(LLMTimeout):
        await make_client(handler).complete(MESSAGES, [TOOL_SPEC], TOOL_NAME)


async def test_connection_failure_maps_to_unavailable():
    def handler(request):
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(LLMUnavailable):
        await make_client(handler).complete(MESSAGES, [TOOL_SPEC], TOOL_NAME)


async def test_missing_key_fails_without_a_network_call():
    def handler(request):
        raise AssertionError("must not call the network")

    client = DeepSeekClient(Settings(deepseek_api_key=""),
                            http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    with pytest.raises(LLMAuth, match="DEEPSEEK_API_KEY"):
        await client.complete(MESSAGES, [TOOL_SPEC], TOOL_NAME)
