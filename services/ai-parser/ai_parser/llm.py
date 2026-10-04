"""The one place that talks to DeepSeek (OpenAI-compatible API) and translates its failures."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

import httpx
import openai

from .config import Settings


class LLMError(Exception):
    code = "llm_error"
    retryable = False
    message = "AI service error"

    def __init__(self, detail: str | None = None) -> None:
        super().__init__(detail or self.message)
        self.detail = detail or self.message


class LLMTimeout(LLMError):
    code, retryable, message = "llm_timeout", True, "The AI service took too long to answer"


class LLMUnavailable(LLMError):
    code, retryable, message = "llm_unavailable", True, "The AI service is temporarily unavailable"


class LLMBadOutput(LLMError):
    code, retryable, message = "llm_bad_output", True, "The AI service returned an unusable answer"


class LLMRejected(LLMError):
    code, retryable, message = "llm_rejected", False, "The AI service rejected the request"


class LLMAuth(LLMError):
    code, retryable, message = "llm_auth", False, "The AI service is not configured correctly"


@dataclass(frozen=True, slots=True)
class LLMResult:
    tool_arguments: str | None  # JSON string of the forced function call
    content: str | None  # plain text, used as a fallback when no tool call came back


class LLMClient(Protocol):
    async def complete(self, messages: list[dict], tools: list[dict], tool_name: str) -> LLMResult: ...


class DeepSeekClient:
    def __init__(self, settings: Settings, http_client: httpx.AsyncClient | None = None) -> None:
        self._settings = settings
        self._client = openai.AsyncOpenAI(
            api_key=settings.deepseek_api_key or "unset",
            base_url=settings.deepseek_base_url,
            timeout=settings.timeout_s,
            max_retries=0,  # retries are ours, so they are counted and testable
            http_client=http_client,
        )

    async def complete(self, messages: list[dict], tools: list[dict], tool_name: str) -> LLMResult:
        s = self._settings
        if not s.deepseek_api_key:
            raise LLMAuth("DEEPSEEK_API_KEY is not set")
        extra = {"thinking": {"type": "disabled"}} if s.disable_thinking else None
        try:
            resp = await self._client.chat.completions.create(
                model=s.deepseek_model,
                messages=messages,
                tools=tools,
                tool_choice={"type": "function", "function": {"name": tool_name}},
                temperature=0,
                max_tokens=1024,
                extra_body=extra,
            )
        except openai.APITimeoutError as e:  # before APIConnectionError: it is a subclass
            raise LLMTimeout() from e
        except openai.APIConnectionError as e:
            raise LLMUnavailable() from e
        except openai.APIStatusError as e:
            raise _from_status(e) from e

        if not resp.choices:
            raise LLMBadOutput("no choices in response")
        message = resp.choices[0].message
        calls = message.tool_calls or []
        arguments = calls[0].function.arguments if calls and calls[0].type == "function" else None
        return LLMResult(tool_arguments=arguments, content=message.content)


def _from_status(e: openai.APIStatusError) -> LLMError:
    status = e.status_code
    if status in (401, 402, 403):  # bad key, no balance, forbidden: our configuration, not the user's fault
        return LLMAuth(f"HTTP {status}")
    if status == 429 or status >= 500:
        return LLMUnavailable(f"HTTP {status}")
    return LLMRejected(f"HTTP {status}")
