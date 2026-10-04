from __future__ import annotations

import os
from dataclasses import dataclass

from sqlalchemy.engine import URL

INSECURE_SECRETS = {"", "change-me", "changeme", "secret"}


class ConfigError(RuntimeError):
    pass


def database_url_from_parts(env) -> str | None:
    """Build the DSN from POSTGRES_* so passwords with @ / : etc. are escaped correctly."""
    password = env("POSTGRES_PASSWORD")
    if not password:
        return None
    return URL.create(
        "postgresql+asyncpg",
        username=env("POSTGRES_USER", "diet"),
        password=password,
        host=env("POSTGRES_HOST", "postgres"),
        port=int(env("POSTGRES_PORT", "5432")),
        database=env("POSTGRES_DB", "diet"),
    ).render_as_string(hide_password=False)


@dataclass(frozen=True)
class Settings:
    database_url: str = "postgresql+asyncpg://diet:diet@postgres:5432/diet"
    jwt_secret: str = ""
    jwt_ttl_days: int = 30

    code_ttl_min: int = 10
    code_resend_s: int = 60
    code_hourly_limit: int = 5
    code_max_attempts: int = 5

    ai_parser_url: str = "http://ai-parser:8000"
    nutrition_url: str = "http://nutrition:8000"
    stt_url: str = "http://stt:8000"
    # ai-parser may spend 3 x 30 s on retries before it gives up, so be patient with it.
    parser_timeout_s: float = 100.0
    nutrition_timeout_s: float = 10.0
    stt_timeout_s: float = 60.0

    smtp_host: str = ""
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_password: str = ""
    smtp_from: str = ""

    min_calorie_goal: int = 1200
    max_calorie_goal: int = 6000
    clarify_threshold: float = 0.6  # keep equal to the ai-parser threshold
    max_audio_bytes: int = 10 * 1024 * 1024

    def validate(self) -> None:
        if self.jwt_secret in INSECURE_SECRETS or len(self.jwt_secret) < 16:
            raise ConfigError(
                "JWT_SECRET is missing or too weak. Set a random string of 16+ characters in .env, e.g. "
                'python -c "import secrets; print(secrets.token_urlsafe(48))"'
            )

    @classmethod
    def from_env(cls) -> Settings:
        d = cls()
        env = os.environ.get
        return cls(
            database_url=env("DATABASE_URL") or database_url_from_parts(env) or d.database_url,
            jwt_secret=env("JWT_SECRET", d.jwt_secret),
            jwt_ttl_days=int(env("JWT_TTL_DAYS", d.jwt_ttl_days)),
            ai_parser_url=env("AI_PARSER_URL", d.ai_parser_url),
            nutrition_url=env("NUTRITION_URL", d.nutrition_url),
            stt_url=env("STT_URL", d.stt_url),
            smtp_host=env("SMTP_HOST", d.smtp_host),
            smtp_port=int(env("SMTP_PORT", d.smtp_port)),
            smtp_user=env("SMTP_USER", d.smtp_user),
            smtp_password=env("SMTP_PASSWORD", d.smtp_password),
            smtp_from=env("SMTP_FROM", d.smtp_from),
            min_calorie_goal=int(env("MIN_CALORIE_GOAL", d.min_calorie_goal)),
            clarify_threshold=float(env("PARSER_CLARIFY_THRESHOLD", d.clarify_threshold)),
        )
