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
