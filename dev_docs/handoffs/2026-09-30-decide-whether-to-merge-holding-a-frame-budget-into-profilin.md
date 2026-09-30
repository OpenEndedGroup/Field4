# Decide whether to merge Holding a Frame Budget into Profiling Timeline

- **Handoff:** `8e31f300-0ea1-4636-822f-319a55252392` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/frame_budget/; also affects profiling_timeline.md
- **Filed by:** agent:claude, 2026-09-30 20:52
- **Close with:** `python tools/docs_notes.py handoff-close 8e31f300 "what happened"`

## The notes that led here

- `18abf4b8` (public/docs/frame_budget.md:1): Merge with Profiling?
  > Holding a frame budget

## What was asked

The note on the title of `public/docs/frame_budget.md` (line 1) reads: "Merge with Profiling?" The page it means is almost certainly `public/docs/profiling_timeline.md`, the only other profiling page in `public/docs/`.

## Why this is more than a docs edit

A merge touches two pages, the nav, several pages that link in, and tests that name `frame_budget.md` by path. The agent revising this page owns only `frame_budget.md`.

## Evidence

- Nav: `public/zensical.yml` lists `Profiling Timeline: profiling_timeline.md` directly followed by `Holding a Frame Budget: frame_budget.md`. The comment between them states why they were kept apart deliberately. The split is by audience: profiling_timeline is the panel's controls, and frame_budget covers what the numbers mean and what each of the three instruments (`CPUSpan`, a coroutine resume, a JFR sample) cannot see. The comment adds that "somebody whose piece is dropping frames needs this one, and may never need to open the other".
- Each page describes the split in its own opening: `frame_budget.md` lines 8–12, and `profiling_timeline.md` line 12 and line 282.
- Sizes: frame_budget.md is 460 lines with 10 sections. profiling_timeline.md is 294 lines, with a demo, 2 runner captures driven by `dev_docs/agents/transcripts/profiling_timeline.yaml`, and 8 sections. Together they would make a page of about 750 lines.
- Inbound links to `frame_budget.md`: `profiling_timeline.md`, `simulation_clock.md`, `flow_grain.md`, `staging.md` (it describes the two as "the pair"), `aperture_playground.md`, `building_and_testing.md`, and `animation_time_model.md` (twice).
- Tests and tools that name the file by path:
  - `src/jvmTest/kotlin/afield/docs/CitationContractTest.kt`: the `"public/docs/frame_budget.md" to 21` citation count.
  - `DerivedArithmeticGuard.kt` and `DerivedArithmeticContractTest.kt`: these name it in comments, as a history.
  - `tools/docs_voice/number_restated.py`: an exemption keyed on this page's `63.9` / `3,002.6` restatement.
  - `dev_docs/derived_arithmetic_sweep.md` and `dev_docs/citation_contracts.md`: these name it as history.
- Memory `renaming-a-published-doc-page`: regenerate, never sed. A section rename breaks `](#anchors)` and no guard catches it.

## Options

1. **Keep two pages, tighten the pairing (recommended if the split still holds).** Leave both pages. Make each opening paragraph a one-line pointer to the other. Consider nesting both under one nav heading, "Profiling", so they read as one topic with two pages. This costs little and breaks nothing.
2. **Full merge into one "Profiling" page.** Put the panel controls from profiling_timeline first, then the measurement model from frame_budget as the second half. Keep `frame_budget.md` as a redirect stub, or rewrite every inbound link and anchor. Update the `CitationContractTest` count, the `number_restated.py` exemption, the nav comment, and the transcript/captures page path. The result is a very long page, and it reverses a split that someone recorded on purpose.
3. **Partial merge.** Move the "Where the three disagree" and "What none of this sees" material into profiling_timeline's "What the numbers mean" section, and keep frame_budget as the "it stutters" diagnostic route only.

## Questions for the human

- Is the audience split in the zensical.yml comment still the one you want? If yes, choose option 1.
- If you merge, which filename survives, and should the other stay as a redirect stub?
- Should "Holding a frame budget", the diagnostic route from "it stutters" to a source line, stay a page of its own even inside a merged section?

## What this page needs afterwards

- Option 1: a small edit to the second paragraph of `frame_budget.md`, plus the nav change.
- Options 2 and 3: the page is rewritten or retired, and its citation count and derived-arithmetic markers move with the text.
