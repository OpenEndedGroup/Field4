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

    def test_field_is_a_third_choice_that_needs_no_executable(self) -> None:
        os.environ["FIELD_AGENT"] = "field"
        self.assertEqual(field_agent.configured_agent(), "field")
        argv = field_agent.headless_argv()
        self.assertEqual(argv[0], sys.executable)
        self.assertTrue(argv[1].endswith("field_repo_agent.py"))
        self.assertEqual(argv[2:], ["--root", "."], "read-only unless edits: no --write glob")
        self.assertEqual(field_agent.headless_argv("field", edits=True)[-2:], ["--write", "**"])
        self.assertEqual(field_agent.describe("field"), "Field AI4")


class FieldRepoAgentTest(unittest.TestCase):
    def test_backdoor_line_carries_root_globs_and_caps(self) -> None:
        import field_repo_agent
        argv = field_repo_agent.build_backdoor_argv(root=Path("/r"), read=["**"], write=["a.md", "b/*"],
                                                    goal_file=Path("/g.md"), model="m", max_rounds=7, port="9631", timeout=100)
        self.assertIn("--port", argv)
        task = argv[argv.index("--command") + 1]
        self.assertEqual(task, "agent-task --goal-file /g.md --repo-root /r --max-rounds 7 --timeout 100 "
                               "--repo-read '**' --repo-write a.md --repo-write 'b/*' --model m")

    def test_result_is_found_in_backdoor_json(self) -> None:
        import field_repo_agent
        out = '{"ok": false, "results": [{"command": "agent-task", "ok": false, "data": {"finalText": "done", "rounds": 3}}]}'
        self.assertEqual(field_repo_agent._result(out)["finalText"], "done")
        self.assertEqual(field_repo_agent._result("not json"), {})

    def test_docs_notes_field_agent_writes_only_its_partition(self) -> None:
        import docs_notes
        part = docs_notes.Partition(key="k", pages=["p.md"], files=["public/docs/p.md"], notes=[{"id": "n"}], generated=[])
        argv = docs_notes.AGENTS["field"].argv("PROMPT", Path("/r"), part, "cli")
        writes = [argv[i + 1] for i, a in enumerate(argv) if a == "--write"]
        self.assertEqual(writes[0], "public/docs/p.md")
        self.assertTrue(all(w == "public/docs/p.md" or part.slug in w for w in writes))
        prompt = argv[argv.index("--prompt") + 1]
        self.assertTrue(prompt.startswith(docs_notes.FIELD_AGENT_NOTE) and prompt.endswith("PROMPT"))


if __name__ == "__main__":
    unittest.main()
