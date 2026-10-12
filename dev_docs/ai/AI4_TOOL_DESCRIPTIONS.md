# AI4 tool descriptions: a rubric, rewrites, and a live before/after

On 2026-09-30 one rewritten `CompileKotlinRequest` description took AI4's held-out rounds from 30/31
to 21/22, and lessons added nothing on top ([AI4_SEES_ITS_OUTPUT.md](AI4_SEES_ITS_OUTPUT.md),
"Held out"). This page applies that finding to every core tool.

## The rubric and its guard

`ToolDescriptionRubricTest` (`src/jvmTest/kotlin/afield/ai/core/`) registers the built-in tool set
(`registerBuiltInAgentTools`) and reads each core request class's `@JsonClassDescription` and
parameter descriptions through `ToolRegistry.coreToolClasses()`, which ignores availability gates.
It asks four questions, each matched by a phrase class:

| question | where it must appear | matched by (roughly) |
| --- | --- | --- |
| returns: what comes back | class description | returns, answers, reports, lists, attaches |
| when: when to use it, or what to use instead | class description | use it when/for, instead of, prefer, only when |
| fails: a failure mode or refusal | class or parameter descriptions | fails, error, refuses, missing, cannot |
| example: an example argument | class or parameter descriptions | `e.g.`, for example, a backticked literal |

All four must hold, and the class description must be at least 80 characters. `Commentary` is
exempt. The scorecard is written to `build/ai4-tool-descriptions.tsv` on every run.

Since 2026-10-09 the test also scores a second population: the in-tree tools that reach AI4 through
`AgentToolExtensions` rather than core (the StackSL, stack.auto, RectSL and function studies,
`ReadLineNotes`, `WhyDoesThisResolve`, `ListGeometryFiles` / `InspectGeometryFile`). It calls their
installers, scores what they registered, and unregisters them again. The scorecard is
`build/ai4-extension-tool-descriptions.tsv`. Box-authored `aiTool(...)` tools belong to a document, so
no source enumeration can reach them.

- **Baseline (`bb5624f3c`): 0 of 52 scored core tools met all four.** Failure modes and examples were the
  commonest gaps.
- Tools still below the bar are listed in `NOT_YET_REWRITTEN`, a shrink-only register. In-tree
  extensions have their own register, `EXTENSIONS_NOT_YET_REWRITTEN`, opened with all 53 of them. A listed tool
  that passes fails the test until it is removed. A tool that is new, or not listed, must pass. So a
  new tool cannot ship a bare description.
- The phrase classes are a floor, not a judgement: a description can match all four and still be
  wrong. Each rewrite below was checked against its handler's actual result. For example,
  `GetBoxPropertyRequest` fails on a missing property (it does not return empty text), and
  `StopBoxRequest` answers `success=false` while the box is still running.

## What was rewritten

The 13 box-graph and execution tools (`FieldGraphTools.kt`), `CompileKotlinRequest` and
`CaptureRenderWindowRequest`: 15 tools, now at 4/4. The remaining 37 are in the register.

`CaptureRenderWindowRequest` was the one recurring misuse left after 09-30. Its description said
"Use it for any visual request", and the model called it first on `draw-sun`, a task with no render
window. It now says to use it only for boxes under a Renderer with a native window, that
`display(...)` output belongs to `ViewBoxOutputRequest`, and that a box with no window fails and
should not be retried. `ViewBoxOutputRequest` says the same from the other side.

## Before / after, live (2026-10-02, old harness)

These rows were measured before main's context pipeline and model default changed; compare them with
each other, not with the 2026-10-09 rows below. Population: the 6 held-out tasks (stop-ticker, wire-two, fix-runtime-error, draw-sun, bars-visible,
bars-sorted-red), **3 repeats per setup** (18 task runs each), openai gpt-6-astra, lessons off, max 24
rounds, 2026-10-02, port 9621. Only the descriptions differ between setups: "base" is `Tools.kt` and
`FieldGraphTools.kt` checked out from `bb5624f3c` on top of the cached-token accounting. Uncached input =
`inputTokens - cachedInputTokens` (OpenAI `input_tokens_details.cached_tokens`). The runs used this
branch's own field, `cachedInputTokens`; since the merge of `claude/ai4-chat-field-plan-164d81` the same
number is reported as `cacheReadTokens` (plus `cacheWriteTokens` for Anthropic). Numbers are in `dev_docs/ai/evidence/ai4_tool_descriptions_2026-10-02.json`.

| setup | pass | rounds (rep 1 / 2 / 3) | input tokens | uncached input | misuse | CaptureRenderWindow calls |
| --- | --- | --- | --- | --- | --- | --- |
| base | 18/18 | 21 / 20 / 20 | 222k / 215k / 214k | 31.0k / 27.9k / 26.7k | 1 / 1 / 0 | 1 / 1 / 0 |
| rewrite v1 | 18/18 | 22 / 23 / 24 | 252k / 261k / 271k | 31.8k / 27.3k / 27.4k | 0 / 0 / 0 | 0 / 0 / 0 |
| **rewrite v2 (committed)** | 18/18 | **16 / 17 / 15** | **197k / 206k / 187k** | 30.7k / 26.0k / 25.3k | 0 / 0 / 0 | 0 / 0 / 0 |
| v2, after merging `claude/ai4-chat-field-plan-164d81` | 18/18 | 17 / 17 / 17 | 199k / 200k / 199k | 33.6k / 29.6k / 29.3k | 0 / 0 / 0 | 0 / 0 / 0 |

Per task, averaged: base 3.4 rounds, 36.1k input, 4.75k uncached; v2 2.7 rounds, 32.8k input,
4.55k uncached.

- **v2 cuts rounds by about a fifth** (20.3 → 16.0 per run) and total input by 9%. The misuse is gone
  in all three repeats.
- **Uncached tokens barely move** (28.5k → 27.3k per run, inside the spread). The longer descriptions
  sit in the cached prefix, so they cost little; the saving is in the rounds, which are mostly cached
  too. Judged by uncached tokens, the rewrite is roughly cost-neutral and buys fewer rounds and no misuse.
- **v1 made rounds worse (22–24).** `ListBoxesRequest` said "Use it first", and the model obeyed:
  every task opened with ListBoxes, even `stop-ticker`, which names its box. `GetBoxPropertyRequest`
  said "Use it before editing a box you have not read". v2 says when each is *needed* instead, and
  `stop-ticker` dropped to one round (StopBox alone; its description now says `success=true` means
  stopped). **A "when to use it" clause is an instruction the model follows literally; write it as a
  condition, never as an order of operations.**

The last row is a different harness, so compare it with v2, not with base. The merged branch sends
the stable instructions once per conversation and defers rarely used tools behind
`DescribeToolsRequest`. Rounds and misuse hold (17 per run, none). Uncached input rose by about 3.5k
per run (27.3k → 30.8k); that is the merged context pipeline, not the descriptions, which did not
change between the two rows. It is not explained here.

## Main vs branch, live (2026-10-09)

After merging main (73 scored core tools by then), the 20
core tools new on main got rubric descriptions, as did `EditFileRequest` and `ListContentsRequest`.
Of 73 scored core tools, 38 pass; `NOT_YET_REWRITTEN` holds 35 (down from 37).

Population: the same 6 held-out tasks, 3 repeats per setup (18 task runs each), lessons off, max 24
rounds, port 9641. The model is **openai gpt-6.1-sol**, main's default by then, so these rows do not
compare with the 10-02 ones. "main" is the nine description files (`FieldGraphTools.kt`,
`GeometryReviewTools.kt`, `StudyBuildTools.kt`, `StateTools.kt`, `Tools.kt`, `WorkspaceSearch.kt`,
`ToolEvidence.kt`, `ToolProgramTools.kt`, `ToolMeta.kt`) checked out from main at `e555c0a2c`.
"branch" is this branch. The harness and the held-out tasks were unchanged on main. Uncached input
is `inputTokens - cacheReadTokens`. Numbers are in
`dev_docs/ai/evidence/ai4_tool_descriptions_2026-10-09.json`.

| setup | pass | rounds (rep 1 / 2 / 3) | input tokens | uncached input | misuse | CaptureRenderWindow calls |
| --- | --- | --- | --- | --- | --- | --- |
| main | 18/18 | 22 / 22 / 23 | 268k / 275k / 282k | 56.7k / 34.0k / 38.7k | 0 / 0 / 0 | 0 / 0 / 0 |
| **branch** | 18/18 | **20 / 19 / 20** | 286k / 273k / 284k | 60.7k / 26.8k / 31.9k | 0 / 0 / 0 | 0 / 0 / 0 |

- **Rounds fall by about an eighth** (22.3 → 19.7 per run), from two tasks. On `stop-ticker` the branch
  needed 1 round in every repeat and main needed 2: main's model confirmed with `ListBoxes` after
  `StopBox`, and the branch's `StopBox` description says `success=true` means stopped. On
  `bars-visible` the branch needed 4 rounds and main 5–6: in every repeat the branch read the code and patched it, while main looked at (and twice ran) the box before the edit.
- **Total input does not fall** (275k → 281k per run, inside the spread), because the descriptions are
  longer. Uncached input is lower on the branch (43.1k → 39.8k), but the first repeat of each setup
  ran with a cold cache (57k and 61k), so the spread is too wide to call that a saving.
- With the newer model neither setup misused `CaptureRenderWindowRequest`; the 10-02 misuse no
  longer separates them.

## Re-running it

```bash
I=stop-ticker,wire-two,fix-runtime-error,draw-sun,bars-visible,bars-sorted-red
C="agent-eval --mode live --ids $I --lessons off --max-rounds 24"
AFIELD_JDWP_PORT=off uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9641 --timeout 3600 \
  --command "$C" --command "$C" --command "$C" > after.json
```

Each `data` object carries `rounds`, `inputTokens`, `cacheReadTokens`, `misuseCalls` (omitted when
0) and per-task `toolCalls`. For a "base", check out the two description files from the base commit,
rerun, and restore them. The backdoor rebuilds when source changed. Three repeats of six tasks take
about 7 minutes.

## Next

- Rewrite the 35 tools in `NOT_YET_REWRITTEN`, and the 53 in `EXTENSIONS_NOT_YET_REWRITTEN`. The held-out tasks do not exercise them (only
  `ListContents` and `CodeUseQuery` appeared, once each in the base runs), so these held-out tasks
  cannot measure them; they need tasks that use them.
- Anthropic reports cache reads separately from `input_tokens`, so on that bridge uncached input is
  `inputTokens` itself, not the difference.
