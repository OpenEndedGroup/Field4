# Field Document: spectral_examples

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. filter chains as layered curves

<!-- field-box id="spectral_filter_chains" index="0" -->

```edn [field-box id="spectral_filter_chains" role="properties"]
{"id" "spectral_filter_chains" "index" 0 "name" "filter chains as layered curves" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "filter chains as layered curves" "_tombstone" false "frame" #afield/rect [520.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_filter_chains_code_.kt" "lines" 29 "characters" 949 "sha256" "0bc3a33df9cc542d4a54882d593142c156b5b7c73e4023f7ba751b129f34a0e4"}]}
```

```kotlin [field-property box="spectral_filter_chains" name="code" path="spectral_filter_chains_code_.kt"]
val raw = mutableListOf<Float>()
val smooth = mutableListOf<Float>()
val filtered = mutableListOf<Float>()

val ema = EMAFilter(alpha = 0.08f)
val oneEuro = OneEuroFilter(minCutoff = 1.0f, beta = 0.02f, sampleRate = 120f)
val band = BandPassFilter(frequency = 5f, sampleRate = 120f, q = 0.8f)

for (i in 0 until 420) {
    val t = i / 120f
    val signal =
        (sin(2.0 * PI * 1.2 * t).toFloat() * 0.7f +
            sin(2.0 * PI * 4.7 * t + 0.3).toFloat() * 0.22f +
            (-0.12f + random().toFloat() * 0.24f))
    raw += signal
    val s = oneEuro(ema(signal))
    smooth += s
    filtered += band(s)
}

// One call puts all three streams on one axis — raw, the smoothed chain, and the band-passed
// component each get their own trace (dark theme + cyan/orange palette applied).
showSignals(
    "raw" to raw,
    "EMA + One-Euro (smoothed)" to smooth,
    "band-pass (5 Hz)" to filtered,
    channel = "main",
    xTitle = "sample",
)
```

## 2. one spectrum (chirp + tones)

<!-- field-box id="spectral_one_spectrum" index="1" -->

### Narrative

From `code`

Synthesise a rising linear chirp + two steady tones, FFT it, draw the one-sided spectrum.

```edn [field-box id="spectral_one_spectrum" role="properties"]
{"id" "spectral_one_spectrum" "index" 1 "name" "one spectrum (chirp + tones)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "one spectrum (chirp + tones)" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_one_spectrum_code_.kt" "lines" 37 "characters" 1189 "sha256" "947730db553f05567988a51025fd464ccfb9a86c5f01fbe80801ec2163d000af"}]}
```

```kotlin [field-property box="spectral_one_spectrum" name="code" path="spectral_one_spectrum_code_.kt"]
// Synthesise a rising linear chirp + two steady tones, FFT it, draw the one-sided spectrum.
val sampleRate = 8000.0
val n = 2048
val f0 = 300.0
val f1 = 3000.0
val dur = n / sampleRate
val k = (f1 - f0) / dur
val signal =
    DoubleArray(n) { i ->
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
    xTitle = "frequency (Hz)",
    yTitle = "magnitude",
    marks = listOf(
        "centroid ${"%.0f".format(centroid)} Hz" to centroid,
        "85% rolloff ${"%.0f".format(rolloff)} Hz" to rolloff,
    ),
)
println(
    "peak=${"%.0f".format(peak)}Hz centroid=${"%.0f".format(centroid)}Hz rolloff=${"%.0f".format(rolloff)}Hz bins=${mag.size}",
)
```

## 3. spectrogram of a chirp

<!-- field-box id="spectral_spectrogram" index="2" -->

```edn [field-box id="spectral_spectrogram" role="properties"]
{"id" "spectral_spectrogram" "index" 2 "name" "spectrogram of a chirp" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spectrogram of a chirp" "_tombstone" false "frame" #afield/rect [80.0 440.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_spectrogram_code_.kt" "lines" 46 "characters" 1418 "sha256" "d8bc19ca1957d4212289f090338f84c44646e9d650658ee8d30437e053885917"}]}
```

```kotlin [field-property box="spectral_spectrogram" name="code" path="spectral_spectrogram_code_.kt"]
val sampleRate = 8000.0
val n = 24000
val f0 = 300.0
val f1 = 3000.0
val dur = n / sampleRate
val k = (f1 - f0) / dur
val signal =
    DoubleArray(n) { i ->
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
val dB =
    Array(spec.numFrames) { f ->
        DoubleArray(spec.numBins) { b -> 20.0 * log10((mags[f][b] / maxMag).coerceAtLeast(1e-4)) }
    }
// Spectral centroid as a normalized (x, y) overlay: x = time fraction, y = fraction of Nyquist.
val trace =
    centroid.indices.map { f ->
        (f.toDouble() / (spec.numFrames - 1).coerceAtLeast(1)) to (centroid[f] / nyquist).coerceIn(0.0, 1.0)
    }

println(
    "frames=${spec.numFrames} bins=${spec.numBins} centroid ${"%.0f".format(centroid.first())} -> ${"%.0f".format(centroid.last())} Hz",
)
showHeatmap(
    dB,
    colormap = "cyanorange",
    width = 1800,
    height = 840,
    vmin = -80.0,
    vmax = 0.0,
    transpose = true,
    flipY = true,
    overlay = trace,
    overlayThickness = 3,
)
```

## 4. streaming spectral features

<!-- field-box id="spectral_streaming" index="3" -->

```edn [field-box id="spectral_streaming" role="properties"]
{"id" "spectral_streaming" "index" 3 "name" "streaming spectral features" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "streaming spectral features" "_tombstone" false "frame" #afield/rect [80.0 1540.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_streaming_code_.kt" "lines" 36 "characters" 1256 "sha256" "8936c339c570cfb3f26bd92fdd412b11362705a7bc67a475453a9c271387c3f4"}]}
```

```kotlin [field-property box="spectral_streaming" name="code" path="spectral_streaming_code_.kt"]
val sampleRate = 8000f
val n = 8000
val stream =
    FloatArray(n) { i ->
        val t = i / sampleRate
        val f = 200f + 1800f * (i.toFloat() / n)
        var s = 0.6f * sin(2.0 * PI * f * t).toFloat() + 0.25f * sin(2.0 * PI * 900.0 * t).toFloat()
        if (i == n / 3 || i == 2 * n / 3) s += 2.0f
        s
    }

val brightness = EMAFilter(0.1f) then SpectralCentroidFilter(fftSize = 1024, hopSize = 256, sampleRate = sampleRate)
val onset = SpectralFluxFilter(fftSize = 1024, hopSize = 256, sampleRate = sampleRate)
val level = RmsFilter(windowSize = 512)

val cTrace = FloatArray(n)
val fTrace = FloatArray(n)
val rTrace = FloatArray(n)
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
    xTitle = "sample",
    normalize = true,
)
println(
    "centroid ${"%.0f".format(cTrace[3000])} -> ${"%.0f".format(cTrace[n - 200])} Hz, flux peak ${"%.3f".format(fTrace.maxOrNull() ?: 0f)}",
)
```

## 5. distribution-aware event flags

<!-- field-box id="spectral_distribution_events" index="4" -->

```edn [field-box id="spectral_distribution_events" role="properties"]
{"id" "spectral_distribution_events" "index" 4 "name" "distribution-aware event flags" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "distribution-aware event flags" "_tombstone" false "frame" #afield/rect [520.0 440.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_distribution_events_code_.kt" "lines" 32 "characters" 972 "sha256" "61a7ff3344cb3bcf724ebc9d7c7e4244550ff5b62557e2b5ed563d35601f37fd"}]}
```

```kotlin [field-property box="spectral_distribution_events" name="code" path="spectral_distribution_events_code_.kt"]
val gammaEvents = GammaEventDetector(windowSize = 64, tailProbabilityThreshold = 0.03f, influence = 0.15f)
val betaEvents = BetaEventDetector(
    windowSize = 64,
    supportMin = -1f,
    supportMax = 1f,
    lowerTailThreshold = 0.04f,
    upperTailThreshold = 0.04f,
)

val raw = mutableListOf<Float>()
val eg = mutableListOf<Float>()
val eb = mutableListOf<Float>()

for (i in 0 until 360) {
    val t = i / 120f
    var x = (sin(2.0 * PI * 1.1 * t) * 0.55 + (-0.08 + random() * 0.16)).toFloat()
    if (i % 83 == 0) x += 0.95f
    if (i % 127 == 0) x -= 0.8f
    raw += x
    eg += gammaEvents(kotlin.math.abs(x))
    eb += betaEvents(x)
}

// The raw stream and both detectors' verdicts on one axis — the event traces flag their tail
// crossings (beta fires either way, ±1) over the raw motion, so which departures triggered is clear.
showSignals(
    "raw" to raw,
    "gamma events" to eg,
    "beta events" to eb,
    channel = "main",
    xTitle = "sample",
)
```

## 6. mel spectrogram (crossing chirps)

<!-- field-box id="spectral_mel" index="5" -->

```edn [field-box id="spectral_mel" role="properties"]
{"id" "spectral_mel" "index" 5 "name" "mel spectrogram (crossing chirps)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "mel spectrogram (crossing chirps)" "_tombstone" false "frame" #afield/rect [80.0 820.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_mel_code_.kt" "lines" 38 "characters" 1102 "sha256" "cff51422bea98e636dfdbea6ff8224fdf4dfbc4a1d2c338d0fc7221c4382204f"}]}
```

```kotlin [field-property box="spectral_mel" name="code" path="spectral_mel_code_.kt"]
val sampleRate = 8000.0
val n = 24000
val up0 = 200.0
val up1 = 3200.0
val dn0 = 3200.0
val dn1 = 200.0
val dur = n / sampleRate
val ku = (up1 - up0) / dur
val kd = (dn1 - dn0) / dur
val signal =
    DoubleArray(n) { i ->
        val t = i / sampleRate
        0.7 * sin(2.0 * PI * (up0 * t + 0.5 * ku * t * t)) + 0.7 * sin(2.0 * PI * (dn0 * t + 0.5 * kd * t * t))
    }

val spec = stft(signal, fftSize = 1024, hopSize = 256, window = WindowType.HANN, sampleRate = sampleRate)
val numBands = 48
val mel = Array(spec.numFrames) { Mel.filterBank(spec.magnitude(it), sampleRate, numBands, 0.0, sampleRate / 2.0) }
var maxMel = 1e-9
for (row in mel) for (v in row) if (v > maxMel) maxMel = v

// dB relative to the peak, as a [frame][band] matrix.
val dB =
    Array(spec.numFrames) { f ->
        DoubleArray(numBands) { b -> 20.0 * log10((mel[f][b] / maxMel).coerceAtLeast(1e-4)) }
    }

println("frames=${spec.numFrames} bands=$numBands")
showHeatmap(
    dB,
    colormap = "cyanorange",
    width = 1800,
    height = 720,
    vmin = -80.0,
    vmax = 0.0,
    transpose = true,
    flipY = true,
)
```

## 7. resonator bank (no transform)

<!-- field-box id="spectral_resonator" index="6" -->

```edn [field-box id="spectral_resonator" role="properties"]
{"id" "spectral_resonator" "index" 6 "name" "resonator bank (no transform)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "resonator bank (no transform)" "_tombstone" false "frame" #afield/rect [80.0 1180.0 310.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_resonator_code_.kt" "lines" 59 "characters" 1662 "sha256" "7d3d8070d602568af2a02e3c163174fd4f5c343ad1886db9b9913390fec7e46c"}]}
```

```kotlin [field-property box="spectral_resonator" name="code" path="spectral_resonator_code_.kt"]
import afield.audio.engine.resonate
import afield.audio.engine.resonatorAlphaHeuristics

val sampleRate = 8000f
val n = 24000
val f0 = 300.0
val f1 = 3000.0
val durS = n / sampleRate.toDouble()
val k = (f1 - f0) / durS
val signal =
    FloatArray(n) { i ->
        val t = i / sampleRate.toDouble()
        (0.9 * sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) +
                0.35 * sin(2.0 * PI * 700.0 * t) +
                0.30 * sin(2.0 * PI * 1800.0 * t))
            .toFloat()
    }

val numRes = 96
val minHz = 120.0
val maxHz = 3900.0
val freqs =
    FloatArray(numRes) { i ->
        kotlin.math.exp(ln(minHz) + (ln(maxHz) - ln(minHz)) * i / (numRes - 1)).toFloat()
    }
val alphas = resonatorAlphaHeuristics(freqs, sampleRate)
val hop = 256

val flat = resonate(signal, sampleRate, freqs, alphas, betas = alphas, hopLength = hop)
val slices = flat.size / (2 * numRes)
fun ampAt(s: Int, kk: Int): Float {
    val base = s * 2 * numRes
    val re = flat[base + kk]
    val im = flat[base + numRes + kk]
    return kotlin.math.sqrt(re * re + im * im)
}
var maxA = 1e-9f
for (s in 0 until slices) for (kk in 0 until numRes) {
    val a = ampAt(s, kk)
    if (a > maxA) maxA = a
}

// dB relative to the peak, as a [slice][resonator] matrix.
val dB =
    Array(slices) { s ->
        DoubleArray(numRes) { kk -> 20.0 * log10((ampAt(s, kk).toDouble() / maxA).coerceAtLeast(1e-4)) }
    }

println("resonators=$numRes slices=$slices freq ${freqs.first().toInt()} -> ${freqs.last().toInt()} Hz")
showHeatmap(
    dB,
    colormap = "cyanorange",
    width = 1800,
    height = 720,
    vmin = -80.0,
    vmax = 0.0,
    transpose = true,
    flipY = true,
)
```

