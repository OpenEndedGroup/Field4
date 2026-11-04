"""Run the AI4 cold-reader task on the bare OpenAI API, without Field: the control for AI4's numbers.

Same goal text (`dev_docs/agents/ai4_cold_reader/goal_template.txt`), same two tools with the same
descriptions and parameters as the `ai4_cold_reader` box, same numbered-line page text. No Field
system prompt, no AI4 tool loop: the Responses API at medium effort (AI4's setting), one user message, the model's tool calls answered here until it
replies without one. Stalls are written in the shape `tools/cold_reader_score.py` reads, with
`"source": "naked"`.

    uv run python tools/cold_reader_naked.py --model gpt-6.1-sol --out build/cold_reader/naked.jsonl \
        installation.md staging.md sound.md kotlin_overview.md
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CATEGORIES = ["undefined-term", "fence-unintroduced", "page-meta", "mechanism-first", "dense",
              "unparseable", "ceremony", "broken-markup", "false-claim"]

TOOLS = [
    {"type": "function", "function": {
        "name": "cold_read_page",
        "description": "Read one page of Field's documentation as it stood at a git revision, as numbered lines. "
                       "Read it as a newcomer would: in order, without the source and without the pages it links to.",
        "parameters": {"type": "object", "properties": {
            "page": {"type": "string", "description": "the page under public/docs, e.g. staging.md"},
            "revision": {"type": "string", "description": "a git revision, e.g. a commit hash; HEAD for the current page"},
        }, "required": ["page"]}}},
    {"type": "function", "function": {
        "name": "report_stall",
        "description": "Record one place a newcomer reading the page would stall. Quote the page exactly. Categories: "
                       + ", ".join(CATEGORIES) + ". Report only what the page itself shows; one call per stall.",
        "parameters": {"type": "object", "properties": {
            "page": {"type": "string", "description": "the page, e.g. staging.md"},
            "line": {"type": "integer", "description": "the line number cold_read_page showed"},
            "category": {"type": "string", "description": "one of: " + ", ".join(CATEGORIES)},
            "exact": {"type": "string", "description": "the words on the page where the reader stalls, quoted exactly, at most a sentence"},
            "note": {"type": "string", "description": "one sentence: why a newcomer stalls, and what would fix it"},
        }, "required": ["page", "line", "category", "exact", "note"]}}},
]


def api_key() -> str:
    for env in ("OPENAI_PLATFORM_KEY", "OPENAI_API_KEY"):
        if os.environ.get(env, "").strip():
            return os.environ[env].strip()
    return (Path.home() / ".afield" / "openai-platform-key.txt").read_text().strip()


def read_page(page: str, revision: str) -> str:
    p = subprocess.run(["git", "-C", str(REPO), "show", f"{revision}:public/docs/{page}"], capture_output=True, text=True)
    if p.returncode != 0:
        return "error: " + p.stderr.strip()
    return "\n".join(f"{i + 1}: {line}" for i, line in enumerate(p.stdout.split("\n")))


def chat(model: str, items: list, key: str) -> dict:
    tools = [{"type": "function", **t["function"]} for t in TOOLS]
    body = json.dumps({"model": model, "input": items, "tools": tools, "reasoning": {"effort": "medium"}}).encode()
    req = urllib.request.Request("https://api.openai.com/v1/responses", data=body,
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)


def run_page(page: str, model: str, goal: str, key: str, max_rounds: int) -> tuple[list[dict], dict]:
    items: list = [{"role": "user", "content": goal.replace("PAGE", page)}]
    stalls: list[dict] = []
    stats = {"page": page, "rounds": 0, "input_tokens": 0, "output_tokens": 0, "reads": 0}
    t0 = time.time()
    for _ in range(max_rounds):
        resp = chat(model, items, key)
        stats["rounds"] += 1
        stats["input_tokens"] += resp.get("usage", {}).get("input_tokens", 0)
        stats["output_tokens"] += resp.get("usage", {}).get("output_tokens", 0)
        output = resp.get("output", [])
        items += output
        calls = [o for o in output if o.get("type") == "function_call"]
        if not calls:
            stats["reply"] = " ".join(c.get("text", "") for o in output if o.get("type") == "message"
                                      for c in o.get("content", []))
            break
        for call in calls:
            args = json.loads(call.get("arguments") or "{}")
            if call["name"] == "cold_read_page":
                stats["reads"] += 1
                result = read_page(args.get("page", page), args.get("revision", "HEAD"))
            elif args.get("category") not in CATEGORIES:
                result = f"error: category must be one of {CATEGORIES}"
            else:
                stalls.append({"page": args["page"], "line": args["line"], "category": args["category"],
                               "exact": args["exact"], "note": args["note"], "source": "naked"})
                result = f"recorded {args['page']}:{args['line']} {args['category']}"
            items.append({"type": "function_call_output", "call_id": call["call_id"], "output": result})
    stats["seconds"] = round(time.time() - t0, 1)
    return stalls, stats


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("pages", nargs="+")
    ap.add_argument("--model", default="gpt-6.1-sol")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--max-rounds", type=int, default=48)
    args = ap.parse_args(argv)
    goal = (REPO / "dev_docs/agents/ai4_cold_reader/goal_template.txt").read_text()
    key = api_key()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w") as f:
        for page in args.pages:
            stalls, stats = run_page(page, args.model, goal, key, args.max_rounds)
            for s in stalls:
                f.write(json.dumps(s) + "\n")
            print(json.dumps({**stats, "stalls": len(stalls)}), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
