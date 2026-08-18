"""Which completion backend the docs runner actually captured.

Every `capture="ui"` completion figure in `public/docs` was, until 2026-08-18, a picture of the K1
fallback: the runner launches `./gradlew runJvm` itself, only `field.sh` exports
`AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL`, and without that URL `AaCompletionClient` never engages —
so `KotlinInterface2.complete` fell through to K1 while `activeCompletionBackend` still read `AA`.
Nothing failed; the figures were simply of the wrong thing.

Two things stop that recurring, and both are tested here: the runner now derives and starts the same
sidecar `field.sh` would (so the two share one warm Analysis-API session rather than starting two), and
a completion capture is checked against Field's own `completion backend=` log lines afterwards.
"""

import http.server
import json
import re
import subprocess
import sys
import threading
import unittest
import unittest.mock
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

import docs_hooks  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent


class _Runner:
    """The guard and its two log readers, without a live runner."""

    def __init__(self, log_path: Path, sidecar_url: str | None = None):
        self.shared_log_path = log_path
        self._sidecar_url = sidecar_url

    _shared_log_size = docs_hooks.RunnerState._shared_log_size
    _shared_log_since = docs_hooks.RunnerState._shared_log_since
    _assert_completion_served_by_aa = docs_hooks.RunnerState._assert_completion_served_by_aa


AA_LINE = "[info,completion] completion backend=AA box='Untitled'(__docs_drive__) 41ms (254 candidates)\n"
# Verbatim from public/.field-docs-runner-field.log on 2026-08-17 — the line that was there all along.
NO_SIDECAR_LINE = (
    "[info,completion] completion backend=K1 box='Untitled'(__docs_drive__) 2ms "
    "(AA→K1: no-sidecar-url)\n"
)
WARMING_LINE = (
    "[info,completion] completion backend=K1 box='Untitled'(__docs_drive__) 8ms "
    "(AA→K1: empty: session building (warming up; using K1 meanwhile))\n"
)
DELIBERATE_K1_LINE = "[info,completion] completion backend=K1 box='Untitled'(__docs_drive__) (backend=k1)\n"


class CompletionBackendGuardTests(unittest.TestCase):

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.log = Path(self.tmp.name) / "field.log"

    def guard(self, *, offset: int, sidecar_url: str | None = None):
        _Runner(self.log, sidecar_url)._assert_completion_served_by_aa(
            page_path=Path("survivalGuide.md"),
            output_path=Path("assets/autocomplete.png"),
            log_offset=offset,
            command=["field_backdoor.py", "--command", "macro run x"],
        )

    def test_an_aa_served_capture_passes(self):
        self.log.write_text(AA_LINE, encoding="utf-8")
        self.guard(offset=0)

    def test_a_no_sidecar_fallback_fails_and_quotes_the_log(self):
        self.log.write_text(NO_SIDECAR_LINE, encoding="utf-8")
        with self.assertRaises(RuntimeError) as caught:
            self.guard(offset=0)
        message = str(caught.exception)
        self.assertIn("AA→K1: no-sidecar-url", message)
        self.assertIn("autocomplete.png", message)

    def test_a_warming_session_fallback_also_fails(self):
        # This is the one a *correctly configured* build hits: the sidecar is up but its session is
        # still building, so the capture would photograph K1 anyway. `warmCompletionBackend` on the
        # drive op is what prevents it; the guard is what proves it worked.
        self.log.write_text(WARMING_LINE, encoding="utf-8")
        with self.assertRaises(RuntimeError):
            self.guard(offset=0)

    def test_a_deliberately_selected_k1_is_left_alone(self):
        # `-Dafield.kotlin.completion=k1` logs no `AA→K1` clause: AA was never selected, so nothing
        # fell back, so there is nothing to complain about.
        self.log.write_text(DELIBERATE_K1_LINE, encoding="utf-8")
        self.guard(offset=0)

    def test_an_earlier_captures_fallback_does_not_fail_this_one(self):
        self.log.write_text(NO_SIDECAR_LINE, encoding="utf-8")
        offset = self.log.stat().st_size
        with self.log.open("a", encoding="utf-8") as handle:
            handle.write(AA_LINE)
        self.guard(offset=offset)

    def test_a_truncated_log_is_read_from_the_start(self):
        # A mid-build server restart reopens the log with mode "w". An offset from before that is past
        # the new end of file; the whole file is then new content and has to be read.
        self.log.write_text(AA_LINE + NO_SIDECAR_LINE, encoding="utf-8")
        with self.assertRaises(RuntimeError):
            self.guard(offset=10_000_000)

    def test_a_capture_that_never_completed_is_not_a_failure(self):
        self.log.write_text("[info,docs] drive warmCompletion for box=x took 900ms\n", encoding="utf-8")
        self.guard(offset=0)

    def test_the_quoted_line_is_stripped_of_terminal_escapes(self):
        # Field's log is written for a terminal. Verbatim, the tag arrives as
        # `\x1b[90m\x1b[3m[info,completion]\x1b[0m`, and the raw bytes in a build log or a saved failure
        # report are unreadable.
        self.log.write_text(
            "\x1b[90m\x1b[3m[info,completion]\x1b[0m " + NO_SIDECAR_LINE.split("] ", 1)[1],
            encoding="utf-8",
        )
        with self.assertRaises(RuntimeError) as caught:
            self.guard(offset=0)
        message = str(caught.exception)
        self.assertIn("[info,completion] completion backend=K1", message)
        self.assertNotIn("\x1b", message)

    def test_the_sidecar_in_use_is_named_in_the_failure(self):
        self.log.write_text(NO_SIDECAR_LINE, encoding="utf-8")
        with self.assertRaises(RuntimeError) as caught:
            self.guard(offset=0, sidecar_url="http://127.0.0.1:18374")
        self.assertIn("http://127.0.0.1:18374", str(caught.exception))


class SidecarPortDerivationTests(unittest.TestCase):
    """The runner and `field.sh` must land on the same port, or a checkout gets two sidecars."""

    def field_sh_port(self, root: Path) -> int:
        """`field.sh derive_sidecar_port`, run as the shell runs it."""
        completed = subprocess.run(
            ["sh", "-c", f"printf '%s' '{root}' | cksum | awk '{{ print 17841 + ($1 % 1000) }}'"],
            capture_output=True, text=True, check=True,
        )
        return int(completed.stdout.strip())

    def test_the_formula_is_still_the_one_field_sh_uses(self):
        # If this fails, field.sh changed how it derives the port and derive_sidecar_port must follow.
        script = (REPO_ROOT / "field.sh").read_text(encoding="utf-8")
        self.assertRegex(
            script,
            re.compile(r"cksum \| awk '\{ print 17841 \+ \(\$1 % 1000\) \}'"),
            "field.sh no longer derives the sidecar port the way docs_hooks.derive_sidecar_port does",
        )

    def test_the_two_agree_for_this_checkout(self):
        self.assertEqual(
            self.field_sh_port(REPO_ROOT), docs_hooks.derive_sidecar_port(REPO_ROOT)
        )

    def test_the_two_agree_for_an_arbitrary_path(self):
        root = Path("/some/other/worktree/field4")
        self.assertEqual(self.field_sh_port(root), docs_hooks.derive_sidecar_port(root))

    def test_an_explicit_port_wins(self):
        with unittest.mock.patch.dict("os.environ", {docs_hooks.SIDECAR_PORT_ENV: "19001"}):
            self.assertEqual(19001, docs_hooks.derive_sidecar_port(REPO_ROOT))


class SidecarHealthTests(unittest.TestCase):
    """`ok` only for a sidecar serving THIS checkout — the same rule field.sh applies."""

    def serve(self, payload: dict | None):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 - http.server's spelling
                if payload is None:
                    self.send_error(500)
                    return
                body = json.dumps(payload).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_address[1]}"

    def test_a_sidecar_for_this_workspace_is_ok(self):
        url = self.serve({"workspaceRoot": str(REPO_ROOT)})
        self.assertEqual("ok", docs_hooks.sidecar_workspace_status(url, REPO_ROOT))

    def test_a_sidecar_for_another_workspace_is_wrong(self):
        url = self.serve({"workspaceRoot": "/Users/someone/other-checkout"})
        self.assertEqual("wrong", docs_hooks.sidecar_workspace_status(url, REPO_ROOT))

    def test_an_unreachable_sidecar_is_down(self):
        # Port 1 is reserved and never listening.
        self.assertEqual("down", docs_hooks.sidecar_workspace_status("http://127.0.0.1:1", REPO_ROOT))

    def test_an_analyze_suffix_is_tolerated(self):
        # field.sh strips it, because the code-use telemetry URL people export points at /analyze.
        url = self.serve({"workspaceRoot": str(REPO_ROOT)})
        self.assertEqual("ok", docs_hooks.sidecar_workspace_status(url + "/analyze", REPO_ROOT))


if __name__ == "__main__":
    unittest.main()
