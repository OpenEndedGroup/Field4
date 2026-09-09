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

Four pieces:

1. `src/jvmMain/kotlin/afield/kratos/KratosStudy.kt`
   - `StudyVariable<D>` — one continuous design variable: name, unit, range, `apply: (D, Double) -> D`.
   - `StudySpace<D>` — a base design plus its variables; `at(coords)` builds a design.
   - `StudyCase` — what one design means to the solver: an optional `SimulationGeometry` plus a
     `SimulationParameters`, so **geometry is a first-class axis** for an embedded formulation
     (reclassification is free; the background grid is never rebuilt).
   - `KratosStudy<D>` — `evaluate(coords)`, `grid(steps…)`, `optimise(budget, …)`. Memoised on a
     quantised coordinate signature, so an overlapping re-run does not re-solve.
   - `StudyLandscape` / `StudySample` / `StudyOptimisation` — the results.
   - **Sequential by construction.** `KratosSession` is a newest-wins preview engine: a second
     `solveAsync` obsoletes the first and the first completes `null`. A study needs *every* answer,
     so it awaits each solve before submitting the next. That is a property of the session, and the
     study documents it rather than fighting it.

2. `src/jvmMain/kotlin/afield/kratos/KratosStudyFigure.kt` — a CPU raster (`PngEncoder`, no AWT):
   a labelled landscape heat map with a colour bar and the best cell marked, and a convergence panel
   (best-so-far per evaluation, optimiser against the random control arm).

3. `src/jvmMain/kotlin/afield/kratos/KratosStudyRun.kt` — a `jvm-main` entry point that drives the
   whole thing through the **live container** and writes the figures, plus a small FIELD_TEST-sized
   smoke.

4. `src/jvmTest/kotlin/afield/kratos/KratosStudyTest.kt` — the layer's own invariants against a
   fake evaluator (no solver): grid shape and ordering, cache hits, coordinate clamping, optimiser
   determinism, figure non-degeneracy.

### The question the flagship study asks

> **Which arm length and which conductivity put 320 K at this point in the part?**

- `thermal.stationary`, bounds ±1 m, resolution 12 (2,197 nodes / 12,288 tets) — the configuration
  `VERIFICATION.md` already measured.
- Body: `KratosDemo.body(armEnd)`, the existing two-cuboid assembly.
- Axis 1 — `armEnd` ∈ [0.35, 0.85] m. **Geometric**, and therefore a `SimulationEdit.GEOMETRY`
  reclassification of the same background grid.
- Axis 2 — `conductivity` ∈ [0.8, 6.0] W/(m·K). A `MATERIAL` edit.
- Objective, minimised: `|T(probe) − 320 K|` at a probe inside the arm but outside the base.

Why this objective: for stationary conduction `T = T_b + (q/k)·φ(geometry)`, so a target temperature
is the level set `k = q·φ(armEnd)/(T − T_b)` — a **curve** in the plane, bent by the geometry axis
alone. The landscape must therefore be a valley whose shape traces the geometric response φ, and
each column's minimum is checkable in closed form against a single solve at that `armEnd`. A figure
that is right is a figure I can arithmetically confirm, not one I merely like the look of.

Grid: 11 × 11 = 121 solves. Then an optimiser at a matched budget against
`PopulationSearch.random` as the control arm, which is the repo's own standard for reporting a
search result.

### Verification

Real numbers from `localhost/field-kratos:10.4.0` (warm, 447 MB, built ~11 h ago). The two figures
are opened and looked at. The target temperature is chosen **after** an exploratory sweep reports
the achievable range, so that the valley crosses the grid's interior; that ordering is recorded
rather than hidden.

### Demo artifact

`dev_docs/agents/transcripts/kratos_parameter_study.yaml`, materialised to
`public/docs/assets/agent_transcripts/kratos_parameter_study/metadata.edn`.

### Risks known up front

- Per-solve cost under x86-64 emulation is the budget. Measured first; the grid is sized to it.
- A design whose probe falls outside the solved region samples `null`. That is a real infeasible
  point and the study reports it as such rather than substituting a zero.
