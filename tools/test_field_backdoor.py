import argparse
import asyncio
import re
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

from tools.field_backdoor import (
    BackdoorError,
    CommandParseError,
    FieldBackdoor,
    REPO_ROOT,
    build_command_parser,
    parse_command,
)
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
        wait = parse_command("wait-running box --state running --settle 0.5")
        action = parse_command("action box moduleHere_reload --property vertex")

        self.assertIsNone(launch.value)
        self.assertIsNone(launch.file)
        self.assertEqual(wait.settle, 0.5)
        self.assertEqual(action.action_id, "moduleHere_reload")
        self.assertEqual(action.property, "vertex")

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
