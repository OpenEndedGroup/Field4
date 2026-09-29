# Field Document: spectral_examples

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. one spectrum (chirp + tones)

<!-- field-box id="spectral_one_spectrum" index="0" -->

### Narrative

From `code`

Synthesise a rising linear chirp + two steady tones, FFT it, draw the one-sided spectrum.

```edn [field-box id="spectral_one_spectrum" role="properties"]
{"id" "spectral_one_spectrum" "index" 0 "name" "one spectrum (chirp + tones)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "one spectrum (chirp + tones)" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_one_spectrum_code_.kt" "lines" 32 "characters" 1155 "sha256" "bfd8527fb96230b683d680dc379c43a374a93efd47cc3674c88e6135529a58e5"}]}
```

```kotlin [field-property box="spectral_one_spectrum" name="code" path="spectral_one_spectrum_code_.kt"]
// Synthesise a rising linear chirp + two steady tones, FFT it, draw the one-sided spectrum.
val sampleRate = 8000.0
val n = 2048
val f0 = 300.0; val f1 = 3000.0
val dur = n / sampleRate
val k = (f1 - f0) / dur
val signal = DoubleArray(n) { i ->
    val t = i / sampleRate
    0.9 * sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) +
        0.4 * sin(2.0 * PI * 700.0 * t) +
        0.3 * sin(2.0 * PI * 1800.0 * t)
}

val spec = fft(signal, WindowType.HANN, sampleRate)
val mag = spec.magnitudeOneSided()
val freqs = spec.frequencies()
val centroid = spec.centroid()
val rolloff = spec.rolloff(0.85)
val peak = spec.peakFrequency()

// One call puts the magnitude curve on screen vs real Hz, centroid + rolloff as dashed marks.
showSignals(
    "magnitude" to mag.toList(),
    x = freqs.toList(),
    channel = "main",
    xTitle = "frequency (Hz)", yTitle = "magnitude",
    marks = listOf(
        "centroid ${"%.0f".format(centroid)} Hz" to centroid,
        "85% rolloff ${"%.0f".format(rolloff)} Hz" to rolloff,
    ),
)
println("peak=${"%.0f".format(peak)}Hz centroid=${"%.0f".format(centroid)}Hz rolloff=${"%.0f".format(rolloff)}Hz bins=${mag.size}")
```

## 2. spectrogram of a chirp

<!-- field-box id="spectral_spectrogram" index="1" -->

```edn [field-box id="spectral_spectrogram" role="properties"]
{"id" "spectral_spectrogram" "index" 1 "name" "spectrogram of a chirp" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spectrogram of a chirp" "_tombstone" false "frame" #afield/rect [80.0 440.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_spectrogram_code_.kt" "lines" 30 "characters" 1325 "sha256" "a0d46a0fce786cece96b8591c65ccdbe6168e32793626440c14614a456bfcf1f"}]}
```

```kotlin [field-property box="spectral_spectrogram" name="code" path="spectral_spectrogram_code_.kt"]
val sampleRate = 8000.0
val n = 24000
val f0 = 300.0; val f1 = 3000.0
val dur = n / sampleRate
val k = (f1 - f0) / dur
val signal = DoubleArray(n) { i ->
    val t = i / sampleRate
    0.9 * sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) +
        0.35 * sin(2.0 * PI * 700.0 * t) +
        0.30 * sin(2.0 * PI * 1800.0 * t)
}

val spec = stft(signal, fftSize = 1024, hopSize = 256, window = WindowType.HANN, sampleRate = sampleRate)
val mags = spec.magnitudes()
val maxMag = spec.maxMagnitude().coerceAtLeast(1e-9)
val centroid = spec.centroidTrace()
val nyquist = sampleRate / 2.0

// dB relative to the peak, as a [frame][bin] matrix.
val dB = Array(spec.numFrames) { f ->
    DoubleArray(spec.numBins) { b -> 20.0 * log10((mags[f][b] / maxMag).coerceAtLeast(1e-4)) }
}
// Spectral centroid as a normalized (x, y) overlay: x = time fraction, y = fraction of Nyquist.
val trace = centroid.indices.map { f ->
    (f.toDouble() / (spec.numFrames - 1).coerceAtLeast(1)) to (centroid[f] / nyquist).coerceIn(0.0, 1.0)
}

println("frames=${spec.numFrames} bins=${spec.numBins} centroid ${"%.0f".format(centroid.first())} -> ${"%.0f".format(centroid.last())} Hz")
showHeatmap(dB, colormap = "cyanorange", width = 1800, height = 840, vmin = -80.0, vmax = 0.0,
    transpose = true, flipY = true, overlay = trace, overlayThickness = 3)
```

## 3. streaming spectral features

<!-- field-box id="spectral_streaming" index="2" -->

```edn [field-box id="spectral_streaming" role="properties"]
{"id" "spectral_streaming" "index" 2 "name" "streaming spectral features" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "streaming spectral features" "_tombstone" false "frame" #afield/rect [80.0 1540.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_streaming_code_.kt" "lines" 30 "characters" 1219 "sha256" "3d68032177ecde40aec99dbcbe9cc1229c5ee308bab7d26ac9935ff481177796"}]}
```

```kotlin [field-property box="spectral_streaming" name="code" path="spectral_streaming_code_.kt"]
val sampleRate = 8000f
val n = 8000
val stream = FloatArray(n) { i ->
    val t = i / sampleRate
    val f = 200f + 1800f * (i.toFloat() / n)
    var s = 0.6f * sin(2.0 * PI * f * t).toFloat() + 0.25f * sin(2.0 * PI * 900.0 * t).toFloat()
    if (i == n / 3 || i == 2 * n / 3) s += 2.0f
    s
}

val brightness = EMAFilter(0.1f) then SpectralCentroidFilter(fftSize = 1024, hopSize = 256, sampleRate = sampleRate)
val onset = SpectralFluxFilter(fftSize = 1024, hopSize = 256, sampleRate = sampleRate)
val level = RmsFilter(windowSize = 512)

val cTrace = FloatArray(n); val fTrace = FloatArray(n); val rTrace = FloatArray(n)
for (i in 0 until n) {
    cTrace[i] = brightness(stream[i])
    fTrace[i] = onset(stream[i])
    rTrace[i] = level(stream[i])
}

// Different scales (Hz vs flux vs 0..1 level): normalize each to compare shape, one call.
showSignals(
    "spectral centroid (Hz)" to cTrace.toList(),
    "spectral flux (onset novelty)" to fTrace.toList(),
    "rolling RMS (level)" to rTrace.toList(),
    channel = "main",
    xTitle = "sample", normalize = true,
)
println("centroid ${"%.0f".format(cTrace[3000])} -> ${"%.0f".format(cTrace[n - 200])} Hz, flux peak ${"%.3f".format(fTrace.maxOrNull() ?: 0f)}")
```

## 4. mel spectrogram (crossing chirps)

<!-- field-box id="spectral_mel" index="3" -->

```edn [field-box id="spectral_mel" role="properties"]
{"id" "spectral_mel" "index" 3 "name" "mel spectrogram (crossing chirps)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "mel spectrogram (crossing chirps)" "_tombstone" false "frame" #afield/rect [80.0 820.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_mel_code_.kt" "lines" 27 "characters" 1053 "sha256" "e7d28152ef16413d6bd94d5b35b03f4fecc685de783197b2d5fc814a04c3ca5a"}]}
```

```kotlin [field-property box="spectral_mel" name="code" path="spectral_mel_code_.kt"]
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

val spec = stft(signal, fftSize = 1024, hopSize = 256, window = WindowType.HANN, sampleRate = sampleRate)
val numBands = 48
val mel = Array(spec.numFrames) { Mel.filterBank(spec.magnitude(it), sampleRate, numBands, 0.0, sampleRate / 2.0) }
var maxMel = 1e-9
for (row in mel) for (v in row) if (v > maxMel) maxMel = v

// dB relative to the peak, as a [frame][band] matrix.
val dB = Array(spec.numFrames) { f ->
    DoubleArray(numBands) { b -> 20.0 * log10((mel[f][b] / maxMel).coerceAtLeast(1e-4)) }
}

println("frames=${spec.numFrames} bands=$numBands")
showHeatmap(dB, colormap = "cyanorange", width = 1800, height = 720, vmin = -80.0, vmax = 0.0,
    transpose = true, flipY = true)
```

## 5. resonator bank (no transform)

<!-- field-box id="spectral_resonator" index="4" -->

```edn [field-box id="spectral_resonator" role="properties"]
{"id" "spectral_resonator" "index" 4 "name" "resonator bank (no transform)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "resonator bank (no transform)" "_tombstone" false "frame" #afield/rect [80.0 1180.0 310.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_resonator_code_.kt" "lines" 41 "characters" 1550 "sha256" "35f796728c7428a28503c902b97743af1abe54f61b14103a4ef8bc92160b329e"}]}
```

```kotlin [field-property box="spectral_resonator" name="code" path="spectral_resonator_code_.kt"]
import afield.four.audio.resonate
import afield.four.audio.resonatorAlphaHeuristics

val sampleRate = 8000f
val n = 24000
val f0 = 300.0; val f1 = 3000.0
val durS = n / sampleRate.toDouble()
val k = (f1 - f0) / durS
val signal = FloatArray(n) { i ->
    val t = i / sampleRate.toDouble()
    (0.9 * sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) +
        0.35 * sin(2.0 * PI * 700.0 * t) +
        0.30 * sin(2.0 * PI * 1800.0 * t)).toFloat()
}

val numRes = 96
val minHz = 120.0; val maxHz = 3900.0
val freqs = FloatArray(numRes) { i ->
    kotlin.math.exp(ln(minHz) + (ln(maxHz) - ln(minHz)) * i / (numRes - 1)).toFloat()
}
val alphas = resonatorAlphaHeuristics(freqs, sampleRate)
val hop = 256

val flat = resonate(signal, sampleRate, freqs, alphas, betas = alphas, hopLength = hop)
val slices = flat.size / (2 * numRes)
fun ampAt(s: Int, kk: Int): Float {
    val base = s * 2 * numRes
    val re = flat[base + kk]; val im = flat[base + numRes + kk]
    return kotlin.math.sqrt(re * re + im * im)
}
var maxA = 1e-9f
for (s in 0 until slices) for (kk in 0 until numRes) { val a = ampAt(s, kk); if (a > maxA) maxA = a }

// dB relative to the peak, as a [slice][resonator] matrix.
val dB = Array(slices) { s ->
    DoubleArray(numRes) { kk -> 20.0 * log10((ampAt(s, kk).toDouble() / maxA).coerceAtLeast(1e-4)) }
}

println("resonators=$numRes slices=$slices freq ${freqs.first().toInt()} -> ${freqs.last().toInt()} Hz")
showHeatmap(dB, colormap = "cyanorange", width = 1800, height = 720, vmin = -80.0, vmax = 0.0,
    transpose = true, flipY = true)
```

