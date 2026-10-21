"""The census must retain inline-only code and respect container language/context."""
import json
from pathlib import Path
import tempfile
import unittest

from tools.inventory_box_code import Inventory


class InventoryTest(unittest.TestCase):
    def census(self, files):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        for path, text in files.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
        result = Inventory(root, files)
        result.run()
        return result

    def test_recorded_function_identity_is_retained_but_starter_is_editable(self):
        files = {
            "dev_docs/agents/demos/function_conversation_example/function_source_code_.kt": "println(1)",
            "dev_docs/agents/demos/function_conversation_example/studies/function_source/r9-82456e1e48ac.kt": "println(1)",
            "dev_docs/agents/demos/other/studies/source/r2-deadbeef.kt": "println(2)",
            "dev_docs/agents/demos/function_conversation/function_source_code_.kt": "println(3)",
        }
        result = self.census(files)
        self.assertEqual([r["path"] for r in result.rows],
                         ["dev_docs/agents/demos/function_conversation/function_source_code_.kt"])
        self.assertEqual({r["path"] for r in result.excluded}, set(files) - {result.rows[0]["path"]})

    def test_inline_only_code_is_an_editable_source(self):
        metadata = '{"boxes" [{"id" "a" "properties" {"code" #afield/file-inline {"path" "a_code_.kt" "inline" "println(1)"}}}]}'
        result = self.census({"public/docs/assets/example/metadata.edn": metadata})
        self.assertEqual(len(result.rows), 1)
        self.assertEqual(result.rows[0]["role"], "source")
        self.assertEqual(result.findings[0]["kind"], "inline-only-source")

    def test_legacy_kt_file_can_be_javascript(self):
        metadata = json.dumps([dict(id="a", properties={
            "code_language": dict(first="String", second="Javascript"),
            "code": dict(first="__file__", second="a_code_.kt")})])
        result = self.census({"templates/test/metadata.json": metadata,
                              "templates/test/a_code_.kt": "SCENE.add(object)"})
        self.assertEqual(result.rows[0]["language"], "javascript")
        self.assertEqual(result.rows[0]["group"], "templates/test")

    def test_quoted_yaml_and_shader_edits_keep_language(self):
        result = self.census({"dev_docs/agents/transcripts/test.yaml": '''operations:
  - create:
      box: a
      properties:
        mainLanguage: kotlinjs
        code: "onStart { println(1) }"
  - execute: {box: a, code: 'onStart { println(1) }'}
  - edit: {box: a, property: fragment, value: '#version 450'}
'''})
        self.assertEqual([r["language"] for r in result.rows], ["kotlinjs", "kotlinjs", "glsl"])
        self.assertEqual(result.rows[0]["identical_sources"], [result.rows[1]["id"]])

    def test_metadata_main_language_is_a_fallback_for_legacy_sidecars(self):
        metadata = json.dumps([dict(id="a", properties={
            "mainLanguage": dict(first="String", second="remotejs"),
            "code": dict(first="__file__", second="a_code_.kt")})])
        result = self.census({"templates/test/metadata.json": metadata,
                              "templates/test/a_code_.kt": "const branches = new LSystem();"})
        self.assertEqual(result.rows[0]["language"], "javascript")

    def test_stage_specific_glsl_extensions_are_authored_review_sources(self):
        files = {f"dev_docs/agents/demos/test/stage.{ext}": '#version 450\nvoid main() {}'
                 for ext in ("vert", "frag", "comp", "rgen", "rchit", "rmiss")}
        result = self.census(files)
        self.assertEqual(len(result.rows), len(files))
        self.assertTrue(all(r["language"] == "glsl" and r["role"] == "source" for r in result.rows))
        self.assertEqual({r["group"] for r in result.rows}, {"dev_docs/agents/demos/test"})

    def test_document_child_inherits_language_through_parents(self):
        metadata = '{"boxes" [{"id" "child" "properties" {"parents" #{#afield/box "root"} "code" "xs = [1, 2]"}} {"id" "root" "properties" {"mainLanguage" "python"}}]}'
        result = self.census({"dev_docs/agents/demo/metadata.edn": metadata})
        self.assertEqual(result.rows[0]["language"], "python")

    def test_transcript_language_inheritance_uses_connections(self):
        result = self.census({"dev_docs/agents/transcripts/test.yaml": '''operations:
  - create: {box: root, properties: {mainLanguage: forth}}
  - create: {box: child, properties: {code: '1 2 +'}}
  - connect: {parent: root, child: child}
  - execute: {box: child, code: '3 4 +'}
'''})
        self.assertEqual([r["language"] for r in result.rows], ["forth", "forth"])

    def test_drivers_mirrors_and_generated_copies_are_not_review_sources(self):
        result = self.census({
            "public/docs/a.md": '```diffstatic_runner {data-lang=kotlin capture="ui"}\nui.pause(1)\n```\n',
            "public/docs/assets/example/document.md": '```kotlin [field-property box="a" name="code" path="a_code_.kt"]\nprintln(1)\n```\n',
            "public/docs/assets/agent_transcripts/test/a_code_.kt": "println(1)",
            "dev_docs/agents/demos/test/backups/a_code_.kt": "println(2)",
        })
        self.assertEqual({r["role"] for r in result.rows}, {"capture-driver", "document-mirror", "generated"})
        self.assertEqual(len(result.excluded), 1)

    def test_code_inside_a_docs_yaml_fence_is_included(self):
        result = self.census({"public/docs/a.md": '''```yaml
operations:
  - create: {box: a, properties: {code: 'println(1)'}}
```
'''})
        self.assertEqual(len(result.rows), 1)
        self.assertEqual(result.rows[0]["role"], "docs-fence")
        self.assertEqual(result.rows[0]["line"], 3)

    def test_archive_viewer_code_is_live_but_archived_chat_code_is_history(self):
        result = self.census({
            "public/docs/assets/ai4_chat_archive/demo/viewer_code_.kt": 'println("viewer")',
            "public/docs/assets/ai4_chat_archive/chats/old/chat_code_.kt": 'println("history")',
        })
        self.assertEqual(len(result.rows), 1)
        self.assertIn("/demo/", result.rows[0]["path"])
        self.assertEqual(result.excluded[0]["reason"], "history")

    def test_standalone_shader_sidecars_keep_their_box_and_stage(self):
        result = self.census({"dev_docs/agents/demos/test/my_box_fragment_.glsl": '#version 450\nvoid main() {}'})
        self.assertEqual(result.rows[0]["box"], "my_box")
        self.assertEqual(result.rows[0]["property"], "fragment")
        self.assertEqual(result.rows[0]["language"], "glsl")

    def test_raw_property_calls_are_review_notes(self):
        result = self.census({
            "dev_docs/agents/demos/test/raw_code_.kt": 'setPropertyRaw("value", 1)',
            "dev_docs/agents/demos/test/typed_code_.kt": 'prop<Int>(value) = 1',
        })
        flags = {r["box"]: r["flags"] for r in result.rows}
        self.assertIn("review-raw-property-access", flags["raw"])
        self.assertNotIn("review-raw-property-access", flags["typed"])


if __name__ == "__main__":
    unittest.main()
