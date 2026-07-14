# Nightly worklog — 2026-07-14 / 2-oscillator-domain-guard

**Kind:** B (bug / ergonomics — robustness hardening)
**Branch:** `nightly/2026-07-14/2-oscillator-domain-guard` (base `127fc7d0`)
**Mission:** Close any unbounded-domain enumeration hazard left by commit `fd603986`
("oscillators default to infinite domains"), which flipped periodic constructors to
default `domain = Domain.ALL`. Any *public enumerator* that walks an implicit domain
without routing through `Domain.finiteSweep()` (or an explicit finite bound) will now
hang or OOM when handed a fresh `sin()`.

## The guard idiom (from fd603986)

- `Domain.finiteSweep()` (private, `Functions.kt:2296`): `_isEmpty` → pass through;
  `isFinite` → pass through; else → `plotSafe()`. For a fresh oscillator (`Domain.ALL`)
  this previews over `plotSafe()`'s default window, which resolves to
  `Domain.DEFAULT` = `UNIT` = `0.0..1.0`. So every implicit-domain enumeration of an
  infinite oscillator sweeps `[0,1]` — small, bounded, fast.
- `plotSafe()` (`Functions.kt:2277`): finite → keep; infinite → `default` (`UNIT`).

## Audit scope (what I checked, exhaustively)

Read the full `git show fd603986` diff first to learn exactly which sites were guarded.
Then audited `src/jvmMain/kotlin/afield/stdlib/Functions.kt` (7870 lines) for every place
that materialises samples/points/iteration from a domain span. Techniques: grepped
`finiteSweep`, `.length`, `domain.min`/`domain.max`, every `while` loop,
`generateSequence`/`.iterator()`, and every declaration with `sampleDomain: Domain = domain`.

### Implicit-domain enumerators (`sampleDomain: Domain = domain`) — ALL GUARDED
`toDoubleArray` (count/step overloads), `toVec2List`, `toFLine`, `sampleUniform`,
`sampleJittered`, `sampleRandom`, `sampleByDensity`, `sampleZeroCrossings`, `samplePeaks`,
`autoScale`, `zNormalize`, `whitenCdf` — each takes `finiteSweep()` on the sample domain
(directly, or via `sampleStatsRandom` which sweeps at `Functions.kt:6231`). Verified every
`sampleDomain: Domain = domain` site (14 of them) resolves through a `finiteSweep()` call.
The `ClosedRange<*>` sibling overloads are pure delegators to the `Domain` overloads and a
`ClosedRange` is inherently finite, so they cannot carry `Domain.ALL`.

### Iteration anchors / streams — ALL GUARDED
`FunctionCursor` default `x`, `Fn.cursor`, `Fn.stream(start,step)`, `Fn.streamWithX`,
`Fn.lowpassStream`, `Fn.highpassStream`, `Fn.integral` origin, `Fn.repeat`/`mirrorRepeat`/
`tile` origin+period — all default to `domain.finiteSweep().min` (and `.length` for `tile`).
The explicit-domain `Fn.stream(domain, step, includeEnd)` overload guards with
`if (!domain.length.isFinite()) return@sequence` (bails to empty on an infinite explicit
domain) — a valid finite bound; it requires the caller to pass a domain, so it is not a
default-arg hazard.

### Plotting — GUARDED via plotSafe
All `Fn.plot` / `plot(fn,...)` overloads funnel through `Fn.plotHtml`, whose sample loop
uses `plotDomain = (sampleDomain ?: domain).plotSafe()` (`Functions.kt:2346`).

### Domain→[0,1] shapers — GUARDED
`tween`, `spliceWindow`, `hannWindow`/`hammingWindow`/`blackmanWindow`/`gaussianWindow`
all take `domain.finiteSweep().alphaOf(x)`.

### Fixed-count / per-x closures — bounded by construction (no domain walk)
`convolve`, `correlate`, `movingAverage`, `blur`, `echo` build a `graphFn` whose eval
loops over a *fixed* `samples`/`repeats` count (not the receiver's domain), with
`!isFinite` early-returns where they touch a kernel/window length. `gateHold` while-loop is
bounded by a finite `lookBack`; `randomWalk` while-loop is bounded by a finite `x`.
`resample(source,...)` guards with `if (d._isEmpty || !d.isFinite) return source` — an
explicit finite bound (returns the source unchanged for an infinite-domain input).

### Private / non-public
`sampleXs` + `demoSeries` + `demo()` are `private`; `demo()` builds its own finite domain,
and `sampleXs` uses a fixed count (no hang). `FunctionGraphRules.kt:166` only reads
`fn.domain.min/max` for fingerprint *serialization*, not enumeration.

### Cross-file check
No `operator fun iterator`, `Fn.asSequence`, or `Domain.forEach/samples/step` enumeration
entry point exists elsewhere. Only `FunctionGraphRules.kt` references `.domain.min/max`
outside `Functions.kt`, and that is serialization only.

## Finding

**No unguarded public enumerator found. The surface `fd603986` guards is complete.**
This is the honest, valuable outcome: `fd603986` was exhaustive. Per the mission, I did
NOT manufacture a change. Instead I HARDENED the surface with a regression test so any
future edit that reintroduces an unbounded enumeration fails CI rather than hanging it.

## What I changed

- Added `src/jvmTest/kotlin/afield/stdlib/OscillatorDomainGuardTest.kt`: for each public
  oscillator/pulse builder (`sin`,`cos`,`saw`,`triangle`,`square`,`pulseTrain`,
  `impulseTrain`,`pulseBurst`) — first asserting each really defaults to an infinite domain
  (so the test proves something) — it calls every public enumerator with default args on a
  bounded worker thread and asserts each (a) completes within a short wall-clock timeout and
  (b) returns a finite, size-capped result. A regression that drops a `finiteSweep()` guard
  makes the step/`while`-based enumerators (`toDoubleArray(step)`, `sampleZeroCrossings`,
  `samplePeaks`) hang/OOM → caught by the timeout → test FAILS; and makes the start-anchored
  streams/`cursor` emit `NaN` from `x = -Infinity` → caught by the finiteness assertion.

## Verification

- `./gradlew compileKotlinJvm compileTestKotlinJvm` — BUILD SUCCESSFUL (no new warnings from
  the added test).
- `./gradlew jvmTest --tests '*OscillatorDomainGuardTest*'` — **green** (1 test, 0 failures,
  0.24s).
- **Red→green demonstration (proves the test bites):** temporarily neutered
  `finiteSweep()` to `else -> this` (reintroducing the exact hazard), recompiled, re-ran:
  the test went **RED** — `OutOfMemoryError` at `OscillatorDomainGuardTest.kt:108`
  (`fn.toDoubleArray(1e-3)`, whose step overload computes `count = floor(Inf/1e-3).toInt()`
  = `Int.MAX_VALUE` → ~17 GB array allocation), surfaced through the worker-thread guard as
  an `AssertionError` at line 89. Reverted `finiteSweep()` to `plotSafe()`; recompiled;
  test back to **green**. `Functions.kt` is byte-identical to `fd603986`'s state
  (`git diff` empty).
- Regression sweep (all green, no failures):
  - `OscillatorDomainGuardTest` — 1/1
  - `FunctionsGraphRewriteTest` — 17/17
  - `FunctionVarietyTest` — 4/4
  - `FnWaveshapeTransformTest` — 5/5

## Risks / unknowns

- Test only covers the *public* enumeration surface in `Functions.kt` (the mission scope).
  A future new enumerator added elsewhere would need its own coverage.
- The bounded-worker-thread timeout (10s/oscillator battery) is generous; a pathological
  slow-but-finite regression under that bound would still pass. The explicit size caps are
  the primary net; the timeout is the anti-hang backstop.

## Recommendation

**MERGE.** No production-code change was needed (audit found the enumeration surface
`fd603986` guards to be complete), so the risk is minimal: the only addition is a new
jvmTest. The test is a durable regression net — verified to fail red on the pre-guard code
path and green on the current code — that will catch any future edit which reintroduces an
unbounded-domain enumeration. It costs ~0.24s in the suite.
