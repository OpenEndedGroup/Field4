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

## Measurements

Default rule, 200 particles, 12-unit square, seed 1: mean particle energy 0.518 → −0.286 by step
3000, spread 4.87 → 6.19, settles into a ring of petals. Probe over n ∈ {50, 100, 200, 400},
seeds 1–2, 5000 steps: every run near-stationary (|rotation| ≤ 7e-4 rad/t, drift ≤ 0.01/t).
The article's rotators/gliders were **not** reproduced; they need other parameters. Note: mean
particle energy is not the total energy and is not strictly monotone.

## Not done

- GPU version (`GpuNeighbourGrid` / `ShaderBoids` patterns) for thousands of particles.
- Per-particle rules, 3-D variant, gliders from the article's other parameter sets, audio voice.
