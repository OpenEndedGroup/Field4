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

(to be filled in as work proceeds)

## Verification status

(to be filled in)

## Risks & unknowns

(to be filled in)

## Recommendation

(to be filled in)
