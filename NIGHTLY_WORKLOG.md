# Nightly worklog — dough-dsp-gallery

**Branch:** `nightly/2026-07-25/2-dough-dsp-gallery` (based on `ec4ef430`)
**Kind:** B — feature / generalization
**Mission:** build the missing headless response-gallery / plot-capture harness for the
freshly-landed dough DSP primitives (`DoughDsp.kt`).

## Plan

Mirror `afield.examples.functions.FunctionsPlotGallery` (pure-CPU AWT `Graphics2D` →
`BufferedImage` → `ImageIO` PNG under `java.awt.headless=true`, registered as a FIELD_TEST,
renders to a temp dir in test mode / to a committed asset dir when a path is passed). Reuse the
smoke test's real CPU driving helpers (`renderMono`, `renderStereo`, `SynthContext`+`advanceCycle`,
`OneShotImpulse`, a ramp source, `Gate` scheduling). Every plotted point comes from actually
running the primitive — impulse in, sine sweep in, ramp in, gated envelope — never a synthesized
curve.

New file: `src/jvmMain/kotlin/afield/four/synth/DoughDspPlotGallery.kt`
- id `synth.dough-dsp.gallery`, runner `jvm-main`, entrypoint the object's `main`.
- Self-contained renderer copied from the proven FunctionsPlotGallery renderer (Series/PlotSpec/
  render/drawPolyline/drawLegend/autoYRange/niceTicks/fmt/paintedFraction), plus **log-x support**
  (filter magnitude responses need log frequency to read a resonant peak).
- Reusable `PLOTS: List<Plot>` list so future primitives are one append.

Confirmed API (from DoughDsp.kt + DoughDspSmokeTest.kt + Signal.kt):
- `Signal.render(out, n, ctx)` / `SignalProcessor.input` / `Signal.through(proc)`.
- `SynthContext(sampleRate: Float, block: Int)`, `ctx.advanceCycle()`.
- `StereoNode.render(outL, outR, n, ctx)`.
- Sources: `SineOsc(Param)`, `ZawOsc(Param)`, `PulzeOsc(Param freq, Param duty)`.
- Processors: `ChamberlinLowpass(cutoff, resonance)`, `Phaser(rate, depth, center, sweep, mix)`,
  `SoftDistort(amount, postGain)`.
- Stereo: `PlateReverb(source, ..., decay, mix, scaleToSampleRate)`.
- Envelope: `CurvedADSR(gate, attack, decay, sustain, release, attackCurve, decayCurve)` + `Gate`.

Plots (each real output):
1. `plate-reverb-impulse` — feed a one-shot impulse, plot per-bucket abs-peak decay envelope of L
   and R over ~1.5 s. Shows the tail decaying AND L≠R (decorrelation).
2. `phaser-swept-notch` — magnitude response (per-frequency sine in, output/input dB) with the LFO
   parked (rate=0) at three `center` settings → the notch sits at a different frequency in each,
   i.e. the swept notch's positions. log-x.
3. `chamberlin-lowpass` — magnitude response at a few cutoff/resonance settings; resonant peak
   near cutoff visible, roll-off above. log-x, dB.
4. `curved-adsr` — the CurvedADSR level trace under a gate schedule that retriggers mid-release,
   plus the gate, plus plain `ADSR` under the same gating for contrast (its retrigger jumps, ours
   does not). Linear time (ms).
5. `soft-distort-transfer` — input→output transfer curve for several `amount` values (ramp
   -3..3), the soft-clip S shape.
6. `naive-oscillators` — two periods of `ZawOsc` and `PulzeOsc` (a couple duty cycles), raw
   aliasing waveforms.

Asset dir: `dev_docs/assets/dough_dsp_gallery/` (committed captures).
Docs: a short note under `dev_docs/` explaining the captures + how to regenerate.

Verification: run `python3 tools/run_field_tests.py --id synth.dough-dsp.gallery --port <free>`
from the worktree, assert it exercised THIS worktree, then Read every PNG and eyeball correctness.

## Execution

New file `src/jvmMain/kotlin/afield/four/synth/DoughDspPlotGallery.kt` — object
`DoughDspPlotGallery`, FIELD_TEST `synth.dough-dsp.gallery` (runner jvm-main). Self-contained
renderer copied from the proven FunctionsPlotGallery renderer, plus log-x support for the filter
magnitude plots. Six reusable `PLOTS`, each with a `build` (real CPU driving) and a `verify`
(per-plot correctness). CPU drivers (`renderMono/renderStereo`, `ImpulseSource`,
`SequenceSource`, `magnitude`, `envelope`, `transfer`, `runEnvelope`) reuse the smoke test's
approach.

New doc `dev_docs/DOUGH_DSP_PLOT_GALLERY.md`; captures under
`dev_docs/assets/dough_dsp_gallery/` (6 PNGs, force-added — that tree is gitignored, like the
existing `functions_plot_gallery/` assets are tracked).

Commits:
- `02628e92` feat: the gallery + plan worklog
- `ed34001c` fix: correct SoftDistort bound + widen y-range (first run's only failing verify)
- `80485e73` docs: captures + dev note

## Verification — drove to pixels

Ran `python3 tools/run_field_tests.py --id synth.dough-dsp.gallery --port 9611` from the
worktree. First run FAILED on my own too-strict SoftDistort bound check (exit 1, real
worktree code — the compile error and the check both proved it was running THIS worktree's
freshly-built class, not a cross-worktree server). After the fix: **PASS** (1 passed, 0
failed, ~4.1 s). Regenerated committed captures with `DOUGH_DSP_PLOT_DIR=...` → PASS.

Read (eyeballed) all six PNGs:
- `dough-plate-reverb-impulse.png` — tail decays from ~0.14 to ~0.01 over 1.5 s; L (cyan) and
  R (orange) envelopes clearly diverge early and stay decorrelated. ✔
- `dough-phaser-swept-notch.png` — three notch positions (≈400/1200/3500 Hz), each showing the
  two cascaded dips; nulls bottom around −18 to −25 dB as documented. ✔
- `dough-chamberlin-lowpass.png` — flat setting rolls off past cutoff; res 0.95 @ 1 kHz and
  res 0.9 @ 4 kHz show sharp resonant peaks at their cutoffs. ✔
- `dough-curved-adsr.png` — 4 segments visible; CurvedADSR (cyan) retriggers smoothly from
  ~0.22 while plain ADSR (pink) drops to 0 first — the click contrast is unmistakable. ✔
- `dough-soft-distort-transfer.png` — clean S-curve family, harder knee with more drive, all
  monotonic, bounded. ✔
- `dough-naive-oscillators.png` — raw saw ramp −1..1 and two pulse duty cycles, correct. ✔

## Risks

Low. Capture harness only — no production/runtime code path changed; a failure can only break
the `synth.dough-dsp.gallery` test, never audio. The magnitude/envelope numbers depend on the
existing primitives (unchanged); the per-plot `verify` guards catch a regression. Minor: the
two tallest resonant peaks clip the top of the lowpass plot (self-oscillation-tall) — cosmetic,
the peak is still unmistakable.

## Recommendation

**MERGE.** Delivers the missing gallery exactly as specced, mirrors the established
FunctionsPlotGallery pattern, test is green, and all six captures were eyeballed and are
correct/meaningful. Asset paths a human can open:
`dev_docs/assets/dough_dsp_gallery/dough-{plate-reverb-impulse,phaser-swept-notch,chamberlin-lowpass,curved-adsr,soft-distort-transfer,naive-oscillators}.png`.
