"""Regression guard for how a run decides it has a completion sidecar that can complete.

From 2026-09-28 to 09-30 the Kotlin 2.5 uplift left the AA sidecar unable to build a session, and
then unable to answer a request. Its `/health` said `{"ok": true}` the whole time, and the three
smoke tests of the AA chain found no sidecar, printed SKIP and passed. These tests pin the two
things that let that through: readiness must read the sidecar's `completion` state, not `ok`; and
a test that needs a sidecar and cannot have a working one is environment-incomplete, not a pass.

The fake below serves `/health` payloads shaped exactly as `CodeUseSemanticSidecar.kt` writes them.

Run with:  python -m unittest tools.test_completion_sidecar
"""

import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

from tools import completion_sidecar  # noqa: E402  (path set up just above)
from tools.completion_sidecar import (  # noqa: E402
    SIDECAR_PORT_ENV,
    SIDECAR_URL_ENV,
    SidecarLease,
    completion_problem,
    sidecar_workspace_status,
)
from tools.discover_field_tests import ParseError, validate_annotation  # noqa: E402
from tools.run_field_tests import requires_sidecar  # noqa: E402


class FakeSidecar:
    """A `/health` endpoint serving whatever payload the test sets."""

    def __init__(self, payload):
        self.payload = payload
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 (http.server's name)
                body = json.dumps(outer.payload).encode("utf-8")
                self.send_response(200 if self.path == "/health" else 404)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.url = f"http://127.0.0.1:{self.port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()


def health(workspace, completion=None):
    payload = {"ok": True, "backend": "k2-standalone", "workspaceRoot": str(workspace)}
    if completion is not None:
        payload["completion"] = completion
    return payload


class CompletionProblemTest(unittest.TestCase):
    def setUp(self):
        self.workspace = Path(tempfile.mkdtemp())

    def test_ok_true_with_a_failed_session_build_is_broken(self):
        # The 09-28 sidecar exactly: HTTP up, every session build failing.
        error = "RuntimeException: Cannot resolve /META-INF/analysis-api/low-level-api-fir.xml"
        with FakeSidecar(health(self.workspace, {"state": "failed", "readySessions": 0, "building": 0,
                                                 "lastError": error})) as fake:
            problem = completion_problem(fake.url)
        self.assertIsNotNone(problem)
        self.assertIn("failed", problem)
        self.assertIn("low-level-api-fir.xml", problem)

    def test_a_session_that_builds_but_fails_every_request_is_broken(self):
        # The sidecar once low-level-api-fir was restored and kotlin-reflect was still missing.
        error = "error KotlinReflectionNotSupportedError: Kotlin reflection implementation is not found"
        with FakeSidecar(health(self.workspace, {"state": "failing", "readySessions": 1, "building": 0,
                                                 "lastError": error})) as fake:
            problem = completion_problem(fake.url)
        self.assertIsNotNone(problem)
        self.assertIn("KotlinReflectionNotSupportedError", problem)

    def test_a_sidecar_that_reports_no_completion_state_cannot_be_trusted(self):
        with FakeSidecar(health(self.workspace)) as fake:
            problem = completion_problem(fake.url)
        self.assertIsNotNone(problem)
        self.assertIn("no completion state", problem)

    def test_idle_building_and_ready_are_not_problems(self):
        # Field's first completion is what builds a session, so idle and building are normal.
        for state in ("idle", "building", "ready"):
            with self.subTest(state=state):
                with FakeSidecar(health(self.workspace, {"state": state, "readySessions": 0,
                                                         "building": 0})) as fake:
                    self.assertIsNone(completion_problem(fake.url))

    def test_nothing_listening_is_a_problem(self):
        with FakeSidecar(health(self.workspace)) as fake:
            url = fake.url
        self.assertIn("no sidecar answers", completion_problem(url))

    def test_workspace_status_matches_field_sh(self):
        with FakeSidecar(health(self.workspace)) as fake:
            self.assertEqual("ok", sidecar_workspace_status(fake.url, self.workspace))
            self.assertEqual("wrong", sidecar_workspace_status(fake.url, Path(tempfile.mkdtemp())))
            # The client may hand over the /complete URL; health lives beside it.
            self.assertEqual("ok", sidecar_workspace_status(fake.url + "/complete", self.workspace))


class SidecarLeaseTest(unittest.TestCase):
    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        (self.repo / "tools" / "code-use-semantic-sidecar").mkdir(parents=True)
        self.log = self.repo / "sidecar.log"

    def test_a_configured_sidecar_for_this_checkout_is_used_and_never_stopped(self):
        with FakeSidecar(health(self.repo, {"state": "ready", "readySessions": 1, "building": 0})) as fake:
            with mock.patch.dict(os.environ, {SIDECAR_URL_ENV: fake.url}):
                lease = SidecarLease(self.repo, self.log)
                self.assertEqual(fake.url, lease.ensure())
                self.assertFalse(lease.started_by_lease)
                self.assertIsNone(lease.problem())
                with mock.patch.object(completion_sidecar.subprocess, "Popen") as popen:
                    lease.stop()
                popen.assert_not_called()

    def test_a_reused_sidecar_that_cannot_complete_is_reported_by_problem(self):
        with FakeSidecar(health(self.repo, {"state": "failed", "readySessions": 0, "building": 0,
                                            "lastError": "boom"})) as fake:
            with mock.patch.dict(os.environ, {SIDECAR_URL_ENV: fake.url}):
                lease = SidecarLease(self.repo, self.log)
                self.assertEqual(fake.url, lease.ensure())
                self.assertIn("boom", lease.problem())

    def test_a_port_held_by_another_workspace_is_refused_without_starting_anything(self):
        with FakeSidecar(health(Path(tempfile.mkdtemp()))) as fake:
            env = {SIDECAR_PORT_ENV: str(fake.port), SIDECAR_URL_ENV: ""}
            with mock.patch.dict(os.environ, env):
                with mock.patch.object(completion_sidecar.subprocess, "Popen") as popen:
                    lease = SidecarLease(self.repo, self.log)
                    self.assertIsNone(lease.ensure())
                popen.assert_not_called()
        self.assertIn("different workspace", lease.unavailable)
        self.assertEqual(lease.unavailable, lease.problem())

    def test_a_checkout_without_the_sidecar_project_is_unavailable(self):
        lease = SidecarLease(Path(tempfile.mkdtemp()), self.log)
        self.assertIsNone(lease.ensure())
        self.assertIn("no tools/code-use-semantic-sidecar", lease.problem())


class RequiresSidecarAnnotationTest(unittest.TestCase):
    def test_only_a_literal_true_requires_a_sidecar(self):
        self.assertTrue(requires_sidecar({"requiresSidecar": True}))
        self.assertFalse(requires_sidecar({}))
        self.assertFalse(requires_sidecar({"requiresSidecar": False}))

    def test_a_non_boolean_is_a_discovery_error_not_a_silent_false(self):
        annotation = {"id": "x", "runner": "jvm-main", "entrypoint": "E", "requiresSidecar": "yes"}
        with self.assertRaises(ParseError):
            validate_annotation(Path("Probe.kt"), 1, annotation)

    def test_every_catalogued_aa_probe_declares_it(self):
        # The three tests whose job is the AA chain. Dropping the key would put them back to
        # running with no sidecar, which is how they stayed green for two days of a dead backend.
        root = Path(__file__).resolve().parent.parent / "src" / "jvmMain" / "kotlin" / "afield"
        for relative in ("CompletionAaE2EProbe.kt", "CompletionLoweringProbe.kt",
                         "examples/KotlinJsCompletionSpikeTest.kt"):
            with self.subTest(probe=relative):
                text = (root / relative).read_text(encoding="utf-8")
                header = text[text.index("FIELD_TEST"): text.index("*/", text.index("FIELD_TEST"))]
                self.assertIn("requiresSidecar: true", header)
                self.assertNotIn('println("SKIP', text)


if __name__ == "__main__":
    unittest.main()
