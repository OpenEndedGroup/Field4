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




class ScheduleHealth(Isolated):
    def setUp(self):
        super().setUp()
        import datetime as dt
        from zoneinfo import ZoneInfo
        self.dt = dt
        self.zone = ZoneInfo('Europe/Paris')
        self.now = dt.datetime(2026, 11, 6, 12, tzinfo=self.zone)
        write_routine(self.tmp, 'alpha', kind='command', command=f'{sys.executable} -c "print(42)"', schedule='0 5 * * *')
        self.routine = routines.find('alpha', self.tmp)

    def health(self, recent=None, scheduled=True, **kwargs):
        return routines.schedule_health(self.routine, recent or [], scheduled, now=self.now, **kwargs)

    def test_overdue_has_offset_deadline_and_explicit_schedule_source(self):
        result = self.health()
        self.assertEqual(result['state'], 'overdue')
        self.assertEqual(result['expectedAt'], '2026-11-06T05:00:00+01:00')
        self.assertEqual(result['deadlineAt'], '2026-11-06T06:00:00+01:00')
        self.assertIn('unverified', result['scheduleSource'])
        self.assertEqual(self.health(scheduled=False)['state'], 'unscheduled')

    def test_runtime_window_and_skipped_external_report_are_not_missing(self):
        self.now = self.now.replace(hour=5, minute=30)
        self.assertEqual(self.health()['state'], 'awaiting')
        self.assertEqual(self.health([{'date': '2026-11-06', 'verdict': 'skipped', 'source': 'journal:report.md'}])['state'], 'recorded')
        self.assertEqual(self.health([{'date': '2026-11-05', 'source': 'old.md'}])['state'], 'awaiting')
        self.assertEqual(self.health([{'date': '2026-11-07', 'source': 'future.md'}])['state'], 'awaiting')

    def test_weekly_and_dst_gap_and_fold_use_actual_instants(self):
        self.routine.meta['schedule'] = '30 2 * * *'
        spring = self.dt.datetime(2026, 3, 29, 4, tzinfo=self.zone)
        self.assertEqual(routines.latest_slot(self.routine, spring).date().isoformat(), '2026-03-28')
        autumn = self.dt.datetime(2026, 10, 25, 3, tzinfo=self.zone)
        self.assertEqual(routines.latest_slot(self.routine, autumn).fold, 1)
        self.routine.meta['schedule'] = '0 5 * * 0'
        self.assertEqual(routines.latest_slot(self.routine, self.now).date().isoformat(), '2026-11-01')

    def test_monthly_schedule_is_unknown_not_guessed_and_zero_step_invalid(self):
        self.routine.meta['schedule'] = '0 5 1 * *'
        self.assertEqual(self.health()['state'], 'unknown')
        self.assertFalse(routines.cron_valid('0 */0 * * *'))
        self.assertFalse(routines.cron_valid('0 */ * * *'))
        self.assertFalse(routines.cron_valid('0 9-2 * * *'))

    def test_invalid_runtime_reports_unknown_instead_of_crashing_status(self):
        self.routine.meta['runtime-hours'] = 'nan'
        self.assertEqual(self.health()['state'], 'unknown')
        self.assertIn('finite and positive', self.health()['message'])

    def test_hourly_external_date_cannot_clear_a_specific_slot(self):
        self.routine.meta['schedule'] = '20 */4 * * *'
        result = self.health([{'date': '2026-11-06', 'source': 'dated.md'}])
        self.assertEqual(result['state'], 'unknown')

    def test_older_completed_log_proves_the_actual_slot(self):
        self.routine.meta['schedule'] = '20 */4 * * *'
        log = self.tmp / '20261106-082005.log'
        log.write_text('# routine alpha at 2026-11-06T08:20:05 in /tmp\nOK\n# exit 0\n')
        recent = [{'date': '2026-11-06', 'source': str(log)}]
        self.assertEqual(self.health(recent)['state'], 'recorded')
        log.write_text('# routine alpha at 2026-11-06T08:20:05 in /tmp\n# exit 3\n')
        self.assertEqual(self.health(recent)['state'], 'failed')
        log.write_text('# routine alpha at 2026-11-06T08:20:05 in /tmp\n')
        self.assertEqual(self.health(recent)['state'], 'unknown')
        log.write_text('# routine alpha at 2026-11-06T04:20:05 in /tmp\n# exit 0\n')
        self.assertEqual(self.health(recent)['state'], 'overdue')

    def test_active_lock_interruption_and_failure_are_distinct(self):
        import json
        folder = routines.RUN_LOGS / 'alpha'
        with routines.routine_lock('alpha'):
            self.assertEqual(self.health()['state'], 'running')
            with self.assertRaisesRegex(RuntimeError, 'active'):
                with routines.routine_lock('alpha'):
                    self.fail('duplicate run admitted')
        state = {'startedAt': '2026-11-06T05:00:00+01:00', 'log': 'owned.log'}
        (folder / 'last-run.json').write_text(json.dumps(state))
        self.assertEqual(self.health()['state'], 'interrupted')
        state['exit'] = 2
        (folder / 'last-run.json').write_text(json.dumps(state))
        self.assertEqual(self.health()['state'], 'failed')

    def test_catch_up_executes_once_and_records_completion(self):
        import json
        import shlex
        self.routine.meta['command'] = shlex.join([sys.executable, '-c',
                                                   'import os; print(os.environ.get("FIELD_ROUTINE_CATCH_UP"))'])
        with mock.patch.object(routines, 'local_clock', return_value=self.now), redirect_stdout(io.StringIO()):
            self.assertEqual(routines.run(self.routine, self.tmp, catch_up=True), 0)
            self.assertEqual(routines.run(self.routine, self.tmp, catch_up=True), 0)
        folder = routines.RUN_LOGS / 'alpha'
        self.assertEqual(len(list(folder.glob('2*.log'))), 1)
        self.assertIn('\n1\n', next(folder.glob('2*.log')).read_text())
        state = json.loads((folder / 'last-run.json').read_text())
        self.assertEqual(state['exit'], 0)
        self.assertTrue(state['catchUp'])
        self.assertEqual(self.health()['state'], 'recorded')

    def test_whole_run_disk_budget_blocks_before_command_start(self):
        self.routine.meta['disk-gib'] = '36'
        from types import SimpleNamespace
        with mock.patch.object(routines, 'local_clock', return_value=self.now), mock.patch.object(routines.shutil, 'disk_usage', return_value=SimpleNamespace(free=20 * 1024**3)), mock.patch.object(routines, 'run_paths', return_value={'REPO': str(self.tmp), 'WORKTREE_BASE': str(self.tmp/'wt'), 'WORKSPACE': str(self.tmp/'ws')}), mock.patch.object(routines.subprocess, 'run') as execute, redirect_stdout(io.StringIO()):
            self.assertEqual(routines.run(self.routine, self.tmp), 2)
            execute.assert_not_called()
        self.assertEqual(self.health()['state'], 'failed')

    def test_missing_executable_is_a_failed_attempt_not_a_missing_night(self):
        self.routine.meta['command'] = '/does/not/exist'
        with mock.patch.object(routines, 'local_clock', return_value=self.now), redirect_stdout(io.StringIO()):
            self.assertEqual(routines.run(self.routine, self.tmp), 2)
        self.assertEqual(self.health()['state'], 'failed')

    def test_external_journal_lock_prevents_catch_up(self):
        self.routine.meta['journal'] = 'nightly/ci-journal'
        journal = self.tmp / 'wt' / 'ci-journal'
        journal.mkdir(parents=True)
        (journal / '.ci-lock').write_text('other agent may be active')
        with mock.patch.object(routines, 'local_clock', return_value=self.now), redirect_stdout(io.StringIO()):
            self.assertEqual(routines.run(self.routine, self.tmp, catch_up=True), 2)
        self.assertFalse((routines.RUN_LOGS / 'alpha' / 'last-run.json').exists())

    def test_audit_returns_nonzero_for_missing_adopted_routine(self):
        with mock.patch.object(routines, 'adopted', return_value=['test scheduler']), mock.patch.object(routines, 'local_clock', return_value=self.now), redirect_stdout(io.StringIO()):
            self.assertEqual(routines.main(['--repo', str(self.tmp), 'audit', '--json']), 1)

    def test_notification_only_on_new_or_changed_problem_and_quiet_recovery(self):
        from types import SimpleNamespace
        report = {'routines': [{'name': 'alpha', 'health': {'state': 'overdue'}}]}
        with mock.patch.object(routines.sys, 'platform', 'darwin'), mock.patch.object(
                routines.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stderr='')) as send:
            self.assertTrue(routines.notify_health(report))
            self.assertTrue(routines.notify_health(report))
            self.assertEqual(send.call_count, 1)
            report['routines'][0]['health']['state'] = 'unknown'
            self.assertTrue(routines.notify_health(report))
            report['routines'][0]['health']['state'] = 'overdue'
            self.assertTrue(routines.notify_health(report))
            self.assertEqual(send.call_count, 1)
            report['routines'][0]['health']['state'] = 'failed'
            self.assertTrue(routines.notify_health(report))
            self.assertEqual(send.call_count, 2)
            report['routines'][0]['health']['state'] = 'recorded'
            self.assertTrue(routines.notify_health(report))
            self.assertEqual(send.call_count, 2)
            report['routines'][0]['health']['state'] = 'overdue'
            self.assertTrue(routines.notify_health(report))
            self.assertEqual(send.call_count, 3)

    def test_failed_notification_is_retried(self):
        from types import SimpleNamespace
        report = {'routines': [{'name': 'alpha', 'health': {'state': 'overdue'}}]}
        with mock.patch.object(routines.sys, 'platform', 'darwin'), mock.patch.object(
                routines.subprocess, 'run', side_effect=[OSError('unavailable'),
                                                         SimpleNamespace(returncode=0, stderr='')]) as send:
            self.assertFalse(routines.notify_health(report))
            self.assertTrue(routines.notify_health(report))
            self.assertEqual(send.call_count, 2)


if __name__ == "__main__":
    unittest.main()
