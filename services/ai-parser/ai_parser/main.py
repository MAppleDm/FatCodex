from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .cache import Cache, NullCache, RedisCache
from .config import Settings
from .llm import DeepSeekClient, LLMClient, LLMError
from .parser import Parser
from .schemas import ErrorResponse, Health, ParseRequest, ParseResponse

log = logging.getLogger(__name__)

STATUS = {
    "llm_timeout": 504,
    "llm_unavailable": 503,
    "llm_bad_output": 502,
    "llm_auth": 502,
    "llm_rejected": 422,
}
ERROR_RESPONSES = {status: {"model": ErrorResponse, "description": code} for code, status in STATUS.items()}


def create_app(settings: Settings | None = None, llm: LLMClient | None = None, cache: Cache | None = None) -> FastAPI:
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        the_cache = cache or (RedisCache.from_url(settings.redis_url) if settings.redis_url else NullCache())
        the_llm = llm or DeepSeekClient(settings)
        app.state.cache = the_cache
        app.state.parser = Parser(the_llm, the_cache, settings)
        yield
        if isinstance(the_cache, RedisCache):
            await the_cache.close()

    app = FastAPI(title="DietApp ai-parser", version="0.1.0", lifespan=lifespan)

    @app.exception_handler(LLMError)
    async def llm_error_handler(_: Request, exc: LLMError) -> JSONResponse:
        log.warning("parse failed: %s (%s)", exc.code, exc.detail)
        return JSONResponse(status_code=STATUS.get(exc.code, 502), content={"error": {"code": exc.code, "message": exc.message}})

    @app.get("/healthz", response_model=Health, tags=["ops"])
    def healthz(request: Request) -> Health:
        return Health(
            status="ok",
            model=settings.deepseek_model,
            configured=bool(settings.deepseek_api_key),
            cache=getattr(request.app.state.cache, "kind", "none"),
        )

    @app.post("/v1/parse", response_model=ParseResponse, responses=ERROR_RESPONSES, tags=["parse"])
    async def parse(body: ParseRequest, request: Request) -> ParseResponse:
        """Extract foods (name, grams, confidence) from text and/or a photo.

        Returns at most one clarifying question, and only when some item's confidence is below the
        threshold. Calories are never produced here: pass the items to the nutrition service.
        For `update`/`remove`, the entry must be listed in `context.entries`.
        """
        return await request.app.state.parser.parse(body)

    return app


app = create_app()
