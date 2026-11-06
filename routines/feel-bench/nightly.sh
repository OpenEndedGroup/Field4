#!/bin/zsh
# Nightly feel benchmark: one 100-rep run on local main, checked against this host's committed baseline.
# Report only: never commits, re-baselines or lands.
#
# Waits for AC power and a 1-minute load average under 4 held for 3 minutes, for at most
# --wait-minutes (default 50) after it starts, or until --give-up HH:MM if given. The run itself takes
# ~30 min and must finish before the heavier night routines start, or it measures them instead of
# Field. Prints a final line `NIGHTLY: <verdict>` and writes $FIELD_FEEL_REPORTS/nightly-<date>.txt (default ~/.afield/feel-bench).
#
# Paths: FIELD_REPO (default: the checkout holding this script), FIELD_WORKTREE_BASE (default:
# <repo>-worktrees beside it). Works in its own worktree, <base>/feel-nightly, reset to local main
# each night. `--prepare-only` builds the prerequisites and stops.
setopt pipefail
giveup_at=
wait_minutes=50
prepare_only=0
while [ $# -gt 0 ]; do
  case $1 in
    --give-up) giveup_at=$2; shift 2 ;;
    --wait-minutes) wait_minutes=$2; shift 2 ;;
    --prepare-only) prepare_only=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
script_dir=${0:A:h}
repo=${FIELD_REPO:-$(git -C $script_dir rev-parse --path-format=absolute --git-common-dir 2>/dev/null)}
repo=${repo%/.git}
[ -d "$repo" ] || repo=${script_dir:h:h}
base=${FIELD_WORKTREE_BASE:-${repo}-worktrees}
wt=$base/feel-nightly
reports=${FIELD_FEEL_REPORTS:-$HOME/.afield/feel-bench}
mkdir -p $reports
day=$(date +%Y%m%d)
report=$reports/nightly-$day.txt
uv=$(command -v uv || echo ~/.local/bin/uv)
if [ -n "$giveup_at" ]; then
  giveup=$(date -j -f "%H:%M:%S" "$giveup_at:00" +%s)
  [ $giveup -lt $(date +%s) ] && giveup=$(date -j -v+1d -f "%H:%M:%S" "$giveup_at:00" +%s)
else
  giveup=$(( $(date +%s) + wait_minutes * 60 ))
  giveup_at=$(date -j -r $giveup +%H:%M)
fi
exec > >(tee $report) 2>&1
finish() { echo "NIGHTLY: $1"; exit ${2:-0}; }

echo "feel-bench nightly $(date)"
if [ ! -d $wt ]; then
  git -C $repo worktree add --detach $wt main || finish "could not create $wt" 2
fi
cd $wt || finish "no worktree" 2
git checkout -q --detach main && git reset -q --hard main || finish "could not reset worktree to main" 2
git lfs checkout >/dev/null 2>&1
echo "at $(git rev-parse --short HEAD) (local main)"

# Native prerequisites first, so a missing build fails in seconds, not 30 minutes into a run.
./gradlew -q buildFieldBrowserBridge >/dev/null 2>&1 || finish "buildFieldBrowserBridge failed" 2
[ $prepare_only = 1 ] && finish "prepared (--prepare-only)" 0

load1() { sysctl -n vm.loadavg | awk '{print $2}'; }
quiet_since=0
while true; do
  now=$(date +%s)
  [ $now -ge $giveup ] && finish "skipped: load never held under 4 before $giveup_at (load $(load1))" 0
  if pmset -g batt | grep -q "AC Power" && awk -v l=$(load1) 'BEGIN{exit !(l<4)}'; then
    [ $quiet_since -eq 0 ] && quiet_since=$now
    [ $(( now - quiet_since )) -ge 180 ] && break
  else
    quiet_since=0
  fi
  sleep 15
done

echo "run start $(date +%H:%M:%S), load $(load1)"
caffeinate -dims $uv run --project . --group backdoor python tools/feel_bench.py run \
  --reps 100 --cold-runs 10 --require-ac --max-load 4 > build/feel-bench-nightly-run.log 2>&1
rc=$?
echo "run end $(date +%H:%M:%S), exit $rc"
[ $rc -eq 3 ] && finish "skipped by the run's own gate: $(tail -1 build/feel-bench-nightly-run.log)" 0
tail -40 build/feel-bench-nightly-run.log | grep -E "FAIL|FAILED" && echo "(see $wt/build/feel-bench-nightly-run.log)"

$uv run --project . --group backdoor python tools/feel_bench.py check --page build/feel-bench/nightly-check.md
crc=$?
echo "check page: $wt/build/feel-bench/nightly-check.md"
case $crc in
  0) [ $rc -eq 0 ] && finish "no regressions" 0 || finish "run exit $rc but check clean" 1 ;;
  1) finish "regressions (see check output above)" 1 ;;
  *) finish "check not comparable (exit $crc)" 2 ;;
esac
