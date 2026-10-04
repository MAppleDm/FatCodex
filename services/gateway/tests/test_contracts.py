"""Contracts: the hand-written upstream DTOs must match the other services' committed OpenAPI files,
and the gateway's own OpenAPI must match /contracts/gateway.openapi.json (what the Android client is written against).

Regenerate the gateway file after an intentional change:  UPDATE_CONTRACTS=1 pytest tests/test_contracts.py
"""

import json
import os
import typing
from pathlib import Path

import pytest

from gateway import upstream as u
from gateway.config import Settings
from gateway.main import create_app

CONTRACTS = Path(__file__).resolve().parents[3] / "contracts"

# our DTO -> (contract file, schema name in that contract)
DTO_TO_SCHEMA = {
    u.UContextEntry: ("ai-parser", "ContextEntry"),
    u.UFrequentItem: ("ai-parser", "FrequentItem"),
    u.UFrequentMeal: ("ai-parser", "FrequentMeal"),
    u.UPendingQuestion: ("ai-parser", "PendingQuestion"),
    u.UParseContext: ("ai-parser", "ParseContext"),
    u.UParseRequest: ("ai-parser", "ParseRequest"),
    u.UParsedItem: ("ai-parser", "ParsedItem"),
    u.UParseResponse: ("ai-parser", "ParseResponse"),
    u.ULookupItem: ("nutrition", "LookupItem"),
    u.ULookupRequest: ("nutrition", "LookupRequest"),
    u.UNutrients: ("nutrition", "Nutrients"),
    u.UFood: ("nutrition", "FoodOut"),
    u.UItemResult: ("nutrition", "ItemResult"),
    u.ULookupResponse: ("nutrition", "LookupResponse"),
    u.UTranscribeResponse: ("stt", "TranscribeResponse"),
}
REQUEST_DTOS = {u.UContextEntry, u.UFrequentItem, u.UFrequentMeal, u.UPendingQuestion, u.UParseContext,
                u.UParseRequest, u.ULookupItem, u.ULookupRequest}


def schema(service, name):
    doc = json.loads((CONTRACTS / f"{service}.openapi.json").read_text(encoding="utf-8"))
    return doc["components"]["schemas"][name]


@pytest.mark.parametrize("dto", list(DTO_TO_SCHEMA), ids=lambda d: d.__name__)
def test_dto_fields_exist_in_the_contract(dto):
    service, name = DTO_TO_SCHEMA[dto]
    contract = schema(service, name)
    unknown = set(dto.model_fields) - set(contract["properties"])
    assert not unknown, f"{dto.__name__} has fields the {service} contract does not: {unknown}"


@pytest.mark.parametrize("dto", sorted(REQUEST_DTOS, key=lambda d: d.__name__), ids=lambda d: d.__name__)
def test_requests_supply_every_field_the_contract_requires(dto):
    service, name = DTO_TO_SCHEMA[dto]
    missing = set(schema(service, name).get("required", [])) - set(dto.model_fields)
    assert not missing, f"{dto.__name__} cannot send required {service} fields: {missing}"


def test_parsed_item_actions_match_the_contract():
    contract = schema("ai-parser", "ParsedItem")["properties"]["action"]["enum"]
    ours = typing.get_args(u.UParsedItem.model_fields["action"].annotation)
    assert set(ours) == set(contract)


def test_upstream_error_codes_are_the_ones_the_services_document():
    parser_doc = schema("ai-parser", "ErrorDetail")["properties"]["code"]["description"]
    stt_doc = schema("stt", "ErrorDetail")["properties"]["code"]["description"]
    for code in u.PARSER_ERRORS:
        assert code in parser_doc
    for code in u.STT_ERRORS:
        assert code in stt_doc


# ---------- the gateway's own contract ----------

def gateway_schema():
    return create_app(Settings(database_url="sqlite+aiosqlite://", jwt_secret="x" * 32)).openapi()


def test_gateway_openapi_matches_committed_contract():
    rendered = json.dumps(gateway_schema(), indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    path = CONTRACTS / "gateway.openapi.json"
    if os.environ.get("UPDATE_CONTRACTS"):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(rendered, encoding="utf-8")
    assert path.exists(), "contract missing: run with UPDATE_CONTRACTS=1"
    assert path.read_text(encoding="utf-8") == rendered


def test_every_route_except_login_and_health_requires_a_bearer_token():
    open_paths = {"/healthz", "/v1/auth/request-code", "/v1/auth/verify"}
    for path, methods in gateway_schema()["paths"].items():
        for method, op in methods.items():
            if path in open_paths:
                assert "security" not in op, f"{method.upper()} {path} should be public"
            else:
                assert op.get("security"), f"{method.upper()} {path} is missing authentication"
