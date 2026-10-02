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
since is skipped) plus deleting the created boxes. Not undone: other structural changes, and accepted
provisional `EditFile` edits. The reply says so.

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
- **A turn cancelled while a tool program's GraalJS context is being built can miss the
  cancellation**: the interrupt seems to be consumed there (seen once under full-suite load in
  `ToolProgramTest`). The program then runs until it ends or reaches its own timeout (60 s by default);
  a cancellation during the program's tool calls or JS is caught (conjecture, 2026-10-02: the window is
  the context build, not measured further).
- **`ai4_blobs` is never pruned.** An image a deleted branch referred to stays in the folder.
- **Hooks see views, not the registry.** A hook gets a `ToolCallView` and a `ToolResultView`, which hold
  neither the registry nor the result object, so a hook neither calls tools nor changes what the model
  reads; giving them either would be a new decision, not a fix.
- **The eval suite is small**: 17 tasks, mostly one run per arm.
