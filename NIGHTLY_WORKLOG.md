# Nightly 2026-09-09 · `kratos-parameter-studies`

Branch `nightly/2026-09-09/1-kratos-parameter-studies`, based on `6658f4d65`.

## Plan

### The gap

Kratos in Field is now a platform — a formulation registry with three wire-named physics, a
container transport, an async session with revision tracking, and a `SimulationResult` that already
knows how to reduce a solved field to numbers. Every demo in the repo solves it **exactly once**.
Nothing runs the solver many times and compares the answers, which is what a simulator is for.

### What lands

A **study layer** over the existing session: the human declares a design space over a typed
parameter/geometry object, an objective reduced from a `SimulationResult`, and gets back either a
sampled **landscape** or an **optimiser run** — with a figure, and with the best design's geometry.

1. `src/jvmMain/kotlin/afield/kratos/KratosStudy.kt` — `StudyVariable` / `StudySpace` / `StudyCase`
   / `KratosStudy` (`evaluate`, `grid`, `optimise`, `resultAt`) / `StudySample` / `StudyLandscape` /
   `StudyOptimisation`.
2. `src/jvmMain/kotlin/afield/kratos/KratosStudyFigure.kt` — a CPU raster of the landscape and of
   the convergence, plus `ColourScale` and `StudyTrace`.
3. `src/jvmMain/kotlin/afield/kratos/KratosStudyRun.kt` — `KratosStudyDemo` (the flagship question,
   shared by the run, the smoke and the document), `KratosStudyRun` (the nightly experiment) and
   `KratosStudySmokeTest` (the catalog-sized one).
4. `src/jvmTest/kotlin/afield/kratos/KratosStudyTest.kt` — the layer's invariants, no solver.

### The question the flagship study asks

> **Which arm length and which conductivity put 294.15 K — one kelvin above the skin — at a chosen
> point inside the part?**

Chosen because stationary conduction makes the answer a closed form, `k* = q·φ(arm)/(T − T_b)`, so
the valley in the measured landscape is checkable arithmetic rather than a shape to admire.

### Deviations from the plan as written

- The target temperature was **294.15 K, not 318 K**. The plan guessed; the exploratory corner pass
  measured the reachable range as 293.35–299.23 K and, more sharply, showed that the level set is
  interior *at every arm length* only for a target in (293.960, 294.677). Picking a round number
  blind would have put the valley off the edge of the map.
- The grid is **15×15, not 11×11**, because the measured cost of one solve (1.6 s) made 225 solves
  affordable at six minutes.
- The figure needed a **labelled logarithmic colour stretch** (`ColourScale.STRETCHED`) and a
  **logarithmic convergence axis**. Neither was foreseen; both were forced by looking at the first
  rendered figure, in which the bottom third of the map was one flat shade and the convergence
  panel was a cliff followed by a line along the floor.
- `optimise` grew a `start` seed and a **refinement stage**, because the first run's honest result
  was that a global search does not beat a grid on a valley this thin.

## Files changed

| file | why |
| --- | --- |
| `src/jvmMain/kotlin/afield/kratos/KratosStudy.kt` | new — the study layer |
| `src/jvmMain/kotlin/afield/kratos/KratosStudyFigure.kt` | new — landscape and convergence raster |
| `src/jvmMain/kotlin/afield/kratos/KratosStudyRun.kt` | new — the demo spec, the nightly run, the catalog smoke |
| `src/jvmTest/kotlin/afield/kratos/KratosStudyTest.kt` | new — 15 tests, no solver |
| `dev_docs/agents/transcripts/kratos_parameter_study.yaml` | new — the loadable document |
| `public/docs/assets/agent_transcripts/kratos_parameter_study/**` | new — materialised bundle |
| `public/docs/assets/kratos_studies/*.png` | new — the committed figures |
| `optional/kratos/README.md` | the study layer's section |
| `optional/kratos/VERIFICATION.md` | the measured sweep |
| `public/docs/agent_transcripts.md` | index entry, figure, and the two counts it asserts |
| `src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt` | classify the new transcript `COMPILE_ONLY` |
| `build.gradle.kts` | `runKratosStudy`, following the fifteen `JavaExec` tasks already there |

## Verification

*(filled in below after the final run)*

## Risks and unknowns

## Recommendation
