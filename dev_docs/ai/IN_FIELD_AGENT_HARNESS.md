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

- **`BoxOutputLog`** (`src/jvmMain/kotlin/afield/app/diagnostics/BoxOutputLog.kt`). Before this there was no
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
Field 4 render windows. Details, the pixel checks and the measurements are in
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

## Mined corpus (2026-10-26)

`AgentEvalCorpus` (`ai/agent/AgentEvalCorpus.kt`) mines tasks from the operation transcripts in
`dev_docs/agents/transcripts/`, so the suite stops being seventeen hand-written tasks. Each mined task has
four parts. The goal gives the box's name, its parents, the first two sentences of its leading comment and
the text its output must include. The setup is the box's ancestors, created with their code and run. The
solution is the box's real code. The checks are `box-exists`, `last-run-ok`, `connected` per parent, and
`output-contains` on output that came out the same in two scripted runs. No new check kind was added. The
calibrated corpus is checked in as `src/jvmMain/resources/ai4/agent_eval_corpus.json`. The split is fixed by
id hash, with about a third `heldout`.

```bash
B="uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9631 --command"
$B 'agent-eval --calibrate'                       # re-mine + rewrite the corpus (~4 min)
$B 'agent-eval --corpus all'                      # scripted floor
$B 'agent-eval --corpus heldout --sample 20 --seed 1 --mode live --lessons on --sealed'
```

`--sealed` withholds the four repository-search tools: a mined task's answer is in the repository (below).
Corpus live runs are capped at **25** sampled tasks. The hand suite's cap stays at 17. Demo:
`dev_docs/agents/transcripts/agent_eval_corpus.yaml`, two boxes that print the corpus summary and one task.

**Yield** (267 transcripts, 978 code boxes): 177 static candidates, **116 tasks** (89 checked on printed
lines, 18 on a displayed text node, 9 only on a tag such as `<svg`; 35 heldout). The dropped boxes, by reason:

| reason | boxes | stage |
| --- | --- | --- |
| not executed whole by its transcript (fragment runs, never run) | 366 | static |
| renderer / Mesh / Vulkan (incl. 5 via an ancestor) | 210 | static |
| no output call | 99 | static |
| ran, but said nothing the tool result carries (mostly `lines(...)`/drawing helpers) | 51 | calibration |
| audio, files, clock, camera, python, threads, `loop {}`/`start {}`, shader, unseeded random | 113 | static |
| solution fails in a scratch document (NPE, "another document is recording", source-backed block) | 7 | calibration |
| output differs between two runs | 3 | calibration |
| other (too long, unknown ancestor, self-reference) | 13 | static |

**What mines well**: CPU boxes that `println` a computed summary (`hysteresis on: 553 edges created, ...`).
These are 89 of 116 tasks and every live failure but one infrastructure error. **What mines badly**: drawing
boxes, whose output never reaches the tool result as text (51 dropped, and 9 kept only with a tag check that
any SVG passes). Graphics are a third of the transcripts and cannot be mined without a renderer parent.
**What is brittle**: 18 tasks check floating-point layout to four or more decimals (`plotRect:
Rect(x=73.71484375, ...)`). The one task that failed in both sealed arms (`chart_clip/clip_on`) is of
this kind. 32 tasks have no leading comment, so their goal is a name and a target text.

Two harness bugs turned up and were fixed:
- `ScriptedLlmBridge` treated every `${a.b}` as a placeholder. Any box code with a Kotlin template such as
  `"${it.name}"` threw, which cost 70 of 177 candidates. Placeholders must now name a tool
  (`${XxxRequest.field}`).
- One check quoted the calibrating worktree's absolute path. Lines with absolute paths, uids or identity
  hashes are no longer checks.

### Results (2026-10-26): scripted floor and live before/after

Scripted floor on live Field (port 9631): **116/116** in 93 s, documents 1 → 1 (118/118 on the corpus
before the path filter). Live runs used openai gpt-6.1-sol (`AFIELD_AI_PROVIDER=openai`), because the
configured local endpoint did not answer. Each arm is 20 seeded tasks, one run per arm:

| arm | population | lessons | search | pass | rounds | input tokens | misuse |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | train, seed 1 | off (v5), then `--distil` → v6 | on | 20/20 | 148 | 2,834,827 | 15 |
| B | heldout, seed 1 (corpus of 118) | off | on | 19/20 | 201 | 3,975,333 | 28 |
| C | same 20 as B | on (v6, from A) | on | 20/20 | 159 | 3,133,460 | 5 |
| D | heldout, seed 1 (corpus of 116) | off | sealed | 17/20 | 174 | 3,317,309 | 14 |
| E | same 20 as D | on (v6) | sealed | 19/20 | 150 | 3,471,504 | 10 |

Arm A's distil wrote three lessons: `DescribeToolsRequest` asked for a non-existent `AttachmentRequest`,
`WorkspaceSearchRequest` was called with an empty query, and `EvaluateKotlinExpressionRequest` was given a
blank target. On held-out tasks the lessons cut misuse 28 → 5 with search on and 14 → 10 sealed, and cut
rounds 201 → 159 and 174 → 150. Pass rate is near ceiling (B 19, C 20, D 17, E 19), so on this population
it does not separate the arms. One run per arm: read the misuse difference as a direction, not an effect
size. D's three misses are two layout-float tasks the model renamed or abandoned and one provider outage
(`provider remained temporarily unavailable`). Arm B's miss is the absolute-path check, since removed.
The distilled v6 lessons were **not** kept. `agent_lessons.md` is unchanged in this branch, because
replacing the hand-suite lessons is a separate decision. The v6 text is in the nightly worklog.

**Contamination.** The solutions are in the repository, and the model looks for them. Some non-box tool
result held a line of the task's own solution in 3, 5 and 4 of 20 tasks (A, B, C), through
`SemanticRetrievalSearchRequest` and `EvaluateKotlinExpressionRequest` reading the transcript. Truncated
results make this a lower bound. Sealed, it fell to 1 and 2 of 20, all through
`EvaluateKotlinExpressionRequest`, which can read files and is not withheld. Use `--sealed` for corpus
numbers. A clean seal needs either the corpus solutions kept out of the agent's reachable tree or file
reads denied for that path.
### Continue pass (2026-10-27): runaways, a real seal, the floor on disk

- **Runaway code.** The `forever` deny list now also matches `while (true)` and `do { } while (true)`, and
  `markdown(...)` counts as output. Runaway code the deny list cannot see, such as recursion with no base
  case, hits a 20 s calibration timeout (`CALIBRATION_TIMEOUT_SECONDS`). It is rejected as `timeout`, or as
  `interrupted` once the Runloop watchdog (`BoxInterruptedError`) stops it. It is not counted as
  `solution-fails`. Re-calibrated: 194 candidates, **132 tasks** (92 stdout, 31 display, 9 display-shape;
  42 heldout), with one `timeout` rejection.
- **Sealing.** `--sealed` also withholds `EvaluateKotlinExpressionRequest` and `CodeUseQueryRequest`
  (`SEALED_TOOLS`), the two tools through which the 10-26 sealed arms still read their solution. Box code
  can still open the transcript files, so withholding tools proves nothing on its own.
  `AgentEvalCorpus.leak` searches every non-echoing tool result for the solution's five longest code
  lines. Box output counts, so a box that printed the transcript file is caught. A sealed pass that saw
  its solution scores `leaked`, and the report counts `leaked`.
- **Floor on disk.** `dev_docs/ai/agent_eval_corpus_floor.json` holds the per-task result of the scripted
  floor on live Field 9631: **132/132** in 114 s, documents 1 → 1.

Held-out rerun (openai gpt-6.1-sol, sealed, heldout seed 2, 12 tasks per arm, the same 12 in both arms):

| arm | lessons | pass | rounds | input tokens | misuse | leaked |
| --- | --- | --- | --- | --- | --- | --- |
| F | off (v5) | 12/12 | 90 | 1,716,618 | 4 | 0 |
| G | on (v6, distilled from arm A) | 12/12 | 62 | 1,014,646 | 4 | 0 |

With the wider seal, no task saw its own solution (0 of 24, against 1 and 2 of 20 before). The v6
lessons cut rounds and tokens on this sample but not misuse, which is what they were distilled to cut.
Arms D/E showed misuse 14 → 10. One run per arm, and the rerun does not reproduce the misuse effect, so
**v6 is not committed**: `agent_lessons.md` stays at v5.

## AI4 as the repo's third coding agent (2026-10-27)

`FIELD_AGENT=field` (or `field` as the first word of `~/.afield/agent`) makes Field's own agent the one
the repo's tools hand work to, beside `claude` and `codex`: the landing queue's `huh?`/`bouncy?` and
fixer (`tools/land.py` through `field_agent.headless_argv`), `tools/docs_notes.py revise --agent field`,
and the docs-pass page's terminals (`fieldAgent()` in `terminal.kt`). It is the existing headless task,
not a new runner:

- **The `repo` tools** (`afield/ai/tools/RepoFileTools.kt`): `RepoReadFileRequest` (numbered line
  ranges), `RepoListFilesRequest`, `RepoSearchRequest` (literal or Java regex), `RepoPatchFileRequest`
  (one exact passage, or `replaceAll`) and `RepoWriteFileRequest` (create; `overwrite` opt-in). They
  work in one `RepoScope`: a root, readable globs and writable globs. A path that is absolute, has a
  `..`/`.`/empty segment, starts at `.git`, passes through a symbolic link, resolves outside the root, or
  matches no glob is refused with the reason. Patch and overwrite refuse a file whose first five lines
  say `DO NOT EDIT`. The tools are gated on `ToolExecutionContext.repoFiles`, which only a task with a
  `repoRoot` wires, so no chat sees them and `ai4_tools.md`'s catalogue (a box chat's) does not list
  them. `RepoFileToolsTest` pins the confinement.
- **The job**: `AgentTaskRequest.repoRoot`/`repoRead`/`repoWrite` (result: `repoWritten`); the prompt
  gains one paragraph naming the root, both lists, and that there is no shell. Backdoor:
  `agent-task --goal-file F --repo-root R --repo-read G --repo-write G`.
- **The CLI**: `tools/field_repo_agent.py --root R [--read G] [--write G] [--prompt|--prompt-file|stdin]`
  prints the final answer on stdout and one line per tool call plus model, rounds, wall time, tokens and
  files written on stderr. `FIELD_AGENT_PORT`, `FIELD_AGENT_MODEL`, `FIELD_AGENT_MAX_ROUNDS` (40).
  `headless_argv("field")` is it with `--root .`, plus `--write '**'` when the caller wants edits.
- **docs_notes**: the `field` agent may write only the partition's own files, its outcomes file and
  its handoff drafts. AI4 cannot run `docs_notes.py resolve/question/handoff`, so a preamble tells it to
  write the outcomes JSON the harness already accepts, and to ask a question where it would file a handoff.

### The live runs

All on `openai/gpt-6.1-sol` (reasoning effort low), `AFIELD_AI_PROVIDER=openai` because this machine's
configured `local` provider (qwen3.8-27b) was unreachable: the first try failed in 4.5 s with
`OpenAIIoException: Request failed`. Every run's Field was the one the backdoor started on port 9631.

| Job | Rounds | Wall | Tokens in / out (cache read) | Result |
| --- | --- | --- | --- | --- |
| Toy root: fix two typos, try to read an unlisted file | 4 / 8 | 29 s | 60,057 / 207 (47,846) | both fixed; `secret.txt` refused; it said so |
| `docs_notes revise --agent field`, 3 `kdoc-draft` notes on `Vectors.kt` | 6 / 30 | 46 s (agent), 72 s (harness) | 187,974 / 1,013 (163,871) | harness: `exit 0, 3/3 resolved, 0 question(s), 0 handoff(s), 1 file(s) changed, 46s`, **but the file was generated** |
| Same partition, after the `DO NOT EDIT` refusal | 6 / 30 | 50 s (agent), 75 s (harness) | 184,976 / 1,181 (161,402) | harness: `exit 0, 2/3 resolved, 1 question(s), 0 handoff(s), 0 file(s) changed, 50s` |
| `land.py huh` (read-only, real queue) | not reported by `land.py` | 22 s | — | a correct "alive but idle" diagnosis with the next command |

The store was a copy of `~/.afield/docs-annotations.json`; it had no open human notes (all 166
resolved), so the three notes are completion's real KDoc drafts imported into the copy with
`import-drafts --limit 3`. The first revise read `Vectors.kt` lines 850-1035, never line 1
(`// DO NOT EDIT — generated by tools/linalg-generator`), added KDoc to the generated file and resolved
the note; the harness has no check for that and called it a success. The patch was discarded. With the
refusal in place the second run hit it, read the header, and turned the note into a question for the
generator's owner: the right outcome. Both runs also declined the two `Vector3f.x` drafts as restating
the name, which is what the prompt asks for.

Failure modes seen or expected: a model that never reads a file's header (now refused for `DO NOT
EDIT`, not for other generated-file conventions); no shell, so a prompt that says "run the docs build"
or "run Gradle" cannot be obeyed (the fixer in `land.py` needs exactly that, and was not tried; the
command tool below, 2026-10-28, answers it for named gates); a
prompt as an argv element (`docs_notes` passes it so) is bounded by `ARG_MAX`; the backdoor's own
timeout must cover a cold Field start; and AI4's turn goes through the box-chat instructions, which talk
about boxes it does not need here.

## A bounded command tool, so AI4 can be the lander's fixer (2026-10-28)

The repo tools could read and patch but not run anything, so AI4 could neither reproduce a red nor
confirm its fix. `RepoRunCommandRequest` (`afield/ai/tools/RepoCommandTools.kt`, namespace `repo`) runs
one *named* command in the job's root. It is not a shell:

- **Templates.** `RepoCommandTemplates.ALL` maps a name to a fixed argv prefix and a check for the
  model's `args`: `gradle` (`./gradlew --console=plain` + task names matching a task-path regex, minus
  `clean`/`wrapper`/`run*`/`publish*`/`init`/`generateLocalKeystore`, plus `--tests <filter>`,
  `--offline`, `--continue`, `--rerun`; every other option, including `--stop`, `-D`, `-I`, `-p`, is
  refused), `python-test` (`uv run --project . --group backdoor python` + a readable
  `tools/test_*.py`/`python/**/test_*.py` + unittest names), `check` (the same + one
  `tools/check_*.py`, no options), `git-status`, `git-diff` (`--stat`/`--cached`/`--name-only`/
  `--name-status`, revisions, `--` then confined paths), `git-log` (`-n 1..999`, default 20), `git-show`
  (one revision), and `land-precheck` (`uv run python tools/land.py precheck`; not in the fixer's set,
  because it merges `main` in and out of the tree). git runs with `--no-pager`, `--no-ext-diff`,
  `--no-textconv`, `-c core.fsmonitor=false`, `-c diff.external=`. Paths go through
  `RepoFileTools.resolve`, so a command can name only files the job may read.
- **Process.** argv list, cwd = root, stdin closed; environment = the Field JVM's minus `GIT_*`,
  `VIRTUAL_ENV` (uv warns and ignores it; seen on the first live run) and
  `AFIELD_VERIFICATION_LOCK_OWNER`, plus `AFIELD_VERIFICATION_LOCK=fail`, `GIT_TERMINAL_PROMPT=0`,
  `GIT_PAGER=cat`. One command at a time per job.
- **Verification lock.** A live process holding `<root>/.field-backdoor/verification.lock` refuses the
  run with the owner's record, unless it is an ancestor of the Field JVM (the job's own runner); a dead
  pid is stale. `AFIELD_VERIFICATION_LOCK=fail` makes a Gradle child stop rather than wait 30 minutes
  past the tool's own cap if the lock is taken mid-run.
- **Caps.** Wall clock: the template's default (Gradle 900 s, Python 600 s, git 60 s, precheck 1800 s),
  or the call's `timeoutSec`, clamped to the job's `repoCommandMaxSec` (default 900, 10..3600). On
  timeout the tree is stopped deepest first (TERM, 3 s, KILL) from `ProcessHandle.descendants()`,
  sparing any process whose command line says `GradleDaemon`: daemons are shared by every checkout,
  and killing the client cancels its build. Output: stdout+stderr merged, the first 8 KB and the last
  bytes up to 48 KB kept, the middle dropped and counted (`outputBytes`, `truncated`).
- **Wiring.** `AgentTaskRequest.repoCommands` (names) and `repoCommandMaxSec`; result `repoRuns`
  (`name args -> exit|timeout`); the prompt lists the allowed commands with their templates and says to
  reproduce before editing and confirm after. Backdoor: `agent-task ... --repo-command NAME
  --repo-command-max-sec N`. CLI: `field_repo_agent.py --run NAME` (repeatable; `--run fixer` =
  `RepoCommandTemplates.FIXER_SET`, mirrored in `FIXER_COMMANDS` and pinned by `test_field_agent.py`),
  `--run-max-sec` / `FIELD_AGENT_RUN_MAX_SEC`. `field_agent.headless_argv("field", edits=True)`, which
  `tools/land.py`'s fixer runs, now passes `--write '**' --run fixer`; read-only `huh?` gets no commands.
- **Tests.** `RepoCommandToolsTest` (11): argv built from checked slots; refusals (`--stop`, `clean`,
  `runJvm`, `-D`, `-I`, `;` in a task, `$(...)` in a filter, unlisted scripts, unknown git options,
  oversize `-n`); path confinement against the read list; only the job's names run; cwd, verbatim `A*`
  (no shell expanded it) and environment seen from inside the child; non-zero exit reported, stderr
  merged; a 1 s timeout stops a backgrounded grandchild `sleep 300`; the job cap clamps; head+tail
  output cap; a live foreign lock refuses, our own pid and a dead pid do not.

### The live runs

`openai/gpt-6.1-sol` (`AFIELD_AI_PROVIDER=openai`; the configured local qwen3.8-27b was unreachable,
`OpenAIIoException: Request failed`), Field started by the backdoor on port 9601, the lander's own
`FIXER_PROMPT` filled from `tools/land.py`, and the exact argv `field_agent.headless_argv(edits=True)`
returns with `FIELD_AGENT=field`. Transcripts (prompt, full `AgentTaskResult`, tool log, reply, diff) are
in `dev_docs/ai/ai4_fixer_runs/`.

| Bounce | Rounds | Wall | Tokens in / out (cache read) | Commands it ran | Outcome |
| --- | --- | --- | --- | --- | --- |
| Constructed, Python: a branch renamed `describe("field")` but not its test (`2026-10-28-python/`) | 7 / 40 | 39 s | 151,754 / 508 (130,841) | git-diff, python-test → 1, patch, python-test → 0, git-diff | right one-line test fix, `FIXER-VERDICT` fix, culprit right; `land._fixer_verdict` parses it |
| Constructed, Gradle: a branch changed git-log's default to 30 but not `RepoCommandToolsTest` (`2026-10-28-gradle/`) | 6 / 40 | 43 s | 187,787 / 645 (161,837) | git-diff, `gradle jvmTest --tests …` → 1 (4 s, warm), patch, same → 0 (6 s), git-diff | right one-line fix, verdict fix |
| Real, replayed read-only: `claude/handwriting-pen`'s 2026-10-26 bounce, `DocsFenceTreatmentTest`, on its failing tip `04e486575` (`2026-10-28-replay-learned-pen/`) | 5 / 40 | 31 s | 151,246 / 655 (126,645) | git commands only (Gradle withheld: cold checkout, disk at 13 GB free) | four fence openers changed; **byte-identical** to the lander's real fixup `64c03befa`; it said the test was not rerun |

What this does not show: all three were the easy kind (the failure text names the fix), and the two
constructed ones were written by the same agent that built the tool. No run hit the timeout or output
cap live (the tests do). Not run through a real `tools/land.py` round: that needs the launchd lander
on `FIELD_AGENT=field`, and the lander checkout must have this branch. A cold Gradle build in the
fixer's worktree is still bounded only by the 900 s cap, inside `FIXER_MINUTES` (15).

## The fixer job measured over history (2026-10-29)

`tools/field_fixer_bench.py` replays every past `Lander fixup:` commit through AI4 with the lander's
own brief and grades it with the real failing check (controls: red on the bounced tip, green on the
fixup). Of the 30 cases a cheap check can grade, AI4 passed 29 with either brief, 17 byte-identical to
the lander's fix. The one measured failure that was AI4's own — widening `PersonalBoundaryTest`'s
allowlist instead of fixing the flagged string, 3/3 runs — is gone with a new `FIXER_PROMPT` rule
(3/3 correct). Results, failure modes and how to replay: [AI4_FIXER_BENCH.md](AI4_FIXER_BENCH.md).

## What is not done

- ~~No native box screenshot tool~~: done 2026-09-30 (`ViewBoxOutputRequest`).
- ~~`RunBoxRequest` on code that never returns blocks the Runloop~~: fixed 2026-10-26. On timeout
  the tool interrupts box code that holds the Runloop and returns stage `interrupted`
  (`dev_docs/RUNAWAY_BOX_INTERRUPT.md`).
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
