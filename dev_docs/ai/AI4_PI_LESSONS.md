# What AI4 took from Pi (2026-10-02)

A ChatGPT analysis of [earendil-works/pi](https://github.com/earendil-works/pi) proposed nine ideas
for AI4. This records which of them mapped onto Field, how, and what each one measured. Everything
here is AI4 extended: the same registry, bridges, panel and eval harness as
[IN_FIELD_AGENT_HARNESS.md](IN_FIELD_AGENT_HARNESS.md) and
[AGENT_TOOL_EXTENSIONS.md](AGENT_TOOL_EXTENSIONS.md).

## The mapping

| Pi idea | What Field had | What Field has now | Code |
| --- | --- | --- | --- |
| Codemode | a JSON-in dispatcher (`ToolRegistry.executeByName`), GraalJS on the classpath, no batch tool | `RunToolProgramRequest`: a JS program over the conversation's other tools, in a locked-down GraalJS context | `ai/core/program/` |
| Nested call trace | a flat tool transcript; timing on the OpenAI bridge only | child entries `<callId>/<n>` in every bridge and the tool I/O log; the panel shows a program's calls under its line | `ToolProgramTools.kt` |
| Context as a runtime object | one 15–20k-character instruction string prepended to every stored user message | `ContextFragment`s (durable/ephemeral, stable/volatile) assembled per turn; `/context` | `ContextPipeline.kt`, `Instructions.kt` |
| Tool exposure, namespaces | every gated-open schema on every request | `@AgentToolMeta(namespace, exposure, effects)`; DIRECT / PROGRAM / DEFERRED / HIDDEN; `DescribeToolsRequest`; a catalogue line per namespace | `ToolMeta.kt`, `Tools.kt` |
| Effect annotations | read-only refusals in two graph tools | declared effects on all 54 core tools; a central read-only refusal; `effects-absent` / `effects-present` eval checks; MCP `readOnlyHint` / `destructiveHint` | `ToolMeta.kt` |
| Session tree | `ai4_chat_history`, one markdown string, no ids | `ai4_session`, parent-linked turns beside it; rewind, edit and resend, `/branches` | `AiSessionTree.kt` |
| Capability checkpoints | the model label on each reply | per-turn checkpoint: model, instruction hash and fragments, tool set, box tools with owner and source revision, provider-settings revision, Field build | `AiSessionTree.checkpointFor`, `FieldBuildInfo` |
| Branch-sensitive state | — | the classification below, applied on every head change | `Ai4Installer` (`ai-chat-history`) |
| Extensions | `aiTool {}` (ledger-owned) and `AgentToolExtensions` (process-global) | `agentExtension {}`: tools, context, before/after-tool hooks, commands, all ledger-owned; box-registered `AgentToolExtensions` ledger-owned too | `AgentExtensions.kt` |

Not copied, as the analysis also advised: Pi itself, an MCP-centred core, the terminal UI. Branches
are not joined to `HistoryManager` versions (those count saves only, and the chat sidecar is not
versioned with the document); the world link is the OperationTranscript instead, below.

### Why JavaScript for programs, not the macro YAML

The OperationTranscript YAML is a straight-line list of document operations with no variables, loops
or conditionals, which is what makes it reviewable and reversible. What makes a program pay is the
middle of it: filter, loop, branch on a result. Kotlin was the other candidate, but every program would
pay a K2 compile and a box classloader can reach the whole JVM. The YAML keeps its job: a program's edits
go through the same tool handlers, so they land in the transcript as ordinary entries.

## Branch-sensitive state

| State | Class | What a rewind or branch switch does |
| --- | --- | --- |
| The transcript (`ai4_chat_history`) | branch | re-rendered from the new head |
| Provider threads | branch | dropped; the next turn reseeds from the new transcript |
| Scratchpad and checklist | branch | restored from the turn the head now ends with (`AgentMemoryState.snapshot/restore`) |
| Loaded deferred tools (`ThreadState.promotedTools`) | branch | lost with the thread; a new thread starts with DIRECT tools |
| Stable instructions already in an OpenAI conversation | branch | lost with the thread; sent again |
| The document | document | untouched unless the person accepts the world-undo question |
| Box tools, extensions, guides | document | untouched |
| `lastContextByBox` (`/context`) | ephemeral | cleared |

## World undo

Every graph-tool edit an AI turn makes is recorded under the turn's origin, `ai4:<id>`
(`FieldGraphTools.forTurn(origin =)` → `applyAgentPropertyRecorded` → `OperationTranscript.recordEdit`).
A person's edit and an agent's edit of one property are never merged into one entry, and
`turnBoundary()` stops merging across a turn's start and end, so undoing a turn's origin cannot take a
person's work with it. The session tree stores each turn's `op {from, to, origin, createdBoxes}`.

After a rewind the reply says how many recorded edits and new boxes the abandoned turns made. Undoing
them is a second, explicit request (`ai-chat-rewind-world`): `undoOrigins` (strict, so an edit changed
since is skipped) plus deleting the created boxes that are exactly as the turns left them: each turn
records `op.fpBefore` / `op.fpAfter`, an `AiWorldUndo.fingerprint` (name, frame, links, saved
properties) of every box its conversation has created, and a box a person changed afterwards, or
between the abandoned turns, is kept and named in the reply. Not undone: other structural changes, and
accepted provisional `EditFile` edits. The reply says so.

## Measurements

Live evals, OpenAI `gpt-6-astra`, lessons off, each build checked out into its own pinned worktree so
every number belongs to one commit. One run per arm unless stated; the populations are small, so read
differences of a few percent as noise. "Cost" is an estimate, uncached input plus a tenth of cached
input, not the provider's price list.

### Tool programs (P1, `a1936f835`; repeated at the end state, `68a9294ac`)

Three tasks of many similar calls (`audit-failing`, `sweep-parameter`, `tag-todos`), the
`programs=off` arm withholding the tool in the same build:

| build | arm | pass | rounds | input tokens | est. cost |
| --- | --- | --- | --- | --- | --- |
| P1 | off | 3/3 | 29 | 291,131 | 46.4k |
| P1 | on | 3/3 | 11 | 139,296 | — |
| end | off | 3/3 | 29 | 259,991 | 46.2k |
| end | on | 3/3 | 10 | 118,526 | 33.6k |

The model chose a program in every on-arm run without being told to. On the other twelve tasks the
tool's schema and guidance cost about 3.5% more input (P1: 447.4k against 432.3k).

### The context pipeline (P2, `ea1794c97`, and the end state)

Two multi-turn tasks (`four-turns`, four user turns; `revise-table`, three), the `context=legacy` arm
putting the instructions back into the stored message in the same build:

| build | arm | runs | pass | rounds | input tokens (mean) |
| --- | --- | --- | --- | --- | --- |
| P2 | legacy | 2 | 4/4 | 17, 17 | 321.6k |
| P2 | pipeline | 2 | 4/4 | 15, 17 | 208.5k |
| end | legacy | 1 | 2/2 | 17 | 307.8k (est. cost 57.8k) |
| end | pipeline | 1 | 2/2 | 15 | 192.4k (est. cost 31.8k) |

### Deferred tools and the catalogue (P3, `e1671287b`)

17/17. Input 699k against P2's 783k; the single-turn tasks fell 7.8% (564k → 520k) at the same rounds
(49 → 50). The sixteen DEFERRED tools are those under 1% of 433 tool-using turns in
`~/.afield/logs/ai4_tool_io` (`tools/ai_tool_usage.py`); `ToolMetaPinTest` pins the list.

### The caching regression, and what fixed it

P2 cut multi-turn input, but on single-turn tasks the cached share of input fell from 86% to 74% and
uncached input nearly doubled, so the estimated cost of single-turn work *rose*. Per task: in the
baseline only a run's first task paid about 8k uncached tokens and later tasks about 4k (their first
request reused a prefix earlier conversations had cached); from P2 every task paid about 8k.

| attempt | build | single-turn cached share | verdict |
| --- | --- | --- | --- |
| ephemeral instructions as each request's `instructions`, `prompt_cache_key` per thread | P2 | 74% | regression found |
| same, `prompt_cache_key` = instruction hash | P3 | 74% | no change |
| stable part once per conversation as a developer message, volatile part in `instructions` | `e9e248f87` | 76% | no change |
| stable part as above, volatile part in front of the user message, no `instructions` | `68a9294ac` | 86% | fixed |

The fix came from a controlled experiment, four cheap tasks, one build, a fresh Field per arm, uncached
input of tasks 2–4: `instructions` arm 7,468 / 7,449 / 7,447; volatile-in-input arm 4,991 / 3,990 /
4,225; legacy 4,385 / 3,992 / 4,222. Any per-request `instructions` parameter, even ~2k characters of
it, stopped conversations sharing their cached prefix. Why the Responses API places it that way is not
known; the measurement is what the code follows (`OpenAiIdeCodexBridge`, `AstraBridgeContractTest`).

### End state (`68a9294ac`)

17/17 live, 17/17 scripted in real Field, the four AI smoke tests and the sidecar-registration smoke
passing.

| tasks | comparison | rounds | input tokens | est. cost |
| --- | --- | --- | --- | --- |
| the original 12 | baseline (`f867b7996`) → end | 41 → 42 | 434.8k → 420.9k | 95.3k → 95.3k |
| 3 many-call | programs off → on | 29 → 10 | 260.0k → 118.5k | 46.2k → 33.6k |
| 2 multi-turn | legacy → pipeline | 17 → 15 | 307.8k → 192.4k | 57.8k → 31.8k |

The original tasks are single-turn with few calls each, which is the case none of this targets; what
they show is that nothing got worse.

### In the real panel

Two backdoor sessions with `--with-playwright` drove a box's chat with live turns and real clicks
(`window.confirm` stubbed to answer yes): a turn that used a tool program, with its trace expanded to
eight calls and their times; `/context`; **rewind** on the second request, then the world-undo question,
after which `property-lacks:Cell1:code:100` passed; **edit**, which put the request back in the input;
and `/branches` marking the current position. Two bugs that run found are fixed (`0078c138d`).

## A test: a painting world (2026-10-03)

A Studio box over the paint simulator (`afield.paint`): an `agentExtension` with eleven box-authored
`paint.*` tools (stroke, dab, work, glaze, draw, hatch, stipple, wait, fresh, hang, and a DEFERRED
crack), a `canvas` context fragment (size, coordinates, wetness), a stable `craft` fragment (how to
paint, the tube names), `/canvas`, `/palette` and `/gallery`, and the canvas itself as a
`branchState`. Driven live through the panel (`agent-task --chat-box Studio`, gpt-6-astra): dusk
(182 s), night on the same canvas (201 s), a rewind to before the night request, a stormy morning from
there (168 s), and `/branch 0`. Hanging the canvas after the rewind gave a PNG byte-identical to
*Harbour at dusk*; after the switch back, byte-identical to *Harbour at night*. The model used tool
programs to batch strokes without being told to, and recovered from a glaze that turned the canvas
almost black after seeing it.

It found three bugs, all fixed:

- **A dotted `aiTool` name failed every OpenAI request in its document** (`tools[n].name` must match
  `^[a-zA-Z0-9_-]+$`); box tools now go out under `BoxTool.wireName` (`paint_look`), and the registry
  answers to either name.
- **The panel cleared "running" before the turn was recorded.** A request sent in that gap (seconds,
  with a 49 MB canvas to save) was refused as "already has an AI turn running", and a Field shut down
  in it saved the document without the turn's session-tree node. "Running" now ends after recording.
- **The turn-start snapshot ran in the message handler**; it now runs in the turn's coroutine, and
  hashing and gzip happen off the Runloop (only the box's `save` runs there).

## The house-workflow review (2026-10-03)

A review of `3f29cf1a3` against the house-design workflow (in the house checkout,
`dev_docs/agents/house-sketch/PI_WORKTREE_REVIEW_20261003.md`, with five offline probes) found five
contract problems and five gaps for long, concurrently edited work. Each is fixed below, and each
probe is now a test asserting the corrected behaviour.

| Finding | What it was | Now | Test |
| --- | --- | --- | --- |
| Failed calls | a `success:false` result came back normally, so a held check followed by a cut ran the cut and the program reported success | a failed result throws `ToolFailure` (`e.result`); `tools.X.raw()` inspects failures; `check()` stops on a verdict that is not a failure (`qualified:false`); stages `tool-failed` / `check-failed` | `ToolProgramTest.aFailedCheckStopsTheEditAfterIt`, `aVerdictIsNotAFailureButCheckStopsOnIt`, `rawReturnsFailuresWithoutThrowing` |
| Nested evidence | nested results cut at 8,000 characters into invalid JSON, with `truncated=false` | a larger result is stored whole (`ToolEvidence`, gzip, content-addressed); the transcript and tool log get a valid stub with id, SHA-256 and size; `truncated` and `nestedClipped` say so; `ReadToolEvidenceRequest` reads it back | `aLargeNestedResultIsKeptWholeAndTheTranscriptSaysWhere` |
| World undo | deleted every box the abandoned turns created, edited or not (and, it turned out, deleted none of them; below) | turns record a fingerprint of each box the conversation created, at start and end (`AiWorldUndo`); only boxes exactly as the last abandoned turn left them, untouched between turns, are deleted; the reply names the rest | `AgentExtensionsTest.worldUndoDeletesOnlyBoxesAsTheTurnsLeftThem` |
| Tool identity | `house.check` and `house_check` (and long names sharing 64 characters) reached the model as one tool; programs had no `tools["rectsl.run"]` | long names get a hash suffix; a second tool with the same wire name is refused unless it is the same box renaming its own; built-in names are refused; programs get both spellings | `twoToolsCannotReachTheModelUnderOneName`, `ToolProgramTest.aBoxToolIsCallableByItsOwnNameAndItsWireName` |
| `/context` | said ephemeral fragments were not stored, which is false on OpenAI | each row says what the provider keeps (`kept, sent once`, `kept, added every turn`, `every request, not kept`, `this turn only`), with totals and a note on why OpenAI's placement accumulates | `ContextPlacementTest.theContextReportSaysWhatEachProviderKeeps` |
| Live progress | `text()` is only returned at the end | `progress(...)` reaches the panel at once and prefixes the per-call lines; nested tools keep the same progress callback | `progressReachesThePersonWhileTheProgramRuns` |
| Long jobs | only `timeoutSeconds` was cut to the program's remaining time | `waitSeconds` too; guidance says to return a job handle; nothing retries | `aNestedWaitIsCutToTheTimeTheProgramHasLeft` |
| Guards | a before-hook that threw or overran let the call proceed | `beforeTool(mandatory = true, timeoutMs)` fails closed; observers unchanged | `aMandatoryGuardThatFailsRefusesTheCall` |
| Branch restore | restored over whatever was there, and resumed on every reopen | the state now is saved and compared with the last state the conversation left (`AiSessionTree.lastKnownStates`, `state` lines); a difference is kept by default (`BranchConflict.KEEP`) and reported; `resumeOnOpen` is opt-in. The Studio opts into both | `aStateChangedOutsideTheConversationIsNotRestoredOverByDefault`, `AiSessionTreeTest.theLastKnownStateIsTheLatestTurnEndOrRestore` |
| Cancellation | an interrupt while the Graal context was built could be swallowed | the context is built on another thread and the turn thread waits interruptibly | `anInterruptWhileTheContextIsBuiltCancelsTheProgram` |

Also found while fixing these:

- The tool catalogue listed DEFERRED tools in contexts that cannot load them (`promoted == null`); it
  now lists them only where `DescribeToolsRequest` is offered.
- **World undo never deleted anything**: it ran the `-delete-box` action on the Runloop without binding the
  document as the session, so the reply said "Deleted" and the box stayed in `metadata.edn`. It now deletes through `LiveFieldGraphHost`, as `DeleteBox` does.
- **A reopened document broke every fingerprint**: loading fills in `autorun false`, so an untouched box
  no longer matched how its turn left it. Properties at their empty value now count as absent.
- `tools/docs_voice/check_anchors.py` read the underscores in `photo_to_solid_chain` as emphasis and
  reported three working links dead (one of them new on this branch); intraword underscores are now
  literal, as they are to the site's Markdown.

### The re-review (2026-10-03, at `27b37002f`)

`PI_WORKTREE_REREVIEW_20261003.md` beside the first review found three edge paths in those safeguards,
each reproduced by a probe (`PiRereviewProbeTest`, kept in the house checkout's evidence folder). All
three probes now fail at the assertion that captured the bug, and their inversions are tests here:

- **A failed safety copy restored anyway.** When saving the state as it is failed, the copy was null,
  null read as "no conflict", and the old snapshot overwrote a person's edit, reported as restored. Now
  no hook is restored without its safety copy, under `KEEP` or `RESTORE`; the chat gets a note saying
  what failed; a cancelled save cancels the restore. `noRestoreWithoutASafetyCopyOfTheStateNow`,
  `aCancelledSaveCancelsTheRestore`.
- **A missing snapshot meant "start fresh".** A target hash whose blob was missing reached `restore` as
  `null` and reset the design. A missing or damaged blob (`AiStateBlobs.get` now throws on a damaged
  file) is reported and the live state left alone; only a target absent on purpose passes `null`.
  `aMissingOrDamagedSnapshotIsReportedAndNotRestoredAsEmpty`.
- **Fingerprints sorted every collection**, so reordering a list (a polygon's vertices) still let world
  undo delete the box. `AiWorldUndo.canonical` now writes values typed and quoted, lists in order, only
  sets and maps sorted; the reload normalisation is narrowed from "any empty value" to the loader's own
  defaults (`LOADER_DEFAULTS`: `autorun`, `preamble`, `nested`). Fingerprints recorded before this no
  longer match, which keeps those boxes rather than deleting them. `aReorderedListIsAChangeAndValuesDoNotPrintAlike`.

### Checked in Field

Backdoor sessions on scratch documents, one live panel turn each on the configured provider (now
`gpt-6.1-sol`), the rest scripted:

- A box registering `house_check` beside another box's `house.check` failed to run with "would reach the
  model as house_inspect, the same name as aiTool("house.inspect") in box Inspect; rename one of them."
- A program over the two box tools: `tools["house.inspect"]` and `tools.house_inspect` both called the
  tool; the held `house_check` threw `ToolFailure: house_check (call 2) failed: Qualification held …`, and
  the `NewBox({name: "Cut"})` after it never ran; `.raw` returned the same failure; the 19,921-byte
  inspection result was kept as `ai4ev:0de77fe1…` and `ReadToolEvidenceRequest` read it back with the
  same SHA-256.
- After the panel turn, a dab painted on the Studio canvas from outside the chat; rewinding the chat
  replied "Studio/studio/canvas had changed since the conversation last saw it; it was restored anyway,
  and the replaced state is saved as 6e2dd2e4…" (the Studio's `RESTORE`).
- A panel turn made boxes A and B; B was then edited by hand; Field was restarted on the document; a
  rewind and world undo replied "Deleted 1 box(es) those turns created: A. Kept 1 they created: B
  (changed since)", and `metadata.edn` then had A tombstoned and B as edited.
- Fingerprints of four boxes were identical across a restart.

Left for the house integration, as the review assigns them: StudyBuild job handles and qualification
facts, the shared tool pool and promotion of programs into it, `ToolEvidence.store` pointed at the
house evidence records, capability checkpoints that reference study manifests, reconciling
`DescribeToolsRequest` with server-side deferral, and the bounded acceptance exercise on a copy of a
house document.

## Not done

- **Anthropic was not measured live.** Its placement (cached system blocks, a volatile prefix stripped
  from history, cache breakpoints) is pinned on the serialised request by `ContextPlacementTest`, not
  by an eval.
- **OpenRouter** stays on the one-string prompt: its Responses API keeps its own history, and how it
  treats per-request instructions is unverified.
- **Editor turns** (`chatFile`, `chatInline`, `chatError`) still store the whole box text with each
  request; a reply was made against that text, and those threads are reset on every kind switch.
- **Checkpoints are recorded, not used**: nothing yet replays a turn under its checkpoint or warns when
  the tool set has changed since.
- **`ai4_blobs` is never pruned.** An image a deleted branch referred to stays in the folder.
- **Evidence is never pruned**, like the state blobs.
- **A mandatory guard is still only a second line.** It runs where `ToolRegistry.dispatch` runs, so a
  tool reached another way (a box calling its own `aiTool` handle) is not guarded.
- **Hooks see views, not the registry.** A hook gets a `ToolCallView` and a `ToolResultView`, which hold
  neither the registry nor the result object, so a hook neither calls tools nor changes what the model
  reads; giving them either would be a new decision, not a fix.
- **The eval suite is small**: 17 tasks, mostly one run per arm.
- **Branch-state snapshots are large and never pruned.** A 900 px oil canvas's state is 49 MB and
  gzips to 28 MB (the ground's texture is noise); every turn that changes it stores one. The four in
  the painting test came to 114 MB. A Field shut down while a turn is still recording can still lose
  that turn's node.
- **Box-chat transcripts keep tool images inline.** `ai4_chat_history` holds each image a turn showed
  as a data URL (the session tree moves them to `ai4_blobs`, the markdown does not), so the painting
  test's three turns made an 18 MB transcript.
