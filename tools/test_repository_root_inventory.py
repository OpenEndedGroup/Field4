"""A bidirectional ledger of the files tracked at the repository root.

SCRATCHPAD backlog item 21 asks for "a narrow guard against accidental new tracked files at
repository root". This is it, and it is worth being precise about what it does and does not catch,
because the litter that prompted it is a *different* failure mode.

On 2026-08-25 the root held 68 untracked PNGs — `angled-section-*`, `bridge-*`, `collar-*`,
`three-mitre-*`, `monkeypaw-*`, `bounded-*`, `planar-*`, `house-*` — ad-hoc render output from the
rect/house and tessellation work, written there because a box called
`renderer.captureWindowToFile(outputPath = "bridge-top-assembled.png")` with a bare relative path,
which resolves against the JVM's working directory. None of them was ever `git add`ed. So:

  * the `/*.png` rule in `.gitignore` is what addresses those. Measured against the real 68: all 68
    are ignored, and a PNG under `public/docs/assets/` is not, because the rule is root-anchored.
  * **this module would not have caught a single one of them**, and saying otherwise would be the
    kind of claim this repository keeps having to walk back. What it catches is the next class up:
    one of them, or anything else, actually being committed to the root — where a stray file is
    least likely to be noticed and most likely to be copied by the next person looking for a
    template.

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
    ".gitignore": "the ignore set, including the root-anchored render-output rule",
    ".gitmodules": "submodule declarations (services/field-audio-ai)",
    ".mcp.json": "MCP server declarations for coding agents",
    ".nsight-run.sh": "NVIDIA Nsight profiling launcher",
    "AGENTS.md": "the contract coding agents read first",
    "LICENSE": "the licence",
    "README.md": "the front door",
    "build-offline-docs.sh": "offline docs bundle builder",
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
        # A floor, not a decoration: the ledger names 20 files, and a subprocess that silently
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
        """The class the `.gitignore` rule covers, held down against `git add -f`."""
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

    def test_the_ignore_rule_covers_root_render_output_and_nothing_deeper(self) -> None:
        """`/*.png` is root-anchored. Both halves of that matter, so both are checked."""

        def ignored(path: str) -> bool:
            return (
                subprocess.run(
                    ["git", "check-ignore", "-q", path], cwd=REPO_ROOT
                ).returncode
                == 0
            )

        # A name from the real 2026-08-25 litter, and a plausible neighbour.
        self.assertTrue(ignored("bridge-top-assembled.png"), "a root PNG is not ignored")
        self.assertTrue(ignored("some-new-capture.png"), "a root PNG is not ignored")
        # Committed captures live in subdirectories and must stay visible to git.
        for kept in (
            "public/docs/assets/afield_four_graphics/00-traditional-triangle.png",
            "dev_docs/agents/artifacts/anything.png",
        ):
            self.assertFalse(
                ignored(kept),
                f"{kept} is ignored — the rule is no longer root-anchored, and committed captures "
                "would silently stop being tracked",
            )


if __name__ == "__main__":
    unittest.main()
