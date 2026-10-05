"""Tests for tools/check_box_idioms.py: what each rule flags, what --fix writes, and scoping."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_box_idioms as idioms  # noqa: E402

TRANSCRIPT = "dev_docs/agents/transcripts/example.yaml"


class Fixture:
    def __init__(self, files: dict[str, str]):
        self._dir = tempfile.TemporaryDirectory()
        self.root = Path(self._dir.name)
        for rel, text in files.items():
            path = self.root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")

    def read(self, rel: str) -> str:
        return (self.root / rel).read_text(encoding="utf-8")

    def run(self, *args: str) -> int:
        return idioms.main(["--root", str(self.root), *args])

    def close(self) -> None:
        self._dir.cleanup()


YAML = """\
name: example
operations:
  - create:
      box: child
      code: |
        import afield.four.Renderer
        import afield.four.modules.fromContext
        val renderer: Renderer = fromContext()

        var module = moduleHere { }
        mesh.push.bindPushConstant("tint", { tint }) { throw IllegalStateException("Missing tint") }
        // %-
        renderer += module
  - connect:
      parent: root
"""

YAML_FIXED = """\
name: example
operations:
  - create:
      box: child
      code: |
        var module = moduleHere { }
        mesh.tint = { tint }
        //%-
        renderer += module
  - connect:
      parent: root
"""


class RuleTests(unittest.TestCase):
    def test_each_rule_is_reported_on_its_file_line(self):
        f = Fixture({TRANSCRIPT: YAML})
        try:
            findings = idioms.process(TRANSCRIPT, f.root, fix=False)
        finally:
            f.close()
        self.assertEqual(
            [(x.line, x.rule) for x in findings],
            [(8, "redundant-renderer"), (11, "bind-push-constant"), (12, "spaced-fragment-marker")],
        )

    def test_fix_rewrites_lines_and_drops_orphaned_imports(self):
        f = Fixture({TRANSCRIPT: YAML})
        try:
            self.assertEqual(f.run("--fix"), 0)
            self.assertEqual(f.read(TRANSCRIPT), YAML_FIXED)
        finally:
            f.close()

    def test_an_import_still_used_stays(self):
        code = "import afield.four.Renderer\nval renderer: Renderer = fromContext()\nval other: Renderer? = null\n"
        f = Fixture({"dev_docs/agents/demos/d/x_code_.kt": code})
        try:
            f.run("--fix")
            self.assertEqual(f.read("dev_docs/agents/demos/d/x_code_.kt"),
                             "import afield.four.Renderer\nval other: Renderer? = null\n")
        finally:
            f.close()

    def test_a_box_that_starts_coroutines_gets_a_note_not_a_rewrite(self):
        code = "val renderer: Renderer = fromContext()\nstart {\n    renderer.frame()\n}\n"
        f = Fixture({"dev_docs/agents/demos/d/x_code_.kt": code})
        try:
            self.assertEqual(f.run("--fix"), 0)
            self.assertEqual(f.read("dev_docs/agents/demos/d/x_code_.kt"), code)
        finally:
            f.close()

    def test_a_method_called_start_is_not_a_coroutine(self):
        code = "val renderer: Renderer = fromContext()\nval ring = streamer.start()\n"
        f = Fixture({"dev_docs/agents/demos/d/x_code_.kt": code})
        try:
            self.assertEqual(f.run("--fix"), 0)
            self.assertEqual(f.read("dev_docs/agents/demos/d/x_code_.kt"), "val ring = streamer.start()\n")
        finally:
            f.close()

    def test_a_differently_named_local_is_not_flagged(self):
        f = Fixture({"dev_docs/agents/demos/d/x_code_.kt": "val r: Renderer = fromContext()\n"})
        try:
            self.assertEqual(f.run(), 0)
        finally:
            f.close()

    def test_markdown_only_kotlin_fences_count(self):
        page = "```kotlin\nval renderer = fromContext<Renderer>()\n```\n\n```text\nval renderer: Renderer = fromContext()\n```\n"
        f = Fixture({"public/docs/page.md": page})
        try:
            findings = idioms.process("public/docs/page.md", f.root, fix=False)
        finally:
            f.close()
        self.assertEqual([x.line for x in findings], [2])


class ScopeTests(unittest.TestCase):
    def test_generated_bundles_and_files_outside_the_corpus_are_ignored(self):
        self.assertFalse(idioms.in_corpus("public/docs/assets/agent_transcripts/x/a_code_.kt"))
        self.assertFalse(idioms.in_corpus("src/jvmMain/kotlin/afield/Server.kt"))
        self.assertTrue(idioms.in_corpus("public/docs/assets/canonical_graphics/a_code_.kt"))
        self.assertTrue(idioms.in_corpus(TRANSCRIPT))

    def test_paths_limit_the_check_to_the_changed_files(self):
        f = Fixture({TRANSCRIPT: YAML, "dev_docs/agents/demos/d/clean_code_.kt": "val a = 1\n"})
        try:
            self.assertEqual(f.run("--paths", "dev_docs/agents/demos/d/clean_code_.kt", "deleted.kt"), 0)
            self.assertEqual(f.run(), 1)
        finally:
            f.close()


if __name__ == "__main__":
    unittest.main()
