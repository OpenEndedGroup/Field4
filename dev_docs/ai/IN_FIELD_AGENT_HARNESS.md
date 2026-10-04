# In-Field agent harness: AI4, extended

**This is AI4 extended, not a second chat harness.** The box graph and execution tools are part of
AI4's one shared tool registry (`registerBuiltInAgentTools`) and are wired into every AI4 chat path.
`AgentTaskRunner`, `askFieldAgent`, `agent-task` and `/api/agent-task` are headless *entries* into
the same turn: `IdeChatHelpers.chatGeneral`, with the same bridge factory (`createIdeBridge`), the
same thread instructions (`defaultChatThreadInstructions`) and the same instruction builder
(`buildDynamicInstructions`, which describes the graph tools through `fieldGraphToolGuidance`). With
`--chat-box <box>` a task goes through the real `ai-assist` handler as that box's panel turn, and
lands in its `ai4_chat_history`. No separate chat harness remains. The only code of its own is the
task wrapper: setup, checks, verdict and result.

## Which AI4 path has which tools (2026-09-29)

| Path | Handler → helper | Reply | Graph + execution tools | Text editing |
| --- | --- | --- | --- | --- |
| Box panel, inline chat (`kind: chat`) | `ai-assist` → `chatInline` | rewrites the current box/property (a proposal the user accepts) | yes; the current box/property is *reply-owned* | the reply (current box); `EditFile` (other files, whole-file proposals); `SetBoxProperty` / `PatchBoxText` (other boxes, applied) |
| Box panel, cursor / rewrite / error | `ai-assist` → `chatFile` | rewrites the current box/property | yes, reply-owned as above | as above |
| Box panel, conversation (`kind: general`) | `ai-assist` → `chatGeneral` | prose | yes, nothing reply-owned | `EditFile`, `SetBoxProperty`, `PatchBoxText` |
| Document chat (output-area chat) | `chat_input` → `chatGeneral` | prose | yes, nothing reply-owned | `SetBoxProperty`, `PatchBoxText`. **Before this change it could not edit a box at all**: it had no `EditFile` and no graph tools |
| Headless task | `AgentTaskRunner` → `chatGeneral` (ctx overload) | prose + checks | yes | `SetBoxProperty`, `PatchBoxText` (+ the backdoor context's Kotlin tools) |
| Headless task as a panel turn | `agent-task --chat-box B` → real `ai-assist` `general` | prose, saved in B's history | yes (the panel's own context) | as for `general` |
| Backdoor / MCP tool calls | `agent-ai-tool-call` → `Ai4BackdoorTools` | — | yes | yes |

`chatError` (IdeChatHelpers) is wired too, though no handler calls it today; error kinds go through
`chatFile`.

**Reply-owned text.** In an editor turn the reply is the new text of the current box's property.
`SetBoxPropertyRequest` / `PatchBoxTextRequest` on that `(box, property)` refuse, with a message that
points at the reply, because the edit would be overwritten when the reply is accepted.
`RunBoxRequest(box = current, code = candidate)` **runs the candidate without writing it**, so the
editor chat can check its edit before replying. Running other boxes, or other properties of the
current box, is unrestricted. The editor shows the model Kotlin wrapped in
`/*preamble*/ fun SCRIPT() { … }`, and models pass that wrapper back; the tools strip it
(`unwrapEditorScript`), because run as-is it only *declares* `SCRIPT` and prints nothing. The first
live editor turn hit exactly that.

**`PatchBoxTextRequest`**: exact find/replace (it refuses a find that is ambiguous or absent;
`occurrence` N or 0 for all) or a 1-based line range. It is recorded through the same property path
as `SetBoxPropertyRequest` (`applyAgentPropertyRecorded` → `OperationTranscript.recordEdit`) and
applied at once. Unlike `EditFile` it is not a provisional proposal.

## Architecture

```
 box code: askFieldAgent(goal, checks)     backdoor: agent-task / agent-eval     HTTP: POST /api/agent-task, /api/agent-eval
                  \                                   |                                   /
                   +----------------> AgentTaskRunner.run(request, SessionInformation) <---+
                                        | setup script (ScriptedLlmBridge, not logged as the agent's)
                                        | LlmBridge.send(goal, ctx, maxToolRounds)   <- OpenAI / Anthropic / ScriptedLlmBridge
                                        |      ctx = Ai4BackdoorTools.buildContext(info) + fieldGraph
                                        |      tools: registerBuiltInAgentTools -> registerFieldGraphTools
                                        | checks over the document + BoxOutputLog since the turn began
                                        v
                                   AgentTaskResult (verdict, tool log, rounds, tokens, elapsed)
```

- **`BoxOutputLog`** (`src/jvmMain/kotlin/afield/BoxOutputLog.kt`). Before this there was no
  server-side record of what a box printed; it existed only on the websocket. The log is a bounded
  per-box ring (400 entries per box, 4000 chars per entry, 4096 boxes) of `out` / `err` / `display` /
  `verdict` entries with a global sequence number. It is fed where each kind of message originates:
  `dispatchExecutionOutput` (println and errors), the document broadcast `_send(message, document)`
  (`set-output`, i.e. `display`), and `ExecutionTrace.finish` (verdicts). So it records browser runs,
  backdoor runs and agent runs alike, once each, whether or not a browser is attached.
- **Shared execution paths.** The bodies of the `execute-all`, `launch-all` and `stop-box` handlers
  are now `executeAllRequest`, `launchAllRequest` and `stopBoxRequest` (`server/executing.kt`), and
  `agent-create-box` is now `createAgentBox` (`agent_backdoor.kt`). The tools call these, and they
  call the actionTable's `-connect` / `-disconnect` / `-delete-box`, so no execution logic is
  reimplemented. The websocket handlers call the same functions they used to inline.
- Review fixes (2026-09-29, evening): `DeleteBoxRequest` and `LaunchBoxRequest(code=…)` are refused
  on the reply-owned box. A candidate *launch* would leave unaccepted code running; use
  `RunBoxRequest(code=…)`. `PatchBoxTextRequest` refuses a negative `occurrence`, counts and picks
  matches by one non-overlapping rule, treats `endLine = 0` as "just `startLine`" everywhere, and
  inserts only with an explicit `insert = true`. `BoxOutputLog` releases an evicted box's
  characters, and a drop by either bound sets `Page.dropped`.
- **`FieldGraphTools`** (`ai/agent/FieldGraphTools.kt`) sits behind a `FieldGraphHost` seam.
  `LiveFieldGraphHost` runs mutations on the Runloop and blocks off it. Tests use a host whose
  "execution" is a table. The tools are gated on `ToolExecutionContext.fieldGraph`, which is wired in
  the backdoor context (`Ai4BackdoorTools.buildContext`), in every `AgentTask`, and (after Marc's
  review) in every chat path through one factory, `FieldGraphTools.forTurn` (table above).
- **`ScriptedLlmBridge`** (`ai/core/ScriptedLlmBridge.kt`) replays tool calls through the real
  registry. `${Tool.field}` placeholders take values from earlier results, and it records what it was
  given. Its provider is `scripted`, so a scripted pass can never be read as a model's.
- **`AgentTaskRunner`** (`ai/agent/AgentTask.kt`) takes an `AgentTaskRequest` (goal, checks, setup,
  focus box, round budget, mode `live`/`scripted`, script, model) and returns an `AgentTaskResult`.
  The verdict is `pass` (all checks pass), `budget-exhausted` (checks fail and the round cap was
  hit), `fail`, `error`, or `unchecked` (no checks were given). **The verdict comes from the checks,
  never from the agent's final text.**
- `SendResult` gained `rounds`, `hitToolRoundCap`, `inputTokens` and `outputTokens`, all with
  additive defaults. The OpenAI bridge sums `Response.usage()` over every request in the turn, and
  the Anthropic bridge sums `Message.usage()`.

## askFieldAgent answers in the AI4 panel (2026-09-29, evening)

After running the demo, Marc asked for this: the output is the box's own AI4 chat panel, which is
better formatted and lets the person continue the conversation. The custom HTML card is gone.

- **Live**: the goal is dispatched to the real `ai-assist` handler as a `general` panel turn in
  the target box, the same turn a message typed into that panel starts. It is saved in
  `ai4_chat_history`. Then the checks run, and `AgentTaskTranscript.appendVerdict` adds a
  `<<progress>>` block after the reply. Its first line is the whole verdict
  (`✓ box-exists:Table  ✓ last-run-ok:Table  … — PASS`), because the panel folds a progress
  entry to its first line. One line per check with its detail follows.
- **Scripted**: the replay runs through the same tools. `appendScriptedTurn` writes the whole
  exchange in the transcript's markup: the goal as `<<you>>`, "Scripted replay — no model was
  called", each scripted tool call as a progress entry (as a live turn's appear), the scripted
  reply under a `scripted replay · no model` identity, and the verdict.
- **Fails fast**, with the reason in the transcript and in the result, when the box already has
  an AI turn running, or when the handler does not start one (AI setup failed). A turn that did
  start gets 10 minutes. The reply is taken only from what the history gained after dispatch,
  following a `<<you>>` block that carries this goal, so another turn's reply is never returned.
- The panel turn's visible user message is the goal alone. The headless task framing ("nobody can
  answer questions") is used only by non-panel tasks. The panel's own round limit applies;
  `maxToolRounds` is not passed through `ai-assist`.

Verified live (openai gpt-6-astra, 3 turns):
1. Demo live box: the panel showed the model's tool calls, its reply, and the verdict block.
2. A follow-up `ai-assist` message in the same box asked what Table displayed and how many tool
   rounds were used. It answered from the earlier turn ("rows (1, 1) … (5, 25) … 4 tool rounds"),
   so the conversation continues.
3. The demo live box re-run on the final build: `✓ box-exists:Table ✓ last-run-ok:Table
   ✓ output-contains:Table ✓ output-ints-include:Table — PASS`.

`ai4_chat_history` was read back (`agent-get-property`) after turns 1 and 2, and after the scripted
box run.

## Tools

All return one `FieldGraphResult` shape with `success` and `message`. Boxes are named by uid or a
unique name; an ambiguous name is refused, never guessed.

| Tool | What it does |
| --- | --- |
| `ListBoxesRequest` | uid, name, parents, children, running, code length, last-run verdict |
| `NewBoxRequest(name, code, parent)` | creates via `createAgentBox`; placed to the right of everything, because output areas hang *below* boxes |
| `DeleteBoxRequest(box)` | actionTable `-delete-box` |
| `SetBoxPropertyRequest(box, property, value)` / `GetBoxPropertyRequest` | the recorded property path used by `agent-set-property` |
| `ConnectBoxesRequest(parent, child)` / `DisconnectBoxesRequest` | actionTable `-connect` / `-disconnect` |
| `RunBoxRequest(box, code?, timeoutSeconds)` | `executeAllRequest` with a request id, then waits for **the box's own verdict**. `success:false` carries `error` and `errors`. No verdict within the timeout returns `stage: timeout`, not success |
| `LaunchBoxRequest(box, code?, timeoutSeconds, settleSeconds)` | `launchAllRequest`; waits for setup, reports `running` |
| `StopBoxRequest(box)` | `stopBoxRequest`; waits up to 2 s for jobs to end, reports `running` |
| `ReadBoxOutputRequest(box, since, contains, waitSeconds, maxEntries)` | log entries after a marker; optional bounded wait for text; returns the next marker |
| `ViewBoxOutputRequest(box, settleMs, note)` | a picture of the box's output panels, attached as a tool image, with size, ink fraction and dominant colours as text (2026-09-30; see [AI4_SEES_ITS_OUTPUT.md](AI4_SEES_ITS_OUTPUT.md)) |

A blank `code` means "not given". OpenAI's strict function schemas make every field required, so a
model that means "run what is there" sends `code: ""`. The first live demo run showed that this
used to erase the box's code and then fail with "no code" (regression test
`blankCodeMeansRunWhatIsThereNotEraseIt`).

**Box screenshots are native (2026-09-30).** `ViewBoxOutputRequest` captures a box's output area
through a server-owned Playwright page of its own (`BoxOutputCapture`), headless, with or without a
browser attached. Panels a box displayed before any page existed are replayed to it. The page is
released with its task document and when idle. `CaptureRenderWindowRequest` still covers native
afield.four render windows. Details, the pixel checks and the measurements are in
[AI4_SEES_ITS_OUTPUT.md](AI4_SEES_ITS_OUTPUT.md).

## Entry points

- Backdoor, in the session's document: `agent-task --goal ... --check kind:args ... [--mode live|scripted] [--script file] [--setup file] [--max-rounds N] [--model M]`.
  `ok` is `verdict == "pass"`. `agent-eval [--mode scripted|live] [--ids a,b] [--max-rounds N]`
  runs each task in its own headless scratch document. See `dev_docs/agents/FIELD_BACKDOOR.md`.
- HTTP, with its own guard (`server/agentRouteAccess.kt`: a loopback peer, a `Host` of `localhost`,
  `127.0.0.1` or `[::1]` against DNS rebinding, and same-origin). It is deliberately not the docs
  annotation policy, whose opt-ins must not open code execution:
  `POST /api/agent-task[?document=<open doc>]` with an `AgentTaskRequest` body, and
  `POST /api/agent-eval` with an `AgentEvalRequest`. Without `document`, a headless scratch document
  is made and discarded.
- Box code: `askFieldAgent(goal, checks, mode = "auto"|"live"|"scripted", script, maxToolRounds, chatBox = null)`.
  **It answers in an AI4 chat panel**: that of the calling box, or of `chatBox` (a box or a name).
  It returns `CompletableFuture<AgentTaskResult>`. See "askFieldAgent answers in the AI4 panel"
  below. `fieldAgentEvalScript(id)` returns an eval task's scripted solution.

Check spellings: `box-exists:B`, `last-run-ok:B` (the latest verdict since the turn began succeeded
*and* was for the current code), `output-contains:B:text` (only what was printed or displayed since
the turn began, so setup output cannot satisfy it), `output-ints-include:B:1,2,3` (in order),
`property-contains:B:prop:text`, `property-lacks:B:prop:text`, `connected:A:B`, `running:B`,
`not-running:B`.

## Eval suite

`InFieldAgentEvals` (`ai/agent/InFieldAgentEvals.kt`), seventeen tasks: five graded by pixels (added
2026-09-30), and five added 2026-10-02 to measure what AI4 took from Pi ([AI4_PI_LESSONS.md](AI4_PI_LESSONS.md)):

| id | goal (short) | starts from |
| --- | --- | --- |
| primes | box Primes prints the first 10 primes | empty |
| fix-compile-error | fix `val count: Int = "three"` so it prints `count is 3` | failing box |
| parent-variable | connect Source→Reader; Reader prints Source's `prop<Int>(secret)` by name | two boxes |
| html-table | launch Table displaying an HTML table of n, n² | empty |
| stop-ticker | stop a running `loop {}` box | launched box |
| wire-two | Alpha→Beta | empty |
| fix-runtime-error | empty list `.first()` → print `empty` | throwing box |
| swatch-colour | name the colour a CSS-cascade swatch *shows* (blue; the source reads red) | displaying box |
| draw-sun | an SVG sun on a night sky, graded by colour shares | empty |
| bars-visible | percent-height bars that render 0 px tall → visible | displaying box |
| bars-sorted-red | bars sorted as *displayed* (another box's stylesheet reverses them), tallest red | two boxes |
| bars-one-row | ten 28 px bars in one row (they wrap at the panel's width) | displaying box |
| audit-failing | Report prints which of Cell01..Cell10 fail when run | ten boxes, three throwing |
| sweep-parameter | run Sq for k = 1..8, Results prints the eight outputs, Sq left at k = 1 | one box |
| tag-todos | insert `// reviewed` into the five of twelve boxes with a TODO, running nothing | twelve boxes |
| four-turns | four user turns in one conversation: Counter 1..3, 1..5, squares, then Total | empty |
| revise-table | three user turns revising one displayed table | empty |

The first three are made of many similar calls and their scripted solutions are tool programs
(`RunToolProgramRequest`); the last two are several turns of one conversation
(`AgentTaskRequest.followUps`, `--follow-up`). Two A/B flags run either kind against its old
behaviour in the same build: `programs=off` (`--programs off`) withholds the program tool, and
`context=legacy` (`--context legacy`) puts the instructions back inside the stored message. Three check
kinds came with them: `effects-absent:<EFFECT>` and `effects-present:<EFFECT>`, over every call the turn
made including a program's nested ones (a call with undeclared effects fails `effects-absent`), and
`tool-used:<Name>`. `tools/test_agent_check_parse.py` keeps `parse_agent_check` in step with
`AgentCheck.parse`. Live runs are capped at 17 tasks. Reports carry `cacheReadTokens`,
`cacheWriteTokens`, `programCalls`, `nestedCalls`, `turns` and `contextPlacement`.

Each task has a scripted solution in real Kotlin. The first scripted run against real Field caught
an error in the harness's own instructions: a child does **not** see a parent's plain `val`
(`Unresolved reference 'secret'`). Values are shared with `prop<T>(name) = v`. The task and the
agent instructions were corrected.

### Results (2026-09-30): lessons and the tool fix

Ten tasks, one live run per condition, openai gpt-6-astra. All five conditions passed 10/10, so what
they compare is cost. Full tables and population notes are in
[AI4_SEES_ITS_OUTPUT.md](AI4_SEES_ITS_OUTPUT.md) ("Before / after, live").

| run | condition | rounds | input tokens | misuse calls |
| --- | --- | --- | --- | --- |
| A / A2 | lessons off, base tools | 63 / 61 | 620,187 / 593,022 | 13 / 12 |
| B | lessons on (distilled from A) | 50 | 520,131 | 0 |
| C | lessons off, CompileKotlin description + resolver fixed | 33 | 348,537 | 2 |
| D | lessons on (distilled from C), tools fixed | 31 | 332,607 | 1 |

Scripted, live Field over HTTP: 12/12 in 28 s, with 0 documents and 0 capture pages left open.
Measured on held-out tasks (two runs per setup), the lessons cut misuse 4 → 1 per run with the base
tools and did nothing once the tool was fixed; see AI4_SEES_ITS_OUTPUT.md ("Held out").

### Results (2026-09-29)

Populations are stated per row. "Scripted" means no model: the tools, real K2 execution and the
checks, driven by the canned solutions.

| run | population | passed | notes |
| --- | --- | --- | --- |
| jvmTest `InFieldAgentHarnessTest` + `Ai4ChatGraphWiringTest` + `AgentTaskPanelTest` (fake host; final, `--rerun`) | 18 + 8 + 3 tests | 29 | includes all 7 scripted solutions against the fake host |
| scripted, live Field, backdoor `agent-eval` | 7 tasks | 6 → 7 | 6/7 first; parent-variable failed on the `val` idiom; 1/1 after the fix |
| scripted, live Field, HTTP `/api/agent-eval` | 7 tasks | 7 | 17.6 s total |
| live model, backdoor `agent-task` (Squares) | 1 task | 1 | openai gpt-6-astra, 7 rounds, 37,364 in / 557 out tokens, 48 s; 3 rounds spent on `CompileKotlinRequest` fileRef guesses |
| live model, demo box `agent_demo_live` (Table) | 1 task | 1 | openai gpt-6-astra, 3 rounds, 17,594 in / 283 out tokens, 26 s; round 2 hit the `code: ""` bug |
| live model, HTTP `/api/agent-eval` | 7 tasks | 7 | openai gpt-6-astra, max 24 rounds; 1–5 rounds per task (16 total), no cap hit; 98,738 in / 866 out tokens; 104.7 s |

**Through the real chat handlers (after the AI4 rework, 2026-09-29 afternoon)**, openai gpt-6-astra,
5 live turns:

| turn | how it was sent | result |
| --- | --- | --- |
| document chat | backdoor `raw-send chat_input` | NewBox(ChatSquares) → RunBox → ReadBoxOutput; reply "It printed exactly: 1 4 9 16 25 36"; 20 s |
| box panel inline edit, first try | `raw-send ai-assist` kind `chat` on box Cubes ("print cubes instead; test your candidate first") | CompileKotlin ✓, RunBox(candidate) "succeeded" but printed nothing: the candidate still wore the editor's `SCRIPT` wrapper. Fixed by `unwrapEditorScript` |
| box panel inline edit, retry | same | CompileKotlin ✓, RunBox(Cubes, candidate) ✓ stdout `[1, 8, 27, 64] (4)`; reply became the proposal; Cubes' stored code unchanged until accepted (checked) |
| task as a panel turn | `agent-task --chat-box Panel` | pass: FromPanel made, run, printed "hello from the panel"; the exchange is in Panel's `ai4_chat_history` (checked) |
| demo live box | `run agent_demo_live` | pass (Table 1..5), 6 rounds, 59,609 in / 559 out tokens. Two rounds went to `CompileKotlin` fileRef guesses again |

The fake-host jvmTests, run with `--rerun`: `InFieldAgentHarnessTest` 18/18 and `Ai4ChatGraphWiringTest`
7/7. The second asserts that all four chat helpers offer the graph tools and `PatchBoxTextRequest`
when wired and offer none when not, and it covers the reply-owned rule, the candidate run, the
wrapper stripping, the patch semantics, the route guard, and the log bounds.

A first attempt at the live eval over the backdoor websocket died after about 15 s with
`ConnectionClosedError 1006`, while the Field it drove was still alive. At the same time a sibling
nightly agent brought up a Field on this night's assigned port 9611, so the cause is not settled; the
port collision is the leading suspect. It cost one live task (primes, abandoned mid-turn). The rest
ran on port 9637 and over HTTP.

## What is not done

- ~~No native box screenshot tool~~: done 2026-09-30 (`ViewBoxOutputRequest`).
- `RunBoxRequest` on code that never returns blocks the Runloop, just as a Run from the editor
  does. The tool times out and says so, but it cannot unstick Field.
- ~~`CompileKotlinRequest` fileRefs cost the live model rounds~~: fixed 2026-09-30. The tool's
  description now says RunBox compiles by itself (live CompileKotlin calls 20 → 0), and the
  backdoor resolver accepts every spelling the model guessed.
- A `--chat-box` task returns no per-call tool log (the panel keeps it; the provider's tool-io
  log under `~/.afield/logs/ai4_tool_io/` has it). Its checks still decide the verdict.
- The chat handlers still build their tool contexts inline (Ai4Installer, around 1000 lines of
  box-focused closures). What is shared is the graph-tool factory, the registry, the instruction
  builder, the turn function, and the bridge factory. The per-handler provider closures are not
  folded into one builder yet.
- Checks are data, not arbitrary predicates. Adding a kind means editing `AgentCheck.parse`, the
  evaluator, and `parse_agent_check` in `field_backdoor.py`.
- The eval tasks are small, and seven tasks is not a benchmark. The scripted row shows only that the
  harness, the checks and real execution agree.
