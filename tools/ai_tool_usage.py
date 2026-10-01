#!/usr/bin/env python3
"""How AI4's tools are actually used, read from the per-turn tool I/O logs.

Both bridges write one JSONL file per turn under `~/.afield/logs/ai4_tool_io/`
(`AiToolIoLogger`, `src/jvmMain/kotlin/afield/ai/core/Logging.kt`): a `session_start` line with
the provider, model and prompt, then `tool_call` / `tool_result` / `tool_exception` lines, then a
terminal `session_end` / `session_failed` / `session_cancelled`.

This is the evidence for deciding a tool's exposure (`ToolExposure` in `ToolMeta.kt`): a schema
sent on every round costs tokens on every round, so a tool most conversations never call is a
candidate for DEFERRED, and a tool that is mostly called in bursts is one a tool program should be
doing. The counts are only as good as the population, which is why test sessions are dropped by
default — jvmTests write to the same directory with fake conversations (`conv_test`, a `test` file
key, scripted fake models).

    uv run --project . --group backdoor python tools/ai_tool_usage.py
    uv run --project . --group backdoor python tools/ai_tool_usage.py --since 2026-09-01 --json

Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_ROOT = Path.home() / ".afield" / "logs" / "ai4_tool_io"
BURST = 3


@dataclass
class Session:
    path: str
    start: dict
    calls: list[dict] = field(default_factory=list)
    results: list[dict] = field(default_factory=list)
    exceptions: list[dict] = field(default_factory=list)
    status: str = ""


def read_session(path: Path) -> Session | None:
    """One log file as a Session, or None when it has no session_start (truncated or foreign)."""
    session: Session | None = None
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None
    for line in lines:
        try:
            record = json.loads(line)
        except ValueError:
            continue
        event = record.get("event")
        if event == "session_start":
            session = Session(path=str(path), start=record)
        elif session is None:
            continue
        elif event == "tool_call":
            session.calls.append(record)
        elif event == "tool_result":
            session.results.append(record)
        elif event == "tool_exception":
            session.exceptions.append(record)
        elif event in ("session_end", "session_failed", "session_cancelled"):
            session.status = record.get("status", event)
    return session


def is_test_session(session: Session) -> bool:
    """A jvmTest's fake turn rather than a person's or an eval's."""
    start = session.start
    if start.get("conversationId") == "conv_test" or start.get("fileKey") == "test":
        return True
    if str(start.get("maxToolRounds")) == "0":
        return True
    prompt = str(start.get("prompt", ""))
    return len(prompt.strip()) < 20


def result_failed(result: dict) -> bool:
    raw = result.get("result")
    if not isinstance(raw, str):
        return False
    try:
        parsed = json.loads(raw)
    except ValueError:
        return False
    return isinstance(parsed, dict) and (parsed.get("success") is False or parsed.get("error") is True)


def load(root: Path, since: str = "", include_tests: bool = False) -> list[Session]:
    sessions = []
    for path in sorted(root.glob("*.jsonl")):
        session = read_session(path)
        if session is None:
            continue
        if since and str(session.start.get("timestamp", "")) < since:
            continue
        if not include_tests and is_test_session(session):
            continue
        sessions.append(session)
    return sessions


def summarise(sessions: list[Session]) -> dict:
    """Per-tool usage over [sessions]. `nested` counts calls a tool program made (they carry a parentCallId)."""
    tools: dict[str, dict] = {}

    def entry(name: str) -> dict:
        return tools.setdefault(name, {
            "calls": 0, "nested": 0, "sessions": 0, "burstSessions": 0, "failures": 0,
            "elapsed": [], "resultChars": [],
        })

    for session in sessions:
        per_session: dict[str, int] = {}
        for call in session.calls:
            name = call.get("toolName", "?")
            e = entry(name)
            e["calls"] += 1
            if call.get("parentCallId"):
                e["nested"] += 1
            per_session[name] = per_session.get(name, 0) + 1
        for name, count in per_session.items():
            e = entry(name)
            e["sessions"] += 1
            if count >= BURST:
                e["burstSessions"] += 1
        for result in session.results:
            e = entry(result.get("toolName", "?"))
            if result_failed(result):
                e["failures"] += 1
            try:
                e["elapsed"].append(int(result.get("elapsedMs", 0)))
            except (TypeError, ValueError):
                pass
            e["resultChars"].append(len(str(result.get("result", ""))))
        for exception in session.exceptions:
            entry(exception.get("toolName", "?"))["failures"] += 1

    # Shares are over turns that used tools at all: most turns are plain conversation and call none,
    # and counting those would make every tool look rare.
    total = sum(1 for session in sessions if session.calls)
    out = {}
    for name, e in tools.items():
        out[name] = {
            "calls": e["calls"],
            "nested": e["nested"],
            "sessions": e["sessions"],
            "sessionShare": round(e["sessions"] / total, 4) if total else 0.0,
            "burstShare": round(e["burstSessions"] / e["sessions"], 4) if e["sessions"] else 0.0,
            "failureRate": round(e["failures"] / e["calls"], 4) if e["calls"] else 0.0,
            "medianElapsedMs": statistics.median(e["elapsed"]) if e["elapsed"] else None,
            "meanResultChars": round(statistics.fmean(e["resultChars"])) if e["resultChars"] else 0,
            "suggested": suggest(e["sessions"] / total if total else 0.0),
        }
    return {"sessions": len(sessions), "sessionsWithCalls": total, "providers": provider_counts(sessions), "tools": out}


def suggest(session_share: float) -> str:
    """A starting point for ToolExposure, not a verdict: core graph tools stay DIRECT regardless."""
    if session_share >= 0.05:
        return "DIRECT"
    if session_share < 0.01:
        return "DEFERRED"
    return "DIRECT?"


def provider_counts(sessions: list[Session]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for session in sessions:
        provider = str(session.start.get("provider", "?"))
        counts[provider] = counts.get(provider, 0) + 1
    return counts


def render(summary: dict, top: int) -> str:
    rows = sorted(summary["tools"].items(), key=lambda kv: (-kv[1]["sessions"], kv[0]))[:top]
    lines = [
        f"sessions: {summary['sessions']} ({summary['sessionsWithCalls']} with tool calls; shares are of those)"
        f"  providers: {summary['providers']}",
        f"{'tool':42} {'calls':>6} {'nested':>6} {'sess%':>6} {'burst%':>6} {'fail%':>6} {'med ms':>7} {'chars':>7}  suggested",
    ]
    for name, s in rows:
        med = "-" if s["medianElapsedMs"] is None else f"{s['medianElapsedMs']:.0f}"
        lines.append(
            f"{name[:42]:42} {s['calls']:>6} {s['nested']:>6} {100 * s['sessionShare']:>5.1f}% "
            f"{100 * s['burstShare']:>5.1f}% {100 * s['failureRate']:>5.1f}% {med:>7} {s['meanResultChars']:>7}  {s['suggested']}"
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="tool I/O log directory")
    parser.add_argument("--since", default="", help="ISO timestamp prefix, e.g. 2026-09-01")
    parser.add_argument("--include-tests", action="store_true", help="keep jvmTest fake sessions")
    parser.add_argument("--json", action="store_true", help="print the summary as JSON")
    parser.add_argument("--top", type=int, default=80)
    args = parser.parse_args(argv)
    if not args.root.is_dir():
        print(f"no tool I/O logs at {args.root}", file=sys.stderr)
        return 1
    summary = summarise(load(args.root, args.since, args.include_tests))
    print(json.dumps(summary, indent=2) if args.json else render(summary, args.top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
