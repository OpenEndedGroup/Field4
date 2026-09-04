#!/usr/bin/env python3
"""Pin `loc_history.classify` — the part of the LOC count that rots silently.

The counting itself is arithmetic and cannot drift. The classification is a list of path
substrings, and every one of them breaks *quietly* when a directory moves: a renamed vendor
drop starts counting as ours, and the headline number grows by tens of thousands of lines
with nothing to show for it. Vendored code is a third of the raw `.kt`/`.js`/`.html` line
count in this repo, so the rules are not a rounding detail.

So every rule is checked against **every path the repo has ever had**, not against HEAD. The
tool walks history, so a rule may legitimately match nothing in the current tree and still be
load-bearing: `public/min/vs/` (a Monaco copy) and the three.js editor's `examples/` tree were
both deleted along the way, and dropping their rules would silently inflate 2025-2026.
"""

from __future__ import annotations

import functools
import subprocess
import tempfile
import unittest
from unittest import mock
from pathlib import Path

from loc_history import (COUNTED_EXT, GENERATED_DIRS, LANGS, SUBMODULES,
                         VENDORED_DIRS, VENDORED_NAMES, classify, language)

REPO_ROOT = Path(__file__).resolve().parent.parent
IGNORED_UNINITIALIZED_SUBMODULE_ENTRIES = {".DS_Store"}


def submodule_checkout_state(checkout: Path) -> str:
    """Classify an optional submodule checkout as absent, present, or partially broken."""
    if (checkout / ".git").exists():
        return "present"
    if not checkout.exists():
        return "absent"
    meaningful = [
        entry for entry in checkout.iterdir()
        if entry.name not in IGNORED_UNINITIALIZED_SUBMODULE_ENTRIES
    ]
    return "broken" if meaningful else "absent"


@functools.cache
def paths_ever() -> frozenset[str]:
    """Every counted-language path added anywhere in Field4's history.

    Two flags carry weight here:

    * `--no-renames` — with rename detection on, a file moved into place is reported as `R`
      and `--diff-filter=A` never sees it, so paths that arrived by rename go missing.
    * `--diff-merges=first-parent` — the visionOS app was brought in by a *subtree merge*
      (`Add 'native/field-visionos/' from commit ...`), and plain `git log --name-only`
      shows no diff for a merge commit. Without this, 7 of the 36 Swift files are invisible.
    """
    out = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "log", "--all", "--pretty=format:",
         "--name-only", "--diff-filter=A", "--no-renames",
         "--diff-merges=first-parent"],
        capture_output=True, check=True,
    ).stdout.decode(errors="replace")
    return frozenset(
        line for line in out.splitlines()
        if line.rsplit(".", 1)[-1].lower() in ("kt", "js", "html", "swift", "py")
    )


VENDORED = (
    "src/jsMain/resources/monaco/min/vs/language/typescript/tsWorker.js",
    "src/commonMain/resources/extensions/remote_3editor/examples/jsm/controls/OrbitControls.js",
    "src/commonMain/resources/extensions/remote_3editor/js/Editor.js",
    "src/commonMain/resources/extensions/remote_ar/vendor/super-three/0.184.0/three.module.js",
    "src/commonMain/resources/extensions/remote_ar/vendor/aframe/1.8.0/aframe-v1.8.0.js",
    "src/commonMain/resources/extensions/remote_ar/ar/tess2.js",
    "src/commonMain/resources/extensions/remote_3editor/field/acorn.js",
    "src/jsMain/resources/plotly-2.24.1.min.js",
    "public/docs/assets/codemirror.js",
    "public/docs/assets/mode/clike/clike.js",
)

GENERATED = (
    "src/generatedLinalg/kotlin/afield/stdlib/linalg/Vectors.kt",
    "public/docs/assets/canonical_graphics/05adaae8-82c5-4273-b3b2-8db72b4a3435_code_.kt",
    "templates/remote/_code_.kt",
)

OURS = (
    "src/jvmMain/kotlin/afield/stdlib/Functions.kt",
    "src/jsMain/kotlin/afield/Main.kt",
    "src/jvmTest/kotlin/afield/ArraySparklineVisualizerTest.kt",
    "src/commonMain/resources/welcome.html",
    "src/commonMain/resources/extensions/remote_ar/field/field.js",
    "src/commonMain/resources/extensions/remote_ar/ar/fline.js",
    "public/docs/assets/code-diff-player/code-diff-player.js",
    "tools/webosc/static/touch.js",
    "electron/main.js",
    # Swift: the visionOS head and its encoder, both hand-written, no vendored Swift.
    "native/field-visionos/Field4Vision/RTT/RTTReceiver.swift",
    "native/field-encode/Sources/field-encode/Encoder.swift",
    # Python: Field4's own tooling. The submodule's Python is covered by
    # SubmoduleTest, because it does not appear in Field4's own history at all.
    "tools/run_field_tests.py",
    "tools/field_backdoor.py",
)


class ClassifyTest(unittest.TestCase):
    def test_vendored(self) -> None:
        for path in VENDORED:
            self.assertEqual(classify(path), "vendored", path)

    def test_generated(self) -> None:
        for path in GENERATED:
            self.assertEqual(classify(path), "generated", path)

    def test_ours(self) -> None:
        for path in OURS:
            self.assertEqual(classify(path), "ours", path)

    def test_our_code_is_not_shadowed_by_a_vendor_rule(self) -> None:
        """`remote_ar/` holds our code beside a `vendor/` drop — the split must stay exact."""
        base = "src/commonMain/resources/extensions/remote_ar"
        self.assertEqual(classify(f"{base}/field/runners.js"), "ours")
        self.assertEqual(classify(f"{base}/vendor/tone/Tone.js"), "vendored")


class RulesStillMatchHistoryTest(unittest.TestCase):
    """A rule matching nothing in *any* commit is dead weight or a typo."""

    def test_sample_paths_are_real(self) -> None:
        for path in VENDORED + GENERATED + OURS:
            self.assertIn(path, paths_ever(), f"{path} was never in the repo — fix the sample")

    def test_every_dir_rule_matches_something(self) -> None:
        ever = paths_ever()
        for frag in VENDORED_DIRS:
            self.assertTrue(any(frag in p for p in ever), f"no file ever matched {frag!r}")
        for prefix in GENERATED_DIRS:
            self.assertTrue(any(p.startswith(prefix) for p in ever),
                            f"no file ever matched {prefix!r}")

    def test_every_name_rule_matches_something(self) -> None:
        names = {p.rsplit("/", 1)[-1] for p in paths_ever()}
        for name in VENDORED_NAMES:
            self.assertIn(name, names, f"no file ever named {name} — drop it from VENDORED_NAMES")


class SubmoduleTest(unittest.TestCase):
    """Submodule files are classified under their prefixed path, by the same rules."""

    def test_submodule_python_is_ours(self) -> None:
        self.assertEqual(
            classify("services/field-audio-ai/src/field_audio/jobs.py"), "ours")
        self.assertEqual(language("services/field-audio-ai/src/field_audio/jobs.py"), "py")

    def test_a_vendor_drop_inside_a_submodule_would_still_be_caught(self) -> None:
        self.assertEqual(
            classify("services/field-audio-ai/web/vendor/chart.js"), "vendored")

    def test_declared_submodules_are_gitlinks(self) -> None:
        """A stale SUBMODULES entry silently contributes nothing — enforce it at HEAD."""
        for path in SUBMODULES:
            line = subprocess.run(
                ["git", "-C", str(REPO_ROOT), "ls-files", "--stage", "--", path],
                capture_output=True, check=True, text=True,
            ).stdout.strip()
            self.assertTrue(line, f"{path} is not tracked at HEAD")
            self.assertEqual(line.split(maxsplit=1)[0], "160000", f"{path} is not a gitlink")

    def test_populated_submodules_are_real_checkouts(self) -> None:
        """An untouched fresh-worktree submodule is optional; partial content is not."""
        absent: list[str] = []
        for path, checkout in SUBMODULES.items():
            state = submodule_checkout_state(checkout)
            self.assertNotEqual(
                state,
                "broken",
                f"{path} is partially populated at {checkout} but has no .git metadata",
            )
            if state == "absent":
                absent.append(path)
        if absent:
            self.skipTest(
                "optional submodule checkout absent: " + ", ".join(absent) +
                " (initialize only for Field Audio integration work)"
            )

    def test_submodule_contributes_counted_files(self) -> None:
        """If the pin holds no counted language the band would be flat at zero."""
        absent: list[str] = []
        for path, checkout in SUBMODULES.items():
            state = submodule_checkout_state(checkout)
            self.assertNotEqual(
                state,
                "broken",
                f"{path} is partially populated at {checkout} but has no .git metadata",
            )
            if state == "absent":
                absent.append(path)
                continue
            tracked = subprocess.run(
                ["git", "-C", str(checkout), "ls-files"],
                capture_output=True, check=True).stdout.decode().splitlines()
            hits = [f for f in tracked if COUNTED_EXT.search(f)]
            self.assertTrue(hits, f"{path} has no .{{{','.join(LANGS)}}} files")
        if absent:
            self.skipTest(
                "optional submodule checkout absent: " + ", ".join(absent) +
                " (count remains pinned by the Field4 gitlink)"
            )

    def test_checkout_state_distinguishes_absent_from_broken(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(submodule_checkout_state(root / "missing"), "absent")

            checkout = root / "checkout"
            checkout.mkdir()
            self.assertEqual(submodule_checkout_state(checkout), "absent")
            (checkout / ".DS_Store").write_bytes(b"finder metadata")
            self.assertEqual(submodule_checkout_state(checkout), "absent")

            (checkout / "README.md").write_text("partial checkout", encoding="utf-8")
            self.assertEqual(submodule_checkout_state(checkout), "broken")
            (checkout / ".git").write_text("gitdir: elsewhere", encoding="utf-8")
            self.assertEqual(submodule_checkout_state(checkout), "present")


class SubmoduleCheckoutContractTest(unittest.TestCase):
    def run_checkout_test(self, checkout: Path) -> unittest.TestResult:
        result = unittest.TestResult()
        with mock.patch.dict(SUBMODULES, {"services/example": checkout}, clear=True):
            SubmoduleTest("test_populated_submodules_are_real_checkouts").run(result)
        return result

    def test_uninitialized_fresh_worktree_checkout_is_skipped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory) / "services" / "example"
            checkout.mkdir(parents=True)
            result = self.run_checkout_test(checkout)
        self.assertEqual(result.failures, [])
        self.assertEqual(result.errors, [])
        self.assertEqual(len(result.skipped), 1)
        self.assertIn("optional submodule checkout absent", result.skipped[0][1])

    def test_partially_populated_checkout_still_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory) / "services" / "example"
            checkout.mkdir(parents=True)
            (checkout / "README.md").write_text("partial", encoding="utf-8")
            result = self.run_checkout_test(checkout)
        self.assertEqual(result.errors, [])
        self.assertEqual(result.skipped, [])
        self.assertEqual(len(result.failures), 1)
        self.assertIn("partially populated", result.failures[0][1])


class LanguageSetTest(unittest.TestCase):
    def test_counted_extensions_match_langs(self) -> None:
        for lang in LANGS:
            self.assertTrue(COUNTED_EXT.search(f"x.{lang}"), lang)
            self.assertEqual(language(f"x.{lang}"), lang)

    def test_uncounted_languages_stay_out(self) -> None:
        """native/ also holds C++ and headers; they are not part of this count."""
        for path in ("native/field-encode/Sources/x.cpp", "a/b.hpp", "a/b.h", "a/b.md"):
            self.assertIsNone(COUNTED_EXT.search(path), path)


if __name__ == "__main__":
    unittest.main()
