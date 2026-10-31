"""Field's own agent (AI4) as a one-shot coding agent over a checkout: `field_agent.headless_argv("field")`.

    uv run --project . --group backdoor python tools/field_repo_agent.py --root . \\
        --read '**' --write 'public/docs/fline.md' --prompt-file prompt.md

Reads the prompt from --prompt, --prompt-file, or stdin; hands it to a running (or backdoor-started) Field
as an `agent-task` whose `repo` tools (RepoReadFileRequest, RepoListFilesRequest, RepoSearchRequest,
RepoPatchFileRequest, RepoWriteFileRequest) are confined to --root and the --read / --write globs; prints
AI4's final answer on stdout and a one-line-per-call tool log on stderr. AI4 has no shell. Each --run NAME
allows one named argv template (RepoRunCommandRequest; `RepoCommandTemplates` in
src/jvmMain/kotlin/afield/ai/tools/RepoCommandTools.kt: gradle, python-test, check, git-status, git-diff,
git-log, git-show, land-precheck) with cwd --root, a wall-clock cap (--run-max-sec) and an output cap;
`--run fixer` is the lander fixer's set. Without --run, a prompt that says "run this command" has to be
answered through the files (the docs-notes outcomes file, say).

Environment: FIELD_AGENT_PORT (the Field to use; default: the backdoor's first free port from 9477),
FIELD_AGENT_MODEL, FIELD_AGENT_MAX_ROUNDS (default 40), FIELD_AGENT_READ / FIELD_AGENT_WRITE (extra globs,
separated by ':'). Exit 0 when the turn finished, 1 when it errored or ran out of rounds.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent


def _globs(values: list[str], env: str) -> list[str]:
    extra = [g for g in os.environ.get(env, "").split(":") if g.strip()]
    return [*values, *extra]


FIXER_COMMANDS = ("gradle", "python-test", "check", "git-status", "git-diff", "git-log", "git-show")


def expand_runs(runs: list[str]) -> list[str]:
    """--run values with `fixer` expanded to FIXER_COMMANDS (= RepoCommandTemplates.FIXER_SET), de-duplicated in order."""
    out: list[str] = []
    for r in runs:
        for name in (FIXER_COMMANDS if r == "fixer" else (r,)):
            if name not in out:
                out.append(name)
    return out


def build_backdoor_argv(*, root: Path, read: list[str], write: list[str], goal_file: Path, model: str,
                        max_rounds: int, port: str, timeout: float, runs: list[str] = (), run_max_sec: int = 900) -> list[str]:
    """The `field_backdoor.py` line one job runs (separate so the tests can check it without a Field)."""
    task = ["agent-task", "--goal-file", str(goal_file), "--repo-root", str(root), "--max-rounds", str(max_rounds),
            "--timeout", str(int(timeout))]
    for g in read:
        task += ["--repo-read", g]
    for g in write:
        task += ["--repo-write", g]
    for name in expand_runs(list(runs)):
        task += ["--repo-command", name]
    if runs:
        task += ["--repo-command-max-sec", str(run_max_sec)]
    if model:
        task += ["--model", model]
    argv = [sys.executable, str(HERE / "field_backdoor.py"), "--json", "--timeout", str(int(timeout) + 60)]
    if port:
        argv += ["--port", port]
    return argv + ["--command", shlex.join(task)]


def _result(stdout: str) -> dict:
    """The agent-task reply in the backdoor's --json output (one object per command, or a list)."""
    for line in reversed(stdout.strip().splitlines() or [""]):
        try:
            doc = json.loads(line)
        except json.JSONDecodeError:
            continue
        items = doc if isinstance(doc, list) else doc.get("results", [doc]) if isinstance(doc, dict) else []
        for item in reversed(items):
            if isinstance(item, dict) and isinstance(item.get("data"), dict) and "finalText" in item["data"]:
                return item["data"]
    try:
        doc = json.loads(stdout)
    except json.JSONDecodeError:
        return {}
    if isinstance(doc, dict) and isinstance(doc.get("data"), dict) and "finalText" in doc["data"]:
        return doc["data"]
    for item in (doc if isinstance(doc, list) else doc.get("results", []) if isinstance(doc, dict) else []):
        if isinstance(item, dict) and isinstance(item.get("data"), dict) and "finalText" in item["data"]:
            return item["data"]
    return {}


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--root", default=".", help="the checkout the repo tools are confined to (default: cwd)")
    p.add_argument("--read", action="append", default=[], help="readable glob, repeatable (default **)")
    p.add_argument("--write", action="append", default=[], help="writable glob, repeatable (default: nothing)")
    p.add_argument("--run", action="append", default=[], help="a named command AI4 may run (repeatable; `fixer` = the fixer's set)")
    p.add_argument("--run-max-sec", type=int, default=int(os.environ.get("FIELD_AGENT_RUN_MAX_SEC", "900")))
    p.add_argument("--prompt", default="")
    p.add_argument("--prompt-file", default="")
    p.add_argument("--model", default=os.environ.get("FIELD_AGENT_MODEL", ""))
    p.add_argument("--max-rounds", type=int, default=int(os.environ.get("FIELD_AGENT_MAX_ROUNDS", "40")))
    p.add_argument("--port", default=os.environ.get("FIELD_AGENT_PORT", ""))
    p.add_argument("--timeout", type=float, default=1800.0)
    p.add_argument("--result-file", default="", help="also write the whole AgentTaskResult JSON here")
    args = p.parse_args(argv)

    prompt = args.prompt or (Path(args.prompt_file).read_text(encoding="utf-8") if args.prompt_file else sys.stdin.read())
    if not prompt.strip():
        print("field_repo_agent: empty prompt", file=sys.stderr)
        return 2
    root = Path(args.root).expanduser().resolve()
    read = _globs(args.read, "FIELD_AGENT_READ") or ["**"]
    write = _globs(args.write, "FIELD_AGENT_WRITE")
    tmp = REPO / ".field-backdoor" / "field-agent"  # this checkout's, never the job's root
    tmp.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".md", dir=tmp, delete=False, encoding="utf-8") as f:
        f.write(prompt)
        goal_file = Path(f.name)
    cmd = build_backdoor_argv(root=root, read=read, write=write, goal_file=goal_file, model=args.model,
                              max_rounds=args.max_rounds, port=args.port, timeout=args.timeout,
                              runs=args.run, run_max_sec=args.run_max_sec)
    started = time.monotonic()
    try:
        done = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True, timeout=args.timeout + 300)
    finally:
        goal_file.unlink(missing_ok=True)
    data = _result(done.stdout)
    if args.result_file:
        Path(args.result_file).write_text(json.dumps(data, indent=2), encoding="utf-8")
    if not data:
        print(f"field_repo_agent: no agent-task result (backdoor exit {done.returncode})", file=sys.stderr)
        print(done.stdout[-4000:], file=sys.stderr)
        print(done.stderr[-4000:], file=sys.stderr)
        return 1
    for call in data.get("toolCalls", []):
        mark = "ok " if call.get("ok") else "ERR"
        print(f"[field] {mark} {call.get('tool')} {str(call.get('arguments', ''))[:160]}", file=sys.stderr)
    print(f"[field] model={data.get('provider')}/{data.get('model')} rounds={data.get('rounds')}/{data.get('maxToolRounds')} "
          f"wall={time.monotonic() - started:.0f}s tokens in={data.get('inputTokens')} out={data.get('outputTokens')} "
          f"cacheRead={data.get('cacheReadTokens')} wrote={data.get('repoWritten')} ran={data.get('repoRuns')} verdict={data.get('verdict')} "
          f"error={data.get('error') or '-'}", file=sys.stderr)
    print(data.get("finalText", ""))
    return 1 if data.get("error") or data.get("hitToolRoundCap") or data.get("verdict") == "error" else 0


if __name__ == "__main__":
    sys.exit(main())
