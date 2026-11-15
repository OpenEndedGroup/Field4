# Bonded-particle organisms (ALIEN-style) — design notes

After ALIEN by Christian Heinemann (https://github.com/chrxh/alien, 5.0-beta).

- Library: `src/jvmMain/kotlin/afield/simulation/agents/alife_bonded.kt` (`BondedWorld`, `genome`,
  `BondedClock`). CPU tests: `src/jvmFastTest/kotlin/afield/simulation/agents/BondedOrganismTest.kt` (8/8 pass under `./gradlew fastTest --tests afield.simulation.agents.BondedOrganismTest`).
- Demo: `dev_docs/agents/transcripts/bonded_organisms.yaml` (renderer, organisms [compute tab],
  draw, GPU against CPU). Docs: `public/docs/alife_examples.md` section 13.
- The shader lives only in the transcript's `compute` tab; `BondedWorld.step` is its CPU twin,
  cell for cell. The check box measured GPU-vs-CPU max distance 5.8e-5 after 600 steps with the swimming
  defaults (KosmicKrisp, Apple silicon, 2026-11-15; 2.2e-5 with the old ones).

## Findings

- **Swimming speed is set by the drag ratio and stroke size.** Old defaults (dragNormal 3,
  dragTangent 0.3, amplitude 0.25) swam 0.039 body lengths per stroke (12-segment worm, after a
  2-stroke warm-up, 20 strokes) and the ladder often folded flat (centreline bends to 154 deg).
  A CPU sweep (amplitude 0.25-0.55, waveStep 0.4-1.2, dragNormal 3-15, ratio 10-100) chose
  dragNormal 15, dragTangent 0.15, amplitude 0.4, waveStep 0.8: 0.26 BL/stroke for 12 segments,
  0.19 for 16, 0.12-0.22 for the L/R curvers, all bonds intact, bends ~15-20 deg per joint.
  Amplitude 0.5 folds the body at ratio <= 50; it survives at ratio 100, so 0.4 keeps margin.
  Over 60 time units the worm moves 83 (was 12.7); standing wave 1.4, isotropic 6e-7.
  In the demo (2 steps/frame, 16 s) the orange worm crosses ~33 units, about 3 body lengths.

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
matter, neighbour grid for repulsion (O(N²) now), wrap-around world (walls instead; the worms start apart, heading across).
