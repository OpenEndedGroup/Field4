import contextlib
import io
import os
import subprocess
import tempfile
import unittest
import unittest.mock
from pathlib import Path

import tools.reap_merged_worktrees as reaper
from tools.reap_merged_worktrees import (
    DEFAULT_MIN_IDLE_HOURS,
    REFUSAL_LABELS,
    DiskRow,
    RetentionFamily,
    branch_idle_hours,
    classify_worktree,
    disk_rows,
    family_for,
    parse_lsof_cwds,
    parse_worktrees,
    pids_inside,
    print_disk_report,
    print_policy,
    retention_families,
)

TOOL = Path(__file__).resolve().parent / "reap_merged_worktrees.py"
REPO_ROOT = TOOL.parent.parent

BASE = Path("/trees")
AGENT = Path("/repo/.claude/worktrees")
FAMILIES = retention_families(BASE, AGENT)
NIGHTLY_ONLY = frozenset({"nightly"})
GIB = 1048576  # KiB


def captured(fn, *args, **kwargs) -> str:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        fn(*args, **kwargs)
    return buffer.getvalue()


class PolicyTests(unittest.TestCase):
    """The scope rules, which are the part a human has to be able to predict."""

    def test_a_family_needs_both_the_root_and_the_prefix(self) -> None:
        # The bug this replaced was one-axis: the prefix decided everything and the root was checked
        # somewhere else, or not at all.
        self.assertEqual(family_for(BASE / "w", "nightly/2026-08-01/example", FAMILIES).name, "nightly")
        self.assertIsNone(family_for(AGENT / "w", "nightly/2026-08-01/example", FAMILIES))
        self.assertIsNone(family_for(BASE / "w", "claude/session-1234", FAMILIES))
        self.assertEqual(family_for(AGENT / "w", "claude/session-1234", FAMILIES).name, "agent-session")

    def test_the_default_family_still_covers_exactly_what_it_used_to(self) -> None:
        for branch in ("nightly/2026-08-01/example", "review/example-on-main", "integration/nightly-four"):
            self.assertEqual(family_for(BASE / "w", branch, FAMILIES).name, "nightly", branch)
        for branch in ("main", "feature/user-work", "docs/voice-pass-2"):
            self.assertIsNone(family_for(BASE / "w", branch, FAMILIES), branch)

    def test_protected_branches_outrank_every_family(self) -> None:
        for branch in ("nightly/journal", "nightly/ci-journal"):
            self.assertIsNone(family_for(BASE / branch, branch, FAMILIES), branch)
        # ...including a family written specifically to claim one.
        greedy = (RetentionFamily("greedy", (BASE,), ("nightly/",), True, "test"),)
        self.assertIsNone(family_for(BASE / "journal", "nightly/journal", greedy))

    def test_a_detached_head_belongs_to_no_family(self) -> None:
        self.assertIsNone(family_for(BASE / "w", None, FAMILIES))

    def test_the_blast_family_is_named_and_reaches_both_roots(self) -> None:
        blast = next(f for f in FAMILIES if f.name == "docs-blast")
        self.assertFalse(blast.default_on)
        self.assertEqual(set(blast.roots), {BASE, AGENT})
        self.assertEqual(family_for(BASE / "w", "docs/language-2026-09-05", FAMILIES).name, "docs-blast")
        self.assertEqual(family_for(AGENT / "w", "docs/language-2026-09-05", FAMILIES).name, "docs-blast")
        # A neighbouring docs branch that is not blast output stays out.
        self.assertIsNone(family_for(BASE / "w", "docs/nav-dissolve-staging", FAMILIES))

    def test_only_the_nightly_family_is_on_by_default(self) -> None:
        self.assertEqual({f.name for f in FAMILIES if f.default_on}, {"nightly"})

    def test_every_family_explains_itself(self) -> None:
        for family in FAMILIES:
            self.assertTrue(family.why.strip(), family.name)
            self.assertTrue(family.roots, family.name)
            self.assertTrue(family.branch_prefixes, family.name)


class RepoFixture:
    """A throwaway repo with both worktree roots, so the policy can be tested against real git.

    Nothing here is ever removed by the tool under test: these tests exercise classification and
    reporting only, never ``--apply``.
    """

    def __init__(self, root: Path) -> None:
        self.repo = root / "repo"
        self.base = root / "repo-worktrees"
        self.agent = self.repo / ".claude/worktrees"
        self.repo.mkdir(parents=True)
        self.base.mkdir(parents=True)
        self.git("init", "-q", "-b", "main", ".")
        self.git("config", "user.email", "nightly@example.invalid")
        self.git("config", "user.name", "Nightly")
        (self.repo / "a.txt").write_text("one\n")
        self.git("add", "a.txt")
        self.commit("one", "2026-01-01T00:00:00+00:00")
        self.agent.mkdir(parents=True)
        self.families = retention_families(self.base.resolve(), self.agent.resolve())
        self.main_head = self.git("rev-parse", "main").stdout.strip()

    def git(self, *args: str, cwd: Path | None = None, when: str | None = None):
        env = dict(os.environ)
        if when:
            env["GIT_COMMITTER_DATE"] = when
            env["GIT_AUTHOR_DATE"] = when
        return subprocess.run(
            ["git", "-C", str(cwd or self.repo), *args],
            capture_output=True, text=True, check=True, env=env,
        )

    def commit(self, message: str, when: str, cwd: Path | None = None) -> None:
        self.git("commit", "-q", "-m", message, cwd=cwd, when=when)

    def add_worktree(self, root: Path, name: str, branch: str, when: str) -> Path:
        path = root / name
        self.git("worktree", "add", "-q", str(path), "-b", branch, when=when)
        return path

    def trees(self):
        return parse_worktrees(self.git("worktree", "list", "--porcelain").stdout)

    def tree_at(self, path: Path):
        return next(t for t in self.trees() if t.path.resolve() == path.resolve())

    def classify(self, path: Path, *, now: float, enabled=NIGHTLY_ONLY, **kwargs):
        return classify_worktree(
            self.tree_at(path), repo=self.repo, main_head=self.git("rev-parse", "main").stdout.strip(),
            families=self.families, cwds={}, min_idle_hours=DEFAULT_MIN_IDLE_HOURS,
            now_epoch=now, **kwargs,
        )


class GitBackedTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.fx = RepoFixture(Path(self._tmp.name).resolve())
        # Well after every fixture timestamp, so "idle" is deterministic rather than wall-clock.
        self.now = 1800000000.0

    # ---- the idle guard -------------------------------------------------------------------

    def test_idle_guard_refuses_a_branch_that_was_just_touched(self) -> None:
        path = self.fx.add_worktree(self.fx.base, "fresh", "nightly/2026-01-02/fresh", "2026-01-02T00:00:00+00:00")
        # Ask as if it were one hour after that branch was created.
        just_after = 1767312000.0 + 3600
        verdict = self.fx.classify(path, now=just_after)
        self.assertFalse(verdict.removable)
        self.assertEqual(verdict.reason, "in-flight")
        self.assertIn("in flight", verdict.text)

    def test_idle_guard_allows_the_same_branch_once_it_goes_quiet(self) -> None:
        path = self.fx.add_worktree(self.fx.base, "stale", "nightly/2026-01-02/stale", "2026-01-02T00:00:00+00:00")
        verdict = self.fx.classify(path, now=self.now)
        self.assertTrue(verdict.removable, verdict.text)
        self.assertEqual(verdict.reason, "removable")
        self.assertEqual(verdict.family.name, "nightly")

    def test_a_branch_created_but_never_committed_to_still_reads_as_alive(self) -> None:
        """The case that was nearly deleted: HEAD is main, so nothing about the *commit* is recent."""
        path = self.fx.add_worktree(self.fx.base, "empty", "nightly/2026-09-09/empty", "2026-09-09T08:29:29+00:00")
        head = self.fx.tree_at(path).head
        self.assertEqual(head, self.fx.main_head, "fixture should model a worktree with no commits")
        idle = branch_idle_hours(self.fx.repo, "nightly/2026-09-09/empty", 1788935369.0 + 600)
        self.assertLess(idle, 1.0, "branch creation must count as a sign of life")
        # The commit date alone would have called it ancient and let it through.
        self.assertGreater(branch_idle_hours(self.fx.repo, "main", 1788935369.0), 5000)

    def test_idle_takes_the_most_recent_of_reflog_and_commit_date(self) -> None:
        """A branch created long ago but committed to recently must read as recent."""
        path = self.fx.add_worktree(self.fx.base, "mixed", "nightly/2026-01-02/mixed", "2026-01-02T00:00:00+00:00")
        (path / "b.txt").write_text("two\n")
        self.fx.git("add", "b.txt", cwd=path)
        self.fx.commit("two", "2026-06-01T00:00:00+00:00", cwd=path)
        january, june = 1767312000.0, 1780272000.0
        # Committing also writes a reflog entry, so make the reflog *older* than the commit by hand
        # -- that is the case where taking the newest of the two signals actually matters.
        log = self.fx.repo / ".git/logs/refs/heads/nightly/2026-01-02/mixed"
        log.write_text(log.read_text().replace(f" {int(june)} ", f" {int(january)} "))
        self.assertEqual(branch_idle_hours(self.fx.repo, "nightly/2026-01-02/mixed", january), 0.0)
        idle = branch_idle_hours(self.fx.repo, "nightly/2026-01-02/mixed", june + 3600)
        self.assertAlmostEqual(idle, 1.0, places=3, msg="the June commit, not the January reflog")

    def test_a_future_dated_signal_clamps_to_zero_rather_than_going_negative(self) -> None:
        """A negative idle would sail past any --min-idle-hours floor."""
        self.fx.add_worktree(self.fx.base, "skewed", "nightly/2026-01-02/skewed", "2026-01-02T00:00:00+00:00")
        self.assertEqual(branch_idle_hours(self.fx.repo, "nightly/2026-01-02/skewed", 0.0), 0.0)

    def test_a_branch_with_neither_signal_has_no_idle_time(self) -> None:
        self.fx.add_worktree(self.fx.base, "nolog", "nightly/2026-01-02/nolog", "2026-01-02T00:00:00+00:00")
        self.assertIsNone(branch_idle_hours(self.fx.repo, "nightly/2026-01-02/absent", self.now))

    def test_an_undatable_branch_is_refused_rather_than_assumed_stale(self) -> None:
        """Losing the age signal must fail closed. Failing open would delete the youngest trees,
        because a brand-new branch is exactly the one whose history is thinnest."""
        path = self.fx.add_worktree(self.fx.base, "nolog", "nightly/2026-01-02/nolog", "2026-01-02T00:00:00+00:00")
        with unittest.mock.patch.object(reaper, "branch_idle_hours", return_value=None):
            verdict = self.fx.classify(path, now=self.now)
        self.assertFalse(verdict.removable)
        self.assertEqual(verdict.reason, "undatable")

    def test_completed_mode_waives_the_idle_guard_and_the_merge_requirement(self) -> None:
        """Otherwise --completed rejects every call it exists to serve: you have just been working."""
        path = self.fx.add_worktree(self.fx.base, "done", "nightly/2026-01-02/done", "2026-01-02T00:00:00+00:00")
        (path / "c.txt").write_text("three\n")
        self.fx.git("add", "c.txt", cwd=path)
        self.fx.commit("three", "2026-01-02T00:10:00+00:00", cwd=path)
        just_after = 1767312600.0 + 60
        self.assertFalse(self.fx.classify(path, now=just_after).removable)
        waived = self.fx.classify(path, now=just_after, completed=True)
        self.assertTrue(waived.removable, waived.text)
        self.assertIn("branch preserved", waived.text)

    def test_completed_mode_still_refuses_dirt_and_still_needs_a_family(self) -> None:
        path = self.fx.add_worktree(self.fx.base, "messy", "nightly/2026-01-02/messy", "2026-01-02T00:00:00+00:00")
        (path / "untracked.txt").write_text("work\n")
        self.assertEqual(self.fx.classify(path, now=self.now, completed=True).reason, "dirty")
        outside = self.fx.add_worktree(self.fx.base, "mine", "feature/mine", "2026-01-02T00:00:00+00:00")
        self.assertEqual(self.fx.classify(outside, now=self.now, completed=True).reason, "no-family")

    # ---- the other refusals ---------------------------------------------------------------

    def test_dirt_is_refused_inside_an_enabled_family(self) -> None:
        path = self.fx.add_worktree(self.fx.base, "dirty", "nightly/2026-01-02/dirty", "2026-01-02T00:00:00+00:00")
        (path / "a.txt").write_text("edited\n")
        verdict = self.fx.classify(path, now=self.now)
        self.assertFalse(verdict.removable)
        self.assertEqual(verdict.reason, "dirty")

    def test_an_unmerged_branch_is_refused_in_the_default_mode(self) -> None:
        path = self.fx.add_worktree(self.fx.base, "ahead", "nightly/2026-01-02/ahead", "2026-01-02T00:00:00+00:00")
        (path / "d.txt").write_text("four\n")
        self.fx.git("add", "d.txt", cwd=path)
        self.fx.commit("four", "2026-01-02T00:10:00+00:00", cwd=path)
        verdict = self.fx.classify(path, now=self.now)
        self.assertFalse(verdict.removable)
        self.assertEqual(verdict.reason, "unmerged")

    def test_a_live_cwd_beats_everything_else(self) -> None:
        path = self.fx.add_worktree(self.fx.base, "busy", "nightly/2026-01-02/busy", "2026-01-02T00:00:00+00:00")
        verdict = classify_worktree(
            self.fx.tree_at(path), repo=self.fx.repo, main_head=self.fx.main_head,
            families=self.fx.families, cwds={path.resolve() / "build": {4242}},
            min_idle_hours=DEFAULT_MIN_IDLE_HOURS, now_epoch=self.now,
        )
        self.assertFalse(verdict.removable)
        self.assertEqual(verdict.reason, "live")
        self.assertIn("4242", verdict.text)

    # ---- report / candidate parity, which is the regression that matters ------------------

    def test_agent_root_tree_with_a_nightly_name_is_not_counted_as_reclaimable(self) -> None:
        """The old report added this row to the "enough to close the requirement" subtotal, while
        ``--apply`` refused it on the root check and freed nothing."""
        path = self.fx.add_worktree(self.fx.agent, "impostor", "nightly/2026-01-02/impostor", "2026-01-02T00:00:00+00:00")
        rows = disk_rows(
            [self.fx.agent], self.fx.trees(), self.fx.repo, self.fx.main_head, {},
            self.fx.families, NIGHTLY_ONLY, DEFAULT_MIN_IDLE_HOURS, self.now,
        )
        row = next(r for r in rows if r.path == path.resolve())
        self.assertFalse(row.reclaimable)
        self.assertEqual(row.reason, "no-family")
        self.assertIsNone(row.offered_by, "it is not on offer either -- no family covers it")

    def test_enabling_a_family_moves_a_row_from_withheld_to_reclaimable(self) -> None:
        path = self.fx.add_worktree(self.fx.agent, "session", "claude/session-abc123", "2026-01-02T00:00:00+00:00")

        def row_for(enabled):
            rows = disk_rows(
                [self.fx.agent], self.fx.trees(), self.fx.repo, self.fx.main_head, {},
                self.fx.families, enabled, DEFAULT_MIN_IDLE_HOURS, self.now,
            )
            return next(r for r in rows if r.path == path.resolve())

        withheld = row_for(NIGHTLY_ONLY)
        self.assertFalse(withheld.reclaimable)
        self.assertEqual(withheld.offered_by, "agent-session")
        self.assertIn("not enabled", withheld.verdict)

        opened = row_for(frozenset({"nightly", "agent-session"}))
        self.assertTrue(opened.reclaimable)
        self.assertIsNone(opened.offered_by)

    def test_an_unremovable_row_is_never_offered_by_a_disabled_family(self) -> None:
        """Enabling a family must not be advertised as freeing space that is dirty or unmerged."""
        path = self.fx.add_worktree(self.fx.agent, "dirty-session", "claude/session-dirty", "2026-01-02T00:00:00+00:00")
        (path / "scratch.txt").write_text("work in progress\n")
        rows = disk_rows(
            [self.fx.agent], self.fx.trees(), self.fx.repo, self.fx.main_head, {},
            self.fx.families, NIGHTLY_ONLY, DEFAULT_MIN_IDLE_HOURS, self.now,
        )
        row = next(r for r in rows if r.path == path.resolve())
        self.assertFalse(row.reclaimable)
        self.assertIsNone(row.offered_by)
        self.assertEqual(row.reason, "dirty")

    def test_an_unregistered_directory_is_reported_and_never_reclaimable(self) -> None:
        stray = self.fx.base / "leftover"
        stray.mkdir()
        (stray / "big.bin").write_text("x" * 1024)
        rows = disk_rows(
            [self.fx.base], self.fx.trees(), self.fx.repo, self.fx.main_head, {},
            self.fx.families, NIGHTLY_ONLY, DEFAULT_MIN_IDLE_HOURS, self.now,
        )
        row = next(r for r in rows if r.path == stray.resolve())
        self.assertFalse(row.reclaimable)
        self.assertEqual(row.reason, "unregistered")

    def test_the_report_subtotal_equals_what_apply_would_select(self) -> None:
        """Report and candidate selection must not be able to drift apart again."""
        self.fx.add_worktree(self.fx.base, "ok1", "nightly/2026-01-02/ok1", "2026-01-02T00:00:00+00:00")
        self.fx.add_worktree(self.fx.base, "ok2", "review/ok2", "2026-01-02T00:00:00+00:00")
        self.fx.add_worktree(self.fx.base, "mine", "feature/mine", "2026-01-02T00:00:00+00:00")
        self.fx.add_worktree(self.fx.agent, "session", "claude/session-abc", "2026-01-02T00:00:00+00:00")
        trees = self.fx.trees()
        rows = disk_rows(
            [self.fx.base, self.fx.agent], trees, self.fx.repo, self.fx.main_head, {},
            self.fx.families, NIGHTLY_ONLY, DEFAULT_MIN_IDLE_HOURS, self.now,
        )
        reported = {r.path for r in rows if r.reclaimable}
        selected = {
            t.path.resolve()
            for t in trees
            if (v := classify_worktree(
                t, repo=self.fx.repo, main_head=self.fx.main_head, families=self.fx.families,
                cwds={}, min_idle_hours=DEFAULT_MIN_IDLE_HOURS, now_epoch=self.now,
            )).removable and v.family is not None and v.family.name in NIGHTLY_ONLY
        }
        self.assertEqual(reported, selected)
        self.assertEqual({p.name for p in reported}, {"ok1", "ok2"})


class CommandLineTests(unittest.TestCase):
    """End to end, against a throwaway repo. Dry run only -- --apply is never invoked."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.fx = RepoFixture(Path(self._tmp.name).resolve())

    def run_tool(self, *args: str):
        return subprocess.run(
            ["python3", str(TOOL), "--repo", str(self.fx.repo), *args],
            capture_output=True, text=True, cwd=REPO_ROOT,
        )

    def test_explain_policy_prints_the_table_without_scanning(self) -> None:
        result = self.run_tool("--explain-policy")
        self.assertEqual(result.returncode, 0, result.stderr)
        for expected in ("nightly", "docs-blast", "agent-session", "--family", "nightly/journal"):
            self.assertIn(expected, result.stdout)
        self.assertNotIn("DRY RUN", result.stdout, "--explain-policy must not scan")

    def test_an_unknown_family_is_refused_by_name(self) -> None:
        result = self.run_tool("--family", "everything")
        self.assertEqual(result.returncode, 2)
        self.assertIn("unknown --family everything", result.stderr)
        self.assertIn("agent-session", result.stderr, "the error should list the real names")

    def test_a_dry_run_removes_nothing_and_says_so(self) -> None:
        self.fx.add_worktree(self.fx.base, "ok1", "nightly/2026-01-02/ok1", "2026-01-02T00:00:00+00:00")
        self.fx.add_worktree(self.fx.agent, "session", "claude/session-abc", "2026-01-02T00:00:00+00:00")
        before = sorted(p.name for p in self.fx.base.iterdir()) + sorted(p.name for p in self.fx.agent.iterdir())
        result = self.run_tool("--base", str(self.fx.base), "--family", "agent-session")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("DRY RUN", result.stdout)
        self.assertIn("policy: nightly=on", result.stdout)
        self.assertIn("agent-session=on", result.stdout)
        after = sorted(p.name for p in self.fx.base.iterdir()) + sorted(p.name for p in self.fx.agent.iterdir())
        self.assertEqual(before, after, "a dry run must not touch the disk")

    def test_completed_outside_every_enabled_family_is_an_explicit_error(self) -> None:
        path = self.fx.add_worktree(self.fx.agent, "session", "claude/session-abc", "2026-01-02T00:00:00+00:00")
        result = self.run_tool("--base", str(self.fx.base), "--completed", str(path))
        self.assertEqual(result.returncode, 2)
        self.assertIn("outside every enabled retention family", result.stderr)
        self.assertIn("--family agent-session", result.stderr, "say which flag would help")
        # ...and naming the family makes it work, still as a dry run.
        allowed = self.run_tool(
            "--base", str(self.fx.base), "--completed", str(path), "--family", "agent-session"
        )
        self.assertEqual(allowed.returncode, 0, allowed.stderr)
        self.assertIn("branches preserved", allowed.stdout)
        self.assertTrue(path.is_dir(), "dry run must leave the checkout alone")

    def test_completed_on_a_protected_branch_does_not_suggest_a_flag(self) -> None:
        """There is no flag, and hinting at one invites somebody to go looking for it."""
        path = self.fx.add_worktree(self.fx.base, "journal", "nightly/journal", "2026-01-02T00:00:00+00:00")
        result = self.run_tool("--base", str(self.fx.base), "--completed", str(path))
        self.assertEqual(result.returncode, 2)
        self.assertIn("protected branch", result.stderr)
        self.assertIn("no flag will", result.stderr)
        self.assertNotIn("--family nightly", result.stderr)


class ReportTests(unittest.TestCase):
    def test_disk_report_counts_only_safe_rows_and_fails_a_deficit(self) -> None:
        rows = [
            DiskRow(Path("/large-but-dirty"), 9 * GIB, "dirty", False, "dirty"),
            DiskRow(Path("/small-and-merged"), 2 * GIB, "clean and merged", True, "removable"),
        ]
        out = captured(
            lambda: self.assertFalse(print_disk_report(
                rows, free_kib=5 * GIB, total_kib=100 * GIB, required_kib=8 * GIB,
            ))
        )
        self.assertIn("deficit 3.00 GiB", out)
        self.assertIn("reclaimable now: 2.00 GiB across 1 worktree(s)", out)
        self.assertIn("does not close the requirement", out)

    def test_disk_report_passes_when_reservation_is_already_clear(self) -> None:
        self.assertTrue(
            print_disk_report([], free_kib=12 * GIB, total_kib=100 * GIB, required_kib=8 * GIB)
        )

    def test_report_names_a_withheld_family_and_whether_it_would_close_the_gate(self) -> None:
        rows = [
            DiskRow(Path("/a"), 6 * GIB, "clean and merged, but family agent-session is not enabled",
                    False, "withheld", "agent-session"),
            DiskRow(Path("/b"), 1 * GIB, "clean and merged", True, "removable"),
        ]
        out = captured(print_disk_report, rows, free_kib=2 * GIB, total_kib=100 * GIB, required_kib=8 * GIB)
        self.assertIn("reclaimable now: 1.00 GiB", out)
        self.assertIn("does not close the requirement", out)
        self.assertIn("--family agent-session", out)
        self.assertIn("6.00 GiB", out)
        self.assertIn("would close the requirement", out)

    def test_report_says_when_even_the_widest_family_is_not_enough(self) -> None:
        rows = [DiskRow(Path("/a"), 1 * GIB, "withheld", False, "withheld", "docs-blast")]
        out = captured(print_disk_report, rows, free_kib=1 * GIB, total_kib=100 * GIB, required_kib=50 * GIB)
        self.assertIn("still short", out)
        self.assertNotIn("would close the requirement", out)

    def test_report_tallies_refusals_by_reason_and_excludes_offers(self) -> None:
        rows = [
            DiskRow(Path("/a"), 3 * GIB, "dirty", False, "dirty"),
            DiskRow(Path("/b"), 2 * GIB, "dirty", False, "dirty"),
            DiskRow(Path("/c"), 4 * GIB, "in flight", False, "in-flight"),
            DiskRow(Path("/d"), 9 * GIB, "withheld", False, "withheld", "agent-session"),
        ]
        out = captured(print_disk_report, rows, free_kib=99 * GIB, total_kib=100 * GIB, required_kib=8 * GIB)
        self.assertIn("2 dirty (5.00 GiB)", out)
        self.assertIn("1 in flight (4.00 GiB)", out)
        self.assertNotIn("withheld (", out, "an offer is not a refusal")

    def test_every_reason_the_classifier_emits_has_a_printable_label(self) -> None:
        emitted = {"no-family", "live", "dirty", "in-flight", "undatable", "unmerged", "unregistered"}
        self.assertEqual(emitted - set(REFUSAL_LABELS), set())

    def test_policy_printout_names_every_family_and_the_protected_branches(self) -> None:
        out = captured(print_policy, FAMILIES, NIGHTLY_ONLY)
        for family in FAMILIES:
            self.assertIn(family.name, out)
            self.assertIn(family.why[:40], out)
        self.assertIn("[enabled]", out)
        self.assertIn("--family docs-blast", out)
        self.assertIn("nightly/journal", out)


class PolicyPropertyTests(unittest.TestCase):
    """Invariants that must hold over a whole synthetic population, in every family configuration.

    The per-case tests above each pin one decision. These pin the relationships *between* decisions,
    which is where a policy about deleting things tends to go wrong: a flag that is supposed to widen
    scope quietly lifting a protection, or a report that stops agreeing with the candidate list for
    some configuration nobody tried by hand.
    """

    NOW = 1800000000.0
    OLD = "1767312000 +0000"  # 2026-01-02, ~9080 h before NOW
    FRESH = "1799996400 +0000"  # NOW minus one hour

    @classmethod
    def setUpClass(cls) -> None:
        cls._tmp = tempfile.TemporaryDirectory()
        fx = RepoFixture(Path(cls._tmp.name).resolve())
        cls.fx = fx
        cls.protected: set[Path] = set()
        # Root x branch-family x state. Not every combination is meaningful, but every family and
        # every refusal reason appears under both roots.
        plan = [
            (fx.base, "n-stale", "nightly/2026-01-02/stale", cls.OLD, "clean"),
            (fx.base, "n-dirty", "nightly/2026-01-02/dirty", cls.OLD, "dirty"),
            (fx.base, "n-ahead", "nightly/2026-01-02/ahead", cls.OLD, "commit"),
            (fx.base, "n-fresh", "nightly/2026-01-02/fresh", cls.FRESH, "clean"),
            (fx.base, "r-stale", "review/stale", cls.OLD, "clean"),
            (fx.base, "journal", "nightly/journal", cls.OLD, "clean"),
            (fx.base, "cijournal", "nightly/ci-journal", cls.OLD, "clean"),
            (fx.base, "human", "feature/human-work", cls.OLD, "clean"),
            (fx.base, "b-stale", "docs/language-2026-01-02", cls.OLD, "clean"),
            (fx.agent, "a-stale", "claude/session-stale", cls.OLD, "clean"),
            (fx.agent, "a-dirty", "claude/session-dirty", cls.OLD, "dirty"),
            (fx.agent, "a-ahead", "claude/session-ahead", cls.OLD, "commit"),
            (fx.agent, "a-fresh", "claude/session-fresh", cls.FRESH, "clean"),
            (fx.agent, "ab-stale", "docs/language-2026-01-03", cls.OLD, "clean"),
            (fx.agent, "a-nightly", "nightly/2026-01-02/misfiled", cls.OLD, "clean"),
        ]
        for root, name, branch, when, state in plan:
            path = fx.add_worktree(root, name, branch, when)
            if branch in ("nightly/journal", "nightly/ci-journal"):
                cls.protected.add(path.resolve())
            if state == "dirty":
                (path / "wip.txt").write_text("work\n")
            elif state == "commit":
                (path / "extra.txt").write_text("extra\n")
                fx.git("add", "extra.txt", cwd=path)
                fx.commit("extra", when, cwd=path)
        cls.trees = fx.trees()

    @classmethod
    def tearDownClass(cls) -> None:
        cls._tmp.cleanup()

    # Every property below sweeps all eight family configurations, and the classification of one
    # worktree does not depend on which families are *enabled* -- only on the policy and the idle
    # floor. Without these caches the sweep re-forks git a few thousand times for no new information.
    _rows_cache: dict = {}
    _removable_cache: dict = {}

    def rows(self, enabled, min_idle=DEFAULT_MIN_IDLE_HOURS):
        key = (frozenset(enabled), min_idle)
        if key not in self._rows_cache:
            self._rows_cache[key] = disk_rows(
                [self.fx.base, self.fx.agent], self.trees, self.fx.repo, self.fx.main_head, {},
                self.fx.families, frozenset(enabled), min_idle, self.NOW,
            )
        return self._rows_cache[key]

    def removable(self, min_idle: float) -> dict[Path, str]:
        """Removable worktrees and the family that claims each, independent of what is enabled."""
        if min_idle not in self._removable_cache:
            found = {}
            for tree in self.trees:
                verdict = classify_worktree(
                    tree, repo=self.fx.repo, main_head=self.fx.main_head, families=self.fx.families,
                    cwds={}, min_idle_hours=min_idle, now_epoch=self.NOW,
                )
                if verdict.removable and verdict.family is not None:
                    found[tree.path.resolve()] = verdict.family.name
            self._removable_cache[min_idle] = found
        return self._removable_cache[min_idle]

    def selected(self, enabled, min_idle=DEFAULT_MIN_IDLE_HOURS) -> set[Path]:
        return {
            path for path, family in self.removable(min_idle).items() if family in enabled
        }

    def configurations(self):
        names = [family.name for family in self.fx.families]
        for bits in range(1 << len(names)):
            yield frozenset(n for i, n in enumerate(names) if bits >> i & 1)

    def test_the_fixture_actually_exercises_the_policy(self) -> None:
        """A property suite over a population that reaches nothing would pass vacuously."""
        self.assertEqual(len(self.trees), 16, "15 worktrees plus the primary checkout")
        widest = self.selected({"nightly", "docs-blast", "agent-session"})
        self.assertEqual(
            {p.name for p in widest}, {"n-stale", "r-stale", "b-stale", "a-stale", "ab-stale"}
        )
        self.assertEqual(self.selected({"nightly"}), self.selected(["nightly"]))

    def test_no_configuration_ever_reaches_a_protected_branch(self) -> None:
        for enabled in self.configurations():
            self.assertEqual(
                self.selected(enabled) & self.protected, set(), f"enabled={sorted(enabled)}"
            )

    def test_enabling_a_family_only_ever_adds(self) -> None:
        """--family is documented as additive; a widening flag that narrows would be a trap."""
        for enabled in self.configurations():
            base = self.selected(enabled)
            for family in self.fx.families:
                wider = self.selected(enabled | {family.name})
                self.assertTrue(
                    base <= wider,
                    f"enabling {family.name} removed {sorted(base - wider)} from {sorted(enabled)}",
                )

    def test_every_selection_is_clean_merged_idle_and_claimed(self) -> None:
        for enabled in self.configurations():
            for path in self.selected(enabled):
                tree = next(t for t in self.trees if t.path.resolve() == path)
                self.assertEqual(
                    self.fx.git("status", "--porcelain", cwd=path).stdout.strip(), "", f"{path} dirty"
                )
                self.assertEqual(
                    subprocess.run(
                        ["git", "-C", str(self.fx.repo), "merge-base", "--is-ancestor",
                         tree.head, self.fx.main_head], capture_output=True,
                    ).returncode, 0, f"{path} unmerged",
                )
                idle = branch_idle_hours(self.fx.repo, tree.branch, self.NOW)
                self.assertGreaterEqual(idle, DEFAULT_MIN_IDLE_HOURS, f"{path} not idle")
                family = family_for(path, tree.branch, self.fx.families)
                self.assertIn(family.name, enabled, f"{path} claimed by nothing enabled")

    def test_report_and_selection_agree_in_every_configuration(self) -> None:
        for enabled in self.configurations():
            reported = {row.path for row in self.rows(enabled) if row.reclaimable}
            self.assertEqual(reported, self.selected(enabled), f"enabled={sorted(enabled)}")

    def test_an_offer_is_exactly_what_enabling_that_family_would_add(self) -> None:
        for enabled in self.configurations():
            offers: dict[str, set[Path]] = {}
            for row in self.rows(enabled):
                if row.offered_by is not None:
                    offers.setdefault(row.offered_by, set()).add(row.path)
            for family in self.fx.families:
                if family.name in enabled:
                    self.assertNotIn(family.name, offers, "an enabled family is not an offer")
                    continue
                added = self.selected(enabled | {family.name}) - self.selected(enabled)
                self.assertEqual(
                    offers.get(family.name, set()), added,
                    f"offer for {family.name} misstates what enabling it adds, from {sorted(enabled)}",
                )

    def test_raising_the_idle_floor_only_ever_removes(self) -> None:
        enabled = {"nightly", "docs-blast", "agent-session"}
        previous = self.selected(enabled, min_idle=0.0)
        self.assertTrue(previous, "a zero floor should select the fresh worktrees too")
        for floor in (1.0, 12.0, 1000.0, 20000.0):
            current = self.selected(enabled, min_idle=floor)
            self.assertTrue(current <= previous, f"floor {floor} added {sorted(current - previous)}")
            previous = current
        self.assertEqual(previous, set(), "a floor above every branch's age must select nothing")

    def test_a_zero_floor_still_refuses_dirt_unmerged_work_and_protection(self) -> None:
        selected = self.selected({"nightly", "docs-blast", "agent-session"}, min_idle=0.0)
        names = {p.name for p in selected}
        self.assertIn("n-fresh", names, "only the idle guard should have been holding this back")
        for refused in ("n-dirty", "a-dirty", "n-ahead", "a-ahead", "journal", "cijournal", "human"):
            self.assertNotIn(refused, names, refused)


class UnchangedBehaviourTests(unittest.TestCase):
    def test_porcelain_parser_keeps_branch_identity(self) -> None:
        trees = parse_worktrees(
            "worktree /repo\nHEAD abc123\nbranch refs/heads/main\n\n"
            "worktree /trees/nightly\nHEAD def456\nbranch refs/heads/nightly/2026-08-01/example\n"
        )
        self.assertEqual(trees[1].path, Path("/trees/nightly"))
        self.assertEqual(trees[1].branch, "nightly/2026-08-01/example")

    def test_completed_mode_is_documented_as_branch_preserving(self) -> None:
        source = TOOL.read_text()
        self.assertIn("--completed", source)
        self.assertIn("branches preserved", source)
        self.assertIn("if not completed:", source)

    def test_the_module_docstring_documents_the_policy_it_enforces(self) -> None:
        """This is a policy about deleting the human's data; --help has to state it."""
        doc = reaper.__doc__ or ""
        for family in retention_families(Path("/base"), Path("/agent")):
            self.assertIn(family.name, doc, f"{family.name} is enforced but undocumented")
        self.assertIn("--explain-policy", doc)
        self.assertIn("IDLE", doc.upper())

    def test_live_cwd_parser_and_nested_path_detection(self) -> None:
        cwds = parse_lsof_cwds("p12\nn/trees/one\np34\nn/trees/two/build\n")
        self.assertEqual(pids_inside(Path("/trees/one"), cwds), {12})
        self.assertEqual(pids_inside(Path("/trees/two"), cwds), {34})
        self.assertEqual(pids_inside(Path("/trees/elsewhere"), cwds), set())


if __name__ == "__main__":
    unittest.main()
