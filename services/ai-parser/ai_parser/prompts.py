"""Prompt, tool definition and message assembly. Bump PROMPT_VERSION on any change: it keys the cache."""

from __future__ import annotations

import json
from pathlib import Path

from .schemas import ParseRequest

PROMPT_VERSION = "2"
TOOL_NAME = "record_food"

# The prompt and the tool schema live in data files so the Android app (local mode) can ship the very same
# text. tests/test_prompt_assets.py keeps the two copies identical.
_DIR = Path(__file__).parent
SYSTEM_PROMPT = (_DIR / "system_prompt.txt").read_text(encoding="utf-8")
TOOL_SPEC = json.loads((_DIR / "tool_spec.json").read_text(encoding="utf-8"))


def _context_json(req: ParseRequest) -> str:
    ctx = req.context
    data: dict = {}
    if ctx.local_time:
        data["local_time"] = ctx.local_time
    if ctx.entries:
        data["entries"] = [e.model_dump() for e in ctx.entries]
    if ctx.frequent:
        data["frequent"] = [m.model_dump(exclude_none=True) for m in ctx.frequent]
    if ctx.pending_question:
        data["pending_question"] = ctx.pending_question.model_dump()
    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))


def build_messages(req: ParseRequest) -> list[dict]:
    text = f"context: {_context_json(req)}\nmessage: {req.text or '(photo only)'}"
    content: list[dict] = [{"type": "text", "text": text}]
    if req.image_base64:
        content.append(
            {"type": "image_url", "image_url": {"url": f"data:{req.image_mime};base64,{req.image_base64}"}}
        )
    return [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": content}]
