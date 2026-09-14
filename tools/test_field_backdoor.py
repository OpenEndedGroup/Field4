import argparse
import asyncio
import contextlib
import io
import re
import shlex
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

# `run_field_tests.py` imports its siblings by bare name (`from discover_field_tests import ...`), the
# way it does when run as a script from `tools/`. Put that directory on the path before importing it
# under its package name, rather than reaching into the module's own imports.
REPO_PATH = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_PATH))
sys.path.insert(0, str(REPO_PATH / "tools"))

from tools.field_backdoor import (
    BackdoorError,
    CommandParseError,
    FieldBackdoor,
    REPO_ROOT,
    SessionLogger,
    build_command_parser,
    exception_message,
    parse_command,
)
from tools.verification_runtime import automation_workspace

from tools.run_field_tests import build_backdoor_command  # noqa: E402  (path set up just above)


class _Logger:
    session_id = "field-backdoor-test"
    path = Path("/tmp/field-backdoor-test.jsonl")

    def log(self, _event: str, **_fields) -> None:
        pass


class SessionLoggerTests(unittest.TestCase):
    def test_message_less_timeout_keeps_its_exception_type(self) -> None:
        self.assertEqual(exception_message(asyncio.TimeoutError()), "TimeoutError")

    def test_session_byte_cap_drops_telemetry_without_growing_forever(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            logger = SessionLogger(Path(root), ["test"], max_bytes=1_200)
            for index in range(100):
                logger.log("ws_receive_raw", index=index, raw="x" * 4_000)
            logger.close()

            text = logger.path.read_text()
            self.assertTrue(logger.capped)
            self.assertGreater(logger.dropped_records, 0)
            self.assertIn('"event": "session_log_capped"', text)
            self.assertLess(logger.path.stat().st_size, 4_000)

    def test_log_write_failure_never_escapes_into_the_receiver(self) -> None:
        class FullDisk:
            closed = False

            def write(self, _value: str) -> None:
                raise OSError(28, "No space left on device")

            def flush(self) -> None:
                pass

            def close(self) -> None:
                self.closed = True

        with tempfile.TemporaryDirectory() as root:
            logger = SessionLogger(Path(root), ["test"])
            logger.handle.close()
            logger.handle = FullDisk()  # type: ignore[assignment]
            logger.log("ws_receive_raw", raw="still not fatal")
            logger.log("another", value=1)
            logger.close()
            self.assertIn("No space left on device", logger.write_error or "")

    def test_prune_enforces_a_total_byte_budget(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            for index in range(5):
                path = directory / f"{index}.jsonl"
                path.write_bytes(b"x" * 1_000)
                path.touch()
            SessionLogger.prune(directory, keep=10, max_total_bytes=2_200)
            remaining = list(directory.glob("*.jsonl"))
            self.assertLessEqual(sum(path.stat().st_size for path in remaining), 2_200)
            self.assertGreaterEqual(len(remaining), 1)


class BoxReferenceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.client = FieldBackdoor(
            port=1,
            workspace=None,
            default_timeout=0.01,
            logger=_Logger(),
        )
        self.client.scratch = True

    def test_managed_server_defaults_to_a_checkout_local_workspace(self) -> None:
        self.assertEqual(
            self.client.workspace,
            str(automation_workspace(REPO_ROOT)),
        )
        self.assertIn(str(REPO_ROOT), self.client.workspace)

    def status(self) -> dict:
        from tools.field_backdoor import dispatch_command

        result = asyncio.run(dispatch_command(self.client, parse_command("status"), "status"))
        self.assertTrue(result["ok"])
        return result["data"]

    def test_status_separates_the_request_from_what_the_server_reported(self) -> None:
        """The replacement for a test that could not fail.

        Until 2026-09-12 this asserted `status["workspace"] == client.workspace` -- the tool
        echoing its own field back, which is true however wrong the workspace is. `AGENTS.md`
        prescribed verifying that same value before editing a real document, and on 2026-09-11
        an agent wrote five documents into a different checkout's `dev_docs/agents/demos/`
        across four runs while believing it had done so.
        """
        data = self.status()
        self.assertNotIn("workspace", data)
        self.assertEqual(data["requestedWorkspace"], None)
        # Nothing has been read back from a server in this fixture, so there is no server fact
        # to report -- and the request must not be substituted for one.
        self.assertIsNone(data["serverWorkspace"])

    def test_status_reports_a_server_workspace_that_can_disagree(self) -> None:
        self.client.requested_workspace = "/tmp/what-the-caller-asked-for"
        self.client.server_workspace = "/tmp/where-the-server-actually-writes"

        data = self.status()

        self.assertEqual(data["serverWorkspace"], "/tmp/where-the-server-actually-writes")
        self.assertEqual(data["requestedWorkspace"], "/tmp/what-the-caller-asked-for")
        self.assertNotEqual(data["serverWorkspace"], data["requestedWorkspace"])

    def open_capturing_stderr(self, document: str, *, scratch: bool = False) -> str:
        async def reply(_name: str, payload: dict, *, timeout: float, **_kwargs) -> dict:
            return {"payload": {"documentName": payload["documentName"], "sessionKey": "k", "boxes": []}}

        self.client.call_for_single_reply = reply  # type: ignore[assignment]
        captured = io.StringIO()
        with contextlib.redirect_stderr(captured):
            asyncio.run(self.client.open_document(document, scratch=scratch, timeout=1.0))
        return captured.getvalue()

    def test_an_absolute_document_name_is_called_out(self) -> None:
        """The third known cause of an unexplained `boxes: []`.

        The server resolves a name as `workspace + "/" + name` unconditionally, so an absolute
        path yields a session key with the workspace glued in front of it and an empty new
        document. Measured on a live Field on port 19542: `--document <ws>/demo_doc` produced
        `sessionKey` = `<ws>//Users/.../<ws>/demo_doc`.
        """
        warning = self.open_capturing_stderr("/Users/marc/Documents/AFieldWorkspace/dust_three")

        self.assertIn("is an absolute path", warning)
        # The remedy, spelled out with this caller's own paths rather than in the abstract.
        self.assertIn("--workspace /Users/marc/Documents/AFieldWorkspace --document dust_three", warning)

    def test_a_relative_document_name_says_nothing(self) -> None:
        self.assertEqual(self.open_capturing_stderr("dust_three"), "")

    def test_a_scratch_open_says_nothing_about_paths(self) -> None:
        # `scratch` throws the name away, so there is nothing to warn about.
        self.assertEqual(self.open_capturing_stderr("/tmp/anything", scratch=True), "")

    def open_document_reporting(
        self, document: str, *, existed: bool | None, scratch: bool = False, must_exist: bool = False
    ) -> tuple[dict, str]:
        """Dispatch `open`, with the server's `documentExisted` under the test's control."""
        from tools.field_backdoor import dispatch_command

        payload = {"documentName": document, "sessionKey": f"/ws/{document}", "boxes": []}
        if existed is not None:
            payload["documentExisted"] = existed

        async def reply(_name: str, _payload: dict, *, timeout: float, **_kwargs) -> dict:
            return {"payload": payload}

        self.client.call_for_single_reply = reply  # type: ignore[assignment]
        line = f"open {document}"
        if scratch:
            line += " --scratch"
        if must_exist:
            line += " --must-exist"
        captured = io.StringIO()
        with contextlib.redirect_stderr(captured):
            result = asyncio.run(dispatch_command(self.client, parse_command(line), line))
        return result, captured.getvalue()

    def test_a_document_field_had_to_invent_is_called_out(self) -> None:
        """The fourth cause of an unexplained `boxes: []`, and the one that was unnameable.

        Opening a name that is not there is how a document gets CREATED in Field, so the
        server answers with a snapshot either way and `open` reported `ok: true`. The fact
        was not missing -- the server logged `file '...' not found; will be created on
        first save` -- it just never reached the reply. Measured on a live Field on 8802.
        """
        result, warning = self.open_document_reporting("alife_walls", existed=False)

        self.assertTrue(result["ok"])  # creating a document is legitimate; saying nothing was not
        self.assertTrue(result["data"]["createdNewDocument"])
        self.assertIn("did not exist in the server's workspace", warning)
        self.assertIn("created a new EMPTY document", warning)

    def test_a_document_that_was_there_says_nothing(self) -> None:
        result, warning = self.open_document_reporting("alife_walls", existed=True)
        self.assertEqual(warning, "")
        self.assertNotIn("createdNewDocument", result["data"])

    def test_a_server_too_old_to_answer_abstains_rather_than_guessing(self) -> None:
        """Fail open, like `serverWorkspace: null` -- but labelled, so it reads as unverified."""
        result, warning = self.open_document_reporting("alife_walls", existed=None)

        self.assertEqual(warning, "")
        self.assertFalse(result["data"]["documentExistedKnown"])
        self.assertNotIn("createdNewDocument", result["data"])

    def test_must_exist_refuses_a_document_field_would_have_invented(self) -> None:
        with self.assertRaises(BackdoorError) as raised:
            self.open_document_reporting("alife_walls", existed=False, must_exist=True)

        message = str(raised.exception)
        self.assertIn("no document named 'alife_walls'", message)
        # Names where to look, and that the workspace it resolves against is the server's.
        self.assertIn("serverWorkspace", message)

    def test_must_exist_does_not_also_advise_passing_must_exist(self) -> None:
        """The refusal supersedes the advisory; printing both told the caller to do what
        they had just done."""
        with contextlib.redirect_stderr(io.StringIO()) as captured:
            with self.assertRaises(BackdoorError):
                self.open_document_reporting("alife_walls", existed=False, must_exist=True)
        self.assertEqual(captured.getvalue(), "")

    def test_must_exist_is_satisfied_by_a_document_that_was_there(self) -> None:
        result, _ = self.open_document_reporting("alife_walls", existed=True, must_exist=True)
        self.assertTrue(result["ok"])

    def test_must_exist_refuses_rather_than_passes_when_it_cannot_be_checked(self) -> None:
        """An unanswerable guarantee is worse than no guarantee: the point is to fail closed."""
        with self.assertRaises(BackdoorError) as raised:
            self.open_document_reporting("alife_walls", existed=None, must_exist=True)
        self.assertIn("predates", str(raised.exception))

    def test_must_exist_with_scratch_is_a_contradiction_and_says_so(self) -> None:
        with self.assertRaises(BackdoorError) as raised:
            self.open_document_reporting("anything", existed=None, scratch=True, must_exist=True)
        self.assertIn("meaningless with --scratch", str(raised.exception))

    def test_status_says_whether_the_request_reached_a_server_at_all(self) -> None:
        # False for an attached server: `--workspace` only ever becomes
        # `--args=--workspace ...` on the launch path, so on this path it decided nothing.
        self.assertFalse(self.status()["workspaceAppliedToServer"])

    def test_set_command_uses_acknowledged_persistent_property_path(self) -> None:
        from tools.field_backdoor import dispatch_command

        self.client.boxes["box"] = {"uid": "box", "name": "box"}
        calls: list[tuple[str, dict, float]] = []

        async def reply(name: str, payload: dict, *, timeout: float, **_kwargs) -> dict:
            calls.append((name, payload, timeout))
            return {
                "payload": {
                    "ok": True,
                    "uid": payload["uid"],
                    "property": payload["property"],
                    "message": "saved",
                }
            }

        self.client.call_for_single_reply = reply  # type: ignore[method-assign]
        result = asyncio.run(
            dispatch_command(
                self.client,
                parse_command('set box vertex "shader text"'),
                'set box vertex "shader text"',
            )
        )

        self.assertTrue(result["ok"])
        self.assertEqual(self.client.boxes["box"]["vertex"], "shader text")
        self.assertEqual(
            calls,
            [
                (
                    "agent-set-property",
                    {"uid": "box", "property": "vertex", "value": "shader text"},
                    self.client.default_timeout,
                )
            ],
        )

    def test_live_document_docs_require_workspace_and_document(self) -> None:
        text = (REPO_ROOT / "dev_docs" / "agents" / "FIELD_BACKDOOR.md").read_text()
        live = text.split("### Coordinated live coding in the user's visible Field", 1)[1]
        live = live.split("Preserve Field's execution distinction", 1)[0]
        self.assertIn("--port 9092", live)
        self.assertIn("--attach-existing", live)
        self.assertIn("--workspace /Users/marc/Documents/AFieldWorkspace", live)
        self.assertIn("--document dust_three", live)
        self.assertIn("--command status", live)

    def test_explicit_live_attachment_reuses_same_checkout_despite_newer_source(self) -> None:
        client = FieldBackdoor(
            port=9092,
            workspace="/tmp/AFieldWorkspace",
            default_timeout=0.01,
            logger=_Logger(),
            attach_existing=True,
        )
        instance = {
            "repoRoot": str(REPO_ROOT),
            "startedAtEpochMs": 1,
            "pid": 123,
        }
        with (
            mock.patch("tools.field_backdoor.health_check", return_value=True),
            mock.patch("tools.field_backdoor.field_instance_info", return_value=instance),
        ):
            asyncio.run(client.ensure_server())
        self.assertTrue(client.reused_existing_server)
        self.assertIsNone(client.managed_process)

    def attach(self, *, requested: str | None, server: str | None) -> FieldBackdoor:
        """Attach to a live server that reports ``server`` as its workspace."""
        client = FieldBackdoor(
            port=9092,
            workspace=requested,
            default_timeout=0.01,
            logger=_Logger(),
            attach_existing=True,
        )
        instance: dict[str, object] = {
            "repoRoot": str(REPO_ROOT),
            "startedAtEpochMs": 1,
            "pid": 123,
        }
        if server is not None:
            instance["workspace"] = server
        with (
            mock.patch("tools.field_backdoor.health_check", return_value=True),
            mock.patch("tools.field_backdoor.field_instance_info", return_value=instance),
        ):
            asyncio.run(client.ensure_server())
        return client

    def test_an_attached_server_donates_its_own_workspace_to_the_client(self) -> None:
        client = self.attach(requested=None, server="/Users/marc/Documents/AFieldWorkspace")
        self.assertEqual(client.server_workspace, "/Users/marc/Documents/AFieldWorkspace")
        self.assertIsNone(client.requested_workspace)

    def test_a_workspace_that_the_attached_server_will_ignore_is_refused(self) -> None:
        """The 2026-09-11 incident, turned into a hard error.

        `--workspace` reaches exactly one place: the `--args=--workspace ...` of a server this
        process launches. Attached, it decided nothing, `status` echoed it back as fact, and
        five documents landed in a different checkout's `dev_docs/agents/demos/` while the agent
        believed it had verified the workspace.
        """
        with self.assertRaises(BackdoorError) as raised:
            self.attach(
                requested="/Users/marc/field4-worktrees/mine/dev_docs/agents/demos",
                server="/Users/marc/field4/build/field-automation-workspace",
            )

        message = str(raised.exception)
        # Both values, so the diagnosis needs no second command.
        self.assertIn("/Users/marc/field4-worktrees/mine/dev_docs/agents/demos", message)
        self.assertIn("/Users/marc/field4/build/field-automation-workspace", message)
        self.assertIn("used to be ignored in silence", message)

    def test_the_servers_own_workspace_is_accepted_however_it_is_spelled(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            nested = Path(root) / "AFieldWorkspace"
            nested.mkdir()
            # macOS resolves /tmp through /private/tmp, so a caller typing the path they were
            # given must not be refused for a symlink they cannot see.
            client = self.attach(requested=str(nested), server=str(nested.resolve()))
        self.assertTrue(client.reused_existing_server)

    def test_a_server_too_old_to_report_a_workspace_is_attachable_but_unverified(self) -> None:
        # Fail open: refusing would make the new client unable to attach to a Field started
        # before this change, which is a worse failure than an unverifiable one. `status` says
        # `serverWorkspace: null`, which is the honest answer.
        client = self.attach(requested="/tmp/AFieldWorkspace", server=None)
        self.assertTrue(client.reused_existing_server)
        self.assertIsNone(client.server_workspace)

    def test_explicit_live_attachment_never_starts_a_missing_server(self) -> None:
        client = FieldBackdoor(
            port=9092,
            workspace="/tmp/AFieldWorkspace",
            default_timeout=0.01,
            logger=_Logger(),
            attach_existing=True,
        )
        with (
            mock.patch("tools.field_backdoor.health_check", return_value=False),
            self.assertRaisesRegex(BackdoorError, "no Field server is listening"),
        ):
            asyncio.run(client.ensure_server())
        self.assertIsNone(client.managed_process)

    def test_unknown_scratch_box_explains_invocation_lifetime(self) -> None:
        with self.assertRaises(BackdoorError) as raised:
            self.client.require_box_reference("box-from-an-earlier-process")

        message = str(raised.exception)
        self.assertIn("one field_backdoor.py invocation", message)
        self.assertIn("reusing --port does not reopen", message)
        self.assertIn("--document <name>", message)

    def test_run_does_not_fabricate_an_unknown_box(self) -> None:
        async def attempt() -> None:
            await self.client.run_box(
                "box-from-an-earlier-process",
                property_name="code",
                value=None,
                timeout=0.01,
            )

        with self.assertRaises(BackdoorError):
            asyncio.run(attempt())
        self.assertNotIn("box-from-an-earlier-process", self.client.boxes)

    def test_execution_fetches_a_custom_property_missing_from_the_compact_snapshot(self) -> None:
        self.client.boxes["box"] = {"uid": "box", "name": "Box", "code": "ordinary()"}
        calls: list[tuple[str, dict]] = []

        async def reply(name, payload, **_kwargs):
            calls.append((name, payload))
            return {
                "payload": {
                    "ok": True,
                    "uid": "box",
                    "property": "look_switch_test",
                    "value": "switchLooks()",
                }
            }

        self.client.call_for_single_reply = reply  # type: ignore[method-assign]
        code = asyncio.run(
            self.client.execution_code(
                "box", self.client.boxes["box"], "look_switch_test", timeout=0.01
            )
        )

        self.assertEqual(code, "switchLooks()")
        self.assertEqual(self.client.boxes["box"]["look_switch_test"], "switchLooks()")
        self.assertEqual(
            calls,
            [("agent-get-property", {"uid": "box", "property": "look_switch_test"})],
        )


class RunningStateOrderingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.client = FieldBackdoor(port=1, workspace=None, default_timeout=0.01, logger=_Logger())

    def apply(self, *, running: bool, watermark: int) -> None:
        self.client._apply_event(
            {
                "name": "is-running",
                "uid": "box",
                "payload": {
                    "uid": "box",
                    "strand": "__jobs__",
                    "watermark": watermark,
                    "isRunning": running,
                },
            }
        )

    def test_late_true_cannot_resurrect_a_stopped_strand(self) -> None:
        self.apply(running=True, watermark=3)
        self.apply(running=False, watermark=4)
        self.apply(running=True, watermark=3)

        self.assertEqual(self.client.boxes["box"]["runningStrands"], {})

    def test_false_wins_over_late_true_at_the_same_watermark(self) -> None:
        self.apply(running=True, watermark=7)
        self.apply(running=False, watermark=7)
        self.apply(running=True, watermark=7)

        self.assertEqual(self.client.boxes["box"]["runningStrands"], {})

    def test_newer_launch_can_start_after_stop(self) -> None:
        self.apply(running=False, watermark=7)
        self.apply(running=True, watermark=8)

        self.assertEqual(self.client.boxes["box"]["runningStrands"], {"__jobs__": 8})


class BackdoorLiveCodingStateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.client = FieldBackdoor(port=1, workspace=None, default_timeout=0.05, logger=_Logger())
        self.client.boxes["box"] = {"uid": "box", "runningStrands": {}}
        self.seq = 0

    def emit(self, name: str, payload: dict) -> dict:
        self.seq += 1
        self.client.event_seq = self.seq
        event = {
            "seq": self.seq,
            "name": name,
            "messageName": name,
            "uid": "box",
            "payload": {"uid": "box", **payload},
        }
        self.client.event_log.append(event)
        self.client._apply_event(event)
        return event

    def test_empty_line_minus_one_error_clears_instead_of_failing(self) -> None:
        self.emit(
            "peer-output",
            {"kind": "error", "line": 12, "property": "vertex", "html": "shader failed"},
        )
        self.assertIsNotNone(self.client._execution_error_event("box"))

        cleared = self.emit(
            "peer-output",
            {"kind": "error", "line": -1, "property": "vertex", "html": ""},
        )

        self.assertIsNone(self.client._execution_error_event("box"))
        self.assertEqual(self.client.execution_errors([cleared]), [])
        self.assertNotIn(cleared, self.client.collect_output("box", channel=None, kind="all"))

    def test_clear_is_scoped_to_one_shader_property(self) -> None:
        self.emit(
            "peer-output",
            {"kind": "error", "line": 2, "property": "vertex", "html": "vertex failed"},
        )
        fragment = self.emit(
            "peer-output",
            {"kind": "error", "line": 3, "property": "fragment", "html": "fragment failed"},
        )
        self.emit(
            "peer-output",
            {"kind": "error", "line": -1, "property": "vertex", "html": ""},
        )

        self.assertIs(self.client._execution_error_event("box"), fragment)

    def test_wait_output_does_not_abort_on_a_cleared_error(self) -> None:
        self.emit(
            "peer-output",
            {"kind": "error", "line": 8, "property": "fragment", "html": "old failure"},
        )
        self.emit(
            "peer-output",
            {"kind": "error", "line": -1, "property": "fragment", "html": ""},
        )

        events, timed_out, error = asyncio.run(
            self.client.wait_output(
                "box",
                channel=None,
                kind="all",
                contains="not-going-to-arrive",
                timeout=0.01,
            )
        )

        self.assertTrue(timed_out)
        self.assertIsNone(error)
        self.assertEqual(events, [])

    def test_nonempty_line_minus_one_error_remains_a_failure(self) -> None:
        failure = self.emit(
            "output",
            {"kind": "error", "line": -1, "property": "code", "html": "runtime failed"},
        )

        self.assertIs(self.client._execution_error_event("box"), failure)
        self.assertEqual(self.client.execution_errors([failure]), ["runtime failed"])

    def test_describe_action_is_retained_and_action_uses_the_field_protocol(self) -> None:
        self.emit(
            "describe-action",
            {
                "value": [
                    {"id": "moduleHere_reload", "description": "Reload Shader|alt r"},
                ]
            },
        )
        self.assertIn("moduleHere_reload", self.client.boxes["box"]["actionDescriptions"])
        sent: list[tuple[str, dict, float]] = []

        async def reply(name: str, payload: dict, *, timeout: float, **_kwargs) -> dict:
            sent.append((name, payload, timeout))
            return {
                "payload": {
                    "actionId": payload["uid_action"],
                    "found": True,
                    "success": True,
                    "selectedUids": payload["uid_currentSelection"],
                }
            }

        self.client.call_for_single_reply = reply  # type: ignore[method-assign]
        data, events = asyncio.run(
            self.client.perform_action(
                "box",
                action_id="moduleHere_reload",
                property_name="vertex",
                settle=0.0,
                timeout=0.5,
            )
        )

        self.assertTrue(data["success"])
        self.assertEqual(events, [])
        self.assertEqual(
            sent,
            [
                (
                    "perform-action",
                    {
                        "uid_currentSelection": ["box"],
                        "uid_action": "moduleHere_reload",
                        "uid_property": "vertex",
                    },
                    0.5,
                )
            ],
        )

    def test_wait_running_requires_a_settled_state(self) -> None:
        self.client.boxes["box"]["runningStrands"] = {"default": 1}
        data, events, timed_out = asyncio.run(
            self.client.wait_running("box", desired_running=True, settle=0.01, timeout=0.05)
        )

        self.assertFalse(timed_out)
        self.assertTrue(data["running"])
        self.assertEqual(data["settledSeconds"], 0.01)
        self.assertEqual(events, [])

    def test_live_coding_commands_parse_without_overriding_stored_code(self) -> None:
        launch = parse_command("launch box")
        launch_batch = parse_command("launch-batch parent child draw --property code --strand scene")
        wait = parse_command("wait-running box --state running --settle 0.5")
        action = parse_command("action box moduleHere_reload --property vertex")

        self.assertIsNone(launch.value)
        self.assertIsNone(launch.file)
        self.assertEqual(launch_batch.uids, ["parent", "child", "draw"])
        self.assertEqual(launch_batch.property, "code")
        self.assertEqual(launch_batch.strand, "scene")
        self.assertEqual(wait.settle, 0.5)
        self.assertEqual(action.action_id, "moduleHere_reload")
        self.assertEqual(action.property, "vertex")

    def test_launch_batch_forwards_one_ordered_native_chord(self) -> None:
        self.client.boxes = {
            "parent": {"uid": "parent", "code": "println(\"parent\")", "runningStrands": {}},
            "child": {"uid": "child", "code": "println(\"child\")", "runningStrands": {}},
        }
        sent: list[tuple[str, dict, dict]] = []

        async def send(name: str, payload: dict, **kwargs) -> None:
            sent.append((name, payload, kwargs))
            for execution in payload["executions"]:
                self.seq += 1
                event = {
                    "seq": self.seq,
                    "name": "is-running",
                    "messageName": "is-running",
                    "uid": execution["uid"],
                    "payload": {
                        "uid": execution["uid"],
                        "strand": execution["strand"],
                        "watermark": 1,
                        "mode": "launching",
                        "isRunning": True,
                    },
                }
                self.client.event_seq = self.seq
                self.client.event_log.append(event)
                self.client._apply_event(event)

        self.client.send_message = send  # type: ignore[method-assign]
        data, events, timed_out = asyncio.run(
            self.client.launch_batch(
                ["parent", "child"],
                property_name="code",
                strand="default",
                timeout=0.05,
            )
        )

        self.assertFalse(timed_out)
        self.assertTrue(data["queued"])
        self.assertEqual(data["acknowledgedUids"], ["parent", "child"])
        self.assertEqual([event["uid"] for event in events], ["parent", "child"])
        self.assertEqual(len(sent), 1)
        name, payload, kwargs = sent[0]
        self.assertEqual(name, "launch-batch")
        self.assertEqual([item["uid"] for item in payload["executions"]], ["parent", "child"])
        self.assertEqual(
            [item["code"] for item in payload["executions"]],
            ["println(\"parent\")", "println(\"child\")"],
        )
        self.assertEqual(kwargs["reply_context"]["command"], "launch-batch")

    def test_launch_batch_rejects_a_duplicate_box(self) -> None:
        with self.assertRaisesRegex(BackdoorError, "same box more than once"):
            asyncio.run(
                self.client.launch_batch(
                    ["box", "box"],
                    property_name="code",
                    strand="default",
                    timeout=0.05,
                )
            )

    def test_set_frame_keeps_the_local_snapshot_in_sync(self) -> None:
        sent: list[tuple[str, dict]] = []

        async def send(name: str, payload: dict, **_kwargs) -> None:
            sent.append((name, payload))

        self.client.send_message = send  # type: ignore[method-assign]
        frame = {"x": 10.0, "y": 20.0, "w": 30.0, "h": 40.0}
        data = asyncio.run(self.client.set_frame("box", frame))

        self.assertEqual(self.client.boxes["box"]["frame"], frame)
        self.assertEqual(data["value"], frame)
        self.assertEqual(sent[0][0], "set-rect")


#: The `output` event a live Field on port 8802 emitted on 2026-09-14 for a box whose
#: `code` was `val x: Int = "this is not an Int"`. Copied verbatim from the reproduction
#: in `dev_docs/agents/AGENT_SURFACE_TRUTHFULNESS.md` rather than invented, because the
#: whole point of these tests is that the packet carrying the diagnostic was already
#: arriving and the `run` branch was not reading it.
COMPILE_ERROR_EVENT = {
    "seq": 35,
    "messageName": "exec_45b94fbcf59d449fa6a7f95021f53483",
    "name": "output",
    "originalName": "output",
    "payload": {
        "uid": "box",
        "line": 1,
        "html": "Initializer type mismatch: expected &#39;Int&#39;, actual &#39;String&#39;.",
        "kind": "error",
        "frame": 30058,
        "startColumn": 12,
        "endLine": 1,
        "endColumn": 13,
    },
    "replyContext": {"uid": "box", "command": "run"},
    "uid": "box",
    "kind": "error",
}


class ExecutionTruthfulnessTests(unittest.TestCase):
    """`run` / `launch` / `continue` must not report success for a box that failed.

    Before 2026-09-14 all three computed `ok = not timed_out` and never looked at the
    events they had just collected, so a box with a hard Kotlin compile error returned
    `ok: true`, `running: false`, process exit 0 -- while the diagnostic sat unread in
    the same result's `events`. `action` (`execution_errors`) and `wait-output`
    (`_execution_error_event`) already graded themselves on exactly these packets.
    """

    def setUp(self) -> None:
        self.client = FieldBackdoor(port=1, workspace=None, default_timeout=0.05, logger=_Logger())
        self.client.boxes["box"] = {"uid": "box", "runningStrands": {}}

    def dispatch(self, command: str, *, events: list[dict], running: bool = False) -> dict:
        from tools.field_backdoor import dispatch_command

        async def fake(uid: str, *, property_name: str, value, timeout: float):
            for event in events:
                self.client.event_log.append(event)
                self.client._apply_event(event)
            data = {"uid": uid, "property": property_name, "codeLength": 63, "running": running}
            return data, events, False

        self.client.run_box = fake  # type: ignore[method-assign]
        self.client.launch_box = fake  # type: ignore[method-assign]
        self.client.continue_box = fake  # type: ignore[method-assign]
        return asyncio.run(dispatch_command(self.client, parse_command(command), command))

    def test_run_reports_failure_for_a_box_that_did_not_compile(self) -> None:
        result = self.dispatch("run box", events=[COMPILE_ERROR_EVENT])

        self.assertFalse(result["ok"])
        self.assertFalse(result["timedOut"])
        self.assertIn("Initializer type mismatch", " ".join(result["data"]["errors"]))
        # `data["error"]` is the single string the two harnesses print (`run_field_tests`'s
        # `collect_execution_problems`, `docs_hooks`'s `require_diagnostic` path).
        self.assertIn("Initializer type mismatch", result["data"]["error"])

    def test_launch_reports_failure_for_a_box_that_did_not_compile(self) -> None:
        result = self.dispatch("launch box", events=[COMPILE_ERROR_EVENT])
        self.assertFalse(result["ok"])
        self.assertIn("Initializer type mismatch", " ".join(result["data"]["errors"]))

    def test_continue_reports_failure_for_a_box_that_did_not_compile(self) -> None:
        result = self.dispatch("continue box", events=[COMPILE_ERROR_EVENT])
        self.assertFalse(result["ok"])

    def test_a_clean_run_is_still_ok(self) -> None:
        output = {
            "seq": 1,
            "name": "set-output",
            "messageName": "set-output",
            "uid": "box",
            "payload": {"uid": "box", "channel": "default", "value": "<svg/>"},
        }
        result = self.dispatch("run box", events=[output])

        self.assertTrue(result["ok"])
        self.assertNotIn("errors", result["data"])

    def test_a_cleared_error_is_not_a_failure(self) -> None:
        """Field clears a stale marker with an empty error at line -1.

        DatalogExecution sends one on every success and ClojureExecution now does too, so
        reading it as a failure would fail every box that merely ran cleanly.
        """
        clear = {
            "seq": 1,
            "name": "output",
            "messageName": "output",
            "uid": "box",
            "payload": {"uid": "box", "kind": "error", "line": -1, "property": "code", "html": ""},
        }
        result = self.dispatch("run box", events=[clear])
        self.assertTrue(result["ok"])

    def test_no_fail_on_error_is_the_documented_escape_hatch(self) -> None:
        """The five smoke tests with `allowExecutionErrors: true` need a way to opt out.

        Same flag name and same meaning as `wait-output --no-fail-on-error`, which has had
        it since the docs-capture silent-timeout fix.
        """
        result = self.dispatch("run box --no-fail-on-error", events=[COMPILE_ERROR_EVENT])

        self.assertTrue(result["ok"])
        # Opting out of the verdict must not suppress the evidence.
        self.assertIn("Initializer type mismatch", " ".join(result["data"]["errors"]))
        self.assertTrue(result["data"]["executionErrorsIgnored"])


class MacroAliasTests(unittest.TestCase):
    def test_macro_and_transcript_are_exact_parser_aliases(self) -> None:
        macro = parse_command("macro run demo --scope project --project-name example")
        transcript = parse_command("transcript run demo --scope project --project-name example")

        self.assertEqual(macro.macro_command, transcript.macro_command)
        self.assertEqual(macro.name, transcript.name)
        self.assertEqual(macro.scope, transcript.scope)
        self.assertEqual(macro.project_name, transcript.project_name)

    def test_successful_macro_run_refreshes_box_index_before_returning(self) -> None:
        client = FieldBackdoor(port=1, workspace=None, default_timeout=0.01, logger=_Logger())
        calls: list[str] = []

        async def reply(*_args, **_kwargs):
            calls.append("reply")
            return {"payload": {"success": True, "applied": 1}}

        async def refresh(*_args, **_kwargs):
            calls.append("snapshot")
            client.boxes["created-by-macro"] = {"uid": "created-by-macro"}
            return {"boxes": list(client.boxes.values())}

        client.call_for_single_reply = reply  # type: ignore[method-assign]
        client.refresh_snapshot = refresh  # type: ignore[method-assign]
        result = asyncio.run(
            client.macro_transcript_command(command="run", name="demo", timeout=0.01)
        )

        self.assertTrue(result["success"])
        self.assertEqual(calls, ["reply", "snapshot"])
        self.assertIn("created-by-macro", client.boxes)


class VideoCaptureParserTests(unittest.TestCase):
    def test_record_box_frame_synchronous_options_round_trip(self) -> None:
        parsed = parse_command(
            "record-box example /tmp/example.mp4 --property animate --duration 9 "
            "--fps 20 --frame-synchronous --restart-target"
        )

        self.assertEqual(parsed.uid, "example")
        self.assertEqual(parsed.path, "/tmp/example.mp4")
        self.assertEqual(parsed.property, "animate")
        self.assertEqual(parsed.duration, 9.0)
        self.assertEqual(parsed.fps, 20)
        self.assertTrue(parsed.frame_synchronous)
        self.assertTrue(parsed.restart_target)


class SmokeCatalogWaiverWiringTests(unittest.TestCase):
    """`allowExecutionErrors` has to reach the backdoor now, not just the grader.

    It used to be purely a grading concession inside `evaluate_expectations`: a box could
    error, `run` still said `ok: true`, and only `collect_execution_problems` -- the one
    thing the flag gates -- would have objected. Since 2026-09-14 `run` itself reports
    `ok: false`, and two checks OUTSIDE that gate would then fail the test: the
    unconditional failed-results loop, and `field_backdoor.py`'s batch loop, which returns
    at the first not-ok command and so would never reach the screenshot.

    Population at the time of writing: 5 tests tree-wide carry the flag (1 in
    public/docs/clojure.md, 4 in public/docs/ray_tracing_examples.md).
    """

    def build(self, *, allow: bool) -> list[str]:
        with tempfile.TemporaryDirectory() as root:
            snippet = Path(root) / "snippet.kt"
            snippet.write_text("print(1)\n", encoding="utf-8")
            cmd, _ = build_backdoor_command(
                {
                    "id": "test.waiver",
                    "runner": "backdoor-box",
                    "snippet": "print(1)",
                    "snippetPath": str(snippet),
                    "timeoutSec": 20.0,
                    "expect": {"allowExecutionErrors": True} if allow else {},
                },
                port=1,
                workspace=None,
            )
        return [cmd[i + 1] for i, value in enumerate(cmd) if value == "--command"]

    def test_a_registered_execution_error_waives_the_run_verdict(self) -> None:
        commands = self.build(allow=True)
        runs = [line for line in commands if line.startswith(("run ", "launch "))]
        self.assertTrue(runs, commands)
        for line in runs:
            self.assertIn("--no-fail-on-error", line)

    def test_nothing_but_the_execution_verb_is_waived(self) -> None:
        """A screenshot or a stop that fails is still a failure, flag or no flag."""
        commands = self.build(allow=True)
        others = [line for line in commands if not line.startswith(("run ", "launch "))]
        for line in others:
            self.assertNotIn("--no-fail-on-error", line, line)

    def test_an_ordinary_test_is_emitted_exactly_as_before(self) -> None:
        for line in self.build(allow=False):
            self.assertNotIn("--no-fail-on-error", line, line)


def _emitted_command(args: list[str]) -> str:
    """The `--command` string run_field_tests.py hands to field_backdoor.py for a jvm-main test."""
    cmd, _ = build_backdoor_command(
        {
            "id": "test.emission",
            "runner": "jvm-main",
            "entrypoint": "afield.examples.Whatever",
            "timeoutSec": 20.0,
            "args": args,
        },
        port=1,
        workspace=None,
    )
    return cmd[cmd.index("--command") + 1]


def _legacy_command(args: list[str]) -> str:
    """The emitter as it stood before flag-like args were supported, reproduced verbatim."""
    parts = ["run-jvm-main", "--timeout", "20.0", "afield.examples.Whatever"]
    for arg in args:
        parts.extend(["--arg", str(arg)])
    return shlex.join(parts)


class JvmMainArgEmissionTests(unittest.TestCase):
    """
    `run_field_tests.py` is shared harness code: every FIELD_TEST and the nightly CI loop run through
    it. When flag-like entrypoint arguments were added (`args: ["--tier", "fast"]`, which argparse used
    to reject with "argument --arg: expected one argument"), the property that mattered was not that the
    new form works — it is that **every pre-existing invocation emits exactly what it emitted before**.
    These tests pin both halves.
    """

    # Every shape a FIELD_TEST has ever passed, plus the awkward ones.
    ORDINARY = [
        [],
        ["/tmp/out"],
        ["/Users/someone/field4/public/docs/assets/agent_transcripts"],
        ["a", "b", "c"],
        ["a path with spaces"],
        ["key=value"],
        ["", "after-an-empty-one"],
        ["quote'and\"quote"],
        ["12"],
        ["semi;colon", "dollar$sign", "star*"],
    ]

    def test_ordinary_args_emit_byte_identically_to_the_legacy_form(self) -> None:
        for args in self.ORDINARY:
            with self.subTest(args=args):
                self.assertEqual(_emitted_command(args), _legacy_command(args))

    def test_flag_like_args_are_now_possible_at_all(self) -> None:
        emitted = _emitted_command(["--tier", "fast"])
        self.assertIn("--arg=--tier", emitted)
        # The legacy form produced a command the backdoor's argparse refused outright. This is the
        # bug itself, pinned: `--arg --tier` is not parseable, so no amount of quoting in the
        # FIELD_TEST annotation could have made a flag-like argument work.
        with self.assertRaises(CommandParseError):
            parse_command(_legacy_command(["--tier", "fast"]))
        # …while the new form parses and yields the two arguments intact.
        self.assertEqual(parse_command(emitted).entry_args, ["--tier", "fast"])

    def test_both_forms_round_trip_to_the_same_entry_args(self) -> None:
        """The point of the `=` form: the parser must see the same list either way."""
        for args in self.ORDINARY + [["--tier", "fast"], ["-x"], ["--out=/tmp/a b"], ["--n", "-3"]]:
            with self.subTest(args=args):
                parsed = parse_command(_emitted_command(args))
                self.assertEqual(parsed.entry_args, [str(a) for a in args])

    def test_non_jvm_main_runners_are_untouched(self) -> None:
        """`args` is a jvm-main concept; a backdoor-box test must not grow one."""
        cmd, _ = build_backdoor_command(
            {
                "id": "test.box",
                "runner": "backdoor-box",
                "boxName": "B",
                "snippet": "1 + 1",
                "timeoutSec": 20.0,
                "scratch": True,
            },
            port=1,
            workspace=None,
        )
        self.assertNotIn("--arg", " ".join(cmd))


if __name__ == "__main__":
    unittest.main()


class CaptureSignatureDocTests(unittest.TestCase):
    """The capture-signature table in FIELD_BACKDOOR.md must match the real parser.

    Why this guard exists: every capture subparser is built with ``add_help=False``, so
    ``native-screenshot --help`` prints the *top-level* usage and an error that reads like a
    complaint about the caller's arguments. An agent with no way to interrogate the CLI falls back
    to guessing, and the guess is always ``--output`` -- the output path is a positional on all of
    these. The doc table is the only place that fact is written down, so it must not be allowed to
    drift away from the code it describes.
    """

    DOC = REPO_ROOT / "dev_docs" / "agents" / "FIELD_BACKDOOR.md"
    BEGIN = "<!-- BEGIN CAPTURE SIGNATURES"
    END = "<!-- END CAPTURE SIGNATURES -->"

    def _documented(self) -> dict[str, tuple[str, set[str]]]:
        text = self.DOC.read_text()
        self.assertIn(self.BEGIN, text, f"{self.DOC} lost its capture-signature table markers")
        table = text.split(self.BEGIN, 1)[1].split(self.END, 1)[0]
        rows: dict[str, tuple[str, set[str]]] = {}
        for line in table.splitlines():
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) != 3 or cells[0].startswith("-") or cells[0] == "command":
                continue
            command = cells[0].strip("`")
            positionals = cells[1].replace("`", "").strip()
            options = {tok for tok in cells[2].replace("`", "").split() if tok.startswith("--")}
            rows[command] = (positionals, options)
        return rows

    def _actual(self, command: str) -> tuple[str, set[str]]:
        parser = build_command_parser()
        subparsers = [
            action for action in parser._actions
            if isinstance(action, argparse._SubParsersAction)
        ]
        self.assertEqual(len(subparsers), 1, "field-backdoor should have exactly one subparser group")
        sub = subparsers[0].choices[command]
        positionals: list[str] = []
        options: set[str] = set()
        for action in sub._actions:
            if action.option_strings:
                options.update(o for o in action.option_strings if o.startswith("--"))
            else:
                positionals.append(
                    f"[{action.dest}]" if action.nargs == "?" else action.dest
                )
        return " ".join(positionals), options

    def test_documented_signatures_match_the_parser(self) -> None:
        documented = self._documented()
        self.assertTrue(documented, "the capture-signature table parsed as empty")
        for command, (positionals, options) in sorted(documented.items()):
            with self.subTest(command=command):
                actual_positionals, actual_options = self._actual(command)
                self.assertEqual(
                    positionals,
                    actual_positionals,
                    f"{command}: doc says positionals '{positionals}', parser says "
                    f"'{actual_positionals}'. Update the table in {self.DOC.name}.",
                )
                self.assertEqual(
                    options,
                    actual_options,
                    f"{command}: doc and parser disagree on options. "
                    f"Only in doc: {sorted(options - actual_options)}; "
                    f"only in parser: {sorted(actual_options - options)}.",
                )

    def test_every_capture_command_is_documented(self) -> None:
        parser = build_command_parser()
        sub = next(
            a for a in parser._actions if isinstance(a, argparse._SubParsersAction)
        )
        capture = {
            name for name in sub.choices
            if name.startswith(("screenshot-", "record-", "native-", "wait-native-"))
        }
        self.assertEqual(
            capture,
            set(self._documented()),
            "a capture command exists that the FIELD_BACKDOOR.md table does not list "
            "(or vice versa) -- an undocumented one is a command an agent will guess at",
        )

    def test_no_capture_command_accepts_an_output_flag(self) -> None:
        """The mistake this whole guard is about: there is no --output anywhere."""
        for command in self._documented():
            with self.subTest(command=command):
                _, options = self._actual(command)
                self.assertFalse(
                    options & {"--output", "--out", "--path", "--file"},
                    f"{command} grew an output-style flag; the doc says the path is positional",
                )

    QUICK_REF = REPO_ROOT / "dev_docs" / "agents" / "AGENT_QUICK_REF.md"
    _ROLE = {"path": "path", "box": "uid"}

    def _quick_ref_positionals(self, line: str) -> list[str]:
        """The positional region of a terse usage line: everything before the first option.

        `screenshot-window <path> [--uid <box>]` has ONE positional -- the `<box>` belongs to
        `--uid`. Stopping at the first `[`/`--` token is what keeps a flag's value from being
        counted as a positional.
        """
        out: list[str] = []
        for token in line.split()[1:]:
            if token.startswith("--") or token.startswith("[--"):
                break
            match = re.fullmatch(r"\[?<(\w+)>\]?", token)
            if not match:
                break
            role = self._ROLE.get(match.group(1))
            if role is None:
                break
            out.append(f"[{role}]" if token.startswith("[") else role)
        return out

    def test_quick_ref_usage_lines_match_the_parser(self) -> None:
        """AGENT_QUICK_REF.md carries the same signatures in a terser form.

        It had them right when FIELD_BACKDOOR.md had only prose, and then drifted: the combined
        ``record-window / record-box / record-editor <path>`` line was wrong for two of the three,
        because -box and -editor take the uid as their first positional. Two documents stating the
        same fact need one of them checked, or they diverge exactly like that.
        """
        text = self.QUICK_REF.read_text()
        section = text.split("## Screenshots & video", 1)
        self.assertEqual(len(section), 2, "AGENT_QUICK_REF.md lost its Screenshots & video section")
        block = section[1].split("```", 2)
        self.assertGreaterEqual(len(block), 3, "the usage code block went missing")
        parser = build_command_parser()
        sub = next(a for a in parser._actions if isinstance(a, argparse._SubParsersAction))
        checked = 0
        for raw in block[1].splitlines():
            line = raw.split("#", 1)[0].strip()
            tokens = line.split()
            if not tokens or tokens[0] not in sub.choices:
                continue
            command = tokens[0]
            expected, _ = self._actual(command)
            self.assertEqual(
                " ".join(self._quick_ref_positionals(line)),
                expected,
                f"{command}: quick ref line '{line}' does not show the parser's "
                f"positionals '{expected}'",
            )
            checked += 1
        self.assertGreaterEqual(checked, 7, f"only {checked} quick-ref usage lines were recognised")

    def test_a_subcommand_cannot_report_its_own_usage(self) -> None:
        """The reproducer for FIELD_BACKDOOR.md's claim that ``<command> --help`` is not self-describing.

        Every subparser is built ``add_help=False``, so argparse treats ``--help`` as an
        unrecognised argument and answers with the *top-level* usage plus an error that reads like a
        complaint about the caller's arguments. That is what leaves a reader with no way to check the
        argument shape from the CLI, and the guess it invites is ``--output``.
        """
        parser = build_command_parser()
        sub = next(a for a in parser._actions if isinstance(a, argparse._SubParsersAction))
        for command in sorted(self._documented()):
            with self.subTest(command=command):
                self.assertFalse(
                    sub.choices[command].add_help,
                    f"{command} now answers --help on its own; the claim in FIELD_BACKDOOR.md that "
                    f"a subcommand cannot report its usage is stale and should be removed",
                )
