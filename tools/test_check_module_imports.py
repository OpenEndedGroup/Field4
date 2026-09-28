"""Tests for tools/check_module_imports.py, including a run over the real tree."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))

import check_module_imports as checker  # noqa: E402

KOTLIN = """
@file:OptIn(ExperimentalJsExport::class)
package afield

@JsExport
fun send(name: String) {}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun sendWithReply(name: String, cb: String) {}

fun notExported() {}
"""


class FixtureTree(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        (self.repo / checker.KOTLIN_JS / "afield").mkdir(parents=True)
        (self.repo / checker.KOTLIN_JS / "afield" / "Messages.kt").write_text(KOTLIN)
        self.res = self.repo / checker.RESOURCES
        self.res.mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, name: str, text: str) -> None:
        path = self.res / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text))

    def problems(self) -> list[str]:
        return checker.violations(self.repo)[0]

    def test_exports_are_read_from_kotlin_source(self) -> None:
        self.assertEqual(checker.kotlin_js_exports(self.repo), {"send", "sendWithReply"})

    def test_bare_call_in_a_module_is_flagged_with_the_fix(self) -> None:
        self.write("a.js", 'import { x } from "./b.js"\nsend("hello")\n')
        self.write("b.js", "export const x = 1\n")
        problems = self.problems()
        self.assertEqual(len(problems), 1)
        self.assertIn("a.js:2: bare `send(`", problems[0])
        self.assertIn('import { send } from "./afield.mjs"', problems[0])

    def test_an_imported_name_is_fine(self) -> None:
        self.write("a.js", 'import { send, sendWithReply as swr } from "./afield.mjs"\nsend("x"); swr("y")\n')
        self.assertEqual(self.problems(), [])

    def test_an_aliased_import_does_not_cover_the_original_name(self) -> None:
        self.write("a.js", 'import { sendWithReply as swr } from "./afield.mjs"\nsendWithReply("y")\n')
        self.assertEqual(len(self.problems()), 1)

    def test_a_classic_script_is_not_a_module_and_may_use_globals(self) -> None:
        self.write("legacy.js", 'window.foo = 1\nsend("hello")\n')
        self.assertEqual(self.problems(), [])

    def test_html_type_module_makes_a_file_a_module(self) -> None:
        self.write("plain.js", 'send("hello")\n')
        self.write("index.html", '<script src="plain.js" type="module"></script>\n')
        self.assertEqual(len(self.problems()), 1)

    def test_a_module_imports_its_imports_into_module_status(self) -> None:
        self.write("entry.js", 'import "./helper.js"\n')
        self.write("helper.js", 'send("hello")\n')
        self.assertEqual(len(self.problems()), 1)

    def test_inline_module_blocks_are_checked(self) -> None:
        self.write("page.html", '<p>x</p>\n<script type="module">\n  send("x")\n</script>\n')
        problems = self.problems()
        self.assertEqual(len(problems), 1)
        self.assertIn("page.html:3", problems[0])

    def test_comments_strings_methods_and_locals_are_not_calls_of_the_export(self) -> None:
        self.write(
            "a.js",
            """\
            export {}
            // send("in a comment")
            /* sendWithReply("in a block") */
            const s = "send('in a string')"
            const t = `send(${1})`
            socket.send("a method")
            function sendWithReply(a) { return a }
            sendWithReply(1)
            """,
        )
        self.assertEqual(self.problems(), [])

    def test_vendored_trees_are_skipped(self) -> None:
        self.write("vendor/lib/x.js", 'export {}\nsend("x")\n')
        self.assertEqual(self.problems(), [])

    def test_a_stale_allowance_is_itself_a_failure(self) -> None:
        original = dict(checker.ALLOWED)
        checker.ALLOWED[("gone.js", "send")] = "test"
        try:
            self.write("a.js", "export {}\n")
            self.assertEqual(checker.main(["--repo", str(self.repo)]), 1)
        finally:
            checker.ALLOWED.clear()
            checker.ALLOWED.update(original)


class AllowlistShape(unittest.TestCase):
    def test_allowances_are_exact_pairs_with_reasons(self) -> None:
        for (path, name), reason in checker.ALLOWED.items():
            with self.subTest(path=path, name=name):
                self.assertNotIn("*", path)
                self.assertNotIn("*", name)
                self.assertTrue(reason.strip())


class AgainstTheRealTree(unittest.TestCase):
    def test_the_repository_is_clean(self) -> None:
        result = subprocess.run(
            [sys.executable, str(TOOLS / "check_module_imports.py")],
            capture_output=True,
            text=True,
            cwd=TOOLS.parent,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_the_real_tree_has_modules_and_exports_to_check(self) -> None:
        """A checker that finds nothing to look at passes vacuously."""
        repo = TOOLS.parent
        units = [label for label, _ in checker.module_units(repo)]
        self.assertIn("bootstrapMonaco.js", units)
        self.assertIn("outputareas2.js", units)
        self.assertTrue(any(":inline-module@" in label for label in units))
        exports = checker.kotlin_js_exports(repo)
        self.assertTrue({"send", "sendWithReply", "monacoHandleOutput"} <= exports)


if __name__ == "__main__":
    unittest.main()
