"""The Android app (local mode) bundles the same prompt and tool schema as this service.

Edit the files in ai_parser/, then copy them over:
    cp ai_parser/system_prompt.txt ai_parser/tool_spec.json ../../android/data/src/main/assets/parse/
"""

import json
from pathlib import Path

import pytest

from ai_parser.prompts import SYSTEM_PROMPT, TOOL_NAME, TOOL_SPEC

SERVICE = Path(__file__).resolve().parents[1] / "ai_parser"
ANDROID = Path(__file__).resolve().parents[3] / "android" / "data" / "src" / "main" / "assets" / "parse"

pytestmark = pytest.mark.skipif(not ANDROID.exists(), reason="android/ is not part of this checkout")


def test_system_prompt_is_identical_in_the_android_app():
    assert (ANDROID / "system_prompt.txt").read_bytes() == (SERVICE / "system_prompt.txt").read_bytes()


def test_tool_schema_is_identical_in_the_android_app():
    assert (ANDROID / "tool_spec.json").read_bytes() == (SERVICE / "tool_spec.json").read_bytes()


def test_the_files_are_what_the_service_uses():
    assert SYSTEM_PROMPT == (SERVICE / "system_prompt.txt").read_text(encoding="utf-8")
    assert TOOL_SPEC == json.loads((SERVICE / "tool_spec.json").read_text(encoding="utf-8"))
    assert TOOL_SPEC["function"]["name"] == TOOL_NAME


def test_files_use_unix_line_endings():
    # CRLF would change the bytes sent to the model and break the identical-copy check on other platforms
    assert b"\r" not in (SERVICE / "system_prompt.txt").read_bytes()
    assert b"\r" not in (SERVICE / "tool_spec.json").read_bytes()
