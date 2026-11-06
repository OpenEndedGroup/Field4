#!/bin/zsh -l
# Scheduled worktree reap. Runs main's copies of tools/preview_server.py (stops registered preview
# servers older than 16 h) and tools/reap_merged_worktrees.py: the standard reap, then
# --clean-builds on trees idle for 3 h or more. The reaper's own guards (live cwd, dirty, ignored
# work, .reaper-keep, protected branches) apply unchanged.
#
# Main's copies, read with `git show`, not the files in whatever branch this checkout has out: a
# deleting tool runs the reviewed version only. Output goes to stdout; `tools/routines.py run`
# keeps it in ~/.afield/routines/reap-worktrees/. FIELD_REPO overrides the checkout.
set -u
script_dir=${0:A:h}
repo=${FIELD_REPO:-$(git -C $script_dir rev-parse --path-format=absolute --git-common-dir 2>/dev/null)}
repo=${repo%/.git}
[ -d "$repo" ] || repo=${script_dir:h:h}
free() { df -h "$repo" | awk 'NR==2{print $4}'; }
script=$(mktemp -t reap_main).py
trap 'rm -f $script $script.preview' EXIT

echo "== $(date) start; free: $(free)"
git -C $repo show refs/heads/main:tools/reap_merged_worktrees.py > $script || { echo "cannot read main's reaper"; exit 1; }
if git -C $repo show refs/heads/main:tools/preview_server.py > $script.preview 2>/dev/null; then
  echo "== preview_server stop --older-than-hours 16"
  python3 $script.preview stop --older-than-hours 16
  echo "== exit $?"
fi
echo "== reap --apply"
python3 $script --repo $repo --apply
echo "== exit $?"
echo "== --clean-builds --min-idle-hours 3 --apply"
python3 $script --repo $repo --clean-builds --min-idle-hours 3 --apply
echo "== exit $?"
echo "== $(date) done; free: $(free)"
echo "== routine health audit"
python3 "$repo/tools/routines.py" audit --notify || true
