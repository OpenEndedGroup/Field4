# Spatial artificial life — developer guide

`afield.stdlib`'s `alife*.kt` family: grid cellular automata, reaction–diffusion, Lenia, agent
populations and L-systems. Public worked example: [`public/docs/alife_examples.md`](../public/docs/alife_examples.md).
Verification harness: `afield.examples.stdlib.AlifeGallery`, in two tiers — FIELD_TEST
`stdlib.alife.gallery` (`--tier fast`, 11 figures, ~6 s, tagged `smoke`, and therefore what a routine
`--tag smoke` run pays for) and `stdlib.alife.gallery.slow` (`--tier slow`, ~25 s, deliberately *not*
tagged `smoke`, so it runs in an unfiltered catalog pass and on demand).
Loadable demo: `dev_docs/agents/transcripts/alife_worked_example.yaml`.

## Why this exists, and what it is built on

Field's stated target domain is new-media art, and before this landed the stdlib had the *graph*
half of artificial life (`growing.kt`'s 3-regular graph rewriting, `dynamic_graph_systems.kt`,
`interactionnets.kt`, `booleannetworks.kt`) and the constraint-based generative half (`wfc.kt`), but
nothing spatial. A grep for `lenia|physarum|boids|gray.scott|cellular automat|l-system|reaction.diffusion`
over `src/**.kt` found three unrelated physics files.

The family is deliberately **additive**. Everything it needs already existed:

| what it needs | where it comes from |
|---|---|
| a float raster with a wrapping border | `imaging.kt` — `Img`, `Border.WRAP` |
| convolution, blur, Laplacian | `imaging_filters.kt` — `convolve2D`, `boxBlur`, `Kernel2D` |
| connected components, contour tracing, morphology | `imaging_features.kt` — `connectedComponents`, `traceContours`, `dilate` |
| colour maps, montage with burned-in captions, `FLine` bridges | `imaging_field.kt` — `colormap`, `montage`, `isoContourFLines` |
| marching squares | `contours.kt` |
| marching cubes, watertight by construction | `mesh_sdf.kt`, `mesh_topology.kt` |

Nothing here reimplements any of those. If you find yourself writing a convolution in this family,
stop.

## File layout

| file | contents |
|---|---|
| `alife.kt` | `IntGrid`; grid↔`Img` bridges; **the measurement layer** |
| `alife_ca.kt` | `LifeRule`, `LifePatterns`, `runUntilCycle`, `CyclicRule`, elementary 1-D CA, `IntVolume` + `LifeRule3D` + `toMesh` |
| `alife_continuous.kt` | `diffuse`, `GrayScottState`, `turingPattern`, `LeniaRule`, `leniaRun` |
| `alife_agents.kt` | `BoidFlock`, `PhysarumSwarm`, `dlaCluster` / `edenCluster` / `sprinkleCluster` |
| `alife_lsystem.kt` | `LSystem`, `turtleDraw`, `rasterizeFLines`, `spaceColonization` |
| `alife_field.kt` | the inline `ToHTML` views for `IntGrid`, `GrayScottState` and `LeniaRun` |
| `alife_animate.kt` | `Box.animateEvolution` and four "show a prefix of something" helpers |

The multi-file split follows the `imaging*.kt` / `mesh*.kt` convention for a large family. All seven
carry `@file:afield.CompletionCategory("ALife")` — without it a new file lands in a freshly-created
`reference/uncategorised.md`, which is how `alife_field.kt` first failed the reference drift guard.

`alife_animate.kt` sits apart from the five simulation files for the same reason `alife_field.kt` does:
those files name nothing from Field's box runtime and it is entirely about it (`Box`, `Frames`,
`display`). See "Animating one of these" below for the two things in it that are not obvious.

## The measurement layer is the load-bearing part

This is the thing to understand before changing anything. Roughly half of `alife.kt` is not automata
at all — it is the statistics the rest of the family makes its claims through:

- **`polarization(vx, vy)`** and **`randomPolarizationBaseline(n)`** — the flock order parameter and
  the level `n` *independent* headings would give (`√π / 2√n`). A polarization quoted without the
  baseline is a number with no scale.
- **`nearestNeighborDistances`** (toroidal) + **`quantileOf`** — the *floor* under a flock's spacing.
- **`blobShapes`** → **`BlobShape.isoperimetricRatio`** — `P²/4πA` with the crack perimeter corrected
  by `π/4`. This is what tells **spots from stripes** when coverage, mean and variance are identical.
- **`radialAutocorrelation`** / **`correlationLength`** — the length scale a pattern selected.
- **`topShare(fraction)`** — concentration; how a Physarum *network* is distinguished from the same
  agents doing a random walk.
- **`massRadiusDimension`** → **`MassRadiusFit`** (dimension, the fitted points, and `r²`) — fractal
  dimension by mass–radius scaling.
- **`translationBetween`** — the *strong* form of "the pattern moved": not "the centroid drifted about
  one cell" but "every live cell moved by the same integer offset and nothing else changed".

### The estimators are pinned against closed forms, not against a disc

`figureCalibration` measures a synthetic disc and a thin annulus. That is one easy point of one curve,
and it is not enough: two of these statistics were *wrong* in the first version of this family and were
caught only because a control arm happened to disagree. So `figureMeasurement` (fast tier, runs first)
checks them against inputs whose answers are known analytically. Every number below is asserted.

| input | closed form | measured |
|---|---|---|
| uncorrelated noise, `ac(1..6)` | 0, within the `1/sqrt(N)` sampling band | `|ac| <= 0.0032`, band `0.0156` |
| uncorrelated noise, `correlationLength` | exactly `(1 − 1/e)/(1 − ac(1))` | `0.630106` vs `0.630106`, to `1e-9` |
| the same field, bilinear `ac(1)` | a *positive bias*, outside the band | `0.0417`, 2.7× outside |
| checkerboard `ac(1)` | exactly `−1/3` (8 of 24 directions round to a diagonal, 16 to an axis) | `−0.333333` |
| checkerboard `correlationLength` | exactly `(1 − 1/e)/(4/3)` | `0.474090` vs `0.474090` |
| stripes at `λ = 12, 24, 48` | `length ∝ λ` | `length/λ` = `0.2655, 0.2749, 0.2774` (2.6 % spread) |
| points at radii `R·(i/n)^(1/D)` | `massRadiusDimension` = `D` | `1.000, 1.262, 1.711, 2.002` for `D = 1.00, 1.2619, 1.71, 2.00` |
| a lattice line | `D = 1` | `0.993` — `N(r) = 2⌊r⌋+1` is a staircase |

Two things follow that no amount of control-arm design gives you.

**The floor of `correlationLength` is 0.632, not 0.** For a field with no correlation, the estimator
interpolates between `ac(0) = 1` and `ac(1) ≈ 0` and lands on `1 − 1/e`. Every "a structureless field
reads low" claim in this family is now stated against that floor rather than against a chosen constant.

**`massRadiusDimension` is unbiased at 1.71.** Handed a point set whose exponent is exactly 1.71 it
returns 1.711. So the DLA figure's 1.617 is a fact about the cluster and not the estimator reading low —
which the first pass asserted but did not check. The lattice costs about 0.7 % (the line reads 0.993 and
the calibrated disc 1.986, the same bias in both directions), so the continuum reading of that cluster
is ~1.63: still clearly under 1.71, and the "between 1.5 and 1.8, clearly not 2" framing survives.

### Three calibration/robustness lessons that are easy to undo

These are all things a control arm caught during the initial build. Each is now pinned by an assertion
in `AlifeGallery`, and each is a trap you would otherwise fall into again. All three fixes have since
been confirmed *correct* rather than merely non-failing, by the oracle table above.

1. **`radialAutocorrelation` must not interpolate.** Bilinear sampling at a non-integer offset averages
   four neighbours, *one of which can be the centre pixel*, so it manufactures correlation out of
   nothing. Measured: lag-1 autocorrelation of shuffled white noise is `0.004` with lattice sampling
   and `0.049` with bilinear — and under the old zero-crossing definition of correlation length that
   noise floor read as a **correlation length of 8.97**. The `bilinear` parameter still exists so the
   artefact can be demonstrated; the default is `false`, and the gallery asserts the gap.
2. **`correlationLength` uses a `1/e` threshold, not the first zero crossing.** An uncorrelated field's
   autocorrelation does not sit at zero, it fluctuates about zero at the estimator's noise floor, so
   the first *sign change* is wherever the noise happens to flip. An e-folding threshold sits far above
   that floor.
3. **`massRadiusDimension` needs `minCount`.** A fit that starts where `N(r)` is three points is
   dominated by counting noise: without it, a **uniform disc** measured 2.32, which is not a possible
   answer.

And a calibration rather than a lesson: `AlifeGallery.figureCalibration` runs first and measures a
synthetic disc (isoperimetric ratio must be ≈ 1, mass dimension ≈ 2) and a thin annulus (ratio > 8),
because every downstream threshold is stated relative to those. If the calibration drifts, nothing
below it means anything.

## Design conventions

- **A step is a value.** `IntGrid.step(rule)`, `GrayScottState.step(p)` and `Img.leniaStep(rule)` all
  return the next state. The exceptions are `BoidFlock.step` and `PhysarumSwarm.step`, which mutate the
  agent arrays in place — a simulation, not a value, and copying 50 000 agents per frame would be most
  of the cost of a long run. `PhysarumSwarm.step` returns a *new* swarm because the trail field is
  replaced by its blurred, decayed successor, while sharing the agent arrays.
- **Synchronous update, always.** Every cell reads the *old* state. An in-place CA update is a different
  and much less interesting automaton, and is the classic way a first implementation ends up with a
  glider that smears instead of moving.
- **Deterministic from a seed.** Every stochastic entry point takes `seed: Long` and constructs its own
  `kotlin.random.Random`. Figures are reproducible; a regression is a diff.
- **Stability bounds are `require`d, not clamped.** `Img.diffuse` demands `rate <= 0.5` and
  `GrayScottParams` demands `D * dt <= 0.5`, because above that the explicit scheme diverges and that is
  a property of the scheme rather than a bug to hide.
- **`IntGrid` is integers, `Img` is floats.** A CA cell is a *state label*, not a sample. Convert at the
  boundary with `IntGrid.toImg(states)` / `IntGrid.toImg(palette)` / `Img.toIntGrid(threshold)`.

### The `IntGrid.step` fast path

`step(rule: LifeRule)` has an interior fast path for the radius-1 Moore case that reads the eight
neighbours straight out of the array with no border resolution and no bounds checks; border rows and
columns still go through `get`, so wrapped and bounded grids behave identically. This is not premature:
verifying the R-pentomino's 1103-generation history needs a 620×620 board, which is `4 × 10⁸` cell
updates, and the difference between eight `get` calls and eight array reads is the difference between a
figure that renders and one that times out.

## Per-system notes

### Life-like rules

`lifeRule("B3/S23")` accepts both notations — with `B`/`S` labels the parts are honoured; a bare `a/b`
is read as the older *survive/birth* convention. `LifeRule` also carries `radius` and `vonNeumann`, so
"Larger than Life" rules are expressible.

`runUntilCycle` hashes **exact grids**, so a pattern whose population is constant while it rotates is
not falsely reported as period 1. Memory is `O(maxGenerations)` hashes plus the grids that share a hash.

`LifePatterns` deliberately contains only patterns whose behaviour is a published fact. The Gosper
glider gun was written and then deleted during the initial build because its layout could not be
recalled reliably, and a wrong "canonical" pattern is a fabrication.

**The R-pentomino subtlety**, which is the model for how this family handles boundary conditions: the
textbook figures (peak 319 at generation 821, then 116 cells from generation 1103) are about an
*unbounded* board — six of those 116 cells are gliders still in flight. On a walled board the gliders
are destroyed and the system instead becomes exactly period-2 at generation 1164 with 110 cells. The
gallery checks the unbounded figures on a 620-wide board *and asserts `liveBounds` to confirm no cell
ever reached an edge*, and reports the walled-board answer alongside as the correctly different result.
Do not "fix" the discrepancy; it is the point.

### Cyclic CA

`CYCLIC_LABYRINTH` is named for the phase this codebase *measures* it to produce (a locally coherent
labyrinth of colour bands), not for the spirals it is often illustrated with. Spiral cores appear in
`CYCLIC_EXCITABLE`'s corner of the parameter space. `cyclicAdvanceFraction` is the discriminating
statistic, and it has a closed form to check against in the uncorrelated case:
`1 - (1 - 1/states)^8` for threshold 1, radius 1.

The length-scale claim is made against each field's **own shuffled pixels**, not against the other
parameter set: on the gallery's 220x220 fields the labyrinth measures a correlation length of **2.80**
against **0.634** shuffled and the excitable rule **2.01** against **0.633**, i.e. 4.4x and 3.2x their
own floor. (0.632 is the floor of that statistic, not 0 — see the oracle table above.) The pairwise
labyrinth-vs-excitable ratio is only 1.39, so its assertion is bounded at 1.15 rather than at the
measured value; the direction is the interesting part and the margin is not large.

**These numbers are grid-size dependent, and the public page's are different on purpose.**
`public/docs/alife_examples.md` runs the same comparison on 200x200 fields and prints **2.77** and
**1.96**. Same phenomenon, different measurement, not a discrepancy — do not "reconcile" them.

### Elementary 1-D CA

`elementaryCa` returns the whole space-time diagram as an `IntGrid` (one row per generation) because a
1-D automaton's behaviour is 2-D and every famous statement about these rules is a statement about that
picture.

Two **independent oracles** live here, and they are the strongest evidence in the family because they
are computed without running a CA:

- `sierpinskiReference` — rule 90 from a single seed is Pascal's triangle mod 2, from Kummer's theorem
  (`binomialParity(n, k) = 1` iff `n and k == k`). Zero differing cells over 257 × 128.
- Gould's sequence — row `g` of that diagram holds exactly `2^popcount(g)` live cells.

Plus rule 184's exact density conservation, with rule 90 as the control.

### Gray–Scott and Turing

`REACTION_DIFFUSION_LAPLACIAN` uses the literature's `0.05 / 0.2 / -1` weights, which sum to **exactly
zero** — that is what makes `u + rate * Lap(u)` conserve total mass on a torus. Note the boundary
behaviour, because it is counter-intuitive and one version of the gallery asserted the opposite:
`WRAP` conserves mass, **`CLAMP` also conserves mass** (it is a reflecting, zero-flux Neumann boundary),
and `ZERO` is the absorbing one that loses it.

The preset names (`GRAY_SCOTT_SPOTS`, `_WORMS`, `_MAZE`, `_MITOSIS`, `_HOLES`) are labelled by the
morphology this codebase *measures*, not by a label copied from a paper. `GRAY_SCOTT_MITOSIS`'s KDoc
states exactly what was measured — one synchronous division from a square seed within ~80 steps, then
stable — because "mitosis" would otherwise read as a claim of sustained division that the figure does
not support.

`turingPattern` exposes `rho` as well as `scale`, because `scale` is capped near 1.25 by the explicit
scheme's stability limit while the selected wavelength goes as `√(D/ρ)`. At the old default `rho = 0.04`
the pattern sat at the pixel scale: a correct Turing pattern and a useless figure.

### Lenia

`LeniaRule`'s kernel is a **sparse tap list** (`LeniaTaps`), because a ring kernel of radius 12 has 625
dense taps of which the centre and corners contribute nothing, and the convolution is the entire cost of
a step.

`leniaFromLifeRule` is the family's cross-check: with a uniform Moore ring kernel, `dt = 1` and a
three-valued step growth mapping, Lenia reduces **exactly** to an outer-totalistic `B/S` rule, so the
float convolution and `alife_ca.kt`'s integer neighbour counting must agree bit for bit. Two notes:

- It supplies its 8 taps through `LeniaRule.kernelTaps` rather than the shell builder. The shell builder
  measures radius in *Euclidean* units and drops anything past `radius`, so at `R = 1` the four
  diagonals (at `√2`) fall outside and the kernel silently becomes the von Neumann cross — a different
  automaton. A tap count of 4 instead of 8 is how that bug surfaced.
- It requires `birth ⊆ survive`, and that is a fact rather than a limitation: if a count births a dead
  cell but does not sustain a live one, no function of the neighbour count alone can do both.

The continuous side deliberately does **not** claim a named Lenia lifeform. Reproducing Orbium needs its
exact published 20 × 20 array, which this family does not ship; inventing one would be a fabrication.
What it does instead is report the measured `DEATH` / `BOUNDED` / `SATURATION` trichotomy via
`leniaRun`, with the mass history behind the verdict.

### Boids

`BoidParams.minSpeedFraction` is not cosmetic. The three steering terms are all written as
*desired-minus-current* velocities, so in a settled flock they largely cancel and, with a force cap, the
speed drifts downward — measured at 0.46 of a 1.4 cap before the floor existed, i.e. a stalled flock
still scoring 0.88 on alignment. The floor changes magnitude only, never heading, so it cannot inflate a
polarization measurement.

A global polarization near 0.69 rather than 1.0 is the *correct* answer: the population settles into
several separate flocks with different headings, and the global order parameter of `k` independent
flocks tops out near `1/√k`.

### Physarum

`PhysarumParams.exclusive` (Jones' one-agent-per-cell rule) is **the model**, not an optimisation.
Without it, every agent within sensing range of the brightest trail converges on it: the first version
collapsed 6000 agents onto a 191-cell streak and measured `topShare(0.05) == 1.000`, which reads as
perfect concentration and is total failure. Exclusion makes the population incompressible, so a trail
can only get so strong before it must widen or branch.

Deposit happens **after** the move, so nothing senses its own current step, and the trail is diffused
and decayed once per step after all agents have moved (a per-agent decay would make the result depend on
iteration order).

### DLA

`dlaCluster` launches walkers on a circle just outside the current cluster radius and abandons any that
wander past a kill radius. Both are standard and change the runtime by orders of magnitude without
changing the physics.

Two controls ship with it and exist to be used: `edenCluster` (same neighbourhood, growth site chosen
uniformly) and `sprinkleCluster` (uniform in a disc, dimension exactly 2 by construction). A DLA
dimension quoted without at least one of them is not evidence.

Note the honest framing in the gallery: the measured 1.62 is below the 1.71 asymptote because lattice
clusters of this size are known to read low, so the assertion is "between 1.5 and 1.8 and clearly not 2"
rather than a claimed match. That "lattice clusters read low" is no longer an appeal to the literature:
`figureMeasurement` shows the estimator returns 1.711 for a synthetic set whose exponent is 1.71, and
that a lattice line and a lattice disc both read 0.7 % under their exact answers. So ~0.7 % of the gap is
the lattice and the remaining ~5 % is the cluster's finite size.

The Eden and sprinkle arms are compared with *each other*, not with a `> 1.85` constant: two compact
objects built by different code must measure the same dimension (they agree to 0.046) and DLA must sit
well below both (it sits 0.29 and 0.34 below). That is a three-way statement no single threshold makes.

### L-systems

`TurtleParams.drawSymbols` defaults to `"FG"` but must be `"AB"` for the Sierpiński arrowhead, whose
non-terminals are also its drawing symbols. With the wrong alphabet the turtle silently draws nothing.

The exact identities the gallery asserts, all of which a mis-implemented turn breaks: Fibonacci
derivation lengths for the algae system; `3 · 4^n` forward symbols for the Koch snowflake; a turtle path
that closes to `2.3e-14` of a step; drawn length that grows by exactly `4×` per generation at constant
step (the textbook `4/3` is the constant-*diameter* form); and enclosed area matching
`9^n · A₀ · (1 + 3/5(1 − (4/9)^n))` to `3e-8` relative. That last floor is `FLine`'s single-precision
coordinate storage, not the maths.

## The bridges out, and one thing `isoContourFLines` does not promise

The family reaches Field's existing geometry two ways, and both are now verified rather than merely
demonstrated (until this pass they were exercised by the shipped transcript, which only asserts that no
box printed an error — so "195 iso-contour FLines" was a number nobody could check):

- **`Img` → `FLine`** via `imaging_field.kt`'s `Img.isoContourFLines`, which adapts an `Img` to the
  `ScalarField2D` that `contours.kt` marches. Checked in `AlifeGallery`'s `bridge-flines` figure (fast
  tier) against circle geometry: a disc of radius 60 gives exactly one loop enclosing **0.16 %** off
  `πr²` and **1.44 %** off `2πr`; three separated discs give three loops, matching what
  `blobShapes` counts independently; a constant field gives none.
- **`IntVolume` → `TriMesh`** via `mesh_sdf.kt`'s marching cubes, checked for watertightness and an even
  Euler characteristic with a non-zero triangle count (an empty mesh is vacuously watertight, and the
  first version of that figure passed that way).

**`isoContourFLines` does not encode a hole by winding direction, and nothing said so.** An annulus
contours to two loops whose signed areas are **both positive** (15408.2 and 3862.2 for `R = 70`,
`r = 35`). Each loop is right to better than 0.4 % of its own circle, so the geometry is fine — but the
orientation carries no information, and the consequence is concrete:

```kotlin
loops.sumOf { flineSignedArea(listOf(it)) }        // 19270 — 67 % too high for an annulus
abs(outer) - abs(inner)                            // 11546 vs the exact 11545.4 — 0.006 %
```

Anything that fills, booleans or exports these curves has to identify the hole by some means other than
its winding — containment, or area magnitude. This is **not a violated contract**: `contours.kt` promises
no winding convention anywhere (its one "counter-clockwise" comment is about a marching cell's corner
ordering, not the output loop). It is undocumented behaviour, which is why it is now an assertion.

That assertion is also a deliberate tripwire on a live coupling. `contours.kt` is shared code and its
marching-squares behaviour is being changed on another branch as of 2026-07-29. **This branch only ever
*consumes* `contours.kt` — it does not edit it.** If winding becomes orientation-aware there, the
same-sign assertion in `figureBridgeFLines` fails and names the reason in one line, rather than the
annulus area quietly starting to come out right for a reason nobody wrote down. Every other claim in that
figure is stated as the area or perimeter of a circle, which is the form that survives a correct
reimplementation and still fails a wrong one.

## Inline `ToHTML` views

`alife_field.kt` holds the bodies of `IntGrid.toHTML()`, `GrayScottState.toHTML()` and
`LeniaRun.toHTML()`. It is a separate file for the reason `wfc_field.kt` is: the five system files then
name exactly three symbols from it and nothing else from Field's visualizer, so `alife.kt` reads as
integer lattices with no HTML in it.

**The range policy is load-bearing, not tidy.** Every scale is fixed by meaning and named in the caption.
The specific reason, and it is the same trap that cost this family a figure during the first build: a
`SATURATION` Lenia field is *constant at 1*. Normalise per frame and it maps to the dark end of the
colour map and renders solid black — pixel-for-pixel identical to a field that died. A view is a worse
place for that bug than a figure, because a figure is regenerated deliberately and a view is regenerated
every time somebody looks at a value.

So the views are verified at the pixel, in `AlifeGallery`'s `inline-view` figure (fast tier), by decoding
the base64 PNG back out of the emitted HTML — the pattern `WfcSmokeTest` uses:

| claim | how it is checked | measured |
|---|---|---|
| one pixel per cell | panel dimensions against the grid's, and every pixel addressed by *grid* coordinates | 12×12 for a 12×12 grid |
| the caption's grayscale mapping | all live cells `#ffffff`, all dead `#000000` | 5 / 139, 0 wrong |
| a multi-state grid is not grayscale | distinct colour count, and the caption names `cyclicPalette` | 8 colours in an 8-state soup |
| `DEATH` ≠ `SATURATION` | centre pixel of each against the two ends of `inferno` | `#000004` vs `#fcffa4` |
| an unseeded `GrayScottState` renders empty | distinct colour count, and the bottom of `magma` | exactly 1 colour, `#000004` |
| …and the view is not simply always black | the same view of a seeded field | 101 distinct colours |

One honest limit, stated in the KDoc rather than papered over: an `IntGrid` does not carry its rule's
state count, so the view reads `maxState() + 1` from the data. A 14-state cyclic soup that has died back
to 9 distinct states reports 9. The caption therefore says "states **observed**".

## Animating one of these

Every system here is a time evolution, and the montage of frozen generations that the docs page's figures
end with is precisely the view that hides what they are about. `Box.animateEvolution` is the family's one
animation idiom: a state, a `step`, a `panels` renderer, and a **schedule** — `totalSteps` over `seconds`,
divided into `framesTarget` displayed frames. A `Frames` that is merely built never runs, so a box that
ends in a call to it must be **launched**, not run; the docs fences say `execution="launch"`.

Two things in it are not obvious, and both were paid for.

**Two clocks, because either alone fails.** A fixed steps-per-frame makes the clip's content depend on how
fast the host is. A pure wall-clock schedule diverges, because a pass that has fallen behind is then asked
for a bigger batch, which makes it fall further behind. So the loop advances at most the largest batch its
own schedule ever asks of one frame *and* never runs ahead of the clock. A slow host therefore lags at a
bounded rate and keeps catching up after the schedule ends — which is why a recording should ask for a
second or two more than `seconds`, and why every call site burns the step counter into the frame. "Did this
clip reach the regime?" is then answered by looking at the last frame of the mp4 rather than by trusting
the schedule.

**`ramp`, because a linear schedule is not neutral.** Reaction–diffusion and Lenia are log-time processes:
step 60 → 600 and step 600 → 6000 are about equally large *visible* changes. Measured on the recorded
clips as the consecutive-frame mean absolute luma difference summed per quarter, an even schedule put
**78.3 %** of Gray–Scott's whole change in the first quarter of the clip (Lenia: 86.3 %) and 0 % in the
last. `ramp` is the ratio between the last displayed frame's batch and the first, on the geometric schedule
`done(t) = totalSteps · (ramp^t − 1) / (ramp − 1)`, pinned to 0 at `t = 0` and `totalSteps` at `t = 1` for
every ramp — so no ramp can make a clip skip its seed or stop short, and `ramp = 1.0` is exactly the linear
schedule. At `ramp = 40` Gray–Scott's quarters read 10.4 / 35.6 / 50.0 / 4.0 % and half the change has
arrived at 51 % of the clip. `perPass` is found by scanning the schedule's own per-frame increments for the
largest, which keeps the two-clock guarantee for any monotone schedule.

**Do not measure pacing at the endpoints.** The obvious instrument — mean absolute difference between the
first, middle and last frames — *saturates* for anything that starts near black: Gray–Scott's
first-to-middle figure barely moved (42.09 → 42.40) across a fourfold change in pacing, because a
labyrinth and an empty field differ by about 42 however you got there. Consecutive-frame difference per
quarter is the number that moves.

The four prefix helpers each fix one specific animation failure rather than being general tidiness:
`Img.rowsRevealed` (a space-time diagram at generation *n*, at **full height**, so `montage` does not
rescale every frame differently), `rasterizeFLinesFramed` (framing taken from the finished curve, because
`rasterizeFLines` fits its raster to what it is handed and a growing prefix would rescale every frame),
`flinePrefix` (the turtle's path in the order the turtle walked it, so an L-system animates by *drawing*),
and `Aggregate.arrivalPrefixImg` (the colour ramp fixed to the full cluster's count, because
`arrivalOrderImg` normalises by its own and would re-map the newest particle to 1.0 every frame).

All four are the same principle in different coordinates, and it is the principle `alife_field.kt`'s range
policy states in colour: **a frame must never set its own scale.** The DLA clip's crop is the same rule in
geometry — taken once from the finished cluster, per axis rather than as a square around `maxRadius`
(a DLA cluster is anisotropic, and a square leaves a third of the panel empty forever).

## Running the verification

```bash
# the fast tier: 11 figures, ~6 s, every claim an exact identity or a closed-form comparison
python3 tools/run_field_tests.py --id stdlib.alife.gallery --port 952x --no-reap

# the slow tier: 7 figures, ~25 s, the statistical verdicts
python3 tools/run_field_tests.py --id stdlib.alife.gallery.slow --port 952x --no-reap

# both, plus every other smoke in the catalog
python3 tools/run_field_tests.py --port 952x --no-reap

# one figure while iterating (development only). Stage names are in AlifeGallery.STAGES;
# ALIFE_GALLERY_TIER=all is the default when the entrypoint is run without --tier.
ALIFE_GALLERY_ONLY=lenia,turing ALIFE_GALLERY_TIER=all \
  python3 tools/run_field_tests.py --id stdlib.alife.gallery.slow --port 952x

# render the figures somewhere that survives, and LOOK at them
ALIFE_GALLERY_DIR=/tmp/alife python3 tools/run_field_tests.py --id stdlib.alife.gallery --port 952x

# replay the loadable demo through the real engine (compile + run + assert no error output)
DEMO_TRANSCRIPT_ONLY=alife_worked_example AFIELD_DEMO_TRANSCRIPT_VERBOSE=alife_worked_example \
  python3 tools/run_field_tests.py --id examples.demo-transcript.exec --port 952x
```

`AlifeGallery` writes to a temp dir by default (the "tests: no committed writes" invariant) and to
`--out DIR` / a bare first argument / `$ALIFE_GALLERY_DIR` when told to. **Rendering the figures is not
verification — looking at them is.** Two rendering lies were caught only by looking: a saturated Lenia
field is constant at 1, so an auto-ranged colour map drew it *black* and indistinguishable from death
(the panels now use a fixed `0..1` range); and the mitosis filmstrip sampled every 300 steps showed five
identical frames of the aftermath rather than the division.

And one caveat about *how* to look, learned the hard way on the oracle figure: **decode the PNG and
sample its pixels**, do not judge a preview. A downscaled preview of the checkerboard panel showed an
obvious moiré and prompted a "fix"; the file itself had exactly two grey values, 0 and 255, in clean
10-pixel blocks. `montage` never upscales (`min(1.0, …)`), so an undersized panel is centred rather than
stretched and cannot alias. The moiré was in the viewer.

## Regenerating the docs

The public page's figures come from the docs capture pipeline (`diffstatic_runner` fences with
`capture="box"`), which does **not** re-derive captures from stdlib source on an incremental build — the
digest is keyed on a hand-list of runner files containing no stdlib code (see
`dev_docs/docs_capture_staleness.md`). So a green docs build is *not* evidence that the figures match
the code. To regenerate:

```bash
FIELD_DOCS_RUNNER_MODE=always uv run --group docs \
  python3 tools/build_zensical_docs.py --page alife_examples.md
```

Adding or editing the transcript also requires regenerating the committed starting-point bundle, or the
`AgentTranscriptMaterializer` drift guard fails the build:

```bash
python3 tools/field_backdoor.py run-jvm-main afield.examples.AgentTranscriptMaterializer \
  --arg "$PWD/public/docs/assets/agent_transcripts"
```

## Where this could go next

Stated as leads rather than promises, and roughly in order of value per unit of work:

- **GPU steppers.** Gray–Scott, Lenia and Physarum are all one ping-pong texture pass. The CPU versions
  here are reference implementations; a StackSL or `afield.four` compute version would make them
  interactive at 512² and the CPU version becomes the oracle it is checked against.
- **A `LeniaRule` from a published creature.** The right way to get Orbium is to load its array from a
  file the user supplies (or from a `.lenia` JSON), not to hardcode a remembered one. That would turn
  the phase trichotomy into "here is a soliton, and its measured velocity is `v`".
- **Reaction–diffusion on a mesh.** `mesh_topology.kt` already has the adjacency; a Laplacian over
  triangle neighbourhoods would put Turing patterns on a surface, which is where the biology actually
  happens.
- **Multi-species Lenia / Flow-Lenia.** A vector-valued field with a kernel per channel pair. The tap
  machinery generalises directly.
- **A history/animation bridge.** `GrayScottState.filmstrip` and `IntGrid.history` already produce frame
  lists; wiring those to the mp4 encoder the RectSL gallery uses would make the whole family animatable
  in docs.
