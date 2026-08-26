"""Behavioural gate for `field.sh`'s launcher argument parsing, across every shell it claims.

`field.sh` is `#!/bin/sh` and 186 lines of it — the whole workspace-resolution layer — arrived on
2026-08-25 in commit bafa9ee5f, whose own message records "NOT run: field.sh. No launch, no
--print-workspace, no argument-parsing check on any of the three shells it has to satisfy. Its
README and AGENTS.md prose therefore describes intent that nobody has watched work." This module
is that check. Running it found two live defects (both fixed in the same commit that added this
file), and neither was in the resolution logic itself:

  * the macOS no-tty path dropped every argument. `run_in_terminal` tries four X11 terminal
    emulators and falls back to `run_field` — with no `"$@"`. None of those four exists on macOS,
    so on a Mac invoked without a tty the script printed `field: workspace X (from the command
    line)` and then launched a backend carrying no `--workspace`, which opened whatever
    `~/.afield/last-workspace.txt` said. Announcing one workspace and opening another is the exact
    confusion `--print-workspace` was added to end.
  * `local terminal_cmd=""` — `local` is not POSIX, ksh93 has no such builtin, and the variable was
    never read. Every ksh launch printed `field.sh: line NNN: local: not found`.

How this runs the real thing. `field.sh` is copied byte-for-byte into a sandbox (the copy is
sha256-checked against the original, so this can never drift into testing a paraphrase) beside a
fake `gradlew`, a fake `build/field-launch/java` that records its argv, and a `HOME` of its own.
Every `AFIELD_SKIP_*` escape the script already documents is used to keep it out of Gradle. What is
exercised is therefore the shipped script, not a re-implementation of its `case` block.

What this module cannot prove, and where that is proved instead: it drives the real script against a
*fake* `java`, so it establishes the argv the backend is handed and stops there. "Field started and
opened that workspace" needs a real backend, and the live A/B that settles it — the pre-fix launcher
announcing a temp directory and opening the developer's real 493-document workspace — is recorded in
`dev_docs/launcher_workspace_verification.md`, with the recipe for repeating it.

Two invariants beyond the individual cases:

  * `test_every_launcher_option_is_covered_by_the_ledger` is bidirectional. It parses the option
    patterns out of `field.sh`'s own `case "$arg" in` block and requires that set to *equal* the
    set the ledger names. A new launcher flag with no test fails; a ledger entry for a flag that
    has been removed fails too. A one-directional check would let either drift through.
  * `test_field_sh_never_writes_the_machine_wide_preference_file`. The workspace choice is
    remembered in `~/.afield/last-workspace.txt`, which is machine-wide: it decides what the next
    bare `./field.sh` in *any* clone opens. `field.sh` only ever reads it (the backend writes it,
    Server.kt), and that has to stay true, because a launcher that wrote it would mean every
    `--print-workspace` and every failed typo-check silently re-pointed the developer's next
    launch. The whole suite also runs under a sandbox `HOME` and asserts nothing appeared there.

Run with:  python -m unittest tools.test_field_sh_launcher
"""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
FIELD_SH = REPO_ROOT / "field.sh"

# The shells `field.sh` has to satisfy. `#!/bin/sh` names the first; AGENTS.md and README.md hand
# the command to humans, whose interactive shell is bash or zsh on the machines this ships to; dash
# is the strictest POSIX reading available and ksh93 is the one that rejects `local`. A shell that
# is not installed is reported, not skipped: silently dropping it is how "runs on five shells"
# decays into "runs on whichever two this laptop has".
DECLARED_SHELLS = ("sh", "bash", "zsh", "dash", "ksh")

# The option patterns `field.sh`'s own parser spells out, keyed by the ledger name used below. The
# two catch-alls are the fall-through arms, not flags, and are covered by the passthrough cases.
COVERED_OPTIONS = {
    "-h|--help",
    "--print-workspace",
    "-w|--workspace",
    "--workspace=*",
    "-*",
    "*",
}


def _resolved_shells() -> dict[str, str]:
    return {name: shutil.which(name) or "" for name in DECLARED_SHELLS}


class FieldShSandbox:
    """A copy of `field.sh` with everything below the argument parser stubbed out.

    The stubs are chosen so that `run_field` reaches its `"$JAVA_BIN" @args "$@"` line and no
    further: `AFIELD_SKIP_BUILD=1` with a present `build/field-launch/args` short-circuits
    `build_field`, `AFIELD_SKIP_TOOLCHAIN_PROVISION=1` short-circuits the GraalVM download,
    `AFIELD_CODE_USE_SEMANTIC_SIDECAR=off` the sidecar, a `.flag` under the sandbox HOME the JVM
    wrapper, and an absent `.gitattributes` the Git LFS check. `build/field-launch/electron` is
    deliberately absent so `launch_electron_when_ready` returns immediately.
    """

    def __init__(self, root: Path) -> None:
        self.root = root
        self.checkout = root / "checkout"
        self.home = root / "home"
        self.workspace = root / "ws"
        self.document = self.workspace / "docA"
        self.invoked_from = root / "cwd"
        self.argv_file = root / "argv.txt"

        for directory in (self.checkout, self.document, self.invoked_from):
            directory.mkdir(parents=True, exist_ok=True)
        (self.home / ".field4" / "gradle-jvm" / "provisioned").mkdir(parents=True, exist_ok=True)
        (self.home / ".field4" / "gradle-jvm" / "provisioned" / ".flag").write_text("")
        (self.document / "metadata.edn").write_text("{:version 2 :boxes []}\n")

        script = self.checkout / "field.sh"
        script.write_bytes(FIELD_SH.read_bytes())
        script.chmod(0o755)
        assert hashlib.sha256(script.read_bytes()).hexdigest() == hashlib.sha256(
            FIELD_SH.read_bytes()
        ).hexdigest(), "the sandbox copy of field.sh is not the shipped field.sh"

        # ensure_graalvm_toolchain reads this even when provisioning is skipped on some paths.
        (self.checkout / "build.gradle.kts").write_text('val graalvmVersion = "25.0.2"\n')
        gradlew = self.checkout / "gradlew"
        gradlew.write_text("#!/bin/sh\necho \"stub gradlew $*\"\n")
        gradlew.chmod(0o755)

        launch = self.checkout / "build" / "field-launch"
        launch.mkdir(parents=True, exist_ok=True)
        fake_java = self.root / "fake-java.sh"
        fake_java.write_text(
            "#!/bin/sh\n"
            f'for a in "$@"; do printf "%s\\n" "$a"; done > "{self.argv_file}"\n'
        )
        fake_java.chmod(0o755)
        (launch / "java").write_text(f"{fake_java}\n")
        (launch / "args").write_text("-Dstub=1\n")

    @property
    def preference_file(self) -> Path:
        return self.home / ".afield" / "last-workspace.txt"

    def run(self, shell: str, *args: str, no_terminal: bool = True) -> subprocess.CompletedProcess:
        environment = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self.home),
            "AFIELD_SKIP_BUILD": "1",
            "AFIELD_SKIP_TOOLCHAIN_PROVISION": "1",
            "AFIELD_CODE_USE_SEMANTIC_SIDECAR": "off",
        }
        if no_terminal:
            environment["AFIELD_FIELD_SH_NO_TERMINAL"] = "1"
        if self.argv_file.exists():
            self.argv_file.unlink()
        return subprocess.run(
            [shell, str(self.checkout / "field.sh"), *args],
            cwd=self.invoked_from,
            env=environment,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=120,
        )

    def backend_argv(self) -> list[str]:
        """What reached the backend, minus the fixed `@args` file the script always passes first."""
        if not self.argv_file.exists():
            return []
        argv = self.argv_file.read_text().splitlines()
        assert argv and argv[0].startswith("@"), f"unexpected first backend argument: {argv[:1]}"
        return argv[1:]


class FieldShLauncherTests(unittest.TestCase):
    sandbox_root: tempfile.TemporaryDirectory
    sandbox: FieldShSandbox

    @classmethod
    def setUpClass(cls) -> None:
        cls.sandbox_root = tempfile.TemporaryDirectory(prefix="field-sh-launcher-")
        # .resolve() matters on macOS: mkdtemp hands back /var/folders/..., a symlink to
        # /private/var/folders/.... field.sh's `cd "$RAW" && pwd` reports the logical path it was
        # given, so an absolute argument comes back unresolved while a path resolved against
        # $PWD (the relative case) comes back physical. Anchoring the sandbox at the physical path
        # makes the two agree without the test having to paper over the difference.
        cls.sandbox = FieldShSandbox(Path(cls.sandbox_root.name).resolve())

    @classmethod
    def tearDownClass(cls) -> None:
        cls.sandbox_root.cleanup()

    # --- premises -------------------------------------------------------------------------------
    #
    # Each of these asserts something this module would otherwise assume. Without them a rewrite of
    # field.sh that deleted the feature under test would leave every case below passing vacuously.

    def test_premise_field_sh_is_a_posix_sh_script_with_a_launcher_parser(self) -> None:
        text = FIELD_SH.read_text()
        self.assertTrue(text.startswith("#!/bin/sh"), "field.sh is no longer #!/bin/sh")
        for fragment in (
            'INVOKED_FROM="$PWD"',
            'WORKSPACE_PREFERENCE_FILE="$HOME/.afield/last-workspace.txt"',
            "--print-workspace",
            "resolve_workspace_request()",
            'case "$arg" in',
        ):
            self.assertIn(fragment, text, f"field.sh no longer contains {fragment!r}")

    def test_premise_every_declared_shell_is_installed(self) -> None:
        missing = sorted(name for name, path in _resolved_shells().items() if not path)
        self.assertEqual(
            missing,
            [],
            f"shells declared in DECLARED_SHELLS but not installed: {missing}. Install them or "
            "drop them from the ledger — do not let the suite quietly stop covering them.",
        )

    def test_every_launcher_option_is_covered_by_the_ledger(self) -> None:
        """Bidirectional: field.sh's own `case` arms must equal the ledger, in both directions."""
        text = FIELD_SH.read_text()
        block = re.search(
            r'^argc=\$#$.*?^done$', text, re.S | re.M
        )
        self.assertIsNotNone(block, "could not find field.sh's argument-rebuilding loop")
        arms = set(re.findall(r"^\s{8}([-\w|*=]+)\)$", block.group(0), re.M))
        self.assertNotEqual(arms, set(), "found the loop but parsed no `case` arms out of it")
        self.assertEqual(
            arms,
            COVERED_OPTIONS,
            "field.sh's launcher options and this module's ledger have diverged: "
            f"only in field.sh {sorted(arms - COVERED_OPTIONS)}, "
            f"only in the ledger {sorted(COVERED_OPTIONS - arms)}",
        )

    # --- workspace resolution, under every shell ------------------------------------------------

    def test_print_workspace_resolves_every_spelling_on_every_shell(self) -> None:
        sandbox = self.sandbox
        expected = str(sandbox.workspace)
        cases = {
            "absolute path": [str(sandbox.workspace)],
            # field.sh cd's to $DIR on line 5, so a relative path only works because INVOKED_FROM
            # is captured before that. `cwd/` is a sibling of `ws/`, so `../ws` resolving to the
            # workspace is the whole point: against $DIR it would resolve inside the checkout.
            "relative to the invoking directory": ["../ws"],
            "-w spelling": ["-w", str(sandbox.workspace)],
            "--workspace spelling": ["--workspace", str(sandbox.workspace)],
            "--workspace= spelling": [f"--workspace={sandbox.workspace}"],
            # A workspace's documents are its subdirectories, so naming a document has to open the
            # folder above it or the workspace merely looks empty.
            "document folder holding a metadata.edn": [str(sandbox.document)],
            "the metadata.edn file itself": [str(sandbox.document / "metadata.edn")],
        }
        for shell_name, shell in _resolved_shells().items():
            for label, arguments in cases.items():
                with self.subTest(shell=shell_name, case=label):
                    result = sandbox.run(shell, "--print-workspace", *arguments)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(result.stdout.strip(), expected, result.stderr)

    def test_tilde_is_expanded_against_the_sandbox_home(self) -> None:
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(shell, "--print-workspace", "~")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), str(sandbox.home))

    def test_a_typo_is_rejected_before_gradle_on_every_shell(self) -> None:
        """The point of parsing in the launcher: fail in milliseconds, not after minutes of build."""
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(shell, str(sandbox.root / "no-such-workspace"))
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertIn("there is no workspace directory at", result.stderr)
                self.assertIn("mkdir -p", result.stderr)
                self.assertNotIn("stub gradlew", result.stdout)
                self.assertEqual(sandbox.backend_argv(), [])

    def test_a_workspace_flag_with_no_value_is_rejected(self) -> None:
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            for flag in ("-w", "--workspace"):
                with self.subTest(shell=shell_name, flag=flag):
                    # Also with a pushed argument ahead of it, which is where an off-by-one in the
                    # `consumed`/`argc` bookkeeping would show up as re-reading a pushed value.
                    result = sandbox.run(shell, "--already-pushed", flag)
                    self.assertEqual(result.returncode, 2, result.stdout)
                    self.assertIn(f"field: {flag} needs a directory", result.stderr)

    def test_the_reported_origin_distinguishes_all_three_sources(self) -> None:
        sandbox = self.sandbox
        shell = _resolved_shells()["sh"]

        result = sandbox.run(shell, str(sandbox.workspace))
        self.assertIn("(from the command line)", result.stderr)

        self.assertFalse(sandbox.preference_file.exists())
        result = sandbox.run(shell)
        self.assertIn("(the default; nothing recorded yet)", result.stderr)
        self.assertIn(str(sandbox.home / "Documents" / "AFieldWorkspace"), result.stderr)

        sandbox.preference_file.parent.mkdir(parents=True, exist_ok=True)
        try:
            sandbox.preference_file.write_text(f"  {sandbox.workspace}  \n")
            result = sandbox.run(shell, "--print-workspace")
            self.assertEqual(result.stdout.strip(), str(sandbox.workspace))
            result = sandbox.run(shell)
            self.assertIn("last used, per", result.stderr)
        finally:
            sandbox.preference_file.unlink()

    def test_naming_a_document_says_so_on_the_way_past(self) -> None:
        sandbox = self.sandbox
        result = sandbox.run(_resolved_shells()["sh"], str(sandbox.document))
        self.assertIn("is a document, not a workspace", result.stderr)
        self.assertIn("open docA from the welcome page", result.stderr)
        self.assertEqual(sandbox.backend_argv(), ["--workspace", str(sandbox.workspace)])

    # --- what reaches the backend ---------------------------------------------------------------

    def test_backend_argv_is_exact_on_every_shell(self) -> None:
        """Argument parsing is POSIX sh with no arrays, so `"$@"` is rebuilt in place by shifting
        off the front and pushing onto the back for exactly $argc iterations. Nothing pushed may be
        re-read, and nothing consumed may reappear. These are the cases where that goes wrong."""
        sandbox = self.sandbox
        workspace = str(sandbox.workspace)
        cases = {
            "bare workspace": (
                [workspace],
                ["--workspace", workspace],
            ),
            "workspace then a backend flag and its value": (
                [workspace, "--port", "1234"],
                ["--port", "1234", "--workspace", workspace],
            ),
            # Once a backend flag has gone past, a bare word is more likely to be that flag's value
            # than a workspace — so it is forwarded and no --workspace is appended at all.
            "bare word after a backend flag is not the workspace": (
                ["--port", "1234", workspace],
                ["--port", "1234", workspace],
            ),
            "-w in the middle of backend flags": (
                ["--alpha", "-w", workspace, "--beta", "bval"],
                ["--alpha", "--beta", "bval", "--workspace", workspace],
            ),
            "--workspace= plus a flag carrying a value": (
                [f"--workspace={workspace}", "--debug", "extraval"],
                ["--debug", "extraval", "--workspace", workspace],
            ),
            "no workspace named: nothing is appended": (
                ["--solo"],
                ["--solo"],
            ),
            # --print-workspace exits before the backend runs, so it must never be forwarded.
            "print-workspace is consumed, not forwarded": (
                ["--print-workspace", workspace],
                [],
            ),
        }
        for shell_name, shell in _resolved_shells().items():
            for label, (arguments, expected) in cases.items():
                with self.subTest(shell=shell_name, case=label):
                    sandbox.run(shell, *arguments)
                    self.assertEqual(sandbox.backend_argv(), expected)

    def test_the_no_tty_fallback_still_carries_the_workspace(self) -> None:
        """The regression this module was written for.

        With no tty and no AFIELD_FIELD_SH_NO_TERMINAL, field.sh goes to run_in_terminal, which
        tries four X11 terminal emulators and falls back to running directly. None of those four
        exists on macOS, so this is the ordinary macOS no-tty path — an agent's shell tool, a
        launchd job, anything with stdin closed. It used to call `run_field` with no arguments, so
        the script announced a workspace on stderr and then launched a backend that opened a
        different one.
        """
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(
                    shell, str(sandbox.workspace), "--myflag", no_terminal=False
                )
                self.assertIn(f"field: workspace {sandbox.workspace}", result.stderr)
                self.assertEqual(
                    sandbox.backend_argv(),
                    ["--myflag", "--workspace", str(sandbox.workspace)],
                    "the workspace field.sh announced did not reach the backend",
                )

    def test_no_shell_emits_diagnostics_of_its_own(self) -> None:
        """`local` is not POSIX and `2>/dev/null` on `stty` does not cover a failed `</dev/tty`.

        Both produced shell-voice noise on the launch path — "local: not found" on ksh93, "/dev/tty:
        Device not configured" on the other four — which is how a script that works still reads as
        broken.
        """
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(
                    shell, str(sandbox.workspace), no_terminal=False
                )
                noise = [
                    line
                    for line in result.stderr.splitlines()
                    if line.strip() and not line.startswith("field:")
                ]
                self.assertEqual(noise, [], f"{shell_name} printed diagnostics of its own: {noise}")

    # --- the machine-wide preference file -------------------------------------------------------

    def test_field_sh_never_writes_the_machine_wide_preference_file(self) -> None:
        text = FIELD_SH.read_text()
        reads = re.findall(r'.*\$WORKSPACE_PREFERENCE_FILE.*|.*"\$\{WORKSPACE_PREFERENCE_FILE\}".*', text)
        self.assertNotEqual(reads, [], "field.sh no longer mentions the preference file at all")
        for line in reads:
            self.assertNotRegex(
                line.strip(),
                r'>\s*"?\$\{?WORKSPACE_PREFERENCE_FILE',
                "field.sh writes ~/.afield/last-workspace.txt. Only the backend (Server.kt) may: a "
                "launcher that wrote it would re-point every other clone's next bare launch, "
                f"including from a --print-workspace that never started anything. Offending: {line}",
            )

    def test_the_suite_left_no_preference_file_behind(self) -> None:
        """Belt to the braces above: every invocation ran under a sandbox HOME, and nothing wrote
        there. If this ever fails, the developer's real ~/.afield/last-workspace.txt was at risk."""
        self.assertFalse(
            self.sandbox.preference_file.exists(),
            f"{self.sandbox.preference_file} exists — something in the launcher wrote it",
        )


if __name__ == "__main__":
    unittest.main()
