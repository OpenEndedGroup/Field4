import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PUBLIC_DIR = Path(__file__).resolve().parent.parent / "public"
if str(PUBLIC_DIR) not in sys.path:
    sys.path.insert(0, str(PUBLIC_DIR))

import docs_hooks  # noqa: E402

from tools.verification_runtime import automation_server_environment, automation_workspace


class AutomationWorkspaceTests(unittest.TestCase):
    def test_workspace_is_checkout_local_and_not_a_user_preference(self) -> None:
        root = Path("/tmp/example-field-worktree")

        self.assertEqual(
            automation_workspace(root),
            root.resolve() / "build" / "field-automation-workspace",
        )

    def test_server_environment_cannot_rewrite_the_interactive_preference(self) -> None:
        environment = automation_server_environment(
            9477,
            base={"PRESERVED": "yes", "AFIELD_SKIP_WORKSPACE_PERSIST": "0"},
            extra={"EXTRA": "value"},
        )

        self.assertEqual(environment["PORT"], "9477")
        self.assertEqual(environment["AFIELD_SKIP_WORKSPACE_PERSIST"], "1")
        self.assertEqual(environment["PRESERVED"], "yes")
        self.assertEqual(environment["EXTRA"], "value")

    def test_docs_server_never_inherits_or_rewrites_the_global_preference(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = object.__new__(docs_hooks.RunnerState)
            runner.repo_root = root
            runner.shared_log_path = root / "public" / ".field-docs-runner-field.log"
            runner.backdoor_port = 9477
            runner._ensure_semantic_sidecar = lambda: None
            runner._http_health_ok = lambda _port: True
            runner._listener_pids = lambda: []
            runner.server_starts = 0

            process = mock.Mock()
            process.poll.return_value = None
            with mock.patch.dict(os.environ, {docs_hooks.RUNNER_WORKSPACE_ENV: ""}):
                with mock.patch.object(docs_hooks.subprocess, "Popen", return_value=process) as spawn:
                    self.assertIsNone(runner._start_shared_field_server_once())

            command, kwargs = spawn.call_args
            self.assertIn(
                f"--args=--workspace {automation_workspace(root)}",
                command[0],
            )
            self.assertEqual(kwargs["env"]["AFIELD_SKIP_WORKSPACE_PERSIST"], "1")
            kwargs["stdout"].close()


if __name__ == "__main__":
    unittest.main()
