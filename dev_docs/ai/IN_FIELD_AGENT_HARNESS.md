# In-Field agent harness

Field's own agent (AI4) can now do what external agents do through `tools/field_backdoor.py`:
make boxes, edit them, connect them, run and launch them, stop them and read what they said. A
whole task can be handed to it without a browser and returns a structured result, and a small eval
suite measures it. Landed on the nightly branch `nightly/2026-09-29/1-in-field-agent-harness`.

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
- **`FieldGraphTools`** (`ai/agent/FieldGraphTools.kt`) sits behind a `FieldGraphHost` seam.
  `LiveFieldGraphHost` runs mutations on the Runloop and blocks off it. Tests use a host whose
  "execution" is a table. The tools are gated on `ToolExecutionContext.fieldGraph`, which is wired in
  the backdoor context (`Ai4BackdoorTools.buildContext`) and in every `AgentTask`. **It is not wired
  in the browser chat panel** (`Ai4Installer`): that would change what the everyday chat can do to a
  document, which is a decision for Marc, not for a nightly.
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

A blank `code` means "not given". OpenAI's strict function schemas make every field required, so a
model that means "run what is there" sends `code: ""`. The first live demo run showed that this
used to erase the box's code and then fail with "no code" (regression test
`blankCodeMeansRunWhatIsThereNotEraseIt`).

**Box screenshots are not native yet.** Server-side capture of a box needs the server-owned
Playwright page (`agent-box-screenshot`), which a headless task does not have. The existing
`CaptureRenderWindowRequest` still covers native render windows.

## Entry points

- Backdoor, in the session's document: `agent-task --goal ... --check kind:args ... [--mode live|scripted] [--script file] [--setup file] [--max-rounds N] [--model M]`.
  `ok` is `verdict == "pass"`. `agent-eval [--mode scripted|live] [--ids a,b] [--max-rounds N]`
  runs each task in its own headless scratch document. See `dev_docs/agents/FIELD_BACKDOOR.md`.
- HTTP, guarded by `refuseUnlessSameSiteDocsClient` (loopback, not cross-site):
  `POST /api/agent-task[?document=<open doc>]` with an `AgentTaskRequest` body, and
  `POST /api/agent-eval` with an `AgentEvalRequest`. Without `document`, a headless scratch document
  is made and discarded.
- Box code: `askFieldAgent(goal, checks, mode = "auto"|"live"|"scripted", script, maxToolRounds, channel = "agent")`.
  It runs on a background thread (the tools wait on other boxes' verdicts, which need the Runloop)
  and draws a card with the verdict, the mode, and the tool log. `fieldAgentEvalScript(id)` returns
  an eval task's scripted solution.

Check spellings: `box-exists:B`, `last-run-ok:B` (the latest verdict since the turn began succeeded
*and* was for the current code), `output-contains:B:text` (only what was printed or displayed since
the turn began, so setup output cannot satisfy it), `output-ints-include:B:1,2,3` (in order),
`property-contains:B:prop:text`, `property-lacks:B:prop:text`, `connected:A:B`, `running:B`,
`not-running:B`.

## Eval suite

`InFieldAgentEvals` (`ai/agent/InFieldAgentEvals.kt`), seven tasks:

| id | goal (short) | starts from |
| --- | --- | --- |
| primes | box Primes prints the first 10 primes | empty |
| fix-compile-error | fix `val count: Int = "three"` so it prints `count is 3` | failing box |
| parent-variable | connect Source→Reader; Reader prints Source's `prop<Int>(secret)` by name | two boxes |
| html-table | launch Table displaying an HTML table of n, n² | empty |
| stop-ticker | stop a running `loop {}` box | launched box |
| wire-two | Alpha→Beta | empty |
| fix-runtime-error | empty list `.first()` → print `empty` | throwing box |

Each task has a scripted solution in real Kotlin. The first scripted run against real Field caught
an error in the harness's own instructions: a child does **not** see a parent's plain `val`
(`Unresolved reference 'secret'`). Values are shared with `prop<T>(name) = v`. The task and the
agent instructions were corrected.

### Results (2026-09-29)

Populations are stated per row. "Scripted" means no model: the tools, real K2 execution and the
checks, driven by the canned solutions.

| run | population | passed | notes |
| --- | --- | --- | --- |
| jvmTest `InFieldAgentHarnessTest` (fake host) | 18 tests | 18 | includes all 7 scripted solutions against the fake host |
| scripted, live Field, backdoor `agent-eval` | 7 tasks | 6 → 7 | 6/7 first; parent-variable failed on the `val` idiom; 1/1 after the fix |
| scripted, live Field, HTTP `/api/agent-eval` | 7 tasks | 7 | 17.6 s total |
| live model, backdoor `agent-task` (Squares) | 1 task | 1 | openai gpt-6-astra, 7 rounds, 37,364 in / 557 out tokens, 48 s; 3 rounds spent on `CompileKotlinRequest` fileRef guesses |
| live model, demo box `agent_demo_live` (Table) | 1 task | 1 | openai gpt-6-astra, 3 rounds, 17,594 in / 283 out tokens, 26 s; round 2 hit the `code: ""` bug |
| live model, HTTP `/api/agent-eval` | 7 tasks | 7 | openai gpt-6-astra, max 24 rounds; 1–5 rounds per task (16 total), no cap hit; 98,738 in / 866 out tokens; 104.7 s |

A first attempt at the live eval over the backdoor websocket died after about 15 s with
`ConnectionClosedError 1006`, while the Field it drove was still alive. At the same time a sibling
nightly agent brought up a Field on this night's assigned port 9611, so the cause is not settled; the
port collision is the leading suspect. It cost one live task (primes, abandoned mid-turn). The rest
ran on port 9637 and over HTTP.

## What is not done

- The graph tools are not in the browser chat panel's context (a product decision, above).
- No native box screenshot tool (needs Playwright; see above).
- `RunBoxRequest` on code that never returns blocks the Runloop, just as a Run from the editor
  does. The tool times out and says so, but it cannot unstick Field.
- `CompileKotlinRequest` fileRefs for scratch documents (the document name is a uuid) cost the live
  model three rounds. The instructions now say RunBox compiles on its own, but the fileRef resolver
  itself was not changed.
- Checks are data, not arbitrary predicates. Adding a kind means editing `AgentCheck.parse`, the
  evaluator, and `parse_agent_check` in `field_backdoor.py`.
- The eval tasks are small, and seven tasks is not a benchmark. The scripted row shows only that the
  harness, the checks and real execution agree.
