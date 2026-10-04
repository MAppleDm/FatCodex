from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import datetime

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from .db import User
from .errors import unauthorized
from .security import read_token

bearer = HTTPBearer(auto_error=False, description="Token from POST /v1/auth/verify")


async def get_session(request: Request) -> AsyncIterator[AsyncSession]:
    async with request.app.state.sessionmaker() as session:
        yield session


def now(request: Request) -> datetime:
    return request.app.state.clock()


async def current_user(
    request: Request,
    creds: HTTPAuthorizationCredentials | None = Depends(bearer),
    session: AsyncSession = Depends(get_session),
) -> User:
    if creds is None:
        raise unauthorized()
    user_id = read_token(request.app.state.settings.jwt_secret, creds.credentials)
    user = await session.get(User, user_id) if user_id else None
    if user is None:
        raise unauthorized()
    return user
