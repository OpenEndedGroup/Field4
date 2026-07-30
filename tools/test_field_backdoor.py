import asyncio
import shlex
import sys
import unittest
from pathlib import Path

from tools.field_backdoor import BackdoorError, CommandParseError, FieldBackdoor, parse_command

# `run_field_tests.py` imports its siblings by bare name (`from discover_field_tests import ...`), the
# way it does when run as a script from `tools/`. Put that directory on the path before importing it
# under its package name, rather than reaching into the module's own imports.
sys.path.insert(0, str(Path(__file__).resolve().parent))

import tools.run_field_tests as rft  # noqa: E402  (path set up just above)
from tools.run_field_tests import build_backdoor_command  # noqa: E402


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


class _FakePs:
    """Stands in for `subprocess.run(["ps", ...])` with a fixed process table."""

    def __init__(self, table: str) -> None:
        self.stdout = table

    def __call__(self, cmd, **_kwargs):
        return self


# One Field JVM per checkout, exactly as `ps -eo pid=,command=` renders them: argv[0] is the
# java binary and the worktree shows up in the -D flags.
def _field_jvm(pid: int, root: str, main: str = "afield.Server") -> str:
    return (
        f"{pid} /jdks/graalvm/bin/java "
        f"-Dableton.link.library={root}/libs/libabl_link_jvm.dylib "
        f"-Dafield.aot.cache.path={root}/build/aot/field.aot -cp {root}/build/classes {main}"
    )


class ReapScopeTests(unittest.TestCase):
    """Backlog #20: reaping must not reach into a sibling worktree or the user's live Field."""

    OURS = "/Users/marc/field4-worktrees/2026-07-29b-ci-warnings"
    SIBLING = "/Users/marc/field4-worktrees/2026-07-28-ci-warnings"
    PRIMARY = "/Users/marc/field4"

    def _table(self) -> str:
        return "\n".join([
            _field_jvm(101, self.OURS),
            _field_jvm(202, self.SIBLING),
            _field_jvm(303, self.PRIMARY),
            _field_jvm(404, self.OURS, main="afield.AgentJvmMainLauncher"),
            # A Gradle daemon and a shell that merely mentions the marker: never reapable.
            "505 /jdks/graalvm/bin/java -cp /gradle org.gradle.launcher.daemon.bootstrap.GradleDaemon",
            "606 /bin/zsh -c grep afield.Server foo",
        ])

    def setUp(self) -> None:
        self._real_run = rft.subprocess.run
        rft.subprocess.run = _FakePs(self._table())
        self._real_ancestors = rft._ancestor_pids
        rft._ancestor_pids = lambda: set()
        self._real_root = rft.REPO_ROOT
        rft.REPO_ROOT = Path(self.OURS)

    def tearDown(self) -> None:
        rft.subprocess.run = self._real_run
        rft._ancestor_pids = self._real_ancestors
        rft.REPO_ROOT = self._real_root

    def test_repo_scope_reaps_only_this_checkout(self) -> None:
        pids = rft._stale_field_jvm_pids(exclude=set(), scope="repo")
        self.assertEqual(sorted(pids), [101, 404])

    def test_repo_scope_spares_sibling_worktree_and_primary_tree(self) -> None:
        """The 2026-07-28 mutual destruction: a sibling agent session and the user's live app."""
        pids = rft._stale_field_jvm_pids(exclude=set(), scope="repo")
        self.assertNotIn(202, pids)
        self.assertNotIn(303, pids)

    def test_all_scope_preserves_the_old_machine_wide_behaviour(self) -> None:
        pids = rft._stale_field_jvm_pids(exclude=set(), scope="all")
        self.assertEqual(sorted(pids), [101, 202, 303, 404])

    def test_neither_scope_touches_gradle_daemons_or_shells(self) -> None:
        for scope in ("repo", "all"):
            pids = rft._stale_field_jvm_pids(exclude=set(), scope=scope)
            self.assertNotIn(505, pids, "Gradle daemon must survive")
            self.assertNotIn(606, pids, "a shell mentioning the marker must survive")

    def test_exclude_wins_over_scope(self) -> None:
        """The port holder is left to recycle_field even though it is ours."""
        pids = rft._stale_field_jvm_pids(exclude={101}, scope="repo")
        self.assertEqual(pids, [404])

    def test_scope_defaults_to_repo_and_rejects_junk(self) -> None:
        import os
        for value, expected in [(None, "repo"), ("", "repo"), ("repo", "repo"),
                                ("all", "all"), ("ALL", "all"), ("sideways", "repo")]:
            with self.subTest(value=value):
                if value is None:
                    os.environ.pop("AFIELD_TEST_REAP_SCOPE", None)
                else:
                    os.environ["AFIELD_TEST_REAP_SCOPE"] = value
                self.assertEqual(rft._reap_scope(), expected)
        os.environ.pop("AFIELD_TEST_REAP_SCOPE", None)


class CompetingRunnerTests(unittest.TestCase):
    """Backlog #20's other half: say so when a second runner is live."""

    TABLE = "\n".join([
        "111 /usr/bin/python3 /repo/tools/run_field_tests.py --refresh-catalog",
        "222 /usr/bin/python3 /repo/tools/field_backdoor.py --command snapshot",
        "333 /bin/zsh -c 'python tools/run_field_tests.py'",
        "444 /usr/bin/python3 /repo/tools/other_thing.py",
    ])

    def setUp(self) -> None:
        self._real_run = rft.subprocess.run
        rft.subprocess.run = _FakePs(self.TABLE)
        self._real_ancestors = rft._ancestor_pids
        rft._ancestor_pids = lambda: set()

    def tearDown(self) -> None:
        rft.subprocess.run = self._real_run
        rft._ancestor_pids = self._real_ancestors

    def test_finds_other_runners_and_backdoors(self) -> None:
        pids = [pid for pid, _ in rft._competing_runner_pids()]
        self.assertEqual(sorted(pids), [111, 222])

    def test_ignores_shells_and_unrelated_python(self) -> None:
        pids = [pid for pid, _ in rft._competing_runner_pids()]
        self.assertNotIn(333, pids, "a shell quoting the script name is not a runner")
        self.assertNotIn(444, pids, "an unrelated python script is not a runner")

    def test_excludes_our_own_process_chain(self) -> None:
        rft._ancestor_pids = lambda: {111}
        pids = [pid for pid, _ in rft._competing_runner_pids()]
        self.assertEqual(pids, [222])


if __name__ == "__main__":
    unittest.main()
