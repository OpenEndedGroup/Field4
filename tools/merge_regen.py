#!/usr/bin/env python3
"""Merge a branch and regenerate the generated artifacts on the merged tree, in one commit.

    uv run python tools/merge_regen.py main            # bring main into this branch
    uv run python tools/merge_regen.py --continue      # after resolving real (source) conflicts

Why: most landing conflicts were in files nobody writes by hand. Two branches that each add a KDoc
summary or a transcript both regenerate ``public/docs/reference/`` or the starting-point bundle, so
they conflict, the agent hand-merges the generated output, the pre-commit guard finds it stale, and
the loop repeats. ``.gitattributes`` marks those outputs ``merge=field-generated``. That driver
(registered by ``tools/install_git_hooks.py``) keeps our side so the merge never stops on them. This
script then reruns each affected generator once on the merged sources and stages the result before
committing, and runs the full generated-artifact guard first: the hooks only report, so this is
where the result is verified rather than trusted. Derived numerals in hand-written prose
(``**1025**<!--count:key-->``) work the same way: the ``field-counts`` driver merges those pages
as text without conflicting on the numeral, and the ``citationGates`` regenerator rewrites it.

Only real source conflicts stop the merge. Resolve them, ``git add`` them, and run ``--continue``.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_staged_generated_artifacts import GUARDS, guards_for_paths  # noqa: E402

# Guard task -> (Gradle arguments that rewrite the committed artifact, extra environment).
REGENERATORS: dict[str, tuple[list[str], dict[str, str]]] = {
    "checkCodebaseReference": (["generateCodebaseReference"], {}),
    "checkAgentTranscripts": (["regenerateAgentTranscriptStartingPoints"], {}),
    "checkCompletionCorpusFixtures": (["checkCompletionCorpusFixtures"], {"AFIELD_HARVEST_WRITE": "1"}),
    # Counts written into prose as `N<!--count:key-->` (logging.md's lprintln call sites, ...).
    "citationGates": (
        ["citationGates", "--tests", "afield.docs.ProseNumeralContractTest", "--rerun"],
        {"AFIELD_WRITE_DERIVED_COUNTS": "1"},
    ),
}

# Regenerators that are Gradle Test tasks (see regenerate() for why they get their own build).
TEST_TASK_REGENERATORS = {"citationGates"}

# Guards that only compare a committed artifact with its generator's output.
PURE_DRIFT_CHECKS = {"checkCodebaseReference", "checkAgentTranscripts", "checkCompletionCorpusFixtures"}

# Where a regenerator may write, when the guard has no output roots of its own.
REGENERATED_ROOTS = {"citationGates": ("public/docs", "dev_docs")}


def _git(repo: Path, *args: str, check: bool = True) -> str:
    return subprocess.run(
        ["git", *args], cwd=repo, check=check, stdout=subprocess.PIPE, text=True
    ).stdout


def _lines(text: str) -> list[str]:
    return [line for line in text.splitlines() if line]


def _driver_registered(repo: Path, name: str = "field-generated") -> bool:
    return _git(repo, "config", "--get", f"merge.{name}.driver", check=False).strip() != ""


def regenerate(repo: Path, changed: set[str]) -> tuple[int, set[str]]:
    """Rerun every generator whose inputs are in [changed], in ONE Gradle invocation, and stage what
    they wrote. Returns (exit code, the guard tasks whose drift check that has made redundant)."""
    selected = [guard for guard in guards_for_paths(changed) if guard.task in REGENERATORS]
    # Fused into as few Gradle starts as is safe: under load each start (configuration, up-to-date
    # checks, a compile check) cost minutes. But a regenerator that is a Test task runs on its own:
    # every Test task is finalised by checkTestTreeWrites, which in a shared build also sees what
    # the other generators legitimately wrote, and bounced two landings for 182 identical-byte
    # rewrites of the completion corpus.
    groups = [[g for g in selected if g.task not in TEST_TASK_REGENERATORS],
              [g for g in selected if g.task in TEST_TASK_REGENERATORS]]
    for group in filter(None, groups):
        gradle_args: list[str] = []
        env = dict(os.environ)
        for guard in group:
            args, extra_env = REGENERATORS[guard.task]
            gradle_args += args
            env.update(extra_env)
        print(f"merge_regen: regenerating {', '.join(g.description for g in group)} ({' '.join(gradle_args)})")
        code = subprocess.run([str(repo / "gradlew"), "--continue", *gradle_args], cwd=repo, env=env).returncode
        if code != 0:
            print("merge_regen: regeneration failed; the merge is left uncommitted for you to inspect", file=sys.stderr)
            return code, set()
        for guard in selected:
            roots = [root for root in (guard.output_roots or REGENERATED_ROOTS.get(guard.task, ())) if (repo / root).exists()]
            if roots:
                _git(repo, "add", "-A", "--", *roots)

    # GUARD_CORPORA.md counts corpora across the whole tree, so any merge can move it. Cheap: no Gradle.
    print("merge_regen: regenerating dev_docs/GUARD_CORPORA.md")
    if subprocess.run([sys.executable, str(repo / "tools/guard_corpora.py"), "--write"], cwd=repo).returncode != 0:
        print("merge_regen: guard_corpora.py --write failed; the merge is left uncommitted", file=sys.stderr)
        return 1, set()
    _git(repo, "add", "--", "dev_docs/GUARD_CORPORA.md")
    if not selected:
        print("merge_regen: no Gradle-generated artifact is affected")
    # A pure drift check ("does the committed artifact equal what the generator writes?") is true by
    # construction right after regenerating. citationGates is not one: it checks much more than the
    # counts it regenerated, so it always runs.
    return 0, {guard.task for guard in selected if guard.task in PURE_DRIFT_CHECKS}


def regen_since(repo: Path, base: str, message: str) -> int:
    """The landing queue's batch step: everything since [base] is already committed, one merge per
    branch. Regenerate once for all of it, commit that if it changed anything, then run the full
    guard once over base..HEAD. One Gradle run for the whole batch instead of one per branch."""
    changed = set(_lines(_git(repo, "diff", "--name-only", base, "HEAD")))
    code, redundant = regenerate(repo, changed)
    if code != 0:
        return code
    if _git(repo, "diff", "--cached", "--name-only"):
        code = subprocess.run(["git", "commit", "-q", "-m", message], cwd=repo).returncode
        if code != 0:
            return code
        print("merge_regen: committed the regenerated artifacts")
    skipped = f" (skipping {', '.join(sorted(redundant))}: just regenerated)" if redundant else ""
    print(f"merge_regen: running the generated-artifact guards over {base[:9]}..HEAD{skipped}")
    code = subprocess.run(
        [sys.executable, str(repo / "tools/check_staged_generated_artifacts.py"), "--since", base,
         "--skip", ",".join(sorted(redundant))], cwd=repo
    ).returncode
    if code != 0:
        print("merge_regen: a guard failed on the batch", file=sys.stderr)
    return code


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ref", nargs="?", help="branch or commit to merge into the current branch")
    parser.add_argument("--continue", dest="cont", action="store_true", help="finish a merge after resolving source conflicts")
    parser.add_argument("-m", "--message", help="merge commit message (default: git's own)")
    parser.add_argument("--no-commit", action="store_true", help="regenerate and stage, but leave the commit to you")
    parser.add_argument("--regen-since", metavar="BASE",
                        help="no merge: regenerate and guard everything committed since BASE (the landing queue's batch step)")
    args = parser.parse_args(argv)
    repo = Path(_git(Path.cwd(), "rev-parse", "--show-toplevel").strip())
    if args.regen_since:
        return regen_since(repo, args.regen_since, args.message or "Regenerate generated artifacts")

    if not _driver_registered(repo):
        print("merge driver field-generated is not registered; run: uv run python tools/install_git_hooks.py", file=sys.stderr)
        return 2
    if not _driver_registered(repo, "field-counts"):
        # Not fatal: without it these pages take git's own text merge, as they did before the driver.
        print("merge_regen: merge driver field-counts is not registered, so two edits of a <!--count:--> "
              "numeral will conflict; run: uv run python tools/install_git_hooks.py", file=sys.stderr)

    in_merge = subprocess.run(["git", "rev-parse", "-q", "--verify", "MERGE_HEAD"], cwd=repo, stdout=subprocess.DEVNULL).returncode == 0
    if args.cont:
        if not in_merge:
            print("no merge in progress", file=sys.stderr)
            return 2
    else:
        if not args.ref:
            parser.error("a ref to merge is required (or --continue)")
        if in_merge:
            print("a merge is already in progress; resolve it and use --continue", file=sys.stderr)
            return 2
        subprocess.run(["git", "merge", "--no-ff", "--no-commit", args.ref], cwd=repo)
        if subprocess.run(["git", "rev-parse", "-q", "--verify", "MERGE_HEAD"], cwd=repo, stdout=subprocess.DEVNULL).returncode != 0:
            print("nothing to merge (already up to date, or the merge failed before starting)")
            return 0

    unmerged = _lines(_git(repo, "diff", "--name-only", "--diff-filter=U"))
    if unmerged:
        print("source conflicts to resolve by hand, then `git add` them and rerun with --continue:", file=sys.stderr)
        for path in unmerged:
            print(f"  {path}", file=sys.stderr)
        return 1

    # Inputs that changed on either side since the merge base decide which generators can be stale.
    base = _git(repo, "merge-base", "HEAD", "MERGE_HEAD").strip()
    changed = set(_lines(_git(repo, "diff", "--name-only", base, "HEAD")))
    changed |= set(_lines(_git(repo, "diff", "--name-only", base, "MERGE_HEAD")))
    code, _redundant = regenerate(repo, changed)
    if code != 0:
        return code
    # The hooks only report now, so this is where the full guard runs: once, on the merged tree.
    print("merge_regen: running the generated-artifact guards on the merged tree")
    code = subprocess.run([sys.executable, str(repo / "tools/check_staged_generated_artifacts.py")], cwd=repo).returncode
    if code != 0:
        print("merge_regen: a guard failed; the merge is left uncommitted for you to fix", file=sys.stderr)
        return code

    if args.no_commit:
        print("merge_regen: merged and regenerated; commit when ready")
        return 0
    commit = ["git", "commit", "-m", args.message] if args.message else ["git", "commit", "--no-edit"]
    return subprocess.run(commit, cwd=repo).returncode


if __name__ == "__main__":
    raise SystemExit(main())
