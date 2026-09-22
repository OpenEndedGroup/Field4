#!/usr/bin/env python3
"""Hold `check_offline_assets.py` to its three claims, and to running at all.

`tools/check_offline_assets.py` is one of nine `tools/check_*.py`. Measured 2026-09-22, it was one
of exactly **two** that no gate reached by any route: zero occurrences in `build.gradle.kts`, and —
unlike `check_doc_assets`, `check_doc_site_links` and `check_doc_offline_links` — no
`tools/test_*.py` beside it. That distinction is the thing worth writing down, because the other
three *look* equally unwired and are not: `tools/run_python_tests.py` discovers suites by glob
(`TOOLS_ROOT.glob("test_*.py")`), so a suite here is wired into `checkPythonTools` by existing, and
each of those three carries a class that runs its checker over the real tree. A checker with a
suite is gated; a checker with neither a Gradle task nor a suite is decoration. This file moves
`check_offline_assets` from the second group to the first.

**What the gap cost, found by writing this:** `repo_relative()` was called by the extended-face
branch and never defined anywhere in the module. The guard's only code path for reporting a
shipped Extended Iosevka raised `NameError` instead of naming the file — a guard that crashes
rather than fails, for the full life of that branch. It survived because the branch is unreachable
while the tree is clean, which is exactly the shape of defect a suite catches and a green `check`
cannot: [test_an_extended_face_is_reported_by_name] is the regression test, and it fails on the
code as it stood.

The three claims, each asserted from both sides:

1. every path in `CANONICAL_FILES` exists (a missing one is an error);
2. no path matching `GENERATED_TRACKED_PATHS` is *tracked* — those are `sync_offline_assets.py`'s
   output and re-tracking them is the vendoring regression the module was written for;
3. no Extended Iosevka face is shipped, and `iosevka.css` registers none.

Run with:  uv run --project . --group backdoor python -m unittest tools.test_check_offline_assets
"""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from tools import check_offline_assets as checker
from tools.check_offline_assets import (
    CANONICAL_FILES,
    GENERATED_TRACKED_PATHS,
    TARGET_FILES,
    repo_relative,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
CHECKER = REPO_ROOT / "tools" / "check_offline_assets.py"

# Measured 2026-09-22 on this tree. Floors rather than equalities: a new canonical asset or a new
# generated-path pattern is ordinary growth, but a *shrinking* list means someone deleted a claim
# rather than satisfying it, and that should need a deliberate edit here.
RECORDED_CANONICAL = 19
RECORDED_GENERATED_PATTERNS = 14
RECORDED_TARGET_FILES = 9


def _fake_repo(tmp: Path) -> Path:
    """A git repository with every `CANONICAL_FILES` path present and committed.

    The module resolves `REPO_ROOT` from its own `__file__` and passes it as `cwd` to
    `git ls-files`, so a fixture tree is only reachable by patching the module's globals — a
    temp directory plus `cwd=` is not enough. Patching is therefore the house form here, and the
    real-tree class below is what keeps the fixture honest.
    """
    subprocess.run(["git", "init", "-q"], cwd=tmp, check=True)
    subprocess.run(["git", "config", "user.email", "t@t"], cwd=tmp, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=tmp, check=True)
    for relative in CANONICAL_FILES:
        path = tmp / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("x", encoding="utf-8")
    (tmp / "src/jsMain/resources/woff2").mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "add", "-A"], cwd=tmp, check=True)
    subprocess.run(["git", "commit", "-qm", "fixture"], cwd=tmp, check=True)
    return tmp


class AgainstAFixtureTree(unittest.TestCase):
    """Each error branch, driven by building the condition rather than by asserting a string."""

    def run_checker(self, tmp: Path) -> int:
        with mock.patch.object(checker, "REPO_ROOT", tmp), \
                mock.patch.object(checker, "JS_RESOURCES", tmp / "src/jsMain/resources"):
            return checker.main([])

    def test_a_clean_fixture_passes(self) -> None:
        with TemporaryDirectory() as raw:
            self.assertEqual(0, self.run_checker(_fake_repo(Path(raw))))

    def test_a_missing_canonical_asset_fails(self) -> None:
        with TemporaryDirectory() as raw:
            tmp = _fake_repo(Path(raw))
            (tmp / CANONICAL_FILES[0]).unlink()
            self.assertEqual(1, self.run_checker(tmp))

    def test_a_tracked_generated_duplicate_fails(self) -> None:
        """Claim 2: the vendoring regression this module exists to prevent."""
        with TemporaryDirectory() as raw:
            tmp = _fake_repo(Path(raw))
            duplicate = tmp / "public" / "offline.html"
            duplicate.parent.mkdir(parents=True, exist_ok=True)
            duplicate.write_text("x", encoding="utf-8")
            subprocess.run(["git", "add", "-A"], cwd=tmp, check=True)
            subprocess.run(["git", "commit", "-qm", "re-track"], cwd=tmp, check=True)
            self.assertEqual(1, self.run_checker(tmp))

    def test_an_untracked_generated_duplicate_is_fine(self) -> None:
        """The other side of claim 2: `sync_offline_assets.py` writes these, so presence is normal."""
        with TemporaryDirectory() as raw:
            tmp = _fake_repo(Path(raw))
            duplicate = tmp / "public" / "offline.html"
            duplicate.parent.mkdir(parents=True, exist_ok=True)
            duplicate.write_text("x", encoding="utf-8")
            self.assertEqual(0, self.run_checker(tmp))

    def test_an_extended_face_is_reported_by_name(self) -> None:
        """The `NameError` regression: this branch crashed instead of failing until 2026-09-22."""
        with TemporaryDirectory() as raw:
            tmp = _fake_repo(Path(raw))
            face = tmp / "src/jsMain/resources/woff2/iosevka-extended.woff2"
            face.write_bytes(b"x")
            self.assertEqual(1, self.run_checker(tmp))

    def test_iosevka_css_registering_an_extended_face_fails(self) -> None:
        with TemporaryDirectory() as raw:
            tmp = _fake_repo(Path(raw))
            (tmp / "src/jsMain/resources/iosevka.css").write_text(
                "@font-face { font-family: 'Iosevka Extended'; }", encoding="utf-8"
            )
            self.assertEqual(1, self.run_checker(tmp))

    def test_a_missing_target_file_fails(self) -> None:
        """`--target` is the generated-distribution half, and is checked for every TARGET_FILES entry."""
        with TemporaryDirectory() as raw:
            tmp = _fake_repo(Path(raw))
            with mock.patch.object(checker, "REPO_ROOT", tmp), \
                    mock.patch.object(checker, "JS_RESOURCES", tmp / "src/jsMain/resources"):
                self.assertEqual(1, checker.main(["--target", "dist/nothing-here"]))


class RepoRelative(unittest.TestCase):
    def test_a_path_inside_the_repository_is_relative(self) -> None:
        self.assertEqual(
            "tools/check_offline_assets.py", repo_relative(REPO_ROOT / "tools/check_offline_assets.py")
        )

    def test_a_path_outside_the_repository_is_returned_whole(self) -> None:
        """`relative_to` raises rather than climbing, and an error message must not become a crash."""
        self.assertEqual("/etc/hosts", repo_relative(Path("/etc/hosts")))


class AgainstTheRealTree(unittest.TestCase):
    """End to end on this repository, which is the population the claims are made over."""

    def test_exit_zero_and_the_success_line(self) -> None:
        result = subprocess.run(
            [sys.executable, str(CHECKER)],
            capture_output=True, text=True, cwd=str(REPO_ROOT),
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Offline asset check passed", result.stdout)

    def test_every_canonical_asset_really_is_on_disk(self) -> None:
        """Stated directly as well as through the checker, so a broken scan cannot read as a pass."""
        missing = [rel for rel in CANONICAL_FILES if not (REPO_ROOT / rel).is_file()]
        self.assertEqual([], missing)

    def test_no_generated_duplicate_is_tracked(self) -> None:
        listing = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "ls-files", "--", *GENERATED_TRACKED_PATHS],
            capture_output=True, text=True, check=True,
        ).stdout
        self.assertEqual([], [line for line in listing.split("\n") if line.strip()])

    def test_the_recorded_populations_are_floors(self) -> None:
        self.assertGreaterEqual(len(CANONICAL_FILES), RECORDED_CANONICAL)
        self.assertGreaterEqual(len(GENERATED_TRACKED_PATHS), RECORDED_GENERATED_PATTERNS)
        self.assertGreaterEqual(len(TARGET_FILES), RECORDED_TARGET_FILES)


if __name__ == "__main__":
    unittest.main()
