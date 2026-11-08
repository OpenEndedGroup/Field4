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
- No straight-line glider found. Next: larger n (the article's gliders), asymmetric seeds, or
  evolve parameters with `PopulationSearch` on a displacement objective.

## Not done

- Per-particle rules, 3-D variant, the article's glider, audio voice.
