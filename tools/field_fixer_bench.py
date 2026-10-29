"""Replay the lander's past fixups through AI4 (Field's own agent) and grade what it does.

    uv run --project . --group backdoor python tools/field_fixer_bench.py cases           # the corpus
    uv run --project . --group backdoor python tools/field_fixer_bench.py run --id 36addffcd --port 9611
    uv run --project . --group backdoor python tools/field_fixer_bench.py run --corpus --port 9611
    uv run --project . --group backdoor python tools/field_fixer_bench.py table           # Markdown results

Every `Lander fixup:` commit F on history is one case. Its parent F^ is the branch tip the lander's
fixer was handed (the fixer's edit is committed straight onto it), and the queue keeps what the fixer
was told: `field-land-queue/fixer.jsonl` names the branch, and the round's `logs/batch-*.log` (plus
`.failures.txt` when tests failed) holds main's sha, the log tail and the failing tests. A case is
replayed by checking F^ out in one scratch worktree, building the brief exactly as `land.py` does
(`FIXER_PROMPT` + `_fixer_context`'s parts, with the ledger cut at the case's own time), running it
through `field_repo_agent.py` with the fixer's command set minus `gradle` (a cold scratch tree cannot
build inside the fixer's budget), and grading:

- **check**: the named failing check, run against the scratch tree with *this* checkout's compiled
  checker (`user.dir` = the scratch tree; JUnit classes, `KDocAttachmentGuard`, `AgentTranscriptIndexGuard`,
  or the case's own `tools/` Python). It is only trusted when the controls agree: red on F^, green on F.
- **lander**: `land._fixup_problem`'s rules (no deletes, denied paths, 6 files / 60 lines, no skips).
- **similarity** of AI4's diff to the real one (difflib ratio over the changed lines), and
  **identical** when the edited files match F byte for byte.

Results go to `dev_docs/ai/ai4_fixer_runs/bench/<sha9>/` and `results.jsonl` there; `table` prints them.
Needs `./gradlew compileTestKotlinJvm` here first (the checkers' classpath), and a Field on --port.
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
sys.path.insert(0, str(HERE))
import land  # noqa: E402

OUT = REPO / "dev_docs" / "ai" / "ai4_fixer_runs" / "bench"
SCRATCH = REPO / ".nightly-tmp" / "replay"
CP_FILE = REPO / ".nightly-tmp" / "test.cp"
RUNS = ["python-test", "check", "git-status", "git-diff", "git-log", "git-show"]


def git(cwd: Path, *args: str, check: bool = True) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=check, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True).stdout


def queue_dir() -> Path:
    return Path(git(REPO, "rev-parse", "--path-format=absolute", "--git-common-dir").strip()) / "field-land-queue"


# ------------------------------------------------------------------------------------------ cases

def cases() -> list[dict]:
    """Every fixup on history joined to its ledger entry and round log, oldest first."""
    q = queue_dir()
    ledger = []
    for line in (q / "fixer.jsonl").read_text().splitlines():
        try:
            ledger.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    by_sha = {e["sha"]: (i, e) for i, e in enumerate(ledger) if e.get("outcome") == "fixed" and e.get("sha")}
    logs = sorted((q / "logs").glob("batch-*.log"))
    out = []
    for row in git(REPO, "log", "--reverse", "--format=%H%x09%s", "--grep=^Lander fixup").splitlines():
        sha, subject = row.split("\t", 1)
        if sha not in by_sha:
            continue
        index, entry = by_sha[sha]
        key = "land: fixer: fixed: " + entry["reason"][:60]
        day = entry["finished"][:10].replace("-", "")
        log = next((p for p in logs if p.name[6:14] in (day, _day_before(day)) and key in p.read_text(errors="replace")), None)
        out.append({"id": sha[:9], "sha": sha, "parent": git(REPO, "rev-parse", f"{sha}^").strip(),
                    "branch": entry.get("branch"), "files": entry.get("files", []), "reason": entry["reason"],
                    "ledger_before": index, "log": str(log) if log else None})
    return out


def _day_before(day: str) -> str:
    import datetime
    return (datetime.date(int(day[:4]), int(day[4:6]), int(day[6:])) - datetime.timedelta(days=1)).strftime("%Y%m%d")


def _fq_class(root: Path, simple: str) -> str | None:
    for base in ("src/jvmTest/kotlin", "src/jvmMain/kotlin"):
        for path in (root / base).rglob(f"{simple}.kt"):
            m = re.search(r"^package\s+([\w.]+)", path.read_text(errors="replace"), re.M)
            if m:
                return f"{m.group(1)}.{simple}"
    return None


def check_spec(case: dict, root: Path) -> list[list[str]]:
    """The failing checks, as [kind, arg] pairs: junit <class>, main <class>, python <argv...>."""
    specs: list[list[str]] = []
    log = Path(case["log"]) if case.get("log") else None
    failures = log.with_suffix(".failures.txt") if log else None
    text = case["reason"]
    if failures and failures.exists():
        for simple in dict.fromkeys(re.findall(r"^## (\w+)\.", failures.read_text(errors="replace"), re.M)):
            fq = _fq_class(root, simple)
            if fq:
                specs.append(["junit", fq])
    if "checkKDocAttachment" in text:
        specs.append(["main", "afield.tools.docs.KDocAttachmentGuard"])
    if "checkAgentTranscriptIndex" in text:
        specs.append(["main", "afield.tools.docs.AgentTranscriptIndexGuard"])
    if "checkBoxIdioms" in text or "idiom check" in text:
        specs.append(["python", "tools/check_box_idioms.py", "--all"])
    for name in re.findall(r"\b(test_\w+)\b", text):
        for path in sorted((root / "tools").glob("test_*.py")):
            if f"def {name}" in path.read_text(errors="replace"):
                specs.append(["python", str(path.relative_to(root))])
    if "Python corpus tests" in text:
        specs.append(["python", "tools/test_demo_build_scripts.py"])
    for named in re.findall(r"\b([A-Z]\w+Test)\b", text):
        fq = _fq_class(root, named)
        if fq and ["junit", fq] not in specs:
            specs.append(["junit", fq])
    return specs


def _java() -> str:
    for home in sorted(Path.home().glob(".gradle/jdks/graalvm_community-25*/*/Contents/Home")):
        return str(home / "bin" / "java")
    return "java"


def run_checks(specs: list[list[str]], root: Path) -> tuple[bool | None, str]:
    """(all green, summary). None when there is nothing runnable."""
    if not specs:
        return None, "no runnable check"
    cp = CP_FILE.read_text().strip()
    ok, notes = True, []
    for spec in specs:
        if spec[0] == "junit":
            argv = [_java(), "-cp", cp, "org.junit.runner.JUnitCore", spec[1]]
        elif spec[0] == "main":
            argv = [_java(), "-cp", cp, spec[1]]
        else:
            argv = [sys.executable, *spec[1:]]
            if spec[1].startswith("tools/check_box_idioms") and "--all" in argv and \
                    "--all" not in (root / spec[1]).read_text(errors="replace"):
                argv.remove("--all")
        done = subprocess.run(argv, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=900)
        tail = [l for l in done.stdout.splitlines() if l.strip()][-1:] or [""]
        notes.append(f"{spec[0]}:{spec[1].split('.')[-1] if spec[0] != 'python' else spec[1]}="
                     f"{'green' if done.returncode == 0 else 'red'}")
        ok = ok and done.returncode == 0
    return ok, " ".join(notes)


# ---------------------------------------------------------------------------------------- the brief

def brief(case: dict, root: Path) -> str:
    """land.py's fixer prompt and context, rebuilt from what the queue kept about this round."""
    log = Path(case["log"])
    lines = log.read_text(errors="replace").splitlines()
    asked = max(n for n, l in enumerate(lines) if "asking the fixer" in l)
    head = re.search(r"onto main ([0-9a-f]{40})", lines[0])
    main_sha = head.group(1) if head else git(root, "merge-base", case["parent"], "origin/main").strip()
    prompt = land.FIXER_PROMPT.format(max_files=land.FIXUP_MAX_FILES, max_lines=land.FIXUP_MAX_LINES,
                                      verdict=land.FIXER_VERDICT)
    stat = git(root, "diff", "--stat=120", f"{main_sha}...{case['parent']}", check=False).rstrip()
    subject = git(root, "log", "-1", "--format=%s", case["parent"]).strip()
    parts = [f"main before this round: {main_sha}\n\nBranches in the round, each merged as its own commit:",
             f"\n## {case['branch']} ({case['parent'][:9]}, by Marc)\n{subject}\n{stat}"]
    failures = log.with_suffix(".failures.txt")
    if failures.exists():
        text = failures.read_text(errors="replace")
        first = ", ".join(dict.fromkeys(re.findall(r"^## (\S+)", text, re.M)))
        parts.append(f"\nFailing tests (first): {first}")
        parts.append(text[:6000])
    parts.append("\nThe end of the landing log:\n" + "\n".join(lines[: asked + 1][-200:]))
    ledger = []
    for line in (queue_dir() / "fixer.jsonl").read_text().splitlines()[: case["ledger_before"]]:
        try:
            ledger.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    if ledger[-8:]:
        parts.append("\nWhat the fixer did on recent reds (for calibration):\n"
                     + "\n".join(f"- {e.get('outcome')}: {e.get('reason', '')}" for e in ledger[-8:]))
    replay = ("\n\nThis replay's checkout cannot build: the `gradle` command is not available, so verify by "
              "reading the check's source and rerunning any `tools/` Python check that covers it.\n")
    return prompt + "\n".join(parts) + replay


# ---------------------------------------------------------------------------------------- grading

def _changed_lines(diff: str) -> list[str]:
    return [l for l in diff.splitlines() if l[:1] in "+-" and not l.startswith(("+++", "---"))]


def grade(case: dict, root: Path, specs: list[list[str]]) -> dict:
    subprocess.run(["git", "add", "-A"], cwd=root, check=True)
    ours = git(root, "diff", "--cached", "-U3", case["parent"])
    real = git(root, "diff", "-U3", case["parent"], case["sha"])
    touched = [l.split("\t")[-1] for l in git(root, "diff", "--cached", "--name-status", case["parent"]).splitlines()]
    identical = bool(ours) and not git(root, "diff", "--cached", case["sha"], check=False).strip()
    same_files = bool(ours) and not git(root, "diff", "--cached", case["sha"], "--", *case["files"], check=False).strip() \
        and set(touched) == set(case["files"])
    ratio = difflib.SequenceMatcher(None, _changed_lines(ours), _changed_lines(real)).ratio() if ours else 0.0
    problem = land._fixup_problem(root, case["parent"]) if ours else "it changed nothing"
    passed, note = run_checks(specs, root) if ours else (False, "no edit")
    return {"diff": ours, "touched": touched, "identical": identical or same_files, "similarity": round(ratio, 2),
            "lander_problem": problem, "check_pass": passed, "check_note": note}


def reset(root: Path, rev: str) -> None:
    subprocess.run(["git", "reset", "-q", "--hard"], cwd=root, check=True)
    subprocess.run(["git", "clean", "-fdq"], cwd=root, check=True)
    subprocess.run(["git", "checkout", "-q", "--detach", rev], cwd=root, check=True)


def ensure_scratch(rev: str) -> Path:
    if not (SCRATCH / ".git").exists():
        env = {**os.environ, "GIT_LFS_SKIP_SMUDGE": "1"}
        subprocess.run(["git", "worktree", "add", "--detach", str(SCRATCH), rev], cwd=REPO, check=True, env=env)
    return SCRATCH


def run_case(case: dict, args) -> dict:
    root = ensure_scratch(case["parent"])
    reset(root, case["sha"])
    specs = check_spec(case, root)
    green_on_fix, note_fix = run_checks(specs, root)
    reset(root, case["parent"])
    red_on_parent, note_parent = run_checks(specs, root)
    controls = green_on_fix is True and red_on_parent is False
    if args.corpus and not controls:
        return {"id": case["id"], "skipped": f"controls do not hold (F^: {note_parent}; F: {note_fix})"}
    out = OUT / f"{case['id']}{args.suffix}"
    out.mkdir(parents=True, exist_ok=True)
    prompt = brief(case, root)
    (out / "prompt.md").write_text(prompt, encoding="utf-8")
    started = time.monotonic()
    argv = [sys.executable, str(HERE / "field_repo_agent.py"), "--root", str(root), "--write", "**",
            "--prompt-file", str(out / "prompt.md"), "--result-file", str(out / "result.json"),
            "--max-rounds", str(args.max_rounds), "--timeout", str(args.timeout), "--run-max-sec", "600"]
    for r in RUNS:
        argv += ["--run", r]
    if args.port:
        argv += ["--port", args.port]
    if args.model:
        argv += ["--model", args.model]
    done = subprocess.run(argv, cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    wall = time.monotonic() - started
    (out / "reply.txt").write_text(done.stdout, encoding="utf-8")
    (out / "tool_log.txt").write_text(done.stderr, encoding="utf-8")
    data = json.loads((out / "result.json").read_text()) if (out / "result.json").exists() else {}
    verdict = land._fixer_verdict(done.stdout) or {}
    g = grade(case, root, specs)
    (out / "fix.diff").write_text(g.pop("diff"), encoding="utf-8")
    row = {"id": case["id"], "branch": case["branch"], "reason": case["reason"], "checks": specs,
           "controls": controls, "control_note": f"F^: {note_parent}; F: {note_fix}",
           "ai4_verdict": verdict.get("verdict"), "culprit_ok": verdict.get("culprit") == case["branch"],
           "ai4_reason": verdict.get("reason"), **g, "model": f"{data.get('provider')}/{data.get('model')}",
           "rounds": data.get("rounds"), "input_tokens": data.get("inputTokens"),
           "output_tokens": data.get("outputTokens"), "cache_read": data.get("cacheReadTokens"),
           "wall_s": round(wall), "error": data.get("error"), "suffix": args.suffix}
    with (OUT / "results.jsonl").open("a") as f:
        f.write(json.dumps(row) + "\n")
    reset(root, case["parent"])
    return row


def outcome(r: dict) -> str:
    if r.get("ai4_verdict") != "fix" or not r.get("touched"):
        return "declined" if r.get("ai4_verdict") == "decline" else "no verdict"
    if r.get("lander_problem"):
        return "rejected"
    if r.get("identical"):
        return "identical"
    if r.get("controls") and r.get("check_pass"):
        return "check passes"
    if r.get("controls"):
        return "check red"
    return "ungraded"


def table(rows: list[dict]) -> str:
    out = ["| case | branch | check (controls) | AI4 | outcome | sim | files | rounds | tokens in/out | wall |",
           "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for r in rows:
        checks = ", ".join(s[1].split(".")[-1].split("/")[-1] for s in r["checks"]) or "none"
        ctl = "ok" if r["controls"] else "n/a"
        out.append(f"| `{r['id']}`{r.get('suffix', '')} | {r['branch']} | {checks} ({ctl}) | {r.get('ai4_verdict')} | "
                   f"**{outcome(r)}** | {r['similarity']} | {len(r['touched'])} | {r.get('rounds')} | "
                   f"{r.get('input_tokens')}/{r.get('output_tokens')} | {r['wall_s']} s |")
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("cases")
    r = sub.add_parser("run")
    r.add_argument("--id", action="append", default=[])
    r.add_argument("--corpus", action="store_true", help="every case whose check controls hold")
    r.add_argument("--port", default=os.environ.get("FIELD_AGENT_PORT", ""))
    r.add_argument("--model", default=os.environ.get("FIELD_AGENT_MODEL", ""))
    r.add_argument("--max-rounds", type=int, default=40)
    r.add_argument("--timeout", type=float, default=900)
    r.add_argument("--suffix", default="", help="tag for a re-run (e.g. -after)")
    sub.add_parser("table")
    sub.add_parser("controls", help="the check and its controls for every case, without AI4")
    args = p.parse_args(argv)
    all_cases = cases()
    if args.cmd == "cases":
        for c in all_cases:
            print(c["id"], c["branch"], (c["log"] or "-").split("/")[-1], c["reason"][:90])
        return 0
    if args.cmd == "controls":
        for c in all_cases:
            root = ensure_scratch(c["parent"])
            reset(root, c["sha"])
            specs = check_spec(c, root)
            g, ng = run_checks(specs, root)
            reset(root, c["parent"])
            rd, nr = run_checks(specs, root)
            print(json.dumps({"id": c["id"], "checks": specs, "controls": g is True and rd is False,
                              "F^": nr, "F": ng}), flush=True)
        return 0
    if args.cmd == "table":
        rows = [json.loads(l) for l in (OUT / "results.jsonl").read_text().splitlines() if l.strip()]
        print(table(rows))
        return 0
    chosen = [c for c in all_cases if any(c["sha"].startswith(i) for i in args.id)] if args.id else all_cases
    for c in chosen:
        row = run_case(c, args)
        if "skipped" in row:
            print(row["id"], "skipped:", row["skipped"], flush=True)
            continue
        print(f"{row['id']} {outcome(row)} sim={row['similarity']} {row['check_note']} ({row['wall_s']} s)", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
