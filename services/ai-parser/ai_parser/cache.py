"""Response cache. Failures here must never break parsing, so every Redis error is swallowed."""

from __future__ import annotations

import hashlib
import json
import logging
from typing import Protocol

from .prompts import PROMPT_VERSION
from .schemas import ParseRequest

log = logging.getLogger(__name__)


class Cache(Protocol):
    async def get(self, key: str) -> str | None: ...
    async def set(self, key: str, value: str, ttl_s: int) -> None: ...


def make_key(req: ParseRequest, model: str) -> str:
    """Same request (text, photo, context), same model, same prompt -> same key."""
    canonical = json.dumps(
        {"v": PROMPT_VERSION, "model": model, "req": req.model_dump(exclude_none=True)},
        sort_keys=True,
        ensure_ascii=False,
    )
    return "parse:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class NullCache:
    kind = "none"

    async def get(self, key: str) -> str | None:
        return None

    async def set(self, key: str, value: str, ttl_s: int) -> None:
        return None


class MemoryCache:
    kind = "memory"

    def __init__(self) -> None:
        self.data: dict[str, str] = {}

    async def get(self, key: str) -> str | None:
        return self.data.get(key)

    async def set(self, key: str, value: str, ttl_s: int) -> None:
        self.data[key] = value


class RedisCache:
    kind = "redis"

    def __init__(self, client) -> None:  # redis.asyncio.Redis (or a test double)
        self._client = client

    @classmethod
    def from_url(cls, url: str) -> RedisCache:
        import redis.asyncio as redis

        return cls(redis.from_url(url, decode_responses=True, socket_timeout=2, socket_connect_timeout=2))

    async def get(self, key: str) -> str | None:
        try:
            return await self._client.get(key)
        except Exception:  # noqa: BLE001 - cache is best effort
            log.warning("redis get failed", exc_info=True)
            return None

    async def set(self, key: str, value: str, ttl_s: int) -> None:
        try:
            await self._client.set(key, value, ex=ttl_s)
        except Exception:  # noqa: BLE001
            log.warning("redis set failed", exc_info=True)

    async def close(self) -> None:
        try:
            await self._client.aclose()
        except Exception:  # noqa: BLE001
            pass
