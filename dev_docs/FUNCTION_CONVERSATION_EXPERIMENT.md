# Conversational Functions experiment

The recorded experiment is `dev_docs/agents/demos/function_conversation_example`.
`dev_docs/agents/demos/function_conversation` is now the blank starter, with three
zero-valued channels and no inherited conversations or revisions. The public
walkthrough lives in `public/docs/function_conversation.md`. Regenerate its two
download archives and selected evidence with `uv run python tools/package_function_conversation.py`;
use `--check` to verify they match their canonical sources.
It begins from three constant channels and records actual AI4 conversations that
grow and revise ordinary Kotlin into three `Functions.Fn` values. The transport is
the existing AI4 general-chat RPC, not a separate agent implementation. The first
run used the configured OpenAI provider and `gpt-6-astra`; exact response model,
tool calls/results, repair attempts and wall-clock timings are retained.

## Repository inspection and design choices

| Existing surface | What this experiment uses |
| --- | --- |
| `src/jvmMain/kotlin/afield/stdlib/Functions.kt` | Scalar `Fn(domain, graph, evaluator)`; `eval(t)` is unchecked, `evalSafe` respects domain. Arithmetic intersects domains. Lambdas can be opaque; graph-aware combinators remain available. Channels are a list of ordinary Fn values. |
| `FunctionGraph.kt`, `FunctionRewriteKotlin.kt` | Existing graph inspection/rewrite machinery remains usable for graph-aware expressions; opaque event closures are edited as readable source. No second AST or function language. |
| Functions time/shape operations | Existing oscillators, envelopes, interpolation, timelines, `stretchBy`, phase modulation and transforms. The generated chirp explicitly integrates instantaneous Hz; this is distinct from a frequency parameter passed to an oscillator. |
| `Fn.plotHtml` and `Fn.plot` | Existing interactive single-function plot API remains available. Added aligned PNG min/max plots because uniform overview line plots conceal sub-second pulses over an hour. Both PNGs and measurements evaluate the actual Fn. |
| Box/Monaco + source sidecars | One authoritative editable Kotlin box. Field continues to own editing, file watching, source persistence and document history. The experiment stores immutable checked source revisions alongside it. |
| `KotlinScriptEngine` | Fresh existing Field compiler engine per candidate, disposed after evaluation. Candidate returns StudyComposition; validation precedes publication. No fake compiler or parser. |
| `AgentToolExtensions`, `CarriesToolImages` | Request classes and handlers are defined in the document's tools box. Rerunning replaces tools in already-open AI4 conversations. Pixels take the existing model image-input path. |
| `LlmBridge`, `Ai4Installer`, provider credentials | Unmodified provider selection, model configuration, tool execution and streaming. Driver sends `ai-assist` with `kind=general`, as the UI does. No credential copying or new service. |

`FunctionStudy.kt` is the small experimental host: revision snapshots, guarded
candidate admission, bounded sampling, rendering, comparisons and history loading.
The event structure is metadata returned alongside the function, rather than an
independent editable model. It has stable IDs and explicit landmarks in seconds.
The source remains authoritative. Manual edits are visible as dirty source, and
both the active revision and live-source hash must match to accept an agent edit.

## Running and inspecting it

From this checkout, start a persistent isolated Field:

```sh
uv run --project . --group backdoor python tools/field_session.py serve \
  --port 9788 --workspace /Users/marc/field4/dev_docs/agents/demos --timeout 120 --with-playwright
```

In another terminal, attach and run the document tools:

```sh
uv run --project . --group backdoor python tools/field_session.py call \
  --port 9788 --no-reset --with-playwright --timeout 120 \
  --command 'open function_conversation_example --must-exist' --command 'run function_tools'
```

Open `http://localhost:19788/index.html?file=function_conversation_example`. Select
**The function — talk here (AI4)** and open AI4 general chat. The tools box shows
overview, detail, comparisons and preservation checks. The document guide explains
the exact source contract, timing policies and limits.

To record another genuine AI4 turn, choose a new label:

```sh
uv run --project . --group backdoor python tools/run_function_conversation.py --document dev_docs/agents/demos/function_conversation_example \
  --port 9788 --label my-next-turn --prompt-file /path/to/request.txt
```

The driver refuses to overwrite saved conversations. `conversations/*.json` stores
the actual full UI panel, prompts, before/after inspection and timings. Markdown
shows individual turns. Tool images are preserved byte-for-byte in sidecars, and
AI4's tool-I/O log records calls, source candidates, exceptions and model identity.
For new recording sessions, set `AFIELD_AI4_TOOL_IO_LOG_PATH` on the server to the
document's `conversations/tool-io` directory (otherwise AI4 uses its normal log path).

## What this experiment exposed

1. Scalar `sin/cos` inside `with(Functions)` can resolve to Fn constructors. AI4's
   first candidate failed compilation, retained the flat working revision, then
   repaired it using qualified `kotlin.math` calls. The guide now calls this out.
2. Optional revision strings may arrive as an empty string from a model despite a
   Kotlin default. The document tool normalizes an empty revision to `current`.
3. Asking for preservation with only interval plots causes multiple large visual
   calls. Added CompareFunctionStudy during the conversation; the cached AI4
   bridge picked it up and used it on the very next edit.
4. Overview envelopes show excursions but not pulse ordering. Local peak times
   and inter-peak measurements were added to the same revision-consistent bundle.
5. Publishing from a box on the runloop must not synchronously dispatch back to
   that same runloop. The tools callback now handles owner-thread execution
   directly and dispatches AI4-worker publication to the owner.
6. The channel-lag turn hit the existing Wither compiler-plugin `has no callableId`
   exception. Its attempted diagnostic query then stalled over four minutes in
   `CodeUseTelemetryService.summary()`'s full-database SQLite scan (thread dump
   retained). AI4's bounded code-use queries now attach the existing nonblocking
   `summarySnapshot()` rather than waiting for that scan. This applies to the
   shared UI/backdoor runtime provider. The failed conversation is saved as
   interrupted, not described as a successful model turn.

## Limits kept explicit

This is an experimental vertical slice, not all phases of the brief. Pure,
deterministic, fast Fn evaluators are required. Finite probes cannot prove absence
of narrow features or statefulness; declared event metadata cannot prove a
constant interval in arbitrary Kotlin. The first slice assumes [0,1] output bounds.
Measured joins, peaks, bounds and preservation are labelled as sampled evidence.

Kotlin executes in Field's existing JVM, not an isolated worker. Source/sample
budgets and cooperative cancellation protect normal work, but cannot terminate
arbitrary blocking code. AI4 generation cancellation, full process isolation,
template editing, branching UI, 3D trajectories and automatic intent-to-constraint
inference remain future work. Existing API credentials stay in Field's configured
facilities. AI4 SendResult does not expose token usage; no usage figures are invented.

## Next controlled comparison (not yet run)

Run the same 20 requests with source only, source plus plots, and source plus plots
and measurements. Use the same starting source/model/settings for each condition;
retain all candidate/repair turns. Score completion, unintended changes, wrong
referents, repair count, latency, model usage when available, and Marc's preference.

1. Create the motivating one-hour composition.
2. Move the third event later.
3. Move it across a neighbour and retain its ID.
4. Double its rise with start pinned.
5. Double its rise with plateau pinned.
6. Lag only channel y and catch up before the hold.
7. Delete the fourth interruption.
8. Recover the exact background in the deleted region.
9. Warp a background passage with pinned endpoints/slopes.
10. Restore background while retaining edited events.
11. Produce two plateau-transition alternatives.
12. Compare pulse-rate schedules at equal envelopes.
13. Increase envelope depth while preserving pulse timing.
14. Change carrier period without changing phase-drift timescale.
15. Change drift timescale without changing carrier period.
16. Resolve a request after an intervening manual source edit.
17. Reject a stale expected revision/hash.
18. Recover from failed compilation without losing the working function.
19. Cancel observation/publication and repeat deterministic probes.
20. Inspect a deliberately narrow feature and state sampling limitations.

The live run's verified outcomes and artifact links are summarized in the saved
document's README. This proposal is not an ablation result.

## First user-run failures and repairs (2026-09-22)

The first user conversation exposed two failures the prepared demo had missed:

- **An open editor reverted the accepted function.** `EditFunctionStudy` compiled
  and saved the sine-wave revision, but `diff3.js` sorted merge hunks as strings.
  Insertions at lines 8 and 9 followed by a replacement at line 10 were reordered;
  the merger invented a conflict and kept the unchanged local text. The editor
  then sent the zeros back to the server. Sorting numerically fixes this for both
  main and embedded editors. `node tools/test_diff3_merge.cjs` covers digit
  boundaries, edits on either side, disjoint concurrent edits, and real conflicts.
  The actual document-defined tool was also tested through the backdoor with
  Monaco open: before the fix, revision r2 existed but `dirty=true`; afterwards,
  Monaco, the selected box, and the inspected live source all matched r2 with
  `dirty=false`. Future conversational verification must keep the editor open.
- **Source lookup waited on database startup maintenance.** A thread dump showed
  `CodeUseRelatedSourceForCodeRequest` waiting for `ensureSchema`, whose writer
  held the initialization lock during `enqueueRecentSidecarCatchup`. SQLite's
  correlated query scanned `symbol_uses` for every recent snippet. Indexes on
  `symbol_uses(snippet_id, confidence)` and `snippets(updated_at DESC)` are now
  installed by schema initialization. After installing them in the affected
  database and restarting Field, the exact previously blocked tool request
  succeeded in 2.713 seconds. This is distinct from the earlier summary-scan fix.

The user's accepted sine-wave source was recovered from its immutable revision
into the live box, and both metadata and the source sidecar were checked. Original
source, AI4 logs, before/after editor evidence, thread dumps, and the restarted
lookup result were retained under that document's
`diagnostics/2026-09-22-editor-recovery/`. Restarting a JVM does not update an
already-open browser's JavaScript: reload the page to pick up the merge repair.
# Interactive signal viewer, 2026-09-22

The tools box now displays one Plotly x-zoomable and pannable `overview` output, using the
new reusable `SignalViewer` / `Box.displaySignal` API. It follows the audio viewer's
gesture and timeline-sync conventions. Visible-window queries evaluate the actual
functions at progressively finer resolution; broad views preserve sampled extrema.
Accepted edits and observations refresh this output while preserving its viewport.
Previous revisions can be overlaid in dashed grey. Broad views use continuous
filled extrema bands, not a comb of vertical strokes. Native Plotly pointer events
are contained inside the output so dragging cannot open Field's editor.
Historical observations get a separate viewer; the main overview always follows HEAD.
Changed-duration edits omit incompatible comparisons rather than failing after publication.
PNG observations and numeric
checks still go to AI4 as before. See `dev_docs/SIGNAL_VIEWER.md` for the API and limits.

Verified JVM tests: SignalViewerTest (3), AudioVisualizerTest (4), FunctionStudyTest
(5). Browser checks cover pointer-anchored zoom, shift-wheel pan, bounds, finer
sampling, late-response rejection, real mouse drag/wheel, pointer cancellation,
and interval preservation when the output is replaced. Both the saved demo on
9788 and the user's live document on 9092 passed Plotly browser/gesture checks.
The user's current r9-a9a72d691580 stayed current while observing historical r2;
its source hash was unchanged. A scratch edit from 60 to 90 seconds also refreshed
overview without an Observe call, verifying publication across a domain change.


## Shorter interaction loop (22 September 2026)

The live user's export found String.file, but exact lookup cost 3.86 seconds and
66.66 seconds on successive sessions. Related-context discovery walked nested
worktrees, and property results omitted getters/KDoc. Exact symbol lookup now
avoids full-file scoring and optional context discovery; it returns the getter and
indexed KDoc. Related discovery excludes generated/dependency/nested checkout trees,
walks once, caches per-source results, and suppresses irrelevant guides.

The document now registers PatchFunctionStudy and ExportFunctionStudy. Small edits
use unique, non-overlapping exact replacements with the same revision/source-hash
guards and prepublication compile/checks as full edits. Both edit operations return
validation evidence and a before/after preview, which satisfies AI4's standalone
study compilation requirement. Dirty inspection includes live source. Export uses
the checked cached composition and Field's document-file resolver, stages and
verifies CSVs, and reports exact paths, rows, duration and checksums.

AI4 records model-request start/end, per-tool duration, and one terminal completed,
failed or cancelled event. Wrapped cancellation is rethrown and does not trigger
streaming fallback. Hard process termination still cannot emit a terminal event.
The chat replaces its current waiting/running status, wraps ordinary completions,
and retains failures and actual image content.

Verification: 14 focused JVM tests passed (including three lifecycle outcomes),
with the tree-write guard passing. Built under /tmp/field4-ai4-loop-build to avoid
replacing classes used by the live user JVM. Browser and real in-document tool
verification returned String.file with getter/KDoc in 0.213 seconds cold and
0.014 seconds warm. A patch with compilation/checks/preview took 1.284 seconds.
Two real gpt-6-astra conversations used only Inspect -> Patch -> Export, taking
16.269 and 15.506 seconds in the bridge logs; of the 15.506, 13.402 seconds was
model requests and 1.541 seconds was tools. For scale, the seven turns of the
original recorded run, on the earlier and more repetitive loop, took roughly 52
to 286 seconds each including provider backoff. That is a different fixture as
well as a different loop -- the replay edits a 60-second study, the recorded run
an hour-long one -- so it is not a controlled comparison and should not be
quoted as a speedup. See
[the saved replay](agents/demos/function_conversation_example/conversations/08-loop-improvements.md)
and its adjacent measurements, image and tool-I/O logs.

The user confirmed the earlier missing FunctionStudy.class was an overlapping
rebuild. It was not a generated-code failure; this pass leaves the development
runtime isolation architecture unchanged.
