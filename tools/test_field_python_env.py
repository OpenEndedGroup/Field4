import importlib
import os
import sys
import unittest
from unittest import mock

from tools import field_python_env


class FieldPythonEnvironmentTest(unittest.TestCase):
    def test_importing_backdoor_does_not_bootstrap_the_importing_process(self):
        previous = sys.modules.pop("tools.field_backdoor", None)
        try:
            with mock.patch("tools.field_python_env.ensure_repo_python") as ensure:
                importlib.import_module("tools.field_backdoor")
                ensure.assert_not_called()
        finally:
            sys.modules.pop("tools.field_backdoor", None)
            if previous is not None:
                sys.modules["tools.field_backdoor"] = previous

    def test_entrypoint_bootstrap_preserves_the_entrypoint_and_arguments(self):
        with (
            mock.patch.object(importlib.util, "find_spec", return_value=None),
            mock.patch.object(field_python_env.Path, "exists", return_value=True),
            mock.patch.object(field_python_env, "_is_running_interpreter", return_value=False),
            mock.patch.object(os, "execve", side_effect=RuntimeError("execve called")) as execve,
            mock.patch.object(sys, "argv", ["tools/example.py", "--flag"]),
        ):
            with self.assertRaisesRegex(RuntimeError, "execve called"):
                field_python_env.ensure_repo_python()

        argv = execve.call_args.args[1]
        self.assertTrue(argv[1].endswith("tools/example.py"), argv)
        self.assertEqual("--flag", argv[2])


if __name__ == "__main__":
    unittest.main()
