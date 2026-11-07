---
name: weekly-changelog
title: Weekly What's New
summary: Writes the week's entries for the public What's New page from main's landings, checks them, and sends them through the landing queue.
kind: agent
schedule: 30 21 * * 0
runtime-hours: 1
disk-gib: 3
quiet: no
needs: the landing queue running (tools/land.py); the agent's usage allowance for one short session
cost: one agent for under an hour; one small worktree
reports: log:^docs\(changelog\): What's New
status-command: python3 tools/docs_changelog.py status
---

# Weekly What's New

Bring Field·4's public "What's New" page, `public/docs/changelog.md`, up to date with the landings
on `main` since its newest entry, and hand the result to the landing queue. The scheduler's prompt
gives you `$REPO` and `$WORKTREE_BASE`.

Read first, in the worktree you create below: `dev_docs/agents/DOCS_CHANGELOG.md` (the entry
format, the rules `check` enforces, what goes in and what is left out; follow it exactly),
`dev_docs/agents/DOCS_WRITING_RULES.md` (voice), and the newest ten or so entries of
`public/docs/changelog.md`, so your entries match their length and tone.

1. `cd $REPO && python3 tools/docs_changelog.py status`. If fewer than 8 landings are uncovered,
   report that and stop: nothing to write this week.
2. Create a fresh worktree off local `main`:
   `git -C $REPO worktree add -b docs/changelog-<YYYY-MM-DD> $WORKTREE_BASE/changelog-<YYYY-MM-DD> main`
   (today's date). Do everything below inside it. Never edit, commit or switch branches in `$REPO`.
3. Run `python3 tools/docs_changelog.py brief --windows 20` (raise the number if `status` says more
   are uncovered). Each window brief begins with the marker line for that window.
4. Write the new entries at the top of the page, newest first, each under the `## Month YYYY`
   heading of its last landing's date (add a month heading when a month begins). Aim for about one
   entry per window: fold quiet windows (prose polish, capture fixes, CI, guards, internal tools)
   into a neighbour by widening its marker range, and split a window with two serious features. The
   ranges must tile without gaps from the previous newest entry. Every entry links the page or
   section (`page.md#heading`) where its feature is described today. Check each anchor with
   `grep -n '^#' public/docs/<page>.md` before you use it, and never link a page the brief marks
   `[gone now]`. Don't overclaim: when a commit subject is unclear, read `git show --stat <sha>` or
   the page itself.
5. `python3 tools/docs_changelog.py check` must report 0 faults, and
   `python3 -m unittest tools.test_docs_changelog` must pass. Fix and re-run until both are clean.
6. Commit only `public/docs/changelog.md`, with the message
   `docs(changelog): What's New through <date of newest landing>`. Never use `--no-verify`; the
   hooks only report.
7. Enqueue it: `uv run python tools/land.py enqueue` (from the worktree, on the new branch). Do not
   push, merge or pass `--push`. Run the `uv run python tools/land.py wait <id>` it prints as a
   background command and wait for its exit (0 landed, 1 bounced). On a bounce, read the reason from
   `tools/land.py mine`. If the fix is in the changelog page, fix it, commit and enqueue once more.
   Otherwise report the reason and stop.
8. Report how many landings and entries you covered, the date range, the headlines of the new
   entries, and whether it landed.

Do not spawn sub-agents. Do not run Gradle, `field.sh` or a docs build: the page's dates, commit
links and pictures are added at docs-build time by `docs_changelog.render`, and you write plain
Markdown. Do not edit any file other than `public/docs/changelog.md`. Do not invent pictures; if a
picture is wrong, use `<!-- changelog-image: none -->` or point it at an existing asset, as the
guide says. Do not delete worktrees or branches you did not create in this run.
