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
- Weights: `src/jvmMain/resources/afield/alife/nca_flower.json`, `nca_fish.json` (drawn with FLines on
  the page, `targetFromFLines` + `writeTarget`, trained with `--target-file`).
- Turning: `rotation` (one angle) and `rotationAt` (per cell) turn the Sobel pair as the article's
  Experiment 4 does; the shader takes `angle` (radians) as its last push constant. `rotateTarget`
  makes the target to score against. Shaped damage: `damage { x, y -> ... }`; `liveCells`,
  `strayAlpha` classify outcomes.
- `GrowingNcaTest` — grow/regrow thresholds; also holds the transcript's `compute` text to
  `computeShader()` (writes `build/growing_nca_compute.glsl` on mismatch — paste it in).
- Demo: `dev_docs/agents/transcripts/growing_nca.yaml` (COMPILE_ONLY in DemoTranscriptExecSmokeTest).
  Twelve boxes; two renderer boxes, so two native windows (flower, and fish turned 30°); the docs
  captures pick one with `capture-box`.

## Numbers (2026-11-08)
- flower, 5000 steps, batch 8, pool 1024, damage 3/batch, MPS: ~17 min (0.2 s/step), batch loss
  6e-5 at step 4900. Kotlin CPU: grown err 2.0e-5 at 300 steps (empty 0.054); after r=10 cut, 1.7e-4.
- ring (hollow annulus target), same settings: diverged, final loss 0.10. Not shipped. Untested whether
  a lower LR or no damage fixes it.
- CPU: 64x64 step ~ a few ms in the JVM; fine for live display at 2 steps/frame.

## Numbers: turning, damage, the fish (2026-11-08, CPU, 64 x 64, Random(1..4))
- **Sign.** With `Kx = cos θ Sx − sin θ Sy`, `Ky = sin θ Sx + cos θ Sy`, the flower turns
  *anticlockwise on screen* (y down) by θ. `rotateTarget` follows that; scored against the other
  sense, every off-axis angle looked like a failure (1e-2).
- **Error at 300 steps, four seeds.** 0/90/180°: 2.0e-5 (identical to upright: the turned filters
  are the upright ones relabelled, up to sign). 15°: ~6e-4, 30°: ~7.5e-4, 45°: ~9.5e-4, 60°: ~8e-4,
  135°: ~8.5e-4. The upright flower against the same turned targets: 7.6e-3 to 4e-2. The article
  gives no number for this experiment (a video only); Self-Organising Textures (2021) notes the
  turn can be per cell.
- **Long runs off-axis.** 30°: 7.5e-4 at 300, drifts to 1.7e-3 by 2,000 steps, then flat to 4,000.
  45°: 2.8e-3 by 2,000, and a bud appears around 3,600 (stray alpha 12 at 4,000).
- **Turning a grown flower** does not turn it: setting 30° or 90° on a grown upright flower
  disrupts it (error vs upright 1e-2) and within 400–1,600 steps it overgrows (stray alpha > 200,
  live cells 414 -> 780). Erasing all but the 3 x 3 at the seed then regrows it turned.
- **Per-cell angle.** Left 0° / right 90°: a column of stacked flowers the height of the grid
  (1,271 live cells at 1,000 steps). Down = outward (`atan2 - π/2`): flowers back to back, then
  three. A smooth twist (−45°..45° across) or a swirl (90° per 20 cells) grows one bent flower.
- **Damage sweep** (cut at 300, followed 1,600 steps, 3–6 seeds): small discs, the stem, and any
  half-plane except the bottom heal to 2e-5 within ~400 steps, as does "all but the 3 x 3 seed".
  The centre is the weak point: a disc r=12 at the centre never healed in place (48 runs: 6 seeds x cut
  times 50, 100, 150, 200, 300, 500, 1,000, 2,000; 35 stuck at 1e-2..4e-2, 13 grew a second
  growth); disc r=8 centre and square 16 heal in about a third of runs and otherwise stick near
  4e-3; the bottom half healed in 19 of 48 (else ~4.8e-3). Disc r=12 at the top, the page's strip
  cut: 15 of 48 healed, 20 stuck near 7e-4, 13 grew a second flower. No trend with cut time; the
  random update mask decides. Nothing died.
- **"Not regrown" is often "regrown elsewhere".** The page's sweep (cut at 300, seeds 1–4) scores
  each end state against the target shifted by up to 4 cells: after the r=12 centre disc, the
  runs that grew no second flower had regrown a whole flower 2–3 cells *up* (shift (-1, -2) and
  (-1, -3), error < 1e-4 against the moved target, 1e-2 against the unmoved). Plain MSE called it
  a failure. What is left of the flower after a centre cut decides where the new centre forms.
- **Fish.** 2,000 steps, LR drop at 1,200, MPS: 456 s (0.23 s/step), batch loss 1.3e-3 at the end
  (flower: 6e-5 at 5,000). Kotlin CPU: grown 1.6e-4 at 300 steps (empty 0.038), after a r=10 cut
  3.6e-4. Grows and regrows recognisably; the eye is the last detail to sharpen.
- CPU step: 1.2 ms for 64 x 64 in the JVM test.

## Traps
- **Behaviour after a cut.** ~300 steps after the strip's cut, an edge cell grows into a bud and then a
  second complete flower (docs strip, step 600). Training horizon is 64–96 steps, so nothing held the
  rule beyond that. Marc (2026-11-08): this is fine; describe it neutrally, don't train it away.
- **Push bindings must only read state.** Symptom: one `ComputeMesh` with `parity = { step % 2 }`
  and `seed = { step++ }` froze one step from the seed; readback showed grid 1 never written. A
  binding runs each time its block is written, which can be more than once per dispatch:
  `forEachFanOutDispatch` (`FanOutDispatch.kt`) reads every binding to look for
  `ParallelGeometryRef`s before `Structure.writeToBuffer` reads them again for the write. So `step++`
  inside a binding advanced by 2 per dispatch and parity never changed. Repro: a compute shader
  `pc.counts.c[pc.parity] += 1u` with `parity = { val p = step % 2; step++; p }` gave 244 evaluations
  and GPU counts `0 122` over 120 frames. The box now advances `step` and sets/clears `brush` in its
  `computeTask` lambdas and binds pure reads (`seed = { step }`), as `ShaderBmlSystem.render` does;
  `Structure.bind`'s KDoc states the rule. An engine change that cached binding results per dispatch
  was tried on this branch (84654ce6e) and reverted: it guaranteed something the API never promised.
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
