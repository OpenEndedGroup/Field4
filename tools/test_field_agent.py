"""tools/field_agent.py: which agent the tools start, and how."""

from __future__ import annotations

import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import field_agent  # noqa: E402


class FieldAgentTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.file = self.root / "agent"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("claude", "codex"):
            exe = self.bin / name
            exe.write_text("#!/bin/sh\n")
            exe.chmod(exe.stat().st_mode | stat.S_IXUSR)
        env = {k: v for k, v in os.environ.items() if k != "FIELD_AGENT"}
        env["PATH"] = str(self.bin)
        self.patches = [mock.patch.dict(os.environ, env, clear=True), mock.patch.object(field_agent, "AGENT_FILE", self.file)]
        for patch in self.patches:
            patch.start()

    def tearDown(self) -> None:
        for patch in reversed(self.patches):
            patch.stop()
        self._tmp.cleanup()

    def test_environment_then_file_then_claude(self) -> None:
        self.assertEqual(field_agent.configured_agent(), "claude")
        self.file.write_text("codex   # this machine\n")
        self.assertEqual(field_agent.configured_agent(), "codex")
        os.environ["FIELD_AGENT"] = "CLAUDE"
        self.assertEqual(field_agent.configured_agent(), "claude")
        os.environ["FIELD_AGENT"] = "rm -rf /"
        self.assertEqual(field_agent.configured_agent(), "codex", "an unknown name falls through, it is never run")

    def test_headless_commands_read_the_prompt_on_stdin(self) -> None:
        claude, codex = str(self.bin / "claude"), str(self.bin / "codex")
        self.assertEqual(field_agent.headless_argv("claude"), [claude, "-p", "--output-format", "text"])
        self.assertEqual(field_agent.headless_argv("codex"), [codex, "exec", "--sandbox", "read-only", "--skip-git-repo-check", "-"])
        self.assertIn("acceptEdits", field_agent.headless_argv("claude", edits=True))
        self.assertIn("danger-full-access", field_agent.headless_argv("codex", edits=True))
        self.assertEqual(field_agent.describe("codex"), "codex exec")

    def test_a_missing_agent_is_none_not_a_bare_name(self) -> None:
        (self.bin / "codex").unlink()
        with mock.patch.object(field_agent, "INSTALL_DIRS", ()):
            self.assertIsNone(field_agent.headless_argv("codex"))


if __name__ == "__main__":
    unittest.main()
