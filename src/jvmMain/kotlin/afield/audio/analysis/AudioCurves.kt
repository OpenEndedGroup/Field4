@file:afield.runtime.completion.CompletionCategory("Audio")

package afield.audio.analysis

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.fline
import afield.graphics.render.thickness
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import afield.ui.output.ProgressReporter
import afield.ui.output.withProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem

/**
 * Analysis settings for [audioCurves]; the same names and defaults as the `fieldaudio_curve` CLI.
 *
 * [fftSize] samples go into each spectrum and a new curve starts every [hopSize] samples. Each curve
 * holds the last [history] spectra, so it spans `history * hopSize / sampleRate` seconds of sound.
 * With [emaLongestSeconds] set, each curve instead holds [history] running averages of the spectrum
 * over timescales from one hop up to that many seconds: a long view of the whole recording at one
 * end and the present at the other.
 */
data class AudioCurveSettings(
    val fftSize: Int = 2048,
    val hopSize: Int = 512,
    val melBands: Int = 64,
    val history: Int = 128,
    val dimensions: Int = 2,
    /**
     * Exponent applied to each mel band's energy before the spectrum is scaled to unit mass. Raw
     * power (1.0) leaves a voice's lowest few bands holding most of the mass; 0.3, close to how
     * loudness grows with intensity, lets the whole spectrum count.
     */
    val compression: Double = 0.3,
    /**
     * Null for a rolling window of the last [history] spectra. A number makes each point an
     * exponential moving average of the spectrum instead, with [history] time constants spaced
     * geometrically from one hop up to this many seconds, longest first. Those slow points barely
     * move, so they hold the curve's frame still over the whole recording.
     */
    val emaLongestSeconds: Double? = null,
) {
    init {
        require(fftSize >= 4 && hopSize in 1..fftSize && melBands >= 2 && history >= 3 && dimensions in 2..3 &&
            compression > 0 && compression <= 4 && (emaLongestSeconds == null || emaLongestSeconds > 0)) {
            "invalid audio curve settings $this"
        }
    }

    /** A filename-safe tag, so files analysed with different settings never collide. */
    internal val tag get() = "fft$fftSize-hop$hopSize-mel$melBands-h$history-${dimensions}d-c$compression" + (emaLongestSeconds?.let { "-ema$it" } ?: "")
}

/**
 * Analyse [wav] into one rolling curve file per channel and open them, oldest channel first.
 *
 * This runs the `fieldaudio_curve` Python package from this checkout through `uv` (dependency group
 * `audio-curve`: NumPy and SciPy). The files land in [directory], named after the WAV and the
 * [settings], and are reused while they are newer than the WAV, so a box that calls this on every
 * run only pays for the analysis once. While an analysis runs, a progress bar under the calling box
 * shows how far it has got. Close the returned files when you are done with them.
 *
 * A long analysis blocks the box's run. To keep the box live, move it to a worker inside `go { }`.
 * The box travels with the coroutine, so the progress bar still draws under it, and stopping the
 * box interrupts the analysis:
 *
 * ```kotlin
 * import kotlinx.coroutines.Dispatchers
 * import kotlinx.coroutines.runInterruptible
 *
 * go {
 *     val curves = runInterruptible(Dispatchers.IO) { audioCurves(wav) }
 *     println("${curves.first().frameCount} frames")
 * }
 * ```
 */
fun audioCurves(
    wav: Path,
    settings: AudioCurveSettings = AudioCurveSettings(),
    directory: Path = Paths.get(System.getProperty("java.io.tmpdir"), "field-audio-curves"),
): List<AudioCurveFile> = withProgress(PROGRESS_BAR) { bar -> openAudioCurves(wav, settings, directory, bar) }

private const val PROGRESS_BAR = "audio curves"

private fun openAudioCurves(wav: Path, settings: AudioCurveSettings, directory: Path, bar: ProgressReporter): List<AudioCurveFile> {
    val source = wav.toAbsolutePath().normalize()
    require(Files.isRegularFile(source)) { "No such WAV file: $source" }
    val channels = AudioSystem.getAudioFileFormat(source.toFile()).format.channels
    Files.createDirectories(directory)
    val base = directory.resolve("${source.fileName.toString().substringBeforeLast('.')}.${settings.tag}.f4curve")
    val outputs = if (channels == 1) listOf(base)
        else (1..channels).map { base.resolveSibling(base.fileName.toString().replace(".f4curve", ".ch$it.f4curve")) }
    val modified = Files.getLastModifiedTime(source)
    if (outputs.any { !Files.isRegularFile(it) || Files.getLastModifiedTime(it) < modified }) runCurveAnalysis(source, base, settings, bar)
    return outputs.map { AudioCurveFile(it) }
}

/** Run the analyzer, drawing its progress on [bar] (a cache hit never gets here, so never draws). */
private fun runCurveAnalysis(wav: Path, output: Path, settings: AudioCurveSettings, bar: ProgressReporter) {
    val files = listOf(wav.toString(), output.toString(), "--progress")
    val ema = settings.emaLongestSeconds
    val onProgress = { done: Long, total: Long -> bar.update(done, total, "analysing ${wav.fileName}: $done/$total") }
    if (ema == null) runFieldaudioCurve("fieldaudio_curve", files + settings.cliArguments, onProgress)
    else runFieldaudioCurve(
        "fieldaudio_curve.ema",
        files + listOf(
            "--points", "${settings.history}", "--longest", "$ema",
            "--fft-size", "${settings.fftSize}", "--hop-size", "${settings.hopSize}", "--mel-bands", "${settings.melBands}",
            "--dimensions", "${settings.dimensions}", "--compression", "${settings.compression}",
        ),
        onProgress,
    )
}

private val AudioCurveSettings.cliArguments get() = listOf(
    "--fft-size", "$fftSize", "--hop-size", "$hopSize", "--mel-bands", "$melBands",
    "--history", "$history", "--dimensions", "$dimensions", "--compression", "$compression",
)

/**
 * Run a `fieldaudio_curve` module from this checkout through `uv`, failing with its output. Lines
 * it prints as `progress <done> <total>` go to [onProgress] as they arrive.
 */
private fun runFieldaudioCurve(module: String, arguments: List<String>, onProgress: ((Long, Long) -> Unit)? = null) {
    val checkout = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "python/fieldaudio_curve/curve.py").isFile }
        ?: error("python/fieldaudio_curve not found above ${System.getProperty("user.dir")}: run Field from its checkout")
    val uv = System.getenv("AFIELD_UV")?.trim()?.takeIf { it.isNotEmpty() }
        ?: (System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { Paths.get(it, "uv") } +
            listOf(Paths.get("/opt/homebrew/bin/uv"), Paths.get("/usr/local/bin/uv"), Paths.get(System.getProperty("user.home"), ".local", "bin", "uv")))
            .firstOrNull { Files.isExecutable(it) }?.toString()
        ?: error("audio curves need `uv` (https://docs.astral.sh/uv/); set AFIELD_UV to its path")
    val log = Files.createTempFile("field-audio-curve", ".log").toFile()
    val process = ProcessBuilder(
        listOf(uv, "run", "--project", checkout.path, "--group", "audio-curve", "--", "python", "-m", module) + arguments,
    ).directory(checkout).redirectErrorStream(true).apply {
        environment()["PYTHONPATH"] = File(checkout, "python").path
        environment()["OPENBLAS_NUM_THREADS"] = "1"
        environment().remove("VIRTUAL_ENV")
    }.start()
    // Copy the output to the log, picking out progress lines, on a thread of its own so the timeout
    // below still holds while the process is quiet.
    val reader = Thread({
        log.bufferedWriter().use { out ->
            process.inputStream.bufferedReader().forEachLine { line ->
                out.appendLine(line)
                val fields = line.split(' ')
                if (onProgress != null && fields.size == 3 && fields[0] == "progress") {
                    val done = fields[1].toLongOrNull()
                    val total = fields[2].toLongOrNull()
                    if (done != null && total != null) onProgress(done, total)
                }
            }
        }
    }, "fieldaudio_curve output").apply { isDaemon = true; start() }
    try {
        if (!process.waitFor(30, TimeUnit.MINUTES)) error("$module timed out; log: $log")
        reader.join(5_000)
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
    check(process.exitValue() == 0) { "$module failed (exit ${process.exitValue()}):\n${log.readText().takeLast(4000)}" }
}

/** One curve as [audioCurveStages] reports it: MDS coordinates as solved, and after alignment. */
@Serializable
data class AudioCurveStage(
    /** Centre of the newest analysis window, in seconds. */
    val time: Double,
    /** One row per spectrum, oldest first: the MDS solution before alignment. */
    val unaligned: List<List<Double>>,
    /** The same points after the similarity fit onto the previous curve: what the `.f4curve` holds. */
    val aligned: List<List<Double>>,
)

/**
 * Every stage behind the curve at one moment, from the analyzer itself: the mel spectra in its
 * window, the Wasserstein distances between them, and the MDS coordinates of that curve and the
 * one before it, before and after alignment. For explaining and checking the method; use
 * [audioCurves] to analyse a whole recording.
 */
@Serializable
data class AudioCurveStages(
    val sampleRate: Int, val fftSize: Int, val hopSize: Int, val melBands: Int, val history: Int, val dimensions: Int,
    val compression: Double,
    /** Centre frequency of each mel band, in Hz. */
    val melCentersHz: List<Double>,
    /** One unit-mass mel spectrum per analysis window, oldest first. All zeros means silence. */
    val masses: List<List<Double>>,
    /** Wasserstein-1 distance between every pair of [masses]: what MDS embeds. */
    val distances: List<List<Double>>,
    /** The previous curve and the current one, in that order. */
    val curves: List<AudioCurveStage>,
)

/** Run the analyzer on [channel] of [wav] up to [seconds] and report every stage of that curve. */
fun audioCurveStages(
    wav: Path,
    seconds: Double,
    settings: AudioCurveSettings = AudioCurveSettings(),
    channel: Int = 0,
): AudioCurveStages {
    val out = Files.createTempFile("field-audio-curve-stages", ".json")
    try {
        runFieldaudioCurve(
            "fieldaudio_curve.stages",
            listOf(wav.toAbsolutePath().toString(), out.toString(), "--time", "$seconds", "--channel", "$channel") + settings.cliArguments,
        )
        return stagesJson.decodeFromString(AudioCurveStages.serializer(), Files.readString(out))
    } finally {
        Files.deleteIfExists(out)
    }
}

private val stagesJson = Json { ignoreUnknownKeys = true }

/**
 * A robust half-width of everything this file draws: half the larger of the x and y spreads between
 * the 2nd and 98th percentiles of every valid point. Divide by it to bring a recording's curves to
 * about unit size; one value for the whole file keeps their drift and growth visible.
 */
fun AudioCurveFile.extent(): Double {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    val every = maxOf(1L, frameCount * history / 200_000)
    for (i in 0L until frameCount step every) {
        val f = frame(i)
        for (j in 0 until f.pointCount) {
            xs += f.coordinates[j * dimensions]
            ys += f.coordinates[j * dimensions + 1]
        }
    }
    if (xs.isEmpty()) return 1.0
    fun spread(v: ArrayList<Float>) = v.sorted().let { it[(it.size * 0.98).toInt().coerceAtMost(it.size - 1)] - it[(it.size * 0.02).toInt()] }
    return (maxOf(spread(xs), spread(ys)) / 2.0).takeIf { it > 0 } ?: 1.0
}

/**
 * This curve as FLines, oldest point first, in [bands] pieces that fade from faint (oldest) to
 * [color] (newest).
 *
 * Coordinates are multiplied by [scale]. A 3D file uses its third coordinate for z; a 2D file
 * spreads its points along z instead, from `-depth` (oldest) to 0 (newest), so in `threeLines`
 * the curve trails back through time.
 *
 * [centered] moves the curve so its middle is at the origin. A rolling curve's frame keeps its
 * orientation from one hop to the next but slowly slides, which over a recording can carry it out
 * of a fixed view; centring removes the slide and keeps the shape and its turning.
 */
fun AudioCurveFrame.flines(
    scale: Double = 1.0,
    depth: Double = 0.0,
    color: Vec4 = vec(0.39, 0.78, 0.86, 1.0),
    bands: Int = 8,
    centered: Boolean = false,
): List<FLine> {
    val n = pointCount
    if (n < 2) return emptyList()
    val middle = DoubleArray(dimensions) { d -> if (centered) (0 until n).sumOf { coordinates[it * dimensions + d].toDouble() } / n else 0.0 }
    fun point(j: Int) = vec(
        (coordinates[j * dimensions] - middle[0]) * scale,
        (coordinates[j * dimensions + 1] - middle[1]) * scale,
        if (dimensions == 3) (coordinates[j * dimensions + 2] - middle[2]) * scale else (j / (n - 1.0) - 1.0) * depth,
    )
    return (0 until bands).mapNotNull { b ->
        val from = b * (n - 1) / bands
        val to = (b + 1) * (n - 1) / bands
        if (to <= from) return@mapNotNull null
        val age = (b + 1.0) / bands
        fline {
            moveTo(point(from))
            for (j in from + 1..to) lineTo(point(j))
            this.color = vec(color.x, color.y, color.z, color.w * (0.08 + 0.92 * age * age))
            thickness = 0.8 + 1.8 * age
        }
    }
}

