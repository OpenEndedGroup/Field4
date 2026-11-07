@file:afield.runtime.completion.CompletionCategory("Audio")

package afield.audio.analysis

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.fline
import afield.graphics.render.thickness
import afield.math.linalg.Vec4
import afield.math.linalg.vec
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
 */
data class AudioCurveSettings(
    val fftSize: Int = 2048,
    val hopSize: Int = 512,
    val melBands: Int = 64,
    val history: Int = 128,
    val dimensions: Int = 2,
) {
    init {
        require(fftSize >= 4 && hopSize in 1..fftSize && melBands >= 2 && history >= 3 && dimensions in 2..3) {
            "invalid audio curve settings $this"
        }
    }

    /** A filename-safe tag, so files analysed with different settings never collide. */
    internal val tag get() = "fft$fftSize-hop$hopSize-mel$melBands-h$history-${dimensions}d"
}

/**
 * Analyse [wav] into one rolling curve file per channel and open them, oldest channel first.
 *
 * This runs the `fieldaudio_curve` Python package from this checkout through `uv` (dependency group
 * `audio-curve`: NumPy and SciPy). The files land in [directory], named after the WAV and the
 * [settings], and are reused while they are newer than the WAV, so a box that calls this on every
 * run only pays for the analysis once. Close the returned files when you are done with them.
 */
fun audioCurves(
    wav: Path,
    settings: AudioCurveSettings = AudioCurveSettings(),
    directory: Path = Paths.get(System.getProperty("java.io.tmpdir"), "field-audio-curves"),
): List<AudioCurveFile> {
    val source = wav.toAbsolutePath().normalize()
    require(Files.isRegularFile(source)) { "No such WAV file: $source" }
    val channels = AudioSystem.getAudioFileFormat(source.toFile()).format.channels
    Files.createDirectories(directory)
    val base = directory.resolve("${source.fileName.toString().substringBeforeLast('.')}.${settings.tag}.f4curve")
    val outputs = if (channels == 1) listOf(base)
        else (1..channels).map { base.resolveSibling(base.fileName.toString().replace(".f4curve", ".ch$it.f4curve")) }
    val modified = Files.getLastModifiedTime(source)
    if (outputs.any { !Files.isRegularFile(it) || Files.getLastModifiedTime(it) < modified }) runCurveAnalysis(source, base, settings)
    return outputs.map { AudioCurveFile(it) }
}

private fun runCurveAnalysis(wav: Path, output: Path, settings: AudioCurveSettings) {
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
        uv, "run", "--project", checkout.path, "--group", "audio-curve", "--", "python", "-m", "fieldaudio_curve",
        wav.toString(), output.toString(),
        "--fft-size", "${settings.fftSize}", "--hop-size", "${settings.hopSize}", "--mel-bands", "${settings.melBands}",
        "--history", "${settings.history}", "--dimensions", "${settings.dimensions}",
    ).directory(checkout).redirectErrorStream(true).redirectOutput(log).apply {
        environment()["PYTHONPATH"] = File(checkout, "python").path
        environment()["OPENBLAS_NUM_THREADS"] = "1"
        environment().remove("VIRTUAL_ENV")
    }.start()
    try {
        if (!process.waitFor(30, TimeUnit.MINUTES)) error("audio curve analysis of $wav timed out; log: $log")
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
    check(process.exitValue() == 0) { "audio curve analysis of $wav failed (exit ${process.exitValue()}):\n${log.readText().takeLast(4000)}" }
}

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
 */
fun AudioCurveFrame.flines(
    scale: Double = 1.0,
    depth: Double = 0.0,
    color: Vec4 = vec(0.39, 0.78, 0.86, 1.0),
    bands: Int = 8,
): List<FLine> {
    val n = pointCount
    if (n < 2) return emptyList()
    fun point(j: Int) = vec(
        coordinates[j * dimensions] * scale,
        coordinates[j * dimensions + 1] * scale,
        if (dimensions == 3) coordinates[j * dimensions + 2] * scale else (j / (n - 1.0) - 1.0) * depth,
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

