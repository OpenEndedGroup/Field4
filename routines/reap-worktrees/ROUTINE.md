---
name: reap-worktrees
title: Worktree reaper
summary: Every four hours, removes worktrees that are merged, clean and idle, and deletes rebuildable build output from idle ones, so agent work does not fill the disk.
kind: command
command: zsh routines/reap-worktrees/reap.sh
schedule: 20 */4 * * *
runtime-hours: 0.25
disk-gib: 0
quiet: no
needs: nothing beyond git and python3
cost: no agent; a few minutes of disk scanning
reports: runs
verdict: (?m)^== .* done; free: (\S+)
---

# Worktree reaper

Agents work in git worktrees, and a built worktree is 2 to 16 GiB. Left alone they fill the disk,
and a nearly full disk corrupts builds and captures without saying so. This routine runs `main`'s
`tools/reap_merged_worktrees.py` twice: once to remove worktrees whose branch is merged and whose
tree is clean and idle, and once with `--clean-builds` to delete regenerable build output (`build/`,
`.gradle/`, `.venv/`, built docs, caches) from trees nobody has touched for three hours. It first
stops preview servers that `tools/preview_server.py` started more than 16 hours ago, since a running
server keeps its worktree alive.

Nothing with uncommitted work, ignored files outside build output, a live process, a `.reaper-keep`
file or a protected branch is touched. The retention rules and their measurements are in
`dev_docs/worktree_retention_policy.md`; `python3 tools/reap_merged_worktrees.py --explain-policy`
prints them.

The same scheduled run also removes Gradle daemon logs older than 14 days when their recorded PID
is no longer alive. It rechecks the file identity, age and PID before removal; changed, symlinked,
missing or live-daemon logs are left alone. `python3 tools/prune_gradle_daemon_logs.py
--older-than-days 14` previews candidates without deleting them. The check cannot make deletion
atomic with a completely unrelated process starting or changing a file.

Run it directly from a scheduler (`tools/routines.py run reap-worktrees`) rather than through an
agent: it needs no judgement, and an agent's permission system may refuse the deletion, which is
the whole job.

## When an agent runs it

Run `zsh $REPO/routines/reap-worktrees/reap.sh` and report its first and last lines (free space
before and after) and any `exit` that is not 0. Never substitute a hand-written `git clean`, `rm` or
`git worktree remove`.
