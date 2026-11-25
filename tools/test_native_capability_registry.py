"""Tests for tools/check_native_capability_registry.py, including a run over the real tree."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))

import check_native_capability_registry as checker  # noqa: E402

REGISTRY = """
object NativeLibraries {
    init {
        register("csound") { afield.audio.csound.CsoundLibrary }
    }
}
"""


class FixtureTree(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        self.write(str(checker.REGISTRY.relative_to(checker.KOTLIN)), REGISTRY)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, rel: str, text: str) -> None:
        path = self.repo / checker.KOTLIN / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def test_a_registered_locator_passes(self) -> None:
        self.write("afield/audio/csound/Csound.kt",
                   'object CsoundLibrary : NativeCapability {\n'
                   '    val p = System.getProperty("afield.csound.library")\n}\n')
        self.assertEqual(checker.violations(self.repo), [])

    def test_a_locator_without_a_capability_is_flagged(self) -> None:
        self.write("afield/audio/foo/Foo.kt",
                   'object FooLibrary {\n    val p = System.getenv("AFIELD_FOO_LIBRARY")\n}\n')
        problems = checker.violations(self.repo)
        self.assertEqual(len(problems), 1)
        self.assertIn("Foo.kt:2", problems[0])
        self.assertIn("declares no object implementing NativeCapability", problems[0])

    def test_an_unregistered_capability_is_flagged(self) -> None:
        self.write("afield/lang/bar/Bar.kt",
                   'object BarLibrary : NativeCapability {\n'
                   '    val p = System.getProperty("afield.bar.executable")\n}\n')
        problems = checker.violations(self.repo)
        self.assertEqual(len(problems), 1)
        self.assertIn("BarLibrary implements NativeCapability but is not registered", problems[0])

    def test_exempt_libraries_and_mentions_in_strings_are_ignored(self) -> None:
        self.write("afield/physics/Jolt.kt", 'val p = System.getProperty("afield.joltjni.library")\n')
        self.write("afield/examples/Uses.kt", 'fun f() = println("point AFIELD_CERES_LIBRARY at a build")\n')
        self.assertEqual(checker.violations(self.repo), [])


class AgainstTheRealTree(unittest.TestCase):
    def test_every_locator_in_the_repo_is_registered(self) -> None:
        result = subprocess.run(
            [sys.executable, str(TOOLS / "check_native_capability_registry.py")],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
