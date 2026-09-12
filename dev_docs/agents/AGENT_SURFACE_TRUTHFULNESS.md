# Agent-surface truthfulness

A tool that answers a question about the **world** with a value the **caller supplied** turns
a documented safety check into a ritual: it cannot fail, so it verifies nothing. This page is
the contract for the three places in `tools/` where that had happened, the predicate for
finding more, and the reproduction commands.

Landed 2026-09-12 on `nightly/2026-09-12/2-agent-surface-truthfulness`.

## The replacement for the four-field safety check

`AGENTS.md` used to say: before editing a real document, run `status` and verify `workspace`,
`documentName`, `sessionKey` and `boxCount`. Of those four, exactly **one** was the caller's
own argument — and it was the one that looked most like the answer. Check these instead:

| Field | Where it comes from | Can it disagree with you? |
|---|---|---|
| `serverWorkspace` | `GET /api/instance-info`, from the server's `home` option | **Yes.** `null` means the server predates the field — then you have verified nothing |
| `sessionKey` | the server's `agent-open-document` reply | **Yes.** The absolute path the server will actually write |
| `documentName` | the same reply | **Yes.** Normalised server-side |
| `boxCount` | the boxes in that reply | **Yes** |
| `requestedWorkspace` | your `--workspace` argument, echoed | **No. Never verification.** `null` when you passed none |
| `workspaceAppliedToServer` | whether *this process* launched the server | No — but it tells you whether your `--workspace` could have had any effect |

The relation worth checking in one glance:
`sessionKey == serverWorkspace + "/" + documentName`. When it does not hold, the document name
was absolute (see below), and the document you opened is a new empty one.

## Why `--workspace` cannot be forwarded to an attached server

The server resolves a document name as `home + "/" + name`
(`agent_backdoor.kt:135#private fun resolveDocumentTarget(`), where `home` is the
process-global `--workspace` option fixed when that JVM booted
(`Server.kt:119#val workspace: String,`). The backdoor's `--workspace` becomes
`--args=--workspace ...` on the **launch** path only
(`field_backdoor.py:597#cmd.append(f"--args=--workspace {quoted_workspace}")`, the sole site).
Attaching, it decides nothing. Moving a live server's workspace restarts it
(`POST /api/workspace-switch`), so the honest outcomes are *agree* or *refuse*:

- Launching (no `--attach-existing`): pass `--workspace <dir>`; it becomes the server's.
- Attaching: pass nothing, or pass exactly `serverWorkspace`. A disagreeing value is a hard
  error naming both paths. Paths are compared with `~` expanded and symlinks resolved, so
  `/tmp/x` and `/private/tmp/x` agree.
- Attaching to a Field older than 2026-09-12: `serverWorkspace` is `null` and the guard
  abstains. Fail open, because refusing would make a current client unable to attach to a
  server that was fine — but treat `null` as "unverified", not as "fine".

### The default workspace is a machine-global preference, and it wanders

Measured while verifying this change: `~/.afield/last-workspace.txt` on this machine read
`/Users/marc/field4/.claude/worktrees/serene-panini-93b3ca/dev_docs/agents/demos` — a
*nested foreign checkout's* demos directory. A Field launched by `./field.sh` with no
`--workspace` inherits that, whichever checkout it is launched from. That is how documents
land somewhere surprising while every local path looks right. `serverWorkspace` is the one
field that shows it.

Automation started through `tools/` avoids the problem by construction:
`automation_workspace(REPO_ROOT)` is `<checkout>/build/field-automation-workspace`, and
`AFIELD_SKIP_WORKSPACE_PERSIST=1` keeps a launch from rewriting the global preference. Pass
that env var whenever you launch `./field.sh` with an explicit `--workspace` you do not want
to make sticky for the human.

### `boxes: []` / `boxCount: 0`, three causes

1. Wrong workspace — now visible as `serverWorkspace`, and a hard error when you claimed one.
2. A scratch session: `--port` reuse does not reopen an earlier scratch document.
3. An **absolute** `--document`. The server glues it onto the workspace regardless, so
   `--document /ws/demo_doc` yields `sessionKey` = `/ws//ws/demo_doc` — an empty new document.
   Split it: `--workspace /ws --document demo_doc`. The backdoor now says so on stderr.

### Fatals are no longer silent

Every failure out of `ensure_server`/`connect` — the foreign-checkout refusal, the new
workspace refusal, a dropped websocket — used to exit 1 with **empty stdout and empty
stderr**, the reason recorded only in `.field-backdoor/logs/<session>.jsonl`. They now print
the message and the session-log path. A refusal nobody can see is not a refusal.

## Reaping: attribution is evidence, not a boolean

`tools/run_field_tests.py`'s preflight reaper kills Field JVMs it attributes to this checkout.
It now prints *which signal matched and its value* beside every pid, on both the reaping and
the sparing lines:

```
Leaving 3 Field JVM(s) from other checkouts alone: pid 63808 (age 11:40, rss 372M)
  [unattributed: looks like /Users/marc/field4-worktrees/2026-09-12-3-profiling-measurement-model],
  pid 64657 (age 09:56, rss 2838M) [unattributed: looks like /Users/marc/field4/.claude/worktrees/serene-panini-93b3ca], …
```

Signals: `aot-cache-path`, `classpath-entry`, `unattributed`, `foreign-cwd`. Both positive
signals are anchored at `<root>/build/`.

**The nested-checkout trap.** `_checkout_scope_prefix` adds one trailing separator, which
separates *siblings* (`/Users/marc/field4/` is not a prefix of `/Users/marc/field4-worktrees/…`)
and cannot separate *children*. A checkout at `<primary>/.claude/worktrees/<name>/` — what a
Claude Code session creates — is a path under `<primary>/`, so the old substring test
`"-Dafield.aot.cache.path=/Users/marc/field4/" in command` claimed it. On 2026-09-12 that
SIGKILLed pid 654: a live Field 16 hours old holding 2.4 GB, cwd
`/Users/marc/field4/.claude/worktrees/serene-panini-93b3ca`.

A second, **spare-only** opinion backs the anchor up: a JVM whose cwd resolves to a different
checkout (walk up for `.git`, which is a *file* in a worktree) is demoted to foreign. It never
promotes; an unreadable cwd abstains. Limitation, pinned by a test: once the nested worktree is
deleted, the walk up finds the enclosing checkout and the cwd stops objecting.

**No age check.** A 16-hour-old Field looks nothing like "orphaned by an earlier run", but
nothing in the runner knows when this checkout's earlier runs happened, so a threshold would
be a number with no referent. The message was fixed instead: it now says *attributed to* and
shows its evidence.

## Discovery fails open per annotation

`tools/discover_field_tests.py` used to collect every parse error and raise, so one malformed
`FIELD_TEST` anywhere made `run_field_tests.py --id <anything>` dead for **every** test — with
the only fallback an untracked `build/field-tests/catalog.json` that a fresh worktree lacks.
That is how the canonical smoke runner stayed broken for twelve hours.

It now skips the annotation, warns on stderr naming path and line, records it under `skipped`
in the catalog, and catalogs the rest. `--strict` restores the raise for CI, and the
repository-wide guard test uses it so fail-open does not cost that test its teeth. Exit codes:
`0` complete, `2` usable but incomplete, `1` fatal. When a requested `--id` matches nothing,
the runner prints the skip list — the third reason an id can be absent.

## Numbers, with their populations

- `status.workspace` consumers: **0 of 73** files in `tools/` read the key programmatically
  (`grep -rEn 'data\["workspace"\]|\.get\("workspace"\)' tools/*.py`). Renaming was safe.
- Self-report survey: the predicate `"<key>": (args|self).<attr>` matches **27 sites in 8 of
  73** files in `tools/`. Classified by hand: 1 claimed a property of an external system that
  the caller's argument did not determine (`status.workspace` — fixed). The rest are request
  payloads being *sent* (`field_session.py`), arguments of a process the tool itself launched
  (`run_desktop_playwright.py`), post-selection values (`run_field_tests.py`'s
  `automationPort`, reassigned by `select_automation_port` before it is reported), or the
  tool's own telemetry. `field_mcp.py`'s `field_status` was not lying but had no way to answer
  at all; it now reports `serverWorkspace`.
- Live reaper counterfactual: of **3 of 3** Field JVMs running on this machine, the old
  classifier run from the primary checkout claims **1 of 3** (pid 64657, a live 2.8 GB Field in
  the nested checkout); the new one claims **0 of 3**.
- Discovery: **234 tests, 0 skipped** in a worktree (170 from `src/jvmMain/kotlin/afield`,
  64 from `public/docs`), against **252** in the primary checkout, whose extra 18 come from
  the gitignored `src/personal` root. The guard test's floor is 230.
- Python tool suites: **403 passed / 405 run, 2 skipped** (the 2 are pre-existing skips in
  `tools.test_loc_history`). `test_field_backdoor` 38 → 47, `test_run_field_tests_reaper`
  13 → 30, `test_discover_field_tests` 4 → 11.

## Reproducing the before/after in one paste

`$WT` is a checkout; the base commit is `8c872430f`. This starts a Field on **19542**, not
9092, and uses a workspace under `build/`, so it touches nothing of the human's.

```bash
WT=/path/to/your/checkout
WS=$WT/build/field-automation-workspace; mkdir -p "$WS"
cd "$WT"
PORT=19542 AFIELD_SKIP_WORKSPACE_PERSIST=1 AFIELD_FIELD_SH_NO_TERMINAL=1 \
  ./field.sh --workspace "$WS" > /tmp/field19542.log 2>&1 &

# Confirm you are about to drive YOUR checkout, and see the server's own workspace.
P=$(lsof -nP -iTCP:19542 -sTCP:LISTEN -t); lsof -a -p "$P" -d cwd -Fn
curl -sk https://localhost:19542/api/instance-info

# BEFORE: the base-commit tool agrees with a deliberately wrong --workspace.
git show 8c872430f:tools/field_backdoor.py > tools/_legacy_status_demo.py
uv run --project . --group backdoor --frozen python tools/_legacy_status_demo.py --json \
  --port 19542 --attach-existing --workspace "$WT/dev_docs/agents/demos" --command status
#   -> "ok": true, "workspace": ".../dev_docs/agents/demos"   (its own argument)
#      "sessionKey": ".../build/field-automation-workspace/.agent-scratch/..."  (the truth)
rm tools/_legacy_status_demo.py

# AFTER: the same command refuses, naming both paths.
uv run --project . --group backdoor --frozen python tools/field_backdoor.py --json \
  --port 19542 --attach-existing --workspace "$WT/dev_docs/agents/demos" --command status
#   -> exit 1, "--workspace ... disagrees with the Field already running on port 19542"

# AFTER, correct form: serverWorkspace is read back, requestedWorkspace is labelled.
uv run --project . --group backdoor --frozen python tools/field_backdoor.py --json \
  --port 19542 --attach-existing --command status

# Stop only what you started, after checking its cwd.
lsof -a -p "$P" -d cwd -Fn | grep -q "^n$WT$" && kill -INT "$P"
```

The reaper and discovery halves need no server:

```bash
uv run --project . --group backdoor --frozen python tools/run_field_tests.py \
  --reap-dry-run --port 19542 --id nonexistent.test.id   # prints a reason per pid
uv run --project . --group backdoor --frozen python tools/run_python_tests.py
```

## Related

- [`AGENT_QUICK_REF.md`](./AGENT_QUICK_REF.md) — the one-page cheat sheet, including the
  workspace/document rules above.
- [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md) — the backdoor CLI in full.
- [`FIELD_MCP.md`](./FIELD_MCP.md) — the MCP bridge, whose `field_status` now reports
  `serverWorkspace`.
