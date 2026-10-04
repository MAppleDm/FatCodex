"""Delivery of login codes. Without SMTP settings the code goes to the log, which is what you want
for a self-hosted dev setup: `docker compose logs gateway`."""

from __future__ import annotations

import logging
import smtplib
from email.message import EmailMessage
from typing import Protocol

from fastapi.concurrency import run_in_threadpool

from .config import Settings
from .errors import ApiError

log = logging.getLogger(__name__)


class Mailer(Protocol):
    async def send_code(self, email: str, code: str) -> None: ...


class LogMailer:
    async def send_code(self, email: str, code: str) -> None:
        log.warning("LOGIN CODE for %s: %s  (SMTP is not configured, so nothing was emailed)", email, code)


class SmtpMailer:
    def __init__(self, settings: Settings) -> None:
        self._s = settings

    async def send_code(self, email: str, code: str) -> None:
        try:
            await run_in_threadpool(self._send, email, code)
        except (OSError, smtplib.SMTPException) as e:
            log.exception("sending login code failed")
            raise ApiError(502, "mail_failed", "Не удалось отправить письмо с кодом. Попробуй позже.") from e

    def _send(self, email: str, code: str) -> None:
        s = self._s
        msg = EmailMessage()
        msg["From"] = s.smtp_from or s.smtp_user
        msg["To"] = email
        msg["Subject"] = "Код входа"
        msg.set_content(f"Твой код входа: {code}\nОн действует {s.code_ttl_min} минут.")
        with smtplib.SMTP(s.smtp_host, s.smtp_port, timeout=15) as smtp:
            smtp.starttls()
            if s.smtp_user:
                smtp.login(s.smtp_user, s.smtp_password)
            smtp.send_message(msg)


def make_mailer(settings: Settings) -> Mailer:
    return SmtpMailer(settings) if settings.smtp_host else LogMailer()
