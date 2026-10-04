import pytest
from sqlalchemy.engine import make_url

from gateway.config import Settings, database_url_from_parts


def env_of(**values):
    return values.get


def test_dsn_is_built_from_postgres_parts_with_escaping():
    url = database_url_from_parts(env_of(POSTGRES_USER="diet", POSTGRES_PASSWORD="p@ss/w:rd#1", POSTGRES_DB="d"))
    parsed = make_url(url)
    assert parsed.drivername == "postgresql+asyncpg"
    assert (parsed.username, parsed.password, parsed.host, parsed.port, parsed.database) == (
        "diet", "p@ss/w:rd#1", "postgres", 5432, "d")


def test_no_password_means_no_generated_dsn():
    assert database_url_from_parts(env_of()) is None


def test_explicit_database_url_wins(monkeypatch):
    monkeypatch.setenv("DATABASE_URL", "postgresql+asyncpg://u:p@h/db")
    monkeypatch.setenv("POSTGRES_PASSWORD", "ignored")
    assert Settings.from_env().database_url == "postgresql+asyncpg://u:p@h/db"


def test_parts_are_used_when_there_is_no_database_url(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("POSTGRES_PASSWORD", "s3cret")
    assert make_url(Settings.from_env().database_url).password == "s3cret"


@pytest.mark.parametrize("secret", ["", "change-me", "short"])
def test_validate_rejects_weak_secrets(secret):
    from gateway.config import ConfigError

    with pytest.raises(ConfigError):
        Settings(jwt_secret=secret).validate()
