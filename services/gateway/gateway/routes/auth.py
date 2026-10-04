from __future__ import annotations

from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import LoginCode, User
from ..deps import get_session, now
from ..errors import ApiError
from ..schemas import ErrorResponse, RequestCodeIn, TokenOut, VerifyCodeIn
from ..security import codes_match, hash_code, issue_token, new_code

router = APIRouter(prefix="/v1/auth", tags=["auth"])

INVALID_CODE = ApiError(400, "invalid_code", "Код неверный или устарел. Запроси новый.")


@router.post("/request-code", status_code=204, responses={429: {"model": ErrorResponse}, 502: {"model": ErrorResponse}})
async def request_code(
    body: RequestCodeIn,
    request: Request,
    session: AsyncSession = Depends(get_session),
    at: datetime = Depends(now),
) -> Response:
    """Email a 6-digit login code. Always answers 204 for any address, so it does not reveal who has an account."""
    s = request.app.state.settings
    hour_ago = at - timedelta(hours=1)
    recent = (
        await session.scalars(
            select(LoginCode.created_at).where(LoginCode.email == body.email, LoginCode.created_at > hour_ago)
            .order_by(LoginCode.created_at.desc())
        )
    ).all()
    if recent and (at - recent[0]).total_seconds() < s.code_resend_s:
        raise ApiError(429, "rate_limited", "Код уже отправлен. Подожди минуту и запроси снова.")
    if len(recent) >= s.code_hourly_limit:
        raise ApiError(429, "rate_limited", "Слишком много запросов кода. Попробуй через час.")

    code = new_code()
    session.add(LoginCode(
        email=body.email, code_hash=hash_code(s.jwt_secret, body.email, code),
        expires_at=at + timedelta(minutes=s.code_ttl_min), created_at=at,
    ))
    await session.flush()
    await request.app.state.mailer.send_code(body.email, code)  # raises mail_failed -> nothing is committed
    await session.commit()
    return Response(status_code=204)


@router.post("/verify", response_model=TokenOut, responses={400: {"model": ErrorResponse}})
async def verify(
    body: VerifyCodeIn,
    request: Request,
    session: AsyncSession = Depends(get_session),
    at: datetime = Depends(now),
) -> TokenOut:
    """Exchange the emailed code for an access token. Creates the account on first login."""
    s = request.app.state.settings
    row = await session.scalar(
        select(LoginCode)
        .where(LoginCode.email == body.email, LoginCode.consumed.is_(False), LoginCode.expires_at > at)
        .order_by(LoginCode.created_at.desc(), LoginCode.id.desc())
        .limit(1)
    )
    if row is None or row.attempts >= s.code_max_attempts:
        raise INVALID_CODE
    if not codes_match(row.code_hash, s.jwt_secret, body.email, body.code):
        row.attempts += 1
        await session.commit()  # the failed attempt must be remembered even though we answer with an error
        raise INVALID_CODE
    row.consumed = True

    user = await session.scalar(select(User).where(User.email == body.email))
    if user is None:
        user = User(email=body.email, created_at=at)
        session.add(user)
        await session.flush()
    token, expires_in = issue_token(s.jwt_secret, user.id, at, s.jwt_ttl_days)
    await session.commit()
    return TokenOut(access_token=token, expires_in=expires_in)
