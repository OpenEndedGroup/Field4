#!/usr/bin/env python3
"""Before `land.py enqueue`: run only the landing guards this branch's diff can trip.

    uv run python tools/land.py precheck            # merge main (temporarily), select, run
    uv run python tools/land.py precheck --plan     # say what it would run, run nothing
    uv run python tools/land.py precheck --no-merge # check the branch as it is, against its merge base

Why: a bounce costs more than the branch that bounced. The lander bisects a red batch, so one
branch's slip holds three others for tens of minutes. On 2026-10-20 eight starting-point branches
landed through the queue and bounced eleven times, every time on something a few minutes of the
right check would have shown: a merge conflict with siblings that landed first, a compile against
an API `main` had renamed, `ServerSurfaceLedgerTest` (new `/ws` commands), `DocumentedApiEvaluationTest`
in both directions, a reference page with no nav entry (`LanguagesCategoryTest`), a transcript box
that did not compile, mis-attached KDoc, and a bare `send(` in a JS module.

What it does, in order:

1. **Conflicts.** `git merge-tree` against `main`. A conflict is the one bounce no guard can see.
2. **Merge.** If `main` has moved, merge it into the working tree with `--no-commit` (the merge
   drivers `.gitattributes` names apply, as they do for the lander), run the checks on that tree,
   and abort the merge afterwards, so the branch is left exactly as it was. `--keep-merge` commits
   it instead. A compile against a renamed API only shows up here.
3. **Select.** From the diff against `main`, pick the checks in `CHECKS` whose inputs it touches.
4. **Run.** One Gradle invocation for everything that is Gradle (`--continue`, so every red shows),
   then the Python checks. Pure drift checks (`checkCodebaseReference`, `checkAgentTranscripts`)
   are left out: the lander regenerates those artifacts before it guards, so they never bounce.

It is not the full guard and does not try to be: it runs what the diff selects, and the lander
still runs everything. The hooks stay report-only.
"""

from __future__ import annotations

import argparse
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_staged_generated_artifacts import (  # noqa: E402
    _box_code_input,
    _compiled_transcript,
    _demo_input,
    _publication_input,
    _python_tool_input,
    _reference_input,
    _transcript_input,
)

REPO = Path(__file__).resolve().parents[1]
BASE = "main"


@dataclass
class Change:
    """What the branch changes relative to `main`: paths with their status, and the changed lines."""

    status: dict[str, str]
    """path -> git's one-letter status (A, M, D, R)."""
    hunks: dict[str, str] = field(default_factory=dict)
    """path -> the added and removed lines of its diff, for the checks that look at content."""

    @property
    def paths(self) -> list[str]:
        return sorted(self.status)

    def added(self, path: str) -> bool:
        return self.status.get(path, "") in {"A", "R"}


@dataclass(frozen=True)
class Check:
    key: str
    catches: str
    """The bounce it exists for, in the words the lander's bounce reason uses."""
    selects: Callable[[Change], list[str]]
    """The changed paths that select this check (empty: not selected)."""
    gradle: tuple[str, ...] = ()
    citation_test: str | None = None
    """A `citationGates` class to run with `--tests`, instead of all fifteen."""
    python: tuple[str, ...] = ()
    """Python commands (a script and its arguments), run under the repo `uv` environment."""
    jvm_tests: tuple[str, ...] = ()
    """jvmTest classes to run with `--tests`: guards the lander does not run, so only precheck can."""
    lander_guards: tuple[str, ...] = ()
    """The tasks in the lander's `GUARDS` (check_staged_generated_artifacts.py) this check stands in for."""


def _kotlin(path: str) -> bool:
    return path.startswith("src/") and path.endswith(".kt")


def _main_kotlin(path: str) -> bool:
    return path.startswith("src/jvmMain/kotlin/") and path.endswith(".kt")


def _paths(change: Change, predicate: Callable[[str], bool]) -> list[str]:
    return [p for p in change.paths if change.status[p] != "D" and predicate(p)]


# What ServerSurfaceLedgerTest discovers (its `declaration`, `commandName`, `rawChannelName` and
# `staticMount`), plus `route(` and `webSocket(`, which change the paths those declarations resolve to.
_SERVER_SURFACE = re.compile(
    r"^[ \t]*(?:get|post|put|delete|patch)\(\s*\"[/{]"
    r"|(?<![A-Za-z0-9_])(?:handlers|rawHandlers)\s*[\[.]"
    r"|^[ \t]*(?:staticFiles|route|webSocket)\(",
    re.M,
)


def _server_surface(change: Change) -> list[str]:
    selected = [p for p in change.paths if p == "dev_docs/server_surface_inventory.md"]
    selected += [p for p in change.paths if _main_kotlin(p) and _SERVER_SURFACE.search(change.hunks.get(p, ""))]
    return selected


def _documented_api(change: Change) -> list[str]:
    """The three populations DocumentedApiEvaluationTest reads, and the classification it reads them by.

    Published pages (what is taught), top-level transcript yaml (what is called), jvmTest sources and
    FIELD_TEST smokes (what is evaluated). Splat's bounce was the backward direction: a branch added an
    evaluator, and the ledger row it made stale had to go.
    """
    def reads(path: str) -> bool:
        return (
            (path.startswith("public/docs/") and path.count("/") == 2 and path.endswith(".md"))
            or _compiled_transcript(path)
            or (path.startswith("src/jvmTest/kotlin/") and path.endswith(".kt"))
            or path.endswith("/DemoTranscriptExecSmokeTest.kt")
        )

    selected = [p for p in change.paths if reads(p)]
    selected += [p for p in change.paths if _main_kotlin(p) and "FIELD_TEST" in change.hunks.get(p, "")]
    return sorted(set(selected))


def _reference_nav(change: Change) -> list[str]:
    """A new source file can open a new reference category, and so a new page the nav must list."""
    return [
        p
        for p in change.paths
        if (p.endswith(".kt") and _reference_input(p) and change.added(p))
        or p in {"public/zensical.yml", "src/jvmMain/kotlin/afield/tools/docs/StdlibReferenceGenerator.kt"}
    ]


def _js_module(path: str) -> bool:
    return path.startswith("src/") and path.endswith((".js", ".mjs")) and "/resources/" in path


CHECKS: tuple[Check, ...] = (
    Check(
        "compile",
        "a compile error against main (Fern: `No value passed for parameter 'block'`)",
        lambda c: _paths(c, _kotlin),
        gradle=("compileKotlinJvm",),
    ),
    Check(
        "kdoc-attachment",
        "checkKDocAttachment (WebRTC: an annotation on the same line as its declaration)",
        lambda c: _paths(c, _kotlin),
        gradle=("checkKDocAttachment",),
        lander_guards=("checkKDocAttachment",),
    ),
    Check(
        "server-surface",
        "ServerSurfaceLedgerTest (WebRTC: /ws commands missing from dev_docs/server_surface_inventory.md)",
        _server_surface,
        citation_test="afield.app.server.ServerSurfaceLedgerTest",
        lander_guards=("citationGates",),
    ),
    Check(
        "documented-api",
        "DocumentedApiEvaluationTest (Fern: an API taught and never run; Splat: a stale ledger row)",
        _documented_api,
        citation_test="afield.tools.docs.DocumentedApiEvaluationTest",
        lander_guards=("citationGates",),
    ),
    Check(
        "reference-nav",
        "LanguagesCategoryTest (Score, Geometry: a new reference page with no public/zensical.yml entry)",
        _reference_nav,
        # Not LanguagesCategoryTest itself: it reads the *committed* pages, which the lander rewrites
        # before it guards, so on an unregenerated branch it is red for drift no landing would see.
        # `missing_nav_entries` makes its nav comparison against the pages the lander will commit.
        gradle=("generateCodebaseReference",),
    ),
    Check(
        "box-code",
        "checkBoxCodeCompiles and checkBoxIdioms (WebRTC: a transcript box that did not compile)",
        lambda c: sorted(set(_paths(c, _compiled_transcript) + _paths(c, _box_code_input))),
        gradle=("checkBoxCodeCompiles", "checkBoxIdioms"),
        lander_guards=("checkBoxCodeCompiles", "checkBoxIdioms"),
    ),
    Check(
        "demo-corpus",
        "demo registration (2026-10-29, branches 2 and 3: a new dev_docs/agents/demos/* directory missing "
        "from test_demo_build_scripts.py CORPUS and AgentDemoRegistrationTest.DEMO_CORPUS / "
        "DemoTranscriptExecSmokeTest), checkAgentDemos and checkAgentTranscriptLayouts",
        lambda c: [p for p in c.paths if _demo_input(p)],
        gradle=("checkAgentDemos", "checkAgentTranscriptLayouts"),
        # Not in the lander's guard at all (it runs no jvmTest), which is how main went red on it before.
        jvm_tests=("afield.tools.docs.AgentDemoRegistrationTest",),
        # test_demo_build_scripts.py (the CORPUS that bounced both branches) runs under python-tools,
        # which the same paths select.
        lander_guards=("checkAgentDemos", "checkAgentTranscriptLayouts"),
    ),
    Check(
        "transcript-index",
        "checkAgentTranscriptIndex (a transcript added or renamed without its public/docs/agent_transcripts.md entry)",
        lambda c: [p for p in c.paths if _transcript_input(p)],
        gradle=("checkAgentTranscriptIndex",),
        lander_guards=("checkAgentTranscriptIndex",),
    ),
    Check(
        "js-module-imports",
        "test_check_module_imports (WebRTC: a bare `send(` in a module script)",
        lambda c: _paths(c, _js_module),
        python=("tools/check_module_imports.py",),
    ),
    Check(
        "docs-publications",
        "checkDocsPublications (a published page or transcript whose web publication is stale or uncovered)",
        lambda c: [p for p in c.paths if _publication_input(p)],
        gradle=("checkDocsPublications",),
        lander_guards=("checkDocsPublications",),
    ),
    Check(
        "python-tools",
        "checkPythonTools (a tools/ change that broke a tools/test_*.py suite; a demo, reference page or "
        "build.gradle.kts change that broke a corpus contract one of them holds)",
        # The lander's own selector, so precheck cannot select less than the landing does.
        lambda c: [p for p in c.paths if _python_tool_input(p)],
        python=("tools/run_python_tests.py",),
        lander_guards=("checkPythonTools",),
    ),
)


# Lander guards precheck leaves out on purpose, and why. Every other task in GUARDS must appear in some
# check's `lander_guards` (test_land_precheck pins this both ways), so a guard added to the landing
# cannot go unselected here without someone deciding it should.
NOT_PRECHECKED: dict[str, str] = {
    "checkCodebaseReference": "pure drift: the lander regenerates it first (reference-nav covers the nav half)",
    "checkAgentTranscripts": "pure drift: the lander regenerates the starting-point bundle first",
    "checkCompletionCorpusFixtures": "pure drift: the lander regenerates the fixtures first",
}


def select(change: Change) -> list[tuple[Check, list[str]]]:
    return [(check, why) for check in CHECKS if (why := check.selects(change))]


def gradle_arguments(selected: list[Check], reference_out: Path) -> list[str]:
    """One Gradle command line for every selected Gradle check.

    `--tests` is a task option, so it follows `citationGates`; the task's own filter still applies, and
    a test must match both, which is what makes this run three classes rather than fifteen.
    """
    tasks: list[str] = []
    for check in selected:
        for task in check.gradle:
            if task not in tasks:
                tasks.append(task)
    args = ["--continue", *tasks]
    if any(c.key == "reference-nav" for c in selected):
        args.append(f"-Pout={reference_out}")
    jvm_tests = [t for c in selected for t in c.jvm_tests]
    if jvm_tests:
        args += ["jvmTest", "--rerun"]
        for test in dict.fromkeys(jvm_tests):
            args += ["--tests", test]
    tests = [c.citation_test for c in selected if c.citation_test]
    if tests:
        args += ["citationGates", "--rerun"]
        for test in tests:
            args += ["--tests", test]
    return args


def missing_nav_entries(reference_dir: Path, nav_text: str) -> list[str]:
    """The pages a regeneration would publish that `public/zensical.yml` does not link.

    The same comparison LanguagesCategoryTest makes against the committed pages; made here against
    the pages the lander will commit, because it regenerates them before it guards.
    """
    # With -Pout the generator writes its KDoc worklist beside the pages; committed, it lives in dev_docs.
    pages = sorted(p.name for p in reference_dir.glob("*.md") if p.name != "kdoc_worklist.md")
    return [page for page in pages if f"reference/{page}" not in nav_text]


# ---------------------------------------------------------------------------------------------
# Git
# ---------------------------------------------------------------------------------------------


def _git(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=REPO, check=check, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True)


def _patch_ids(repo: Path, revisions: str) -> dict[str, str]:
    """patch-id -> commit, for the non-merge commits in `revisions` (oldest wins on a repeat)."""
    log = subprocess.run(["git", "log", "-p", "--no-merges", "--reverse", "--format=commit %H", revisions],
                         cwd=repo, check=True, stdout=subprocess.PIPE).stdout
    ids = subprocess.run(["git", "patch-id", "--stable"], cwd=repo, input=log, check=True,
                         stdout=subprocess.PIPE).stdout.decode()
    found: dict[str, str] = {}
    for line in ids.splitlines():
        pid, _, sha = line.partition(" ")
        found.setdefault(pid, sha)
    return found


@dataclass(frozen=True)
class Duplicate:
    commit: str
    """The branch's commit."""
    on_base: str
    """The commit already on the base with the same patch."""
    subject: str


def duplicate_patches(repo: Path, base: str = BASE, head: str = "HEAD") -> list[Duplicate]:
    """Commits on `head` whose patch `base` already has under another sha (a cherry-pick of a commit
    that has since landed, or the same fix landed twice).

    2026-10-29: a branch carried `8b68b8187`, a cherry-pick of a command-tool commit that landed on
    main as `23018e9b6` during the night; the two then conflicted in IN_FIELD_AGENT_HARNESS.md and the
    commit had to be found and dropped by hand. `git patch-id --stable` over merge-base..base and
    merge-base..head finds it before any merge is tried.
    """
    merge_base = subprocess.run(["git", "merge-base", base, head], cwd=repo, check=True,
                                stdout=subprocess.PIPE, text=True).stdout.strip()
    on_base = _patch_ids(repo, f"{merge_base}..{base}")
    if not on_base:
        return []
    duplicates = []
    for pid, sha in _patch_ids(repo, f"{merge_base}..{head}").items():
        if pid in on_base:
            subject = subprocess.run(["git", "log", "-1", "--format=%s", sha], cwd=repo, check=True,
                                     stdout=subprocess.PIPE, text=True).stdout.strip()
            duplicates.append(Duplicate(sha, on_base[pid], subject))
    return duplicates


def describe_duplicates(duplicates: list[Duplicate], base: str = BASE) -> str:
    lines = [f"{d.commit[:9]} \"{d.subject}\" is already on {base} as {d.on_base[:9]}" for d in duplicates]
    first = duplicates[0].commit[:9]
    fix = (f"fix: `git rebase --onto {first}^ {first}` drops that one commit; `git rebase {base}` drops "
           "them all (rebase skips a commit whose patch is already upstream)")
    return "\n  ".join(lines) + "\n  " + fix


def conflicts_with(base: str) -> list[str] | None:
    """Files a merge of `base` into HEAD would leave conflicted (None: it merges cleanly)."""
    result = _git("merge-tree", "--write-tree", "--name-only", "--no-messages", base, "HEAD", check=False)
    if result.returncode == 0:
        return None
    if result.returncode != 1:
        raise RuntimeError(f"git merge-tree failed: {result.stderr.strip()}")
    # First line is the tree; the conflicted paths follow.
    return [line for line in result.stdout.splitlines()[1:] if line.strip()]


def read_change(base: str) -> Change:
    """The working tree against `base`, untracked files included: what would land if committed."""
    status: dict[str, str] = {}
    for line in _git("diff", "--name-status", "-M", base).stdout.splitlines():
        parts = line.split("\t")
        letter = parts[0][0]
        status[parts[-1]] = letter
        if letter == "R":
            status[parts[1]] = "D"
    for path in _git("ls-files", "--others", "--exclude-standard").stdout.splitlines():
        status.setdefault(path, "A")
    hunks: dict[str, str] = {}
    for path, letter in status.items():
        if not _main_kotlin(path):
            continue
        if letter == "A" and _git("ls-files", "--error-unmatch", path, check=False).returncode != 0:
            text = (REPO / path).read_text(errors="replace")
        else:
            diff = _git("diff", "-U0", base, "--", path).stdout
            text = "\n".join(l[1:] for l in diff.splitlines() if l[:1] in "+-" and not l.startswith(("+++", "---")))
        hunks[path] = text
    return Change(status, hunks)


# ---------------------------------------------------------------------------------------------
# Running
# ---------------------------------------------------------------------------------------------


def _say(text: str) -> None:
    print(f"precheck: {text}", flush=True)


def _run_gradle(args: list[str], changed: list[str]) -> int:
    from verification_runtime import pinned_graalvm_args  # noqa: PLC0415

    with tempfile.NamedTemporaryFile("w", suffix=".txt", prefix="precheck-changed-", delete=False) as listing:
        listing.write("\n".join(changed) + "\n")
    try:
        env = {**os.environ, "AFIELD_GUARD_CHANGED_PATHS_FILE": listing.name}
        command = [str(REPO / "gradlew"), *pinned_graalvm_args(REPO), *args]
        _say("$ ./gradlew " + " ".join(args))
        return subprocess.run(command, cwd=REPO, env=env).returncode
    finally:
        os.unlink(listing.name)


def _run_python(command: str) -> int:
    _say(f"$ uv run --project . --group backdoor python {command}")
    return subprocess.run(
        ["uv", "run", "--project", ".", "--group", "backdoor", "--frozen", "python", *shlex.split(command)], cwd=REPO
    ).returncode


def _merge_main(base: str) -> bool:
    """Merge `base` into the working tree uncommitted. False if that could not be done cleanly."""
    result = _git("merge", "--no-ff", "--no-commit", base, check=False)
    if _git("rev-parse", "-q", "--verify", "MERGE_HEAD", check=False).returncode != 0:
        _say(f"merging {base} did not leave a merge in progress:\n{result.stdout}{result.stderr}")
        return False
    unmerged = _git("diff", "--name-only", "--diff-filter=U").stdout.split()
    if unmerged:
        _git("merge", "--abort", check=False)
        _say("merging main conflicted after all: " + ", ".join(unmerged))
        return False
    return True


def _tracked_edits() -> list[str]:
    return [line for line in _git("status", "--porcelain", "--untracked-files=no").stdout.splitlines() if line]


def run(args: argparse.Namespace) -> int:
    # The checkout it is run in, which is normally the one it lives in. Not always: replaying an old
    # bounced commit runs this copy of the script against a checkout of that commit.
    global REPO
    REPO = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"], check=True, stdout=subprocess.PIPE,
                               text=True).stdout.strip())
    base = args.base
    branch = _git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    if branch == base:
        _say(f"you are on {base}; precheck a topic branch")
        return 2
    started = time.time()

    duplicates = duplicate_patches(REPO, base)
    if duplicates:
        _say(f"{len(duplicates)} commit(s) on {branch} repeat a patch {base} already has:\n  "
             + describe_duplicates(duplicates, base))
        _say("the lander would merge the same change twice; that conflicts wherever the two sides "
             "drifted, or lands a redundant commit. Drop them, then precheck again.")
        return 1

    conflicts = conflicts_with(base)
    if conflicts:
        _say(f"{branch} conflicts with {base} in: " + ", ".join(conflicts))
        _say(f"the lander would bounce it unseen. Bring {base} in (`uv run python tools/merge_regen.py {base}`), "
             "resolve, commit, then precheck again.")
        return 1

    behind = _git("merge-base", "--is-ancestor", base, "HEAD", check=False).returncode != 0
    merged = False
    if behind and not args.no_merge and not args.plan:
        edits = _tracked_edits()
        if edits:
            _say(f"{base} has moved and the working tree has uncommitted edits, so it cannot merge {base} "
                 "to check the result. Commit them (or pass --no-merge to check against the merge base):\n  "
                 + "\n  ".join(edits))
            return 2
        if not _merge_main(base):
            return 1
        merged = True
        _say(f"merged {base} into the working tree, uncommitted, to check what the lander will build")

    try:
        diff_base = base if (merged or not behind) else _git("merge-base", base, "HEAD").stdout.strip()
        change = read_change(diff_base)
        selected = select(change)
        if not selected:
            _say(f"nothing in this diff ({len(change.paths)} path(s)) selects a check; enqueue")
            return 0
        _say(f"{len(change.paths)} changed path(s) against {base}; running:")
        for check, why in selected:
            more = f" (+{len(why) - 3} more)" if len(why) > 3 else ""
            print(f"  {check.key:18} {', '.join(why[:3])}{more}\n  {'':18} catches {check.catches}")
        if args.plan:
            if behind:
                _say(f"(--plan: {base} has moved; a real run merges it first)")
            return 0

        checks = [c for c, _ in selected]
        if merged and any(c.key == "python-tools" for c in checks):
            # The lander regenerates this inventory on its merged tree. A trial merge can combine
            # two individually current inventories into a stale one; checking it before refreshing
            # only buys a false red. Stage the refreshed artifact so merge --abort restores it too.
            _say("refreshing the guard inventory on the trial merge")
            if subprocess.run(
                [sys.executable, "tools/guard_corpora.py", "--write"], cwd=REPO
            ).returncode != 0:
                _git("restore", "--worktree", "--", "dev_docs/GUARD_CORPORA.md", check=False)
                _say("guard inventory regeneration failed")
                return 1
            _git("add", "--", "dev_docs/GUARD_CORPORA.md")
        failed: list[str] = []
        with tempfile.TemporaryDirectory(prefix="precheck-reference-") as reference_out:
            if any(c.gradle or c.citation_test or c.jvm_tests for c in checks):
                if _run_gradle(gradle_arguments(checks, Path(reference_out)), change.paths) != 0:
                    failed.append("gradle (read the FAILED tasks and tests above)")
            if any(c.key == "reference-nav" for c in checks):
                missing = missing_nav_entries(Path(reference_out), (REPO / "public/zensical.yml").read_text())
                if missing:
                    failed.append(
                        "reference-nav: the lander will regenerate public/docs/reference/ with "
                        f"{missing}, and public/zensical.yml links none of them. Add a "
                        "`reference/<page>.md` entry under the Reference nav for each "
                        "(or file the new source under an existing category in StdlibReferenceGenerator)"
                    )
        for check in checks:
            for script in check.python:
                if _run_python(script) != 0:
                    failed.append(f"{check.key}: {script}")
    finally:
        if merged:
            if args.keep_merge:
                _git("commit", "--no-edit", "-q")
                _say(f"committed the merge of {base} (--keep-merge)")
            else:
                _git("merge", "--abort", check=False)
                _say(f"aborted the trial merge of {base}; the branch is as it was")

    took = time.time() - started
    if failed:
        _say(f"RED after {took / 60:.1f} min. Fix before enqueueing:\n  " + "\n  ".join(failed))
        return 1
    _say(f"green after {took / 60:.1f} min on what this diff selects; `uv run python tools/land.py enqueue`")
    return 0


def add_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--base", default=BASE, help="what the lander merges into (default: main)")
    parser.add_argument("--plan", action="store_true", help="say what it would run; run nothing")
    parser.add_argument("--no-merge", action="store_true",
                        help="check the branch as it is, without merging main in first")
    parser.add_argument("--keep-merge", action="store_true",
                        help="commit the merge of main instead of aborting it afterwards")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="land.py precheck", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    add_arguments(parser)
    return run(parser.parse_args(argv))


if __name__ == "__main__":
    raise SystemExit(main())
