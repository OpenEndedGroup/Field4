"""Preparation must preserve the selected virtual environment and verify real schema-2 output."""
import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import prepare_sentence_parser as prepare


class SentenceParserPreparationTest(unittest.TestCase):
    def test_existing_venv_executable_is_not_resolved_out_of_its_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "base-python"
            target.write_text("binary stand-in; never executed")
            selected = Path(directory) / "venv-python"
            try:
                selected.symlink_to(target)
            except OSError as error:
                self.skipTest(f"symlink creation unavailable: {error}")
            result = subprocess.CompletedProcess([], 0, stdout='[{"backend":"stanza","schema":2,"tokens":[{}],"constituency":{},"dependencies":{"sd_collapsed":[{"gov":0}]}}]')
            result.stdout = result.stdout.replace('"constituency":{}', '"constituency":{"label":"ROOT"}')
            with patch.object(prepare.subprocess, "run", return_value=result) as run, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, prepare.main(["--verify-only", "--python", str(selected)]))
            command = run.call_args.args[0]
            self.assertEqual(str(selected), command[0])
            self.assertIn("cpu", command)
            self.assertNotIn("--download", command)

    def test_parser_output_requires_a_rooted_dependency_graph(self):
        with tempfile.TemporaryDirectory() as directory:
            selected = Path(directory) / "python"
            selected.touch()
            result = subprocess.CompletedProcess([],0,stdout='[{"tokens":[{}],"constituency":{"label":"ROOT"},"dependencies":{"sd_collapsed":[]}}]')
            with patch.object(prepare.subprocess, "run", return_value=result):
                with self.assertRaisesRegex(RuntimeError,"rooted"):
                    prepare.main(["--verify-only","--python",str(selected)])
