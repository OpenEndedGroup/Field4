"""Hold the AI4 conversation demos and the pages built from them to their sources.

Each demo's generated files have a --check mode, and until these tests nothing ran them (the
function packager's check was failing on main):

- `tools/stacksl_conversation_documents.py` and `tools/rectsl_conversation_documents.py` write the
  StackSL and RectSL documents' `metadata.edn`, copy each starter's shared files into its recorded
  sessions, and generate the study's words guide;
- `tools/package_stacksl_conversation.py`, `tools/package_rectsl_conversation.py`,
  `tools/package_stackauto_conversation.py` and `tools/package_function_conversation.py` build each public page's listings, transcript pages,
  images and downloads from the recorded sessions.

The packagers render transcripts with Python-Markdown, which comes with the `docs` dependency group
and not with `backdoor`, the group `checkPythonTools` runs under; so they are checked through
`uv run --group docs`, the way the docs build runs them.

Run directly with:  uv run --project . --group backdoor python -m unittest tools.test_conversation_demos
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tools.conversation_demo import DONE, FAILED, current_kotlin_listing, settle_assets

REPO_ROOT = Path(__file__).resolve().parents[1]


class ConversationDemoTest(unittest.TestCase):
    def test_record_waits_for_notices_ai4_still_sends(self):
        # `record` ends a turn on these notices; when AI4 reworded its own, the recorder waited out
        # its whole timeout on a turn that had finished.
        source = (REPO_ROOT / "src/jvmMain/kotlin/afield/ai/models/Ai4Installer.kt").read_text()
        self.assertIn(DONE[-1], source)
        self.assertIn(FAILED[1], source)

    def test_current_kotlin_listing_preserves_recording_and_nonrewritable_calls(self):
        saved = ('val names = listOf("x", "y", "z").map { it }\n'
                 'val points = listOf(c to 0f, ring[(k + 1) % ring.size] to 1f)\n'
                 'val spread = listOf(*xs)\nval typed = listOf<Int>(1)\n'
                 'val empty = listOf()\nval multiline = listOf(\n    1, 2\n)\n')
        expected = saved.replace('listOf("x", "y", "z")', '["x", "y", "z"]').replace(
            'listOf(c to 0f, ring[(k + 1) % ring.size] to 1f)',
            '[c to 0f, ring[(k + 1) % ring.size] to 1f]')
        self.assertEqual(expected, current_kotlin_listing(saved))
        self.assertIn('listOf("x", "y", "z")', saved)
        self.assertEqual(expected, current_kotlin_listing(expected))

    def test_finder_metadata_is_not_a_published_asset(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            (output / "figure.png").write_bytes(b"image")
            (output / ".DS_Store").write_bytes(b"finder")
            settle_assets(output, {"figure.png": b"image"}, check=True, label="demo")
            settle_assets(output, {"figure.png": b"image"}, check=False, label="demo")
            self.assertTrue((output / ".DS_Store").is_file())

    def run_check(self, command: list[str]) -> subprocess.CompletedProcess:
        return subprocess.run(command, cwd=REPO_ROOT, capture_output=True, text=True, timeout=600)

    def docs_python(self, script: str) -> subprocess.CompletedProcess:
        uv = os.environ.get("UV") or shutil.which("uv")
        self.assertIsNotNone(uv, "uv is needed to run the packagers under the docs dependency group")
        return self.run_check([uv, "run", "--project", ".", "--group", "docs", "--frozen", "python", script, "--check"])

    def test_stacksl_documents_agree_with_their_sources(self):
        result = self.run_check([sys.executable, "tools/stacksl_conversation_documents.py", "--check"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_the_stacksl_page_matches_its_recorded_sessions(self):
        result = self.docs_python("tools/package_stacksl_conversation.py")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_rectsl_documents_agree_with_their_sources(self):
        result = self.run_check([sys.executable, "tools/rectsl_conversation_documents.py", "--check"])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_the_rectsl_page_matches_its_recorded_sessions(self):
        result = self.docs_python("tools/package_rectsl_conversation.py")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_the_stackauto_page_matches_its_recorded_session(self):
        result = self.docs_python("tools/package_stackauto_conversation.py")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_the_function_page_matches_its_recorded_session(self):
        result = self.docs_python("tools/package_function_conversation.py")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
