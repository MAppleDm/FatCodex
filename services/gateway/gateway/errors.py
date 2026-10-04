"""One error envelope for everything the gateway decides itself: {"error": {"code", "message"}}.

`code` is for programs (the app maps it to UI behaviour, e.g. retry later vs. show and drop);
`message` is short Russian text that is safe to show to the user.
"""

from __future__ import annotations

from fastapi import Request
from fastapi.responses import JSONResponse


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.status = status
        self.code = code
        self.message = message


async def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content={"error": {"code": exc.code, "message": exc.message}})


def unauthorized() -> ApiError:
    return ApiError(401, "unauthorized", "Нужно войти заново.")
