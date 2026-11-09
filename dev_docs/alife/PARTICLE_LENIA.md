# Particle Lenia — status and notes

Source: Mordvintsev, Niklasson, Randazzo, "Particle Lenia and the energy-based formulation",
Google Research 2022 (https://google-research.github.io/self-organising-systems/particle-lenia/,
demo https://znah.net/lenia/). Scout card 733d93885f7a.

## What exists (2026-11-08)

- `src/jvmMain/kotlin/afield/simulation/agents/alife_particle_lenia.kt`: `ParticleLeniaParams`
  (article defaults), `ParticleLenia` (O(n²) CPU, analytic ∇E, Euler), `sample`, `fieldImg`
  (E/U/G/R), `render` (dots over −E), `energyHistory`, `spread`, `angularVelocity`,
  `centroidSpeed`, `energyPlotImg`.
- `src/jvmTest/.../ParticleLeniaTest.kt`: analytic gradient vs finite differences (< 1e-5);
  default rule's mean energy falls by > 0.1 and stays bounded.
- `public/docs/particle_lenia.md` (linked from `alife_examples.md` §5), two real captures.
- Transcript `dev_docs/agents/transcripts/particle_lenia.yaml`.

- `src/jvmMain/kotlin/afield/simulation/physics/ShaderParticleLenia.kt`: GPU version over
  `GpuNeighbourGrid` (force pass + barrier + integrate pass, `substeps` per frame, kernel cut at
  `muK + 3 sigmaK`, cell must be >= cutoff). Smoke `four.particle-lenia.smoke`
  (`src/jvmMain/kotlin/afield/examples/four/ParticleLeniaSmoke.kt`): GPU vs CPU velocities, worst
  error 6.7e-4 = 1.6e-4 of max |v| (1600 particles, 8 clusters); mean E after ~300 steps
  −0.0359 GPU vs −0.0371 CPU. Native-window run of 6400 particles (64 clusters of 100, rotator
  rule, 20 substeps/frame) inspected by hand; clusters merge into larger creatures. No docs
  capture of the native window (it would need a hardware capture profile).

## Measurements

Default rule, 200 particles, 12-unit square, seed 1: mean particle energy 0.518 → −0.286 by step
3000, spread 4.87 → 6.19, settles into a ring of petals. Mean particle energy is not the total
energy and is not strictly monotone.

### Sweep for moving creatures (seed 1, square side 12·sqrt(n/200))

Metric: centroid displacement and population rotation (atan2 of summed cross/dot of
centred positions) over 2000-step windows; sweep 2 uses two windows (steps 3000–5000 and
8000–10000) and calls a creature moving only if both agree.

- Sweep 1, n = 200, 5000 steps: muG {0.5, 0.6, 0.7} × sigmaG {0.1, 0.15, 0.2} × wK {0.015, 0.022,
  0.03}, plus muK {3, 5} × sigmaK {0.5, 1.5} and cRep {0.3, 2}. Largest displacement 1.72 / 2000
  steps (cRep 0.3: later found to wander, not glide); largest rotation 0.35 (muK 5, sigmaK 0.5).
- Sweep 2: n {12, 25, 50, 100, 200} × muK {3, 4, 5} × sigmaK {0.4, 0.6} × cRep {0.3, 1}. n ≤ 50
  always static. Steady rotators (rad per window A / B):
  - n 100, muK 4, sigmaK 0.6, cRep 0.3: 0.516 / 0.549 (on the page; four-loop pinwheel; E −0.634,
    spread 3.08–3.09 from step 3000 to 12000; centroid speed 0.0052–0.0059/t along a curve)
  - n 100, muK 5, sigmaK 0.6, cRep 1.0: 0.324 / 0.322
  - n 200, muK 5, sigmaK 0.6, cRep 0.3: 0.237 / 0.251
- No straight-line glider found in either sweep (both stayed near the article's muG 0.6).

## Motion classification, the demo's zoo, the growth map, a searched glider (2026-11-08)

Source: `src/jvmMain/kotlin/afield/simulation/agents/alife_particle_lenia_motion.kt`; test
`ParticleLeniaMotionTest` (page rotator ROTATING at 0.0026 rad/t with rim > centroid speed; zoo
Tarbosaurolop GLIDING at 0.124 +- 0.005, Chungasto ROTATING, Bellusauropel STATIC; demo integrator
gives the same glider speed to 1e-3; parallel step bit-identical to serial; normalisedWK equals the
demo's w1). Page sections "Naming what a creature does", "The demo's creatures", "A map of the growth
curve", "Searching for a glider". Transcript boxes 4-7 in `transcripts/particle_lenia.yaml`.

- `ParticleLenia.measure(steps, every = 50)` -> `ParticleLeniaWindow` (displacement, path length,
  straightness, summed turn, heading drift between halves, spread start/end, mean E, groups at link
  muK + sigmaK, centroid track). `motion`: DISPERSING (groups > 1 or spread x1.25) > GLIDING (speed >=
  0.005, straightness >= 0.9, speed >= rim speed) > ROTATING (rim speed = |omega| x spread >= 0.005) >
  WANDERING (speed >= 0.005) > STATIC. An earlier rotation threshold in rad/t (0.001) let the page's
  rotator (centroid 0.0043, straightness 0.99) be one threshold away from GLIDING; the rim speed puts
  turning and travel in the same units.
- `ParticleLenia.inertia` (0 = Euler; 0.5 = the demo's `pos += 0.5(pos-prev) + 0.5 dt v`),
  `ParticleLeniaParams.normalisedWK` (the demo's calcNormCoef), parallel `step()` from 64 particles
  (bit-identical; energy summed in particle order).
- `ParticleLeniaZoo`: nine 2-D creatures from `znah.net/lenia/zoo.js` (19 in the file; two have
  coincident points and NaN in a numpy port, the rest skipped for size). Measured (2000 settle, 4000
  window): gliders Iguanmousauru 0.2823, Agilienster 0.1753, Tarbosaurolop 0.1254, Coelopasaurop
  0.0134, Sauropeltad 0.0113 (identical under inertia 0.5); rotators Orodromic, Nedocernosa (rim
  0.0051, borderline), Chungasto; Bellusauropel static. The demo also has a confining dish
  (radius 75) and cuts the kernel at muK +- 3 sigmaK; neither matters for these.
- Growth map (rotator kernel, n 100, seed 1, muG 0.3-0.8 x sigmaG 0.06-0.2, 3000 + 4000): 14 static,
  4 gliding (0.0057-0.0093), 2 rotating (page rotator at 0.6/0.15 rim 0.0081), 3 wandering, 1
  dispersing. 8.7 s for 24 cells via `leniaMotionMap` (parallelStream over cells).
- Glider search (`ParticleLeniaRuleGenome` + `particleLeniaGlideObjective`, n 40, seeds 1 and 2,
  3000 settle + two windows of 1500, zero on split or spread change > 2 %): budget 300, seed 5.
  random 0.1315 (muK 3.867 sK 1.293 muG 0.158 sG 0.096 cRep 1.017), evolve 0.1680 (4.366, 0.677,
  0.177, 0.103, 1.186). Held-out seeds 3-7, windows 2000-6000 / 6000-10000: both arms glide from 4 of
  5 (seed 5 static for both); evolution 0.103-0.169, heading drift <= 0.205 rad / 4000 steps.
  Rounding the rule to 3 places changes which creature some seeds assemble (rounded: 5 of 5 glide,
  seed 4 at 0.169 instead of 0.103) - the video uses the full-precision rule.
- What did not work, for the record: a one-seed, one-window objective at n 200 (fitness = speed x
  straightness over steps 2000-4000, spread growth <= 10 %) gave random 0.0112 / evolve 0.0067 at
  100 evals, both rules spreading clouds that were static on four held-out seeds; a two-window
  version at n 200 (120 evals, ~1.6 s each) the same. Evolution lost to random at n 200 in both;
  at n 40 with two seeds it won.
- Box code > 30 s on the runloop is interrupted by the watchdog: the search box uses
  `go { withContext(Dispatchers.Default) { ... } }`.

## Not done

- MAP-Elites zoo over (speed, rim speed); the growth map on the GPU with per-cluster rules (the
  shader's two spare floats per particle could carry a cluster index into a rule buffer);
  per-particle rules / two species; the 3-D variant (mushroom glider: create_params(4.3, 1.1, 0.23,
  0.06, 1.0, dim 3), 256 points, dt 0.2); the article's sonification.
