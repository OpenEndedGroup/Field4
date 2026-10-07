"""tools/land.py against a throwaway clone, with merge_regen.py stubbed so no Gradle runs."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

LAND = Path(__file__).resolve().parent / "land.py"

# Stands in for tools/merge_regen.py: a plain --no-ff merge with the lander's message, or a failure
# when the merged tree contains a file named BREAKS, the way a red guard would refuse it.
STUB = '''
import sys, pathlib
# The lander now merges each branch itself and calls this once per batch, as --regen-since BASE -m MSG.
# The guard "fails" when the merged tree contains a file named BREAKS, or two files named PAIR_A and
# PAIR_B (a combination that breaks only together); it writes REGEN_CALLS so tests can count guards.
assert sys.argv[1] == "--regen-since", sys.argv
calls = pathlib.Path(__file__).resolve().parents[2] / "REGEN_CALLS"
calls.write_text(calls.read_text() + "x" if calls.exists() else "x")
if pathlib.Path("HANGS").exists():
    import time; time.sleep(600)
if pathlib.Path("SLOW").exists():
    import time; time.sleep(1.5)
if pathlib.Path("BREAKS").exists() or (pathlib.Path("PAIR_A").exists() and pathlib.Path("PAIR_B").exists()):
    print("merge_regen: a guard failed on the batch", file=sys.stderr)
    sys.exit(1)
'''

DOC_STUB = '''
import pathlib, sys
root = pathlib.Path.cwd().parent
record = root / "DOC_RUNS"
line = f"{pathlib.Path('seed.txt').read_text().strip()}:{pathlib.Path('alpha.txt').exists()}:{pathlib.Path('beta.txt').exists()}\\n"
record.write_text((record.read_text() if record.exists() else "") + line)
if pathlib.Path("DOCS_BREAKS").exists():
    print("docs build failed deliberately", file=sys.stderr)
    sys.exit(1)
pathlib.Path("public/field4/keyframe_editor").mkdir(parents=True, exist_ok=True)
pathlib.Path("public/field4/keyframe_editor/index.html").write_text("built docs")
'''


class LandQueueTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.primary = root / "repo"
        self.lander = root / "lander"
        self.env = {
            **os.environ,
            "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
            "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t",
            "FIELD_LAND_BY": "test-agent",
            # Never seed a private Gradle home from the real ~/.gradle in a test.
            "FIELD_LAND_GRADLE_HOME": "shared",
        }
        self.primary.mkdir()
        self.git(self.primary, "init", "-q", "-b", "main")
        self.git(self.primary, "config", "core.hooksPath", "/dev/null")
        self.git(self.primary, "config", "merge.field-generated.driver", "true")
        (self.primary / "tools").mkdir()
        (self.primary / "tools/merge_regen.py").write_text(STUB)
        (self.primary / "tools/build_zensical_docs.py").write_text(DOC_STUB)
        (self.primary / "seed.txt").write_text("seed\n")
        self.git(self.primary, "add", "-A")
        self.git(self.primary, "commit", "-q", "-m", "seed")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def git(self, cwd: Path, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True, env=self.env).stdout.strip()

    def land(self, cwd: Path, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(LAND), *args], cwd=cwd, capture_output=True, text=True, env=self.env)

    def branch_with(self, name: str, filename: str) -> Path:
        wt = Path(self._tmp.name) / name
        self.git(self.primary, "worktree", "add", "-q", "-b", name, str(wt), "main")
        (wt / filename).write_text(f"{name}\n")
        self.git(wt, "add", "-A")
        self.git(wt, "commit", "-q", "-m", f"work on {name}")
        return wt

    def run_lander(self) -> subprocess.CompletedProcess[str]:
        return self.land(self.primary, "run", "--once", "--worktree", str(self.lander))

    def test_two_branches_land_in_order_with_attributed_commits(self) -> None:
        for name in ("alpha", "beta"):
            self.assertEqual(self.land(self.branch_with(name, f"{name}.txt"), "enqueue").returncode, 0)
        result = self.run_lander()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        subjects = self.git(self.primary, "log", "--format=%s", "main").splitlines()
        self.assertEqual(subjects[:2], ["Land beta (test-agent)", "Land alpha (test-agent)"])
        self.assertTrue((self.primary / "beta.txt").exists(), "primary's checkout was not fast-forwarded")

    def test_doc_item_separates_landing_batches_and_builds_the_main_checkout(self) -> None:
        self.assertEqual(self.land(self.branch_with("alpha", "alpha.txt"), "enqueue").returncode, 0)
        request = self.land(self.primary, "doc")
        self.assertEqual(request.returncode, 0, request.stdout + request.stderr)
        doc_id = request.stdout.split("queued docs build ", 1)[1].split(" ", 1)[0]
        self.assertEqual(self.land(self.branch_with("beta", "beta.txt"), "enqueue").returncode, 0)
        result = self.run_lander()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((Path(self._tmp.name) / "DOC_RUNS").read_text(), "seed:True:False\n")
        self.assertEqual((self.primary / "public/field4/keyframe_editor/index.html").read_text(), "built docs")
        self.assertEqual(self.guard_calls(), 2, "the docs item must split the branch batches")
        self.assertIn("built", self.land(self.primary, "status", doc_id).stdout)
        self.assertEqual(self.land(self.primary, "wait", doc_id).returncode, 0)

    def test_failed_doc_item_reports_failure_and_does_not_bounce_later_branch(self) -> None:
        (self.primary / "DOCS_BREAKS").write_text("yes\n")
        request = self.land(self.primary, "doc")
        doc_id = request.stdout.split("queued docs build ", 1)[1].split(" ", 1)[0]
        self.land(self.branch_with("beta", "beta.txt"), "enqueue")
        result = self.run_lander()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.land(self.primary, "wait", doc_id).returncode, 1)
        self.assertIn("docs build failed deliberately", self.land(self.primary, "status", doc_id).stdout)
        self.assertIn("Land beta", self.git(self.primary, "log", "-1", "--format=%s", "main"))

    def test_doc_item_waits_for_an_earlier_blocked_landing(self) -> None:
        wt = self.branch_with("alpha", "alpha.txt")
        (wt / "seed.txt").write_text("alpha changed seed\n")
        self.git(wt, "commit", "-qam", "edit seed")
        (self.primary / "seed.txt").write_text("uncommitted\n")
        self.land(wt, "enqueue")
        self.land(self.primary, "doc")
        self.land(self.branch_with("beta", "beta.txt"), "enqueue")
        self.run_lander()
        self.assertFalse((Path(self._tmp.name) / "DOC_RUNS").exists())
        self.assertEqual(self.states()["beta"], "pending")

    def test_a_failing_landing_bounces_and_leaves_main_alone(self) -> None:
        before = self.git(self.primary, "rev-parse", "main")
        self.land(self.branch_with("broken", "BREAKS"), "enqueue")
        self.run_lander()
        self.assertEqual(self.git(self.primary, "rev-parse", "main"), before)
        status = self.land(self.primary, "status")
        self.assertIn("bounced", status.stdout)
        self.assertIn("a guard failed", status.stdout)

    def test_a_branch_touching_a_dirty_primary_file_waits_instead_of_bouncing(self) -> None:
        wt = self.branch_with("mu", "mu.txt")
        (wt / "seed.txt").write_text("mu changes seed\n")
        self.git(wt, "commit", "-qam", "mu edits seed")
        (self.primary / "seed.txt").write_text("someone's uncommitted edit\n")
        self.land(wt, "enqueue")
        self.land(self.branch_with("nu", "nu.txt"), "enqueue")
        self.run_lander()
        self.assertEqual(self.states(), {"mu": "pending", "nu": "landed"})
        self.assertEqual(self.guard_calls(), 1, "a blocked branch must not cost a guard run")
        self.assertIn("BLOCKED", self.land(self.primary, "status", "--plain").stdout)
        self.assertIn("seed.txt", self.land(self.primary, "status", "--plain").stdout)
        self.assertEqual((self.primary / "seed.txt").read_text(), "someone's uncommitted edit\n")
        self.git(self.primary, "checkout", "--", "seed.txt")  # they finish (here: discard) their edit
        self.run_lander()
        self.assertEqual(self.states()["mu"], "landed")

    def test_dirty_files_in_the_primary_survive_the_fast_forward(self) -> None:
        (self.primary / "seed.txt").write_text("someone else's uncommitted edit\n")
        self.land(self.branch_with("gamma", "gamma.txt"), "enqueue")
        self.run_lander()
        self.assertIn("Land gamma", self.git(self.primary, "log", "-1", "--format=%s", "main"))
        self.assertEqual((self.primary / "seed.txt").read_text(), "someone else's uncommitted edit\n")

    def test_enqueue_refuses_uncommitted_work(self) -> None:
        wt = self.branch_with("delta", "delta.txt")
        (wt / "delta.txt").write_text("not committed\n")
        result = self.land(wt, "enqueue")
        self.assertEqual(result.returncode, 2)
        self.assertIn("uncommitted", result.stderr)

    def test_a_branch_already_on_main_is_not_queued(self) -> None:
        wt = self.branch_with("eps", "eps.txt")
        self.git(self.primary, "merge", "-q", "--ff-only", "eps")
        result = self.land(wt, "enqueue")
        self.assertEqual(result.returncode, 0)
        self.assertIn("already on main", result.stdout)

    def test_a_second_lander_refuses_and_status_sees_the_first(self) -> None:
        self.assertIn("NOT RUNNING", self.land(self.primary, "status").stdout)
        first = subprocess.Popen(
            [sys.executable, str(LAND), "run", "--worktree", str(self.lander)],
            cwd=self.primary, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        try:
            # "lander up in ..." is printed once the lock is held (after git's own worktree output).
            line = ""
            while "lander up" not in line:
                line = first.stdout.readline()
                self.assertNotEqual(line, "", "the first lander exited before it was up")
            second = self.land(self.primary, "run", "--once", "--worktree", str(self.lander) + "-2")
            self.assertEqual(second.returncode, 2)
            self.assertIn("already running", second.stderr)
            self.assertNotIn("NOT RUNNING", self.land(self.primary, "status").stdout)
        finally:
            first.kill()
            first.wait()
        # SIGKILL leaves no cleanup behind, and status must not be fooled by that.
        self.assertIn("NOT RUNNING", self.land(self.primary, "status").stdout)

    def test_the_hook_refuses_a_direct_merge_into_main_but_not_the_lander(self) -> None:
        hooks = Path(self._tmp.name) / "hooks"
        hooks.mkdir()
        hook = hooks / "reference-transaction"
        hook.write_text((LAND.parent / "hooks/reference-transaction").read_text())
        hook.chmod(0o755)
        self.git(self.primary, "config", "core.hooksPath", str(hooks))
        wt = self.branch_with("zeta", "zeta.txt")
        before = self.git(self.primary, "rev-parse", "main")
        direct = subprocess.run(["git", "merge", "--ff-only", "zeta"], cwd=self.primary, capture_output=True, text=True, env=self.env)
        self.assertNotEqual(direct.returncode, 0)
        self.assertIn("land.py enqueue", direct.stderr)
        self.assertEqual(self.git(self.primary, "rev-parse", "main"), before)
        self.git(self.primary, "pack-refs", "--all")  # git's own housekeeping must still work
        self.land(wt, "enqueue")
        self.run_lander()
        self.assertIn("Land zeta", self.git(self.primary, "log", "-1", "--format=%s", "main"))

    def test_a_removed_lander_worktree_is_recreated_on_its_protected_branch(self) -> None:
        self.land(self.branch_with("eta", "eta.txt"), "enqueue")
        self.run_lander()
        self.git(self.primary, "worktree", "remove", "--force", str(self.lander))
        self.land(self.branch_with("theta", "theta.txt"), "enqueue")
        result = self.run_lander()
        self.assertIn("Land theta", self.git(self.primary, "log", "-1", "--format=%s", "main"), result.stdout + result.stderr)
        self.assertEqual(self.git(self.lander, "branch", "--show-current"), "land/lander")

    def test_mine_reports_a_bounce_to_the_branch_that_caused_it(self) -> None:
        wt = self.branch_with("iota", "BREAKS")
        self.land(wt, "enqueue")
        self.run_lander()
        mine = self.land(wt, "mine")
        self.assertEqual(mine.returncode, 1, mine.stdout)
        self.assertIn("a guard failed", mine.stdout)
        self.assertEqual(self.land(self.branch_with("kappa", "k.txt"), "mine").returncode, 0)

    def test_reenqueue_replaces_a_waiting_item_but_never_one_being_landed(self) -> None:
        wt = self.branch_with("lam", "lam.txt")
        self.land(wt, "enqueue")
        (wt / "lam.txt").write_text("second\n")
        self.git(wt, "commit", "-qam", "second")
        self.assertIn("replacing", self.land(wt, "enqueue").stdout)
        queue = Path(self.git(self.primary, "rev-parse", "--path-format=absolute", "--git-common-dir")) / "field-land-queue/pending"
        self.assertEqual(len(list(queue.glob("*.json"))), 1)
        only = next(queue.glob("*.json"))
        only.write_text(only.read_text().replace('"enqueued"', '"started": "2026-10-03T00:00:00+00:00", "enqueued"'))
        (wt / "lam.txt").write_text("third\n")
        self.git(wt, "commit", "-qam", "third")
        self.assertNotIn("replacing", self.land(wt, "enqueue").stdout)
        self.assertEqual(len(list(queue.glob("*.json"))), 2)

    # ------------------------------------------------------------------ batching

    def guard_calls(self) -> int:
        calls = Path(self._tmp.name) / "REGEN_CALLS"
        return len(calls.read_text()) if calls.exists() else 0

    def states(self) -> dict[str, str]:
        queue = Path(self.git(self.primary, "rev-parse", "--path-format=absolute", "--git-common-dir")) / "field-land-queue"
        return {item["branch"]: state
                for state in ("pending", "landed", "bounced") for f in (queue / state).glob("*.json")
                if (item := json.loads(f.read_text())).get("branch")}

    def test_a_batch_shares_one_guard_and_keeps_one_commit_per_branch(self) -> None:
        names = ["b1", "b2", "b3", "b4", "b5"]
        for name in names:
            self.land(self.branch_with(name, f"{name}.txt"), "enqueue")
        self.run_lander()
        self.assertEqual(self.guard_calls(), 1, "five branches should share one regeneration and guard")
        self.assertEqual(set(self.states().values()), {"landed"})
        subjects = self.git(self.primary, "log", "--format=%s", "--first-parent", "main").splitlines()
        self.assertEqual(subjects[:5], [f"Land {n} (test-agent)" for n in reversed(names)])

    def test_a_red_batch_is_bisected_to_the_culprit_and_the_rest_land(self) -> None:
        for name, filename in (("ok1", "ok1.txt"), ("ok2", "ok2.txt"), ("bad", "BREAKS"), ("ok3", "ok3.txt")):
            self.land(self.branch_with(name, filename), "enqueue")
        self.run_lander()
        self.assertEqual(self.states(), {"ok1": "landed", "ok2": "landed", "bad": "bounced", "ok3": "landed"})
        self.assertLessEqual(self.guard_calls(), 5)
        mine = self.land(Path(self._tmp.name) / "bad", "mine")
        self.assertIn("bisecting a red batch of 4", mine.stdout)

    def test_a_pair_that_only_breaks_together_lands_the_first_and_bounces_the_second(self) -> None:
        self.land(self.branch_with("pa", "PAIR_A"), "enqueue")
        self.land(self.branch_with("pb", "PAIR_B"), "enqueue")
        self.run_lander()
        self.assertEqual(self.states(), {"pa": "landed", "pb": "bounced"})

    def test_a_conflict_inside_the_batch_is_deferred_not_blamed(self) -> None:
        for name in ("c1", "c2"):
            wt = self.branch_with(name, f"{name}.txt")
            (wt / "shared.txt").write_text(f"{name}\n")
            self.git(wt, "add", "-A")
            self.git(wt, "commit", "-qm", f"{name} edits shared")
            self.land(wt, "enqueue")
        self.run_lander()
        self.assertEqual(self.states(), {"c1": "landed", "c2": "bounced"})
        mine = self.land(Path(self._tmp.name) / "c2", "mine").stdout
        self.assertIn("conflict with main", mine)

    def test_batch_size_one_lands_one_at_a_time(self) -> None:
        for name in ("s1", "s2", "s3"):
            self.land(self.branch_with(name, f"{name}.txt"), "enqueue")
        self.land(self.primary, "run", "--once", "--batch", "1", "--worktree", str(self.lander))
        self.assertEqual(self.guard_calls(), 3)

    # ------------------------------------------------------------------ pushing

    def with_origin(self) -> Path:
        origin = Path(self._tmp.name) / "origin.git"
        self.git(Path(self._tmp.name), "init", "-q", "--bare", "-b", "main", str(origin))
        self.git(self.primary, "remote", "add", "origin", str(origin))
        self.git(self.primary, "push", "-q", "origin", "main")
        return origin

    def test_push_happens_after_a_landing_and_only_then(self) -> None:
        origin = self.with_origin()
        self.land(self.branch_with("p1", "p1.txt"), "enqueue", "--push")
        self.run_lander()
        self.assertEqual(self.git(origin, "rev-parse", "main"), self.git(self.primary, "rev-parse", "main"))
        self.assertIn("pushed main", self.land(Path(self._tmp.name) / "p1", "mine").stdout)
        status = self.land(self.primary, "status", "--plain").stdout
        self.assertIn("recent pushes", status)
        self.assertIn("-> origin, 2 commit(s)", status)  # the branch commit and its Land merge
        before = self.git(origin, "rev-parse", "main")
        self.land(self.branch_with("p2", "BREAKS"), "enqueue", "--push")
        self.run_lander()
        self.assertEqual(self.git(origin, "rev-parse", "main"), before, "a bounced item must not push")

    def test_sync_brings_origin_in_through_the_queue_then_pushes(self) -> None:
        origin = self.with_origin()
        elsewhere = Path(self._tmp.name) / "elsewhere"
        self.git(Path(self._tmp.name), "clone", "-q", str(origin), str(elsewhere))
        (elsewhere / "theirs.txt").write_text("from another machine\n")
        self.git(elsewhere, "add", "-A")
        self.git(elsewhere, "commit", "-qm", "work pushed from elsewhere")
        self.git(elsewhere, "push", "-q", "origin", "main")
        self.land(self.branch_with("local", "local.txt"), "enqueue")
        self.run_lander()  # local main is now 1 ahead, 1 behind origin
        result = self.land(self.primary, "sync", "--push")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.run_lander()
        self.assertTrue((self.primary / "theirs.txt").exists())
        self.assertIn("-> origin", self.land(self.primary, "status", "--plain").stdout)
        self.assertEqual(self.git(origin, "rev-parse", "main"), self.git(self.primary, "rev-parse", "main"))

    def test_a_wedged_build_is_stopped_retried_once_then_bounced(self) -> None:
        self.env["FIELD_LAND_WATCHDOG_MIN"] = "0.05"  # 3 s of silence
        self.land(self.branch_with("wedge", "HANGS"), "enqueue")
        started = time.time()
        self.run_lander()
        self.assertLess(time.time() - started, 120, "the watchdog did not stop the silent build")
        self.assertEqual(self.states(), {"wedge": "bounced"})
        mine = self.land(Path(self._tmp.name) / "wedge", "mine").stdout
        self.assertIn("watchdog", mine)
        self.assertEqual(self.guard_calls(), 2, "one retry, then the bounce")

    def test_a_build_holds_the_machine_awake_until_it_exits(self) -> None:
        # A stand-in for caffeinate that records how it was asked, and whether the build was still
        # running then; the real one keeps a laptop from sleeping through a landing.
        fake = Path(self._tmp.name) / "fake-caffeinate"
        record = Path(self._tmp.name) / "CAFFEINATE"
        fake.write_text(f"""#!/bin/sh
pid="$3"
if kill -0 "$pid" 2>/dev/null; then alive=alive; else alive=gone; fi
echo "$1 $2 $alive" > {record}
while kill -0 "$pid" 2>/dev/null; do sleep 0.1; done
""")
        fake.chmod(0o755)
        self.env["FIELD_LAND_CAFFEINATE"] = str(fake)
        self.land(self.branch_with("awake", "SLOW"), "enqueue")
        self.run_lander()
        self.assertEqual(self.states(), {"awake": "landed"})
        self.assertEqual(record.read_text().split(), ["-i", "-w", "alive"])

    def test_mine_and_status_survive_a_branchless_doc_item(self) -> None:
        # `land.py doc` items have no branch or sha; mine crashed with KeyError: 'branch' on one.
        wt = self.branch_with("xi", "BREAKS")
        self.land(wt, "enqueue")
        self.run_lander()
        queue = Path(self.git(self.primary, "rev-parse", "--path-format=absolute", "--git-common-dir")) / "field-land-queue"
        (queue / "landed" / "20991231-000000-doc.json").write_text(json.dumps(
            {"id": "20991231-000000-doc", "kind": "doc", "checkout": str(self.primary), "by": "t",
             "enqueued": "2099-12-31T00:00:00+00:00", "finished": "2099-12-31T00:00:01+00:00", "seq": 1}))
        mine = self.land(wt, "mine")
        self.assertNotIn("Traceback", mine.stderr, mine.stderr)
        self.assertEqual(mine.returncode, 1)
        status = self.land(self.primary, "status", "--plain")
        self.assertNotIn("Traceback", status.stderr, status.stderr)


if __name__ == "__main__":
    unittest.main()
