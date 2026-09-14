# Agent-surface truthfulness

A tool that answers a question about the **world** with a value the **caller supplied** turns
a documented safety check into a ritual: it cannot fail, so it verifies nothing. Its sibling
is a tool that answers with *nothing at all* where the fact was available and simply never
read. This page is the contract for the places in `tools/` where either had happened, the
predicate for finding more, and the reproduction commands.

Landed 2026-09-12 on `nightly/2026-09-12/2-agent-surface-truthfulness` (workspace, reaping,
discovery) and 2026-09-14 on `nightly/2026-09-14/2-backdoor-compile-truthfulness` (box
execution, `open`, and two harness surfaces).

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

### `boxes: []` / `boxCount: 0`, four causes

1. Wrong workspace — now visible as `serverWorkspace`, and a hard error when you claimed one.
2. A scratch session: `--port` reuse does not reopen an earlier scratch document.
3. An **absolute** `--document`. The server glues it onto the workspace regardless, so
   `--document /ws/demo_doc` yields `sessionKey` = `/ws//ws/demo_doc` — an empty new document.
   Split it: `--workspace /ws --document demo_doc`. The backdoor now says so on stderr.
4. **The document was not there and Field created it** — a typo, a renamed demo, a document
   that lives in another workspace. Added to this list 2026-09-14, when it became answerable:
   read `documentExisted`, and see the `open` section below.

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

## Execution: a box that failed to compile used to report `ok: true`

Landed 2026-09-14 on `nightly/2026-09-14/2-backdoor-compile-truthfulness`, base `2fe5b17b6`.

Found while driving the `alife_script` demo on 2026-09-13: a box with a hard Kotlin compile
error, run through `field_backdoor.py run`, returned **`ok: true`, `running: false`, process
exit 0**. An agent that trusted the exit status shipped a demo that does not run, and the
defect surfaced only because a human asked to see it.

The diagnostics were not missing. They were in the same result:

```json
{"ok": true, "data": {"running": false}, "events": [ ...,
  {"name": "output", "kind": "error", "line": 1, "startColumn": 12,
   "html": "Initializer type mismatch: expected &#39;Int&#39;, actual &#39;String&#39;."} ]}
```

`dispatch_command`'s `run`/`launch`/`continue`/`launch-batch` branch computed
`ok = not timed_out` and never read the events it had just collected — while two sibling
verbs in the same function already graded themselves on exactly these packets: `action` via
`execution_errors()`, `wait-output` via `_execution_error_event()`. One shared
`grade_execution()` now does it for all four, so they cannot drift apart again.

`ok` is now **false**, with `data.errors` (list) and `data.error` (flat string). The flat
key matters: `run_field_tests.collect_execution_problems` and `docs_hooks`'s
`require_diagnostic` path both use it to tell a *diagnosed* failure from an opaque one, and
deliberately do not treat a bare "command failed" as proof of broken example code.

**The predicate is `execution_errors()`, reused rather than rewritten, and that is
load-bearing.** Field encodes "clear this property's stale error marker" as an empty error
at line −1, and `DatalogExecution` emits one on every success (`ClojureExecution` now does
too), so a fresh predicate would have failed every clean Datalog and Clojure box.

Cosmetic wart, known and not fixed: the text keeps Field's HTML entities (`&#39;`).
`execution_errors()` is shared with the `action` verb and with both harnesses' failure text,
so un-escaping it is a cross-verb change with its own blast radius.

### The escape hatch, and who needed it

`--no-fail-on-error` — same name and meaning as `wait-output`'s, which has had it since the
docs-capture silent-timeout fix. It suppresses the verdict, never the evidence:
`data.errors` stays populated and `data.executionErrorsIgnored: true` records the waiver.

Two harnesses have a registered way to say "this box is supposed to fail", and before this
change both were gates on the *grader* with nothing between them and the tool:

| Flag | Population | What it gated | What it did not gate |
|---|---|---|---|
| `expect.allowExecutionErrors` | **5** smoke tests (1 in `public/docs/clojure.md`, 4 in `public/docs/ray_tracing_examples.md`) | `collect_execution_problems` | the unconditional failed-results loop in `evaluate_expectations` |
| `expect-error=true` | **1** docs fence (`public/docs/clojure.md`) | `screenshot_diagnostic` declining to elevate | nothing — until `run` started failing |
| *(none — written by hand)* | **2** of the **34** UI regressions | — | — |

A **third** harness needed it and has no registry to key it off:
`tools/run_ui_regressions.py`. Two of its 34 specs drive a deliberately failing box and
then assert on the error that box produced — `error-fix-import-executes` (an unresolved
`MorphoCircuit`, so the "add import" fix row has something to fix) and `remote-ar-head`
(`clearAFr ame()`, asserting that exactly *one* line-1 syntax error appears). It was found
by sweeping the harness rather than by it breaking. The flag is written into those two
specs by hand with the reason beside it, and a test pins the exact set so a third cannot
appear quietly: **30** `run`/`launch` emissions across the 34 specs, **2** waived, **28**
that must not be.

All of them would have broken in the worst way: `field_backdoor.py`'s batch loop returns 1
at the first not-ok command, so an un-waived `run` aborts the invocation *before* the
`screenshot-box` or `page-eval` the test exists to reach — and the failure then names the
missing assertion rather than the cause. `waive_execution_verdict()` in
`tools/verification_runtime.py` — the stdlib-only module both harnesses already import — is
the single spelling of the flag, applied at each harness's one command-list funnel. It
touches an execution verb and nothing else: a failing `screenshot-box` or `stop` is still a
failure, pinned by a test in each harness. Neither harness loses a check either: a docs
fence carrying `expect-error` whose box runs *cleanly* still fails.

## `open` reported success for a document it had invented

The same sweep, run over the whole verb surface. All **37** `args.command` branches in
`dispatch_command` were enumerated mechanically (a regex over the branch bodies, not by eye).
**10 of 37** hardcode `ok=True`; **9 of those 10 are honest** — five are backed by a helper
that raises on a server `ok: false` (`set_property`, `create_box`, `open_playwright`,
`dispose_graphics_context`, `refresh_snapshot`), four are pure local reads (`boxes`,
`output`, `sleep`, `status`).

The tenth was `open`, a sharper form of the same disease: there was no server verdict to
read, because `agent-open-document` has none to give. In Field, opening a name that is not
there is how a document gets **created**. So an agent that asked for `alife_walls` and
received a new empty document saw exactly what a successful load looks like — and the one
symptom it did get, `boxes: []`, already had three documented causes above. The server knew:
it logs `file '...' not found; will be created on first save`. Precisely the shape of the
compile-error defect — the fact existed, in a log, and not in the reply.

`AgentDocumentSnapshot` now carries two fields, answered server-side because only the server
can answer them (it resolves the name against its process-global workspace, which the
client's `--workspace` does not decide):

| Field | Meaning | absent |
|---|---|---|
| `documentExisted` | the document was there before this open. Predicate: `SavingContext.documentExistsOnDisk`, evaluated *before* the load | the Field predates the field — reported as `documentExistedKnown: false`, and you have verified nothing |
| `attachedToLiveDocument` | this open joined a document the server already held in memory, rather than reading one from disk | as above |

Two traps, both found by measuring rather than by reading:

- **The predicate is metadata presence, not the directory.** `SavingContext.load` calls
  `ensureDirectory`, so after one open the directory exists whether or not the document did.
  A first `open` of a new name leaves a directory holding only the seeded `AGENTS.md` and
  `README.md`; `documentExistsOnDisk` correctly still says no.
- **`@EncodeDefault` is required on both.** `Message.payload` serialises with the default
  `Json`, whose `encodeDefaults` is false, so a plain `= false` is *omitted from the wire* —
  making "the document was not there" and "this Field is too old to say" the same bytes,
  which is the exact confusion the change exists to end. The first build shipped without it,
  and a live Field on 8802 reported `documentExistedKnown: false` for a document it had just
  invented.

Client side, `ok` deliberately stays **true**: creating a document is a legitimate,
load-bearing use of `open` that the docs runner and every scratch capture depend on. What
changed is that the tool says so — `createdNewDocument: true` plus a stderr line naming the
remedy — and that an agent can now demand the other behaviour with **`open --must-exist`**,
which fails *closed* on a server too old to answer, because an unanswerable guarantee is
worse than no guarantee.

Both values are also cached on the client and reported by `status` and by `field_mcp.py`'s
`field_open_document`. That is where they matter most: `status` is where this page and
AGENTS.md send an agent before it edits a real document, and the check they prescribe is
"a mature document should not unexpectedly report zero boxes" — which had nothing to
interpret it with. On a live Field, the same preflight against a one-character typo:

| | real document | typo |
|---|---|---|
| `documentName` | `real_demo_doc_2026` | `real_demo_doc_2027` |
| `boxCount` | 2 | **0** |
| `documentExisted` | `True` | **`False`** |
| `attachedToLiveDocument` | `False` | `False` |

They are kept tri-state rather than coerced with `bool()`, so `null` still means "the
server predates these fields" instead of collapsing into "no" — the same distinction the
missing `@EncodeDefault` destroyed on the wire.

## Two harness surfaces, same disease (2026-09-14)

### A JVM in our own submodule was demoted and then leaked

`_nearest_checkout` walks to the *innermost* `.git`, and a submodule has one — a file
reading `gitdir: ../../.git/modules/...`. So a Field JVM of ours working in
`services/field-audio-ai` resolved to a checkout that is not `root`, was demoted to
`foreign-cwd`, and then leaked forever: the reaper spares what it does not claim.

Walking to the *outermost* `.git` instead is the obvious remedy and it is wrong. The
nested Claude worktrees this cross-check exists for
(`<primary>/.claude/worktrees/<name>/`) would resolve to the primary and be claimed,
reopening the hole that SIGKILLed pid 654. The `.git` shape does not separate them either
— both are files, both point into `<root>/.git`, so the common git dir agrees too.

`.gitmodules` separates them, and nothing else in the tree does: it is the superproject
saying, in tracked content, "this path is part of me". `_registered_submodules(root)`
parses it; `_cwd_disagrees` exempts exactly those paths, and an absolute path in
`.gitmodules` is ignored rather than trusted. Populations measured 2026-09-14: **1**
registered submodule (present in the primary tree, absent from a fresh worktree, which is
why no worktree ever saw this), **20+** unregistered nested checkouts under the primary's
`.claude/worktrees/` that all still object, **0** Field JVMs observed with a cwd inside
the submodule — a latent hole, not a bleeding one.

### `--id` on a duplicated test id reported PASS for a test that never ran

Discovery fails **open** on a duplicate: it warns, drops the second annotation, catalogs
the rest. Kept deliberately — failing closed over one collision is how the canonical smoke
runner stayed dead tree-wide for twelve hours, and that reasoning is right for an
*unreadable* annotation.

A duplicate is different in kind. It is two well-formed tests claiming one name, and the
requested id still **matches** — the copy discovery kept. So the runner never reaches
`describe_unmatched_ids`, the only place that would have printed the skip list; it runs
one of the two and prints PASS, and the author of the second annotation is told their test
passed. That is this page's subject exactly, wearing a harness costume.

The decision: loud and **fatal for the requested ids only**. A collision elsewhere in the
tree still merely warns, so one duplicate cannot make every other id unrunnable, and
`--strict` — what CI uses — still refuses the whole catalog. `SkippedAnnotation` gains
`duplicateOf`, surfaced in the catalog as a field rather than left in the prose of
`error`, because the runner has to act on it and must not parse a sentence. It is absent
for every other kind of skip, where the annotation's id is precisely what could not be
trusted.

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

### Added 2026-09-14, for the execution and `open` halves

- Verb surface: **37** `args.command` branches in `dispatch_command`; **10** hardcode
  `ok=True`; **1 of those 10** (`open`) could report success for something that had failed.
  Enumerated with a regex over the branch bodies, not by eye.
- Readers of a backdoor result's `ok` outside `field_backdoor.py`: **41 sites in 8 files**
  (`grep -rn '\["ok"\]\|\.get("ok"' tools/*.py public/*.py`) — `field_mcp` 2,
  `field_session` 3, `run_field_tests` 10, `run_desktop_playwright` 1, `run_ui_regressions`
  8, `docs_hooks` 4, and 13 across two test modules. Hand-classified: the two harnesses that
  actually grade box execution already failed on these same events independently of `ok`
  (`run_field_tests.collect_execution_problems`,
  `docs_hooks.extract_backdoor_execution_error`), so no smoke test or docs capture that
  passed before can newly fail because of the change — the event was already fatal there.
- Of the **112** files in `dev_docs/agents/transcripts/`, **0** read a backdoor result's
  `ok`. The 3 lines matching `\bok\b` are Kotlin box code inside the yaml
  (`close_node_demo.yaml:170`, `rectsl_glsl_sdf.yaml:50`, `rectsl_sdf_live.yaml:111`).
- Python tool suites: **455 passed / 457 run, 2 skipped** — the same 2 pre-existing
  `tools.test_loc_history` skips. `test_field_backdoor` 47 → 69,
  `test_verification_runtime` 6 → 12, `test_docs_runner_failure_reporting` 16 → 18,
  `test_run_field_tests_reaper` 30 → 34, `test_discover_field_tests` 11 → 15,
  `test_run_field_tests_ordering` 19 → 24.
- Submodules registered in `.gitmodules`: **1** (`services/field-audio-ai`). Unregistered
  nested checkouts under the primary's `.claude/worktrees/`: **20+**. Field JVMs observed
  with a cwd inside the submodule on 2026-09-14: **0**.
- Discovery: `python3 tools/discover_field_tests.py` exits **0** with **247 tests, 0
  skipped**, empty stderr, parsing clean tree-wide. 247 rather than the primary checkout's
  265 because a worktree lacks the gitignored `src/personal` root — the same 18-test gap
  recorded as 234 vs 252 above.
- Smoke, end to end through the real harness on port 8802: **4 passed, 0 failed, 1 skipped**
  of 5 requested (`docs.clojure.error` — which carries `allowExecutionErrors` —
  `docs.forth.basic-stack`, `docs.kotlin-overview.printing.println`,
  `examples.backdoor.println-basic`; `docs.raytracing.uv-gradient` is a `skipOn: darwin`).

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

## Reproducing the execution and `open` before/after in one paste

`$WT` is a checkout; the base commit for this half is `2fe5b17b6`. Port **8802**, workspace
under `build/`, so it touches nothing of the human's. Every line below was run.

```bash
WT=/path/to/your/checkout
cd "$WT"
WS=$WT/build/field-automation-workspace; mkdir -p "$WS"
PORT=8802 AFIELD_SKIP_WORKSPACE_PERSIST=1 AFIELD_FIELD_SH_NO_TERMINAL=1 \
  ./field.sh --workspace "$WS" > /tmp/field8802.log 2>&1 &

# Never believe a server before checking whose it is: --port will happily start its OWN.
P=$(lsof -nP -iTCP:8802 -sTCP:LISTEN -t); lsof -a -p "$P" -d cwd -Fn

printf 'val x: Int = "this is not an Int"\nprint("never reached: " + x)\n' > /tmp/broken.kt

# BEFORE: the base-commit tool calls a box that did not compile a success.
git show 2fe5b17b6:tools/field_backdoor.py > tools/_legacy_run_demo.py
uv run --project . --group backdoor --frozen python tools/_legacy_run_demo.py --json \
  --port 8802 --attach-existing --scratch \
  --command "open --scratch" --command "new-box --name Broken --alias b" \
  --command "run b --property code --file /tmp/broken.kt --timeout 90"
#   -> "ok": true, "data": {"running": false}, process exit 0
#      ...and the diagnostic sitting unread in that same result's 8 events.
rm tools/_legacy_run_demo.py

# AFTER: same server, same box.
uv run --project . --group backdoor --frozen python tools/field_backdoor.py --json \
  --port 8802 --attach-existing --scratch \
  --command "open --scratch" --command "new-box --name Broken --alias b" \
  --command "run b --property code --file /tmp/broken.kt --timeout 90"
#   -> "ok": false, process exit 1,
#      "errors": ["Initializer type mismatch: expected &#39;Int&#39;, actual &#39;String&#39;."]

# The registered waiver: verdict suppressed, evidence kept.
#   ... --command "run b --property code --file /tmp/broken.kt --no-fail-on-error"
#   -> "ok": true, "errors": [ ...same... ], "executionErrorsIgnored": true

# open, both directions. The first invents a document and now says so on stderr.
uv run --project . --group backdoor --frozen python tools/field_backdoor.py --json \
  --port 8802 --attach-existing --command "open alife_walls_typo"
#   -> "documentExisted": false, "attachedToLiveDocument": false,
#      "createdNewDocument": true, + a stderr line naming the remedy
uv run --project . --group backdoor --frozen python tools/field_backdoor.py --json \
  --port 8802 --attach-existing --command "open alife_walls_typo --must-exist"
#   -> exit 1, "no document named 'alife_walls_typo' in the server's workspace '...'"

# Stop only what you started, after checking its cwd.
lsof -a -p "$P" -d cwd -Fn | grep -q "^n$WT$" && kill -INT "$P"
```

The harness halves need no server of your own — `run_field_tests.py` starts and reaps one:

```bash
uv run --project . --group backdoor --frozen python tools/run_python_tests.py
uv run --project . --group backdoor --frozen python tools/run_field_tests.py \
  --port 8802 --id docs.clojure.error        # the allowExecutionErrors path, end to end
```

## Related

- [`AGENT_QUICK_REF.md`](./AGENT_QUICK_REF.md) — the one-page cheat sheet, including the
  workspace/document rules above.
- [`FIELD_BACKDOOR.md`](./FIELD_BACKDOOR.md) — the backdoor CLI in full.
- [`FIELD_MCP.md`](./FIELD_MCP.md) — the MCP bridge, whose `field_status` now reports
  `serverWorkspace`.
