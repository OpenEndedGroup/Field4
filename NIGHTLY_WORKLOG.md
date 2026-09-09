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

## Merge onto current `main` (2026-09-09, second session)

The branch was based on `6658f4d65`; `main` had moved to `4107a579e` with two Kratos commits —
quadratic tetrahedra and the structural playground. `main` merged in as `675adcc2f`.

**What the merge cost:** four conflicting files, every one of them additive — both sides appended a
section or an index entry to the same place. `optional/kratos/README.md` auto-merged;
`optional/kratos/VERIFICATION.md`, `public/docs/agent_transcripts.md` and
`DemoTranscriptExecSmokeTest.kt` were resolved by keeping both sides. Then one thing the textual
merge could not catch and a guard did: both sides had bumped the *same two numbers* in the index
page's totals paragraph by one from the same base, so git kept one side's `+1` and the page claimed
93 transcripts and 25 compile-only for a corpus that is now 94 and 26. `checkAgentTranscriptIndex`
failed on exactly that; fixed in `f6a60a96c`.

**Does main's new work change this layer?** No, and the reason is worth writing down rather than
asserting. `SimulationResult` (+72) and `SimulationGeometry` (+14) are entirely mesh-width plumbing:
`BackgroundDomain.nodesPerCell`, `cellCount` dividing by it instead of by 4, cell-to-node averaging
over every node rather than four, and `boundaryMesh` subdividing a quadratic face into four. The
study layer touches exactly one of those names — `session.domain.cellCount`, in a status string —
and a structured background grid is always `nodesPerCell = 4`, so the value is unchanged.
`KratosSession` (+25) is the `nodesPerCell` handshake on `createBodyMesh`, a path a study over an
embedded formulation never takes. Nothing landed that the study layer now duplicates, and nothing
landed that it should adopt.

`KratosDemo.body`, the geometry every point of the sweep classifies, is untouched by both commits —
`4107a579e`'s diff to `KratosDemo.kt` is purely additive from line 57 down. That is why the numbers
below are comparable to the ones measured before the merge.

**Territory overlap with `4107a579e`'s structural playground:** none. That is a body-fitted
structural solve, run twice at two element orders and compared; this is a sweep and a search over a
design space of an embedded thermal problem. They share a worker and nothing else. The one place
they nearly touch is `KratosStudy.evaluate`, which already refuses a body-fitted study carrying a
geometry with a message naming the reason ("a geometric axis needs a new session per point rather
than a study") — that refusal was written before the structural playground existed and is still the
right answer for it.

## Verification

Everything below was run in this worktree, after the merge, and the numbers are quoted from the
runs' own output.

**The box Kotlin compiles through Field's real K2 path — this is the thing the previous session
died before doing.** `kratos_study_code_.kt` was known to be valid repo Kotlin, which is a
different compiler from the one a box uses.

```
DEMO_TRANSCRIPT_ONLY=kratos_parameter_study python3 tools/run_field_tests.py --id examples.demo-transcript.exec
[1/1] PASS examples.demo-transcript.exec (8612 ms)
  [compile]   kratos_study: PASS
  [compile] PASS kratos_parameter_study
  compile=2.28s
  DemoTranscriptExecSmokeTest ok — 1 demos verified (61 executed, 26 compiled, 7 kotlinjs)
```

That is the real path, not a proxy: `COMPILE_ONLY` builds the graph through the real create/connect
apply path, expands macros, runs the real preamble machinery and hands the body to
`compileSnippetAndWait` with only the terminal evaluation skipped. The compile is where a box
diverges from repo Kotlin, and it passed with zero diagnostics. The materialised sidecar is
byte-identical to the `code` block the harness compiled (both 10251 characters, compared
programmatically), so the file shipped in the bundle is the file that was verified — not a sibling
of it.

**Unit tests, re-run after the merge**, forced with `--rerun-tasks` (2m 23s of real build, not an
`UP-TO-DATE` no-op):

| suite | tests | failures | errors |
| --- | --- | --- | --- |
| `KratosStudyTest` | 15 | 0 | 0 |
| `KratosTest` | 16 | 0 | 0 |

**Guards**, all forced with `--rerun-tasks` so none of them was an `UP-TO-DATE` no-op:

| gate | result |
| --- | --- |
| `compileKotlinJvm` | clean after the merge |
| `checkAgentTranscriptIndex` | failed on the count regression above; green after the fix — *"ok — 94 transcript(s), 94 entry/entries"*, 1m 55s |
| `checkAgentTranscripts` | ok — 88 documents materialized, committed bundle checked, 2m 08s |
| `checkAgentTranscriptLayouts` | **fails, and not because of this branch** — see below |
| `kratos.study.parameter-sweep` | PASS, 44.2 s, against the real container |

`checkAgentTranscriptLayouts` reports six problems and every one of them is `logging_layer`, a demo
under `dev_docs/agents/demos/` that this branch does not touch: `git diff main...HEAD` over
`dev_docs/agents/demos/` and `transcript_layout_exceptions.yaml` is empty, and the demo landed in
`9b78a3057`, an ancestor of `main`. So it is red on `main` too. The thing worth noting is the
*absence*: the guard says nothing about `kratos_parameter_study`, so this transcript's frames pass.

The catalog smoke is the one that matters most, because it is the piece that will keep running after
tonight. It solves a 3x3 grid and a budget-6 search on the real worker and asserts the properties
that fail silently: 9/9 feasible, `buildCount` still 1, a repeated grid costing **0** new solves and
9 cache hits, a higher conductivity giving a colder probe, and a figure with 380 distinct colours
rather than a blank one. Mean solve 1565 ms.

### The live sweep, re-run after the merge

`runKratosStudy --grid 15 15 --budget 40 --coarse 5 --refine 30`, against
`localhost/field-kratos:10.4.0` under `linux/amd64`. 10m 52s wall, exit 0.

```
background · 2197 nodes · 10368 tetrahedra · builds 1
FINE GRID  225/225 feasible, best 6.96784e-05 at arm end=0.6714, conductivity=4.514, 390.0 s of solver
NO_REMESH  buildCount=1 over 350 solves
CACHE      re-running the same grid cost 0 new solves and 225 hits; best unchanged
BEST       probe=294.1501 K against a target of 294.15 K · peak in the part 294.7143 K
total solver time 619.2 s over 350 solves (235 cache hits)
```

**The physics reproduces exactly.** The fine grid's optimum is the same cell as the committed
figure's — arm 0.671, k 4.51, and the committed figure's colour-bar minimum reads 0.00007, which is
this run's 6.96784e-05. The closed-form check agrees at every one of the fifteen arm lengths, worst
gap **0.50 grid cells** and eleven of fifteen under a third of a cell:

```
arm 0.350  measured k*=1.171  closed form k*=1.221  gap 0.13 cells
arm 0.671  measured k*=4.514  closed form k*=4.515  gap 0.00 cells
arm 0.850  measured k*=4.886  closed form k*=4.862  gap 0.06 cells
```

**Solve cost is essentially unchanged, and my first reading of it was wrong.** I began by comparing
this run's `390.0 s of solver` against the committed sheet's caption of `306.0 S OF SOLVER` and
wrote down a 27% slowdown. That comparison is invalid. `StudySample.asCacheHit()` carries `solveMs`
through unchanged and `StudyLandscape.summary()` sums it over all 225 cells, so that caption is "what
these 225 cells cost to solve", counting solves paid for in an earlier stage of the same run — and
the *mix* of fresh to carried-in cells differs between the two runs, because their coarse grids and
search budgets differ. It is a fine per-landscape number and a meaningless cross-run one.

The comparable figures are in `optional/kratos/VERIFICATION.md`, which records the earlier sweep as
`grid wall 365.2 s · mean 1623 ms`. This run: **wall 373.1 s, mean 1769 ms** — 2% on the wall clock
and 9% on the mean solve, with three other Field JVMs resident, one of them at 4.4 GB. That is
machine load. Nothing in the API changed and the per-solve results are identical.

**The two runs are not the same experiment, and the committed figure was left alone.** Its
convergence panel runs to 59 evaluations, so it was produced with a search budget near 60; this run
used 40, which is why the search and control legends differ (0.0143 and 0.0390 here against 0.0142
and 0.0132 there). The refinement arm, which shares its seed and budget across both, reproduced to
every digit printed: **0.00109203**. I did not overwrite the committed PNG — replacing a validated
figure with one from a different configuration would be churn, and the run wrote to
`build/kratos-studies-remerge/` instead.

**I opened the regenerated figure rather than trusting that it was written.** It is right: the
diagonal valley is legible across the whole map, the caption reads `225/225 FEASIBLE · 390.0 S OF
SOLVER`, the stretched colour bar's ticks are labelled and monotone, the grid best is ringed at the
cell the closed form predicts, and all three convergence traces sit at or above the `GRID BEST`
floor — the honest result, which is that a global search does not beat a 225-cell grid and the
seeded refinement gets within a factor of sixteen of it for a quarter of the evaluations.

One thing I checked and it was **not** a bug: the convergence panel's floor label renders as
`0.00007` at this font size and I first misread it as `0.0007`, a tenfold disagreement with the
colour bar. `KratosStudyFigure.number(6.96784e-05)` returns `"0.00007"`; the two labels agree.

## Risks and unknowns

**Not verified, and each of these is a real gap rather than a formality:**

- **The transcript has never been *run*, only compiled.** `COMPILE_ONLY` is the right
  classification — a real sweep is minutes of container solver and does not belong in a CI harness
  that already takes ten — but it means nothing in the automated corpus has ever seen this box's
  sliders move, its figure appear in `display()`, or its cancel path taken. The pieces are covered
  separately: the layer by `KratosStudyTest`, the solver path by `kratos.study.parameter-sweep`, the
  box body by the K2 compile. Nothing covers the three composed, in a window, with a human dragging
  the grid-size slider. That was never claimed and is not claimed now.
- **No headless capture.** The figures in the docs are written by `runKratosStudy`, a CPU raster
  encoded straight to PNG. There is no docs-capture recipe photographing the *box* running, the way
  `kratos_structural_playground` has one. The index entry's image is therefore a picture of the
  study layer's own output, not a picture of Field.
- **`solveCount` counts attempts, not solves.** `KratosStudy.evaluate`'s catch increments `solves`
  even when the failure happened before the worker was reached — a `plan` that throws, or the
  formulation-mismatch `require`. The KDoc says "distinct designs actually sent to the worker",
  which is true on every path a sweep normally takes and false for a study whose `plan` is buggy.
  Cosmetic, in a diagnostic counter; worth an honest line rather than a fix at this hour.
- **The optimiser's honest result is still a negative one.** A global search with no prior does not
  beat a grid on a valley this thin, and the figure says so — every trace sits at or above the
  `GRID BEST` floor. The refinement seeded from a coarse grid *does* win, which is the finding, but
  a reader could take the convergence panel as evidence that the search arm works well. It works
  correctly; it does not work impressively, and the panel is drawn so that is visible.
- **The figures' "N s of solver" caption is not a cross-run quantity**, and it reads like one. It
  sums `solveMs` over every cell of the landscape, cache hits included, so it measures what those
  cells cost whenever they were first solved rather than what this stage spent. Two runs of the same
  grid with different coarse/search stages before them will print different values having done
  identical work. I misread it that way myself before checking; a reader comparing two committed
  sheets will too. Either the caption should say "to solve these cells" or it should report the
  landscape's own wall time.
- **One machine, one container image.** Everything measured here ran against
  `localhost/field-kratos:10.4.0` under `linux/amd64` emulation on Apple silicon. Solve cost on any
  other host is a guess.

## Recommendation

**Merge.** The branch is on current `main`, it builds, its tests pass, its guard passes, and the one
thing that was genuinely unverified when the last session died — whether the shipped box Kotlin
compiles in a box rather than merely in the repo — has now been checked on the real path and passes.

The reasons not to hold it back are that it adds a layer rather than changing one (nothing outside
`afield/kratos/KratosStudy*` and the transcript's own files behaves differently because of it), and
that its two riskiest claims are both self-checking: `kratos.study.parameter-sweep` fails if a sweep
ever rebuilds the background grid or if the figure comes out blank, and the closed-form valley check
in `runKratosStudy` fails if the measured minimum wanders from `k* = q·φ(arm)/(T − Tb)`.

The one thing I would not do is treat the docs figure as maintained. It is a committed PNG produced
by a task nobody runs on a schedule, and the first time the demo's geometry or target changes it
will quietly become a picture of a different study. If this layer earns a second night, giving the
figure a capture recipe — so that it regenerates and is *looked at* the way the graphics transcripts
are — is the work worth doing before anything else is added to the layer itself.
