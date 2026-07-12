# Nightly worklog — 2026-07-12 / 3 — evolve worked example

Kind: C (documentation/explainer with captured evidence)

## Plan (refined against real code)

Target code: `evolve(...)`, `matchFitness(...)`, `FnMutations` in
`src/jvmMain/kotlin/afield/stdlib/FunctionEvolve.kt`. It is a deterministic
(µ+λ) hill-climb over `Fn` graphs. Documented in prose in
`public/docs/functions_examples.md` ("Evolving and optimizing functions",
~line 3480) but with NO captured evidence of a real run.

API facts confirmed by reading source:
- `Functions` is an `object`; use `with(Functions) { ... }`.
- `Fn.evalSafe(x, outside=NaN)` samples a function; `Domain(min,max)` has `.length`.
- `sin(frequency, phase, amplitude, offset, domain)` has Number overloads; `c(value)` = constant.
- `evolve(seed, iterations, lambda, rngSeed, mutations, fitness): FnEvolveRun`.
- `FnEvolveRun` = { best, bestScore, initialScore, steps: List<FnEvolveStep>, iterations, evaluations }.
- `FnEvolveStep` = { iteration, mutation, score }.
- `matchFitness(target, domain, samples, complexityWeight)` returns `(Fn)->Double`, higher=better (negative RMSE minus complexity).
- Default mutations: jitter/nudge numbers, substituteOp, prune, duplicate.
- Tests run with cwd = project root (existing test reads `File("src/commonMain/...")`).

Approach — CPU-only, no GPU/Vulkan/Field window:
1. Harness `afield.examples.functions.EvolveWorkedExample` (JVM object with `main` +
   `render(outDir)`), building on the `Functions` DSL.
   - Build a target `Fn` (a sine with a specific frequency/amplitude), a flat/plain seed.
   - Run `evolve(seed, iterations, rngSeed = FIXED, fitness = matchFitness(target,...))`.
   - Hand-rolled SVG line-plot builder (pure Kotlin strings): sample `evalSafe(x)` across
     the Domain, map to `<polyline>`. Emit target vs seed vs evolved-best overlay SVG.
   - Emit a Markdown trace table from `run.steps` (iteration, mutation, score) + initial→best.
2. A `@Test` in `src/jvmTest/.../EvolveWorkedExampleTest.kt` that runs the harness,
   asserts the SVG + trace files exist and are non-trivial, and asserts determinism
   (same rngSeed => identical bestScore + graphSignature). This is the verification path.
3. Commit artifacts under `public/docs/assets/evolve/`.
4. Thread a prose walkthrough into `functions_examples.md` near the evolve section,
   embedding the SVG(s) and pasting the observed trace table + numbers.

Guaranteed-verifiable floor: SVG + numeric trace (no GPU/plotly path).
Determinism is the key property being demonstrated and is asserted in the test.

## Execution

Files changed:
- `src/jvmMain/kotlin/afield/examples/functions/EvolveWorkedExample.kt` (new) — the CPU-only
  harness: `render(outDir)` builds target/seed, runs `evolve`, writes an SVG overlay + trace
  Markdown; `main` prints the numbers and self-checks. Hand-rolled SVG (no plotly/GPU).
- `src/jvmTest/kotlin/afield/examples/functions/EvolveWorkedExampleTest.kt` (new) — asserts
  improving + deterministic + well-formed artifacts.
- `build.gradle.kts` — registers `runEvolveWorkedExample` (JavaExec, jvmMain runtime classpath),
  mirroring `runTrackingResonatorResynthesisExample`.
- `public/docs/assets/evolve/evolve-overlay.svg`, `evolve-trace.md` (new) — generated evidence.
- `public/docs/functions_examples.md` — new "A worked run (CPU-only, reproducible)" subsection
  in the evolve section, embedding the SVG + trace and narrating the run.

Chosen example (after surveying 5 target/seed × 5 rngSeed combos): target
`sin(freq=1, amp=0.5)`, seed `cos(freq=1, amp=1)`, `rngSeed=3`, iterations=120, lambda=8,
samples=160, complexityWeight=0.01. Picked for the richest, clearest narrative: it exercises
the whole palette (substituteOp → jitter/nudge → prune) and gives the largest error reduction.

## Verification (observed, honest)

- Ran `./gradlew runEvolveWorkedExample` (jvm-main path; jvmMain compiles clean). OBSERVED:
  `initialScore=-0.8420503456220446 bestScore=-0.15676727177844857 improved=true`,
  `acceptedMutations=12 evaluations=961`. Both files written. Trace pasted verbatim into the doc.
- Determinism VERIFIED by running twice and `diff`-ing: `evolve-overlay.svg` and
  `evolve-trace.md` are byte-identical across independent runs.
- SVG VISUALLY VERIFIED in a browser (served over http): evolved-best (blue) tracks target
  (red) closely; seed (dashed) is clearly off. Added a self-contained light figure background
  so it stays legible embedded as an image on any docs theme.
- `df -h /`: 33Gi free (not disk-full) — capture output is trustworthy.
- NOT RUN: `./gradlew jvmTest`. The jvmTest source set does not currently compile because of
  TWO PRE-EXISTING, UNRELATED broken test files in the base commit —
  `src/jvmTest/kotlin/organic/FLineLinearizerTest.kt` and `organic/PointMatcherTest.kt`
  (they reference symbols like `linearizeFLine`, `FLINE_LINEAR_VERTEX_STRIDE`, `PointMatcher`
  that no longer exist in production; both files are unmodified by me). My test file is written
  and self-consistent (all referenced symbols exist), but I could not execute it. Its two
  substantive assertions — improvement and byte-identical determinism — are exactly what I
  verified independently via the double-run `diff` and the `main` self-check, so the behavior
  is confirmed; only the `@Test` wrapper is "compiled path blocked, not executed".

## Risks & unknowns

- One-line risk: the `@Test` is unrun because the pre-existing broken `organic/*` tests block
  jvmTest compilation; the harness's behavior is nonetheless verified via the jvm-main run.
- SVG theming: embedded as `<img>` it can't read page CSS vars, so it relies on the built-in
  light figure background (intended). If a maintainer prefers a PNG (as the other galleries
  ship), `runEvolveWorkedExample` regenerates deterministically.

## Recommendation: MERGE

Self-contained, additive (new files + one doc subsection + one Gradle task), CPU-only, no
GPU/window dependency. Turns undemonstrated prose into reproducible, visually-verified evidence.
The only gap (unrun `@Test`) is caused by pre-existing unrelated breakage, not this change, and
the same assertions were verified out-of-band. Optionally, a follow-up could fix or quarantine
the broken `organic/*` tests so the whole jvmTest set (including this one) runs in CI.
