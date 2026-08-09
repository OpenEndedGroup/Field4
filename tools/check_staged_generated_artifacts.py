#!/usr/bin/env python3
"""Run the generated-artifact guards affected by the staged commit.

The checks themselves operate on the working tree, not Git's index.  To avoid a
false pass, this command refuses to check an artifact when an unstaged input or
output could make the working tree differ from what is about to be committed.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable


PathPredicate = Callable[[str], bool]


def _under(path: str, root: str) -> bool:
    return path == root or path.startswith(f"{root}/")


def _kotlin_source(path: str) -> bool:
    return _under(path, "src") and path.endswith(".kt")


def _reference_input(path: str) -> bool:
    return (
        (_under(path, "src/jvmMain/kotlin/afield/stdlib") and path.endswith(".kt"))
        or (_under(path, "src/jvmMain/kotlin/afield/visualization") and path.endswith(".kt"))
        or (_under(path, "public/docs") and path.endswith(".md"))
    )


def _transcript_input(path: str) -> bool:
    return (
        _under(path, "dev_docs/agents/transcripts")
        or _under(path, "public/docs/assets/agent_transcripts")
        or path == "public/docs/agent_transcripts.md"
    )


def _completion_input(path: str) -> bool:
    return (
        _under(path, "public/docs/assets/agent_transcripts")
        or _under(path, "templates")
        or _under(path, "tools/completion-corpus/boxes")
        or _under(path, "src/jvmMain/kotlin/afield/macros")
        or path
        in {
            "src/jvmMain/kotlin/afield/CompletionCorpus.kt",
            "src/jvmMain/kotlin/afield/CompletionCorpusHarvest.kt",
            "src/jvmMain/kotlin/afield/KotlinInterface.kt",
            "src/jvmMain/kotlin/afield/KotlinInterface2.kt",
            "src/jvmMain/kotlin/afield/KotlinScriptEngine.kt",
        }
    )


@dataclass(frozen=True)
class Guard:
    task: str
    description: str
    input_matches: PathPredicate
    output_roots: tuple[str, ...] = ()

    def is_relevant(self, path: str) -> bool:
        return self.input_matches(path) or any(_under(path, root) for root in self.output_roots)


GUARDS = (
    Guard(
        "checkCodebaseReference",
        "codebase reference and KDoc worklist",
        _reference_input,
        ("public/docs/reference", "dev_docs/kdoc_worklist.md"),
    ),
    Guard("checkKDocAttachment", "KDoc attachment", _kotlin_source),
    Guard(
        "checkAgentTranscripts",
        "materialized agent transcript bundle",
        _transcript_input,
        ("public/docs/assets/agent_transcripts",),
    ),
    Guard("checkAgentTranscriptIndex", "agent transcript index", _transcript_input),
    Guard(
        "checkCompletionCorpusFixtures",
        "completion-corpus fixtures",
        _completion_input,
        ("tools/completion-corpus/boxes",),
    ),
)


def guards_for_paths(paths: Iterable[str]) -> list[Guard]:
    changed = tuple(paths)
    return [guard for guard in GUARDS if any(guard.is_relevant(path) for path in changed)]


def conflicting_unstaged_paths(guards: Iterable[Guard], paths: Iterable[str]) -> list[str]:
    selected = tuple(guards)
    return sorted(path for path in paths if any(guard.is_relevant(path) for guard in selected))


def _git_paths(repo: Path, *args: str) -> list[str]:
    result = subprocess.run(
        ["git", *args, "-z"],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
    )
    return [item.decode("utf-8", "surrogateescape") for item in result.stdout.split(b"\0") if item]


def _repo_root() -> Path:
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    )
    return Path(result.stdout.strip()).resolve()


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--all",
        action="store_true",
        help="run every generated-artifact guard instead of selecting from staged paths",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv or sys.argv[1:])
    repo = _repo_root()
    staged = _git_paths(repo, "diff", "--cached", "--name-only", "--diff-filter=ACMR")
    selected = list(GUARDS) if args.all else guards_for_paths(staged)

    if not selected:
        print("generated-artifact guard: no affected staged paths")
        return 0

    if not args.all:
        unstaged = _git_paths(repo, "diff", "--name-only", "--diff-filter=ACMR")
        conflicts = conflicting_unstaged_paths(selected, unstaged)
        if conflicts:
            print(
                "generated-artifact guard: cannot verify the staged snapshot while relevant "
                "working-tree changes are unstaged:",
                file=sys.stderr,
            )
            for path in conflicts:
                print(f"  {path}", file=sys.stderr)
            print("Stage those changes or set them aside, then commit again.", file=sys.stderr)
            return 1

    print("generated-artifact guard: " + ", ".join(guard.description for guard in selected))
    command = [str(repo / "gradlew"), "--continue", *(guard.task for guard in selected)]
    return subprocess.run(command, cwd=repo).returncode


if __name__ == "__main__":
    raise SystemExit(main())
