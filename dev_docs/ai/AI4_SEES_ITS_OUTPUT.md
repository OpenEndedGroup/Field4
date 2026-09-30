# AI4 sees its output, and learns from its evals (2026-09-30)

This extends AI4; it adds no harness. `ViewBoxOutputRequest` is one more graph tool in the shared
registry, so every AI4 chat path and every agent task has it. The picture reaches the model through
the `CarriesToolImages` path that both bridges already used for render-window captures. The lessons
are one more block in `buildDynamicInstructions`.

## Looking: `ViewBoxOutputRequest`

- **What it returns.** One box's output area, meaning the panels its `display(...)` calls fill,
  captured just now. The model gets a downscaled JPEG as a tool image. The JSON text carries the
  measured facts: size, `inkFraction` (the share of pixels that are not the background colour),
  the six dominant colours, and `path`, the full PNG kept under
  `~/.afield/logs/ai4_box_captures/` (`AFIELD_AI4_CAPTURE_DIR` overrides; the newest 300 are kept).
  A capture that is almost all background says `BLANK` in its message. A box with no panel is
  refused before any browser opens: "println text is not a panel".
- **Where the pixels come from.** `BoxOutputCapture` (`ai/agent/BoxOutputCapture.kt`) opens its
  own context in the server's single Chromium (`ensureSharedPlaywrightBrowser`, split out of
  `ensurePlaywrightPageOnce`), loads the document, and uses the docs capture's own box framing
  (`requestBoxScreenshot`, `ExpectedOutputs.PANEL`). It does not use the global backdoor/docs page,
  because navigating that page to another document would disturb whatever capture sequence was
  using it.
- **Run first, look later works.** A page that attaches after a box ran asks for
  `replay-output-areas` and gets every retained panel (`OutputAreaReplay`). This was checked
  headless at base: a box ran with no browser, then `screenshot-box` returned the real 672×356
  panel. The "48×48 placeholder" in older notes (MARF_DEMO, dmx_lighting) came from before the
  replay existed. The backdoor's "run before Playwright attached" warning no longer says display
  output is missing.
- **Lifecycle.** One page per document, keyed by `SessionInformation.filename`, at most four.
  A page is released when:
  - `closeHeadlessDocument` runs. It releases the page *before* deciding whether the document still
    has clients, and waits for that page's socket to detach. Otherwise the capture page's own
    websocket would keep every task document alive: `closeHeadlessDocument` skips cleanup while
    `info.sessions` is non-empty.
  - it has been idle for 120 s, **unless** it is the only client of a scratch document. Detaching
    the last client retires a scratch document (`cleanupDetachedSession`), and a task can easily
    spend two minutes between looks.

  Measured: every live eval below reports `documentsBefore == documentsAfter` (0 → 0) and
  `capturePagesOpenAfter == 0`.

## Grading pixels

- **Checks.** `output-picture:B` passes when a capture of B succeeds and ≥ 1% of it is ink.
  `output-colour:B:#rrggbb:share` passes when at least that share of the capture is within 48 per
  channel of the colour. The checks take their own capture after the turn (one per box per verdict),
  so they do not depend on whether the model looked.
- **Tasks** (`InFieldAgentEvals`):

  | id | what it tests |
  | --- | --- |
  | `swatch-colour` | Name the colour of a rectangle. The stylesheet says `!important` red, a more specific rule says green, the inline style says purple, and a script then sets an inline `!important` blue. The source invites "red"; the picture is blue |
  | `draw-sun` | A 300×200 SVG of a yellow sun on a dark-blue sky, graded by the share of each colour |
  | `bars-visible` | Five bars with percent heights inside a parent of auto height. Each computes to 0 px, so the panel is an empty white strip |

  Each task has a scripted solution that looks. The fake-host tests carry recorded pictures, and the
  counterfactuals fail: looking without fixing leaves `bars-visible` at a blue share of 0.000, and
  answering "red" fails `swatch-colour`.
- **Does looking matter?** The `blind` flag withholds the tool from the same tasks (population: the
  3 visual tasks, 2 runs each, lessons off, tools fixed):

  | task | with sight (5 runs: A, A2, B, C, D) | blind (2 runs) |
  | --- | --- | --- |
  | swatch-colour | 5/5 pass; ViewBoxOutput was the first call every time; 3–8 rounds, 33–76k input tokens | **1/2 pass**; 13 and 14 rounds; 2,467k and 416k input tokens. One run read `outputareas2.js` through `EvaluateKotlinExpression` and still answered red |
  | draw-sun | 5/5 | 2/2 (the model could draw it without looking; the picture checks grade the result either way) |
  | bars-visible | 5/5 | 2/2 (it read the `%` in the code and fixed it blind) |

  Sight decides one of the three tasks. For the other two it is verification. The blind runs cost
  3.07M input tokens, most of this night's spend.

## Learning: `AgentLessons`

- `AgentLessons.isMisuse` counts a failed tool call as misuse unless it is the box's own error. A
  Run or Launch failure that reached a `stage` is the code's error, and so is a compile that
  produced diagnostics; those are what the task is about. Misuse that happens twice (in one task or
  across tasks) becomes a lesson. A lesson names the tool, what the tool answered (uuids replaced by
  `<uid>`, the document's ref by `<document>`), and the argument that changed on the next call of
  the same tool that worked. Identical repeats are called out. No model is asked to summarise,
  so every lesson traces back to its calls.
- The file is `src/jvmMain/resources/ai4/agent_lessons.md`. It has a `version:`, a provenance line,
  `## Curated` for people (never rewritten) and `## Distilled` (rewritten by `agent-eval --distil`,
  or by `distilFrom: <saved report>` without running anything). Review changes with `git diff`.
- `buildDynamicInstructions` appends the file's bullets, curated first, whenever the graph tools
  are wired, capped at 2400 chars. `AFIELD_AI4_LESSONS=off` is the kill switch; `lessons: on|off`
  pins it per task or per eval.

## Before / after, live

Population: the 10 eval tasks, one run per condition, openai gpt-6-astra, max 24 rounds,
2026-09-30. The lessons in B were distilled from A itself, so B is an in-sample measurement. The
numbers are pinned by `AgentSeesItsOutputTest.liveEvidenceSummaryHoldsTheNumbersTheDocsQuote`
against `dev_docs/ai/evidence/ai4_sees_its_output_2026-09-30.json`.

| run | condition | pass | rounds | input tokens | misuse calls | CompileKotlin calls |
| --- | --- | --- | --- | --- | --- | --- |
| A | lessons off, base tools | 10/10 | 63 | 620,187 | 13 | 20 |
| A2 | same, repeated | 10/10 | 61 | 593,022 | 12 | 19 |
| B | lessons **on** (v1, from A), base tools | 10/10 | 50 | 520,131 | **0** | 9 |
| C | lessons off, **CompileKotlin tool fixed** | 10/10 | **33** | **348,537** | 2 | **0** |
| D | lessons on (v2, from C), tool fixed | 10/10 | 31 | 332,607 | 1 | 0 |

Per task, as rounds / input tokens / misuse:

| task | A | A2 | B | C | D |
| --- | --- | --- | --- | --- | --- |
| primes | 7 / 66k / 3 | 6 / 58k / 2 | 4 / 41k / 0 | 2 / 23k / 0 | 2 / 24k / 0 |
| fix-compile-error | 6 / 56k / 1 | 6 / 57k / 1 | 4 / 41k / 0 | 2 / 23k / 0 | 2 / 24k / 0 |
| parent-variable | 10 / 93k / 1 | 10 / 92k / 2 | 9 / 87k / 0 | 5 / 48k / 0 | 5 / 49k / 0 |
| html-table | 6 / 58k / 1 | 8 / 80k / 2 | 6 / 60k / 0 | 4 / 40k / 1 | 3 / 32k / 0 |
| stop-ticker | 2 / 23k / 0 | 2 / 23k / 0 | 2 / 24k / 0 | 2 / 23k / 0 | 2 / 24k / 0 |
| wire-two | 3 / 31k / 0 | 3 / 31k / 0 | 3 / 32k / 0 | 3 / 31k / 0 | 3 / 32k / 0 |
| fix-runtime-error | 7 / 80k / 1 | 6 / 57k / 1 | 5 / 59k / 0 | 3 / 34k / 0 | 2 / 24k / 0 |
| swatch-colour | 8 / 76k / 3 | 7 / 68k / 2 | 5 / 52k / 0 | 3 / 33k / 0 | 3 / 33k / 0 |
| draw-sun | 7 / 67k / 2 | 7 / 67k / 2 | 6 / 61k / 0 | 4 / 41k / 1 | 4 / 41k / 1 |
| bars-visible | 7 / 69k / 1 | 6 / 60k / 0 | 6 / 62k / 0 | 5 / 51k / 0 | 5 / 51k / 0 |

**Which lever moved the number.**

- The lessons (A/A2 → B) removed the misuse: 13 and 12 failing calls became 0. They did not remove
  the habit: the model still compiled before running, 9 times, and rounds fell only from 61–63
  to 50.
- The tool description (→ C) moved the most. `CompileKotlinRequest` now says that
  RunBox/LaunchBox compile by themselves, and its `file` parameter is described. After that the
  model never called CompileKotlin (20 calls → 0), and rounds and input tokens fell by about 45%.
- The resolver fix that shipped with the description (`resolveBackdoorFileRef` accepts
  `afield://<uid>/code`, `afield://agent-task/<uid>/code`, `afield://scratch/...`, a name or a
  uid) was **never exercised live**, because nothing called the tool. It is pinned by a unit test
  and nothing more.
- D (lessons v2 against the fixed tools, one lesson left, about `CaptureRenderWindowRequest`)
  against C is within noise: 33 → 31 rounds, 2 → 1 misuse.

## Traps found tonight

- **The first distil called the right spelling wrong.** A headless task document is *named*
  `agent-task` (or `scratch`) but is referred to by its directory's uuid. Substituting the display
  name produced "`afield://<document>/<uid>/code` fails", which is the correct form reported as the
  failing one. `AgentTaskResult.documentRef` now carries the name as refs spell it, and
  normalisation uses that.
- **The lessons header comment contains the text "## Curated"**, so `substringBefore` cut the
  comment in half on the second distil. The header now ends at the first *line* that starts a field
  or a section. A test renders twice and checks that the header survives.
- **The model reaches for `CaptureRenderWindowRequest` first on "visual" tasks** with no render
  window (2 of 10 tasks even after the fix). Distilled lesson v2 names it. A description change
  like CompileKotlin's is the likely stronger lever and was not tried.
- **Rebuilding under a running Field.** `jvmTest` recompiles `jvmMain`, so the server was
  restarted after every test run before any live measurement.
