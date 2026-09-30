# Merge Profiling Timeline into Simulation time and profiling

- **Handoff:** `d6e2c53e-3fb2-46eb-8e0f-17a42583262d` (crosscutting, blocking: notes on the page wait for this)
- **Filed from:** https://openendedgroup.com/field4/profiling_timeline/; also affects simulation_clock.md
- **Filed by:** agent:claude, 2026-10-01 00:34
- **Close with:** `python tools/docs_notes.py handoff-close d6e2c53e "what happened"`

## The notes that led here

- `6627ae45` (public/docs/profiling_timeline.md:19): Merge with "Simulation Time"

## Ask

The note on `public/docs/profiling_timeline.md` ("## Try the demo") says: merge this page with "Simulation Time" — `public/docs/simulation_clock.md`, titled "Simulation time and profiling".

## Why this is more than a docs edit

Merging two pages means one of them goes away. That changes the navigation, and links, anchors, and the starting-point references to the removed page have to be redirected. This pass owns only `profiling_timeline.md`. `simulation_clock.md` is being revised by another agent right now.

## Evidence

- `public/docs/simulation_clock.md:1` — its title already covers profiling ("Simulation time and profiling").
- `public/docs/profiling_timeline.md:1` "Profiling Timeline"; the demo section is at line 19. It links the `profiling_timeline` starting point.
- There is already an open handoff to merge "Holding a Frame Budget" into Profiling Timeline (`dev_docs/handoffs/2026-09-30-decide-whether-to-merge-holding-a-frame-budget-into-profilin.md`). Both merges need to be decided together.

## Proposed approach

Make `simulation_clock.md` the survivor and add the timeline panel and its demo as a section there. Then redirect or remove `profiling_timeline.md`, fix the nav in `public/zensical.yml`, and repair inbound links and anchors (see the memory on renaming published pages). Alternative: keep both pages and cross-link them.

## Questions for the human

1. Which page survives, and under what title?
2. Does the frame-budget page fold in too?
3. Should the whole page be merged, or only the demo section the note is anchored to?

## What the page needs afterwards

Either delete it with a redirect, or cut it down to a pointer.
