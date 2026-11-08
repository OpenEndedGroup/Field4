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
    /**
     * Null for a rolling window. A number makes the curve a self-organizing map instead: [history]
     * prototype spectra on a chain that spread out over the kinds of sound heard so far, plus the
     * present spectrum as one more point. Each hop the prototype nearest the present, and its
     * neighbours along the chain, move toward it with this time constant in seconds; prototypes
     * that stop being matched keep what they learned. Cannot be combined with [emaLongestSeconds].
     */
    val somLearnSeconds: Double? = null,
    /**
     * With [somLearnSeconds], the grid the prototypes sit on: null for a chain of [history], or two
     * or three sizes for a sheet (`listOf(12, 12)`) or a lattice (`listOf(6, 6, 6)`), whose
     * neighbours in every direction stay similar. A grid replaces [history].
     */
    val somGrid: List<Int>? = null,
) {
    init {
        require(fftSize >= 4 && hopSize in 1..fftSize && melBands >= 2 && history >= 3 && dimensions in 2..3 &&
            compression > 0 && compression <= 4 && (emaLongestSeconds == null || emaLongestSeconds > 0) &&
            (somLearnSeconds == null || somLearnSeconds > 0) && (emaLongestSeconds == null || somLearnSeconds == null) &&
            (somGrid == null || (somLearnSeconds != null && somGrid.size in 1..3 && somGrid.all { it >= 1 } && somGrid.fold(1) { a, b -> a * b } >= 3))) {
            "invalid audio curve settings $this"
        }
    }

    /** A filename-safe tag, so files analysed with different settings never collide. */
    internal val tag get() = "fft$fftSize-hop$hopSize-mel$melBands-h$history-${dimensions}d-c$compression" + (emaLongestSeconds?.let { "-ema$it" } ?: "") + (somLearnSeconds?.let { "-som$it" } ?: "") + (somGrid?.let { "-g" + it.joinToString("x") } ?: "")
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
    fun named(base: Path) = if (channels == 1) listOf(base)
        else (1..channels).map { base.resolveSibling(base.fileName.toString().replace(".f4curve", ".ch$it.f4curve")) }
    val base = directory.resolve("${source.fileName.toString().substringBeforeLast('.')}.${settings.tag}.f4curve")
    val outputs = named(base)
    val modified = Files.getLastModifiedTime(source)
    if (outputs.any { !Files.isRegularFile(it) || Files.getLastModifiedTime(it) < modified }) {
        // Analyse under a temporary name and move the files into place only when the analysis has
        // finished, so an interrupted run never leaves a half-written file that looks cached.
        val partial = directory.resolve(".partial-${java.util.UUID.randomUUID()}.${base.fileName}")
        val partials = named(partial)
        try {
            runCurveAnalysis(source, partial, settings, bar)
            partials.zip(outputs).forEach { (from, to) ->
                Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            partials.forEach { Files.deleteIfExists(it) }
        }
    }
    return outputs.map { AudioCurveFile(it) }
}

/** Run the analyzer, drawing its progress on [bar] (a cache hit never gets here, so never draws). */
private fun runCurveAnalysis(wav: Path, output: Path, settings: AudioCurveSettings, bar: ProgressReporter) {
    val files = listOf(wav.toString(), output.toString(), "--progress")
    val ema = settings.emaLongestSeconds
    val som = settings.somLearnSeconds
    val onProgress = { done: Long, total: Long -> bar.update(done, total, "analysing ${wav.fileName}: $done/$total") }
    if (som != null) runFieldaudioCurve(
        "fieldaudio_curve.som",
        files + listOf(
            "--learn", "$som",
            *(settings.somGrid?.let { arrayOf("--grid", it.joinToString("x")) } ?: arrayOf("--nodes", "${settings.history}")),
            "--fft-size", "${settings.fftSize}", "--hop-size", "${settings.hopSize}", "--mel-bands", "${settings.melBands}",
            "--dimensions", "${settings.dimensions}", "--compression", "${settings.compression}",
        ),
        onProgress,
    )
    else if (ema == null) runFieldaudioCurve("fieldaudio_curve", files + settings.cliArguments, onProgress)
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
 * How spread out this curve is: the root-mean-square distance of its points from their middle, in
 * the curve's own units. For a map frame only the prototypes count, not the present. A map's
 * prototypes move slowly, so `flines(60 / frame.spread(), centered = true)` keeps a map filling the
 * same space from one hop to the next even as the whole recording's [AudioCurveFile.extent] would not.
 */
fun AudioCurveFrame.spread(): Double {
    val counted = if (bestMatch != null) pointCount - 1 else pointCount
    if (counted < 1) return 1.0
    val middle = DoubleArray(dimensions) { d -> (0 until counted).sumOf { coordinates[it * dimensions + d].toDouble() } / counted }
    val sum = (0 until counted).sumOf { j -> (0 until dimensions).sumOf { d -> val v = coordinates[j * dimensions + d] - middle[d]; v * v } }
    return kotlin.math.sqrt(sum / counted).takeIf { it > 0 } ?: 1.0
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
 *
 * A map frame (one with a [AudioCurveFrame.bestMatch]) draws differently: its grid of prototypes
 * (a chain, a sheet's rows and columns, a lattice's edges in all three directions) glows around the
 * best match and fades with distance from it on the grid, and a bright tether runs from the best
 * match to the present sound. [bands] is unused there; a 2D chain spreads its prototypes along z in
 * chain order, and [centered] centres on the prototypes alone. A sheet or lattice is coloured by grid
 * position (each axis drives one colour channel, so folds show), unless [gridColors] is false.
 * [trail] draws a fading line through the prototypes the present matched recently, oldest first,
 * as returned by [AudioCurveFile.recentMatches]: the path the sound took across the map.
 *
 * [stretch] scales each axis so the points spread equally along all of them (keeping their overall
 * [spread]). MDS puts most of a recording's variation on its first axis, so a sheet or lattice drawn
 * to scale is nearly flat; stretched, its folds show, at the cost of true proportions.
 */
fun AudioCurveFrame.flines(
    scale: Double = 1.0,
    depth: Double = 0.0,
    color: Vec4 = vec(0.39, 0.78, 0.86, 1.0),
    bands: Int = 8,
    centered: Boolean = false,
    trail: List<Int> = emptyList(),
    gridColors: Boolean = true,
    stretch: Boolean = false,
): List<FLine> {
    val n = pointCount
    if (n < 2) return emptyList()
    val match = bestMatch
    // The middle of a map is the middle of its prototypes: the present can sit far outside them.
    val counted = if (match != null) n - 1 else n
    val middle = DoubleArray(dimensions) { d -> if (centered) (0 until counted).sumOf { coordinates[it * dimensions + d].toDouble() } / counted else 0.0 }
    // Per-axis factors on top of [scale]: 1 unless [stretch] evens out the axes.
    val axis = DoubleArray(dimensions) { 1.0 }
    if (stretch) {
        val overall = spread() / kotlin.math.sqrt(dimensions.toDouble())
        for (d in 0 until dimensions) {
            val m = (0 until counted).sumOf { coordinates[it * dimensions + d].toDouble() } / counted
            val rms = kotlin.math.sqrt((0 until counted).sumOf { val v = coordinates[it * dimensions + d] - m; v * v } / counted)
            if (rms > 0) axis[d] = overall / rms
        }
    }
    val sx = scale * axis[0]
    val sy = scale * axis[1]
    val sz = scale * (if (dimensions == 3) axis[2] else 1.0)
    if (match != null) {
        val nodes = n - 1
        val grid = somGrid ?: listOf(nodes)
        val chain = grid.size == 1
        fun node(j: Int) = vec(
            (coordinates[j * dimensions] - middle[0]) * sx,
            (coordinates[j * dimensions + 1] - middle[1]) * sy,
            if (dimensions == 3) (coordinates[j * dimensions + 2] - middle[2]) * sz
            else if (chain) (j / (n - 1.0) - 1.0) * depth else 0.0,
        )
        // Grid coordinates of prototype j (last axis fastest), and the stride of each axis.
        val strides = IntArray(grid.size) { a -> grid.drop(a + 1).fold(1) { x, y -> x * y } }
        fun cell(j: Int) = IntArray(grid.size) { a -> (j / strides[a]) % grid[a] }
        val at = cell(match)
        val glowWidth = maxOf(1.5, grid.max() / 6.0)
        val edges = ArrayList<Pair<Int, Int>>()
        for (j in 0 until nodes) {
            val c = cell(j)
            for (a in grid.indices) if (c[a] + 1 < grid[a]) edges += j to j + strides[a]
        }
        // Many edges (a sheet, a lattice) each draw fainter, so the glow around the best match stands out.
        val base = (0.3 * kotlin.math.sqrt(46.0 / edges.size.coerceAtLeast(1))).coerceIn(0.06, 0.3)
        val mesh = edges.map { (i, j) ->
            val ci = cell(i)
            val cj = cell(j)
            val d2 = grid.indices.sumOf { a -> val mid = (ci[a] + cj[a]) / 2.0 - at[a]; mid * mid }
            val glow = kotlin.math.exp(-d2 / (2 * glowWidth * glowWidth))
            // Grid position, 0..1 along each axis, at the middle of the edge.
            val uvw = DoubleArray(3) { a -> if (a < grid.size && grid[a] > 1) (ci[a] + cj[a]) / 2.0 / (grid[a] - 1) else 0.5 }
            val rgb = if (gridColors && !chain)
                vec(0.25 + 0.75 * uvw[0], 0.25 + 0.75 * uvw[1], 0.25 + 0.75 * (if (grid.size == 3) uvw[2] else 1 - uvw[0]))
            else vec(color.x, color.y, color.z)
            fline {
                moveTo(node(i))
                lineTo(node(j))
                this.color = vec(rgb.x, rgb.y, rgb.z, color.w * (base + (1 - base) * glow))
                thickness = 0.8 + 1.8 * glow
            }
        }
        // The recent path of the best match, through where those prototypes are now, fading with age.
        val path = trail.filter { it in 0 until nodes }.fold(ArrayList<Int>()) { acc, m -> if (acc.lastOrNull() != m) acc += m; acc }
        val trailLines = (1 until path.size).map { k ->
            val age = k.toDouble() / (path.size - 1)
            fline {
                moveTo(node(path[k - 1]))
                lineTo(node(path[k]))
                this.color = vec(1.0, 0.66, 0.28, color.w * (0.15 + 0.85 * age))
                thickness = 1.2 + 2.4 * age
            }
        }
        val tether = fline {
            moveTo(node(match))
            lineTo(node(nodes).let { if (dimensions == 3) it else vec(it.x, it.y, 0.0) })
            this.color = vec(1.0, 1.0, 1.0, color.w * 0.9)
            thickness = 2.6
        }
        return mesh + trailLines + tether
    }
    fun point(j: Int) = vec(
        (coordinates[j * dimensions] - middle[0]) * sx,
        (coordinates[j * dimensions + 1] - middle[1]) * sy,
        if (dimensions == 3) (coordinates[j * dimensions + 2] - middle[2]) * sz else (j / (n - 1.0) - 1.0) * depth,
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

