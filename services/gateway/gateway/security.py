"""Login codes and JWTs."""

from __future__ import annotations

import hashlib
import hmac
import secrets
import uuid
from datetime import datetime, timedelta

import jwt

ALGORITHM = "HS256"


def new_code() -> str:
    return f"{secrets.randbelow(10**6):06d}"


def hash_code(secret: str, email: str, code: str) -> str:
    """Codes are stored as an HMAC, bound to the email, so a database leak does not reveal live codes."""
    return hmac.new(secret.encode(), f"{email}:{code}".encode(), hashlib.sha256).hexdigest()


def codes_match(expected_hash: str, secret: str, email: str, code: str) -> bool:
    return hmac.compare_digest(expected_hash, hash_code(secret, email, code))


def issue_token(secret: str, user_id: uuid.UUID, now: datetime, ttl_days: int) -> tuple[str, int]:
    expires = now + timedelta(days=ttl_days)
    token = jwt.encode(
        {"sub": str(user_id), "iat": int(now.timestamp()), "exp": int(expires.timestamp())},
        secret,
        algorithm=ALGORITHM,
    )
    return token, int((expires - now).total_seconds())


def read_token(secret: str, token: str) -> uuid.UUID | None:
    try:
        # Expiry is what matters; `iat` is informational (and would reject tokens when a test clock runs ahead).
        claims = jwt.decode(
            token, secret, algorithms=[ALGORITHM], options={"require": ["exp", "sub"], "verify_iat": False}
        )
        return uuid.UUID(claims["sub"])
    except (jwt.PyJWTError, ValueError, KeyError):
        return None
