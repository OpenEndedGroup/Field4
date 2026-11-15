# Bonded-particle organisms (ALIEN-style) — design notes

After ALIEN by Christian Heinemann (https://github.com/chrxh/alien, 5.0-beta).

- Library: `src/jvmMain/kotlin/afield/simulation/agents/alife_bonded.kt` (`BondedWorld`, `genome`,
  `BondedClock`). CPU tests: `src/jvmFastTest/kotlin/afield/simulation/agents/BondedOrganismTest.kt` (7/7 pass under `./gradlew fastTest --tests afield.simulation.agents.BondedOrganismTest`).
- Demo: `dev_docs/agents/transcripts/bonded_organisms.yaml` (renderer, organisms [compute tab],
  draw, GPU against CPU). Docs: `public/docs/alife_examples.md` section 13.
- The shader lives only in the transcript's `compute` tab; `BondedWorld.step` is its CPU twin,
  cell for cell. The check box measured GPU-vs-CPU max distance 2.2e-5 after 600 steps (KosmicKrisp,
  Apple silicon, 2026-11-15).

## Findings

- **Drag tensor must come from the body axis only.** Averaging `u uᵀ` over all bonds (rungs and
  braces included) makes resistive-force drag nearly isotropic and the worm does not swim. Bonds
  carry a `dragAxis` weight: 1 for rails, 0 for rungs/braces. With it a 12-segment worm swims 12.7
  units in 60 time units against 1.3 (standing wave) and 1e-6 (isotropic drag).
- **ComputeMesh push-constant lambdas run more than once per dispatch** (measured twice with a
  counter lambda). A lambda that advances a step counter per call skips steps; with ping-pong
  parity that froze the world. `BondedClock.tick(renderer.frameCounter)` advances once per frame.
- **A negative Int into a `uint` push member is refused** (`Not writing tick ...`) and the previous
  value stays in the push buffer, so it cannot be used as a freeze flag. `tick` is a float; -1
  freezes.
- Setting a box's `compute` property through the backdoor `set` does not reload a running
  `computeModuleHere`; re-launch the box.
- `frames {}` must be the box's return value and the box must be *launched*, not `run`.

## Not done
Sensors, constructor cells that grow at runtime, food, creature signal → audio, drawing-layer
matter, neighbour grid for repulsion (O(N²) now), a docs-runner capture (stills are hand captures
from real runs).
