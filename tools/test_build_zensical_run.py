"""How `build_zensical_docs` runs zensical and installs what it built.

On 2026-10-09 four lander docs builds sat at "Build started" at 0% CPU until someone interrupted
them: zensical's file monitor never saw the 100 ms without a file event it waits for, because Finder
kept writing `.DS_Store` files into the open site dir. Interrupted, zensical printed "Received
interrupt, exiting", exited 0, and the job reported a built site holding 2 pages. These tests pin the
three answers: a wedged zensical is stopped, an interrupted one is a failure whatever its exit code,
and a site is installed whole or not at all. Each fake zensical below reproduces one of those runs.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_zensical_docs import (  # noqa: E402
    ZensicalRun,
    install_site,
    missing_pages,
    page_output,
    prepare_local_site_for_captures,
    process_cpu_seconds,
    run_zensical,
)

# Prints what zensical prints up to the deadlock, then waits on nothing.
WEDGED_AFTER_START = "import time; print('Build started', flush=True); time.sleep(60)"
# The other deadlock: pages rendered, but a busy file monitor keeps the build from finishing.
WEDGED_AFTER_REPORT = (
    "import sys, time; print('Build started', flush=True); print('No issues found', file=sys.stderr, flush=True)\n"
    "end = time.time() + 60\n"
    "while time.time() < end: pass"
)
# What a SIGINT'd zensical does: lib.rs calls std::process::exit(0).
INTERRUPTED = "print('Build started'); print('Received interrupt, exiting'); raise SystemExit(0)"
HEALTHY = "print('Build started'); print('No issues found'); print('Build finished in 0.10s')"
SILENT_SUCCESS = "raise SystemExit(0)"


def fake(code: str, tmp: Path, *, stall_seconds: float = 30.0) -> ZensicalRun:
    return run_zensical([sys.executable, "-c", code], cwd=tmp, watched=[tmp], stall_seconds=stall_seconds)


class RunZensicalTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_a_healthy_build_is_complete(self) -> None:
        run = fake(HEALTHY, self.tmp)
        self.assertEqual(run.returncode, 0)
        self.assertTrue(run.finished)
        self.assertIsNone(run.failure())

    def test_an_interrupted_build_fails_although_zensical_exits_0(self) -> None:
        run = fake(INTERRUPTED, self.tmp)
        self.assertEqual(run.returncode, 0)
        self.assertIn("interrupted", run.failure() or "")

    def test_exit_0_without_build_finished_is_not_a_build(self) -> None:
        self.assertIn("without printing 'Build finished'", fake(SILENT_SUCCESS, self.tmp).failure() or "")

    def test_an_idle_wedged_build_is_stopped(self) -> None:
        run = fake(WEDGED_AFTER_START, self.tmp, stall_seconds=1.5)
        self.assertIsNone(run.returncode)
        self.assertIn("no output and under", run.stalled or "")
        self.assertIn("stalled", run.failure() or "")

    def test_a_busy_build_that_never_finishes_after_its_report_is_stopped(self) -> None:
        # Burns CPU throughout, so only the report-without-finish rule can catch it.
        run = fake(WEDGED_AFTER_REPORT, self.tmp, stall_seconds=1.5)
        self.assertIsNone(run.returncode)
        self.assertIn("never finished", run.stalled or "")

    def test_the_stall_report_names_files_written_under_watched_dirs(self) -> None:
        writer = (
            "import pathlib, time; print('Build started', flush=True); "
            "pathlib.Path('.DS_Store').write_text('finder'); time.sleep(60)"
        )
        from contextlib import redirect_stdout
        from io import StringIO

        out = StringIO()
        with redirect_stdout(out):
            fake(writer, self.tmp, stall_seconds=1.5)
        self.assertIn(".DS_Store", out.getvalue())

    def test_cpu_time_of_a_running_process_reads(self) -> None:
        import os

        self.assertIsNotNone(process_cpu_seconds(os.getpid()))


class InstallSiteTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.docs, self.staging, self.site, self.previous = (root / n for n in ("docs", "staging", "field4", "previous"))
        for page in ("index.md", "guide.md", "reference/index.md", "reference/math.md"):
            (self.docs / page).parent.mkdir(parents=True, exist_ok=True)
            (self.docs / page).write_text("# page\n")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def build(self, *pages: str) -> None:
        for page in pages:
            out = self.staging / page_output(Path(page))
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text("<html>")

    def test_page_output_follows_directory_urls(self) -> None:
        self.assertEqual(page_output(Path("guide.md")), Path("guide/index.html"))
        self.assertEqual(page_output(Path("reference/index.md")), Path("reference/index.html"))
        self.assertEqual(page_output(Path("assets/x/README.md")), Path("assets/x/index.html"))

    def test_a_two_page_site_misses_the_rest(self) -> None:
        self.build("index.md", "guide.md")
        expected, missing = missing_pages(generated_docs=self.docs, site_dir=self.staging)
        self.assertEqual(expected, 4)
        self.assertEqual(missing, [Path("reference/index.md"), Path("reference/math.md")])

    def test_workspace_markdown_is_not_expected_as_a_page(self) -> None:
        (self.docs / "assets/demo").mkdir(parents=True)
        (self.docs / "assets/demo/document.md").write_text("edn")
        self.build("index.md", "guide.md", "reference/index.md", "reference/math.md")
        self.assertEqual(missing_pages(generated_docs=self.docs, site_dir=self.staging), (4, []))

    def test_install_replaces_the_site_whole(self) -> None:
        (self.site / "stale").mkdir(parents=True)
        (self.site / "stale/index.html").write_text("old")
        (self.site / ".DS_Store").write_text("finder")
        self.build("index.md")
        install_site(staging=self.staging, site_dir=self.site, previous=self.previous)
        self.assertTrue((self.site / "index.html").is_file())
        self.assertFalse((self.site / "stale").exists())
        self.assertFalse(self.staging.exists())
        self.assertFalse(self.previous.exists())

    def test_install_clears_a_previous_site_left_by_an_earlier_build(self) -> None:
        (self.previous / "leftover").mkdir(parents=True)
        self.build("index.md")
        install_site(staging=self.staging, site_dir=self.site, previous=self.previous)
        self.assertTrue((self.site / "index.html").is_file())
        self.assertFalse(self.previous.exists())


class LocalCaptureSiteTests(unittest.TestCase):
    def test_missing_local_page_is_built_without_captures_before_the_drive(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            public = root / "public"
            (public / "docs").mkdir(parents=True)
            (public / "docs/how_field_is_built.md").write_text("<!-- docs-capture-needs-local-site: landing_queue -->")

            def build(*_args, **kwargs):
                self.assertEqual(kwargs["env"]["FIELD_DOCS_RUNNER_MODE"], "never")
                page = public / "field4/landing_queue/index.html"
                page.parent.mkdir(parents=True)
                page.write_text("<html>local</html>")
                return type("Result", (), {"returncode": 0})()

            with patch.dict("os.environ", {"FIELD_DOCS_RUNNER_MODE": "always"}), \
                 patch("build_zensical_docs.subprocess.run", side_effect=build) as run:
                kwargs = dict(public_dir=public, repo_root=root, page_filters=("how_field_is_built.md",),
                              no_source_pages=False, skip_build=False)
                self.assertEqual(prepare_local_site_for_captures(**kwargs), 0)
                self.assertEqual(prepare_local_site_for_captures(**kwargs), 0)
                self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
