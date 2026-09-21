import os
import socket
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PUBLIC_DIR = Path(__file__).resolve().parent.parent / "public"
if str(PUBLIC_DIR) not in sys.path:
    sys.path.insert(0, str(PUBLIC_DIR))

import docs_hooks  # noqa: E402

from tools.verification_runtime import (
    automation_server_environment,
    automation_workspace,
    select_automation_port,
)


class AutomationWorkspaceTests(unittest.TestCase):
    def test_implicit_port_moves_when_the_default_is_occupied(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            occupied = listener.getsockname()[1]
            selected = select_automation_port(occupied, explicit=False, attempts=50)
            self.assertGreater(selected, occupied)

    def test_implicit_port_also_checks_fields_http_mirror(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            mirror = listener.getsockname()[1]
            preferred = mirror - 10_000
            if preferred <= 1024:
                self.skipTest("ephemeral port range did not leave a usable mirrored port")
            selected = select_automation_port(preferred, explicit=False, attempts=50)
            self.assertGreater(selected, preferred)

    def test_explicit_port_is_never_silently_changed(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            occupied = listener.getsockname()[1]
            self.assertEqual(select_automation_port(occupied, explicit=True), occupied)

    def test_docs_runner_uses_the_shared_implicit_selector(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            occupied = listener.getsockname()[1]
            with mock.patch.object(docs_hooks, "DEFAULT_RUNNER_PORT", occupied), mock.patch.dict(
                os.environ, {}, clear=False
            ):
                os.environ.pop(docs_hooks.RUNNER_PORT_ENV, None)
                port, explicit = docs_hooks.resolve_runner_port()
            self.assertFalse(explicit)
            self.assertGreater(port, occupied)

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
            runner._capture_environment_check = None

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
