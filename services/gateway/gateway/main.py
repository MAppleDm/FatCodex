from __future__ import annotations

import logging
from collections.abc import Callable
from contextlib import asynccontextmanager
from datetime import datetime, timezone

import httpx
from fastapi import FastAPI
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

from .config import Settings
from .db import Base
from .errors import ApiError, api_error_handler
from .mailer import Mailer, make_mailer
from .processing import MessageProcessor
from .routes import auth, diary
from .schemas import Health
from .upstream import AiParser, HttpAiParser, HttpNutrition, HttpStt, Nutrition, Stt

log = logging.getLogger(__name__)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def make_engine(url: str) -> AsyncEngine:
    if url.startswith("sqlite"):  # tests: one shared in-memory database
        return create_async_engine(url, poolclass=StaticPool, connect_args={"check_same_thread": False})
    return create_async_engine(url, pool_pre_ping=True)


def create_app(
    settings: Settings | None = None,
    *,
    mailer: Mailer | None = None,
    ai: AiParser | None = None,
    nutrition: Nutrition | None = None,
    stt: Stt | None = None,
    clock: Callable[[], datetime] = utcnow,
) -> FastAPI:
    settings = settings or Settings.from_env()
    settings.validate()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        engine = make_engine(settings.database_url)
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        http_clients: list[httpx.AsyncClient] = []

        def client(timeout: float) -> httpx.AsyncClient:
            http_clients.append(httpx.AsyncClient(timeout=timeout))
            return http_clients[-1]

        app.state.engine = engine
        app.state.sessionmaker = async_sessionmaker(engine, expire_on_commit=False)
        app.state.settings = settings
        app.state.clock = clock
        app.state.mailer = mailer or make_mailer(settings)
        app.state.nutrition = nutrition or HttpNutrition(settings.nutrition_url, client(settings.nutrition_timeout_s))
        app.state.stt = stt or HttpStt(settings.stt_url, client(settings.stt_timeout_s))
        the_ai = ai or HttpAiParser(settings.ai_parser_url, client(settings.parser_timeout_s))
        app.state.processor = MessageProcessor(settings, the_ai, app.state.nutrition, clock)
        try:
            yield
        finally:
            for c in http_clients:
                await c.aclose()
            await engine.dispose()

    app = FastAPI(title="DietApp gateway", version="0.1.0", lifespan=lifespan)
    app.add_exception_handler(ApiError, api_error_handler)
    app.include_router(auth.router)
    app.include_router(diary.router)

    @app.get("/healthz", response_model=Health, tags=["ops"])
    async def healthz() -> Health:
        async with app.state.sessionmaker() as session:
            await session.execute(text("SELECT 1"))
        return Health(status="ok")

    return app


def app_factory() -> FastAPI:
    """uvicorn --factory gateway.main:app_factory : configuration errors stop startup with a clear message."""
    return create_app()
