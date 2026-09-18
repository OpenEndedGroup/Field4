#!/usr/bin/env python3
"""Run artifact and documentation-contract guards affected by a staged commit or merge.

The checks themselves operate on the working tree, not Git's index.  To avoid a
false pass, this command refuses to check an artifact when an unstaged input or
output could make the working tree differ from what is about to be committed.

``--since <rev>`` selects from what changed between ``<rev>`` and ``HEAD`` instead
of from the index.  That is the ``post-merge`` case, and it exists because the
pre-commit path structurally cannot cover it: hooks live in ``.git/hooks``, which
is per-clone and is never transported by Git, so work authored in another clone
and brought in by ``git pull`` has never passed a hook here.  Every generated
artifact that went stale on ``main`` between 2026-08-08 and 2026-08-14 arrived
that way.
"""

from __future__ import annotations

import argparse
import functools
import re
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


_REFERENCE_GENERATOR_REL = "src/jvmMain/kotlin/afield/docs/StdlibReferenceGenerator.kt"
_SOURCE_ROOTS_DECL = re.compile(r"private val SOURCE_ROOTS = listOf\((.*?)\n    \)", re.DOTALL)
_KOTLIN_STRING = re.compile(r'"([^"\n]+)"')


class ReferenceRootsUnreadable(RuntimeError):
    """`SOURCE_ROOTS` could not be read out of the generator.

    Raised rather than falling back to a hard-coded list, because a silent fallback is the defect
    this whole derivation exists to remove: the guard would keep passing over whatever the fallback
    happened to name on the day the generator moved.
    """


@functools.lru_cache(maxsize=1)
def _reference_source_roots() -> tuple[str, ...]:
    """The source roots ``generateCodebaseReference`` actually reads, from the generator itself.

    Derived, never copied. This predicate used to name two roots —
    ``afield/stdlib`` and ``afield/visualization`` — while
    :data:`afield.docs.StdlibReferenceGenerator.SOURCE_ROOTS` named seventeen. Measured over
    ``git ls-files`` in this worktree on 2026-09-18: **402** tracked ``.kt`` files sit under the
    generator's roots and the old two-root predicate selected **170** of them, so **232 (58%)** of
    the generator's own input set could be staged with the staleness guard never firing. Three
    branches did exactly that on 2026-09-17, and two of them were caught only incidentally,
    because they happened to also stage a Markdown file under ``public/docs`` — so whether an
    agent was made to commit regenerated pages was decided by whether it touched a ``.md``, not by
    whether it changed the API.

    The same argument, and nearly the same sentence, is already written above
    :func:`_macro_producer`: keeping a second hand-written path list is how ``stdlib/shaders.kt``
    escaped that guard. One list, read from the thing that owns it.

    Deliberately *not* derived: the generator's ``EXCLUDE_PREFIXES``. Selecting an excluded file
    over-runs one gate, which costs time; failing to select an included one is the defect. The
    asymmetry is the whole point, so the conservative direction is the coarser read.
    """
    repo = Path(__file__).resolve().parents[1]
    generator = repo / _REFERENCE_GENERATOR_REL
    if not generator.is_file():
        raise ReferenceRootsUnreadable(
            f"{_REFERENCE_GENERATOR_REL} is missing — the codebase-reference generator moved. "
            "Point _REFERENCE_GENERATOR_REL at its new home; do not re-introduce a copy of "
            "SOURCE_ROOTS here."
        )
    match = _SOURCE_ROOTS_DECL.search(generator.read_text())
    if match is None:
        raise ReferenceRootsUnreadable(
            f"no `private val SOURCE_ROOTS = listOf(...)` in {_REFERENCE_GENERATOR_REL} — the "
            "declaration this predicate reads was renamed or reshaped. Update "
            "_SOURCE_ROOTS_DECL; do not re-introduce a copy of the list here."
        )
    roots = tuple(_KOTLIN_STRING.findall(match.group(1)))
    if not roots:
        raise ReferenceRootsUnreadable(
            f"`SOURCE_ROOTS` in {_REFERENCE_GENERATOR_REL} parsed to an empty list"
        )
    return roots


def _reference_input(path: str) -> bool:
    # The generator is its own input: every page's text comes out of this file's blurbs, category
    # map and renderers, so editing it restages all 20 committed pages. It is not under any of
    # SOURCE_ROOTS, so it has to be named.
    if path == _REFERENCE_GENERATOR_REL:
        return True
    if _under(path, "public/docs") and path.endswith(".md"):
        return True
    return path.endswith(".kt") and any(
        _under(path, root) for root in _reference_source_roots()
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
            "src/jvmMain/kotlin/afield/stdlib/shaders.kt",
        }
        or _macro_producer(path)
    )


_MACRO_REGISTRATION = re.compile(
    r"(?:MacroRegistry\s*\.\s*)?callRewrite(?:WithContext)?\s*\["
)


@functools.lru_cache(maxsize=None)
def _macro_producer(path: str) -> bool:
    """Whether ``path`` registers code that changes completion-corpus expansion.

    The corpus runs snippets through Field's real macro expansion. Its producers are therefore
    the Kotlin sources that register into ``callRewrite`` / ``callRewriteWithContext``; keeping a
    second hand-written path list is exactly how ``stdlib/shaders.kt`` escaped the guard. Read both
    the working tree and HEAD so additions, edits, and deletions all select the producer's guard.
    """
    if not (_under(path, "src/jvmMain/kotlin") and path.endswith(".kt")):
        return False
    repo = Path(__file__).resolve().parents[1]
    texts: list[str] = []
    working = repo / path
    if working.is_file():
        try:
            texts.append(working.read_text())
        except OSError:
            pass
    previous = subprocess.run(
        ["git", "show", f"HEAD:{path}"],
        cwd=repo,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    if previous.returncode == 0:
        texts.append(previous.stdout)
    return any(_MACRO_REGISTRATION.search(text) for text in texts)


_CITATION_TEST_SOURCES = {
    "src/jvmTest/kotlin/afield/docs/CitationContractTest.kt",
    "src/jvmTest/kotlin/afield/docs/CitationGuard.kt",
    "src/jvmTest/kotlin/afield/docs/DocsFenceTreatmentTest.kt",
    "src/jvmTest/kotlin/afield/docs/DocsGatesWiringTest.kt",
    "src/jvmTest/kotlin/afield/docs/SmallLanguageAnatomyTest.kt",
    "src/jvmTest/kotlin/afield/graphics/CloseNodeCitationDriftTest.kt",
    "src/jvmTest/kotlin/afield/server/ServerSurfaceLedgerTest.kt",
}
_CITATION_EXTENSIONS = "kt|js|md|py|kts|html|swift|glsl|json|yml|yaml"
_CITATION_LEDGER_PATH = re.compile(
    rf"`([A-Za-z0-9_./-]+\.(?:{_CITATION_EXTENSIONS})):\d+`\s*\|"
)
_CITATION_SYMBOL_PATH = re.compile(
    rf"`([A-Za-z0-9_./-]+\.(?:{_CITATION_EXTENSIONS}))(?::\d+)?#[^`]+`"
)
_GUARDED_PAGE = re.compile(r'"(dev_docs/[^"\n]+\.md)"\s+to\s+\d+')
_CLOSE_NODE_PATH = re.compile(
    rf'Triple\("([A-Za-z0-9_./-]+\.(?:{_CITATION_EXTENSIONS}))",\s*\d+,'
)


@functools.lru_cache(maxsize=1)
def _citation_input_paths() -> frozenset[str]:
    """Read the citation registries instead of maintaining a second source-path list here."""
    repo = Path(__file__).resolve().parents[1]
    contract_source = repo / "src/jvmTest/kotlin/afield/docs/CitationContractTest.kt"
    close_source = repo / "src/jvmTest/kotlin/afield/graphics/CloseNodeCitationDriftTest.kt"

    guarded_pages = set(_GUARDED_PAGE.findall(contract_source.read_text()))
    guarded_pages.add("dev_docs/fline_close_nodes.md")

    cited_names: set[str] = set()
    for page in guarded_pages:
        page_file = repo / page
        if not page_file.is_file():
            # Keep the missing page itself in the input set. citationGates will explain the broken
            # registry; selection must not crash before Gradle gets that chance.
            continue
        text = page_file.read_text()
        cited_names.update(_CITATION_LEDGER_PATH.findall(text))
        cited_names.update(_CITATION_SYMBOL_PATH.findall(text))
    if close_source.is_file():
        cited_names.update(_CLOSE_NODE_PATH.findall(close_source.read_text()))

    # Read both the staged tree and HEAD. A deleted cited file is absent from `git ls-files` after
    # `git add -u`, but its old path must still select the guard that reports the broken citation.
    tracked = set(_git_paths(repo, "ls-files"))
    tracked.update(_git_paths(repo, "ls-tree", "-r", "--name-only", "HEAD"))
    resolved = set(guarded_pages)
    for cited in cited_names:
        # Full repo-relative citations take this branch. Abbreviated citations deliberately match
        # every tracked suffix here: a newly ambiguous path must still select the guard that will
        # reject it, rather than disappearing from the pre-commit input set.
        if cited in tracked:
            resolved.add(cited)
        else:
            suffix = f"/{cited}"
            resolved.update(path for path in tracked if path.endswith(suffix))

    resolved.update(_CITATION_TEST_SOURCES)
    resolved.update({"build.gradle.kts", "public/docs_hooks.py"})
    return frozenset(resolved)


def _citation_input(path: str) -> bool:
    if path in _citation_input_paths():
        return True
    # Two focused tests intentionally discover their inputs rather than cite a fixed list:
    # DocsFenceTreatmentTest checks every published Markdown page, while ServerSurfaceLedgerTest
    # inventories the complete server route/command source surface.
    return (
        (_under(path, "public/docs") and path.endswith(".md"))
        or (_under(path, "src/jvmMain/kotlin/afield") and path.endswith(".kt"))
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
    Guard(
        "citationGates",
        "citation and documentation contracts",
        _citation_input,
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
        help="run every registered repository guard instead of selecting from staged paths",
    )
    parser.add_argument(
        "--since",
        metavar="REV",
        help=(
            "select from paths changed between REV and HEAD instead of from the index "
            "(the post-merge case: work that arrived by pull never passed a hook here)"
        ),
    )
    return parser.parse_args(argv)


def _run_merge_check(repo: Path, since: str) -> int:
    """Guard whatever a merge just brought in.

    Deliberately advisory. Git ignores a ``post-merge`` hook's exit status, so this cannot and
    should not try to block: the merge has already happened and un-doing it is not the hook's
    business. What it owes the reader is the thing that was missing — the news, on the day, with
    the command that fixes it, instead of a red gate the next morning.
    """
    if not _rev_exists(repo, since):
        print(f"generated-artifact guard: no such revision {since}, skipping")
        return 0

    changed = _git_paths(repo, "diff", "--name-only", "--diff-filter=ACMRD", f"{since}..HEAD")
    selected = guards_for_paths(changed)
    if not selected:
        return 0

    print(
        "generated-artifact guard: checking "
        + ", ".join(guard.description for guard in selected)
        + f" (changed by {since}..HEAD)"
    )
    command = [str(repo / "gradlew"), "--continue", *(guard.task for guard in selected)]
    code = subprocess.run(command, cwd=repo).returncode
    if code != 0:
        tasks = " ".join(guard.task for guard in selected)
        print(
            "\n"
            "  A generated artifact is stale after this merge.\n"
            "  It was not your commit that broke it and no hook ran on the clone that did:\n"
            "  .git/hooks is per-clone and Git never transports it.\n"
            "\n"
            f"  Reproduce:  ./gradlew {tasks}\n"
            "  The failing task names the artifact and the command that regenerates it.\n"
            "  Regenerate and commit that, or `main` is red until someone else finds it.\n"
            "\n"
            "  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.\n",
            file=sys.stderr,
        )
    return code


def _rev_exists(repo: Path, rev: str) -> bool:
    return (
        subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", f"{rev}^{{commit}}"],
            cwd=repo,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        ).returncode
        == 0
    )


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv or sys.argv[1:])
    repo = _repo_root()

    if args.since:
        return _run_merge_check(repo, args.since)

    staged = _git_paths(repo, "diff", "--cached", "--name-only", "--diff-filter=ACMRD")
    selected = list(GUARDS) if args.all else guards_for_paths(staged)

    if not selected:
        print("generated-artifact guard: no affected staged paths")
        return 0

    if not args.all:
        unstaged = _git_paths(repo, "diff", "--name-only", "--diff-filter=ACMRD")
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
