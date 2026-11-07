#!/usr/bin/env python3
"""Review real AI4 conversations for what Field should fix, from the per-turn tool I/O logs.

`ai_tool_usage.py` counts how tools are used. This reads the same logs
(`~/.afield/logs/ai4_tool_io/`, one JSONL file per turn) one conversation at a time and reports the
four things a turn teaches, each with where its fix belongs:

- **Tool failures** that are not the box's own compile or runtime error: a refused argument, an
  exception from the tool. Each is a bug report against the tool (AGENTS.md): fix the tool so the
  call works or its answer says what to do, and add a curated lesson only for what code cannot fix.
- **Lookup cost**: a turn that made several lookups (symbol search, retrieval, attachments) before
  it changed anything. The names it ended up using belong in the instructions
  (`fieldGraphToolGuidance` in `Instructions.kt`), so the next model starts with them.
- **Context pressure**: each turn's peak input tokens against the model's window, and the turns the
  provider refused or failed.
- **Corrections**: a person's next message that says the last answer was wrong ("look again", "are
  you sure"). The claim before it is a verification miss, usually worth a curated lesson in
  `src/jvmMain/resources/ai4/agent_lessons.md`.
- **Redirections**: a person's next message that turns the work onto Field's own way of doing it
  ("don't use SVG here, just use FLines", "isn't there something like this on Vector2f already?").
  The turn before it reached for a generic idiom; what the person named is the idiom the next model
  should start with (the instructions, a lesson, or a search that finds it).

It began with the first conversation with a local model (qwen3.8-27b, 2026-10-22, the prime numbers
that became an inferno-coloured Ulam spiral), which had all four.

    uv run --project . python tools/ai4_conversation_review.py --since 2026-10-22T07:40
    uv run --project . python tools/ai4_conversation_review.py --conversation <conversationId> --json

Turns are grouped by `conversationId`. A provider thread that was thrown away after a failure starts
a new id; `--merge-file-key` groups by the box (`fileKey`) instead, which keeps such a conversation whole.

Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from tools.ai_tool_usage import DEFAULT_ROOT, result_failed  # noqa: E402

LOOKUP_TOOLS = {
    "WorkspaceSymbolSearch", "SemanticRetrievalSearchRequest", "AttachmentRequest", "WorkspaceSearchRequest",
    "ListDefinitions", "ListContentsRequest", "CodeUseSemanticAnalyzeCodeRequest", "CodeUseRelatedSourceForCodeRequest",
    "DescribeToolsRequest", "DocumentGuideSearchRequest", "WhyDoesThisResolve",
}
CHANGE_TOOLS = {
    "SetBoxPropertyRequest", "PatchBoxTextRequest", "NewBoxRequest", "EditFile", "RunBoxRequest", "LaunchBoxRequest",
    "DeleteBoxRequest", "ConnectBoxesRequest", "RunToolProgramRequest",
}
# Run/Launch failures that reached execution are the box's own errors: the task, not a tool mistake.
BOX_VERDICT_TOOLS = {"RunBoxRequest", "LaunchBoxRequest", "CompileKotlin", "CompileKotlinRequest", "EvaluateKotlinExpressionRequest"}
LOOKUP_THRESHOLD = 4
CORRECTION = re.compile(
    r"(?i)\b(look again|are you sure|not sure|that'?s (not|wrong)|you'?re wrong|isn'?t (right|correct)|doesn'?t (look|match|work)"
    r"|you (said|claimed|claiming)|claiming|not what i|still (wrong|broken)|try again|wrong)\b")
# From the knight-tour conversation (2026-11-06), where the two redirections were the only course changes and
# the correction pattern saw neither. Across every logged request it matched only redirections (5 of 3310).
REDIRECTION = re.compile(
    r"(?i)(\bdon'?t use\b|\bjust use\b|\bno need (for|to)\b|\bno universe\b|\bwe (already )?have\b"
    r"|\bisn'?t there (something|a|an)\b|\bis there(n'?t)? already\b|\binstead of\b|\buse (the )?(existing|built-?in)\b"
    r"|\bi bet (this|it|that) works with\b|,\s*not the\b)")


@dataclass
class Turn:
    path: str
    start: dict
    events: list[dict] = field(default_factory=list)

    @property
    def request(self) -> str:
        """The person's words: the logged `request`, else the prompt's last paragraph (older logs)."""
        explicit = self.start.get("request")
        if isinstance(explicit, str) and explicit.strip():
            return explicit.strip()
        prompt = str(self.start.get("prompt", "")).rstrip()
        return prompt.rsplit("\n\n", 1)[-1].strip()

    def of(self, kind: str) -> list[dict]:
        return [e for e in self.events if e.get("event") == kind]

    @property
    def status(self) -> str:
        for e in reversed(self.events):
            if e.get("event") in ("session_end", "session_failed", "session_cancelled"):
                return str(e.get("status", e["event"]))
        return "unfinished"

    @property
    def failure(self) -> str:
        failed = self.of("session_failed")
        return str(failed[-1].get("message", "")) if failed else ""

    @property
    def peak_input_tokens(self) -> int:
        return max((int(e.get("inputTokens") or 0) for e in self.of("model_usage")), default=0)


def read_turn(path: Path) -> Turn | None:
    turn: Turn | None = None
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if record.get("event") == "session_start":
            turn = Turn(str(path), record)
        elif turn is not None:
            turn.events.append(record)
    return turn


def load_conversations(root: Path, since: str, conversation: str, merge_file_key: bool) -> dict[str, list[Turn]]:
    conversations: dict[str, list[Turn]] = {}
    for path in sorted(root.glob("*.jsonl")):
        turn = read_turn(path)
        if turn is None or str(turn.start.get("timestamp", "")) < since:
            continue
        if str(turn.start.get("maxToolRounds")) == "0" or turn.start.get("conversationId") in ("", None, "conv_test"):
            continue  # box naming, completion and test fixtures are not conversations
        key = str(turn.start.get("fileKey") if merge_file_key else turn.start.get("conversationId"))
        if conversation and conversation not in (key, turn.start.get("conversationId"), turn.start.get("localThreadId")):
            continue
        conversations.setdefault(key, []).append(turn)
    return conversations


def message_of(raw: object) -> str:
    if not isinstance(raw, str):
        return ""
    try:
        parsed = json.loads(raw)
    except ValueError:
        return raw[:200]
    if isinstance(parsed, dict):
        return str(parsed.get("message") or parsed.get("error") or "")[:200]
    return ""


def is_tool_mistake(tool: str, raw: object) -> bool:
    if tool not in BOX_VERDICT_TOOLS:
        return True
    try:
        parsed = json.loads(raw) if isinstance(raw, str) else {}
    except ValueError:
        return True
    return not (parsed.get("stage") or parsed.get("diagnostics") or parsed.get("errors") or parsed.get("timedOut"))


def review_turn(turn: Turn) -> dict:
    calls = turn.of("tool_call")
    args = {c.get("callId"): c.get("arguments", "") for c in calls}
    failures = []
    for result in turn.of("tool_result"):
        tool = str(result.get("toolName"))
        if result_failed(result) and is_tool_mistake(tool, result.get("result")):
            failures.append({"tool": tool, "message": message_of(result.get("result")),
                             "arguments": str(args.get(result.get("callId"), ""))[:300]})
    # The longest run of lookups with no change between them: the FLine hunt came after a NewBox.
    lookups: list[dict] = []
    run: list[dict] = []
    for call in calls:
        tool = str(call.get("toolName"))
        if tool in CHANGE_TOOLS:
            run = []
        elif tool in LOOKUP_TOOLS:
            run.append({"tool": tool, "arguments": str(call.get("arguments", ""))[:200]})
            if len(run) > len(lookups):
                lookups = list(run)
    return {
        "path": turn.path,
        "timestamp": turn.start.get("timestamp"),
        "model": turn.start.get("model"),
        "provider": turn.start.get("provider"),
        "request": turn.request[:400],
        "status": turn.status,
        "failure": turn.failure,
        "peakInputTokens": turn.peak_input_tokens,
        "toolCalls": len(calls),
        "toolFailures": failures,
        "emptyAnswer": turn.status == "completed" and not calls and not any(int(e.get("textChars") or 0) for e in turn.of("session_end")),
        "lookupRun": lookups if len(lookups) >= LOOKUP_THRESHOLD else [],
    }


def review(turns: list[Turn], context_tokens: int) -> dict:
    reviewed = [review_turn(t) for t in turns]
    for previous, current in zip(reviewed, reviewed[1:]):
        if CORRECTION.search(current["request"]):
            previous["correctedBy"] = current["request"][:300]
        elif REDIRECTION.search(current["request"]):
            previous["redirectedBy"] = current["request"][:300]
    return {
        "conversationId": turns[0].start.get("conversationId"),
        "fileKey": turns[0].start.get("fileKey"),
        "contextTokens": context_tokens,
        "turns": reviewed,
        "summary": {
            "turns": len(reviewed),
            "failedTurns": sum(1 for r in reviewed if r["status"] == "failed"),
            "toolFailures": sum(len(r["toolFailures"]) for r in reviewed),
            "expensiveLookups": sum(1 for r in reviewed if r["lookupRun"]),
            "corrections": sum(1 for r in reviewed if "correctedBy" in r),
            "redirections": sum(1 for r in reviewed if "redirectedBy" in r),
            "peakInputTokens": max((r["peakInputTokens"] for r in reviewed), default=0),
        },
    }


def render(reviews: list[dict]) -> str:
    out: list[str] = []
    for conv in reviews:
        s = conv["summary"]
        window = conv["contextTokens"]
        out.append(f"## {conv['conversationId']}  ({conv['fileKey']})")
        out.append(f"{s['turns']} turns, {s['failedTurns']} failed, {s['toolFailures']} tool failure(s), "
                   f"{s['expensiveLookups']} expensive lookup(s), {s['corrections']} correction(s), {s['redirections']} redirection(s); "
                   f"peak input {s['peakInputTokens']} tokens" + (f" of {window}" if window else ""))
        out.append("")
        for i, t in enumerate(conv["turns"], 1):
            pressure = f" ({100 * t['peakInputTokens'] // window}% of window)" if window and t["peakInputTokens"] else ""
            out.append(f"{i}. **{t['request'][:120]!s}** — {t['status']}, {t['toolCalls']} call(s), "
                       f"peak {t['peakInputTokens']} tokens{pressure}")
            if t["emptyAnswer"]:
                out.append("   - completed with no answer and no tool calls: the reply had no room left in the window → fit the history (`StatelessHistory.inputBudget`)")
            if t["failure"]:
                out.append(f"   - turn failed: `{t['failure'][:200]}` → retry/fit in the bridge (`AiRateLimitRecovery`, `StatelessHistory`)")
            for f in t["toolFailures"]:
                out.append(f"   - tool failure `{f['tool']}`: \"{f['message']}\" with `{f['arguments'][:160]}` → fix the tool or its answer")
            if t["lookupRun"]:
                steps = ", ".join(f"{l['tool']}({l['arguments'][:60]})" for l in t["lookupRun"])
                out.append(f"   - {len(t['lookupRun'])} lookups in a row before changing anything: {steps} "
                           f"→ put what it found in the instructions")
            if "correctedBy" in t:
                out.append(f"   - corrected next turn: \"{t['correctedBy'][:200]}\" → check the claim; a curated lesson if it generalises")
            if "redirectedBy" in t:
                out.append(f"   - redirected next turn: \"{t['redirectedBy'][:200]}\" → the idiom it should have reached for: "
                           "make the instructions or a search lead to it")
        out.append("")
    return "\n".join(out)


def configured_context_tokens() -> int:
    props = Path.home() / ".afield" / "ai-models.properties"
    try:
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("local.contextTokens="):
                return int(line.split("=", 1)[1].strip())
    except (OSError, ValueError):
        pass
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="tool I/O log directory")
    parser.add_argument("--since", default="", help="ISO timestamp prefix, e.g. 2026-10-22T07:40")
    parser.add_argument("--conversation", default="", help="one conversationId, localThreadId or fileKey")
    parser.add_argument("--merge-file-key", action="store_true", help="group turns by box (fileKey), not provider thread")
    parser.add_argument("--context-tokens", type=int, default=0,
                        help="the model's window, for pressure percentages (default: local.contextTokens)")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    window = args.context_tokens or configured_context_tokens()
    conversations = load_conversations(args.root, args.since, args.conversation, args.merge_file_key)
    reviews = [review(turns, window) for turns in conversations.values()]
    if args.json:
        print(json.dumps(reviews, indent=2))
    else:
        print(render(reviews) if reviews else "no conversations matched")
    return 0


if __name__ == "__main__":
    sys.exit(main())
