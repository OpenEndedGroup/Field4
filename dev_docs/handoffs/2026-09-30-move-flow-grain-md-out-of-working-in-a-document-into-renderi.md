# Move flow_grain.md out of Working in a Document into Rendering & GPU

- **Handoff:** `7195659d-c391-4232-ad7d-2898ce9a1210` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/flow_grain/
- **Filed by:** agent:claude, 2026-09-30 20:51
- **Close with:** `python tools/docs_notes.py handoff-close 7195659d "what happened"`

## The notes that led here

- `b41dd426` (public/docs/flow_grain.md:1): This is not "Working in a Document" ? Wrong section. It's a graphics chain demo

## What was asked

The note on `public/docs/flow_grain.md:1` reads: "This is not 'Working in a Document'? Wrong
section. It's a graphics chain demo."

The page's text never mentions "Working in a Document". Its section comes from the nav in
`public/zensical.yml`, and that file is outside this partition. So the fix is a nav move, not
a page edit.

## Evidence

- `public/zensical.yml:65` lists `Flow Grain and Motion: flow_grain.md` under
  `Working in a Document`, between `simulation_clock.md` and `profiling_timeline.md`.
- The page is an afield.four render graph. It uses numbered stage boxes, a fluid storage
  helper, RTT with a motion attachment (`afield.four.targets.FrameHistory`), GLSL includes
  (`flow/grain.glsl`, `flow/strokes.glsl`, ...), `afield.four.flow.TileAtlasLayout`, and a
  `capture="native-window"` capture (`public/docs/flow_grain.md:17`). Nothing on the page is
  about working in a document.
- There is a precedent at `public/zensical.yml:210-216`. `aperture_playground.md` was moved
  out of "Working in a Document" into `Graphics, GPU & Headsets > Rendering & GPU`, and the
  comment gives the same reason ("nothing on it is about working in a document"). flow_grain
  probably landed in the same place for the same reason: both came in as agent-transcript
  starting points.
- Inbound links are by filename (`aiFeatures.md:455`, `profiling_timeline.md:289`,
  `agent_transcripts.md:1916`), so a nav move does not break any of them.

## Proposed approach

Move the entry to `Graphics, GPU & Headsets > Rendering & GPU`, directly after
`Aperture Playground` (`public/zensical.yml:216`). Add a one-line comment like the aperture
one: "a worked render graph (fluid, grain, motion RTT), moved out of Working in a Document
for the same reason as the page above." The filename stays the same.

Alternatives:
- Put it under `Chains Across Subsystems` (`public/zensical.yml:257`). The note says "graphics
  chain demo", but the pages in that section each carry one object across three or four
  subsystems and check each hop's output. flow_grain lives inside one subsystem (afield.four)
  and has no hop checks, so it would stretch that section's definition.
- Put it after `GPU State` / `Field Probes`, since it uses feedback state and profiling.

## Questions for the human

1. When you wrote "chain demo", did you mean the `Chains Across Subsystems` section, or just
   "a graphics demo" (Rendering & GPU)?
2. `profiling_timeline.md` and `frame_budget.md` stay in "Working in a Document". Is that
   still right once flow_grain leaves? (`profiling_timeline.md:287` has a section profiling
   flow_grain.)

## What the page needs afterwards

Nothing, unless it goes under Chains. In that case the opening paragraph should name the
hops the way the chain_* pages do.
