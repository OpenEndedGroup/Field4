# Worktree retention policy

`tools/reap_merged_worktrees.py` deletes checkouts. This page is the statement of what it will and
will not touch, and the measurements behind each rule. `--explain-policy` prints the short version
without scanning anything; read that before you trust a run.

## What may be removed

A worktree is a candidate only if some **enabled retention family** claims it. A family is a named
rule pairing a set of worktree **roots** with a set of **branch prefixes**, and it claims a worktree
only when both match.

| family | on by default | roots | branch prefixes |
|---|---|---|---|
| `nightly` | yes | `<repo>-worktrees` | `nightly/` `review/` `integration/nightly` |
| `docs-blast` | no, `--family docs-blast` | both roots | `docs/language-` |
| `agent-session` | no, `--family agent-session` | `<repo>/.claude/worktrees` | `claude/` |

`--family` is additive. Naming one can widen the population; nothing on the command line narrows a
protection. `nightly/journal` and `nightly/ci-journal` are checked above every family, so no family,
however it is written, reaches them — and `--completed` on one of them says so rather than hinting
at a flag.

Matching a family is necessary, not sufficient. A candidate must also be clean, merged into main, be
free of any live process cwd, and be **idle**. There is still no force mode and no dirty tier, and
dry run is still the default.

Note that the policy keys on the branch, never on the directory name. On 2026-09-09 the machine held
a directory called `.claude/worktrees/docs-language-blast` whose branch was `docs/nav-dissolve-
staging`; `docs-blast` correctly declined it. A suggestive directory name is not evidence.

## Why the family has two axes

The obvious way to widen retention is to add prefixes to a list, and it would not have worked. Until
2026-09-09 the candidate loop refused on `path.parent != base`, where `base` was only
`<repo>-worktrees`. Census that day, across 68 registered worktrees:

| root | trees | size |
|---|---|---|
| `/Users/marc/field4-worktrees` | 32 | 52.3 GiB |
| `/Users/marc/field4/.claude/worktrees` | 33 | 61.4 GiB |

Every one of the 33 trees holding the larger half — and the half that grows, since each interactive
agent session creates one — was unreachable no matter what its branch was called. The scope gap was
the root. Widening only the prefix list would have freed nothing at all.

## The idle guard

Before 2026-09-09 the only thing preventing the reaper from offering an in-flight worktree was the
`lsof` check for a live process cwd inside it. That is a race rather than an invariant. Agent shells
reset cwd to the primary repo between commands, so a working agent's worktree is unguarded at most
instants.

It was not theoretical. Two things were observed directly that morning, over the three worktrees of
that night's own cohort.

The first dry run, against the tool as it then stood, **listed
`2026-09-09-1-kratos-parameter-studies` as a candidate for removal** — about a minute before that
agent committed to it. `git branch -d` would have refused the unmerged branch afterwards, so the
commits were never at risk, but the checkout would have gone out from under a running agent.

The second, eight minutes later, showed why that is not bad luck. `2026-09-09-3-tetgen-public-page`
had four process cwds inside it in the first run and none in the second; it was saved the first time
by `lsof` and the second time only by the idle guard. The set of worktrees `lsof` covers changes from
run to run, over an unchanging set of live agents.

`branch_idle_hours` adds a guard with no dependence on process timing: the newest sign of life on the
branch, taken as the more recent of its reflog tip and its commit date. The reflog is the load-bearing
half, because it moves when a branch is *created* — a worktree whose agent has not committed anything
yet is precisely the case that reads as ancient by commit date alone, since its HEAD is still main's.
The two populations are not close together:

```
nightly/2026-09-09/1-kratos-parameter-studies    0.02 h   in flight
nightly/2026-09-09/2-worktree-retention-scope    0.17 h   in flight, never committed
nightly/2026-09-09/3-tetgen-public-page          0.01 h   in flight
nightly/2026-09-07/1-four-pass-chain            45.75 h   finished
nightly/2026-08-25/3-impossibility-claim-...   372 h      finished
```

`--min-idle-hours` defaults to 12, which sits three orders of magnitude away from either edge, so the
value is not delicate. A branch with neither signal is **refused**, not assumed stale: losing the age
signal must fail closed, because the branch with the thinnest history is the newest one.

## The report is what `--apply` will do

`disk_rows` used to decide `reclaimable` from the branch name alone while the candidate loop also
required the nightly root. A clean, merged, `nightly/`-prefixed worktree under `.claude/worktrees`
was therefore printed into the "safe reclaimable total … enough to close the requirement" subtotal
and then never touched. The report could promise space the tool had no way to free — which matters,
because that subtotal exists to be quoted by an orchestrator deciding whether tonight can run.

Both callers now go through `classify_worktree`, so they cannot disagree, and
`test_the_report_subtotal_equals_what_apply_would_select` pins it. The report distinguishes three
quantities that used to be one:

- **reclaimable now** — what `--apply` would free with the families currently enabled.
- **withheld by families that are not enabled** — per family, what naming it would additionally
  offer, and whether that would close the gate. Only genuinely removable rows are counted here, so
  enabling a family is never advertised as freeing something dirty or unmerged.
- **refused** — a tally by reason: outside every family, live cwd, dirty, in flight, undatable,
  clean but unmerged, unregistered.

Alongside these, `ORPHANED` still reports worktree-shaped directories that have fallen out of
`git worktree list` — the residue of git unregistering a worktree before it finishes deleting it.
Nothing reclaims those automatically and the report is the only way they resurface.

## Measured effect

Dry runs against the live 68-worktree set, 2026-09-09, `--disk-floor-gib 19`:

| policy | candidates | reclaimable |
|---|---|---|
| default (`nightly`) | 3 | 6.04 GiB |
| `--family agent-session --family docs-blast` | 6 | 11.57 GiB |

`docs-blast` contributed nothing that night: 32 `docs/language-*` branches existed and not one had a
checkout left, the family having already been cleaned up by hand. That is the honest answer for that
night rather than a defect. The harness that produces them runs 45 units 8-wide and reaps none, so
the family recurs in bursts, and naming it means the next burst is reachable without editing code.

## Running it

```
tools/reap_merged_worktrees.py --explain-policy          # the policy, no scan
tools/reap_merged_worktrees.py --disk-floor-gib 19       # dry run + ranked report
tools/reap_merged_worktrees.py --disk-floor-gib 19 --family agent-session
```

Tests are `tools/test_reap_merged_worktrees.py`, run with pytest from the repository root with
`PYTHONPATH=.`. They build throwaway repos with real `git worktree add` and set `GIT_COMMITTER_DATE`
so reflog timestamps, and therefore idle times, are deterministic. No test invokes `--apply`; one
asserts that a full dry run leaves the directory listing byte-identical.
