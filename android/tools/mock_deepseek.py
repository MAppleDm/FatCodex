"""A stand-in for the DeepSeek chat completions API, for trying the app on an emulator without a real key.

It is NOT DeepSeek: it plays a fixed, simple agent (search every food, propose the ones the base lacks, record)
so that the whole path in the app can be exercised, and it saves every request the app sends, so they can be
checked against https://api-docs.deepseek.com.

    python android/tools/mock_deepseek.py                  # listens on 127.0.0.1:8090
    ./gradlew assembleDebug -PmodelBaseUrl=http://10.0.2.2:8090   # the emulator sees the host as 10.0.2.2

In the app use any key; a key that starts with "sk-bad" gets HTTP 401 like a refused key does.
MOCK_FAIL=400 (or 500, timeout) makes every request fail that way, to see how the app reports it.
"""

import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(os.environ.get("MOCK_PORT", "8090"))
FAIL = os.environ.get("MOCK_FAIL", "")
LOG_DIR = Path(os.environ.get("MOCK_LOG_DIR", Path(__file__).with_name("mock_requests")))

# typical values per 100 g for foods the built-in base does not have
KNOWN = {
    "ремит": [("Сосиски Рубленые [Ремит]", 190, 13, 15, 1, "health-diet.ru", "https://health-diet.ru/base_of_food/sostav/232505.php"),
              ("сосиски варёные (типичные)", 266, 11, 24, 1.5, "типичные значения", None)],
    "казеин": [("казеиновый протеин", 360, 80, 1.5, 8, "типичные значения", None)],
    "батончик": [("протеиновый батончик", 350, 30, 12, 30, "типичные значения", None)],
}
PARTS = re.compile(r"\s*(?:,|;|\sи\s|\sс\s|\sсо\s)\s*")
GRAMS = re.compile(r"(\d+(?:[.,]\d+)?)\s*(?:г\b|гр\b|грам\w*|мл\b)")


def parts_of(message: str):
    out = []
    for raw in PARTS.split(message):
        raw = raw.strip(" .")
        if not raw:
            continue
        m = GRAMS.search(raw)
        grams = float(m.group(1).replace(",", ".")) if m else (250.0 if "вод" in raw else 100.0)
        name = GRAMS.sub("", raw).strip()
        name = re.sub(r"\b\d+\b", "", name).strip() or raw
        if name in ("водой", "воды", "воду"):
            name = "вода"
        out.append((name, grams))
    return out


def user_text(messages):
    for m in messages:
        if m.get("role") == "user":
            c = m.get("content")
            text = c if isinstance(c, str) else " ".join(p.get("text", "") for p in c if p.get("type") == "text")
            has_image = isinstance(c, list) and any(p.get("type") == "image_url" for p in c)
            return text.split("message:", 1)[-1].strip(), has_image
    return "", False


def tool_results(messages):
    """tool_call_id -> (name, args, result) for every tool already run."""
    calls = {}
    for m in messages:
        if m.get("role") == "assistant":
            for c in m.get("tool_calls") or []:
                calls[c["id"]] = (c["function"]["name"], json.loads(c["function"]["arguments"] or "{}"))
    out = {}
    for m in messages:
        if m.get("role") == "tool" and m.get("tool_call_id") in calls:
            name, args = calls[m["tool_call_id"]]
            out[m["tool_call_id"]] = (name, args, json.loads(m["content"]))
    return out


def call(i, name, args):
    return {"id": f"call_{int(time.time() * 1000)}_{i}", "type": "function",
            "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}}


def answer(body):
    messages = body["messages"]
    text, has_image = user_text(messages)
    done = tool_results(messages)
    forced = isinstance(body.get("tool_choice"), dict)
    if has_image:
        return [call(0, "record_food", {"items": [
            {"action": "add", "name": "омлет", "query_en": "egg, whole, cooked, omelet", "grams": 150, "confidence": 0.7}]})]
    parts = parts_of(text)
    searched = {args["query"]: res for (name, args, res) in done.values() if name == "search_foods"}
    saved = {args["name"]: res for (name, args, res) in done.values() if name == "save_food"}

    if not searched and parts and not forced:
        return [call(i, "search_foods", {"query": n}) for i, (n, _) in enumerate(parts)]

    picks, to_propose = {}, []
    proposed = {args["for_item"] for (name, args, res) in done.values() if name == "propose_food"}
    for n, _ in parts:
        hits = (searched.get(n) or {}).get("results") or []
        key = next((k for k in KNOWN if k in n), None)  # a brand: the catalog's generic food is not it
        if key:
            if n not in proposed:
                to_propose.append((n, KNOWN[key]))
        elif hits:
            picks[n] = hits[0]["id"]
    if to_propose and not forced:
        return [call(i, "propose_food", {
            "for_item": n, "question": f"Нашёл «{opts[0][0]}». Какие значения занести в базу?",
            "options": [{"name": o[0], "kcal": o[1], "protein": o[2], "fat": o[3], "carbs": o[4], "source": o[5], "url": o[6]} for o in opts],
        }) for i, (n, opts) in enumerate(to_propose)]
    # the standard mode asks about foods whose values vary by brand or recipe; plain foods go in at once
    vague = ("сосиск", "колбас", "домашн", "sausage")
    items = [{"action": "add", "name": n, "grams": g, "confidence": 0.9, "food_id": picks.get(n),
              "needs_confirmation": any(k in n for k in KNOWN) or any(v in n for v in vague)} for n, g in parts]
    return [call(0, "record_food", {"items": items})]


class Handler(BaseHTTPRequestHandler):
    counter = 0

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        print(f"<- {self.command} {self.path} {self.request_version} content-length={length} "
              f"transfer-encoding={self.headers.get('Transfer-Encoding')} expect={self.headers.get('Expect')}", flush=True)
        raw = self.rfile.read(length)
        Handler.counter += 1
        LOG_DIR.mkdir(exist_ok=True)
        (LOG_DIR / f"{Handler.counter:03d}.json").write_bytes(raw)
        auth = self.headers.get("Authorization", "")
        print(f"#{Handler.counter} {self.path} auth={'Bearer …' + auth[-4:] if auth else 'none'} {len(raw)} bytes", flush=True)

        if not self.path.endswith("/chat/completions"):
            return self.reply(404, {"error": {"message": "Not Found"}})
        if not auth.startswith("Bearer ") or auth[7:].startswith("sk-bad"):
            return self.reply(401, "Authentication Fails (governor)", plain=True)
        if FAIL == "timeout":
            time.sleep(60)
        if FAIL:
            return self.reply(int(FAIL), {"error": {"message": f"Mock failure {FAIL}", "type": "invalid_request_error"}})
        try:
            body = json.loads(raw)
        except ValueError:
            return self.reply(400, {"error": {"message": "Failed to parse the request body as JSON"}})
        problems = check(body)
        if problems:
            print("  rejected:", problems, flush=True)
            return self.reply(400, {"error": {"message": "; ".join(problems), "type": "invalid_request_error"}})
        calls = answer(body)
        print("  ->", ", ".join(c["function"]["name"] + c["function"]["arguments"][:120] for c in calls), flush=True)
        self.reply(200, {
            "id": f"mock-{Handler.counter}", "object": "chat.completion", "model": body.get("model"),
            "choices": [{"index": 0, "finish_reason": "tool_calls",
                         "message": {"role": "assistant", "content": None, "tool_calls": calls}}],
            "usage": {"prompt_tokens": len(raw) // 4, "completion_tokens": 40},
        })

    def reply(self, code, payload, plain=False):
        data = (payload if plain else json.dumps(payload, ensure_ascii=False)).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain" if plain else "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        pass


def check(body):
    """What the documented API requires (api-docs.deepseek.com, create chat completion)."""
    problems = []
    if body.get("model") not in ("deepseek-flash", "deepseek-v4-pro"):
        problems.append(f"model {body.get('model')!r} is not deepseek-flash / deepseek-v4-pro")
    tc = body.get("tool_choice")
    if tc is not None and tc not in ("none", "auto", "required") and not (isinstance(tc, dict) and tc.get("type") == "function"):
        problems.append(f"tool_choice {tc!r}")
    ids = set()
    for i, m in enumerate(body.get("messages", [])):
        role = m.get("role")
        if role not in ("system", "user", "assistant", "tool"):
            problems.append(f"messages[{i}].role {role!r}")
        if role == "assistant":
            ids |= {c["id"] for c in m.get("tool_calls") or []}
        if role == "tool" and m.get("tool_call_id") not in ids:
            problems.append(f"messages[{i}] answers an unknown tool call")
        if role == "tool" and not isinstance(m.get("content"), str):
            problems.append(f"messages[{i}].content must be a string")
    for t in body.get("tools", []):
        if t.get("type") != "function" or "name" not in t.get("function", {}):
            problems.append("a tool without type=function and a name")
    return problems


if __name__ == "__main__":
    print(f"mock DeepSeek on http://127.0.0.1:{PORT} (fail={FAIL or 'no'}), requests saved to {LOG_DIR}", flush=True)
    try:
        ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)
