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

---
---

# Nightly worklog (part 2) — dough DSP *sound* page: `audio=` capture mode + plotly plots

Branch: `nightly/2026-07-25/5-dough-sound-page` (based on the gallery branch above).

## Goal (human intent)
Make a public *sound* page that (1) uses nicer/interactive dark-mode plots (the gallery plots are
hand-rendered AWT with the ugly `Font.SANS_SERIF` fallback), and (2) embeds **playable `.wav`** so a
reader can HEAR each primitive. The `.wav` must be produced by REAL DSP at docs-build and
auto-regenerate — never a committed/hand-rendered fake. This needs a real docs-runner extension: a new
`audio="assets/x.wav"` capture mode parallel to the existing `video="assets/x.mp4"` mode + an `<audio>`
embed.

## Investigation

### Docs runner / fence system
- `tools/discover_field_tests.py` only *parses* fences into a catalog. The real driver is
  **`public/docs_hooks.py`** (3527 lines), run during the Zensical build.
- `RunnerOptions` (frozen dataclass, single constructor `parse_runner_options`) holds `screenshot`,
  `video`, `duration`, `fps`, `capture`, `wait_output*`. Main loop (~1720–1849): parse → extract code →
  `capture=="ui"`→`ensure_ui_drive`; `elif screenshot`→`ensure_screenshot`; `if video`→`ensure_video`;
  then emit source + embed (`![](png)` for screenshot, raw `<video><source src=>` for video ~1838).
- `ensure_video/ensure_screenshot`→`build_backdoor_command`→ `tools/field_backdoor.py` against a shared
  Field JVM (runner starts `./gradlew runJvm`, port `FIELD_DOCS_RUNNER_PORT` default **9477**). Box
  pipeline: `new-box` → `run @last --property code --file -` (code piped on stdin) → `wait-output`/`sleep`
  → `screenshot-box`/`record-box`.
- **`wait-file <path> --newer-than <mtime> --fail-on-error-uid @last`** already exists (used for
  `source_image`): polls until a file appears + stable, fails fast on box error. Exactly what `audio=` needs.
- Cache: `options_digest_payload` is a manual whitelist — new option fields must be added there, guarded so
  other fences hash byte-identically.
- `build_zensical_docs.py --page PATTERN` runs capture only for matching pages (flag is `--page`, not
  `--only`); `FIELD_DOCS_RUNNER_MODE` = incremental|always|never.

### DoughDsp offline render (honest audio source)
- `DoughDspPlotGallery.kt` already offline-renders each primitive to a real `FloatArray` with no
  engine/GPU/browser (`signal.render(buf,BLOCK,ctx); ctx.advanceCycle()`).
- `Signal`: `render`, `through(SignalProcessor)`, `*`/`+` operators, `Param(base)` +
  `Param { supplier over SynthContext.time }` for sweeps; `PlateReverb : StereoNode`; `Gate.open/close`.
- **`afield.four.audio.WavFloatArrayWriter.writeMono16`** already writes correct mono 16-bit PCM WAV.
- `Box.showSignals(...)` (`afield/visualization/plotly.kt`) = dark-mode plotly line plot into a box output.
  `interpolants_examples.md` already screenshots a `showSignals` box via `diffstatic_runner
  screenshot=... capture="box"` — proven path that fixes the font. (Published as a screenshot of a real
  plotly render — the repo convention; not a page-embedded live plot.)

## Decision (incl. sanctioned pivot)
Generic "tap any box's realtime audio buffer" needs a JVM audio-tap through the browser pipeline. Per the
human's explicit permission I scope `audio=` to the **deterministic OFFLINE DoughDsp render** — REAL
compiled DSP at build time, auto-regenerating, no committed fake, no realtime device. Mechanism mirrors
`fabricate-scene`: the runner names a registered compiled render by id (`audio-spec=`), generates the box
code that offline-renders it and writes the WAV, then `wait-file`s + embeds `<audio>`.

Files:
1. NEW `src/jvmMain/kotlin/afield/four/synth/DoughDspSoundDocs.kt` — `SCENES` registry (real offline
   renders on meaningful test signals) + `renderWav(name,path)` (→ WavFloatArrayWriter) + `Box.plot(name)`
   (→ showSignals) + jvm-main FIELD_TEST asserting sample-level correctness + WAV header.
2. MOD `public/docs_hooks.py` — `audio`/`audio_spec` in RunnerOptions + parse + digest payload; `ensure_audio`
   (generate code → run box → wait-file → verify) + dispatch + `<audio>` embed.
3. MOD `tools/check_doc_assets.py` — add `.wav` to `ASSET_EXTS`.
4. NEW `public/docs/dough_dsp_sound.md` (+ `public/zensical.yml` nav) — per primitive: plotly plot
   (screenshot) + audio player.

## Verification plan
- `run_field_tests.py --id synth.dough-dsp.sound` (offline sample-level checks + WAV header).
- Docs build for just this page on a FREE port (NOT 9477 — a foreign JVM listens there):
  `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=<free> uv run --group docs python3
  tools/build_zensical_docs.py --page dough_dsp_sound` from the worktree; assert the server is MY worktree JVM.
- Read back each generated `.wav`: valid RIFF/WAVE header, non-empty, RMS/peak nonzero + expected envelope.
- Confirm plot screenshots regenerated.

## Implementation log — DONE

### Files changed (part 2)
- NEW `src/jvmMain/kotlin/afield/four/synth/DoughDspSoundDocs.kt` — `SCENES` registry + offline
  block-render helpers + `renderWav(name, path)` + top-level `Box.plotDoughSound(name)` +
  jvm-main FIELD_TEST `synth.dough-dsp.sound`.
- MOD `public/docs_hooks.py` — `audio`/`audio_spec` in `RunnerOptions` + `parse_runner_options` +
  `options_digest_payload` (guarded); `ensure_audio()` (cache/retry/restart like ensure_video);
  `build_audio_backdoor_command()` + `validate_wav_file()`; main-loop dispatch + `<audio>` embed.
- MOD `tools/check_doc_assets.py` — `.wav` in ASSET_EXTS + `audio=` in ATTR_REF.
- NEW `public/docs/dough_dsp_sound.md` (+ `public/zensical.yml` nav) — 6 primitives, each a Plotly
  plot (screenshot) + `<audio>` player + an illustrative code block.
- 12 committed assets `public/docs/assets/dough_dsp_sound/{plate-reverb,phaser,chamberlin-lowpass,
  curved-adsr,soft-distort,zaw-pulze}.{png,wav}`.

### Design note (the sanctioned pivot, as built)
`audio=` is a real new `diffstatic_runner` capture mode, but the "capture" is a deterministic
OFFLINE DSP render, not a browser audio tap: the runner generates box code
`DoughDspSoundDocs.renderWav("<scene>", "<abs wav>")`, runs it in the shared Field JVM, and
`wait-file`s the WAV (`--fail-on-error-uid` surfaces box errors fast). Real compiled DoughDsp, no
device, no committed fake, regenerates at docs-build. A second decision: the interactive plots are
`showSignals` Plotly renders screenshotted from a `capture="box"` box (the established repo
convention — a screenshot of a real Plotly figure, not a page-embedded live plot); this is what
fixes the ugly AWT `Font.SANS_SERIF` fallback.

### Two bugs found + fixed during verification
1. My scene verifies rejected the resonant ChamberlinLowpass's legitimate >1.0 pre-normalization
   overshoot (peak 2.54). Relaxed the raw-sample peak checks (writer normalizes to 0.9).
2. `plot(this, name)` did not type-check — a box script's `this` is the script object, not the
   `Box`. Refactored to a top-level `Box.plotDoughSound(name)` extension (auto-imported), resolved
   via the implicit Box receiver exactly like bare `showSignals(...)`.

### Verification (real output, not "it compiles")
- `run_field_tests.py --id synth.dough-dsp.sound --port 9489` → **PASS** (offline sample-level
  checks + on-disk WAV header/sample-rate assertions for all 6 scenes).
- Docs build: `FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9489 uv run --group docs
  python3 tools/build_zensical_docs.py --page dough_dsp_sound` → **BUILD_EXIT 0**, summary
  `regenerated=12 (screenshots=6 videos=6[audio counter reuse]), 0 failures`. Server confirmed to
  be THIS worktree's JVM (`-Dafield.aot.cache.path=…/2026-07-25-5-dough-sound-page/…`, classpath
  under the worktree) — not the foreign JVM squatting on 9477.
- WAV read-back (scratchpad/verify_wavs.py): all 6 mono/16-bit/48 kHz, valid RIFF/WAVE headers,
  non-silent. Envelope checks: plate-reverb tail decays 17× (early rms 0.301 → late 0.0175);
  chamberlin brightens 34× (HF ratio 0.007 → 0.241); curved-adsr = 3 notes with gaps; zaw-pulze
  autocorr = 1.000 at the 200 Hz (240-sample) period. peak = 0.900 on every file (normalized).
- Generated HTML `public/field4/dough_dsp_sound/index.html`: 6 `<audio controls>` players + 6 plot
  `<img>`s, paths zensical-rewritten to `../assets/dough_dsp_sound/…`. Assets copied into the built
  site. Plate-reverb plot eyeballed: real dark-mode cyan Plotly line, clean font.
- Cleanup: killed the worktree JVM on 9489; no survivors; left the foreign 9477 server alone.

### Risks & unknowns
- The `audio=` render reuses the `videos_run` counter (summary prints audio under "videos") — a
  cosmetic label only; no behavioural effect.
- `audio=` is intentionally specialized to registered `DoughDspSoundDocs` scenes (via `audio-spec`),
  not a generic "any box's realtime audio buffer" tap — that broader capture would need a JVM
  audio-tap through the browser pipeline and was out of scope for one session. The mechanism is
  cleanly extensible (add a `Scene` to `SCENES`).
- Not run: the full docs build / `--strict` asset gate across all pages (only `--page
  dough_dsp_sound`). The new .wav assets are committed, so the untracked-asset warning is resolved.

## Recommendation (part 2)
**MERGE.** A real docs-runner extension (`audio=` offline-render → validated WAV → `<audio>` embed),
a verified sound harness with regression coverage, and a public page whose plots and audio are both
produced by real DoughDsp at build time. Everything regenerates; nothing is faked. To hear/see it:
`FIELD_DOCS_RUNNER_PORT=<free> uv run --group docs python3 tools/build_zensical_docs.py --page
dough_dsp_sound`, then open `public/field4/dough_dsp_sound/index.html`.
