@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import afield.four.audio.WavFloatArrayWriter
import afield.four.synth.DirtSampleLibrary
import afield.four.synth.DirtRender
import afield.four.synth.FaustDsp
import afield.four.synth.FaustEngine
import afield.four.synth.FaustProgram
import afield.four.synth.PatPattern
import afield.four.synth.PatSoundDocs
import afield.four.synth.PatVoiceSpec
import afield.four.synth.Signal
import afield.four.synth.SonicPi
import afield.four.synth.StereoRenderable
import afield.four.synth.SynthContext
import afield.four.synth.doughRenderMono
import afield.four.synth.patRenderStereo
import afield.four.synth.patRenderStereoDirt
import afield.four.synth.patVoice
import afield.stdlib.AudioVisualizerOptions
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioSystem
import kotlin.math.roundToInt

/**
 * A finite sound made by Field code and written to a real WAV file.
 *
 * This is the seam between Field's synthesis systems and Field Audio. Pat, Dirt, Dough and Faust
 * render faster than real time; Sonic Pi records in real time. Once either path has produced this
 * value, [FieldAudioClient.analyzeRenderedAudio] treats it identically: upload immutable bytes,
 * submit durable jobs, follow progress, and return assets rather than in-memory sample arrays.
 *
 * [provenance] is deliberately Field-owned. An asset id identifies WAV *bytes*, so two bits of code
 * that render the same bytes must have the same server identity even if their source text differs.
 * Keep the code itself in the Field document and put concise render parameters here.
 */
data class RenderedAudioFile(
    val path: Path,
    val generator: String,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val durationSeconds: Double? = null,
    val provenance: Map<String, String> = emptyMap(),
) {
    init {
        require(generator.isNotBlank()) { "generator must not be blank" }
        require(path.fileName.toString().lowercase().endsWith(".wav")) { "rendered audio must be a .wav file: $path" }
        require(Files.isRegularFile(path) && Files.size(path) > 44L) { "rendered WAV does not exist or is empty: $path" }
        require(sampleRate == null || sampleRate > 0) { "sampleRate must be positive" }
        require(channels == null || channels > 0) { "channels must be positive" }
        require(durationSeconds == null || durationSeconds > 0.0) { "durationSeconds must be positive" }
    }

    companion object {
        /**
         * Wrap a WAV another Field facility has already rendered, reading its header for the rest.
         *
         * [sampleRate], [channels] and [durationSeconds] are taken from the file itself rather than
         * left null: the header is sitting on disk, and a `RenderedAudioFile` that claims to know
         * nothing about a WAV it is holding is no use to a box drawing a provenance line. They stay
         * null only when the header will not parse.
         */
        fun existing(
            path: Path,
            generator: String,
            provenance: Map<String, String> = emptyMap(),
        ): RenderedAudioFile {
            val absolute = path.toAbsolutePath()
            val header = wavHeaderFacts(absolute)
            return RenderedAudioFile(
                absolute, generator,
                sampleRate = header?.sampleRate,
                channels = header?.channels,
                durationSeconds = header?.durationSeconds,
                provenance = provenance,
            )
        }
    }
}

/** What one WAV's own header says about itself. */
internal data class WavHeaderFacts(
    val sampleRate: Int,
    val channels: Int,
    val durationSeconds: Double,
)

/**
 * Read a WAV's header, and nothing else.
 *
 * `javax.sound.sampled` parses the header without decoding a sample, which is the point: an
 * hour-long capture should not have to be loaded into the JVM to find out how long it is. Null for
 * anything that will not parse, and for anything whose header declines to say — `frameLength` and
 * `sampleRate` are both allowed to be `AudioSystem.NOT_SPECIFIED`, and a guessed duration is worse
 * than an absent one.
 */
internal fun wavHeaderFacts(path: Path): WavHeaderFacts? = runCatching {
    val format = AudioSystem.getAudioFileFormat(path.toFile())
    val rate = format.format.sampleRate
    val frames = format.frameLength.toLong()
    val channels = format.format.channels
    if (rate <= 0f || frames <= 0L || channels <= 0) null
    else WavHeaderFacts(rate.roundToInt(), channels, frames / rate.toDouble())
}.getOrNull()

/** One named analysis request, retained beside its accepted job and final result. */
data class NamedAudioAnalysis(val label: String, val operation: ModelOperation) {
    init { require(label.isNotBlank()) { "analysis label must not be blank" } }
}

/** A completed leg of a rendered-audio analysis fan-out. */
data class CompletedAudioAnalysis(
    val label: String,
    val accepted: JobAccepted,
    val result: AnalysisResult,
)

/**
 * The complete round trip: local render, immutable service asset, exact analysed window, jobs and
 * durable results. This is suitable for storing on a Field node without making Python authoritative.
 */
data class RenderedAudioAnalysis(
    val render: RenderedAudioFile,
    val asset: AudioAsset,
    val sourceRepresentation: AssetRepresentation,
    val region: AssetRegion,
    val analyses: List<CompletedAudioAnalysis>,
) {
    /** Source waveform and every analysis lane on one shared, bidirectional time cursor. */
    fun visualizationHtml(
        serviceBaseUrl: String = "/field-audio",
        width: Int = 1100,
        waveformHeight: Int = 150,
        panelHeight: Int = 250,
        points: Int = 1600,
        syncGroup: String = "render-analysis-${asset.id.removePrefix("sha256:").take(16)}",
    ): String {
        require(analyses.isNotEmpty()) { "rendered analysis has no results to visualize" }
        val label = "${render.generator} · ${render.path.fileName}"
        val provenanceLine = render.provenance.entries.joinToString(" · ") {
            "${it.key}: ${it.value}".htmlEscaped()
        }
        val player = sourceRepresentation.audioVisualizerHtml(
            serviceBaseUrl = serviceBaseUrl,
            label = label,
            options = AudioVisualizerOptions(
                width = width,
                waveformHeight = waveformHeight,
                initialStartSeconds = region.startSeconds,
                initialDurationSeconds = region.durationSeconds,
                initialPositionSeconds = region.startSeconds,
                syncGroup = syncGroup,
            ),
        )
        val dashboard = analysisDashboardHtml(
            results = analyses.map { it.result },
            serviceBaseUrl = serviceBaseUrl,
            width = width,
            panelHeight = panelHeight,
            points = points,
            syncGroup = syncGroup,
            timeOffsetSeconds = region.startSeconds,
        )
        return """
            <section class="field-render-analysis" data-afield-intrinsic-width="$width">
              <style>
                .field-render-analysis{width:${width}px;max-width:100%;display:grid;gap:12px}
                .field-render-analysis .render-provenance{font:11px/1.4 ui-monospace,monospace;color:#91a2af;padding:0 3px}
              </style>
              ${if (provenanceLine.isEmpty()) "" else "<div class=\"render-provenance\">$provenanceLine</div>"}
              $player
              $dashboard
            </section>
        """.trimIndent()
    }
}

/** The standard fan-out used by the transcript; callers can freely supply a different list. */
fun standardAudioAnalyses(
    region: AssetRegion,
    requestedBy: String? = null,
    includeBeats: Boolean = true,
    includeMonophonicPitch: Boolean = true,
    includePolyphonicPitch: Boolean = true,
    forceRegenerate: Boolean = false,
): List<NamedAudioAnalysis> = buildList {
    if (includeBeats) add(NamedAudioAnalysis(
        "beats", multiScaleBeatAnalysis(region, requestedBy = requestedBy, forceRegenerate = forceRegenerate)
    ))
    if (includeMonophonicPitch) add(NamedAudioAnalysis(
        "monophonic pitch", monophonicPitchAnalysis(region, requestedBy = requestedBy, forceRegenerate = forceRegenerate)
    ))
    if (includePolyphonicPitch) add(NamedAudioAnalysis(
        "polyphonic pitch", polyphonicPitchAnalysis(region, requestedBy = requestedBy, forceRegenerate = forceRegenerate)
    ))
}

/**
 * One leg of a rendered-audio fan-out, held on to only so that it can be let go of.
 *
 * A leg owns two things that outlive the future a caller holds: an `await` poll loop, and the
 * WebSocket that `watch` opened. Neither stops by itself if the analysis is abandoned — the service
 * closes an events socket after a *terminal* event, and a job whose worker wedged never has one. So
 * the handles are kept, and [abandon] is what a failed sibling or a cancelling caller reaches for.
 *
 * Every method is synchronized because a leg is populated on one HTTP callback thread and abandoned
 * from another; [abandon] arriving first must still release a socket that opens afterwards.
 */
private class AnalysisLeg {
    private var abandoned = false
    private var socket: CompletableFuture<WebSocket>? = null
    private var analysis: CompletableFuture<AnalysisResult>? = null

    @Synchronized
    fun watching(pending: CompletableFuture<WebSocket>) {
        socket = pending
        if (abandoned) release(pending)
    }

    @Synchronized
    fun polling(pending: CompletableFuture<AnalysisResult>) {
        analysis = pending
        if (abandoned) pending.cancel(false)
    }

    @Synchronized
    fun abandon() {
        abandoned = true
        analysis?.cancel(false)
        socket?.let(::release)
    }

    /**
     * Close now, or when the handshake finishes: a still-connecting socket cannot be closed.
     *
     * `abort()` rather than `sendClose()` because there is nothing to say and no reply worth
     * waiting for. Measured against the stub, which counts live subscriptions, this is enough for
     * the service side to notice and drop its own end.
     */
    private fun release(pending: CompletableFuture<WebSocket>) {
        pending.whenComplete { open, _ -> open?.abort() }
    }
}

/**
 * Import [render], submit a fan-out of analysis jobs, watch them, and complete with all results.
 *
 * Nothing blocks Field's run loop. [requests] runs after import because it needs the content id and
 * exact WAV duration. [onAccepted] is where a box normally displays [fieldAudioJobStatusHtml];
 * [onProgress] can replace that card as WebSocket events arrive. The global iframe monitor sees the
 * same durable jobs without any special integration.
 *
 * The fan-out is all-or-nothing, it fails at the *first* failure, and it cleans up after itself.
 * `CompletableFuture.allOf` gives none of the last two on its own: it does not short-circuit —
 * `BiRelay.tryFire` needs both of its sources done, so `allOf` keeps waiting for every future long
 * after one of them has failed — and it has no way to reach the legs it is waiting on. So each leg
 * reports its own failure, and when one does the others are abandoned rather than left running:
 * their `await` polls are cancelled and their progress sockets closed. A fan-out with a hole in it
 * has no [RenderedAudioAnalysis] to return, and two abandoned legs would otherwise keep polling and
 * keep a socket open for as long as Field runs.
 *
 * Cancelling the returned future does the same thing, and is the only way to stop an analysis whose
 * jobs never reach a terminal state at all. On success the sockets are left to the service, which
 * closes them itself once it has sent the terminal event — closing them from here would race the
 * last [onProgress] call.
 */
fun FieldAudioClient.analyzeRenderedAudio(
    render: RenderedAudioFile,
    startSeconds: Double = 0.0,
    durationSeconds: Double? = null,
    requests: (AssetRegion) -> List<NamedAudioAnalysis> = { standardAudioAnalyses(it) },
    onAccepted: (NamedAudioAnalysis, JobAccepted) -> Unit = { _, _ -> },
    onProgress: (NamedAudioAnalysis, JobAccepted, ProgressEvent) -> Unit = { _, _, _ -> },
    onProgressError: (NamedAudioAnalysis, Throwable) -> Unit = { _, _ -> },
): CompletableFuture<RenderedAudioAnalysis> {
    require(startSeconds >= 0.0) { "startSeconds must be non-negative" }
    require(durationSeconds == null || durationSeconds > 0.0) { "durationSeconds must be positive" }

    val legs = CopyOnWriteArrayList<AnalysisLeg>()
    val released = AtomicBoolean()
    val outcome = CompletableFuture<RenderedAudioAnalysis>()
    // One hook for both endings that strand work: a leg that failed, and a caller who cancelled. A
    // leg registered after this has run sees `released` and abandons itself.
    outcome.whenComplete { _, error ->
        if (error != null) {
            released.set(true)
            legs.forEach(AnalysisLeg::abandon)
        }
    }

    importWav(render.path).thenCompose { asset ->
        val source = asset.representations.firstOrNull {
            it.mediaType in setOf("audio/wav", "audio/x-wav") && it.role == "canonical_project"
        } ?: asset.representations.firstOrNull { it.mediaType in setOf("audio/wav", "audio/x-wav") }
        ?: error("imported asset ${asset.id} has no WAV representation")
        val total = requireNotNull(source.durationSeconds) { "imported WAV has no duration" }
        require(startSeconds < total) { "analysis start $startSeconds is outside ${total}s render" }
        val duration = durationSeconds ?: (total - startSeconds)
        require(startSeconds + duration <= total + 1e-6) {
            "analysis window ${startSeconds}s + ${duration}s exceeds ${total}s render"
        }
        val region = AssetRegion(asset.id, startSeconds, duration.coerceAtMost(total - startSeconds))
        val named = requests(region)
        require(named.isNotEmpty()) { "at least one analysis request is required" }

        val futures = named.map { request ->
            val leg = AnalysisLeg()
            legs += leg
            if (released.get()) leg.abandon()
            submit(request.operation).thenCompose { accepted ->
                onAccepted(request, accepted)
                leg.watching(
                    watch(
                        accepted.jobId,
                        onEvent = { event -> onProgress(request, accepted, event) },
                        onError = { error -> onProgressError(request, error) },
                    )
                )
                val analysis = awaitAnalysis(accepted)
                leg.polling(analysis)
                analysis.thenApply { result ->
                    CompletedAudioAnalysis(request.label, accepted, result)
                }
            }
        }
        // The first leg to fail is the answer. Waiting for `allOf` here would mean waiting for the
        // siblings too, and a sibling whose worker wedged never finishes — a box would then get
        // neither a result nor an error, just a future that stays pending for as long as Field runs.
        futures.forEach { leg ->
            leg.whenComplete { _, error -> error?.let { outcome.completeExceptionally(it.unwrapCompletion()) } }
        }
        CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
            RenderedAudioAnalysis(render, asset, source, region, futures.map { it.join() })
        }
    }.whenComplete { bundle, error ->
        if (error != null) {
            outcome.completeExceptionally(error.unwrapCompletion())
        } else {
            outcome.complete(bundle)
        }
    }
    return outcome
}

/** Offline-render a Pat pattern to an immutable-analysis-ready WAV. */
fun renderPatWav(
    path: Path,
    pattern: PatPattern<String>,
    cycles: Int,
    cps: Double = 1.0,
    voice: PatVoiceSpec = patVoice(),
    sampleRate: Int = PatSoundDocs.SR,
    normalizePeak: Float? = null,
    provenance: Map<String, String> = emptyMap(),
): RenderedAudioFile {
    val audio = patRenderStereo(pattern, cycles, cps, voice, sampleRate)
    WavFloatArrayWriter.writeStereo16(path.toFile(), audio.left, audio.right, sampleRate, normalizePeak)
    return RenderedAudioFile(
        path.toAbsolutePath(), "Pat", sampleRate, 2, audio.frames.toDouble() / sampleRate,
        mapOf("cycles" to cycles.toString(), "cps" to cps.toString()) + provenance,
    )
}

/** Offline-render Pat through the Dirt sample library, falling back exactly as Pat normally does. */
fun renderDirtWav(
    path: Path,
    pattern: PatPattern<String>,
    cycles: Int,
    cps: Double = 1.0,
    library: DirtSampleLibrary? = DirtRender.libraryOrNull(),
    voice: PatVoiceSpec = patVoice(),
    sampleRate: Int = PatSoundDocs.SR,
    normalizePeak: Float? = null,
    provenance: Map<String, String> = emptyMap(),
): RenderedAudioFile {
    val result = patRenderStereoDirt(pattern, cycles, cps, library, voice, sampleRate)
    val audio = result.audio
    WavFloatArrayWriter.writeStereo16(path.toFile(), audio.left, audio.right, sampleRate, normalizePeak)
    return RenderedAudioFile(
        path.toAbsolutePath(), "Dirt", sampleRate, 2, audio.frames.toDouble() / sampleRate,
        mapOf(
            "cycles" to cycles.toString(), "cps" to cps.toString(),
            "sample_library" to result.usedSamples.toString(),
            "matched" to result.matched.sorted().joinToString(" "),
        ) + provenance,
    )
}

/** Offline-render a mono Dough [Signal] to WAV. */
fun renderDoughWav(
    path: Path,
    signal: Signal,
    seconds: Double,
    sampleRate: Int = 48_000,
    normalizePeak: Float? = null,
    provenance: Map<String, String> = emptyMap(),
): RenderedAudioFile {
    require(seconds > 0.0) { "seconds must be positive" }
    val samples = doughRenderMono(signal, seconds.toFloat(), sampleRate)
    WavFloatArrayWriter.writeMono16(path.toFile(), samples, sampleRate, normalizePeak)
    return RenderedAudioFile(
        path.toAbsolutePath(), "Dough", sampleRate, 1, samples.size.toDouble() / sampleRate,
        mapOf("seconds" to seconds.toString()) + provenance,
    )
}

/** Offline-render a stereo Dough node without folding away its spatial image. */
fun renderDoughStereoWav(
    path: Path,
    node: StereoRenderable,
    seconds: Double,
    sampleRate: Int = 48_000,
    normalizePeak: Float? = null,
    provenance: Map<String, String> = emptyMap(),
): RenderedAudioFile {
    require(seconds > 0.0) { "seconds must be positive" }
    val frames = (seconds * sampleRate).roundToInt().coerceAtLeast(1)
    val left = FloatArray(frames)
    val right = FloatArray(frames)
    val block = 128
    val context = SynthContext(sampleRate.toFloat(), block)
    val blockLeft = FloatArray(block)
    val blockRight = FloatArray(block)
    var written = 0
    while (written < frames) {
        node.render(blockLeft, blockRight, block, context)
        context.advanceCycle()
        val count = minOf(block, frames - written)
        System.arraycopy(blockLeft, 0, left, written, count)
        System.arraycopy(blockRight, 0, right, written, count)
        written += count
    }
    WavFloatArrayWriter.writeStereo16(path.toFile(), left, right, sampleRate, normalizePeak)
    return RenderedAudioFile(
        path.toAbsolutePath(), "Dough", sampleRate, 2, frames.toDouble() / sampleRate,
        mapOf("seconds" to seconds.toString()) + provenance,
    )
}

/** Render an already-compiled Faust program to a stereo WAV. */
fun renderFaustWav(
    path: Path,
    program: FaustProgram,
    seconds: Double,
    sampleRate: Int = 48_000,
    normalizePeak: Float? = null,
    provenance: Map<String, String> = emptyMap(),
): RenderedAudioFile {
    require(seconds > 0.0) { "seconds must be positive" }
    val frames = (seconds * sampleRate).roundToInt().coerceAtLeast(1)
    val left = FloatArray(frames)
    val right = FloatArray(frames)
    FaustDsp(program, sampleRate).use { it.render(left, right, frames) }
    WavFloatArrayWriter.writeStereo16(path.toFile(), left, right, sampleRate, normalizePeak)
    return RenderedAudioFile(
        path.toAbsolutePath(), "Faust", sampleRate, 2, frames.toDouble() / sampleRate,
        mapOf("program" to program.name, "seconds" to seconds.toString()) + provenance,
    )
}

private val renderedAudioExecutor = Executors.newCachedThreadPool { task ->
    Thread(task, "field-rendered-audio").apply { isDaemon = true }
}

/** Compile and render Faust away from Field's run loop (the first compiler warm-up can be slow). */
fun renderFaustWavAsync(
    path: Path,
    name: String,
    source: String,
    seconds: Double,
    sampleRate: Int = 48_000,
    compilerArguments: String = FaustEngine.DEFAULT_ARGS,
    normalizePeak: Float? = null,
    provenance: Map<String, String> = emptyMap(),
): CompletableFuture<RenderedAudioFile> = CompletableFuture.supplyAsync({
    val program = FaustEngine.compile(name, source, compilerArguments)
    renderFaustWav(path, program, seconds, sampleRate, normalizePeak, provenance)
}, renderedAudioExecutor)

/**
 * Record a Sonic Pi program in real time, then return the same artifact the offline renderers do.
 *
 * Recording, sleeps and the asynchronous file-save handshake all run on a daemon worker. The
 * supplied [SonicPi] remains owned by the caller. This stops Sonic Pi jobs at the end of the capture
 * but does not close the shared daemon.
 */
fun recordSonicPiWav(
    sonicPi: SonicPi,
    path: Path,
    source: String,
    seconds: Double,
    workspace: String = "field_audio_analysis",
    startSettleMillis: Long = 300,
    tailMillis: Long = 900,
    stopSettleMillis: Long = 500,
    saveTimeoutMillis: Long = 30_000,
    provenance: Map<String, String> = emptyMap(),
): CompletableFuture<RenderedAudioFile> {
    require(seconds > 0.0) { "seconds must be positive" }
    require(startSettleMillis >= 0 && tailMillis >= 0 && stopSettleMillis >= 0 && saveTimeoutMillis > 0) {
        "recording waits are invalid"
    }
    return CompletableFuture.supplyAsync({
        require(sonicPi.isRunning && sonicPi.awaitReady()) { "Sonic Pi is not ready" }
        path.parent?.let(Files::createDirectories)
        Files.deleteIfExists(path)
        var recording = false
        try {
            sonicPi.deleteRecording()
            sonicPi.startRecording()
            recording = true
            Thread.sleep(startSettleMillis)
            sonicPi.run(source, workspace = workspace, silent = true)
            Thread.sleep((seconds * 1000.0).roundToInt().toLong())
            sonicPi.stopAll()
            Thread.sleep(tailMillis)
            sonicPi.stopRecording()
            recording = false
            // Sonic Pi finalizes the recorder buffer asynchronously. Its save command is not a
            // queue barrier: sending it in the same breath as stop-recording can be observed first
            // and silently produce no file. Keep this in step with SonicPiPatternProbe's measured,
            // working handshake rather than making a longer file-existence timeout hide the race.
            Thread.sleep(stopSettleMillis)
            sonicPi.saveRecording(path.toAbsolutePath().toString())
            require(awaitStableWav(path, saveTimeoutMillis)) {
                val recentErrors = sonicPi.errors.takeLast(3)
                    .joinToString(" | ") { it.message.lineSequence().firstOrNull()?.trim().orEmpty() }
                "Sonic Pi did not save a stable WAV to $path after stop-recording settled for " +
                    "$stopSettleMillis ms" + if (recentErrors.isBlank()) "" else "; recent errors: $recentErrors"
            }
            // The recorder's own header is the truth about what was captured. The arithmetic below
            // is only an estimate of it — settle plus sleep plus tail, which is what the *plan* was
            // — and the two differ by however long Sonic Pi actually took to start and stop. Say
            // which one this is, so a box that draws the provenance line is not quietly guessing.
            val header = wavHeaderFacts(path.toAbsolutePath())
            RenderedAudioFile(
                path.toAbsolutePath(), "Sonic Pi",
                sampleRate = header?.sampleRate,
                channels = header?.channels,
                durationSeconds = header?.durationSeconds
                    ?: (seconds + (startSettleMillis + tailMillis) / 1000.0),
                provenance = mapOf(
                    "workspace" to workspace,
                    "capture_seconds" to seconds.toString(),
                    "duration_from" to if (header != null) "wav header" else "capture arithmetic",
                ) + provenance,
            )
        } finally {
            runCatching { sonicPi.stopAll() }
            if (recording) runCatching { sonicPi.stopRecording() }
        }
    }, renderedAudioExecutor)
}

private fun awaitStableWav(path: Path, timeoutMillis: Long): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    var previous = -1L
    var stableChecks = 0
    while (System.nanoTime() < deadline) {
        val size = runCatching { Files.size(path) }.getOrDefault(-1L)
        if (size > 44L && size == previous) {
            stableChecks++
            if (stableChecks >= 3) return true
        } else {
            stableChecks = 0
        }
        previous = size
        Thread.sleep(300)
    }
    return runCatching { Files.size(path) > 44L }.getOrDefault(false)
}

private fun String.htmlEscaped(): String = replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
