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
- **Behaviour after a cut.** ~300 steps after the strip's cut, an edge cell grows into a bud and then a
  second complete flower (docs strip, step 600). Training horizon is 64–96 steps, so nothing held the
  rule beyond that. Marc (2026-11-08): this is fine; describe it neutrally, don't train it away.
- **Push bindings ran twice per dispatch (fixed 2026-11-08).** Symptom: one `ComputeMesh` with
  `parity = { step % 2 }` and `seed = { step++ }` froze one step from the seed; readback showed grid 1
  never written. Cause: `forEachFanOutDispatch` (`FanOutDispatch.kt`) invoked every binding to look for
  `ParallelGeometryRef`s, then `Structure.writeToBuffer` invoked them again for the write. Side effects
  ran twice (step += 2 per dispatch) and only the second evaluation was pushed, so parity was constant.
  Minimal repro: a compute shader `pc.counts.c[pc.parity] += 1u` with `parity = { val p = step % 2;
  step++; p }`. Before the fix, 120 frames gave 244 evaluations and GPU counts `0 122`; with
  `(step / 2) % 2` they were `61 61`. After it, 122 evaluations and `61 61`. Fix: `withEvaluatedPush`
  evaluates once and sets `Structure.dispatchSnapshot`, which the write reuses. Unit test
  `PushBindingsOncePerDispatchTest` (negative control: fails at the step count with the snapshot
  ignored). Applies to `Mesh`, `MeshMesh` and `ComputeMesh`, which all go through `forEachFanOutDispatch`.
  The demo keeps two fixed-parity meshes with a barrier between them, for two steps a frame.
- `ComputeMesh()` with no args inside a local `fun` picks the primary constructor and leaves
  `inside` uninitialised (`lateinit property inside`); pass `ComputeMesh(renderer, module)`.
- A box with both `computeModuleHere` and `moduleHere` shares one preamble: shader struct names
  (`NcaState`) and push-constant member names (`size`, `cells`) must differ between the stages.
- The native window runs at thousands of fps; `native-screenshot --wait-frames` is not time.
  Stepping is gated by wall clock (`stepsPerSecond`) and captures used `sleep 0.5` between shots.
- sRGB swapchain: the fragment shader `pow(c, 2.2)` so window colours match the CPU PNG.
- GPU alive-masking is one step late: the post-update check is applied as the next step's pre-check
  (a single pass cannot see neighbours' updated alpha). Visually indistinguishable.
- **`macro run growing_nca` timeout (2026-11-08).** The first night's run returned `TimeoutError`
  under the backdoor's default `--timeout 20` (it bounds the whole macro). The next day, with no
  change to the transcript's operations, three fresh documents in fresh managed servers each finished
  (applied=11, errors=0, about 10 s of macro time), one of them at the default timeout. The failing
  run was the first after a rebuild, so the cold first compile of the boxes and shaders is the likely
  cause. If it recurs, pass `macro run growing_nca --timeout 120`.
