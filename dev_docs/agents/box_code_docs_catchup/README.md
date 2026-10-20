# Catch up the newly landed documentation code

The original 577-group sweep used a frozen inventory. This pass reviews the 15 pages added since
that inventory, the subsequently added notation example, and their related authored transcripts.
`scope.json` fixes the baseline and paths; it includes the water-bug starting points and the
canvas-flight capture fixture as well as the eight geometry/statistics starting points.

## Changes

- The watching-creatures example uses a run-owned `go` coroutine and `evolveSuspend` on
  `Dispatchers.Default`. An atomic reference publishes completed champions to the drawing loop.
  Stop/relaunch cancels the worker and worker exceptions reach the editor.
- Both live water-bug starting points publish one complete `EvolutionUpdate` atomically, so the
  caption and drawing use the same generation. Their old daemon threads and end-hook scaffolding
  are removed.
- Fixed-decimal captions/tables use `.fN`. Scientific notation and deliberately padded headings
  retain their explicit formatting.
- FLine styling uses typed properties. Intermediate ridge points, junctions and tracks are named;
  a redundant array of equal weights is removed in favour of the fitter's equal-weight default.
- Collection literals replace incidental `listOf` calls. Unnecessary list mutation in the
  canvas-flight fixture becomes concatenation. Comments correct launch shortcuts and sampling units.
- Field's actual import/ktfmt formatter ran on 114 spans. The verifier carries fold markers and
  preserves raw strings exactly, including embedded JS and HTML. All executable tokens match the
  intentionally edited source, apart from formatter changes to commas and semicolons. There are
  no GLSL bodies in this scope.

## Behavioral evidence

`runtime-verification.json` records the exact source hashes and ten real checks. The published
watching-creatures body and both starting-point bodies ran through Field's actual compiler and
execution lifecycle. In private test copies, generations are raised to 100000 and measured cycles
reduced to 1 to keep real evolution running long enough to test Stop. Each produces a champion,
releases its jobs, stops publishing after Stop, and relaunches into a distinct snapshot holder.
An invalid population exercises actual worker-to-editor error reporting.

Before/after executions of the contour-tree, geometry-chain and Morse–Smale table examples match
their native geometry/raster/table proofs. The actual notation elements, score and audit bodies
also execute: mark, frame, grouping, edit and intersection checks all report `works`.

The detailed command receipts and failed harness diagnostics remain under
`build/box-code-revision/docs-catchup/`. Harness repairs addressed fragment history, CLI syntax,
HTML escaping and unchanged-macro handling; they did not waive failures in the examples.

## Fresh captures

`capture-verification.json` records seven successful forced recaptures of the evolving-creatures
page: five images and two recordings, all inspected. The normal runner reported zero failures.
The capture harness uses `always` because incremental mode can reuse checked-in assets without
a cache entry. Five assets reproduced exactly; the evolved-champion and live-worker images changed.

## Delivery

Generated starting points must be refreshed from the authored transcripts. The final capture and
landing state is in `checkpoint.json`. Full integration guards belong to the lander.

## Merge reconciliation

`merge-verification.json` records the landing-bounce repair. Main’s new CMU dance examples,
inline film and solid-tube renderer are preserved and formatted. The creature page now includes
a walking film: all eight captures (five images and three recordings) regenerated successfully.
The binary conflict was resolved by fresh capture. Original behavioral receipts retain their
original source hashes; the current source inventory records both pre-merge and reconciled hashes.
