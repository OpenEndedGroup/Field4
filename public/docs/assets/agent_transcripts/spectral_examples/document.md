# Field Document: spectral_examples

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. one spectrum (chirp + tones)

<!-- field-box id="spectral_one_spectrum" index="0" -->

### Narrative

From `code`

Synthesise a rising linear chirp + two steady tones, FFT it, draw the one-sided spectrum.

```edn [field-box id="spectral_one_spectrum" role="properties"]
{"id" "spectral_one_spectrum" "index" 0 "name" "one spectrum (chirp + tones)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "one spectrum (chirp + tones)" "_tombstone" false "frame" #afield/rect [80.0 80.0 700.0 320.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_one_spectrum_code_.kt" "lines" 32 "characters" 1155 "sha256" "bfd8527fb96230b683d680dc379c43a374a93efd47cc3674c88e6135529a58e5"}]}
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
{"id" "spectral_spectrogram" "index" 1 "name" "spectrogram of a chirp" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spectrogram of a chirp" "_tombstone" false "frame" #afield/rect [80.0 440.0 960.0 340.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_spectrogram_code_.kt" "lines" 60 "characters" 2408 "sha256" "bf478be4c991e3a36c417886801e2a8c9485fc2f11ea5e0572c162b0d7cf1805"}]}
```

```kotlin [field-property box="spectral_spectrogram" name="code" path="spectral_spectrogram_code_.kt"]
import afield.util.PngEncoder

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

// Cyan -> orange colormap (dark -> cyan mids -> orange peaks), returns ARGB.
fun cyanOrange(v: Double): Int {
    val x = v.coerceIn(0.0, 1.0)
    val (r, g, b) = if (x < 0.5) {
        val t = x / 0.5
        Triple(10 + t * (60 - 10), 14 + t * (190 - 14), 20 + t * (230 - 20))
    } else {
        val t = (x - 0.5) / 0.5
        Triple(60 + t * (255 - 60), 190 + t * (150 - 190), 230 + t * (50 - 230))
    }
    return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
}

val nf = spec.numFrames; val nb = spec.numBins
fun db(f: Int, b: Int) = 20.0 * log10((mags[f][b] / maxMag).coerceAtLeast(1e-4))
fun sample(fx: Double, fy: Double): Double {
    val x0 = fx.toInt(); val x1 = (x0 + 1).coerceAtMost(nf - 1)
    val y0 = fy.toInt(); val y1 = (y0 + 1).coerceAtMost(nb - 1)
    val tx = fx - x0; val ty = fy - y0
    return (db(x0, y0) * (1 - tx) + db(x1, y0) * tx) * (1 - ty) +
        (db(x0, y1) * (1 - tx) + db(x1, y1) * tx) * ty
}

val w = 1800; val h = 840
val px = IntArray(w * h)
for (xp in 0 until w) {
    val fx = xp.toDouble() / (w - 1) * (nf - 1)
    for (yp in 0 until h) {
        val fy = (h - 1 - yp).toDouble() / (h - 1) * (nb - 1)
        px[xp + yp * w] = cyanOrange((sample(fx, fy) + 80.0) / 80.0)
    }
}
for (f in 0 until spec.numFrames) {
    val xp = (f.toDouble() / (spec.numFrames - 1).coerceAtLeast(1) * (w - 1)).toInt()
    val yp = (h - 1 - (centroid[f] / nyquist).coerceIn(0.0, 1.0) * (h - 1)).toInt()
    for (dy in -1..1) { val yy = yp + dy; if (yy in 0 until h) px[xp + yy * w] = (0xFF shl 24) or 0xFFFFFF }
}

println("frames=${spec.numFrames} bins=${spec.numBins} centroid ${"%.0f".format(centroid.first())} -> ${"%.0f".format(centroid.last())} Hz")
display("main", PngEncoder.encode(px, w, h))   // display renders PNG bytes as an image
```

## 3. streaming spectral features

<!-- field-box id="spectral_streaming" index="2" -->

```edn [field-box id="spectral_streaming" role="properties"]
{"id" "spectral_streaming" "index" 2 "name" "streaming spectral features" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "streaming spectral features" "_tombstone" false "frame" #afield/rect [80.0 1540.0 780.0 340.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_streaming_code_.kt" "lines" 30 "characters" 1219 "sha256" "3d68032177ecde40aec99dbcbe9cc1229c5ee308bab7d26ac9935ff481177796"}]}
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
{"id" "spectral_mel" "index" 3 "name" "mel spectrogram (crossing chirps)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "mel spectrogram (crossing chirps)" "_tombstone" false "frame" #afield/rect [80.0 820.0 960.0 320.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_mel_code_.kt" "lines" 55 "characters" 2016 "sha256" "7052e80b866493e7dff75345ee2465d92a1e74e1fdf4c291c24b0084394b80e0"}]}
```

```kotlin [field-property box="spectral_mel" name="code" path="spectral_mel_code_.kt"]
import afield.util.PngEncoder

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

fun cyanOrange(v: Double): Int {
    val x = v.coerceIn(0.0, 1.0)
    val (r, g, b) = if (x < 0.5) {
        val t = x / 0.5
        Triple(10 + t * (60 - 10), 14 + t * (190 - 14), 20 + t * (230 - 20))
    } else {
        val t = (x - 0.5) / 0.5
        Triple(60 + t * (255 - 60), 190 + t * (150 - 190), 230 + t * (50 - 230))
    }
    return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
}

val nf = spec.numFrames
fun db(f: Int, b: Int) = 20.0 * log10((mel[f][b] / maxMel).coerceAtLeast(1e-4))
fun sample(fx: Double, fy: Double): Double {
    val x0 = fx.toInt(); val x1 = (x0 + 1).coerceAtMost(nf - 1)
    val y0 = fy.toInt(); val y1 = (y0 + 1).coerceAtMost(numBands - 1)
    val tx = fx - x0; val ty = fy - y0
    return (db(x0, y0) * (1 - tx) + db(x1, y0) * tx) * (1 - ty) +
        (db(x0, y1) * (1 - tx) + db(x1, y1) * tx) * ty
}

val w = 1800; val h = 720
val px = IntArray(w * h)
for (xp in 0 until w) {
    val fx = xp.toDouble() / (w - 1) * (nf - 1)
    for (yp in 0 until h) {
        val fy = (h - 1 - yp).toDouble() / (h - 1) * (numBands - 1)
        px[xp + yp * w] = cyanOrange((sample(fx, fy) + 80.0) / 80.0)
    }
}

println("frames=${spec.numFrames} bands=$numBands")
display("main", PngEncoder.encode(px, w, h))   // display renders PNG bytes as an image
```

## 5. resonator bank (no transform)

<!-- field-box id="spectral_resonator" index="4" -->

```edn [field-box id="spectral_resonator" role="properties"]
{"id" "spectral_resonator" "index" 4 "name" "resonator bank (no transform)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "resonator bank (no transform)" "_tombstone" false "frame" #afield/rect [80.0 1180.0 960.0 320.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_resonator_code_.kt" "lines" 67 "characters" 2497 "sha256" "464bb48405352eec9fa2ea92937fc96c9ff73a87c44c22ba0df444656c02160c"}]}
```

```kotlin [field-property box="spectral_resonator" name="code" path="spectral_resonator_code_.kt"]
import afield.four.audio.resonate
import afield.four.audio.resonatorAlphaHeuristics
import afield.util.PngEncoder

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

fun cyanOrange(v: Double): Int {
    val x = v.coerceIn(0.0, 1.0)
    val (r, g, b) = if (x < 0.5) {
        val t = x / 0.5
        Triple(10 + t * (60 - 10), 14 + t * (190 - 14), 20 + t * (230 - 20))
    } else {
        val t = (x - 0.5) / 0.5
        Triple(60 + t * (255 - 60), 190 + t * (150 - 190), 230 + t * (50 - 230))
    }
    return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
}

fun db(s: Int, kk: Int) = 20.0 * log10((ampAt(s, kk).toDouble() / maxA).coerceAtLeast(1e-4))
fun sample(fx: Double, fy: Double): Double {
    val x0 = fx.toInt(); val x1 = (x0 + 1).coerceAtMost(slices - 1)
    val y0 = fy.toInt(); val y1 = (y0 + 1).coerceAtMost(numRes - 1)
    val tx = fx - x0; val ty = fy - y0
    return (db(x0, y0) * (1 - tx) + db(x1, y0) * tx) * (1 - ty) +
        (db(x0, y1) * (1 - tx) + db(x1, y1) * tx) * ty
}

val w = 1800; val h = 720
val px = IntArray(w * h)
for (xp in 0 until w) {
    val fx = xp.toDouble() / (w - 1) * (slices - 1)
    for (yp in 0 until h) {
        val fy = (h - 1 - yp).toDouble() / (h - 1) * (numRes - 1)
        px[xp + yp * w] = cyanOrange((sample(fx, fy) + 80.0) / 80.0)
    }
}

println("resonators=$numRes slices=$slices freq ${freqs.first().toInt()} -> ${freqs.last().toInt()} Hz")
display("main", PngEncoder.encode(px, w, h))   // display renders PNG bytes as an image
```

