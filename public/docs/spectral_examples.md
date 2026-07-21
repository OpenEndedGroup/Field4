# Spectral examples

The [timeseries toolkit](timeseries_examples.md) works in the time domain: it smooths, gates, and
flags a stream sample by sample. Sometimes the question is not *what is the signal doing now* but
*what is it made of* — which frequencies carry the energy, how bright the timbre is, where a chirp
is heading. That is the frequency domain, and `afield.stdlib` now has a self-contained family for
it: a pure-Kotlin FFT, analysis windows, a `Spectrum`, a `Spectrogram` (STFT), and the classic
spectral features.

Nothing here needs an external DSP library or a GPU. The FFT is an iterative Cooley–Tukey radix-2
core with a Bluestein (chirp-z) fallback, so **any** length transforms exactly, not just powers of
two. Forward and inverse both round-trip.

## One spectrum

Synthesise a rising chirp plus two steady tones, run a Hann-windowed FFT, and read the shape back
out. `fft(...)` returns a `Spectrum` that knows its sample rate, so bins map to real Hz and the
feature helpers return frequencies you can label.

```kotlin
val sampleRate = 8000.0
val n = 2048
val f0 = 300.0; val f1 = 3000.0
val dur = n / sampleRate
val k = (f1 - f0) / dur
val signal = DoubleArray(n) { i ->
    val t = i / sampleRate
    // Rising linear chirp (phase-integrated so instantaneous freq sweeps cleanly) + two tones.
    0.9 * sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) +
        0.4 * sin(2.0 * PI * 700.0 * t) +
        0.3 * sin(2.0 * PI * 1800.0 * t)
}

val spec = fft(signal, WindowType.HANN, sampleRate)
val mag = spec.magnitudeOneSided()   // bins 0 .. N/2
val centroid = spec.centroid()       // magnitude-weighted mean frequency, Hz
val rolloff = spec.rolloff(0.85)     // freq below which 85% of the energy sits
val peak = spec.peakFrequency()      // dominant component, Hz
```

The `spectral_examples` demo transcript (below) draws exactly this: the one-sided magnitude curve,
a solid line at the spectral centroid, a dashed line at the 85% rolloff, and the tone/chirp
structure laid out across 0 Hz → Nyquist.

## A spectrogram of a chirp

A single spectrum is one slice. An STFT frames the signal, windows each frame, hops forward, and
transforms — giving you a spectrogram: a time × frequency heatmap. The rising chirp becomes a
diagonal ridge, the steady tones stay as horizontal lines, and the per-frame **spectral centroid**
draws a bright trace climbing with the chirp.

```kotlin
val spec = stft(signal, fftSize = 1024, hopSize = 256, window = WindowType.HANN, sampleRate = 8000.0)
val heat = spec.magnitudes()         // [frame][bin] one-sided magnitude
val centroidHz = spec.centroidTrace()
```

![](assets/spectral_examples/spectrogram.png)

That capture is rendered by the `afield.stdlib.SpectralTest` unit test, which drives a real STFT of
a chirp + two tones and paints the magnitude heatmap (viridis, log-scaled) with the centroid trace
overlaid — the diagonal ridge is the chirp, the two flat lines are the 700 Hz and 1800 Hz tones,
and the thin trace is the centroid climbing with the sweep.

## Streaming features that compose with the filter chain

The spectral features also come as `FloatFilter`s, so they slot straight into the same `then`
chain as the [time-domain filters](timeseries_examples.md). Feed one sample in, get the latest
feature out (held between hops):

```kotlin
val brightness = EMAFilter(0.1f) then SpectralCentroidFilter(fftSize = 1024, hopSize = 256, sampleRate = 44100f)
val onset = SpectralFluxFilter(fftSize = 1024, hopSize = 256, sampleRate = 44100f)
val level = RmsFilter(windowSize = 512)

for (sample in stream) {
    val c = brightness(sample)   // spectral centroid, Hz
    val f = onset(sample)        // spectral flux (onset novelty)
    val r = level(sample)        // rolling RMS
}
```

`SpectralCentroidFilter`, `SpectralRolloffFilter`, `SpectralFluxFilter`, and `RmsFilter` all
maintain their own ring buffer and recompute on each hop, so they behave like any other
per-sample filter in the toolkit.

## What else is in the family

- **FFT** — `fft(...)` / `ifft(...)` over `DoubleArray`, `FloatArray`, or `List<Float>`; forward and
  inverse; power-of-two (radix-2) or arbitrary length (Bluestein).
- **Windows** — `WindowType.RECTANGULAR / HANN / HAMMING / BLACKMAN`, via `Windows.make(...)`.
- **`Spectrum`** — magnitude / power / phase / dB, `binFrequency`, `peakFrequency`, and the feature
  shortcuts `centroid()`, `spread()`, `rolloff()`, `flatness()`.
- **`Spectrogram`** — `stft(...)`, `magnitudes()`, `centroidTrace()` / `rolloffTrace()` /
  `fluxTrace()`, plus `istft(...)` for overlap-add reconstruction back to a signal.
- **Features** — `SpectralFeatures.centroid / spread / rolloff / flux / flatness / rms / energy`.
- **Mel** — `Mel.hzToMel`, `Mel.melToHz`, and a triangular `Mel.filterBank(...)` for perceptual /
  log-frequency band energies.

## Replay the demo

The whole spectrum example is a loadable transcript. Replay it into a fresh Field with the agent
backdoor and the box computes and draws its own spectrum with no other setup:

```
field_run_transcript dev_docs/agents/transcripts/spectral_examples.yaml
```

Then edit the signal, or swap `WindowType.HANN` for `HAMMING` / `BLACKMAN`, and re-run the box to
watch the spectrum change. For the time-domain companions, see [Timeseries examples](timeseries_examples.md).
