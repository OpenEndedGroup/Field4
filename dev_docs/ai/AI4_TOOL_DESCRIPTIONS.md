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

- **Baseline (`bb5624f3c`): 0 of 52 scored core tools met all four.** Failure modes and examples were the
  commonest gaps.
- Tools still below the bar are listed in `NOT_YET_REWRITTEN`, a shrink-only register. A listed tool
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

## Before / after, live

Population: the 6 held-out tasks (stop-ticker, wire-two, fix-runtime-error, draw-sun, bars-visible,
bars-sorted-red), **3 repeats per setup** (18 task runs each), openai gpt-6-astra, lessons off, max 24
rounds, 2026-10-02, port 9621. Only the descriptions differ between setups: "base" is `Tools.kt` and
`FieldGraphTools.kt` checked out from `bb5624f3c` on top of the cached-token accounting. Uncached input =
`inputTokens - cachedInputTokens` (OpenAI `input_tokens_details.cached_tokens`, newly reported by
`AgentEvalReport`). Numbers are in `dev_docs/ai/evidence/ai4_tool_descriptions_2026-10-02.json`.

| setup | pass | rounds (rep 1 / 2 / 3) | input tokens | uncached input | misuse | CaptureRenderWindow calls |
| --- | --- | --- | --- | --- | --- | --- |
| base | 18/18 | 21 / 20 / 20 | 222k / 215k / 214k | 31.0k / 27.9k / 26.7k | 1 / 1 / 0 | 1 / 1 / 0 |
| rewrite v1 | 18/18 | 22 / 23 / 24 | 252k / 261k / 271k | 31.8k / 27.3k / 27.4k | 0 / 0 / 0 | 0 / 0 / 0 |
| **rewrite v2 (committed)** | 18/18 | **16 / 17 / 15** | **197k / 206k / 187k** | 30.7k / 26.0k / 25.3k | 0 / 0 / 0 | 0 / 0 / 0 |

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

## Re-running it

```bash
I=stop-ticker,wire-two,fix-runtime-error,draw-sun,bars-visible,bars-sorted-red
C="agent-eval --mode live --ids $I --lessons off --max-rounds 24"
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9621 --timeout 3600 \
  --command "$C" --command "$C" --command "$C" > after.json
```

Each `data` object carries `rounds`, `inputTokens`, `cachedInputTokens`, `misuseCalls` (omitted when
0) and per-task `toolCalls`. For a "base", check out the two description files from the base commit,
rerun, and restore them. The backdoor rebuilds when source changed. Three repeats of six tasks take
about 7 minutes.

## Next

- Rewrite the 37 tools in `NOT_YET_REWRITTEN`. The held-out tasks do not exercise them (only
  `ListContents` and `CodeUseQuery` appeared, once each in the base runs), so these held-out tasks
  cannot measure them; they need tasks that use them.
- Anthropic reports cache reads outside `input_tokens`, so `cachedInputTokens` is null on that
  bridge. Uncached cost there would need its own definition.
