"""Tests for tools/check_box_idioms.py: what each rule flags, what --fix writes, and scoping."""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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
        # The landing guard exports the changed-paths listing to every check it runs, this suite
        # included; inherited, it would scope each "whole corpus" run here to the landing's files.
        with mock.patch.dict(os.environ):
            os.environ.pop("AFIELD_GUARD_CHANGED_PATHS_FILE", None)
            return idioms.main(["--root", str(self.root), *args])

    def close(self) -> None:
        self._dir.cleanup()


YAML = """\
name: example
operations:
  - create:
      box: child
      code: |
        import afield.graphics.vulkan.Renderer
        import afield.document.context.fromContext
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
        code = "import afield.graphics.vulkan.Renderer\nval renderer: Renderer = fromContext()\nval other: Renderer? = null\n"
        f = Fixture({"dev_docs/agents/demos/d/x_code_.kt": code})
        try:
            f.run("--fix")
            self.assertEqual(f.read("dev_docs/agents/demos/d/x_code_.kt"),
                             "import afield.graphics.vulkan.Renderer\nval other: Renderer? = null\n")
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
        page = ("```kotlin\nval renderer = fromContext<Renderer>()\n```\n\n```text\nval renderer: Renderer = fromContext()\n```\n"
                "```diffstatic_runner {data-lang=kotlin name=\"x\"}\nval renderer: Renderer = fromContext()\n```\n"
                "```diffstatic {data-lang=glsl}\nval renderer: Renderer = fromContext()\n```\n")
        f = Fixture({"public/docs/page.md": page})
        try:
            findings = idioms.process("public/docs/page.md", f.root, fix=False)
        finally:
            f.close()
        self.assertEqual([x.line for x in findings], [2, 9])


class ListLiteralTests(unittest.TestCase):
    PATH = "public/docs/page.md"

    def fixed(self, code: str) -> str:
        f = Fixture({self.PATH: f"```kotlin\n{code}```\n"})
        try:
            f.run("--fix")
            return f.read(self.PATH).removeprefix("```kotlin\n").removesuffix("```\n")
        finally:
            f.close()

    def test_only_hand_written_docs_pages_are_checked_yet(self):
        fence = "```kotlin\nval xs = listOf(1, 2)\n```\n"
        files = {"dev_docs/agents/demos/d/x_code_.kt": "val xs = listOf(1, 2)\n",
                 "public/docs/assets/lesson/x_code_.kt": "val xs = listOf(1, 2)\n",
                 "public/docs/reference/math.md": fence,
                 "public/docs/page.md": fence}
        f = Fixture(files)
        try:
            self.assertEqual({p: [x.rule for x in idioms.process(p, f.root, fix=False)] for p in files},
                             {**{p: [] for p in files}, "public/docs/page.md": ["list-literal"]})
        finally:
            f.close()

    def test_a_listing_copied_from_recorded_source_keeps_its_spelling(self):
        page = ("<!-- study-source:final -->\n```kotlin\nval xs = listOf(1, 2)\n```\n<!-- /study-source:final -->\n"
                "```kotlin\nval ys = listOf(3, 4)\n```\n")
        f = Fixture({self.PATH: page})
        try:
            self.assertEqual([(x.line, x.rule) for x in idioms.process(self.PATH, f.root, fix=False)], [(7, "list-literal")])
        finally:
            f.close()

    def test_explicit_collection_audit_checks_and_fixes_selected_transcripts(self):
        code = "operations:\n  - create:\n      box: test\n      code: |\n        val xs = listOf(1, 2)\n"
        f = Fixture({TRANSCRIPT: code})
        try:
            self.assertEqual(f.run("--paths", TRANSCRIPT), 0)
            self.assertEqual(f.run("--collection-literals", "--paths", TRANSCRIPT), 1)
            self.assertEqual(f.run("--collection-literals", "--fix", "--paths", TRANSCRIPT), 0)
            self.assertIn("val xs = [1, 2]", f.read(TRANSCRIPT))
            self.assertEqual(f.run("--collection-literals", "--paths", TRANSCRIPT), 0)
        finally:
            f.close()

    def test_a_one_line_call_becomes_a_literal(self):
        self.assertEqual(self.fixed('val xs = listOf("a(", f(1, 2), listOf(3 to 4))\nfor (d in listOf(E, S)) { }\n'),
                         'val xs = ["a(", f(1, 2), [3 to 4]]\nfor (d in [E, S]) { }\n')

    def test_empty_typed_and_spread_calls_are_left_alone(self):
        code = "val a = listOf()\nval b = listOf<Int>(1)\nval c = listOf(*xs)\nval d = xs.listOf(1)\nval e = mutableListOf(1)\n"
        self.assertEqual(self.fixed(code), code)
        self.assertEqual(self.fixed("val p = listOf(a * b, c)\n"), "val p = [a * b, c]\n")

    def test_an_operator_operand_keeps_listOf(self):
        # `xs + [a, b]` is ambiguous between plus(T), plus(Array) and plus(Iterable).
        code = ("val c = merge.comparators + listOf(S(0, 1), S(2, 3))\nxs -= listOf(1)\nxs += listOf(1)\n"
                "val d = xs +\n    listOf(1, 2)\nval f = { x -> listOf(x) }\n")
        self.assertEqual(self.fixed(code), code)
        self.assertEqual(self.fixed("val e = listOf(1) + listOf(2)\n"), "val e = listOf(1) + listOf(2)\n")

    def test_receivers_and_inline_lambda_results_keep_their_type(self):
        code = ("val points = listOf(1 to 2, 3 to 4).map { (x, y) -> x + y }\n"
                "val points = listOf(1 to 2, 3 to 4)\n    .map { (x, y) -> x + y }\n"
                "val panels = xs.flatMap { x -> listOf(x, x) }\n"
                "val panels = run { listOf(a, b) }\n")
        self.assertEqual(self.fixed(code), code)

    def test_a_lambdas_last_expression_keeps_listOf(self):
        # A lambda's result has no expected type: `[ring, cloud]` there does not compile (flow_matching, 11-02/11-03).
        code = ("val panels = xs.flatMapIndexed { i, x ->\n    val ring = x\n    listOf(ring, x)\n}\n"
                "val q = xs.map {\n    listOf(it, it) // pair\n}\n")
        self.assertEqual(self.fixed(code), code)
        self.assertEqual(self.fixed("val r = run {\n    val p = listOf(1, 2)\n}\n"), "val r = run {\n    val p = [1, 2]\n}\n")

    def test_a_call_spanning_lines_is_a_note_not_a_rewrite(self):
        code = "```kotlin\nval xs = listOf(\n    1,\n    2,\n)\n```\n"
        f = Fixture({self.PATH: code})
        try:
            findings = idioms.process(self.PATH, f.root, fix=False)
            self.assertEqual([(x.line, x.rule, x.fixable, x.blocking) for x in findings], [(2, "list-literal", False, False)])
            f.run("--fix")
            self.assertEqual(f.read(self.PATH), code)
        finally:
            f.close()


class BoxContextTests(unittest.TestCase):
    PATH = "dev_docs/agents/demos/d/x_code_.kt"

    def test_a_lookup_becomes_thisBox(self):
        f = Fixture({self.PATH: "record(Box.context.get()!!, 3)\n"})
        try:
            self.assertEqual(f.run("--fix"), 0)
            self.assertEqual(f.read(self.PATH), "record(thisBox, 3)\n")
        finally:
            f.close()

    def test_a_qualified_lookup_loses_its_qualifier(self):
        f = Fixture({self.PATH: "afield.document.graph.Box.context.get()!!.display(\"x\", \"y\")\n"})
        try:
            self.assertEqual(f.run("--fix"), 0)
            self.assertEqual(f.read(self.PATH), "thisBox.display(\"x\", \"y\")\n")
        finally:
            f.close()

    def test_a_guarded_lookup_alias_becomes_a_thisBox_alias(self):
        code = 'val owner = afield.document.graph.Box.context.get() ?: throw IllegalStateException("Run in a Field box") // tools\n'
        f = Fixture({self.PATH: code})
        try:
            self.assertEqual(f.run("--fix"), 0)
            self.assertEqual(f.read(self.PATH), "val owner = thisBox // tools\n")
        finally:
            f.close()

    def test_a_thisBox_alias_is_a_note_and_is_left_alone(self):
        # A smell, but not always wrong: the revision pass or a person decides.
        code = "val historyOwner = thisBox\nhistoryOwner.frame\n"
        f = Fixture({self.PATH: code})
        try:
            findings = idioms.process(self.PATH, f.root, fix=False)
            self.assertEqual([(x.rule, x.blocking) for x in findings], [("box-alias", False)])
            self.assertEqual(f.run("--fix"), 0)
            self.assertEqual(f.read(self.PATH), code)
        finally:
            f.close()


MIRROR = """\
# Field Document: d

```edn [field-box id="b" role="properties"]
{"blockProperties" [{"name" "code" "language" "kotlin" "path" "b_code_.kt" "lines" 2 "characters" 49 "sha256" "stale"}]}
```

```kotlin [field-property box="b" name="code" path="b_code_.kt"]
val renderer: Renderer = fromContext()
renderer.x
```
"""


class DemoTests(unittest.TestCase):
    SIDECAR = "dev_docs/agents/demos/d/b_code_.kt"
    MIRROR = "dev_docs/agents/demos/d/document.md"

    def test_fixing_a_sidecar_fixes_its_mirror_and_refreshes_the_digest(self):
        import hashlib
        f = Fixture({self.SIDECAR: "val renderer: Renderer = fromContext()\nrenderer.x\n", self.MIRROR: MIRROR})
        try:
            self.assertEqual(f.run("--fix", "--paths", self.SIDECAR), 0)
            self.assertEqual(f.read(self.SIDECAR), "renderer.x\n")
            mirror = f.read(self.MIRROR)
            self.assertIn("```kotlin [field-property box=\"b\" name=\"code\" path=\"b_code_.kt\"]\nrenderer.x\n```", mirror)
            digest = hashlib.sha256(b"renderer.x\n").hexdigest()
            self.assertIn(f'"path" "b_code_.kt" "lines" 1 "characters" 11 "sha256" "{digest}"', mirror)
        finally:
            f.close()


class ScopeTests(unittest.TestCase):
    def test_generated_bundles_and_files_outside_the_corpus_are_ignored(self):
        self.assertFalse(idioms.in_corpus("public/docs/assets/agent_transcripts/x/a_code_.kt"))
        self.assertFalse(idioms.in_corpus("src/jvmMain/kotlin/afield/app/server/Server.kt"))
        self.assertTrue(idioms.in_corpus("public/docs/assets/canonical_graphics/a_code_.kt"))
        self.assertTrue(idioms.in_corpus(TRANSCRIPT))

    def test_demo_build_scripts_and_mirrors_count_but_history_does_not(self):
        self.assertTrue(idioms.in_corpus("dev_docs/agents/demos/d/build.yaml"))
        self.assertTrue(idioms.in_corpus("dev_docs/agents/demos/d/verify.yaml"))
        self.assertTrue(idioms.in_corpus("dev_docs/agents/demos/d/document.md"))
        self.assertFalse(idioms.in_corpus("dev_docs/agents/demos/d/operations.yaml"))
        self.assertFalse(idioms.in_corpus("dev_docs/agents/demos/d/README.md"))
        self.assertFalse(idioms.in_corpus("dev_docs/agents/demos/d/backups/b_code__backup_00000.kt"))

    def test_an_inherited_git_dir_does_not_replace_the_root(self):
        # A hook's environment can point git at the real repository instead of the fixture.
        f = Fixture({"dev_docs/agents/demos/d/x_code_.kt": "record(Box.context.get()!!)\n"})
        try:
            with mock.patch.dict(os.environ, {"GIT_DIR": str(idioms.REPO_ROOT / ".git"),
                                              "GIT_WORK_TREE": str(idioms.REPO_ROOT)}):
                self.assertEqual(idioms.corpus_paths(f.root), ["dev_docs/agents/demos/d/x_code_.kt"])
        finally:
            f.close()

    def test_paths_limit_the_check_to_the_changed_files(self):
        f = Fixture({TRANSCRIPT: YAML, "dev_docs/agents/demos/d/clean_code_.kt": "val a = 1\n"})
        try:
            self.assertEqual(f.run("--paths", "dev_docs/agents/demos/d/clean_code_.kt", "deleted.kt"), 0)
            self.assertEqual(f.run(), 1)
        finally:
            f.close()


class HelperAdoptionTests(unittest.TestCase):
    """The approved-helper rules (dev_docs/agents/box_code_helpers/ADOPTION.md)."""

    PATH = "dev_docs/agents/demos/d/x_code_.kt"

    def run_on(self, code: str, path: str | None = None) -> tuple[list[tuple[int, str, bool, bool]], str]:
        path = path or self.PATH
        f = Fixture({path: code})
        try:
            findings = idioms.process(path, f.root, fix=False)
            f.run("--fix")
            return [(x.line, x.rule, x.fixable, x.blocking) for x in findings], f.read(path)
        finally:
            f.close()

    def test_key_pairs(self):
        found, fixed = self.run_on(
            'val a = scope.Key(mutableMapOf<String, Any?>("amount" to quiet))\n'
            'kfs.saveKey(d, kfs.Key(mutableMapOf("r" to f(1, 2), "g" to 2)), "look")\n')
        self.assertEqual([r for _, r, _, _ in found], ["key-pairs", "key-pairs"])
        self.assertEqual(fixed, 'val a = scope.Key("amount" to quiet)\n'
                                'kfs.saveKey(d, kfs.Key("r" to f(1, 2), "g" to 2), "look")\n')

    def test_key_pairs_leaves_empty_spread_and_other_maps_alone(self):
        code = ("val a = scope.Key(mutableMapOf())\nval b = scope.Key(mutableMapOf(*pairs))\n"
                "val c = scope.Key(existing)\nval d = MyKey(mutableMapOf(\"a\" to 1))\n"
                "val e = scope.Key(mutableMapOf(\"a\" to 1), extra)\n")
        self.assertEqual(self.run_on(code), ([], code))

    def test_fixed_format(self):
        found, fixed = self.run_on(
            'println("${"%.3f".format(x)} and ${"%.1f".format(a / b)}")\n'
            'val s = "%.2f".format(stats.mean()) + "%.0f".format((n))\n'
            'val t = "%.4f".format(xs.maxOf { it.v }) + "%.2f".format(-y) + "%.2f".format("%.1f".format(1.0).length)\n')
        self.assertEqual([r for _, r, _, _ in found], ["fixed-format"] * 3)
        self.assertEqual(fixed,
                         'println("${x.f3} and ${(a / b).f1}")\n'
                         'val s = stats.mean().f2 + n.f0\n'
                         'val t = xs.maxOf { it.v }.f4 + (-y).f2 + (1.0.f1.length).f2\n')

    def test_fixed_format_leaves_other_formats_alone(self):
        code = ('val a = "%6.2f".format(x)\nval b = "%.2f%%".format(x)\nval c = "%.2f, %.2f".format(x, y)\n'
                'val d = "%.2f".format(Locale.ROOT, x)\nval e = "%,d".format(n)\nval f = "\\"%.2f\\".format(x)"\n')
        self.assertEqual(self.run_on(code), ([], code))

    def test_starting_value_cast(self):
        found, fixed = self.run_on("(q as NeedsStartingValue<Double>).startAt(0.0)\n")
        self.assertEqual(found, [(1, "starting-value-cast", True, True)])
        self.assertEqual(fixed, "q.startAt(0.0)\n")
        code = "(q as Other<Double>).startAt(0.0)\nq.startAt(0.0)\n"
        self.assertEqual(self.run_on(code), ([], code))

    def test_uint_push_lambda_in_a_module_box(self):
        found, fixed = self.run_on(
            "val m = moduleHere { }\nm.count = { N.toUInt() }\nm.seed = { (seed + 1).toUInt() }\n")
        self.assertEqual([(l, r, x) for l, r, x, _ in found], [(2, "uint-push-lambda", True), (3, "uint-push-lambda", True)])
        self.assertEqual(fixed, "val m = moduleHere { }\nm.count = { N }\nm.seed = { seed + 1 }\n")

    def test_uint_push_lambda_elsewhere_is_a_note(self):
        code = "state.count = { N.toUInt() }\nval u = n.toUInt()\nm.count = { a + b.toUInt() }\n"
        self.assertEqual(self.run_on(code), ([(1, "uint-push-lambda", False, False)], code))

    def test_compute_barrier(self):
        found, fixed = self.run_on(
            'module += "b" to computeTask { _, cmd, _ ->\n    computeMemoryBarrier(cmd)\n}\n'
            'module +=\n    "c" to\n        computeTask { _, cmd, _ ->\n'
            '            computeMemoryBarrier(cmd, null as VulkanBuffer?)\n'
            '            graphicsMemoryBarrier(cmd, null as VulkanBuffer?)\n        }\n')
        self.assertEqual([(l, r) for l, r, _, _ in found], [(1, "compute-barrier"), (6, "compute-barrier")])
        self.assertEqual(fixed, 'module += "b" to computeBarrier()\n'
                                'module +=\n    "c" to\n        computeBarrier(graphics = true)\n')

    def test_compute_barrier_keeps_tasks_that_do_more(self):
        code = ("computeTask { _, cmd, _ ->\n    computeMemoryBarrier(cmd)\n    tmp.zero(cmd)\n}\n"
                "computeTask { _, cmd, _ ->\n    graphicsMemoryBarrier(cmd)\n}\n"
                "computeTask { _, cmd, _ ->\n    computeMemoryBarrier(cmd, buf)\n}\n")
        self.assertEqual(self.run_on(code), ([], code))

    def test_wall_projection_is_a_failure_without_a_rewrite(self):
        code = 'm.push["worldToClip"] = afield.math.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)\n'
        self.assertEqual(self.run_on(code), ([(1, "wall-projection", False, True)], code))
        clean = 'm.push["worldToClip"] = program.wall.worldToClip()\nm.push["view"] = Matrix4f().ortho(0f, 1f, 0f, 1f, -1f, 1f)\n'
        self.assertEqual(self.run_on(clean), ([], clean))

    def test_fullscreen_triangle_is_a_note(self):
        code = "val t = Mesh(renderer, view)\nt.resize(3, 0, view)\nquad.resize(4, 6, view)\n"
        self.assertEqual(self.run_on(code), ([(2, "fullscreen-triangle", False, False)], code))

    def test_recorded_listings_are_not_rewritten(self):
        page = ("<!-- demo-source:a -->\n```kotlin\nval s = \"%.2f\".format(x)\n```\n<!-- /demo-source:a -->\n"
                "```kotlin\nval t = \"%.2f\".format(x)\n```\n")
        found, fixed = self.run_on(page, "public/docs/page.md")
        self.assertEqual(found, [(7, "fixed-format", True, True)])
        self.assertIn('val s = "%.2f".format(x)', fixed)
        self.assertIn("val t = x.f2", fixed)


if __name__ == "__main__":
    unittest.main()
