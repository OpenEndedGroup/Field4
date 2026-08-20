import asyncio
import shlex
import sys
import unittest
from pathlib import Path

# `run_field_tests.py` imports its siblings by bare name (`from discover_field_tests import ...`), the
# way it does when run as a script from `tools/`. Put that directory on the path before importing it
# under its package name, rather than reaching into the module's own imports.
REPO_PATH = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_PATH))
sys.path.insert(0, str(REPO_PATH / "tools"))

from tools.field_backdoor import BackdoorError, CommandParseError, FieldBackdoor, REPO_ROOT, parse_command
from tools.verification_runtime import automation_workspace

from tools.run_field_tests import build_backdoor_command  # noqa: E402  (path set up just above)


class _Logger:
    session_id = "field-backdoor-test"

    def log(self, _event: str, **_fields) -> None:
        pass


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
