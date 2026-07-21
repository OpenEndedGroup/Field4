# Nightly worklog — 2026-07-21 / agent 1 — DSP spectral family (kind A)

## Plan (written early)

The stdlib DSP toolkit (`src/jvmMain/kotlin/afield/stdlib/timeseries.kt`) is entirely
time-domain. This mission adds a self-contained **frequency-domain family** in a new file
`src/jvmMain/kotlin/afield/stdlib/spectral.kt`, additive only.

Deliverables:
1. **`spectral.kt`** (new, `afield.stdlib`):
   - Pure-Kotlin FFT: iterative Cooley–Tukey radix-2 (power-of-two) + Bluestein (chirp-z)
     fallback for arbitrary lengths. Forward + inverse (normalized).
   - Analysis windows: rectangular / Hann / Hamming / Blackman.
   - `Spectrum` type: re/im, magnitude, power, phase, bin→frequency (sample rate aware).
   - `Spectrogram` + `stft(...)` (framed / windowed / hop-size) over
     FloatArray / DoubleArray / List<Float>.
   - Spectral features: centroid, spread/bandwidth, rolloff, flux, flatness, RMS/energy.
   - Streaming `FloatFilter` versions (Float→Float) that compose with the timeseries chain:
     `RmsFilter`, `SpectralCentroidFilter`, `SpectralFluxFilter`, `SpectralRolloffFilter`.
   - Bonus if time: mel filter bank / log-freq, inverse-STFT overlap-add reconstruction.
2. **`SpectralTest.kt`** (jvmTest): sine→bin, round-trip inverse(forward(x))≈x, Parseval,
   hand-computed small DFT, feature sanity. Run module-scoped.
3. **Real PNG**: render a chirp spectrogram heatmap + centroid trace to a PNG in the
   worktree; eyeball it with the Read tool.
4. **Loadable demo**: `dev_docs/agents/transcripts/spectral_examples.md` transcript that
   builds a box computing + drawing a spectrum/spectrogram; replay/compile-check through
   the real engine; register in `DemoTranscriptExecSmokeTest`.
5. **Docs**: `public/docs/spectral_examples.md`, cross-linked from `timeseries_examples.md`.

Verification bar: not "it compiles" — unit tests green + an eyeballed PNG + a replayed/
compile-checked demo transcript.

## Changes (files)

- **`src/jvmMain/kotlin/afield/stdlib/spectral.kt`** (NEW, ~640 lines, additive) — the whole
  family. `FFT` (radix-2 + Bluestein, fwd/inv normalized); `WindowType` + `Windows`
  (rect/Hann/Hamming/Blackman + coherent gain); `Spectrum` (mag/power/phase/dB, bin→Hz, peak,
  feature shortcuts); `fft`/`ifft` overloads (Double/Float/List, windowed); `Spectrogram` + `stft`
  + `istft` (overlap-add); `SpectralFeatures` (centroid/spread/rolloff/flux/flatness/rms/energy);
  `Mel` (hz↔mel + triangular filter bank); streaming `FloatFilter`s
  (`SpectralCentroidFilter`/`SpectralRolloffFilter`/`SpectralFluxFilter`/`RmsFilter`, sample-and-hold
  ring-buffer, `then`-composable with timeseries.kt).
- **`src/jvmTest/kotlin/afield/stdlib/SpectralTest.kt`** (NEW) — 13 tests + two PNG renderers.
- **`src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt`** (1-line touch) — registered
  `spectral_examples` in the EXECUTE set.
- **`dev_docs/agents/transcripts/spectral_examples.yaml`** (NEW) — loadable demo.
- **`public/docs/spectral_examples.md`** (NEW) + cross-link added to `timeseries_examples.md` + nav
  entry in `public/zensical.yml`.
- **Captures**: `spectral_chirp_spectrogram.png`, `spectral_mel_spectrogram.png` (worktree root) +
  copies under `public/docs/assets/spectral_examples/`.
- Did **not** edit `timeseries.kt` (its `FloatFilter` interface is package-visible; nothing needed
  changing — purely additive as intended).

## Verification status

- **Unit tests: VERIFIED.** `./gradlew :jvmTest --tests 'afield.stdlib.SpectralTest'` — 13/13 PASS
  (full output read; no grep-down). Asserts: pure tone→bin 8; inverse∘forward≈id (1e-9); Parseval;
  hand-computed 4-point DFT; Bluestein==naive DFT for N∈{6,12,100} (1e-6); window shape; centroid
  ranks + lands near tone freq; flatness noise>tone; RMS of unit sine≈1/√2; spread wider for split
  tones; mel round-trip + bank energy concentration; inverse-STFT interior mean err <1e-3; streaming
  centroid rises with a chirp.
- **PNG captures: VERIFIED by eyeball (Read tool).** Chirp spectrogram shows a clean monotonic
  rising ridge + two steady tone lines (700/1800 Hz) + rising centroid trace. Mel-spectrogram shows
  two crossing chirps as an X, curved by the mel log warping. Both real output of the spectral code,
  not synthesized. (`df -h /` checked: 10Gi free, not disk-full — captures trustworthy.)
- **Loadable demo: VERIFIED — actually replayed through the REAL engine**, not compile-checked-only.
  `python3 tools/run_field_tests.py --id examples.demo-transcript.exec` → PASS (22.7s); log shows
  `[execute] PASS spectral_examples (applied=4, output lines=1)` — the box body ran through the real
  macro-expand + K2 compile + eval path with zero error diagnostics and run.success.
- **Full jvmMain compiles clean** (no warnings from spectral.kt after fixing one `!!`).

## Risks & unknowns

- The demo renders its spectrum as an inline **SVG via `display()`** (headless-safe, EXECUTE-class),
  not via the live `lines()`/FLine renderer path the timeseries docs use — a deliberate choice so it
  executes headless with no Window. The FLine graphics path was not exercised for spectral output.
- Docs PNGs are committed assets produced by the unit test, NOT regenerated by a `diffstatic_runner`
  fence at docs-build time (the heatmap doesn't go through the FLine capture pipeline). They are
  honest real captures but will not auto-refresh; a code change that alters output won't redraw them.
- Streaming `SpectralFrameFilter` recomputes a full FFT every hop on the calling thread — fine for
  control-rate use, not audio-rate hot loops. Not benchmarked.
- Not wired into `OpRegistry`/completion categories beyond the file-level `@CompletionCategory("Math")`.

## Recommendation

**MERGE.** Self-contained, additive (one trivial touch to a test file), no existing behavior changed.
Unit tests, two eyeballed real captures, and a genuinely-replayed demo transcript all green. Fills a
real gap (the stdlib had zero frequency-domain capability). Natural follow-ups if desired: an
FLine/`lines()`-native spectrogram capture through the docs `diffstatic_runner` pipeline, and
`OpRegistry`/completion wiring — neither blocks merge.
