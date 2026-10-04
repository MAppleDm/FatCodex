from __future__ import annotations

import os
from dataclasses import dataclass


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    return default if raw is None else raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-flash"
    # Thinking mode adds latency/cost and buys nothing for extraction. Turn off if the endpoint rejects the field.
    disable_thinking: bool = True
    timeout_s: float = 30.0
    retries: int = 2
    retry_backoff_s: float = 0.5
    redis_url: str = ""  # empty = no cache
    cache_ttl_s: int = 24 * 3600
    clarify_threshold: float = 0.6

    @classmethod
    def from_env(cls) -> Settings:
        d = cls()
        env = os.environ.get
        return cls(
            deepseek_api_key=env("DEEPSEEK_API_KEY", d.deepseek_api_key),
            deepseek_base_url=env("DEEPSEEK_BASE_URL", d.deepseek_base_url),
            deepseek_model=env("DEEPSEEK_MODEL", d.deepseek_model),
            disable_thinking=_bool("DEEPSEEK_DISABLE_THINKING", d.disable_thinking),
            timeout_s=float(env("DEEPSEEK_TIMEOUT_S", d.timeout_s)),
            retries=int(env("PARSER_RETRIES", d.retries)),
            retry_backoff_s=float(env("PARSER_RETRY_BACKOFF_S", d.retry_backoff_s)),
            redis_url=env("REDIS_URL", d.redis_url),
            cache_ttl_s=int(env("PARSER_CACHE_TTL_S", d.cache_ttl_s)),
            clarify_threshold=float(env("PARSER_CLARIFY_THRESHOLD", d.clarify_threshold)),
        )
