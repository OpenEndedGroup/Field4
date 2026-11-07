#!/usr/bin/env python3
"""Turn real AI4 turn logs into the labelled cases `WorkspaceSymbolSearchEval` measures the symbol search on.

Each `WorkspaceSymbolSearch` call in `~/.afield/logs/ai4_tool_io/*.jsonl` becomes one case: the query and
limit, the model, and what the agent did next in the same turn, within `--window` tool calls:

- `written`: identifiers it wrote in box code (`SetBoxPropertyRequest.value`, `PatchBoxTextRequest.replace` /
  `replacement`, `NewBoxRequest.code`, `EvaluateKotlinExpressionRequest.expression`,
  `CompileKotlinRequest.source`) that it had not written or read in box code earlier in the conversation.
  A name written after a `.` is recorded with a leading dot (`.rotated` for `v.rotated`), a bare one as is
  (`quat`), because Kotlin resolves the two differently and the eval must not credit a top-level `rotated`
  for `v.rotated`.
  Names the written code declares itself (`val x`, `fun f`, lambda and loop parameters) are left out.
- `typesNamed`: capitalised names in that code, the types a `.member` may belong to.
- `opened`: Kotlin files it then read (`AttachmentRequest`, `RepoReadFileRequest`), repo-relative, with
  the `find` string if one was given.
- `loggedTop`: the symbols the search actually returned at the time, for reading presentation bias.

The labels are only raw evidence. Resolving them against definitions (which `quat` is meant, whether it is
callable from box code, whether it relates to the query) happens in the Kotlin eval, against a fresh index
of the checkout's own sources.

    uv run --project . python tools/ai4_symbol_search_cases.py            # rewrite the committed fixture
    uv run --project . python tools/ai4_symbol_search_cases.py --out -    # print instead

Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from tools.ai_tool_usage import DEFAULT_ROOT, Session, is_test_session, read_session  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
DEFAULT_OUT = REPO / "src/jvmTest/resources/ai4/workspace_symbol_search_cases.jsonl"
DEFAULT_WINDOW = 12

# Tool -> argument names holding Kotlin box code it writes.
WRITE_ARGS = {
    "SetBoxPropertyRequest": ("value",),
    "PatchBoxTextRequest": ("replace", "replacement"),
    "NewBoxRequest": ("code",),
    "EvaluateKotlinExpressionRequest": ("expression",),
    "CompileKotlinRequest": ("source",),
}
# Box-code reads: identifiers in what they returned were already known to the agent.
BOX_READ_TOOLS = {"GetBoxPropertyRequest"}
FILE_READ_ARGS = {"AttachmentRequest": "file", "RepoReadFileRequest": "path"}

KOTLIN_KEYWORDS = set("""
as break class continue do else false for fun if in interface is null object package return super this throw
true try typealias typeof val var when while by catch constructor delegate dynamic field file finally get import
init param property receiver set setparam where actual abstract annotation companion const crossinline data enum
expect external final infix inline inner internal lateinit noinline open operator out override private protected
public reified sealed suspend tailrec vararg it
""".split())

IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
DECLARED = re.compile(
    r"\b(?:val|var|fun|class|object|interface|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)")
LAMBDA_PARAMS = re.compile(r"\{\s*\(?\s*([A-Za-z_]\w*(?:\s*(?::\s*[\w.<>?]+)?\s*,\s*[A-Za-z_]\w*)*)\s*(?::\s*[\w.<>?]+)?\s*\)?\s*->")
FOR_VARS = re.compile(r"\bfor\s*\(\s*\(?([\w\s,]+?)\)?\s+in\b")
# `afield.graphics.FLine` -> `FLine`: package segments are not names box code calls.
QUALIFIED_PACKAGE = re.compile(r"\b(?:afield|java|javax|kotlin|kotlinx|org|com|io|house)(?:\.[a-z_]\w*)*\.(?=[A-Za-z_*])")
DESTRUCTURED = re.compile(r"\b(?:val|var)\s*\(([\w\s,]+)\)")


def is_fixture_session(session: Session) -> bool:
    """JVM-test turns (`conv_test`, or `conv` on the `fixture` key) and scripted models, not real use."""
    start = session.start
    if is_test_session(session):
        return True
    if start.get("conversationId") in ("conv", "conv_test") and "fixture" in str(start.get("fileKey", "")):
        return True
    return start.get("model") == "claude-test" or start.get("provider") == "openrouter"


def strip_code(code: str) -> str:
    """Kotlin with comments and string contents blanked; `${...}` templates are kept as code."""
    out: list[str] = []
    i, n = 0, len(code)
    while i < n:
        if code.startswith("//", i):
            j = code.find("\n", i)
            i = n if j < 0 else j
            continue
        if code.startswith("/*", i):
            j = code.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")
            continue
        quote = '"""' if code.startswith('"""', i) else ('"' if code[i] == '"' else ("'" if code[i] == "'" else ""))
        if quote:
            i += len(quote)
            out.append(" ")
            while i < n and not code.startswith(quote, i):
                if code[i] == "\\" and quote != '"""':
                    i += 2
                    continue
                if code.startswith("${", i):
                    depth, j = 1, i + 2
                    while j < n and depth:
                        depth += {"{": 1, "}": -1}.get(code[j], 0)
                        j += 1
                    out.append(" " + code[i + 2:j - 1] + " ")
                    i = j
                    continue
                if quote == '"' and code[i] == "\n":
                    break
                i += 1
            i += len(quote)
            continue
        out.append(code[i])
        i += 1
    return "".join(out)


def declared_names(code: str) -> set[str]:
    names = set(DECLARED.findall(code))
    for pattern in (LAMBDA_PARAMS, FOR_VARS, DESTRUCTURED):
        for group in pattern.findall(code):
            names.update(IDENT.findall(re.sub(r":\s*[\w.<>?]+", "", group)))
    # Function parameters: `name: Type` inside a `fun` header's parentheses.
    for header in re.findall(r"\bfun\b[^(]*\(([^)]*)\)", code):
        names.update(re.findall(r"([A-Za-z_]\w*)\s*:", header))
    return names


def identifiers(code: str) -> list[tuple[str, bool]]:
    """(name, dotted) for each reference in [code], in order, without keywords or the code's own declarations."""
    stripped = QUALIFIED_PACKAGE.sub("", strip_code(code))
    local = declared_names(stripped)
    out: list[tuple[str, bool]] = []
    for match in IDENT.finditer(stripped):
        name = match.group(0)
        if name in KOTLIN_KEYWORDS or name in local or len(name) < 2:
            continue
        before = stripped[:match.start()].rstrip()
        if before.endswith("import") or re.search(r"\bimport\s+[\w.]*$", before) or re.search(r"\bpackage\s+[\w.]*$", before):
            continue
        after = stripped[match.end():].lstrip(" \t")
        # `name = ...` (not `==`) or `name: T` is a named argument, parameter or assignment to a local.
        if not before.endswith(".") and (re.match(r"=(?!=)", after) or re.match(r":(?!:)", after)):
            continue
        dotted = before.endswith(".") and not before.endswith("..")
        out.append((name, dotted))
    return out


def known_names(code: str) -> set[str]:
    """Names [code] uses or declares: once the agent has seen them, they no longer label a search."""
    return {name for name, _ in identifiers(code)} | declared_names(QUALIFIED_PACKAGE.sub("", strip_code(code)))


def written_code(tool: str, args: dict) -> list[str]:
    if tool not in WRITE_ARGS:
        return []
    if tool in ("SetBoxPropertyRequest", "PatchBoxTextRequest") and args.get("property", "code") not in ("", "code"):
        return []
    return [str(args[key]) for key in WRITE_ARGS[tool] if isinstance(args.get(key), str) and args[key].strip()]


def repo_relative(path: str) -> str:
    p = path.replace("\\", "/")
    for marker in ("/src/", "/dev_docs/", "/public/", "/tools/"):
        k = p.find(marker)
        if k >= 0:
            return p[k + 1:]
    return p.lstrip("./")


def parse_args(raw: str) -> dict:
    try:
        parsed = json.loads(raw) if isinstance(raw, str) else raw
    except ValueError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def json_strings(raw: object) -> list[str]:
    """Every string value in a tool result (the box code a read returned is one of them)."""
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except ValueError:
            return [raw]
        if isinstance(raw, str):
            return [raw]
    if isinstance(raw, dict):
        return [text for value in raw.values() for text in json_strings_inner(value)]
    return json_strings_inner(raw)


def json_strings_inner(value: object) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [text for inner in value.values() for text in json_strings_inner(inner)]
    if isinstance(value, list):
        return [text for inner in value for text in json_strings_inner(inner)]
    return []


def logged_top(result_raw: str | None, limit: int = 10) -> list[str]:
    if not isinstance(result_raw, str):
        return []
    try:
        parsed = json.loads(result_raw)
        return [m.get("symbol", "") for m in parsed.get("matches", [])][:limit]
    except (ValueError, AttributeError):
        # Clipped results: recover the symbol names in order.
        return re.findall(r'"symbol"\s*:\s*"([^"]*)"', result_raw)[:limit]


def cases_from_sessions(sessions: list[Session], window: int = DEFAULT_WINDOW) -> list[dict]:
    """One case per symbol search, in time order.

    The window runs on into the conversation's next turns when a turn ends first: a turn that only looked
    things up ("isn't there something like this on Vector2f already?") is answered by the code the next turn
    writes ("ok, let's use quat then"). Names written or read in box code earlier in the conversation are
    known, and never label a later search.
    """
    by_conversation: dict[str, list[Session]] = {}
    for session in sorted(sessions, key=lambda s: (str(s.start.get("timestamp", "")), s.path)):
        start = session.start
        conversation = str(start.get("conversationId") or start.get("fileKey") or session.path)
        by_conversation.setdefault(conversation, []).append(session)
    cases: list[dict] = []
    for conversation, turns in by_conversation.items():
        calls: list[tuple[Session, dict]] = [(turn, call) for turn in turns for call in turn.calls]
        results = {r.get("callId"): r.get("result") for turn in turns for r in turn.results}
        known: set[str] = set()
        for index, (turn, call) in enumerate(calls):
            tool = call.get("toolName", "")
            args = parse_args(call.get("arguments", ""))
            if tool == "WorkspaceSymbolSearch" and str(args.get("query", "")).strip():
                cases.append(case_for(turn, call, args, conversation, calls[index + 1:index + 1 + window], known,
                                      results.get(call.get("callId"))))
            # What this call wrote or read becomes known for later searches in the conversation.
            for code in written_code(tool, args):
                known.update(known_names(code))
            if tool in BOX_READ_TOOLS:
                for text in json_strings(results.get(call.get("callId"))):
                    known.update(known_names(text))
            if tool == "PatchBoxTextRequest" and isinstance(args.get("find"), str):
                known.update(known_names(args["find"]))
    return sorted(cases, key=lambda c: (c["timestamp"], c["conversation"], c["query"]))


def case_for(turn: Session, call: dict, args: dict, conversation: str, following: list[tuple[Session, dict]],
             known: set[str], result_raw: str | None) -> dict:
    start = turn.start
    written: dict[str, bool] = {}
    types: set[str] = set()
    opened: list[dict] = []
    later_turns = 0
    for later_turn, later in following:
        later_tool = later.get("toolName", "")
        later_args = parse_args(later.get("arguments", ""))
        for code in written_code(later_tool, later_args):
            for name, dotted in identifiers(code):
                if name[0].isupper() and not dotted:
                    types.add(name)
                if name in known:
                    continue
                # A name written both ways keeps its bare form: that is the stricter resolution.
                written[name] = written.get(name, True) and dotted
        if later_tool in FILE_READ_ARGS:
            file = str(later_args.get(FILE_READ_ARGS[later_tool], ""))
            if file.endswith(".kt"):
                entry = {"file": repo_relative(file), "find": str(later_args.get("find", "") or "")}
                if entry not in opened:
                    opened.append(entry)
        if later_turn is not turn:
            later_turns += 1
    return {
        "timestamp": str(call.get("timestamp") or start.get("timestamp", "")),
        "model": str(start.get("model", "")),
        "provider": str(start.get("provider") or ""),
        "conversation": conversation,
        "round": call.get("round"),
        "query": str(args.get("query", "")).strip(),
        "limit": int(args.get("limit", 6) or 6),
        "written": [("." if dotted else "") + name for name, dotted in sorted(written.items())],
        "typesNamed": sorted(types),
        "opened": opened,
        "callsInLaterTurns": later_turns,
        "loggedTop": logged_top(result_raw),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="AI4 turn logs (default %(default)s)")
    parser.add_argument("--out", default=str(DEFAULT_OUT), help="fixture to write, or - for stdout")
    parser.add_argument("--window", type=int, default=DEFAULT_WINDOW, help="tool calls after a search that label it")
    args = parser.parse_args(argv)
    sessions = []
    for path in sorted(args.root.glob("*.jsonl")):
        session = read_session(path)
        if session is not None and not is_fixture_session(session):
            sessions.append(session)
    cases = cases_from_sessions(sessions, args.window)
    text = "".join(json.dumps(case, sort_keys=True) + "\n" for case in cases)
    if args.out == "-":
        sys.stdout.write(text)
    else:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")
        labelled = sum(1 for c in cases if c["written"] or c["opened"])
        print(f"{len(cases)} searches from {len(sessions)} turns, {labelled} with something written or opened after "
              f"them -> {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
