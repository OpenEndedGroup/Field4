@file:afield.CompletionCategory("Audio")

package afield.stdlib.loris

import afield.four.audio.WavFloatArrayLoader
import java.io.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Loris's independent resolution, window and tracking controls, expressed in Hz/seconds/dB. */
data class LorisOptions(
    val resolutionHz: Double = 40.0,
    val windowWidthHz: Double = 80.0,
    val hopSeconds: Double = 0.005,
    val frequencyDriftHz: Double = 40.0,
    val amplitudeFloorDb: Double = -60.0,
    val minimumFrequencyHz: Double = 20.0,
) {
    init {
        require(listOf(resolutionHz, windowWidthHz, hopSeconds, frequencyDriftHz, minimumFrequencyHz)
            .all { it.isFinite() && it > 0 })
        require(amplitudeFloorDb.isFinite() && amplitudeFloorDb < 0)
    }
}

/** Irregular native timestamp; bandwidth is a noise-energy parameter, not confidence. Phase is radians. */
data class LorisBreakpoint(
    val timeSeconds: Double, val frequencyHz: Double, val amplitude: Double,
    val phaseRadians: Double, val bandwidth: Double,
) {
    init {
        require(timeSeconds.isFinite() && timeSeconds >= 0)
        require(frequencyHz.isFinite() && frequencyHz > 0)
        require(amplitude.isFinite() && amplitude >= 0)
        require(phaseRadians.isFinite() && bandwidth.isFinite() && bandwidth in 0.0..1.0)
    }
}

/** ID identifies this analysis track; label is Loris's optional channel label, usually zero. */
data class LorisPartial(val id: Int, val label: Int, val points: List<LorisBreakpoint>) {
    init {
        require(points.isNotEmpty())
        require(points.zipWithNext().all { (a, b) -> b.timeSeconds > a.timeSeconds })
    }
}

/** Programmable partial trajectories. Native timestamps and phase are retained without frame pooling. */
data class LorisModel(val sampleRate: Int, val frameCount: Int, val partials: List<LorisPartial>) {
    val durationSeconds: Double get() = frameCount.toDouble() / sampleRate
    init {
        require(sampleRate in 8000..192000 && frameCount in 1..30_000_000)
        require(partials.size <= 100_000 && partials.sumOf { it.points.size.toLong() } <= 10_000_000)
        require(partials.map { it.id }.distinct().size == partials.size)
        require(partials.all { p -> p.points.all { it.frequencyHz < sampleRate / 2.0 && it.timeSeconds <= durationSeconds } })
    }

    /** Transform envelopes; audition phase/transient behavior rather than assuming exact preservation. */
    fun transformed(timeScale: Double = 1.0, frequencyScale: Double = 1.0,
                    gain: Double = 1.0, bandwidthScale: Double = 1.0): LorisModel {
        require(listOf(timeScale, frequencyScale).all { it.isFinite() && it > 0 })
        require(listOf(gain, bandwidthScale).all { it.isFinite() && it >= 0 })
        return copy(frameCount = ceil(frameCount * timeScale).toInt(), partials = partials.map { p ->
            p.copy(points = p.points.map { b -> b.copy(timeSeconds = b.timeSeconds * timeScale,
                frequencyHz = b.frequencyHz * frequencyScale, amplitude = b.amplitude * gain,
                bandwidth = (b.bandwidth * bandwidthScale).coerceIn(0.0, 1.0)) })
        })
    }
}

/** Piecewise-linear (time seconds, value) breakpoints, Loris's LinearEnvelope; constant outside its ends. */
data class LorisEnvelope(val points: List<Pair<Double, Double>>) {
    init {
        require(points.isNotEmpty() && points.size <= 100_000)
        require(points.all { it.first.isFinite() && it.second.isFinite() })
        require(points.zipWithNext().all { (a, b) -> b.first > a.first }) { "envelope times must increase" }
    }
    fun valueAt(t: Double): Double {
        if (t <= points.first().first) return points.first().second
        if (t >= points.last().first) return points.last().second
        val i = points.indexOfLast { it.first <= t }
        val (t0, v0) = points[i]; val (t1, v1) = points[i + 1]
        return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
    }
    companion object {
        fun constant(value: Double) = LorisEnvelope(listOf(0.0 to value))
        /** 0 until [startSeconds], 1 after [endSeconds], linear between: the usual morph gesture. */
        fun ramp(startSeconds: Double, endSeconds: Double, from: Double = 0.0, to: Double = 1.0) =
            LorisEnvelope(listOf(startSeconds to from, endSeconds to to))
    }
}

/** What a distillation labels against: a known harmonic track, or Loris's own fundamental estimate. */
sealed interface LorisReference {
    /** [frequencyHz] tracks harmonic [harmonic] (1 = the fundamental). */
    data class Track(val frequencyHz: LorisEnvelope, val harmonic: Int = 1) : LorisReference {
        init { require(harmonic >= 1 && frequencyHz.points.all { it.second > 0 }) }
    }
    /** Loris's FrequencyReference: estimates the fundamental from the partials, within this range. */
    data class Estimated(val minimumHz: Double, val maximumHz: Double) : LorisReference {
        init { require(minimumHz.isFinite() && maximumHz.isFinite() && minimumHz > 0 && maximumHz > minimumHz) }
    }
    companion object {
        fun constant(fundamentalHz: Double) = Track(LorisEnvelope.constant(fundamentalHz))
    }
}

/** Identity includes exact binary request and executable bytes; results survive subsequent box evaluation. */
data class LorisProvenance(val requestHash: String, val executableSha256: String,
    val upstreamRevision: String, val operation: String, val options: LorisOptions?, val cacheHit: Boolean)

/** Samples remain at the input rate; no normalization, clipping or resampling is applied. */
data class LorisResult(val model: LorisModel, val samples: FloatArray, val provenance: LorisProvenance) {
    val peak: Float get() = samples.maxOfOrNull { abs(it) } ?: 0f
}

/** Lazy optional executable discovery; ordinary Field startup/build/tests never require Loris. */
object LorisNative {
    const val REVISION = "eccd42ac847b08769643b0d0c36442ef0fd27f38"
    private val name get() = if (System.getProperty("os.name").lowercase().contains("win")) "field_loris.exe" else "field_loris"
    val executable: Path? get() = listOfNotNull(
        System.getProperty("afield.loris.executable")?.let(Path::of),
        System.getenv("AFIELD_LORIS_EXECUTABLE")?.let(Path::of),
        Path.of("build/field-loris/build", name), Path.of("build/field-loris/build/Release", name),
    ).firstOrNull { Files.isRegularFile(it) }
    val isAvailable: Boolean get() = executable != null
    fun describe(): String = executable?.let { "Loris at ${it.toAbsolutePath()}" }
        ?: "Loris is optional. Build with ./gradlew buildLorisNative or python tools/build_loris_native.py; set AFIELD_LORIS_EXECUTABLE to override."
}

/** Offline process boundary with durable binary caching and a bounded timeout; call async from a box. */
class LorisClient(val cacheDirectory: Path = Path.of("build/field-loris/cache"),
                  val timeoutSeconds: Long = 180) {
    init { require(timeoutSeconds > 0) }

    fun analyzeAsync(samples: FloatArray, sampleRate: Int, options: LorisOptions = LorisOptions(),
                     onProgress: (String) -> Unit = {}): CompletableFuture<LorisResult> {
        val snapshot = samples.copyOf()
        return CompletableFuture.supplyAsync { analyze(snapshot, sampleRate, options, onProgress) }
    }

    /** WAV convenience uses the existing Field loader's explicit LEFT-channel policy. */
    fun analyzeWavAsync(path: Path, startSeconds: Double = 0.0, durationSeconds: Double? = null,
                        options: LorisOptions = LorisOptions(),
                        onProgress: (String) -> Unit = {}): CompletableFuture<LorisResult> = CompletableFuture.supplyAsync {
        val wav = WavFloatArrayLoader.loadWavToFloatArray(path.toString())
        require(startSeconds.isFinite() && startSeconds >= 0)
        val start = round(startSeconds * wav.sampleRate).toInt()
        val end = durationSeconds?.let {
            require(it.isFinite() && it > 0)
            start.toLong() + round(it * wav.sampleRate).toLong()
        } ?: wav.samples.size.toLong()
        require(start >= 0 && end > start && end <= wav.samples.size) { "Source range is outside WAV" }
        analyze(wav.samples.copyOfRange(start, end.toInt()), wav.sampleRate, options, onProgress)
    }

    fun analyze(samples: FloatArray, sampleRate: Int, options: LorisOptions = LorisOptions(),
                onProgress: (String) -> Unit = {}): LorisResult {
        require(samples.isNotEmpty() && samples.size <= 30_000_000 && samples.all { it.isFinite() })
        require(sampleRate in 8000..192000)
        return request("analyze", options, onProgress) { out ->
            out.writeInt(0); out.writeDouble(sampleRate.toDouble()); out.writeInt(samples.size)
            with(options) { listOf(resolutionHz, windowWidthHz, hopSeconds, frequencyDriftHz,
                amplitudeFloorDb, minimumFrequencyHz).forEach(out::writeDouble) }
            samples.forEach { out.writeDouble(it.toDouble()) }
        }
    }

    fun synthesizeAsync(model: LorisModel): CompletableFuture<LorisResult> =
        CompletableFuture.supplyAsync { synthesize(model) }

    fun synthesize(model: LorisModel): LorisResult = request("synthesize", null, {}) { out ->
        out.writeInt(1); out.writeDouble(model.sampleRate.toDouble()); out.writeInt(model.frameCount)
        writePartials(out, model)
    }

    /**
     * Loris's Channelizer labels each partial with the harmonic number it sits nearest to under
     * [reference]; with [distill] (the default) Loris's Distiller then joins every partial sharing a
     * label into one, so the result has at most one partial per harmonic plus the unlabeled rest.
     * Partial ids are reassigned 0..n-1. The result's samples are the distilled model resynthesized.
     */
    fun distill(model: LorisModel, reference: LorisReference, distill: Boolean = true,
                stretch: Double = 0.0, fadeSeconds: Double = .005, gapSeconds: Double = .001): LorisResult {
        require(stretch.isFinite() && stretch >= 0 && fadeSeconds.isFinite() && fadeSeconds >= 0 && gapSeconds.isFinite() && gapSeconds >= 0)
        return request(if (distill) "distill" else "channelize", null, {}) { out ->
            out.writeInt(2); out.writeDouble(model.sampleRate.toDouble()); out.writeInt(model.frameCount)
            writePartials(out, model)
            when (reference) {
                is LorisReference.Track -> { out.writeInt(0); writeEnvelope(out, reference.frequencyHz); out.writeInt(reference.harmonic) }
                is LorisReference.Estimated -> { out.writeInt(1); out.writeDouble(reference.minimumHz); out.writeDouble(reference.maximumHz) }
            }
            out.writeDouble(stretch); out.writeInt(if (distill) 1 else 0); out.writeDouble(fadeSeconds); out.writeDouble(gapSeconds)
        }
    }

    fun distillAsync(model: LorisModel, reference: LorisReference, distill: Boolean = true): CompletableFuture<LorisResult> =
        CompletableFuture.supplyAsync { distill(model, reference, distill) }

    fun dilateAsync(model: LorisModel, from: List<Double>, to: List<Double>): CompletableFuture<LorisResult> =
        CompletableFuture.supplyAsync { dilate(model, from, to) }

    fun morphAsync(source: LorisModel, target: LorisModel, frequency: LorisEnvelope, amplitude: LorisEnvelope = frequency,
                   bandwidth: LorisEnvelope = amplitude): CompletableFuture<LorisResult> =
        CompletableFuture.supplyAsync { morph(source, target, frequency, amplitude, bandwidth) }

    /** Loris's Sieve: of overlapping same-label partials keep the longest; others become label 0 (or are dropped). */
    fun sift(model: LorisModel, fadeSeconds: Double = .005, dropUnlabeled: Boolean = false): LorisResult {
        require(fadeSeconds.isFinite() && fadeSeconds >= 0)
        return request("sift", null, {}) { out ->
            out.writeInt(3); out.writeDouble(model.sampleRate.toDouble()); out.writeInt(model.frameCount)
            writePartials(out, model); out.writeDouble(fadeSeconds); out.writeInt(if (dropUnlabeled) 1 else 0)
        }
    }

    /**
     * Loris's Dilator: piecewise-linear time warp taking each time in [from] to the matching time in
     * [to] (both strictly increasing). The timeline becomes [durationSeconds], by default the last
     * target plus whatever followed the last source marker.
     */
    fun dilate(model: LorisModel, from: List<Double>, to: List<Double>,
               durationSeconds: Double = to.last() + (model.durationSeconds - from.last())): LorisResult {
        require(from.isNotEmpty() && from.size == to.size) { "dilate needs matching, non-empty marker lists" }
        require(from.zipWithNext().all { (a, b) -> b > a } && to.zipWithNext().all { (a, b) -> b > a }) { "markers must increase" }
        require(from.all { it.isFinite() && it >= 0 } && to.all { it.isFinite() && it >= 0 })
        require(durationSeconds.isFinite() && durationSeconds > 0)
        val frames = ceil(durationSeconds * model.sampleRate).toInt()
        return request("dilate", null, {}) { out ->
            out.writeInt(4); out.writeDouble(model.sampleRate.toDouble()); out.writeInt(model.frameCount)
            out.writeInt(frames); writePartials(out, model)
            out.writeInt(from.size); from.forEach(out::writeDouble); to.forEach(out::writeDouble)
        }
    }

    /**
     * Loris's Morpher. Same-label partials of [source] and [target] are morphed (label 0 crossfades);
     * each envelope maps time in seconds to 0 = source, 1 = target, independently for frequency,
     * amplitude and noise bandwidth. Distill both sounds against the same harmonic numbering first.
     * [referenceLabel] names the partial whose frequency guides quiet partials (Loris's reference
     * partial); 0 disables it. [amplitudeShape] is Loris's log-amplitude morph shape (0 keeps Loris's default).
     */
    fun morph(source: LorisModel, target: LorisModel, frequency: LorisEnvelope, amplitude: LorisEnvelope = frequency,
              bandwidth: LorisEnvelope = amplitude, referenceLabel: Int = 1, amplitudeShape: Double = 0.0,
              durationSeconds: Double = max(source.durationSeconds, target.durationSeconds)): LorisResult {
        require(source.sampleRate == target.sampleRate) { "morph needs one sample rate (${source.sampleRate} vs ${target.sampleRate})" }
        require(referenceLabel >= 0 && amplitudeShape.isFinite() && amplitudeShape >= 0 && durationSeconds.isFinite() && durationSeconds > 0)
        return request("morph", null, {}) { out ->
            out.writeInt(5); out.writeDouble(source.sampleRate.toDouble()); out.writeInt(source.frameCount)
            out.writeInt(ceil(durationSeconds * source.sampleRate).toInt())
            writePartials(out, source); out.writeInt(target.frameCount); writePartials(out, target)
            listOf(frequency, amplitude, bandwidth).forEach { writeEnvelope(out, it) }
            out.writeInt(referenceLabel); out.writeDouble(amplitudeShape)
        }
    }

    private fun writePartials(out: DataOutputStream, model: LorisModel) {
        out.writeInt(model.partials.size)
        model.partials.forEach { p ->
            out.writeInt(p.id); out.writeInt(p.label); out.writeInt(p.points.size)
            p.points.forEach { b -> listOf(b.timeSeconds, b.frequencyHz, b.amplitude, b.phaseRadians, b.bandwidth).forEach(out::writeDouble) }
        }
    }

    private fun writeEnvelope(out: DataOutputStream, envelope: LorisEnvelope) {
        out.writeInt(envelope.points.size)
        envelope.points.forEach { (t, v) -> out.writeDouble(t); out.writeDouble(v) }
    }

    private fun request(operation: String, options: LorisOptions?, progress: (String) -> Unit,
                        write: (DataOutputStream) -> Unit): LorisResult {
        val executable = LorisNative.executable?.toAbsolutePath() ?: error(LorisNative.describe())
        Files.createDirectories(cacheDirectory)
        val temporary = Files.createTempDirectory(cacheDirectory, "request-")
        var process: Process? = null
        try {
            val input = temporary.resolve("input.bin")
            DataOutputStream(Files.newOutputStream(input).buffered()).use { out ->
                out.writeBytes("FLORIS01"); write(out)
            }
            val exeHash = digest(executable)
            val hash = MessageDigest.getInstance("SHA-256")
            // A shared library rebuild changes the computation even if the launcher is unchanged.
            val libraries = Files.walk(executable.parent, 5).use { paths -> paths.filter {
                Files.isRegularFile(it) && it.fileName.toString().let { n ->
                    n.contains("loris", ignoreCase = true) && (n.contains(".so") || n.endsWith(".dylib") || n.endsWith(".dll"))
                }
            }.sorted().toList() }
            val libraryHash = libraries.joinToString(":") { digest(it) }
            hash.update(("field-loris-v2:$exeHash:$libraryHash:${LorisNative.REVISION}").toByteArray())
            Files.newInputStream(input).use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            val key = hash.digest().joinToString("") { "%02x".format(it) }
            val cached = cacheDirectory.resolve("$key.bin")
            val hit = Files.exists(cached)
            if (hit) progress("cached") else {
                progress("$operation: Loris native worker")
                val output = temporary.resolve("output.bin")
                val log = temporary.resolve("worker.log")
                process = ProcessBuilder(executable.toString(), input.toAbsolutePath().toString(), output.toAbsolutePath().toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start()
                check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "Loris exceeded $timeoutSeconds seconds" }
                check(process.exitValue() == 0) { "Loris failed: ${Files.readString(log).takeLast(6000)}" }
                decode(output) // Validate before publishing an immutable cache entry.
                try { Files.move(output, cached, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(output, cached, StandardCopyOption.REPLACE_EXISTING) }
            }
            val (model, audio) = decode(cached)
            progress("complete: ${model.partials.size} partials")
            return LorisResult(model, audio, LorisProvenance(key, exeHash, LorisNative.REVISION, operation, options, hit))
        } finally {
            if (process?.isAlive == true) process.destroyForcibly().waitFor()
            Files.walk(temporary).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun decode(path: Path): Pair<LorisModel, FloatArray> = DataInputStream(Files.newInputStream(path).buffered()).use { input ->
        require(String(input.readNBytes(8), Charsets.US_ASCII) == "FLORIS02") { "Loris protocol mismatch" }
        val rate = input.readDouble(); require(rate.isFinite() && rate == rate.toInt().toDouble())
        val count = input.readInt(); require(count in 0..100_000)
        var total = 0L
        val partials = List(count) {
            val id = input.readInt(); val label = input.readInt(); val n = input.readInt()
            require(n in 1..10_000_000); total += n; require(total <= 10_000_000)
            LorisPartial(id, label, List(n) { LorisBreakpoint(input.readDouble(), input.readDouble(),
                input.readDouble(), input.readDouble(), input.readDouble()) })
        }
        val frames = input.readInt(); require(frames in 1..30_000_000)
        val audio = FloatArray(frames) { input.readDouble().toFloat().also { require(it.isFinite()) } }
        require(input.read() == -1) { "Trailing Loris result data" }
        LorisModel(rate.toInt(), frames, partials) to audio
    }
}

private fun digest(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val b = ByteArray(65536)
        while (true) { val n = stream.read(b); if (n < 0) break; md.update(b, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
