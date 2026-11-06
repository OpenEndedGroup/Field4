"""tools/routines.py: the routine format, schedules, what a scheduler is given, and status.

The first test is the guard: every routines/*/ROUTINE.md in this checkout must pass `check`, so a
routine that names a script that is not there, or a schedule that is not cron, fails here rather
than at 3 am. The rest run against temporary folders and a temporary git repository; nothing here
writes into the checkout or reads the real ~/.afield, ~/.claude or LaunchAgents.
"""

from __future__ import annotations

import io
import plistlib
import subprocess
import sys
import tempfile
import textwrap
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import routines  # noqa: E402

REPO = Path(__file__).resolve().parent.parent


def write_routine(root: Path, name: str, **meta: str) -> Path:
    fields = {"name": name, "title": f"Title {name}", "summary": "s", "kind": "agent", "schedule": "0 1 * * *",
              "runtime-hours": "1", "needs": "n", "cost": "c", "reports": "runs"}
    fields.update(meta)
    folder = root / "routines" / name
    folder.mkdir(parents=True, exist_ok=True)
    head = "\n".join(f"{k}: {v}" for k, v in fields.items())
    (folder / "ROUTINE.md").write_text(f"---\n{head}\n---\n\n# {name}\n\nDo the thing.\n", encoding="utf-8")
    return folder


class Isolated(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="routines-test-"))
        home = self.tmp / "home"
        for name, value in (("RUN_LOGS", home / ".afield" / "routines"), ("LAUNCH_AGENTS", home / "LaunchAgents"),
                            ("CLAUDE_TASKS", home / ".claude" / "scheduled-tasks")):
            patcher = mock.patch.object(routines, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        env = mock.patch.dict("os.environ", {"FIELD_WORKTREE_BASE": str(self.tmp / "wt"), "FIELD_WORKSPACE": str(self.tmp / "ws")})
        env.start()
        self.addCleanup(env.stop)


class TheCheckoutsRoutines(unittest.TestCase):
    def test_every_routine_in_this_checkout_passes_check(self) -> None:
        self.assertEqual(routines.check(REPO), [])

    def test_the_five_routines_exist(self) -> None:
        names = {r.name for r in routines.discover(REPO)}
        self.assertLessEqual({"self-improve", "nightly-ci", "feel-bench", "weekly-changelog", "reap-worktrees"}, names)

    def test_suggested_schedules_do_not_starve_the_quiet_routine(self) -> None:
        self.assertEqual(routines.conflicts(routines.discover(REPO)), [])


class Format(Isolated):
    def test_frontmatter_values_are_literal(self) -> None:
        folder = write_routine(self.tmp, "alpha", verdict=r"(?m)^NIGHTLY: (.+)$ # not a comment")
        routine = routines.parse_routine(folder / "ROUTINE.md")
        self.assertEqual(routine.get("verdict"), r"(?m)^NIGHTLY: (.+)$ # not a comment")
        self.assertTrue(routine.body.startswith("# alpha"))
        self.assertEqual(routine.errors, [])

    def test_missing_frontmatter_is_an_error(self) -> None:
        folder = self.tmp / "routines" / "beta"
        folder.mkdir(parents=True)
        (folder / "ROUTINE.md").write_text("# beta\n", encoding="utf-8")
        self.assertIn("no frontmatter", routines.parse_routine(folder / "ROUTINE.md").errors[0])

    def test_check_names_each_fault(self) -> None:
        write_routine(self.tmp, "gamma", kind="command", command="zsh routines/gamma/missing.sh", schedule="every night",
                      colour="blue")
        faults = "\n".join(routines.check(self.tmp))
        self.assertIn("missing.sh, which does not exist", faults)
        self.assertIn("not a five-field cron expression", faults)
        self.assertIn("unknown key `colour`", faults)

    def test_check_follows_repo_paths_in_the_body(self) -> None:
        folder = write_routine(self.tmp, "delta")
        path = folder / "ROUTINE.md"
        path.write_text(path.read_text() + "\nRead `$REPO/routines/delta/PLAYBOOK.md`.\n", encoding="utf-8")
        self.assertIn("PLAYBOOK.md, which does not exist", "\n".join(routines.check(self.tmp)))
        (folder / "PLAYBOOK.md").write_text("x", encoding="utf-8")
        self.assertEqual(routines.check(self.tmp), [])


class Schedules(unittest.TestCase):
    def test_descriptions(self) -> None:
        self.assertEqual(routines.describe_schedule("2 5 * * *"), "every day at 05:02")
        self.assertEqual(routines.describe_schedule("30 21 * * 0"), "Sundays at 21:30")
        self.assertEqual(routines.describe_schedule("20 */4 * * *"), "every 4 hours, at :20")
        self.assertEqual(routines.describe_schedule("0 9 1 * *"), "cron 0 9 1 * *")

    def test_validity(self) -> None:
        for good in ("0 1 * * *", "*/15 * * * *", "0 9-17 * * 1-5", "5,35 * * * *"):
            self.assertTrue(routines.cron_valid(good), good)
        for bad in ("0 25 * * *", "nightly", "0 1 * *", "x 1 * * *"):
            self.assertFalse(routines.cron_valid(bad), bad)

    def test_a_quiet_routine_inside_a_heavy_one_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_routine(root, "heavy", schedule="45 0 * * *", **{"runtime-hours": "4"})
            write_routine(root, "bench", schedule="0 4 * * *", quiet="yes", **{"runtime-hours": "1"})
            found = routines.conflicts(routines.discover(root))
            self.assertEqual(len(found), 1)
            self.assertIn("bench needs a quiet machine", found[0])
            write_routine(root, "bench", schedule="0 23 * * *", quiet="yes", **{"runtime-hours": "1.5"})
            self.assertEqual(routines.conflicts(routines.discover(root)), [])

    def test_overlap_across_midnight(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_routine(root, "late", schedule="0 23 * * *", **{"runtime-hours": "3"})
            write_routine(root, "bench", schedule="30 0 * * *", quiet="yes", **{"runtime-hours": "1"})
            self.assertEqual(len(routines.conflicts(routines.discover(root))), 1)


class Install(Isolated):
    def test_prompt_names_the_routine_file_and_the_paths(self) -> None:
        write_routine(self.tmp, "alpha", schedule="2 5 * * *")
        rendered = routines.render_install(routines.find("alpha", self.tmp), "prompt", self.tmp)
        self.assertIn('"field4-alpha"', rendered["text"])
        self.assertIn("every day at 05:02", rendered["text"])
        self.assertIn(f"{self.tmp}/routines/alpha/ROUTINE.md", rendered["prompt"])
        self.assertIn(f"$WORKTREE_BASE = {self.tmp / 'wt'}", rendered["prompt"])
        self.assertIn(f"$WORKSPACE = {self.tmp / 'ws'}", rendered["prompt"])

    def test_launchd_every_four_hours(self) -> None:
        write_routine(self.tmp, "reaper", kind="command", command="zsh tools/x.sh", schedule="20 */4 * * *")
        rendered = routines.render_install(routines.find("reaper", self.tmp), "launchd", self.tmp)
        plist = plistlib.loads(rendered["text"].split("-->\n", 1)[1].encode())
        self.assertEqual(plist["Label"], "com.field4.routine.reaper")
        self.assertEqual(plist["ProgramArguments"][-2:], ["run", "reaper"])
        self.assertEqual([(e["Hour"], e["Minute"]) for e in plist["StartCalendarInterval"]],
                         [(h, 20) for h in (0, 4, 8, 12, 16, 20)])
        self.assertTrue(plist["LowPriorityIO"])

    def test_launchd_weekly(self) -> None:
        write_routine(self.tmp, "weekly", schedule="30 21 * * 0")
        rendered = routines.render_install(routines.find("weekly", self.tmp), "launchd", self.tmp)
        plist = plistlib.loads(rendered["text"].split("-->\n", 1)[1].encode())
        self.assertEqual(plist["StartCalendarInterval"], {"Minute": 30, "Hour": 21, "Weekday": 0})

    def test_cron_line(self) -> None:
        write_routine(self.tmp, "alpha", schedule="2 5 * * *")
        line = routines.render_install(routines.find("alpha", self.tmp), "cron", self.tmp)["line"]
        self.assertTrue(line.startswith("2 5 * * * cd "))
        self.assertIn("routines.py run alpha >>", line)

    def test_names_are_refused_before_any_path_is_built(self) -> None:
        with self.assertRaises(SystemExit):
            routines.find("../etc", self.tmp)


class Run(Isolated):
    def test_dry_run_of_a_command_routine(self) -> None:
        write_routine(self.tmp, "cmd", kind="command", command="zsh routines/cmd/go.sh --flag")
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(routines.run(routines.find("cmd", self.tmp), self.tmp, dry_run=True), 0)
        self.assertIn("argv: zsh routines/cmd/go.sh --flag", out.getvalue())

    def test_a_run_keeps_its_log_and_status_reads_it(self) -> None:
        folder = write_routine(self.tmp, "echo", kind="command", command="sh routines/echo/go.sh",
                               verdict=r"(?m)^DONE: (.+)$")
        (folder / "go.sh").write_text('echo "FIELD_REPO=$FIELD_REPO"\necho "DONE: all good"\n', encoding="utf-8")
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(routines.run(routines.find("echo", self.tmp), self.tmp), 0)
        logs = list((routines.RUN_LOGS / "echo").glob("*.log"))
        self.assertEqual(len(logs), 1)
        self.assertIn(f"FIELD_REPO={self.tmp}", logs[0].read_text())
        recent = routines.recent_runs(routines.find("echo", self.tmp), self.tmp)
        self.assertEqual(recent[0]["verdict"], "all good")


class Status(Isolated):
    def git(self, *args: str) -> None:
        subprocess.run(["git", "-C", str(self.tmp), *args], check=True, capture_output=True)

    def test_reports_on_a_journal_branch(self) -> None:
        self.git("init", "-q", "-b", "main")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "root")
        self.git("checkout", "-q", "--orphan", "nightly/journal")
        reports = self.tmp / "reports"
        reports.mkdir()
        (reports / "2026-11-05.md").write_text("# Night\n\nHeadline: **2 merge / 1 continue / 0 delete**.\n")
        (reports / "2026-11-06.md").write_text("_skipped — low disk (3.1 GiB free)_\n")
        self.git("add", "reports")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "reports")
        self.git("checkout", "-q", "main")
        write_routine(self.tmp, "nightly", reports="git:nightly/journal:reports",
                      verdict=r"(?m)(\d+ merge / \d+ continue(?: / \d+ delete)?)|^_?(skipped[^\n]*)")
        recent = routines.recent_runs(routines.find("nightly", self.tmp), self.tmp)
        self.assertEqual([r["date"] for r in recent], ["2026-11-06", "2026-11-05"])
        self.assertTrue(recent[0]["verdict"].startswith("skipped — low disk"))
        self.assertEqual(recent[1]["verdict"], "2 merge / 1 continue / 0 delete")

    def test_report_files_by_glob(self) -> None:
        folder = self.tmp / "reports"
        folder.mkdir()
        (folder / "nightly-20261105.txt").write_text("x\nNIGHTLY: no regressions\n")
        (folder / "nightly-20261106.txt").write_text("x\nNIGHTLY: skipped: load\n")
        write_routine(self.tmp, "bench", reports=f"file:{folder}/nightly-*.txt", verdict=r"(?m)^NIGHTLY: (.+)$")
        recent = routines.recent_runs(routines.find("bench", self.tmp), self.tmp)
        self.assertEqual([(r["date"], r["verdict"]) for r in recent],
                         [("2026-11-06", "skipped: load"), ("2026-11-05", "no regressions")])

    def test_adoption_is_seen_in_claude_tasks_and_launchd(self) -> None:
        write_routine(self.tmp, "alpha")
        task = routines.CLAUDE_TASKS / "field4-alpha"
        task.mkdir(parents=True)
        (task / "SKILL.md").write_text("Read /x/routines/alpha/ROUTINE.md in full and follow it.\n")
        routines.LAUNCH_AGENTS.mkdir(parents=True)
        (routines.LAUNCH_AGENTS / "com.field4.routine.alpha.plist").write_text("<plist/>")
        with mock.patch.object(routines.subprocess, "run", wraps=routines.subprocess.run) as spy:
            found = routines.adopted(routines.find("alpha", self.tmp), self.tmp)
        self.assertIn("launchd", found)
        self.assertIn("Claude scheduled task field4-alpha", found)
        self.assertTrue(any(call.args[0][:2] == ["crontab", "-l"] for call in spy.call_args_list))

    def test_status_json_shape(self) -> None:
        write_routine(self.tmp, "alpha", schedule="0 1 * * *")
        report = routines.status(self.tmp)
        entry = report["routines"][0]
        for key in ("name", "title", "when", "recent", "adopted", "now", "errors", "path"):
            self.assertIn(key, entry)
        self.assertEqual(entry["when"], "every day at 01:00")


if __name__ == "__main__":
    unittest.main()
