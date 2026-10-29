"""Runs the offline viewer's Node regressions and checks the EDN reader knows Field's tags.

`tools/test_offline_edn_parser.js` exercises `src/commonMain/resources/edn-parser.js` against
Field's EDN dialect and every shipped document; `tools/test_offline_viewer_render.js` exercises the
viewer's Markdown and Excalidraw renderers. The tag check fails when `saving.kt` registers a tagged
type the offline reader does not handle, which is how `#afield/file` sidecars went unread.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SAVING = REPO / "src/jvmMain/kotlin/afield/document/persistence/saving.kt"
PARSER = REPO / "src/commonMain/resources/edn-parser.js"


def runnable_node() -> tuple[str | None, str]:
    """The first `node` that starts, and why the others did not.

    PATH's node can be present and still unable to start (a Homebrew node whose
    dylib was upgraded out from under it), so try it, then any nvm install."""
    candidates = [shutil.which("node")]
    candidates += [str(p) for p in sorted((Path.home() / ".nvm/versions/node").glob("v*/bin/node"), reverse=True)]
    failures = []
    for node in dict.fromkeys(c for c in candidates if c):
        try:
            probe = subprocess.run([node, "--version"], capture_output=True, text=True, timeout=30)
        except OSError as e:
            failures.append(f"{node}: {e}")
            continue
        if probe.returncode == 0:
            return node, ""
        failures.append(f"{node}: {(probe.stderr or probe.stdout).strip().splitlines()[:1]}")
    return None, "; ".join(failures) or "node is not installed"


class OfflineViewerJsTest(unittest.TestCase):
    def run_node(self, script: str) -> None:
        node, why = runnable_node()
        if node is None:
            self.skipTest(f"no runnable node: {why}")
        proc = subprocess.run([node, str(REPO / "tools" / script)], cwd=REPO, capture_output=True, text=True, timeout=300)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def test_edn_parser(self) -> None:
        self.run_node("test_offline_edn_parser.js")

    def test_viewer_renderers(self) -> None:
        self.run_node("test_offline_viewer_render.js")

    def test_published_output_manifest(self) -> None:
        self.run_node("test_offline_outputs.js")

    def test_parser_handles_every_tag_saving_registers(self) -> None:
        tags = set(re.findall(r'register(?:Edn|StringPayload)\([^,]+,\s*"([^"]+)"', SAVING.read_text()))
        self.assertTrue(tags, "found no registered tags in saving.kt")
        parser = PARSER.read_text()
        for tag in sorted(tags):
            self.assertIn(f"case '{tag}':", parser, f"edn-parser.js applyTag does not handle #{tag}")

    def test_gallery_index_summarises_documents(self) -> None:
        sys.path.insert(0, str(REPO / "tools"))
        import sync_offline_assets  # type: ignore[import-not-found]

        scene = '{\\"type\\":\\"excalidraw\\",\\"elements\\":[{\\"id\\":\\"a\\",\\"isDeleted\\":false},{\\"id\\":\\"b\\",\\"isDeleted\\":true}]}'
        edn = (
            '{"version" 2 "boxes" [{"id" "b1" "properties" {"name" "one" "notes" #afield/file-inline {"path" "b1_notes_.md" "inline" "# hi"}}} '
            '{"id" "b2" "properties" {"name" "gone" "_tombstone" true}} '
            '{"id" "root" "properties" {"drawing" "' + scene + '"}}]}'
        )
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            (target / "assets" / "docs" / "demo" / "backups").mkdir(parents=True)
            (target / "assets" / "docs" / "demo" / "metadata.edn").write_text(edn)
            (target / "assets" / "docs" / "demo" / "backups" / "metadata.edn").write_text(edn)
            (target / "assets" / "agent_transcripts_gallery").mkdir()
            (target / "assets" / "agent_transcripts_gallery" / "demo_still.png").write_bytes(b"")
            self.assertEqual(sync_offline_assets.write_gallery_index(target), 1)
            import json
            doc = json.loads((target / "offline-gallery.json").read_text())["documents"][0]
            self.assertEqual(doc, {
                "name": "demo", "file": "assets/docs/demo/metadata.edn", "boxes": 1, "notes": 1, "drawing": 1,
                "image": "assets/agent_transcripts_gallery/demo_still.png",
            })


if __name__ == "__main__":
    unittest.main()
