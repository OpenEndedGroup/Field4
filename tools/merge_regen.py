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
where the result is verified rather than trusted.

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

# Guard task -> (gradle task that rewrites the committed artifact, extra environment).
REGENERATORS: dict[str, tuple[str, dict[str, str]]] = {
    "checkCodebaseReference": ("generateCodebaseReference", {}),
    "checkAgentTranscripts": ("regenerateAgentTranscriptStartingPoints", {}),
    "checkCompletionCorpusFixtures": ("checkCompletionCorpusFixtures", {"AFIELD_HARVEST_WRITE": "1"}),
}


def _git(repo: Path, *args: str, check: bool = True) -> str:
    return subprocess.run(
        ["git", *args], cwd=repo, check=check, stdout=subprocess.PIPE, text=True
    ).stdout


def _lines(text: str) -> list[str]:
    return [line for line in text.splitlines() if line]


def _driver_registered(repo: Path) -> bool:
    return _git(repo, "config", "--get", "merge.field-generated.driver", check=False).strip() != ""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ref", nargs="?", help="branch or commit to merge into the current branch")
    parser.add_argument("--continue", dest="cont", action="store_true", help="finish a merge after resolving source conflicts")
    parser.add_argument("--no-commit", action="store_true", help="regenerate and stage, but leave the commit to you")
    args = parser.parse_args(argv)
    repo = Path(_git(Path.cwd(), "rev-parse", "--show-toplevel").strip())

    if not _driver_registered(repo):
        print("merge driver field-generated is not registered; run: uv run python tools/install_git_hooks.py", file=sys.stderr)
        return 2

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
    selected = [guard for guard in guards_for_paths(changed) if guard.task in REGENERATORS]

    for guard in selected:
        task, extra_env = REGENERATORS[guard.task]
        print(f"merge_regen: regenerating {guard.description} ({task})")
        code = subprocess.run([str(repo / "gradlew"), task], cwd=repo, env={**os.environ, **extra_env}).returncode
        if code != 0:
            print(f"merge_regen: {task} failed; the merge is left uncommitted for you to inspect", file=sys.stderr)
            return code
        roots = [root for root in guard.output_roots if (repo / root).exists()]
        if roots:
            _git(repo, "add", "-A", "--", *roots)

    # GUARD_CORPORA.md counts corpora across the whole tree, so any merge can move it. Cheap: no Gradle.
    print("merge_regen: regenerating dev_docs/GUARD_CORPORA.md")
    if subprocess.run([sys.executable, str(repo / "tools/guard_corpora.py"), "--write"], cwd=repo).returncode != 0:
        print("merge_regen: guard_corpora.py --write failed; the merge is left uncommitted", file=sys.stderr)
        return 1
    _git(repo, "add", "--", "dev_docs/GUARD_CORPORA.md")

    if not selected:
        print("merge_regen: no Gradle-generated artifact is affected")
    # The hooks only report now, so this is where the full guard runs: once, on the merged tree.
    print("merge_regen: running the generated-artifact guards on the merged tree")
    code = subprocess.run([sys.executable, str(repo / "tools/check_staged_generated_artifacts.py")], cwd=repo).returncode
    if code != 0:
        print("merge_regen: a guard failed; the merge is left uncommitted for you to fix", file=sys.stderr)
        return code

    if args.no_commit:
        print("merge_regen: merged and regenerated; commit when ready")
        return 0
    return subprocess.run(["git", "commit", "--no-edit"], cwd=repo).returncode


if __name__ == "__main__":
    raise SystemExit(main())
