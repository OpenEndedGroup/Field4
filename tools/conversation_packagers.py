"""The recorded-conversation pages and the packagers that build them, for the hook and the lander.

Each ``tools/package_<name>_conversation.py`` copies a saved demo (``dev_docs/agents/demos/<name>_conversation*``)
and the study-tools Kotlin it names into ``public/docs/<name>_conversation.md`` and its downloads.
Editing any of those inputs without re-running the packager leaves the page stale, and
``tools/test_conversation_demos.py`` bounces the landing. Two batches did exactly that on
2026-10-17: a KDoc rewrite of ``FunctionStudyTools.kt``, and box-code batch 32 editing the
stack.auto demo's sources.

So the commit hook names the packager when a staged path feeds one (:func:`packagers_for_paths`),
and ``tools/merge_regen.py`` repackages whatever is stale on every landing (:func:`repackage_stale`),
which is what retires the bounce. Stdlib only: the hook imports this.
"""
from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
DEMOS = "dev_docs/agents/demos/"
_SOURCE = re.compile(r'^(?:TOOLS_SOURCE|TOOL_EXAMPLE) = ROOT / "([^"]+)"', re.M)


@dataclass(frozen=True)
class Packager:
    script: str          # tools/package_<name>_conversation.py
    stem: str            # <name>_conversation: the page name and the demo directory prefix
    sources: tuple[str, ...]  # repo-relative Kotlin the packager copies

    def feeds(self, path: str) -> bool:
        if path in self.sources:
            return True
        return path.startswith(DEMOS) and path[len(DEMOS):].split("/", 1)[0].startswith(self.stem)

    @property
    def outputs(self) -> tuple[str, str]:
        return (f"public/docs/{self.stem}.md", f"public/docs/assets/{self.stem}")


def packagers(repo: Path = ROOT) -> list[Packager]:
    found = []
    for script in sorted((repo / "tools").glob("package_*_conversation.py")):
        stem = script.stem.removeprefix("package_")
        found.append(Packager(f"tools/{script.name}", stem, tuple(_SOURCE.findall(script.read_text()))))
    return found


def packagers_for_paths(paths: Iterable[str], repo: Path = ROOT) -> list[Packager]:
    changed = tuple(paths)
    return [p for p in packagers(repo) if any(p.feeds(path) for path in changed)]


def repackage_stale(repo: Path) -> tuple[int, list[str]]:
    """Check and regenerate with the same docs environment as ConversationDemoTest.

    The lander may run in another checkout's Python. Its zlib can produce different ZIP bytes
    (e.g. system zlib-ng versus uv's zlib), which made every guard undo the preceding regeneration.
    Both operations must use this checkout's repository environment.
    """
    written: list[str] = []
    for p in packagers(repo):
        command = ["uv", "run", "--project", ".", "--group", "docs", "--frozen", "python", p.script]
        if subprocess.run([*command, "--check"], cwd=repo, capture_output=True).returncode == 0:
            continue
        print(f"conversation packagers: {p.stem} is stale; running {p.script}")
        if subprocess.run(command, cwd=repo).returncode != 0:
            print(f"conversation packagers: {p.script} failed", file=sys.stderr)
            return 1, written
        written += [o for o in p.outputs if (repo / o).exists()]
    return 0, written
