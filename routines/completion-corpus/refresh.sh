#!/bin/zsh
# Nightly completion-corpus refresh: re-harvest tools/completion-corpus/boxes on local main and, if
# anything changed, commit it on its own branch and hand it to the landing queue.
#
# Landings stopped running the harvest on 2026-11-11 (it cost 1-3 minutes of most landings, and on
# a loaded machine up to ten): the fixtures feed only jvmTest, which no landing runs, so a day's lag
# costs nothing a landing could catch. A landing still regenerates them when the harvester itself or
# the committed fixtures change. This keeps `docsGates` / `check` green for nightly-ci, which starts
# at 05:02 and fails `checkCompletionCorpusFixtures` on a corpus that has drifted.
#
# Paths: FIELD_REPO (default: the checkout holding this script), FIELD_WORKTREE_BASE (default:
# <repo>-worktrees beside it). Works in its own worktree, <base>/completion-corpus-nightly, reset to
# local main each run. Prints a final line `CORPUS: <verdict>`. `--no-land` commits but does not
# enqueue.
setopt pipefail
land=1
while [ $# -gt 0 ]; do
  case $1 in
    --no-land) land=0; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
script_dir=${0:A:h}
repo=${FIELD_REPO:-$(git -C $script_dir rev-parse --path-format=absolute --git-common-dir 2>/dev/null)}
repo=${repo%/.git}
[ -d "$repo" ] || repo=${script_dir:h:h}
base=${FIELD_WORKTREE_BASE:-${repo}-worktrees}
wt=$base/completion-corpus-nightly
uv=$(command -v uv || echo ~/.local/bin/uv)
day=$(date +%Y-%m-%d)
branch=nightly/completion-corpus-$day
finish() { echo "CORPUS: $1"; exit ${2:-0}; }

echo "completion-corpus refresh $(date)"
if [ ! -d $wt ]; then
  git -C $repo worktree add --detach $wt main || finish "could not create $wt" 2
fi
cd $wt || finish "no worktree" 2
# The reset below discards everything in this tree, so only reset a tree this routine owns: detached
# or on one of its own branches, with nothing uncommitted. (A first draft reset an agent's working
# worktree that happened to share the name and erased its edits.)
current=$(git branch --show-current)
case $current in
  ""|nightly/completion-corpus-*) ;;
  *) finish "refusing to reset $wt: it is on branch $current, not this routine's" 2 ;;
esac
[ -z "$(git status --porcelain --untracked-files=no)" ] || finish "refusing to reset $wt: it has uncommitted changes" 2
git checkout -q --detach main && git reset -q --hard main || finish "could not reset worktree to main" 2
git lfs checkout >/dev/null 2>&1
echo "at $(git rev-parse --short HEAD) (local main)"
mkdir -p build

AFIELD_HARVEST_WRITE=1 ./gradlew checkCompletionCorpusFixtures > build/completion-corpus-refresh.log 2>&1 \
  || { tail -20 build/completion-corpus-refresh.log; finish "harvest failed (see $wt/build/completion-corpus-refresh.log)" 1; }
grep -E '^wrote ' build/completion-corpus-refresh.log

git add -A -- tools/completion-corpus/boxes
if git diff --cached --quiet; then
  finish "already current at $(git rev-parse --short HEAD)"
fi
changed=$(git diff --cached --name-only | wc -l | tr -d ' ')
git switch -q -C $branch || finish "could not create $branch" 2
git commit -q -m "Refresh completion-corpus fixtures from the transcript and template corpus

The nightly completion-corpus routine; landings no longer re-harvest." || finish "commit failed" 2
[ $land = 0 ] && finish "committed $changed file(s) on $branch (not enqueued: --no-land)"
$uv run python tools/land.py enqueue --by "completion-corpus routine" --note "nightly re-harvest" || finish "enqueue failed for $branch" 1
finish "enqueued $changed file(s) on $branch"
