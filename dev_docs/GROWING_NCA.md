# Growing neural cellular automata — implementation notes

User page: `public/docs/growing_nca.md`. Source: Mordvintsev et al., Distill 2020
(https://distill.pub/2020/growing-ca, CC-BY 4.0). Further work: https://cells2pixels.github.io/.

## Pieces
- `tools/nca/train_growing_nca.py` — PyTorch trainer (`uv run --project . --group pen ...`; torch is in
  the `pen` group). Procedural targets (`flower`, `ring`); no PIL, stdlib PNG writer. Writes JSON
  `w1` (128x48), `b1`, `w2` (16x128), `targetRgba`, loss history.
- `afield.simulation.automata.GrowingNca` (`growing_nca.kt`) — CPU reference over `MlpWeights`
  (output bias zero-filled), `damage`, `argb/png`, `loss`, `computeShader()` (wraps `MlpWeights.glsl`).
  `WindowBrush` — keyed, re-run-safe left-button brush over a native `Window`.
- Weights: `src/jvmMain/resources/afield/alife/nca_flower.json`.
- `GrowingNcaTest` — grow/regrow thresholds; also holds the transcript's `compute` text to
  `computeShader()` (writes `build/growing_nca_compute.glsl` on mismatch — paste it in).
- Demo: `dev_docs/agents/transcripts/growing_nca.yaml` (COMPILE_ONLY in DemoTranscriptExecSmokeTest).

## Numbers (2026-11-08)
- flower, 5000 steps, batch 8, pool 1024, damage 3/batch, MPS: ~17 min (0.2 s/step), batch loss
  6e-5 at step 4900. Kotlin CPU: grown err 2.0e-5 at 300 steps (empty 0.054); after r=10 cut, 1.7e-4.
- ring (hollow annulus target), same settings: diverged, final loss 0.10. Not shipped. Untested whether
  a lower LR or no damage fixes it.
- CPU: 64x64 step ~ a few ms in the JVM; fine for live display at 2 steps/frame.

## Traps
- **Long-horizon buds.** After a cut, at ~300 steps past it, a stray cell can grow a second flower
  (visible in the docs strip, step 600). Training horizon is 64–96 steps; article fix = longer/more
  damage training. Not done.
- **GPU ping-pong via push constants did not work.** One `ComputeMesh` with
  `src/dst = { if (step%2==0) A else B }` (buffer-reference push constants), and later one buffer +
  `parity = { step % 2 }` uint push constant: lambdas were invoked every dispatch (counters
  advanced), but readback showed the second grid never written — every dispatch behaved as
  parity 1 / the first-bound pointers. Root cause in `ComputeMesh`/`Structure.writeToBuffer` not
  found (IteratedSystem binds a changing `frame` uint and appears to work — so suspect
  something specific; worth a minimal repro). Workaround shipped: two meshes with constant
  `parity`, `computeBarrier()` between, `computeBarrier(graphics = true)` after.
- `ComputeMesh()` with no args inside a local `fun` picks the primary constructor and leaves
  `inside` uninitialised (`lateinit property inside`); pass `ComputeMesh(renderer, module)`.
- A box with both `computeModuleHere` and `moduleHere` shares one preamble: shader struct names
  (`NcaState`) and push-constant member names (`size`, `cells`) must differ between the stages.
- The native window runs at thousands of fps; `native-screenshot --wait-frames` is not time.
  Stepping is gated by wall clock (`stepsPerSecond`) and captures used `sleep 0.5` between shots.
- sRGB swapchain: the fragment shader `pow(c, 2.2)` so window colours match the CPU PNG.
- GPU alive-masking is one step late: the post-update check is applied as the next step's pre-check
  (a single pass cannot see neighbours' updated alpha). Visually indistinguishable.
