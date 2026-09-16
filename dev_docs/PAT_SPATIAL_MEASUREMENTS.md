# `patSpatial` — measurements, and the offline stand-in that was rejected

Displaced from `public/docs/pat_spatial.md` by the sixteenth documentation-voice-pass firing
(2026-09-16). The page keeps the findings a user acts on; the method, the raw percentiles and the
design record live here.

**None of the figures below is asserted.** `PatSpatialBudgetProbe` and `PatSpatialEngineSmoke`
print them; the smoke asserts the *shape* (a median far inside budget, a tail far outside) and
`PatSpatialRoom.busyMsTotal` against elapsed time, not any of these numbers. Treat every figure as
a last observation on one machine, not as a contract.

Note also that `PatSpatial.kt`'s file KDoc carries the same table and the same
`1274 of 1500` figure, and that KDoc **publishes** to `public/docs/reference/patterns.md`. So these
numbers are still on the site in the reference. See "Open" below.

## Per-block cost against budget

`PatSpatialBudgetProbe`: four sources, 128 frames, 44.1 kHz, a 2.902 ms budget, 8000 blocks with the
first tenth dropped as warmup. Two runs on the same machine, the second while other work was loading
it.

| | idle | under load |
| --- | --- | --- |
| median block | 0.120 ms | 0.117 ms |
| p99 | 1.170 ms | 2.951 ms |
| p99.9 | 2.405 ms | 9.522 ms |
| worst block | 12.705 ms | **36.631 ms** |
| blocks over budget | 3 of 7200 | 74 of 7200 |

The median is flat across the pair — four wasm engines and eight Steam Audio effects are about 4% of
a block either way. The tail moves by a factor of three. That pair is what put the render off the
audio thread and into `PatSpatialRoom` behind `PatSpatialRing`.

## The scheduling run

On a ten-core machine at load average 33 — twenty-odd agent sessions and a Gradle daemon at 180%
CPU — `PatSpatialEngineSmoke` recorded the render thread producing **1274 of the 1500 blocks** a
four-second window needed, while being **busy only 1399 ms of those 4000**. Per-block dough cost came
out 4.5x its idle figure in the same run.

A thread idle two thirds of the time that still misses its deadline is not a thread that needs faster
code. This is why the ring defaults to 341 ms rather than the ~40 ms the arithmetic alone would
justify: it is sized for the scheduler's stalls, not for this code's.

## Why there is no offline stand-in for the spatialised output

The arithmetic of the whole chain **is** faster than realtime — `PatSpatialBudgetProbe` drives dough
engines and Steam Audio effects in a tight loop, with no thread and no ring — so a docs producer
could have re-implemented the path end to end and published something that sounded right.

It was rejected. What it would have published is a figure of the re-implementation, on a page whose
subject is the thread and the ring that re-implementation leaves out, and it would have needed the
uncommitted `libphonon` anyway. The two clips the page does publish make a smaller claim rendered by
the shipping code, which is the only kind of clip `DocsAudioProvenance` can check.

## What "realtime only" actually means, and the rate it runs at instead

Added 2026-09-16 with the head-tracking work, because the phrase turned out to mislead in a way that
cost a passing test.

`PatSpatialRoom.renderLoop` has **no wall-clock pacing at all**. Its only brake is
`if (ring.fill >= targetFillFrames) Thread.sleep(2)`. So "realtime" on this path means *paced by
whatever drains the ring* — normally OpenAL. A consumer that pulls as fast as blocks appear, which is
what a smoke capture and a file render both do, lets the shipping code free-run:

| | audio seconds per wall second |
| --- | --- |
| `HeadTrackedSpatialSmokeTest`, one source, eight captures | 5.5x – 18.8x |
| `HeadTrackedPatternDemo`, three sources, first movement | 9.7x |
| `HeadTrackedPatternDemo`, three sources, movements 2–4 | 35x – 42x |

The low figure in each row is the one that paid for the JIT warmup. Both tests print the factor per
capture; neither asserts it, for the reason the top of this file gives.

This sharpens the section above rather than contradicting it: it is not only the *arithmetic* that
outruns realtime, it is the whole path including the thread and the ring. What the ring still buys
is the decoupling under an audio device, which is a different claim and the one the budget table
measures.

Two consequences, both now in the code:

- **Nothing on this path may be driven from `System.nanoTime()` and expected to line up with the
  audio.** `HeadTrackedSpatialSmokeTest`'s case 6 originally required a pose count "over 1.6 s at
  90 Hz" and failed as soon as a capture ran fast — the tracker was on the wall clock, the capture
  was on no clock, and their ratio is a property of the machine. `VirtualHead` therefore takes a
  `clock`.
- **A file render should drive that clock from `room.framesRendered`**, not from frames pulled. The
  ring bounds the render thread's lead to its own depth (0.37 s at the default 16384 frames), so
  `framesRendered` tracks the pull closely and is the timeline of the blocks actually being
  spatialised; the pulling end trails it. Draining the ring at a movement boundary matters for the
  same reason — without it the first 0.37 s carries the previous movement's pose, which showed up as
  a full turn finishing at 350 degrees instead of 360.

## Open

- **The same measurement mass is published twice.** `PatSpatial.kt`'s file KDoc (around lines
  104–146) repeats this table, the `1274 of 1500` figure, and the sentence "Read the two columns
  together, because the pair is the finding" — which is the TED-slide heading shape Marc named on
  2026-09-13. Fixing it means editing KDoc and regenerating `public/docs/reference/patterns.md`,
  which needs a Gradle run; the firing that displaced this material had 9 GiB of disk and could not
  build. Do this when the machine has room.
- Nothing pins the six spatial tags' consumption other than `synth.pat-spatial.sound`'s event-map
  assertions, which run only with `./gradlew fetchSteamAudioNatives` done first.
