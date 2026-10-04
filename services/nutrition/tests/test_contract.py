"""The committed OpenAPI file in /contracts must match what the service actually serves.

Regenerate after an intentional API change:  UPDATE_CONTRACTS=1 pytest tests/test_contract.py
"""

import json
import os
from pathlib import Path

from nutrition.config import Settings
from nutrition.main import create_app

CONTRACT = Path(__file__).resolve().parents[3] / "contracts" / "nutrition.openapi.json"


def test_openapi_matches_committed_contract(tmp_path):
    schema = create_app(Settings(db_path=str(tmp_path / "x.db"))).openapi()
    rendered = json.dumps(schema, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    if os.environ.get("UPDATE_CONTRACTS"):
        CONTRACT.parent.mkdir(parents=True, exist_ok=True)
        CONTRACT.write_text(rendered, encoding="utf-8")
    assert CONTRACT.exists(), "contract missing: run with UPDATE_CONTRACTS=1"
    assert CONTRACT.read_text(encoding="utf-8") == rendered
