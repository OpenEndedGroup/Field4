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

    def test_the_uv_fallback_uses_the_entrypoints_dependency_group(self):
        with (
            mock.patch.object(importlib.util, "find_spec", return_value=None),
            mock.patch.object(field_python_env.Path, "exists", return_value=False),
            mock.patch.object(field_python_env.shutil, "which", return_value="/usr/bin/uv"),
            mock.patch.object(os, "execvpe", side_effect=RuntimeError("execvpe called")) as execvpe,
            mock.patch.dict(os.environ, {"FIELD_BACKDOOR_REEXEC": ""}),
            mock.patch.object(sys, "argv", ["tools/build_zensical_docs.py", "--clean"]),
        ):
            with self.assertRaisesRegex(RuntimeError, "execvpe called"):
                field_python_env.ensure_repo_python(("zensical",), group="docs")
        argv = execvpe.call_args.args[1]
        self.assertEqual(argv[argv.index("--group") + 1], "docs")
        self.assertEqual(argv[-1], "--clean")

    def test_docs_and_ui_entrypoints_self_heal_but_only_as_entrypoints(self):
        """AGENTS.md listed four self-healing entrypoints and implied these two; now they are."""
        from pathlib import Path

        tools = Path(field_python_env.__file__).resolve().parent
        for name, modules in (
            ("run_ui_regressions.py", '("PIL", "websockets")'),
            ("build_zensical_docs.py", '("yaml", "zensical", "websockets"), group="docs"'),
        ):
            with self.subTest(entrypoint=name):
                text = (tools / name).read_text()
                guarded = text.split('if __name__ == "__main__":\n', 1)[1].split("\n", 3)
                self.assertIn(f"ensure_repo_python({modules})", "\n".join(guarded[:3]))
                # The bootstrap precedes the first optional import it protects.
                first_optional = min(
                    i for i in (text.find("\nfrom PIL import"), text.find("\nimport yaml")) if i >= 0
                )
                self.assertLess(text.index("ensure_repo_python(("), first_optional)
        previous = sys.modules.pop("tools.run_ui_regressions", None)
        try:
            with mock.patch("tools.field_python_env.ensure_repo_python") as ensure:
                importlib.import_module("tools.run_ui_regressions")
                ensure.assert_not_called()
        finally:
            sys.modules.pop("tools.run_ui_regressions", None)
            if previous is not None:
                sys.modules["tools.run_ui_regressions"] = previous


if __name__ == "__main__":
    unittest.main()
