"""A bidirectional ledger of the files tracked at the repository root.

This guards against accidentally committing new files at the repository root. It does not
police untracked output on disk. Root captures and ad-hoc scratch files remain visible to Git
so that they can be archived or moved to a deliberate output directory.

The ledger is bidirectional on purpose. A new tracked root file fails until it is named here with a
reason; a ledger entry whose file has been deleted or moved fails too. A one-directional check
("every tracked root file is in the ledger") would let the second case rot silently, and the second
case is how a ledger stops describing the tree it claims to describe.

Run with:  python -m unittest tools.test_repository_root_inventory
"""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Every file tracked at the repository root, and why it is allowed to be there. Adding a row is a
# deliberate act; that is the whole point of the guard.
TRACKED_ROOT_FILES = {
    ".gitattributes": "Git LFS filters and merge drivers",
    ".gitignore": "generated output and local-state exclusions",
    ".gitmodules": "submodule declarations (services/field-audio-ai)",
    ".mcp.json": "MCP server declarations for coding agents",
    ".nsight-run.sh": "NVIDIA Nsight profiling launcher",
    "AGENTS.md": "the contract coding agents read first",
    "LICENSE": "the licence",
    "README.md": "the front door",
    "build.gradle.kts": "the build",
    "field.bat": "Windows launcher",
    "field.command": "macOS Finder-double-click launcher",
    "field.ps1": "PowerShell launcher",
    "field.sh": "the POSIX sh launcher",
    "gradle.properties": "build properties",
    "gradlew": "Gradle wrapper",
    "gradlew.bat": "Gradle wrapper, Windows",
    "pyproject.toml": "the Python tool project (uv)",
    "settings.gradle.kts": "Gradle settings",
    "uv.lock": "the pinned Python dependency set",
}

# Extensions that are render output or scratch, never a root-level source of truth. `.png` is the
# one that has actually happened; the others are the same shape and cost nothing to name.
NEVER_TRACKED_AT_ROOT_SUFFIXES = (".png", ".jpg", ".jpeg", ".svg", ".mp4", ".gif", ".csv")


def tracked_root_files() -> set[str]:
    result = subprocess.run(
        ["git", "ls-files", "--full-name"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    return {line for line in result.stdout.splitlines() if line and "/" not in line}


class RepositoryRootInventoryTests(unittest.TestCase):
    def test_premise_git_answers_and_the_root_is_not_empty(self) -> None:
        """Without this, a broken `git ls-files` would make every check below vacuous."""
        tracked = tracked_root_files()
        # A floor, not a decoration: the ledger names 19 files, and a subprocess that silently
        # returned nothing would otherwise compare two sets and blame the ledger for the difference.
        self.assertGreaterEqual(
            len(tracked),
            15,
            f"git ls-files reported only {len(tracked)} tracked file(s) at the repository root, "
            "which is fewer than this repository has ever had — suspect the subprocess, not the tree",
        )

    def test_the_tracked_root_is_exactly_the_ledger(self) -> None:
        tracked = tracked_root_files()
        declared = set(TRACKED_ROOT_FILES)
        self.assertEqual(
            tracked,
            declared,
            "the repository root and this ledger have diverged.\n"
            f"  tracked but not declared: {sorted(tracked - declared)}\n"
            f"  declared but not tracked: {sorted(declared - tracked)}\n"
            "A new root file is a deliberate act: add it to TRACKED_ROOT_FILES with a reason, or "
            "move it under the directory it belongs to. A declared file that has gone is the "
            "ledger going stale, which is the failure a one-directional check would hide.",
        )

    def test_no_render_output_is_tracked_at_the_root(self) -> None:
        """Render output belongs in a deliberate artifact directory."""
        offenders = sorted(
            name
            for name in tracked_root_files()
            if name.lower().endswith(NEVER_TRACKED_AT_ROOT_SUFFIXES)
        )
        self.assertEqual(
            offenders,
            [],
            f"render output or scratch data is tracked at the repository root: {offenders}. "
            "Captures belong under public/docs/assets/ or dev_docs/agents/artifacts/.",
        )

    def test_render_output_and_root_scratch_remain_visible_to_git(self) -> None:
        """A clean status must not conceal loose captures and ad-hoc scripts."""
        for path in (
            "bridge-top-assembled.png",
            "some-new-capture.png",
            "field-css-bug.png",
            "ide.png",
            "tmp_verify_unified.py",
            "history.csv",
            "history2.py",
            "two_timescale_bandit_toy_policy.txt",
            "public/docs/assets/afield_four_graphics/00-traditional-triangle.png",
            "dev_docs/agents/artifacts/anything.png",
        ):
            with self.subTest(path=path):
                result = subprocess.run(
                    ["git", "check-ignore", "-q", path], cwd=REPO_ROOT
                )
                self.assertEqual(
                    result.returncode,
                    1,
                    f"{path} is hidden by an ignore rule or git check-ignore failed",
                )


if __name__ == "__main__":
    unittest.main()
