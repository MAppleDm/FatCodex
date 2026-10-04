import uuid
from datetime import timedelta

import jwt
import pytest

from gateway import security
from gateway.config import ConfigError, Settings
from gateway.main import create_app

from .conftest import SECRET


def request_code(env, email="user@example.com"):
    return env.client.post("/v1/auth/request-code", json={"email": email})


def verify(env, code, email="user@example.com"):
    return env.client.post("/v1/auth/verify", json={"email": email, "code": code})


# ---------- happy path ----------

def test_login_flow_gives_a_working_token(env):
    assert request_code(env).status_code == 204
    [(email, code)] = env.mailer.sent
    assert email == "user@example.com" and len(code) == 6 and code.isdigit()

    r = verify(env, code)
    assert r.status_code == 200
    body = r.json()
    assert body["token_type"] == "bearer" and body["expires_in"] == 30 * 24 * 3600

    me = env.client.get("/v1/me", headers={"Authorization": f"Bearer {body['access_token']}"})
    assert me.status_code == 200 and me.json() == {"email": "user@example.com", "calorie_goal": None}


def test_email_is_normalised(env):
    request_code(env, "  Misha@Example.COM ")
    assert env.mailer.sent[0][0] == "misha@example.com"
    assert verify(env, env.mailer.last_code, "MISHA@example.com").status_code == 200


def test_second_login_is_the_same_account(env):
    def user_id(headers):
        return security.read_token(SECRET, headers["Authorization"][7:])

    first, second = user_id(env.login()), user_id(env.login())
    assert first is not None and first == second


# ---------- wrong / stale codes ----------

def test_wrong_code_is_rejected(env):
    request_code(env)
    wrong = "000000" if env.mailer.last_code != "000000" else "111111"
    r = verify(env, wrong)
    assert r.status_code == 400 and r.json()["error"]["code"] == "invalid_code"


def test_code_is_single_use(env):
    request_code(env)
    code = env.mailer.last_code
    assert verify(env, code).status_code == 200
    assert verify(env, code).status_code == 400


def test_code_expires(env):
    request_code(env)
    env.clock.advance(minutes=11)
    assert verify(env, env.mailer.last_code).status_code == 400


def test_code_is_bound_to_the_email(env):
    request_code(env, "a@example.com")
    assert verify(env, env.mailer.last_code, "b@example.com").status_code == 400


def test_brute_force_locks_the_code(env):
    request_code(env)
    good = env.mailer.last_code
    wrong = "000000" if good != "000000" else "111111"
    for _ in range(5):
        assert verify(env, wrong).status_code == 400
    assert verify(env, good).status_code == 400  # the right code no longer works after 5 misses
    env.clock.advance(seconds=61)
    request_code(env)
    assert verify(env, env.mailer.last_code).status_code == 200


def test_only_the_latest_code_works(env):
    request_code(env)
    first = env.mailer.last_code
    env.clock.advance(seconds=61)
    request_code(env)
    second = env.mailer.last_code
    if first != second:
        assert verify(env, first).status_code == 400
    assert verify(env, second).status_code == 200


# ---------- rate limits ----------

def test_resend_is_limited_to_once_a_minute(env):
    assert request_code(env).status_code == 204
    r = request_code(env)
    assert r.status_code == 429 and r.json()["error"]["code"] == "rate_limited"
    env.clock.advance(seconds=61)
    assert request_code(env).status_code == 204


def test_hourly_cap(env):
    for _ in range(5):
        assert request_code(env).status_code == 204
        env.clock.advance(seconds=61)
    assert request_code(env).status_code == 429
    env.clock.advance(hours=1)
    assert request_code(env).status_code == 204


def test_limits_are_per_address(env):
    assert request_code(env, "a@example.com").status_code == 204
    assert request_code(env, "b@example.com").status_code == 204


def test_failed_mail_does_not_burn_the_rate_limit(env):
    env.mailer.fail = True
    r = request_code(env)
    assert r.status_code == 502 and r.json()["error"]["code"] == "mail_failed"
    env.mailer.fail = False
    assert request_code(env).status_code == 204  # not 429


# ---------- tokens ----------

def test_protected_routes_need_a_token(env):
    assert env.client.get("/v1/me").status_code == 401
    r = env.client.get("/v1/me", headers={"Authorization": "Bearer garbage"})
    assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"


def test_expired_token_is_rejected(env):
    user_id = security.read_token(SECRET, env.login()["Authorization"][7:])
    token, _ = security.issue_token(SECRET, user_id, env.clock() - timedelta(days=40), 30)
    assert env.client.get("/v1/me", headers={"Authorization": f"Bearer {token}"}).status_code == 401


def test_token_signed_with_another_secret_is_rejected(env):
    env.login()
    token, _ = security.issue_token("another-secret-" + "y" * 32, uuid.uuid4(), env.clock(), 30)
    assert env.client.get("/v1/me", headers={"Authorization": f"Bearer {token}"}).status_code == 401


def test_token_for_unknown_user_is_rejected(env):
    token, _ = security.issue_token(SECRET, uuid.uuid4(), env.clock(), 30)
    assert env.client.get("/v1/me", headers={"Authorization": f"Bearer {token}"}).status_code == 401


def test_alg_none_token_is_rejected(env):
    forged = jwt.encode({"sub": str(uuid.uuid4()), "exp": 9999999999}, key=None, algorithm="none")
    assert env.client.get("/v1/me", headers={"Authorization": f"Bearer {forged}"}).status_code == 401


# ---------- validation ----------

@pytest.mark.parametrize("email", ["", "nope", "a@b", "a b@example.com", "x" * 250 + "@example.com"])
def test_bad_email_is_rejected(env, email):
    assert request_code(env, email).status_code == 422


@pytest.mark.parametrize("code", ["12345", "1234567", "abcdef", ""])
def test_bad_code_format_is_rejected(env, code):
    assert verify(env, code).status_code == 422


# ---------- unit ----------

def test_codes_are_six_digits_and_hash_is_bound_to_email():
    assert all(len(security.new_code()) == 6 for _ in range(50))
    assert security.hash_code("s", "a@x.io", "123456") != security.hash_code("s", "b@x.io", "123456")
    assert security.codes_match(security.hash_code("s", "a@x.io", "123456"), "s", "a@x.io", "123456")
    assert not security.codes_match(security.hash_code("s", "a@x.io", "123456"), "s", "a@x.io", "123457")


def test_read_token_handles_garbage():
    assert security.read_token(SECRET, "") is None
    assert security.read_token(SECRET, "a.b.c") is None


@pytest.mark.parametrize("secret", ["", "change-me", "short", "secret"])
def test_weak_secret_stops_startup(secret):
    with pytest.raises(ConfigError, match="JWT_SECRET"):
        create_app(Settings(database_url="sqlite+aiosqlite://", jwt_secret=secret))
