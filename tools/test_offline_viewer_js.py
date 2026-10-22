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
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SAVING = REPO / "src/jvmMain/kotlin/afield/document/persistence/saving.kt"
PARSER = REPO / "src/commonMain/resources/edn-parser.js"


class OfflineViewerJsTest(unittest.TestCase):
    def run_node(self, script: str) -> None:
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is not installed")
        proc = subprocess.run([node, str(REPO / "tools" / script)], cwd=REPO, capture_output=True, text=True, timeout=300)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def test_edn_parser(self) -> None:
        self.run_node("test_offline_edn_parser.js")

    def test_viewer_renderers(self) -> None:
        self.run_node("test_offline_viewer_render.js")

    def test_parser_handles_every_tag_saving_registers(self) -> None:
        tags = set(re.findall(r'register(?:Edn|StringPayload)\([^,]+,\s*"([^"]+)"', SAVING.read_text()))
        self.assertTrue(tags, "found no registered tags in saving.kt")
        parser = PARSER.read_text()
        for tag in sorted(tags):
            self.assertIn(f"case '{tag}':", parser, f"edn-parser.js applyTag does not handle #{tag}")


if __name__ == "__main__":
    unittest.main()
