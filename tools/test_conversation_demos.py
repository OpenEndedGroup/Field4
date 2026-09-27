"""Hold the AI4 conversation demos and the pages built from them to their sources.

Each demo's generated files have a --check mode, and until these tests nothing ran them (the
function packager's check was failing on main):

- `tools/stacksl_conversation_documents.py` and `tools/rectsl_conversation_documents.py` write the
  StackSL and RectSL documents' `metadata.edn`, copy each starter's shared files into its recorded
  sessions, and generate the study's words guide;
- `tools/package_stacksl_conversation.py`, `tools/package_rectsl_conversation.py` and
  `tools/package_function_conversation.py` build each public page's listings, transcript pages,
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
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]


class ConversationDemoTest(unittest.TestCase):
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

    def test_the_function_page_matches_its_recorded_session(self):
        result = self.docs_python("tools/package_function_conversation.py")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
