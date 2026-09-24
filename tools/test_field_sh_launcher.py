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

The same module now covers `--document`, which arrived later and rides on the same parser: a
document is a *subdirectory* of a workspace, so naming one settles both, and the launcher resolves
it to the bare `--document <name>` the backend understands. Its own reason to exist out here is
sharper than the workspace flag's: Field *creates* a document that does not exist yet, so an
unchecked typo does not fail — it opens an empty new document under that name and says nothing.
`test_a_document_that_does_not_exist_is_rejected_before_gradle` is that check, and it also asserts
the launcher did not create what it was rejecting.

What the flag is *for* — arriving in that document instead of the welcome page — is the backend's
half, and no fake `java` can show it. `test_premise_the_backend_reads_the_document_argument` pins
the seam instead: the `option("document")` the launcher's argv feeds, and `/app-url` (what the
launcher curls to decide the Electron window's URL) preferring it over both the welcome page and
the last-opened document.

`--read-only` (commit 8cabbf40e) is the third rider on that parser, and it is the one with a `case`
arm it did not need: an unrecognised `-*` would already have been forwarded to the backend. It has
an arm because two things downstream read the variable it sets — the launch banner, and the inner
command line `run_in_terminal` rebuilds from `WORKSPACE_RELAUNCH_ARGS` rather than from `"$@"`.
`test_read_only_reaches_the_backend_in_every_spelling` covers the argv, including the consequence
of that arm (it does not set `passthrough_seen`, so a bare workspace *after* the flag is still the
workspace); `test_read_only_survives_the_terminal_relaunch` drives the re-exec, which is the path
that had already dropped the workspace once. The backend's half —
`test_premise_the_backend_refuses_to_save_when_launched_read_only` — is the `flag("read-only")` the
argv feeds and the refusal at the `SavingContext.save` funnel it turns into.

Two invariants beyond the individual cases:

  * `test_every_launcher_option_is_covered_by_the_ledger` is bidirectional. It parses the option
    patterns out of `field.sh`'s own `case "$arg" in` block and requires that set to *equal* the
    set the ledger names. A new launcher flag with no test fails; a ledger entry for a flag that
    has been removed fails too. A one-directional check would let either drift through. The same
    ledger feeds `test_the_powershell_launcher_declares_the_same_options`, so one entry is what
    holds `field.ps1` level with `field.sh` — a flag cannot land on one launcher alone.
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
FIELD_PS1 = REPO_ROOT / "field.ps1"
SERVER_KT = REPO_ROOT / "src" / "jvmMain" / "kotlin" / "afield" / "Server.kt"
SAVING_KT = REPO_ROOT / "src" / "jvmMain" / "kotlin" / "afield" / "saving.kt"

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
    "-d|--document",
    "--document=*",
    "--read-only",
    "--offline",
    "-*",
    "*",
}


def _declared_option_spellings() -> set[str]:
    """The individual option spellings the ledger names, for a launcher that is not `field.sh`.

    Every ledger entry but the two catch-alls is a `case` pattern, which is the shell's spelling
    of an option rather than the option itself: `-h|--help` is two spellings of one flag, and
    `--workspace=*` is one whose value is glued on. Unpicking them here, instead of restating them
    by hand in the PowerShell check below, is what makes a single ledger entry gate *both*
    launchers. The hand-maintained list this replaced is why `--read-only` diverged: it reached
    field.sh in 8cabbf40e, field.ps1's parser never grew an arm, and the only test that could have
    said so did not know the flag existed.
    """
    spellings = set()
    for pattern in COVERED_OPTIONS:
        if pattern in ("-*", "*"):
            continue
        for alternative in pattern.split("|"):
            spellings.add(alternative.rstrip("*"))
    return spellings


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
        # welcomeDirCandidates accepts either metadata name, so both have to count as a document
        # here — a workspace's metadata.json documents are listed on the welcome page like any
        # other, and a launcher that did not recognise them would reject a name that works.
        self.json_document = self.workspace / "docJson"
        # A directory in the workspace that is not a document: what `-d <path>` has to refuse.
        self.plain_directory = self.workspace / "notADocument"
        self.invoked_from = root / "cwd"
        self.argv_file = root / "argv.txt"
        # The terminal-relaunch path, off unless a case asks for it. `run_in_terminal` only
        # reaches WORKSPACE_RELAUNCH_ARGS when one of the four X11 emulators it looks for is on
        # PATH, and none of them exists on macOS — so the cases that want the direct fallback
        # get PATH as it is, and the one that wants the re-exec passes terminal_on_path=True.
        self.terminal_bin = root / "fake-terminal"
        self.terminal_argv_file = root / "terminal-argv.txt"
        self.relaunch_output_file = root / "relaunch-output.txt"

        for directory in (
            self.checkout,
            self.document,
            self.json_document,
            self.plain_directory,
            self.invoked_from,
        ):
            directory.mkdir(parents=True, exist_ok=True)
        (self.home / ".field4" / "gradle-jvm" / "provisioned").mkdir(parents=True, exist_ok=True)
        (self.home / ".field4" / "gradle-jvm" / "provisioned" / ".flag").write_text("")
        (self.document / "metadata.edn").write_text("{:version 2 :boxes []}\n")
        (self.json_document / "metadata.json").write_text('{"version": 2, "boxes": []}\n')

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
            'sleep "${AFIELD_TEST_FAKE_JAVA_DELAY:-0}"\n'
            f'for a in "$@"; do printf "%s\\n" "$a"; done > "{self.argv_file}"\n'
        )
        fake_java.chmod(0o755)
        (launch / "java").write_text(f"{fake_java}\n")
        (launch / "args").write_text("-Dstub=1\n")

        # gnome-terminal is the first emulator run_in_terminal looks for, so this one wins even
        # on a machine that has a real emulator installed. It records the argv it was handed and
        # then runs the inner command, as the real emulator would: WORKSPACE_RELAUNCH_ARGS is a
        # *string* that a second shell re-splits, so running it is the only way to find out that
        # a workspace path is single-quoted in it and a bare flag is not.
        self.terminal_bin.mkdir(parents=True, exist_ok=True)
        emulator = self.terminal_bin / "gnome-terminal"
        emulator.write_text(
            "#!/bin/sh\n"
            f'for a in "$@"; do printf "%s\\n" "$a"; done > "{self.terminal_argv_file}"\n'
            # `gnome-terminal -- bash -c <command>`, so the command is $4.
            'if [ "$3" = "-c" ]; then\n'
            f'    bash -c "$4" </dev/null > "{self.relaunch_output_file}" 2>&1\n'
            "fi\n"
            # The real one returns as soon as the window is up; the trailing `read` in the
            # command it is handed would otherwise decide this script's exit status.
            "exit 0\n"
        )
        emulator.chmod(0o755)

    @property
    def preference_file(self) -> Path:
        return self.home / ".afield" / "last-workspace.txt"

    def run(
        self,
        shell: str,
        *args: str,
        no_terminal: bool = True,
        terminal_on_path: bool = False,
        extra_env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess:
        path = os.environ.get("PATH", "/usr/bin:/bin")
        if terminal_on_path:
            path = f"{self.terminal_bin}{os.pathsep}{path}"
        environment = {
            "PATH": path,
            "HOME": str(self.home),
            "AFIELD_SKIP_BUILD": "1",
            "AFIELD_SKIP_TOOLCHAIN_PROVISION": "1",
            "AFIELD_CODE_USE_SEMANTIC_SIDECAR": "off",
        }
        if no_terminal:
            environment["AFIELD_FIELD_SH_NO_TERMINAL"] = "1"
        environment.update(extra_env or {})
        for stale in (self.argv_file, self.terminal_argv_file, self.relaunch_output_file):
            if stale.exists():
                stale.unlink()
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

    def relaunch_command(self) -> str:
        """The command line the fake terminal emulator was handed, or "" if it never ran."""
        if not self.terminal_argv_file.exists():
            return ""
        argv = self.terminal_argv_file.read_text().splitlines()
        return argv[-1] if argv else ""

    def relaunch_output(self) -> str:
        """Everything the re-exec inside the fake terminal printed — for diagnosing a failure."""
        if not self.relaunch_output_file.exists():
            return ""
        return self.relaunch_output_file.read_text()


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
            "is_document_dir()",
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

    def test_premise_the_backend_reads_the_document_argument(self) -> None:
        """`--document` in the argv is inert unless the backend acts on it, and this module drives
        a fake `java`, so it can never see that happen. Pin the seam instead: the option the argv
        feeds, and `/app-url` — the route the launcher curls to decide the Electron window's URL —
        consulting it. The rest of that route's behaviour (preferring the named document over the
        welcome page *and* over the auto-open-last-document preference) is Kotlin's to keep."""
        text = SERVER_KT.read_text()
        self.assertIn(
            'option("document")',
            text,
            "Server.kt no longer parses --document; every --document field.sh forwards is ignored",
        )
        route = re.search(
            r'get\("/app-url"\) \{(.*?)\n {24}\}', text, re.S
        )
        self.assertIsNotNone(route, "could not find the /app-url route in Server.kt")
        self.assertIn(
            "commandLineStartupDocumentName()",
            route.group(1),
            "/app-url no longer consults the document named on the command line, so the Electron "
            "window opens the welcome page (or the last document) whatever field.sh forwards",
        )

    def test_premise_the_backend_refuses_to_save_when_launched_read_only(self) -> None:
        """Same shape as the premise above, for the other flag whose whole point is downstream of
        the argv this module can see. `ReadOnlyDocuments.global` is what reads the flag, and the
        check inside `SavingContext.save` is where reading it stops a write: that function is the
        funnel all eight save call sites go through, two of which (`Ai4AutoBoxNamer`,
        `FileWatchManager`) never pass the interactive loop's own gate. Both halves have to stay:
        the launcher announces on stderr that nothing will be written back, so a backend that
        accepts the flag and saves anyway is a launch that told the user the opposite of what
        happened."""
        server = SERVER_KT.read_text()
        self.assertIn(
            'flag("read-only")',
            server,
            "Server.kt no longer parses --read-only, so every --read-only field.sh forwards is "
            "ignored — and the launcher still says nothing will be written back to disk",
        )
        saving = SAVING_KT.read_text()
        self.assertIn(
            "ReadOnlyDocuments.blocksWritesTo(fn)",
            saving,
            "SavingContext.save no longer consults ReadOnlyDocuments, so a read-only launch can "
            "still write back to the document it opened",
        )

    def test_the_powershell_launcher_declares_the_same_options(self) -> None:
        """field.ps1 is the Windows equivalent and nothing here can run it — no PowerShell on the
        machines this suite runs on (see the note in dev_docs/launcher_workspace_verification.md).
        What is checkable statically is drift: a flag added to one launcher and not the other. The
        spellings come from the ledger, not from a list kept beside it, so adding a flag to
        field.sh fails here as well until field.ps1 names it too."""
        ps1 = FIELD_PS1.read_text()
        spellings = _declared_option_spellings()
        self.assertNotEqual(spellings, set(), "the ledger yielded no option spellings")
        for spelling in sorted(spellings):
            # Not assertIn: that would print all 20KB of field.ps1 as the haystack, burying the
            # one sentence a reader of this guard needs.
            self.assertTrue(
                f'"{spelling}"' in ps1,
                f"field.ps1's parser does not mention {spelling}, which field.sh accepts",
            )

    def test_the_powershell_launcher_stays_readable_by_windows_powershell(self) -> None:
        """A .ps1 with no byte-order mark is read as ANSI by Windows PowerShell 5.1, so a UTF-8
        character reaches the user as mojibake — harmless in a comment, where one already sits,
        and not harmless in a message or a path. The file is CRLF throughout; keep it that way,
        because these scripts get edited by tooling that defaults to LF."""
        raw = FIELD_PS1.read_bytes()
        self.assertNotEqual(raw[:3], b"\xef\xbb\xbf", "field.ps1 gained a BOM")
        self.assertEqual(
            raw.count(b"\n"), raw.count(b"\r\n"), "field.ps1 has mixed line endings"
        )
        for number, line in enumerate(raw.split(b"\r\n"), start=1):
            if line.lstrip().startswith(b"#"):
                continue
            self.assertTrue(
                all(byte < 0x80 for byte in line),
                f"field.ps1:{number} has non-ASCII outside a comment: {line!r}",
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

    def test_naming_a_document_opens_it_and_says_so(self) -> None:
        """This used to print "open docA from the welcome page once the window is up" — an apology
        for resolving a document and then not opening it. It opens it now, and both lines still
        get said, because "the workspace is the folder above what you named" is surprising enough
        on its own to be worth stating."""
        sandbox = self.sandbox
        result = sandbox.run(_resolved_shells()["sh"], str(sandbox.document))
        self.assertIn("is a document, not a workspace", result.stderr)
        self.assertIn("document docA", result.stderr)
        self.assertIn("not the welcome page", result.stderr)
        self.assertEqual(
            sandbox.backend_argv(),
            ["--workspace", str(sandbox.workspace), "--document", "docA"],
        )

    # --- the document flag ----------------------------------------------------------------------

    def test_a_document_reaches_the_backend_in_every_spelling(self) -> None:
        """Every way of naming a document has to arrive as the same `--document <bare name>`: that
        is the only form the backend resolves, against the workspace beside it."""
        sandbox = self.sandbox
        workspace = str(sandbox.workspace)
        opened = ["--workspace", workspace, "--document", "docA"]
        cases = {
            "-d spelling": (["-w", workspace, "-d", "docA"], opened),
            "--document spelling": ([workspace, "--document", "docA"], opened),
            "--document= spelling": ([workspace, "--document=docA"], opened),
            "a trailing slash on the name": ([workspace, "-d", "docA/"], opened),
            "a bare path to the document folder": ([str(sandbox.document)], opened),
            "a bare path to its metadata.edn": ([str(sandbox.document / "metadata.edn")], opened),
            # A path after -d says which workspace it is in, so it settles both.
            "-d given an absolute path": (["-d", str(sandbox.document)], opened),
            # Relative to the directory the user is standing in, not the checkout: `cwd/` is a
            # sibling of `ws/`, so this only resolves because INVOKED_FROM is captured first.
            "-d given a relative path": (["-d", "../ws/docA"], opened),
            "a document whose metadata is json": (
                [workspace, "-d", "docJson"],
                ["--workspace", workspace, "--document", "docJson"],
            ),
            # The document goes on last, after any backend arguments and the workspace.
            "alongside backend arguments": (
                ["--port", "1234", "-w", workspace, "-d", "docA"],
                ["--port", "1234", "--workspace", workspace, "--document", "docA"],
            ),
        }
        for shell_name, shell in _resolved_shells().items():
            for label, (arguments, expected) in cases.items():
                with self.subTest(shell=shell_name, case=label):
                    result = sandbox.run(shell, *arguments)
                    self.assertEqual(sandbox.backend_argv(), expected, result.stderr)

    def test_a_bare_document_name_resolves_in_the_remembered_workspace(self) -> None:
        """The short spelling: `./field.sh -d myDocument`, with no workspace named at all. No
        --workspace is appended — the backend reads the same preference file this did — so what
        this proves is that the *name* was checked against the workspace the backend will use."""
        sandbox = self.sandbox
        sandbox.preference_file.parent.mkdir(parents=True, exist_ok=True)
        try:
            sandbox.preference_file.write_text(f"{sandbox.workspace}\n")
            for shell_name, shell in _resolved_shells().items():
                with self.subTest(shell=shell_name):
                    result = sandbox.run(shell, "-d", "docA")
                    self.assertIn("last used, per", result.stderr)
                    self.assertEqual(sandbox.backend_argv(), ["--document", "docA"])
        finally:
            sandbox.preference_file.unlink()

    def test_server_only_launch_waits_for_backend_instead_of_killing_it(self) -> None:
        """With no Electron bundle the helper exits immediately. It used to win a scheduler race
        and kill the backend before the backend had even read its argv."""
        sandbox = self.sandbox
        result = sandbox.run(
            _resolved_shells()["sh"],
            str(sandbox.workspace),
            extra_env={"AFIELD_TEST_FAKE_JAVA_DELAY": "0.1"},
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sandbox.backend_argv(), ["--workspace", str(sandbox.workspace)])

    def test_a_document_that_does_not_exist_is_rejected_before_gradle(self) -> None:
        """The document flag's own reason to be parsed out here. A missing workspace is at least an
        error later on; a missing *document* is not — Field creates one on save (dir.kt mkdirs its
        folder), so an unchecked typo opens an empty new document under that name and reports
        nothing. Rejecting it must also not be what creates it."""
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(shell, str(sandbox.workspace), "-d", "docTypo")
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertIn("has no document named docTypo", result.stderr)
                self.assertNotIn("stub gradlew", result.stdout)
                self.assertEqual(sandbox.backend_argv(), [])
                self.assertFalse(
                    (sandbox.workspace / "docTypo").exists(),
                    "the launcher created the document it was in the middle of rejecting",
                )

    def test_a_path_that_is_not_a_document_is_rejected_as_one(self) -> None:
        """`-d <path>` has to fail in its own voice. Folding the path into the workspace request is
        an implementation detail, and reporting "there is no workspace directory at ..." for a
        document that does not exist would be the launcher describing its own internals."""
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            for label, argument in {
                "an ordinary directory": str(sandbox.plain_directory),
                "nothing there at all": str(sandbox.workspace / "docTypo"),
            }.items():
                with self.subTest(shell=shell_name, case=label):
                    result = sandbox.run(shell, "-d", argument)
                    self.assertEqual(result.returncode, 2, result.stdout)
                    self.assertIn("there is no Field document at", result.stderr)
                    self.assertEqual(sandbox.backend_argv(), [])

    def test_a_document_flag_with_no_value_is_rejected(self) -> None:
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            for flag in ("-d", "--document"):
                with self.subTest(shell=shell_name, flag=flag):
                    # With a pushed argument ahead of it, which is where an off-by-one in the
                    # consumed/argc bookkeeping shows up as re-reading a pushed value.
                    result = sandbox.run(shell, "--already-pushed", flag)
                    self.assertEqual(result.returncode, 2, result.stdout)
                    self.assertIn(f"field: {flag} needs a document", result.stderr)

    def test_a_document_path_alongside_a_named_workspace_is_rejected(self) -> None:
        """Two answers to "which workspace" in one command line. Choosing one silently is how a
        launch opens a document from somewhere other than the workspace it announced."""
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(
                    shell, str(sandbox.workspace), "-d", str(sandbox.document)
                )
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertIn("not by path", result.stderr)
                self.assertEqual(sandbox.backend_argv(), [])

    def test_print_workspace_reports_a_document_typo_too(self) -> None:
        """--print-workspace answers "what would I get?", and a document that is not there is part
        of the answer. It is also the cheapest place to find out."""
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(
                    shell, "--print-workspace", str(sandbox.workspace), "-d", "docTypo"
                )
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertEqual(result.stdout.strip(), "")
                self.assertIn("has no document named docTypo", result.stderr)

                # ... and still prints just the workspace when the document is real.
                result = sandbox.run(
                    shell, "--print-workspace", str(sandbox.workspace), "-d", "docA"
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), str(sandbox.workspace))

    # --- the read-only flag ---------------------------------------------------------------------

    def test_read_only_reaches_the_backend_in_every_spelling(self) -> None:
        """`--read-only` needs no `case` arm to reach the backend — the `-*` catch-all forwards any
        unrecognised flag — so the arm it has is there for the banner and the relaunch, and it
        changes the parse in one visible way: it consumes the argument without setting
        `passthrough_seen`, so a bare workspace *after* the flag is still read as the workspace.
        Delete the arm and the flag arrives ahead of everything the launcher appends rather than
        after it — and, in the case where it is typed first, the workspace stops being appended at
        all, because the bare path following a forwarded flag is read as that flag's value.
        """
        sandbox = self.sandbox
        workspace = str(sandbox.workspace)
        opened = ["--workspace", workspace, "--read-only"]
        document = ["--workspace", workspace, "--document", "docA", "--read-only"]
        cases = {
            "after a bare workspace": ([workspace, "--read-only"], opened),
            # The case the `-*` catch-all would get wrong: forwarded rather than consumed, this
            # would set passthrough_seen and the workspace would go to the backend as a bare word.
            "before a bare workspace": (["--read-only", workspace], opened),
            "-w spelling": (["-w", workspace, "--read-only"], opened),
            "--workspace spelling": (["--workspace", workspace, "--read-only"], opened),
            "--workspace= spelling": ([f"--workspace={workspace}", "--read-only"], opened),
            # No workspace named at all: the backend reads the same preference file this did, so
            # nothing but the flag is appended.
            "on its own": (["--read-only"], ["--read-only"]),
            # The spelling `--help` hands out, for a document that lives inside a repository.
            "a bare path to a document": ([str(sandbox.document), "--read-only"], document),
            "alongside -d": ([workspace, "-d", "docA", "--read-only"], document),
            # Appended last, after the pushed backend arguments and after --workspace.
            "alongside backend arguments": (
                ["--port", "1234", "--read-only", "-w", workspace],
                ["--port", "1234", "--workspace", workspace, "--read-only"],
            ),
        }
        for shell_name, shell in _resolved_shells().items():
            for label, (arguments, expected) in cases.items():
                with self.subTest(shell=shell_name, case=label):
                    result = sandbox.run(shell, *arguments)
                    self.assertEqual(sandbox.backend_argv(), expected, result.stderr)
                    # Announced, for the same reason the workspace is: a Field that refuses to
                    # save is alarming if the refusal is the first news of it.
                    self.assertIn("field: read-only", result.stderr)

    def test_a_launch_without_the_flag_says_nothing_about_read_only(self) -> None:
        """The other direction of the case above: nothing else may append `--read-only`, and the
        banner may not appear on an ordinary launch. A default-on read-only Field looks exactly
        like one whose saving is broken."""
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(shell, str(sandbox.workspace), "-d", "docA")
                self.assertNotIn("--read-only", sandbox.backend_argv())
                self.assertNotIn("read-only", result.stderr)

    def test_read_only_survives_the_terminal_relaunch(self) -> None:
        """Started with no tty — a Finder double-click — field.sh re-execs itself inside a fresh
        terminal, and that inner command line is rebuilt from WORKSPACE_RELAUNCH_ARGS rather than
        from `"$@"`. Anything missing from that string is dropped on this path and no other: the
        window comes up writable on a document that was asked for read-only, and the only warning
        went to the stderr of the outer process, which has no terminal to print it to. The other
        branch of this same function dropped the workspace exactly this way — the regression at
        the top of this module.

        The fake emulator runs the string as well as recording it, so both halves are checked: that
        the flag is in the rebuilt command line, and that the command line still parses — the
        workspace is single-quoted inside it and a bare flag is not.
        """
        sandbox = self.sandbox
        expected = ["--workspace", str(sandbox.workspace), "--document", "docA", "--read-only"]
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(
                    shell,
                    str(sandbox.workspace),
                    "-d",
                    "docA",
                    "--read-only",
                    no_terminal=False,
                    terminal_on_path=True,
                )
                self.assertIn(
                    f"./field.sh --workspace '{sandbox.workspace}' --document 'docA' --read-only",
                    sandbox.relaunch_command(),
                    "the re-exec command line field.sh built is not the launch that was asked "
                    f"for. field.sh said: {result.stderr!r}",
                )
                self.assertEqual(
                    sandbox.backend_argv(),
                    expected,
                    "the relaunch ran but handed the backend something else. It printed "
                    f"{sandbox.relaunch_output()!r}",
                )

    def test_read_only_survives_the_no_terminal_fallback(self) -> None:
        """The other branch of run_in_terminal, and the one that runs on macOS: none of the four
        emulators exists there, so a no-tty launch falls through to `run_field "$@"` — an agent's
        shell tool, a launchd job, anything with stdin closed. That branch carries the rebuilt
        `"$@"`, not the relaunch string, so it is a separate claim from the one above."""
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(
                    shell, str(sandbox.workspace), "--read-only", no_terminal=False
                )
                self.assertEqual(sandbox.relaunch_command(), "", "a terminal emulator was found")
                self.assertEqual(
                    sandbox.backend_argv(),
                    ["--workspace", str(sandbox.workspace), "--read-only"],
                    result.stderr,
                )

    # --- the offline flag -----------------------------------------------------------------------
    #
    # `--offline` is for Gradle, not the backend; these cases pin that it reaches the one and not
    # the other.

    def test_offline_reaches_gradle_and_not_the_backend(self) -> None:
        """Consumed like --read-only (so a bare workspace after it is still the workspace), and
        put on the build's gradlew command line. The stub gradlew echoes its argv to stdout."""
        sandbox = self.sandbox
        workspace = str(sandbox.workspace)
        building = {"AFIELD_SKIP_BUILD": "0"}
        for shell_name, shell in _resolved_shells().items():
            for label, arguments in {
                "before a bare workspace": ["--offline", workspace],
                "after a bare workspace": [workspace, "--offline"],
            }.items():
                with self.subTest(shell=shell_name, case=label):
                    result = sandbox.run(shell, *arguments, extra_env=building)
                    self.assertEqual(sandbox.backend_argv(), ["--workspace", workspace], result.stderr)
                    self.assertIn("stub gradlew --offline jvmJar dumpFieldLaunch", result.stdout)
                    self.assertIn("field: offline", result.stderr)

    def test_without_the_flag_gradle_is_not_offline(self) -> None:
        """Offline is opt-in only: no flag, no --offline, and nothing said about it."""
        sandbox = self.sandbox
        env = {"AFIELD_SKIP_BUILD": "0"}
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(shell, str(sandbox.workspace), extra_env=env)
                self.assertIn("stub gradlew jvmJar dumpFieldLaunch", result.stdout)
                self.assertNotIn("offline", result.stderr)

    def test_offline_survives_the_terminal_relaunch(self) -> None:
        """The re-exec rebuilds its command line from WORKSPACE_RELAUNCH_ARGS, and AFIELD_OFFLINE
        set by the flag is a shell variable, not an exported one — so the flag has to be in there."""
        sandbox = self.sandbox
        for shell_name, shell in _resolved_shells().items():
            with self.subTest(shell=shell_name):
                result = sandbox.run(
                    shell,
                    str(sandbox.workspace),
                    "--offline",
                    no_terminal=False,
                    terminal_on_path=True,
                )
                self.assertIn(
                    f"./field.sh --workspace '{sandbox.workspace}' --offline",
                    sandbox.relaunch_command(),
                    result.stderr,
                )
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
