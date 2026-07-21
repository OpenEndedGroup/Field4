# Spectral examples

The [timeseries toolkit](timeseries_examples.md) works in the time domain: it smooths, gates, and
flags a stream sample by sample. Sometimes the question is not *what is the signal doing now* but
*what is it made of* — which frequencies carry the energy, how bright the timbre is, where a chirp
is heading. That is the frequency domain, and `afield.stdlib` now has a self-contained family for
it: a pure-Kotlin FFT, analysis windows, a `Spectrum`, a `Spectrogram` (STFT), and the classic
spectral features.

Nothing here needs an external DSP library or a GPU. The FFT is an iterative Cooley–Tukey radix-2
core with a Bluestein (chirp-z) fallback, so **any** length transforms exactly, not just powers of
two. Forward and inverse both round-trip. Every example below is a live box: it synthesises its own
signal, runs the transform, and paints the result into its own output panel — so the code you see is
exactly the code that drew the picture.

## One spectrum

Synthesise a rising chirp plus two steady tones, run a Hann-windowed FFT, and read the shape back
out. `fft(...)` returns a `Spectrum` that knows its sample rate, so bins map to real Hz and the
feature helpers return frequencies you can label.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spectral_examples/one_spectrum.png" wait="1.5" capture="box" open-outputs="true" padding="20"}
:::static
// Synthesise a rising linear chirp + two steady tones, FFT it, draw the one-sided spectrum.
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
val freqs = spec.frequencies()
val centroid = spec.centroid()       // magnitude-weighted mean frequency, Hz
val rolloff = spec.rolloff(0.85)     // freq below which 85% of the energy sits
val peak = spec.peakFrequency()      // dominant component, Hz

// Draw the magnitude curve as an SVG polyline across 0 Hz -> Nyquist.
val w = 640.0; val h = 240.0; val pad = 28.0
val nyq = sampleRate / 2.0
val maxMag = (mag.maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
fun px(freq: Double) = pad + (freq / nyq).coerceIn(0.0, 1.0) * (w - 2 * pad)
fun py(m: Double) = (h - pad) - (m / maxMag).coerceIn(0.0, 1.0) * (h - 2 * pad)

val poly = StringBuilder()
for (i in mag.indices) poly.append("${"%.1f".format(px(freqs[i]))},${"%.1f".format(py(mag[i].toDouble()))} ")
val cx = px(centroid); val rx = px(rolloff)
val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='${w.toInt()}' height='${h.toInt()}' " +
    "style='background:#0a0e14;border-radius:6px'>" +
    "<polyline points='$poly' fill='none' stroke='#7ad9ff' stroke-width='1.4'/>" +
    "<line x1='$cx' y1='$pad' x2='$cx' y2='${h - pad}' stroke='#ffe066' stroke-width='1.6'/>" +
    "<line x1='$rx' y1='$pad' x2='$rx' y2='${h - pad}' stroke='#ff9e4a' stroke-width='1.2' stroke-dasharray='4 3'/>" +
    "<text x='${cx + 4}' y='${pad + 12}' fill='#ffe066' font-family='sans-serif' font-size='11'>centroid ${"%.0f".format(centroid)} Hz</text>" +
    "<text x='${rx + 4}' y='${pad + 28}' fill='#ff9e4a' font-family='sans-serif' font-size='11'>85% rolloff ${"%.0f".format(rolloff)} Hz</text>" +
    "<text x='$pad' y='${h - 8}' fill='#8aa' font-family='sans-serif' font-size='11'>0 Hz</text>" +
    "<text x='${w - pad - 44}' y='${h - 8}' fill='#8aa' font-family='sans-serif' font-size='11'>${nyq.toInt()} Hz</text>" +
    "</svg>"

println("peak=${"%.0f".format(peak)}Hz centroid=${"%.0f".format(centroid)}Hz rolloff=${"%.0f".format(rolloff)}Hz bins=${mag.size}")
display("main", "<div style=\"padding:10px\">$svg</div>")
```

![](assets/spectral_examples/one_spectrum.png)

The one-sided magnitude curve carries a solid line at the spectral centroid, a dashed line at the
85% rolloff, and the tone/chirp structure laid out across 0 Hz → Nyquist.

## A spectrogram of a chirp

A single spectrum is one slice. An STFT frames the signal, windows each frame, hops forward, and
transforms — giving you a spectrogram: a time × frequency heatmap. The rising chirp becomes a
diagonal ridge, the steady tones stay as horizontal lines, and the per-frame **spectral centroid**
draws a bright trace climbing with the chirp. The heatmap is a plain `IntArray` of pixels encoded
with `afield.util.PngEncoder` (no AWT) and shown as a data-URI image in the box's output.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spectral_examples/spectrogram.png" wait="2.5" capture="box" open-outputs="true" padding="20"}
:::static
import afield.util.PngEncoder
import java.util.Base64

// --- signal: a rising linear chirp (300 -> 3000 Hz) plus two steady tones (700, 1800 Hz) ---
val sampleRate = 8000.0
val n = 24000                    // 3 s
val f0 = 300.0; val f1 = 3000.0
val dur = n / sampleRate
val k = (f1 - f0) / dur
val signal = DoubleArray(n) { i ->
    val t = i / sampleRate
    // Phase-integrated chirp so the instantaneous frequency f0 + k*t sweeps cleanly below Nyquist.
    0.9 * sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) +
        0.35 * sin(2.0 * PI * 700.0 * t) +
        0.30 * sin(2.0 * PI * 1800.0 * t)
}

// --- STFT -> magnitude heatmap + spectral-centroid trace ---
val spec = stft(signal, fftSize = 1024, hopSize = 256, window = WindowType.HANN, sampleRate = sampleRate)
val mags = spec.magnitudes()                       // [frame][bin]
val maxMag = spec.maxMagnitude().coerceAtLeast(1e-9)
val centroid = spec.centroidTrace()                // Hz per frame
val nyquist = sampleRate / 2.0

// viridis-ish colormap (dark blue -> teal -> green -> yellow), returns ARGB.
fun viridis(v: Double): Int {
    val x = v.coerceIn(0.0, 1.0)
    val r = (0.28 + x * (0.99 - 0.28) * (0.3 + 0.7 * x)).coerceIn(0.0, 1.0)
    val g = (x * 0.98).coerceIn(0.0, 1.0)
    val b = (0.33 + 0.6 * sin(PI * x) - 0.2 * x).coerceIn(0.0, 1.0)
    return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
}

val w = 900; val h = 420
val px = IntArray(w * h)
for (xp in 0 until w) {
    val frame = (xp.toLong() * (spec.numFrames - 1) / (w - 1).coerceAtLeast(1)).toInt()
    for (yp in 0 until h) {                          // y flipped: low frequency at the bottom
        val bin = ((h - 1 - yp).toLong() * (spec.numBins - 1) / (h - 1)).toInt()
        val db = 20.0 * log10((mags[frame][bin] / maxMag).coerceAtLeast(1e-4))
        px[xp + yp * w] = viridis((db + 80.0) / 80.0)
    }
}
// overlay the spectral-centroid trace as a bright line.
for (f in 0 until spec.numFrames) {
    val xp = (f.toDouble() / (spec.numFrames - 1).coerceAtLeast(1) * (w - 1)).toInt()
    val yp = (h - 1 - (centroid[f] / nyquist).coerceIn(0.0, 1.0) * (h - 1)).toInt()
    for (dy in -1..1) { val yy = yp + dy; if (yy in 0 until h) px[xp + yy * w] = (0xFF shl 24) or 0xFFF078 }
}

val b64 = Base64.getEncoder().encodeToString(PngEncoder.encode(px, w, h))
println("frames=${spec.numFrames} bins=${spec.numBins} centroid ${"%.0f".format(centroid.first())} -> ${"%.0f".format(centroid.last())} Hz")
display("main", "<img src='data:image/png;base64,$b64' style='width:100%;max-width:900px;border-radius:6px;display:block'/>")
```

![](assets/spectral_examples/spectrogram.png)

The diagonal ridge is the chirp, the two flat lines are the 700 Hz and 1800 Hz tones, and the thin
trace is the centroid climbing with the sweep. Everything is drawn by the code above — the STFT, the
viridis mapping (log-scaled to dB for a useful dynamic range), and the centroid overlay.

## Mel bands — a perceptual, log-frequency view

Linear frequency bins spend most of their resolution where the ear spends least of its attention.
The **mel scale** warps frequency logarithmically; `Mel.filterBank(...)` collapses a magnitude
spectrum into a handful of triangular mel bands. Applied per STFT frame it gives a mel-spectrogram.
Here the signal is **two crossing chirps** — one rising, one falling — so they trace an X, curved by
the log warping.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spectral_examples/mel_spectrogram.png" wait="2.5" capture="box" open-outputs="true" padding="20"}
:::static
import afield.util.PngEncoder
import java.util.Base64

// --- signal: two crossing chirps, one rising (200 -> 3200 Hz) and one falling (3200 -> 200 Hz) ---
val sampleRate = 8000.0
val n = 24000
val up0 = 200.0; val up1 = 3200.0
val dn0 = 3200.0; val dn1 = 200.0
val dur = n / sampleRate
val ku = (up1 - up0) / dur
val kd = (dn1 - dn0) / dur
val signal = DoubleArray(n) { i ->
    val t = i / sampleRate
    0.7 * sin(2.0 * PI * (up0 * t + 0.5 * ku * t * t)) +
        0.7 * sin(2.0 * PI * (dn0 * t + 0.5 * kd * t * t))
}

// --- STFT -> per-frame mel filter bank -> heatmap ---
val spec = stft(signal, fftSize = 1024, hopSize = 256, window = WindowType.HANN, sampleRate = sampleRate)
val numBands = 48
val mel = Array(spec.numFrames) { Mel.filterBank(spec.magnitude(it), sampleRate, numBands, 0.0, sampleRate / 2.0) }
var maxMel = 1e-9
for (row in mel) for (v in row) if (v > maxMel) maxMel = v

fun viridis(v: Double): Int {
    val x = v.coerceIn(0.0, 1.0)
    val r = (0.28 + x * (0.99 - 0.28) * (0.3 + 0.7 * x)).coerceIn(0.0, 1.0)
    val g = (x * 0.98).coerceIn(0.0, 1.0)
    val b = (0.33 + 0.6 * sin(PI * x) - 0.2 * x).coerceIn(0.0, 1.0)
    return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
}

val w = 900; val h = 360
val px = IntArray(w * h)
for (xp in 0 until w) {
    val frame = (xp.toLong() * (spec.numFrames - 1) / (w - 1).coerceAtLeast(1)).toInt()
    for (yp in 0 until h) {                          // y flipped: low mel band at the bottom
        val band = ((h - 1 - yp).toLong() * (numBands - 1) / (h - 1)).toInt()
        val db = 20.0 * log10((mel[frame][band] / maxMel).coerceAtLeast(1e-4))
        px[xp + yp * w] = viridis((db + 80.0) / 80.0)
    }
}

val b64 = Base64.getEncoder().encodeToString(PngEncoder.encode(px, w, h))
println("frames=${spec.numFrames} bands=$numBands")
display("main", "<img src='data:image/png;base64,$b64' style='width:100%;max-width:900px;border-radius:6px;display:block'/>")
```

![](assets/spectral_examples/mel_spectrogram.png)

## Streaming features that compose with the filter chain

The spectral features also come as `FloatFilter`s, so they slot straight into the same `then`
chain as the [time-domain filters](timeseries_examples.md). Feed one sample in, get the latest
feature out (held between hops). Here a rising chirp plus a couple of onsets drives three features at
once, and the three traces are drawn stacked in the box's output.

```diffstatic_runner {data-lang=kotlin screenshot="assets/spectral_examples/streaming_features.png" wait="2.0" capture="box" open-outputs="true" padding="20"}
:::static
// --- an input stream: a rising chirp (200 -> 2000 Hz) + a steady tone, with two onset bursts ---
val sampleRate = 8000f
val n = 8000                     // 1 s
val stream = FloatArray(n) { i ->
    val t = i / sampleRate
    val f = 200f + 1800f * (i.toFloat() / n)     // sweep 200 -> 2000 Hz
    var s = 0.6f * sin(2.0 * PI * f * t).toFloat() + 0.25f * sin(2.0 * PI * 900.0 * t).toFloat()
    if (i == n / 3 || i == 2 * n / 3) s += 2.0f   // two sharp onsets for the flux detector
    s
}

// The same `then` chain as the time-domain toolkit: smooth the centroid, track flux and level.
val brightness = EMAFilter(0.1f) then SpectralCentroidFilter(fftSize = 1024, hopSize = 256, sampleRate = sampleRate)
val onset = SpectralFluxFilter(fftSize = 1024, hopSize = 256, sampleRate = sampleRate)
val level = RmsFilter(windowSize = 512)

val cTrace = FloatArray(n); val fTrace = FloatArray(n); val rTrace = FloatArray(n)
for (i in 0 until n) {
    cTrace[i] = brightness(stream[i])   // spectral centroid, Hz
    fTrace[i] = onset(stream[i])        // spectral flux (onset novelty)
    rTrace[i] = level(stream[i])        // rolling RMS
}

// Draw the three traces stacked, each normalised into its own band.
val w = 720.0; val h = 300.0; val pad = 26.0
fun norm(v: Float, lo: Float, hi: Float) = ((v - lo) / (hi - lo).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
fun trace(vals: FloatArray, lo: Float, hi: Float, y0: Double, band: Double, color: String, label: String): String {
    val sb = StringBuilder()
    for (i in vals.indices) {
        val x = pad + i.toDouble() / (vals.size - 1) * (w - 2 * pad)
        val y = y0 - norm(vals[i], lo, hi) * band
        sb.append("${"%.1f".format(x)},${"%.1f".format(y)} ")
    }
    return "<polyline points='$sb' fill='none' stroke='$color' stroke-width='1.3'/>" +
        "<text x='$pad' y='${y0 - band - 4}' fill='$color' font-family='sans-serif' font-size='11'>$label</text>"
}

val cMax = cTrace.maxOrNull() ?: 1f; val fMax = fTrace.maxOrNull() ?: 1f; val rMax = rTrace.maxOrNull() ?: 1f
val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='${w.toInt()}' height='${h.toInt()}' style='background:#0a0e14;border-radius:6px'>" +
    trace(cTrace, 0f, cMax, 100.0, 74.0, "#7ad9ff", "spectral centroid (Hz)") +
    trace(fTrace, 0f, fMax, 195.0, 74.0, "#ff9e4a", "spectral flux (onset novelty)") +
    trace(rTrace, 0f, rMax, 290.0, 74.0, "#8fff9a", "rolling RMS (level)") +
    "</svg>"

println("centroid ${"%.0f".format(cTrace[3000])} -> ${"%.0f".format(cTrace[n - 200])} Hz, flux peak ${"%.3f".format(fMax)}")
display("main", "<div style=\"padding:10px\">$svg</div>")
```

![](assets/spectral_examples/streaming_features.png)

The smoothed centroid climbs with the chirp, the flux spikes at the two onsets, and the RMS tracks
the overall level. `SpectralCentroidFilter`, `SpectralRolloffFilter`, `SpectralFluxFilter`, and
`RmsFilter` all maintain their own ring buffer and recompute on each hop, so they behave like any
other per-sample filter in the toolkit.

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

The one-spectrum example is also a loadable transcript. Replay it into a fresh Field with the agent
backdoor and the box computes and draws its own spectrum with no other setup:

```
field_run_transcript dev_docs/agents/transcripts/spectral_examples.yaml
```

Then edit the signal, or swap `WindowType.HANN` for `HAMMING` / `BLACKMAN`, and re-run the box to
watch the spectrum change. For the time-domain companions, see [Timeseries examples](timeseries_examples.md).
