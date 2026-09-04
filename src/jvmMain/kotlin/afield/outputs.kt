package afield

import afield.logger.lprintln
import afield.macros.Insert
import afield.server.Inspector
import afield.server.LogStream
import afield.server.broadcastControllerControlUpdates
import afield.server.registerKeyframeBrowser
import afield.stdlib.FLineFaceCoordinates
import afield.stdlib.FLineHitKind
import afield.stdlib.FLineIntersection
import afield.stdlib.FileManager
import afield.stdlib.KeyframeScope
import afield.stdlib.everyKeyGlob
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector4f
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Receives text, display, and error output for a box or runtime.
 */
interface HasOutput {
    /** Emits [message] without appending a newline. */
    fun print(message: Any?)

    /** Emits [message] followed by a newline. */
    fun println(message: Any?)

    /** Displays [message] on the default output channel for this output target. */
    fun display(message: Any?)

    /** Displays [message] on the named output [channel]. */
    fun display(channel: String, message: Any?)

    // an error of null,,-1 means 'clear errors'
    fun error(message: Any?, property: String = "code", line: Int = -1)
}

class DefaultOutput : HasOutput {
    var proxy: (Any?, Boolean, Number) -> Unit = { _, _, _ -> }
    var displayProxy: (String, Any?) -> Unit = { _, _ -> }
    var errorProxy: (Any?, String, Int) -> Unit = { _, _, _ -> }

    override fun print(message: Any?) {
        proxy(message, false, -1)
    }

    override fun display(channel: String, message: Any?) {
        displayProxy(channel, message)
    }

    override fun display(message: Any?) {
        displayProxy("output", message)
    }

    override fun println(message: Any?) {
        proxy(message, true, -1)
    }

    override fun error(message: Any?, property: String, line: Int) {
        errorProxy(message, property, line)
    }
}

private fun Box.sendOutputControl(action: String, channel: String? = null) {
    val channelValue = channel?.trim()?.takeIf { it.isNotEmpty() }
    send(Message("output-control").payload(OutputControl(uid = id, action = action, channel = channelValue)))
}

/** Opens the named output [channel] by sending an output-control message to this box. */
fun Box.displayOpen(channel: String) {
    sendOutputControl("open", channel)
}

fun Box.displayClose(channel: String) {
    sendOutputControl("close", channel)
}

fun Box.displayClear(channel: String) {
    sendOutputControl("clear", channel)
}

fun Box.displayClearAll() {
    sendOutputControl("clear-all")
}

private fun escapeHtml(text: String): String {
    return buildString(text.length) {
        text.forEach {
            when (it) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(it)
            }
        }
    }
}

private fun buildInlineProgressBarHtml(value: Double, details: String? = null): String {
    val clamped = value.coerceIn(0.0, 1.0)
    val percent = String.format(Locale.US, "%.2f", clamped * 100.0)
    val detailsHtml = details
        ?.takeIf { it.isNotBlank() }
        ?.let { """<span class="field-inline-progress-details">${escapeHtml(it)}</span>""" }
        ?: ""
    return """
        <span class="field-inline-progress">
            <span class="field-inline-progress-track">
                <span class="field-inline-progress-fill" style="width:${percent}%;"></span>
            </span>
            $detailsHtml
        </span>
    """.trimIndent()
}

private fun buildIndeterminateProgressBarHtml(details: String? = null): String {
    val detailsHtml = details
        ?.takeIf { it.isNotBlank() }
        ?.let { """<span class="field-inline-progress-details">${escapeHtml(it)}</span>""" }
        ?: ""
    // No fill width — the sliver animates across the track to signal "working, total unknown".
    return """
        <span class="field-inline-progress">
            <span class="field-inline-progress-track">
                <span class="field-inline-progress-fill field-inline-progress-fill--indeterminate"></span>
            </span>
            $detailsHtml
        </span>
    """.trimIndent()
}

private fun formatProgressDuration(seconds: Double): String {
    if (!seconds.isFinite()) return "--:--"
    val total = seconds.coerceAtLeast(0.0).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}

/** The inline output-area channel a progress bar named [name] lives on (e.g. `*progress`). */
private fun progressInlineChannel(name: String): String {
    val channel = name.trim().ifEmpty { "progress" }
    return if (channel.startsWith("*")) channel else "*$channel"
}

fun Box.progressBar(name: String, value: Double, details: String? = null) {
    display(progressInlineChannel(name), buildInlineProgressBarHtml(value, details))
}

/** An indeterminate ("still working", no known total) progress bar named [name]. */
fun Box.progressBarIndeterminate(name: String, details: String? = null) {
    display(progressInlineChannel(name), buildIndeterminateProgressBarHtml(details))
}

/** Remove a progress bar named [name] from this box's output-area stack. */
fun Box.progressBarClear(name: String) {
    displayClear(progressInlineChannel(name))
}

/**
 * Global switch for the transient progress bars offered by [progressReporter] and [Box.tqdm].
 *
 * Set [enabled] to `false` to make every progress reporter created afterwards inert — nothing
 * is drawn and nothing is sent to any box. Reporters capture this at construction time, so a
 * long-running routine keeps whatever state it started with.
 *
 * [lingerMillis] is how long a *completed* bar stays on screen before it removes itself, so a
 * finished 100% frame is visible for a beat rather than vanishing the instant work ends. It is
 * the default for [progressReporter]/[withProgress]/[Box.tqdm]; set to `0` to remove instantly.
 * (An aborted bar — early return or exception — is always removed immediately, with no linger.)
 *
 * [showDelayMillis] is a grace period: a bar sends *nothing* to the front end until it has been
 * alive this long. Work that finishes quickly therefore never shows a bar at all — the front
 * end is not troubled for a job that was over in a blink. Set to `0` to show immediately.
 */
object ProgressBars {
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var lingerMillis: Long = 1000L

    @Volatile
    var showDelayMillis: Long = 2000L
}

/**
 * A transient tqdm-style progress bar bound to whichever `Box` is currently executing.
 *
 * Obtain one with [progressReporter]; a routine can then report progress without knowing or
 * caring where it runs. When there is no active [Box.context] — or when [ProgressBars.enabled]
 * is `false` — the reporter is *inert*: every method is a complete no-op, so library code may
 * report progress unconditionally.
 *
 * Reaching `1.0` (through any `update`/[step]) removes the bar from the box's output-area
 * stack; [finish]/[close] do the same explicitly. This is what makes the bar transient — it
 * shows while work is happening and disappears the moment it completes.
 */
class ProgressReporter internal constructor(
    private val box: Box?,
    private val name: String,
    private val lingerMillis: Long = ProgressBars.lingerMillis,
    showDelayMillis: Long = ProgressBars.showDelayMillis,
) : AutoCloseable {
    private val startedAt = System.nanoTime()
    private val showDelayNanos = showDelayMillis.coerceAtLeast(0L) * 1_000_000L
    private var lastEmit = 0L
    private var shown = false
    private var finished = false
    private var seen = 0L
    private var pulses = 0L

    /** True when attached to a live box and not yet finished — i.e. it may still draw. */
    val active: Boolean get() = box != null && !finished

    /**
     * Decide whether to draw a frame right now, applying the grace period and the redraw
     * throttle, and record the emission. Returns false (drawing nothing) when still inside the
     * grace period, when throttled, or when already finished — quietly finishing if a terminal
     * frame arrives before the bar was ever shown, so no message reaches the front end.
     */
    private fun shouldEmit(terminal: Boolean): Boolean {
        if (finished) return false
        val now = System.nanoTime()
        if (now - startedAt < showDelayNanos) {
            // Still in the grace period: stay invisible. Finishing quickly means never showing.
            if (terminal) finished = true
            return false
        }
        // Throttle intermediate frames so a tight loop can't flood the client; the first visible
        // frame and every terminal frame always go through.
        if (!terminal && shown && now - lastEmit < MIN_INTERVAL_NANOS) return false
        shown = true
        lastEmit = now
        return true
    }

    /** Draw the bar at [fraction] of the way done (clamped to 0..1). Reaching 1.0 completes it. */
    fun update(fraction: Double, details: String? = null) {
        val target = box ?: return
        val clamped = fraction.coerceIn(0.0, 1.0)
        val terminal = clamped >= 1.0
        if (!shouldEmit(terminal)) return
        target.progressBar(name, clamped, details)
        if (terminal) complete(target)
    }

    /** Draw the bar at [done]/[total], with a default tqdm-style rate/ETA line if [details] is null. */
    fun update(done: Long, total: Long, details: String? = null) {
        seen = done
        val target = box ?: return
        val clamped = (if (total <= 0L) 1.0 else done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        val terminal = clamped >= 1.0
        if (!shouldEmit(terminal)) return
        target.progressBar(name, clamped, details ?: defaultDetails(done, total))
        if (terminal) complete(target)
    }

    /** Advance by [by] out of [total] and redraw. */
    fun step(total: Long, by: Long = 1L, details: String? = null) = update(seen + by, total, details)

    /**
     * Report indeterminate activity — one "beat" of work whose total is unknown (e.g. a reduce
     * that runs to a fixpoint). Shows an animated bar with a step count and elapsed time rather
     * than a fraction. Never auto-completes; the bar is removed by [finish]/[close]. Like every
     * method here, it is a no-op outside a box, while disabled, or during the grace period.
     */
    fun pulse(details: String? = null) {
        val target = box ?: return
        pulses++
        if (!shouldEmit(terminal = false)) return
        target.progressBarIndeterminate(name, details ?: defaultPulseDetails())
    }

    /** Completed at 100%: keep the finished frame up for [lingerMillis], then remove it. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun complete(target: Box) {
        if (finished) return
        finished = true
        if (!shown) return // never surfaced; nothing to remove
        if (lingerMillis <= 0L) {
            target.progressBarClear(name)
            return
        }
        // Non-blocking: the routine returns now; the bar clears itself a beat later.
        GlobalScope.launch {
            delay(lingerMillis)
            target.progressBarClear(name)
        }
    }

    /**
     * Remove the bar immediately. Idempotent, and a no-op when inert or already completed — so an
     * outer `finally` won't cut short the linger of a bar that finished on its own. This is the
     * abort path: a bar that never reached 100% is removed at once. Sends nothing if the bar was
     * never shown (i.e. it lived and died entirely inside the grace period).
     */
    fun finish() {
        val target = box ?: return
        if (finished) return
        finished = true
        if (shown) target.progressBarClear(name)
    }

    override fun close() = finish()

    private fun defaultDetails(done: Long, total: Long): String {
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val rate = done / elapsed.coerceAtLeast(1e-9)
        val remaining = (total - done).coerceAtLeast(0L)
        val eta = if (rate > 1e-9) remaining / rate else Double.POSITIVE_INFINITY
        val rateText = String.format(Locale.US, "%.1f", rate)
        return "$done/$total $rateText it/s ${formatProgressDuration(elapsed)}<${formatProgressDuration(eta)}"
    }

    private fun defaultPulseDetails(): String {
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val rate = pulses / elapsed.coerceAtLeast(1e-9)
        val rateText = String.format(Locale.US, "%.1f", rate)
        return "$pulses steps · $rateText/s · ${formatProgressDuration(elapsed)}"
    }

    private companion object {
        const val MIN_INTERVAL_NANOS = 33_000_000L // ~30 fps ceiling on redraws
    }
}

/**
 * A [ProgressReporter] for the box currently executing (via [Box.context]), or an inert no-op
 * reporter when there is no active box or when [ProgressBars.enabled] is `false`.
 *
 * This is the entry point for routines that want to *offer* a progress bar without depending on
 * a `Box`: call it, drive the returned reporter, and it draws only when it happens to run inside
 * a box with progress enabled.
 */
fun progressReporter(
    name: String = "progress",
    lingerMillis: Long = ProgressBars.lingerMillis,
    showDelayMillis: Long = ProgressBars.showDelayMillis,
): ProgressReporter {
    val box = if (ProgressBars.enabled) Box.context.get() else null
    return ProgressReporter(box, name, lingerMillis, showDelayMillis)
}

/**
 * Run [block] with a transient progress bar, always removing the bar afterwards. A bar that
 * reached 100% inside [block] lingers as usual; one that didn't (early return or a thrown
 * exception) is removed immediately. The reporter is inert outside a box context.
 */
fun <T> withProgress(
    name: String = "progress",
    lingerMillis: Long = ProgressBars.lingerMillis,
    showDelayMillis: Long = ProgressBars.showDelayMillis,
    block: (ProgressReporter) -> T,
): T {
    val reporter = progressReporter(name, lingerMillis, showDelayMillis)
    try {
        return block(reporter)
    } finally {
        reporter.finish()
    }
}

interface SizedIterable<out T> : Iterable<T> {
    val size: Int
}

fun <T> sizedIterable(size: Int, iteratorFactory: () -> Iterator<T>): SizedIterable<T> {
    val safeSize = size.coerceAtLeast(0)
    return object : SizedIterable<T> {
        override val size: Int = safeSize
        override fun iterator(): Iterator<T> = iteratorFactory()
    }
}

private sealed interface ProcessStreamEvent {
    data class Line(val text: String, val isError: Boolean) : ProcessStreamEvent
    data class Exit(val code: Int) : ProcessStreamEvent
}

enum class StreamProcessErrorKind {
    NO_COMMAND,
    START_FAILED,
    STDERR,
    NON_ZERO_EXIT
}

data class StreamProcessError(
    val kind: StreamProcessErrorKind,
    val name: String,
    val label: String,
    val command: List<String>,
    val channel: String,
    val message: String,
    val exitCode: Int? = null,
    val throwable: Throwable? = null
)

data class StreamProcessEnd(
    val name: String,
    val label: String,
    val command: List<String>,
    val channel: String,
    val exitCode: Int,
    val terminated: Boolean = false
)

/**
 * Launches an external process and streams stdout/stderr into an output area.
 *
 * The returned Frames ends when the process exits, and ending the Frames kills the process. So does
 * pressing stop on the box, but by a different route and with one difference worth knowing: stop
 * cancels the Frames end phase before it can run (see `Box.stopHooks` and
 * `dev_docs/frames_stop_end_phase.md`), so the child is killed by a stop hook while the suspending
 * [atEnd] callback — a box author's own teardown — is skipped. Ending the Frames in code, or letting
 * the process exit, runs [atEnd] normally.
 */
fun Box.streamProcess(
    name: String,
    command: List<String>,
    channel: String = name,
    workingDirectory: File? = null,
    env: Map<String, String> = emptyMap(),
    atError: suspend Box.(StreamProcessError) -> Unit = {},
    atEnd: suspend Box.(StreamProcessEnd) -> Unit = {}
): Frames {
    val box = this
    val label = name.ifBlank { command.joinToString(" ") }
    val outputChannel = channel.ifBlank { label.ifBlank { "process" } }
    var process: Process? = null
    var reportedEnd = false

    /**
     * Removes the stop hook installed further down. Assigned late, but [reportEnd] is the single
     * funnel through which "this process is finished" passes, so the removal belongs there: a
     * process that exits on its own never reaches the Frames end phase at all — the body reports
     * and returns, and `frame()` is never called again — so unregistering only from `atEnd` would
     * leave a stale hook on the box for every completed process.
     */
    var unregisterStopHook: Runnable? = null

    suspend fun reportEnd(exitCode: Int, terminated: Boolean = false) {
        if (reportedEnd) return
        reportedEnd = true
        unregisterStopHook?.run()
        box.atEnd(
            StreamProcessEnd(
                name = name,
                label = label,
                command = command,
                channel = outputChannel,
                exitCode = exitCode,
                terminated = terminated
            )
        )
    }

    suspend fun reportError(
        kind: StreamProcessErrorKind,
        message: String,
        exitCode: Int? = null,
        throwable: Throwable? = null
    ) {
        box.atError(
            StreamProcessError(
                kind = kind,
                name = name,
                label = label,
                command = command,
                channel = outputChannel,
                message = message,
                exitCode = exitCode,
                throwable = throwable
            )
        )
    }

    val runner = frames {
        if (command.isEmpty()) {
            reportError(StreamProcessErrorKind.NO_COMMAND, "no command provided")
            box.display(
                outputChannel,
                """<pre class="process-line error" style="margin:0;font-family:monospace;">[$label] no command provided</pre>"""
            )
            return@frames
        }

        val pb = ProcessBuilder(command)
        workingDirectory?.let { pb.directory(it) }
        env.forEach { (k, v) -> pb.environment()[k] = v }

        process = try {
            pb.start()
        } catch (t: Throwable) {
            reportError(StreamProcessErrorKind.START_FAILED, t.message ?: t.toString(), throwable = t)
            box.display(
                outputChannel,
                """<pre class="process-line error" style="margin:0;font-family:monospace;">[$label] failed to start: ${escapeHtml(t.message ?: t.toString())}</pre>"""
            )
            return@frames
        }

        val commandSummary = command.joinToString(" ") { escapeHtml(it) }
        val pidSuffix = process?.pid()?.let { " (pid $it)" } ?: ""
        box.display(
            outputChannel,
            """<pre class="process-line start" style="margin:0;font-family:monospace;">[$label] started$pidSuffix: $commandSummary</pre>"""
        )

        coroutineScope {
            val events = Channel<ProcessStreamEvent>(Channel.UNLIMITED)

            fun sendEvent(event: ProcessStreamEvent) {
                events.trySend(event)
            }

            launch(Dispatchers.IO) {
                process?.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { sendEvent(ProcessStreamEvent.Line(it, false)) }
                }
            }
            launch(Dispatchers.IO) {
                process?.errorStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { sendEvent(ProcessStreamEvent.Line(it, true)) }
                }
            }
            launch(Dispatchers.IO) {
                val code = process?.waitFor() ?: -1
                sendEvent(ProcessStreamEvent.Exit(code))
                events.close()
            }

            while (frame()) {
                val eventResult = withTimeoutOrNull(50) { events.receiveCatching() } ?: continue
                if (eventResult.isClosed) break
                val event = eventResult.getOrNull() ?: continue

                when (event) {
                    is ProcessStreamEvent.Line -> {
                        val kind = if (event.isError) "stderr" else "stdout"
                        val text = escapeHtml(event.text)
                        if (event.isError) {
                            reportError(StreamProcessErrorKind.STDERR, event.text)
                        }
                        box.display(
                            outputChannel,
                            """<pre class="process-line $kind" style="margin:0;font-family:monospace;">[$label $kind] $text</pre>"""
                        )
                    }

                    is ProcessStreamEvent.Exit -> {
                        val kind = if (event.code == 0) "done" else "error"
                        box.display(
                            outputChannel,
                            """<pre class="process-line $kind" style="margin:0;font-family:monospace;">[$label] exited with code ${event.code}</pre>"""
                        )
                        try {
                            if (event.code != 0) {
                                reportError(
                                    StreamProcessErrorKind.NON_ZERO_EXIT,
                                    "exited with code ${event.code}",
                                    exitCode = event.code
                                )
                            }
                        } finally {
                            reportEnd(event.code)
                        }
                        break
                    }
                }
            }

            events.close()
            coroutineContext.cancelChildren()
        }
    }

    /**
     * Kill the child, if it is still running, and say so in the output area.
     *
     * Idempotent and non-suspending, because it has two callers that can race: the end phase below,
     * and the stop hook. Returns whether this call was the one that terminated it, which is the
     * `terminated` flag [StreamProcessEnd] carries.
     */
    fun terminateProcess(): Boolean {
        val proc = process ?: return false
        if (!proc.isAlive) return false
        proc.destroy()
        if (!proc.waitFor(250, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            proc.waitFor(250, TimeUnit.MILLISECONDS)
        }
        box.display(
            outputChannel,
            """<pre class="process-line stop" style="margin:0;font-family:monospace;">[$label] terminated</pre>"""
        )
        return true
    }

    // Pressing stop does NOT run the end phase — `stopTrackedJobs` bumps the launch epoch before the
    // body's cancellation can register an end job, so `registerLaunchJob` cancels it while it is
    // still queued. That fence is load-bearing and stays; see Box.stopHooks. Without this hook the
    // `destroy()` below simply never happened and the child outlived the box, which is the leak
    // measured in dev_docs/frames_stop_end_phase.md. The hook releases the OS resource only: the
    // suspending `atEnd` user callback still belongs to the end phase and is still skipped on stop.
    unregisterStopHook = box.registerStopHook(Runnable { terminateProcess() })

    runner.atEnd = suspend {
        val proc = process
        if (proc == null) {
            // Nothing was ever spawned, so there is no reportEnd to carry the removal. The hook is
            // registered unconditionally above and never reassigned, so it is non-null here — no
            // safe call, and a future conditional registration becomes a compile error, not a
            // silently skipped unregister.
            unregisterStopHook.run()
        } else {
            val terminated = terminateProcess()
            val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
            reportEnd(exitCode, terminated = terminated)
        }
    }

    return runner
}

private fun streamProcessStrand(name: String, channel: String): String {
    val basis = name.ifBlank { channel }.ifBlank { "process" }
    val sanitized = basis
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_.-]+"), "-")
        .trim('-')
        .ifBlank { "process" }
    return "stream-process:$sanitized"
}

/**
 * Starts [streamProcess] immediately, without requiring the returned [Frames] to be
 * the launched value of a box. Reusing the same strand cancels the previous process.
 */
fun Box.streamProcessFireAndForget(
    name: String,
    command: List<String>,
    channel: String = name,
    workingDirectory: File? = null,
    env: Map<String, String> = emptyMap(),
    strand: String = streamProcessStrand(name, channel),
    atError: suspend Box.(StreamProcessError) -> Unit = {},
    atEnd: suspend Box.(StreamProcessEnd) -> Unit = {}
) {
    val frames = streamProcess(
        name = name,
        command = command,
        channel = channel,
        workingDirectory = workingDirectory,
        env = env,
        atError = atError,
        atEnd = atEnd
    )
    launchFrames(frames, strand = strand, mode = "process")
}

fun Box.streamProcessFireAndForget(
    name: String,
    vararg command: String,
    channel: String = name,
    workingDirectory: File? = null,
    env: Map<String, String> = emptyMap(),
    strand: String = streamProcessStrand(name, channel),
    atError: suspend Box.(StreamProcessError) -> Unit = {},
    atEnd: suspend Box.(StreamProcessEnd) -> Unit = {}
) {
    streamProcessFireAndForget(
        name = name,
        command = command.toList(),
        channel = channel,
        workingDirectory = workingDirectory,
        env = env,
        strand = strand,
        atError = atError,
        atEnd = atEnd
    )
}


private fun printedPayload(arguments: Array<out Any?>): Any? {
    return when (arguments.size) {
        0 -> ""
        1 -> arguments[0]
        else -> arguments.asList()
    }
}

fun Box._printHere(line: Int, vararg a: Any?) {
    output.proxy(printedPayload(a), false, line)
}

fun Box.macro_printHere(contents: List<String>): Insert? {
    var args = contents.joinToString(",")
    if (args.startsWith("(")) args = args.substring(1)
    if (args.endsWith(")")) args = args.substring(0, args.length - 1)

    return Insert(
        here = "_printHere(__line__+1, $args)"
    )
}

fun Box._printlnHere(line: Int, vararg a: Any) {
    output.proxy(printedPayload(a), true, line)
}

fun Box.marco_printlnHere(contents: List<String>): Insert? {
    var args = contents.joinToString(",")
    if (args.startsWith("(")) args = args.substring(1)
    if (args.endsWith(")")) args = args.substring(0, args.length - 1)

    return Insert(
        here = "_printlnHere(__line__+1, $args)"
    )
}

fun Box.tree(channel: String = "debug", a : Any?) {
    val aa = a ?: "null"
    val ln = Inspector.linkFor(aa, "/tree")
    display(channel, """
<iframe src='${ln}' style='border:none;padding:0;margin:0;display:block;width:560px;height:220px;'></iframe>
""")
}

fun Box.column(channel: String = "debug", a : Any?) {
    val aa = a ?: "null"
    val ln = Inspector.linkFor(aa, "/browse")
    display(channel, """
<iframe src='${ln}' style='border:none;padding:0;margin:0;display:block;width:560px;height:220px;'></iframe>
""")
}

/**
 * Embeds Field's RemoteJS / WebXR browser head in this box's output pane and opens it, so the
 * live A-Frame / Three.js scene renders right on the canvas beside the box. This is the same page
 * served at `/extensions/remote_ar/index.html`; it connects back to the Field server over
 * WebSocket and executes Javascript RemoteJS boxes. See `docs/ar_reference.md`.
 *
 * The iframe carries the class `field-remote-ar-head`, so it can be targeted from tests or page
 * scripts. On a desktop browser the head runs in flat mode (no immersive XR session); the same
 * embed enters AR/VR on a headset or the Endrun iOS app.
 *
 * @param channel output-area channel the head is shown on (also its disclosure title)
 * @param width iframe width in CSS pixels
 * @param height iframe height in CSS pixels
 * @param debug show the head's status panel (connection, A-Frame/THREE versions, XR platform)
 * @param selfTest run the head's in-browser self-test on boot and surface pass/fail in the panel
 */
fun Box.remoteAr(
    channel: String = "remote-ar",
    width: Int = 960,
    height: Int = 540,
    debug: Boolean = true,
    selfTest: Boolean = false,
) {
    val query = buildList {
        if (debug) add("debug=1")
        if (selfTest) add("selftest=1")
    }.joinToString("&")
    val suffix = if (query.isEmpty()) "" else "?$query"
    display(channel, """
<iframe
  class="field-remote-ar-head"
  src="/extensions/remote_ar/index.html$suffix"
  allow="xr-spatial-tracking; fullscreen"
  data-afield-autosize="off"
  style="display:block;width:${width}px;height:${height}px;border:0;background:#000">
</iframe>
""".trimIndent())
    displayOpen(channel)
}


/**
 * Browse a directory of saved keys in this box's output area.
 *
 * Not to be confused with `Box.keyframes` (`keyframes.kt:617`), which is the animation *curve*
 * editor for one named channel. This browses the `Key` files a [KeyframeScope] saved — a
 * different thing under a nearby name, which is why this one is not called `keyframes` too.
 *
 * The collection is every file under [root] matching the glob [pattern], **excluding** the numbered
 * snapshots inside each key's `.versions` directory — those are reached through the version rail on
 * a slot, not listed as keys of their own. The view is slot-major: one row per name across every
 * file, with the range that name takes, because a list of your own file names tells you nothing you
 * did not already know.
 *
 * [pattern] defaults to [everyKeyGlob], which is every file at any depth, because `saveKey` writes
 * the path you hand it and appends no extension — so a default that asked for one found nothing in
 * an ordinary directory of keys. Files that turn out not to be keys are counted as *ignored* rather
 * than reported as errors, and narrowing the pattern now tells you how many keys the narrowing hid
 * (`KeyframeCollection.skipSummary`). Java globs support brace alternation, so `{?,??}` is a legal
 * way to say "one- and two-character names".
 *
 * Passing [scope] is what turns a viewer into a tool. With it, the browser can mark a slot the scope
 * watches that no file holds — the "you have never saved this" row — and the Apply button becomes
 * live. Without it the browser is read-only, which is also what happens if the scope is garbage
 * collected while the browser is still open; it is held weakly on purpose.
 *
 * ```kotlin
 * val scope = KeyframeScope()
 * scope.target("glow@radius", glow)
 * keyframeBrowser("looks", scope = scope)
 * ```
 *
 * # Why the size is in pixels
 *
 * [width] is a pixel count and not a percentage, and that is load-bearing rather than a style
 * choice. An output panel sizes itself to its content, and for an iframe it reads that size from
 * `iframe.style.width` / `.style.height` with a `parseFloat`-based parser
 * (`outputareas2.js`, `iframeIntrinsicSize`). A percentage does **not** parse as zero:
 * `parseFloat("100%")` is 100, so such a frame claims an intrinsic width of one hundred pixels,
 * which loses to `headerWidth`'s `CLOSED_W` floor and leaves the panel at its closed size. The
 * usual second source is an `afield-iframe-size` event from
 * `iframe_autosize.js`, and this iframe opts out of that with `data-afield-autosize="off"` —
 * the panel is `overflow: hidden` with its own scrolling regions, so measuring it would only ever
 * report the size it already had.
 *
 * With neither source the panel falls back to measuring an iframe that is itself `width: 100%` of
 * the panel, which can never grow it, and a fresh panel holding nothing else opens at its closed
 * width: the browser rendered 192 pixels wide inside a 200-pixel panel and two thirds of it was
 * off the right edge. A pixel [width] is the panel's size; the CSS then stretches the iframe to
 * fill the panel it just caused. Pass a non-positive [width] for the old `width: 100%` behaviour,
 * which is still the right thing when the panel is already the size you want — a panel you have
 * dragged to a size keeps it, and autosizing skips it from then on.
 *
 * @param root directory to search
 * @param pattern glob, matched against paths relative to [root]
 * @param channel output-area channel the browser is shown on, and its disclosure title
 * @param scope the live scope, for scope-only slots and Apply; read-only without it
 * @param width iframe width in CSS pixels; non-positive means "fill the panel you are already in"
 * @param height iframe height in CSS pixels
 */
fun Box.keyframeBrowser(
    root: Path,
    pattern: String = everyKeyGlob,
    channel: String = "keyframes",
    scope: KeyframeScope? = null,
    width: Int = 900,
    height: Int = 460
) {
    val token = registerKeyframeBrowser(root, pattern, scope)
    display(channel, keyframeBrowserIframe(token, width, height))
    displayOpen(channel)
}

/** [keyframeBrowser] against a directory named as a string, resolved through the document's files. */
fun Box.keyframeBrowser(
    root: String,
    pattern: String = everyKeyGlob,
    channel: String = "keyframes",
    scope: KeyframeScope? = null,
    width: Int = 900,
    height: Int = 460
) = keyframeBrowser(FileManager.current().path(root), pattern, channel, scope, width, height)

/**
 * The browser's iframe markup, split out so `KeyframeBrowserTest` can hold the sizing contract
 * described on [keyframeBrowser] without standing up a box and a browser.
 */
internal fun keyframeBrowserIframe(token: String, width: Int, height: Int): String {
    val declaredWidth = if (width > 0) "${width}px" else "100%"
    return """
<iframe
  class="field-keyframe-browser"
  src="/keyframe_browser.html?root=$token"
  data-afield-autosize="off"
  style="display:block;width:$declaredWidth;height:${height}px;border:0;background:transparent">
</iframe>
""".trimIndent()
}

fun Box.logs(
    channel: String = "internal logging",
    filter: LogStream.Filter = LogStream.Filter(),
    latestOnly: Boolean = false,
    subId: String = "logs-${UUID.randomUUID()}"
) {
    val effectiveFilter = if (latestOnly) filter.copy(limit = 1) else filter
    fun enc(v: String): String = URLEncoder.encode(v, StandardCharsets.UTF_8)
    val qs = buildString {
        append("?subId=${enc(subId)}")
        effectiveFilter.channels?.takeIf { it.isNotEmpty() }?.let { append("&channels=${enc(it.joinToString(","))}") }
        effectiveFilter.messageContains?.let { append("&messageContains=${enc(it)}") }
        effectiveFilter.messageRegex?.let { append("&messageRegex=${enc(it)}") }
        effectiveFilter.blockId?.let { append("&blockId=${enc(it)}") }
        if (effectiveFilter.includeBlockChildren) append("&includeBlockChildren=true")
        effectiveFilter.sinceEpochMs?.let { append("&sinceEpochMs=$it") }
        effectiveFilter.afterSeq?.let { append("&afterSeq=$it") }
        append("&limit=${effectiveFilter.limit}")
        append("&tail=${effectiveFilter.tail}")
        if (latestOnly) append("&latestOnly=true")
    }
    display(channel, """
<iframe src='/logs${qs}' style='border:none;padding:0;margin:0;width:100%;height:260px'>
""")
}

// Simple DSL for log filters: Box.logs { matching = "foo"; cat = "info" }
class LogFilterBuilder {
    var channels: MutableList<String> = mutableListOf()
    var cat: String? = null           // single channel token
    var matching: String? = null      // substring match
    var regex: String? = null         // regex match
    var blockId: String? = null
    var includeBlockChildren: Boolean = false
    var sinceEpochMs: Long? = null
    var afterSeq: Long? = null
    var limit: Int = 500
    var tail: Boolean = true

    fun channel(vararg tokens: String) { channels.addAll(tokens) }

    internal fun build(latestOnly: Boolean): LogStream.Filter {
        val channelList = (cat?.let { listOf(it) } ?: emptyList()) + channels
        return LogStream.Filter(
            channels = channelList.ifEmpty { null },
            messageContains = matching,
            messageRegex = regex,
            blockId = blockId,
            includeBlockChildren = includeBlockChildren,
            sinceEpochMs = sinceEpochMs,
            afterSeq = afterSeq,
            limit = if (latestOnly) 1 else limit,
            tail = tail
        )
    }
}

fun Box.logs(
    channel: String = "debug",
    latestOnly: Boolean = false,
    subId: String = "logs-${UUID.randomUUID()}",
    build: LogFilterBuilder.() -> Unit
) {
    val filter = LogFilterBuilder().apply(build).build(latestOnly)
    logs(channel = channel, filter = filter, latestOnly = latestOnly, subId = subId)
}

fun Box.transcriptEntries(): List<LinkedHashMap<String, Any?>> = FlightRecorder.entriesFor(this)

fun Box.transcriptPayload(): LinkedHashMap<String, Any?> = FlightRecorder.payloadFor(this)

fun Box.transcriptHtml(): String = FlightRecorder.htmlFor(this)

fun Box.transcript(channel: String = "transcript") {
    val html = transcriptHtml().ifBlank {
        """
        <div style="padding:16px 18px;border-radius:12px;border:1px solid rgba(255,255,255,0.08);background:rgba(17,20,24,0.9);color:rgba(255,255,255,0.68);">
            No transcript entries yet.
        </div>
        """.trimIndent()
    }
    display(channel, html)
}

/**
 * One entry in a [sliders] group: a labelled numeric range plus its update callback.
 *
 * Note the defaults, which are mostly only sensible when every field is supplied explicitly
 * (the usual case): [min] `0.0`, [max] `1.0`, [initialValue] `0.0`, and [step] `100.0` — the
 * large default step means an unspecified [step] yields a whole-number-only slider, so pass a
 * real step (e.g. `0.01`) for continuous control.
 *
 * @param label text shown beside the control
 * @param min smallest selectable value
 * @param max largest selectable value
 * @param step increment used for interaction and displayed precision
 * @param initialValue value shown when the control is created
 * @param onUpdate callback invoked with the selected value
 */
data class SliderConfig(
    val label: String,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 100.0,
    val initialValue: Double = 0.0,
    val onUpdate: (Double) -> Unit
)

// Global registry for slider callbacks
private val sliderCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Double) -> Unit>()

internal fun registerSliderCallback(sliderId: String, callback: (Double) -> Unit) {
    sliderCallbacks[sliderId] = callback
}

internal fun getSliderCallback(sliderId: String): ((Double) -> Unit)? {
    return sliderCallbacks[sliderId]
}

// Global registry for FLine click callbacks
private val flineClickCallbacks = java.util.concurrent.ConcurrentHashMap<String, (FLineIntersection) -> Unit>()

internal fun registerFLineClickCallback(callbackId: String, callback: (FLineIntersection) -> Unit) {
    flineClickCallbacks[callbackId] = callback
}

internal fun getFLineClickCallback(callbackId: String): ((FLineIntersection) -> Unit)? {
    return flineClickCallbacks[callbackId]
}

/**
 * The one place a posted [FLineClick] becomes the [FLineIntersection] a callback sees.
 *
 * Both outputs that can be clicked go through here — the SVG one from `Server.kt`'s `fline-click`
 * handler and the three.js one from `installThreeJsHandler` — so the wire and the record cannot drift
 * apart in one of them only. Absent optional fields stay at their [FLineIntersection] defaults, which
 * is how a stale browser cache posting the original five-field payload still works.
 */
internal fun FLineClick.toIntersection(): FLineIntersection = FLineIntersection(
    closestVertexIndex = closestVertexIndex,
    position = afield.stdlib.vec(x, y, z),
    kind = when (kind.lowercase()) {
        "point" -> FLineHitKind.POINT
        "edge" -> FLineHitKind.EDGE
        "face" -> FLineHitKind.FACE
        else -> FLineHitKind.UNKNOWN
    },
    lineIndex = lineIndex,
    label = label,
    nodeIndex = nodeIndex,
    subPathIndex = subPathIndex,
    elementIndex = elementIndex,
    u = u ?: Double.NaN,
    v = v ?: Double.NaN,
    faceCoordinates = when (faceCoordinates.lowercase()) {
        "bilinear" -> FLineFaceCoordinates.BILINEAR
        "bbox", "bounding_box" -> FLineFaceCoordinates.BOUNDING_BOX
        else -> FLineFaceCoordinates.NONE
    },
    onElement = if (ex != null && ey != null) afield.stdlib.vec(ex, ey, ez ?: 0.0) else null
)

/**
 * Displays a numeric slider in an output disclosure and calls [onUpdate] whenever its value changes.
 *
 * Use this standalone form for one control. Use [controlGroup] when several related controls should
 * share one compact surface.
 *
 * ```kotlin
 * var gain = 0.35
 * slider("gain", min = 0.0, max = 1.0, step = 0.01, initialValue = gain) {
 *     gain = it
 * }
 * ```
 *
 * This control is recreated when the box runs. Use [syncToProperty] when the value should also be
 * written to a persistent box property.
 *
 * Plumbing: [buildSliderHtml] emits a `div.field-control.field-slider` containing a hand-drawn
 * `<svg class="field-slider-visual">` (rect hitbox + track/fill lines + thumb circles) and a
 * `span.field-value`; there is no native `<input type="range">`. Dragging is wired browser-side
 * in `src/commonMain/resources/sliders.js` (`startSliderDrag`/`dragSliderMove`/`endSliderDrag`
 * using pointer capture, funnelling through `updateSlider` → `window.sendSliderUpdate`), not by
 * inline HTML event attributes. Each change posts a [rpc.SliderUpdate] `{ sliderId, value }` over
 * the WebSocket; the `slider-update` handler in `Server.kt` mirrors the value into the
 * controller-control registry, broadcasts it, and invokes the callback registered here (by a
 * per-slider UUID in a `ConcurrentHashMap`) on a coroutine.
 *
 * @param label text shown beside the control
 * @param min smallest selectable value
 * @param max largest selectable value
 * @param step increment used for interaction and displayed precision
 * @param initialValue value shown when the control is created
 * @param channel output disclosure that receives the control
 * @param law how travel maps to value; see [ControlLaw] for when a linear track is the wrong shape
 * @param lawExponent taper strength for [ControlLaw.EXPONENTIAL] and [ControlLaw.BIPOLAR]
 * @param lawCenter the value at mid-travel for [ControlLaw.BIPOLAR]; defaults to the range's midpoint
 * @param significantDigits digits shown, or `0` to derive the decimal count from [step] as before
 * @param onUpdate callback invoked with the selected value
 */
fun Box.slider(
    label: String,
    min: Double = 0.0,
    max: Double = 1.0,
    step: Double = 0.01,
    initialValue: Double = (min + max) / 2,
    channel: String = "controls",
    law: ControlLaw = ControlLaw.LINEAR,
    lawExponent: Double = 2.0,
    lawCenter: Double = (min + max) / 2,
    significantDigits: Int = defaultSignificantDigits(law),
    onUpdate: (Double) -> Unit
) {
    val sliderId = "slider-${UUID.randomUUID()}"
    registerSliderCallback(sliderId, onUpdate)

    val spec = ControlLawSpec(law, min, max, lawExponent, lawCenter).also { it.validate(label) }
    val html = buildSliderHtml(sliderId, label, min, max, step, initialValue, spec, significantDigits)
    display(channel, html)
}

/**
 * Displays several [SliderConfig] sliders together under one disclosure [channel].
 *
 * Each pair is `id to SliderConfig(...)`; the id only distinguishes sliders within the group.
 * This is the older, slider-only grouping path — prefer [controlGroup] when you want to combine a
 * slider with other control types (checkbox, colour picker, etc.) in one compact surface. Wire-level
 * behaviour matches [slider] (see its docs).
 *
 * @param channel output disclosure that receives the sliders
 * @param configs id-to-[SliderConfig] pairs, one per slider
 */
fun Box.sliders(
    channel: String = "controls",
    vararg configs: Pair<String, SliderConfig>
) {
    val html = buildString {
        append("<div class='slider-group'>")
        configs.forEach { (id, config) ->
            val sliderId = "slider-$id-${UUID.randomUUID()}"
            registerSliderCallback(sliderId, config.onUpdate)
            append(buildSliderHtml(sliderId, config.label, config.min, config.max, config.step, config.initialValue))
        }
        append("</div>")
    }
    display(channel, html)
}

/**
 * The markup for one slider — the same geometry the browser then re-derives on every drag.
 *
 * The law travels as `data-law` (plus `data-law-exponent`, `data-law-center` and
 * `data-significant-digits`), which is what `sliderLaw` in `src/commonMain/resources/controlLaws.js`
 * reads back. Emitting the *position* here as well as the value is not redundant: it is what makes the
 * control correct before any JavaScript has run, and `ControlSurfaceProbeTest` asserts that the two
 * computations of it agree to well under a pixel.
 */
private fun buildSliderHtml(
    sliderId: String,
    label: String,
    min: Double,
    max: Double,
    step: Double,
    value: Double,
    spec: ControlLawSpec = ControlLawSpec(ControlLaw.LINEAR, min, max),
    significantDigits: Int = defaultSignificantDigits(spec.law)
): String {
    val stepDecimals = calculateDecimalPlaces(step)
    val clampedValue = value.coerceIn(minOf(min, max), maxOf(min, max))
    val decimalPlaces = controlLawDecimals(clampedValue, significantDigits, stepDecimals)
    val formattedValue = "%.${decimalPlaces}f".format(clampedValue)
    val viewWidth = 320
    val viewHeight = 44
    val centerY = viewHeight / 2
    val padding = 14
    val trackLength = viewWidth - (padding * 2)
    val cx = padding + (trackLength * spec.normalizedFor(clampedValue))
    return """
        <div class="field-control field-slider"
             data-slider-id="$sliderId"
             data-min="$min"
             data-max="$max"
             data-step="$step"
             data-decimal-places="$stepDecimals"
             data-law="${spec.law.wireName}"
             data-law-exponent="${spec.exponent}"
             data-law-center="${spec.center}"
             data-significant-digits="$significantDigits"
             data-value="$value"
             data-view-width="$viewWidth"
             data-padding="$padding">
            <span class="field-label">$label</span>
            <svg class="field-slider-visual" viewBox="0 0 $viewWidth $viewHeight" preserveAspectRatio="xMidYMid meet">
                <rect x="0" y="0" width="$viewWidth" height="$viewHeight" class="slider-hitbox"></rect>
                <line x1="$padding" y1="$centerY" x2="${viewWidth - padding}" y2="$centerY" class="slider-track"></line>
                <line x1="$padding" y1="$centerY" x2="$cx" y2="$centerY" class="slider-fill"></line>
                <circle cx="$cx" cy="$centerY" r="5" class="slider-thumb"></circle>
                <circle cx="$cx" cy="$centerY" r="2" class="slider-thumb-dot"></circle>
            </svg>
            <span class="field-value" id="${sliderId}-value">$formattedValue</span>
        </div>
    """.trimIndent()
}

private fun calculateDecimalPlaces(step: Double): Int {
    if (step >= 1.0) return 0

    // Handle very small numbers and scientific notation
    if (step == 0.0) return 0

    // Use BigDecimal for accurate decimal place calculation
    val bd = java.math.BigDecimal.valueOf(step)
    return bd.stripTrailingZeros().scale().coerceAtLeast(0)
}

// Additional control types + HTML builders

// 2D Vector control (using Field Vector2f)
private val vec2Callbacks = java.util.concurrent.ConcurrentHashMap<String, (Vector2f) -> Unit>()

internal fun registerVec2Callback(controlId: String, callback: (Vector2f) -> Unit) {
    vec2Callbacks[controlId] = callback
}

internal fun getVec2Callback(controlId: String): ((Vector2f) -> Unit)? {
    return vec2Callbacks[controlId]
}

private fun buildVec2Html(
    controlId: String,
    label: String,
    xRange: Pair<Double, Double>,
    yRange: Pair<Double, Double>,
    step: Double,
    decimalPlaces: Int,
    initialValue: Vector2f
): String {
    val viewSize = 150
    val padding = 10
    val area = viewSize - (padding * 2)

    fun clamp(value: Double, range: Pair<Double, Double>): Double {
        return value.coerceIn(range.first, range.second)
    }

    fun mapToX(value: Double): Double {
        val normalized = if (xRange.second - xRange.first == 0.0) 0.0 else (clamp(value, xRange) - xRange.first) / (xRange.second - xRange.first)
        return padding + (area * normalized)
    }

    fun mapToY(value: Double): Double {
        val normalized = if (yRange.second - yRange.first == 0.0) 0.0 else (clamp(value, yRange) - yRange.first) / (yRange.second - yRange.first)
        return padding + (area * (1.0 - normalized))
    }

    val cx = mapToX(initialValue.x.toDouble())
    val cy = mapToY(initialValue.y.toDouble())
    val formattedX = "%.${decimalPlaces}f".format(initialValue.x)
    val formattedY = "%.${decimalPlaces}f".format(initialValue.y)
    val angleDeg = Math.toDegrees(Math.atan2(initialValue.y.toDouble(), initialValue.x.toDouble()))
    val magnitude = kotlin.math.sqrt((initialValue.x * initialValue.x + initialValue.y * initialValue.y).toDouble())
    val center = viewSize / 2.0

    return """
        <div class="field-control field-vec2"
             data-control-id="$controlId"
             data-min-x="${xRange.first}"
             data-max-x="${xRange.second}"
             data-min-y="${yRange.first}"
             data-max-y="${yRange.second}"
             data-x="${initialValue.x}"
             data-y="${initialValue.y}"
             data-step="$step"
             data-decimal-places="$decimalPlaces"
             data-view-size="$viewSize"
             data-padding="$padding">
            <div class="vec2-shell">
                <span class="field-label vec2-tl">$label</span>
                <span class="field-value vec2-tr" id="${controlId}-value">(${formattedX}, ${formattedY})</span>
                <span class="field-value vec2-angle" id="${controlId}-angle">${String.format("θ %.0f°", angleDeg)}</span>
                <svg class="vec2-visual vec2-center" viewBox="0 0 $viewSize $viewSize" preserveAspectRatio="xMidYMid meet"
                     width="$viewSize" height="$viewSize"
                     style="width:${viewSize}px;height:${viewSize}px;">
                    <rect x="0" y="0" width="$viewSize" height="$viewSize" class="vec2-pane"></rect>
                    <line x1="${viewSize / 2}" y1="$padding" x2="${viewSize / 2}" y2="${viewSize - padding}" class="vec2-axis"></line>
                    <line x1="$padding" y1="${viewSize / 2}" x2="${viewSize - padding}" y2="${viewSize / 2}" class="vec2-axis"></line>
                    <line x1="$center" y1="$center" x2="$cx" y2="$cy" class="vec2-line"></line>
                    <path class="vec2-arc" d="" data-center="$center"></path>
                    <text class="vec2-angle-label" x="${center + 20}" y="${center - 8}"></text>
                    <text class="vec2-mag-label" x="${cx + 8}" y="${cy - 8}"></text>
                    <circle cx="$cx" cy="$cy" r="3.5" class="vec2-handle"></circle>
                </svg>
                <span class="field-value vec2-mag" id="${controlId}-mag">${String.format("|v| %.2f", magnitude)}</span>
                <span class="field-value vec2-x">X: <span id="${controlId}-x-value">$formattedX</span></span>
                <span class="field-value vec2-y">Y: <span id="${controlId}-y-value">$formattedY</span></span>
            </div>
        </div>
    """.trimIndent()
}

fun Box.vec2Control(
    label: String,
    initialValue: Vector2f = Vector2f(0.0f, 0.0f),
    xRange: Pair<Double, Double> = -1.0 to 1.0,
    yRange: Pair<Double, Double> = -1.0 to 1.0,
    step: Double = 0.01,
    channel: String = "controls",
    onUpdate: (Vector2f) -> Unit
) {
    val controlId = "vec2-${UUID.randomUUID()}"
    registerVec2Callback(controlId, onUpdate)

    val decimalPlaces = calculateDecimalPlaces(step)
    val html = buildVec2Html(
        controlId = controlId,
        label = label,
        xRange = xRange,
        yRange = yRange,
        step = step,
        decimalPlaces = decimalPlaces,
        initialValue = initialValue
    )
    display(channel, html)
}

// Checkbox control
private val checkboxCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

internal fun registerCheckboxCallback(controlId: String, callback: (Boolean) -> Unit) {
    checkboxCallbacks[controlId] = callback
}

internal fun getCheckboxCallback(controlId: String): ((Boolean) -> Unit)? {
    return checkboxCallbacks[controlId]
}

private fun buildCheckboxHtml(controlId: String, label: String, initialValue: Boolean): String {
    val checkedAttr = if (initialValue) "checked" else ""
    return """
        <div class="field-control field-checkbox">
            <label class="field-label" for="$controlId" style="flex:0 0 auto;">$label</label>
            <input type="checkbox" class="checkbox-toggle" id="$controlId" $checkedAttr onchange="handleCheckboxInput(this)" />
        </div>
    """.trimIndent()
}

fun Box.checkbox(
    label: String,
    initialValue: Boolean = false,
    channel: String = "controls",
    onUpdate: (Boolean) -> Unit
) {
    val controlId = "checkbox-${UUID.randomUUID()}"
    registerCheckboxCallback(controlId, onUpdate)
    val html = buildCheckboxHtml(controlId, label, initialValue)
    display(channel, html)
}

// String input control
private val stringCallbacks = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()

internal fun registerStringCallback(controlId: String, callback: (String) -> Unit) {
    stringCallbacks[controlId] = callback
}

internal fun getStringCallback(controlId: String): ((String) -> Unit)? {
    return stringCallbacks[controlId]
}

private fun buildStringInputHtml(controlId: String, label: String, initialValue: String): String {
    val escapedValue = initialValue.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    return """
        <div class="field-control field-text">
            <label class="field-label" for="$controlId" style="flex:0 0 auto;">$label</label>
            <input type="text" class="field-text-input" id="$controlId" value="$escapedValue" onchange="handleStringInput(this)" />
        </div>
    """.trimIndent()
}

fun Box.stringInput(
    label: String,
    initialValue: String = "",
    channel: String = "controls",
    onUpdate: (String) -> Unit
) {
    val controlId = "string-${UUID.randomUUID()}"
    registerStringCallback(controlId, onUpdate)

    val html = buildStringInputHtml(controlId, label, initialValue)
    display(channel, html)
}

// File upload control
private val fileUploadCallbacks = java.util.concurrent.ConcurrentHashMap<String, (List<FileUploadEntry>) -> Unit>()

internal fun registerFileUploadCallback(controlId: String, callback: (List<FileUploadEntry>) -> Unit) {
    fileUploadCallbacks[controlId] = callback
}

internal fun getFileUploadCallback(controlId: String): ((List<FileUploadEntry>) -> Unit)? {
    return fileUploadCallbacks[controlId]
}

// Shared storage for uploaded file data (used for polling from Python)
private val fileUploadData = java.util.concurrent.ConcurrentHashMap<String, List<FileUploadEntry>>()

fun storeFileUploadData(controlId: String, files: List<FileUploadEntry>) {
    fileUploadData[controlId] = files
}

fun getFileUploadData(controlId: String): List<FileUploadEntry>? = fileUploadData[controlId]

private fun buildFileUploadHtml(controlId: String, label: String, accept: String, multiple: Boolean): String {
    val multipleAttr = if (multiple) "multiple" else ""
    val acceptAttr = if (accept.isNotEmpty()) "accept=\"$accept\"" else ""
    return """
        <div class="field-control field-file-upload">
            <label class="field-label" style="flex:0 0 auto;">$label</label>
            <input type="file" id="$controlId" $acceptAttr $multipleAttr onchange="handleFileUploadInput(this)" style="flex:1 1 auto;" />
            <span class="field-value" id="${controlId}-status"></span>
        </div>
    """.trimIndent()
}

fun Box.fileUpload(
    label: String = "Upload",
    accept: String = "",
    multiple: Boolean = false,
    channel: String = "controls",
    onUpload: (List<FileUploadEntry>) -> Unit
) {
    val controlId = "file-upload-${UUID.randomUUID()}"
    registerFileUploadCallback(controlId, onUpload)
    val html = buildFileUploadHtml(controlId, label, accept, multiple)
    display(channel, html)
}

// Color picker control (vec4, normalized - using Field Vector4f)
private val colorCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Vector4f) -> Unit>()

internal fun registerColorCallback(controlId: String, callback: (Vector4f) -> Unit) {
    colorCallbacks[controlId] = callback
}

internal fun getColorCallback(controlId: String): ((Vector4f) -> Unit)? {
    return colorCallbacks[controlId]
}

private fun buildColorPickerHtml(
    controlId: String,
    label: String,
    initialValue: Vector4f
): String {
    val r = (initialValue.x * 255).toInt().coerceIn(0, 255)
    val g = (initialValue.y * 255).toInt().coerceIn(0, 255)
    val b = (initialValue.z * 255).toInt().coerceIn(0, 255)
    val hexColor = String.format("#%02X%02X%02X", r, g, b)
    val alpha = initialValue.w.coerceIn(0f, 1f)
    val viewWidth = 320
    val viewHeight = 44
    val centerY = viewHeight / 2
    val padding = 14
    val trackLen = viewWidth - (padding * 2)
    val cx = padding + (trackLen * alpha)

    return """
        <div class="field-control field-color">
            <div class="field-header">
                <span class="field-label">$label</span>
                <span class="field-value" id="${controlId}-value">${String.format("rgba(%.2f, %.2f, %.2f, %.2f)", initialValue.x, initialValue.y, initialValue.z, initialValue.w)}</span>
            </div>
            <div class="color-row">
                <label class="color-swatch">
                    <input type="color" id="$controlId" value="$hexColor" onchange="handleColorInput(this)" />
                </label>
                <div class="alpha-column">
                    <div class="alpha-row">
                        <span class="field-label alpha-label">&alpha;</span>
                        <svg class="alpha-visual" viewBox="0 0 $viewWidth $viewHeight" preserveAspectRatio="xMidYMid meet"
                             data-view-width="$viewWidth" data-view-height="$viewHeight" data-padding="$padding">
                            <rect x="0" y="0" width="$viewWidth" height="$viewHeight" class="alpha-hitbox"></rect>
                            <line x1="$padding" y1="$centerY" x2="${viewWidth - padding}" y2="$centerY" class="alpha-track"></line>
                            <line x1="$padding" y1="$centerY" x2="$cx" y2="$centerY" class="alpha-fill"></line>
                            <circle cx="$cx" cy="$centerY" r="5" class="alpha-thumb"></circle>
                            <circle cx="$cx" cy="$centerY" r="2" class="alpha-thumb-dot"></circle>
                        </svg>
                        <span class="field-value" id="${controlId}-alpha-value">${String.format("%.2f", initialValue.w)}</span>
                    </div>
                    <input type="range" class="alpha-slider" id="${controlId}-alpha" min="0" max="1" step="0.01" value="${initialValue.w}"
                        data-control-id="$controlId"
                        oninput="handleColorAlphaInput(this)" style="display:none;" />
                </div>
            </div>
        </div>
    """.trimIndent()
}

fun Box.colorPicker(
    label: String,
    initialValue: Vector4f = Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
    channel: String = "controls",
    onUpdate: (Vector4f) -> Unit
) {
    val controlId = "color-${UUID.randomUUID()}"
    registerColorCallback(controlId, onUpdate)
    val html = buildColorPickerHtml(controlId, label, initialValue)
    display(channel, html)
}

// Momentary button control ------------------------------------------------

private val buttonCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

internal fun registerButtonCallback(controlId: String, callback: (Boolean) -> Unit) {
    buttonCallbacks[controlId] = callback
}

internal fun getButtonCallback(controlId: String): ((Boolean) -> Unit)? = buttonCallbacks[controlId]

/**
 * The markup for a momentary button.
 *
 * A real `<button>`, so it is reachable by tab and driven by space and enter, wrapped in the
 * `field-control` shell every other control uses. The press is *not* an inline `onclick`: a click is
 * one event and this control has two, so `sliders.js` wires `pointerdown`/`pointerup` — plus the window
 * listeners that stop a pointer released elsewhere from leaving the button held down.
 */
private fun buildButtonHtml(controlId: String, label: String): String {
    val escaped = label.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return """
        <div class="field-control field-button" data-control-id="$controlId">
            <span class="field-label">$escaped</span>
            <button type="button" class="field-button-hit" id="$controlId" aria-pressed="false">hold</button>
        </div>
    """.trimIndent()
}

/**
 * Displays a momentary button in an output disclosure — down while it is held, up when it is let go.
 *
 * ```kotlin
 * button("gate") { down ->
 *     if (down) voice.start() else voice.stop()
 * }
 * ```
 *
 * Distinct from [checkbox], which latches: use a checkbox for a state and a button for a *gesture*.
 * Anything modelled as "while I am holding this" — a synth gate, a jog, a preview — needs this one, and
 * a latching checkbox cannot express it at all.
 *
 * @param label text shown beside the button
 * @param channel output disclosure that receives the control
 * @param onUpdate called with `true` on the way down and `false` on the way up
 */
fun Box.button(
    label: String,
    channel: String = "controls",
    onUpdate: (Boolean) -> Unit
) {
    val controlId = "button-${UUID.randomUUID()}"
    registerButtonCallback(controlId, onUpdate)
    display(channel, buildButtonHtml(controlId, label))
}

// Read-only meter control -------------------------------------------------

/**
 * The markup for a level meter — a read-only bar, with the same geometry a slider uses.
 *
 * There is no hitbox and no thumb, deliberately: a meter reports and cannot be dragged. It carries the
 * same `data-law` attributes a slider does, because a level is usually the most logarithmic quantity in
 * the room.
 */
private fun buildMeterHtml(
    controlId: String,
    label: String,
    value: Double,
    spec: ControlLawSpec,
    significantDigits: Int,
    decimalPlaces: Int
): String {
    val escaped = label.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val viewWidth = 320
    val viewHeight = 44
    val padding = 14
    val centerY = viewHeight / 2
    val trackLength = viewWidth - (padding * 2)
    val clamped = value.coerceIn(minOf(spec.min, spec.max), maxOf(spec.min, spec.max))
    val width = trackLength * spec.normalizedFor(clamped)
    val decimals = controlLawDecimals(clamped, significantDigits, decimalPlaces)
    val formatted = "%.${decimals}f".format(clamped)
    return """
        <div class="field-control field-meter"
             data-control-id="$controlId"
             data-min="${spec.min}"
             data-max="${spec.max}"
             data-law="${spec.law.wireName}"
             data-law-exponent="${spec.exponent}"
             data-law-center="${spec.center}"
             data-significant-digits="$significantDigits"
             data-decimal-places="$decimalPlaces"
             data-value="$clamped"
             data-view-width="$viewWidth"
             data-padding="$padding">
            <span class="field-label">$escaped</span>
            <svg class="field-meter-visual" viewBox="0 0 $viewWidth $viewHeight" preserveAspectRatio="xMidYMid meet">
                <line x1="$padding" y1="$centerY" x2="${viewWidth - padding}" y2="$centerY" class="meter-track"></line>
                <rect x="$padding" y="${centerY - 4}" width="$width" height="8" class="meter-fill"></rect>
            </svg>
            <span class="field-value" id="${controlId}-value">$formatted</span>
        </div>
    """.trimIndent()
}

/** How often a meter is allowed to reach the browser. 30 Hz is what a level display needs. */
internal const val meterUpdateHz = 30

private val meterMinIntervalNanos = 1_000_000_000L / meterUpdateHz

/**
 * A live handle on one meter in a control group — the thing a box holds in order to show a level.
 *
 * Every [set] is coalesced to at most [meterUpdateHz] updates a second and rides the same
 * `control-value-update` message a slider's value does, so both control surfaces show it with no new
 * plumbing. Crucially it does **not** write the box's value cache: that path ends in `setPropertyRaw`,
 * i.e. a document mutation, and thirty of those a second would churn `operations.yaml` for a number
 * that is an output and has nothing to preserve across a rerun.
 */
class MeterHandle internal constructor(
    /** The control id this handle drives; useful when a box wants to log what it is feeding. */
    val controlId: String,
    /** Exact document route captured while the control group is built. */
    internal val targetDocument: String?
) {
    private val lastSentNanos = java.util.concurrent.atomic.AtomicLong(0)
    private val lastValue = java.util.concurrent.atomic.AtomicReference<Double?>(null)
    private val sent = java.util.concurrent.atomic.AtomicLong(0)
    private val dropped = java.util.concurrent.atomic.AtomicLong(0)

    /** How many updates passed this meter's rate gate, and how many it coalesced locally. */
    val counts: Pair<Long, Long> get() = sent.get() to dropped.get()

    /**
     * Shows [value] on this meter, if the rate limit and the previous value allow it.
     *
     * Returns true when the value went out. Safe to call from an audio callback or a render loop: it
     * takes no locks, allocates one boxed double, and returns immediately when it is too soon.
     */
    fun set(value: Double): Boolean {
        if (!value.isFinite()) return false
        if (lastValue.get() == value) return false
        val now = System.nanoTime()
        val previous = lastSentNanos.get()
        if (now - previous < meterMinIntervalNanos) {
            dropped.incrementAndGet()
            return false
        }
        if (!lastSentNanos.compareAndSet(previous, now)) {
            dropped.incrementAndGet()
            return false
        }
        lastValue.set(value)
        val updated = updateControllerControlValueInMemory(controlId) { it.copy(value = value) }
        if (updated.isEmpty()) return false
        // The owner carries its SessionInformation even on audio/render threads, where thisSessionInfo
        // is absent. An unattached box has no browser to notify; never turn that case into a broadcast.
        targetDocument?.let { broadcastControllerControlUpdates(updated, it) }
        sent.incrementAndGet()
        return true
    }
}

private class MeterSampler(val groupKey: String, val handle: MeterHandle, val source: () -> Double)

private val meterSamplers = java.util.concurrent.ConcurrentHashMap<String, MeterSampler>()

private val meterSamplerThread: java.util.concurrent.ScheduledExecutorService by lazy {
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "field-meter-sampler").apply { isDaemon = true }
    }.also { executor ->
        executor.scheduleAtFixedRate(
            {
                meterSamplers.forEach { (id, sampler) ->
                    try {
                        sampler.handle.set(sampler.source())
                    } catch (t: Throwable) {
                        // A source usually throws because whatever it was reading has been stopped.
                        // Say so once and stop asking, rather than filling the log at 30 Hz.
                        meterSamplers.remove(id)
                        lprintln("warning,controls", "meter source for $id failed, no longer sampling: ${t.message}")
                    }
                }
            },
            0L,
            1000L / meterUpdateHz,
            TimeUnit.MILLISECONDS
        )
    }
}

private fun registerMeterSampler(groupKey: String, handle: MeterHandle, source: () -> Double) {
    meterSamplers[handle.controlId] = MeterSampler(groupKey, handle, source)
    meterSamplerThread // start the shared sampler on first use, never before
}

/**
 * Stops sampling any meter that belonged to [groupKey] and is not in [liveIds].
 *
 * This is the whole lifecycle: a control group is rebuilt every time the box runs, so a meter that is
 * no longer declared is a meter nobody is looking at. A box that is stopped and never rerun keeps its
 * sampler until then, which is a bounded leak of one lambda and one map entry per meter.
 */
private fun retireMeterSamplers(groupKey: String, liveIds: Set<String>) {
    meterSamplers.entries.removeIf { (id, sampler) -> sampler.groupKey == groupKey && id !in liveIds }
}

/** How many meters are being sampled right now. Test and diagnostic use. */
internal fun activeMeterSamplerCount(): Int = meterSamplers.size

// ------------------------------------------------------------------------
// DSL-style grouping of controls

private const val controlGroupCacheProperty = "_controlGroupCache"
private const val controlGroupValuesProperty = "_controlGroupValues"

private val controllerControlGroups = java.util.concurrent.ConcurrentHashMap<String, List<ControllerControl>>()
private val controllerControlGroupBoxes = java.util.concurrent.ConcurrentHashMap<String, Box>()

private fun controllerControlGroupKey(boxId: String, channel: String): String = "$boxId\u0000$channel"

private fun controlGroupControlKey(channel: String, type: String, label: String, ordinal: Int): String =
    listOf(channel, type, label, ordinal.toString()).joinToString("\u0000")

private fun stableControlId(prefix: String, boxId: String, cacheKey: String): String {
    val uuid = UUID.nameUUIDFromBytes("$boxId\u0000$cacheKey".toByteArray(StandardCharsets.UTF_8))
    return "$prefix-$uuid"
}

@Suppress("UNCHECKED_CAST")
private fun Box.controlGroupCache(): java.util.concurrent.ConcurrentHashMap<String, ControllerControl> {
    val existing = properties[controlGroupCacheProperty]
    if (existing is java.util.concurrent.ConcurrentHashMap<*, *>) {
        return existing as java.util.concurrent.ConcurrentHashMap<String, ControllerControl>
    }
    val cache = java.util.concurrent.ConcurrentHashMap<String, ControllerControl>()
    properties[controlGroupCacheProperty] = cache
    return cache
}

@Suppress("UNCHECKED_CAST")
private fun Box.existingControlGroupCache(): java.util.concurrent.ConcurrentHashMap<String, ControllerControl>? {
    val existing = properties[controlGroupCacheProperty]
    return existing as? java.util.concurrent.ConcurrentHashMap<String, ControllerControl>
}

private fun controlGroupNumber(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}

private fun controlGroupBoolean(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is String -> value.toBooleanStrictOrNull()
    else -> null
}

private fun controlGroupStoredValue(control: ControllerControl): LinkedHashMap<String, Any?> =
    LinkedHashMap<String, Any?>().apply {
        this["id"] = control.id
        this["type"] = control.type
        this["label"] = control.label
        this["channel"] = control.channel
        this["min"] = control.min
        this["max"] = control.max
        this["step"] = control.step
        this["value"] = control.value
        this["xMin"] = control.xMin
        this["xMax"] = control.xMax
        this["yMin"] = control.yMin
        this["yMax"] = control.yMax
        this["x"] = control.x
        this["y"] = control.y
        this["checked"] = control.checked
        this["text"] = control.text
        this["r"] = control.r
        this["g"] = control.g
        this["b"] = control.b
        this["a"] = control.a
        this["decimalPlaces"] = control.decimalPlaces
        this["law"] = control.law
        this["lawExponent"] = control.lawExponent
        this["lawCenter"] = control.lawCenter
        this["significantDigits"] = control.significantDigits
        entries.removeIf { it.value == null }
    }

private fun Box.controlGroupValues(): LinkedHashMap<String, MutableMap<String, Any?>> {
    val existing = properties[controlGroupValuesProperty]
    if (existing is Map<*, *>) {
        val normalized = LinkedHashMap<String, MutableMap<String, Any?>>()
        existing.forEach { (key, value) ->
            val keyString = key?.toString() ?: return@forEach
            val valueMap = value as? Map<*, *> ?: return@forEach
            val clean = LinkedHashMap<String, Any?>()
            valueMap.forEach { (valueKey, storedValue) ->
                clean[valueKey?.toString() ?: return@forEach] = storedValue
            }
            normalized[keyString] = clean
        }
        return normalized
    }
    return LinkedHashMap()
}

private fun controlGroupControlFromStored(cacheKey: String, id: String, stored: Map<String, Any?>): ControllerControl {
    val parts = cacheKey.split('\u0000')
    val channel = stored["channel"]?.toString() ?: parts.getOrNull(0) ?: "controls"
    val type = stored["type"]?.toString() ?: parts.getOrNull(1) ?: "slider"
    val label = stored["label"]?.toString() ?: parts.getOrNull(2) ?: ""
    return ControllerControl(
        id = id,
        type = type,
        label = label,
        channel = channel,
        min = controlGroupNumber(stored["min"]) ?: 0.0,
        max = controlGroupNumber(stored["max"]) ?: 1.0,
        step = controlGroupNumber(stored["step"]) ?: 0.01,
        value = controlGroupNumber(stored["value"]),
        xMin = controlGroupNumber(stored["xMin"]) ?: -1.0,
        xMax = controlGroupNumber(stored["xMax"]) ?: 1.0,
        yMin = controlGroupNumber(stored["yMin"]) ?: -1.0,
        yMax = controlGroupNumber(stored["yMax"]) ?: 1.0,
        x = controlGroupNumber(stored["x"]),
        y = controlGroupNumber(stored["y"]),
        checked = controlGroupBoolean(stored["checked"]),
        text = stored["text"]?.toString(),
        r = controlGroupNumber(stored["r"]),
        g = controlGroupNumber(stored["g"]),
        b = controlGroupNumber(stored["b"]),
        a = controlGroupNumber(stored["a"]),
        decimalPlaces = controlGroupNumber(stored["decimalPlaces"])?.toInt() ?: 2,
        law = stored["law"]?.toString() ?: "linear",
        lawExponent = controlGroupNumber(stored["lawExponent"]) ?: 2.0,
        lawCenter = controlGroupNumber(stored["lawCenter"]) ?: 0.0,
        significantDigits = controlGroupNumber(stored["significantDigits"])?.toInt() ?: 0
    )
}

private fun Box.cachedControlGroupValue(cacheKey: String, id: String): ControllerControl? {
    controlGroupCache()[cacheKey]?.let { return it }
    val stored = controlGroupValues()[cacheKey] ?: return null
    val control = controlGroupControlFromStored(cacheKey, id, stored)
    controlGroupCache()[cacheKey] = control
    return control
}

private fun Box.storeControlGroupValue(cacheKey: String, control: ControllerControl) {
    controlGroupCache()[cacheKey] = control
    val values = controlGroupValues()
    values[cacheKey] = controlGroupStoredValue(control)
    if (properties[controlGroupValuesProperty] != values) {
        setPropertyRaw(controlGroupValuesProperty, values, doResync = false)
    }
}

private fun Box.updateCachedControlGroupValue(control: ControllerControl) {
    val cache = existingControlGroupCache() ?: return
    val cacheKey = cache.entries.firstOrNull { it.value.id == control.id }?.key ?: return
    storeControlGroupValue(cacheKey, control)
}

/**
 * Keep a programmatically-driven control coherent across a box rerun without recording one document
 * edit for every animation frame. Browser gestures use [updateCachedControlGroupValue] because they
 * are durable edits; a keyframe interpolation is already durable in its key file and only needs the
 * live cache to agree with the value currently on screen.
 */
private fun Box.updateCachedControlGroupValueInMemory(control: ControllerControl) {
    val cache = existingControlGroupCache() ?: return
    val cacheKey = cache.entries.firstOrNull { it.value.id == control.id }?.key ?: return
    cache[cacheKey] = control
    val values = controlGroupValues()
    values[cacheKey] = controlGroupStoredValue(control)
    properties[controlGroupValuesProperty] = values
}

internal fun registerControllerControlGroup(box: Box, channel: String, controls: List<ControllerControl>) {
    val key = controllerControlGroupKey(box.id, channel)
    controllerControlGroups[key] = controls
    controllerControlGroupBoxes[key] = box
    // A rebuild is the only reliable signal that a meter is gone: the box just said what it has, so
    // anything sampled for this group and no longer declared has nobody looking at it.
    retireMeterSamplers(key, controls.map { it.id }.toSet())
}

internal fun controllerControlsForBox(boxId: String): List<ControllerControl> {
    val prefix = "$boxId\u0000"
    return controllerControlGroups.entries
        .filter { it.key.startsWith(prefix) }
        .sortedBy { it.key.substringAfter('\u0000') }
        .flatMap { it.value }
}

/**
 * Updates a control in the live registry **without** touching the box's value cache.
 *
 * [updateControllerControlValue] deliberately writes the cache, because a slider a person moved should
 * survive a rerun. A meter reading and a button press are the opposite: one is an output arriving thirty
 * times a second — and the cache write ends in `setPropertyRaw`, a document mutation — and the other is
 * a gesture that must never be replayed, since a cached press would re-fire the moment the box runs
 * again. So they update the registry (which is what the tablet polls and what the broadcast reads) and
 * stop there.
 */
internal fun updateControllerControlValueInMemory(
    controlId: String,
    update: (ControllerControl) -> ControllerControl
): List<ControllerControl> {
    val updated = mutableListOf<ControllerControl>()
    controllerControlGroups.replaceAll { _, controls ->
        controls.map { control ->
            if (control.id == controlId) update(control).also { updated += it } else control
        }
    }
    return updated
}

internal fun updateControllerControlValue(controlId: String, update: (ControllerControl) -> ControllerControl): List<ControllerControl> {
    val updated = mutableListOf<ControllerControl>()
    controllerControlGroups.replaceAll { groupKey, controls ->
        controls.map { control ->
            if (control.id == controlId) {
                update(control).also {
                    updated += it
                    controllerControlGroupBoxes[groupKey]?.updateCachedControlGroupValue(it)
                }
            } else {
                control
            }
        }
    }
    return updated
}

/** The programmatic counterpart of [updateControllerControlValue]: live cache, no operation log. */
internal fun updateControllerControlValueProgrammatically(
    controlId: String,
    update: (ControllerControl) -> ControllerControl
): List<ControllerControl> {
    val updated = mutableListOf<ControllerControl>()
    controllerControlGroups.replaceAll { groupKey, controls ->
        controls.map { control ->
            if (control.id == controlId) {
                update(control).also {
                    updated += it
                    controllerControlGroupBoxes[groupKey]?.updateCachedControlGroupValueInMemory(it)
                }
            } else {
                control
            }
        }
    }
    return updated
}

private fun controllerControlForId(controlId: String): ControllerControl? =
    controllerControlGroups.values.asSequence().flatten().firstOrNull { it.id == controlId }

/**
 * A writable view of one cached [controlGroup] value.
 *
 * [set] updates the desktop control, the remote controller surface and the box's live rerun cache.
 * It deliberately does not invoke the control's callback: the caller is changing the authoritative
 * Kotlin value and uses this handle to keep its UI mirror in step. This makes it safe for a keyframe
 * to drive a control every animation frame without feeding the change back through itself or adding
 * one operation-log entry per frame.
 */
class ControlValueHandle<T> internal constructor(
    /** Stable id derived from box, channel, control type, label and occurrence. */
    val controlId: String,
    private val targetDocument: String?,
    private val read: (ControllerControl) -> T?,
    private val write: (ControllerControl, T) -> ControllerControl,
    private val copy: (T) -> T = { it }
) {
    /** Show [value] everywhere this control appears. Returns false when it was already current. */
    fun set(value: T): Boolean {
        val safeValue = copy(value)
        val current = controllerControlForId(controlId) ?: return false
        if (read(current) == safeValue) return false
        val updated = updateControllerControlValueProgrammatically(controlId) { write(it, safeValue) }
        if (updated.isEmpty()) return false
        targetDocument?.let { broadcastControllerControlUpdates(updated, it) }
        return true
    }
}

class ControlGroupBuilder(private val box: Box, private val channel: String) {
    private val htmlParts = mutableListOf<String>()
    private val controls = mutableListOf<ControllerControl>()
    private val ordinals = mutableMapOf<String, Int>()

    private data class CachedControl(val cacheKey: String, val id: String, val previous: ControllerControl?)

    private fun <T> valueHandle(
        id: String,
        read: (ControllerControl) -> T?,
        write: (ControllerControl, T) -> ControllerControl,
        copy: (T) -> T = { it }
    ) = ControlValueHandle(
        id,
        box.takeIf { it.isInsideInitialized() }?.inside?.filename,
        read,
        write,
        copy
    )

    /**
     * The stable identity of the next control of this [type] and [label] — no cache lookup.
     *
     * Split out of [cachedControl] for the two controls that must *not* restore a value: a meter, whose
     * value is an output, and a button, whose value is a gesture that would re-fire on every rerun if it
     * were remembered. Both still want the stable, label-derived id, so that the control on a tablet is
     * the same control after the box runs again.
     */
    private fun controlIdentity(type: String, prefix: String, label: String): CachedControl {
        val ordinalKey = "$type\u0000$label"
        val ordinal = ordinals.getOrDefault(ordinalKey, 0)
        ordinals[ordinalKey] = ordinal + 1
        val cacheKey = controlGroupControlKey(channel, type, label, ordinal)
        return CachedControl(cacheKey, stableControlId(prefix, box.id, cacheKey), null)
    }

    private fun cachedControl(type: String, prefix: String, label: String): CachedControl {
        val identity = controlIdentity(type, prefix, label)
        return identity.copy(previous = box.cachedControlGroupValue(identity.cacheKey, identity.id))
    }

    /**
     * Adds a bounded numeric value to this control group.
     *
     * ```kotlin
     * slider("speed", min = 0.0, max = 5.0, step = 0.1, initialValue = speed) {
     *     speed = it
     * }
     * ```
     *
     * On a rerun, a cached UI value is reused when available and [onUpdate] is called with that
     * value so the live variable and visible control remain synchronized.
     *
     * Pass a [law] when a linear track is the wrong shape for the parameter. It almost always is for
     * anything heard or seen as a ratio: on a linear 20 Hz–20 kHz control every note below A2 lives in
     * the first 1% of the track, so the first pixel of travel is a 200 Hz jump.
     *
     * ```kotlin
     * slider("cutoff", min = 20.0, max = 20000.0, initialValue = 1000.0, law = ControlLaw.LOGARITHMIC) {
     *     cutoff = it
     * }
     * ```
     *
     * @param label text shown beside the slider
     * @param min smallest selectable value
     * @param max largest selectable value
     * @param step increment used for interaction and displayed precision; snapping is in value space,
     *   so a step means the same thing at both ends of a nonlinear track
     * @param initialValue value used when no cached value exists
     * @param law how travel maps to value; a [ControlLaw.LOGARITHMIC] law over a non-positive [min]
     *   throws here rather than emitting a `NaN` into the page
     * @param lawExponent taper strength for [ControlLaw.EXPONENTIAL] and [ControlLaw.BIPOLAR]
     * @param lawCenter the value at mid-travel for [ControlLaw.BIPOLAR]; defaults to the midpoint
     * @param significantDigits digits shown, or `0` to derive the decimal count from [step] as before;
     *   nonlinear laws default to 4, so one control can read `20.00` and `20000`
     * @param onUpdate callback invoked with the current value
     */
    fun slider(
        label: String,
        min: Double = 0.0,
        max: Double = 1.0,
        step: Double = 0.01,
        initialValue: Double = (min + max) / 2,
        law: ControlLaw = ControlLaw.LINEAR,
        lawExponent: Double = 2.0,
        lawCenter: Double = (min + max) / 2,
        significantDigits: Int = defaultSignificantDigits(law),
        onUpdate: (Double) -> Unit
    ): ControlValueHandle<Double> {
        val cached = cachedControl("slider", "slider", label)
        val sliderId = cached.id
        val value = cached.previous?.value?.coerceIn(min, max) ?: initialValue
        val spec = ControlLawSpec(law, min, max, lawExponent, lawCenter).also { it.validate(label) }
        registerSliderCallback(sliderId, onUpdate)
        if (cached.previous?.value != null) {
            onUpdate(value)
        }
        htmlParts += buildSliderHtml(sliderId, label, min, max, step, value, spec, significantDigits)
        val control = ControllerControl(
            id = sliderId,
            type = "slider",
            label = label,
            channel = channel,
            min = min,
            max = max,
            step = step,
            value = value,
            decimalPlaces = calculateDecimalPlaces(step),
            law = law.wireName,
            lawExponent = spec.exponent,
            lawCenter = spec.center,
            significantDigits = significantDigits
        )
        controls += control
        box.storeControlGroupValue(cached.cacheKey, control)
        return valueHandle(sliderId, { it.value }, { old, next -> old.copy(value = next) })
    }

    /**
     * Adds an RGBA color editor to this control group.
     *
     * Components use normalized `0.0f..1.0f` values: `x`, `y`, `z`, and `w` correspond to red,
     * green, blue, and alpha.
     *
     * ```kotlin
     * colorPicker("tint", initialValue = tint) {
     *     tint = it
     * }
     * ```
     *
     * @param label text shown beside the picker
     * @param initialValue color used when no cached value exists
     * @param onUpdate callback invoked with the selected RGBA color
     */
    fun colorPicker(
        label: String,
        initialValue: Vector4f = Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
        onUpdate: (Vector4f) -> Unit
    ): ControlValueHandle<Vector4f> {
        val cached = cachedControl("color", "color", label)
        val controlId = cached.id
        val value = Vector4f(
            (cached.previous?.r ?: initialValue.x.toDouble()).toFloat(),
            (cached.previous?.g ?: initialValue.y.toDouble()).toFloat(),
            (cached.previous?.b ?: initialValue.z.toDouble()).toFloat(),
            (cached.previous?.a ?: initialValue.w.toDouble()).toFloat()
        )
        registerColorCallback(controlId, onUpdate)
        if (cached.previous?.r != null || cached.previous?.g != null || cached.previous?.b != null || cached.previous?.a != null) {
            onUpdate(Vector4f(value))
        }
        htmlParts += buildColorPickerHtml(controlId, label, value)
        val control = ControllerControl(
            id = controlId,
            type = "color",
            label = label,
            channel = channel,
            r = value.x.toDouble(),
            g = value.y.toDouble(),
            b = value.z.toDouble(),
            a = value.w.toDouble(),
            value = value.w.toDouble(),
            decimalPlaces = 2
        )
        controls += control
        box.storeControlGroupValue(cached.cacheKey, control)
        return valueHandle(
            controlId,
            { old ->
                if (old.r == null || old.g == null || old.b == null || old.a == null) null
                else Vector4f(old.r.toFloat(), old.g.toFloat(), old.b.toFloat(), old.a.toFloat())
            },
            { old, next ->
                old.copy(
                    r = next.x.toDouble(), g = next.y.toDouble(), b = next.z.toDouble(),
                    a = next.w.toDouble(), value = next.w.toDouble()
                )
            },
            { Vector4f(it) }
        )
    }

    /**
     * Adds an XY pad for editing a related pair of numeric values.
     *
     * Prefer this to two independent sliders for positions, directions, velocities, and other
     * values where the relationship between axes matters.
     *
     * ```kotlin
     * vec2Control(
     *     "velocity",
     *     initialValue = velocity,
     *     xRange = -1.0 to 1.0,
     *     yRange = -1.0 to 1.0,
     *     step = 0.05
     * ) {
     *     velocity = it
     * }
     * ```
     *
     * @param label text shown beside the XY control
     * @param initialValue vector used when no cached value exists
     * @param xRange selectable range for the x component
     * @param yRange selectable range for the y component
     * @param step increment used for interaction and displayed precision
     * @param onUpdate callback invoked with the selected vector
     */
    fun vec2Control(
        label: String,
        initialValue: Vector2f = Vector2f(0.0f, 0.0f),
        xRange: Pair<Double, Double> = -1.0 to 1.0,
        yRange: Pair<Double, Double> = -1.0 to 1.0,
        step: Double = 0.01,
        onUpdate: (Vector2f) -> Unit
    ): ControlValueHandle<Vector2f> {
        val cached = cachedControl("vec2", "vec2", label)
        val controlId = cached.id
        val value = Vector2f(
            (cached.previous?.x ?: initialValue.x.toDouble()).toFloat(),
            (cached.previous?.y ?: initialValue.y.toDouble()).toFloat()
        )
        registerVec2Callback(controlId, onUpdate)
        if (cached.previous?.x != null || cached.previous?.y != null) {
            onUpdate(Vector2f(value))
        }
        val decimalPlaces = calculateDecimalPlaces(step)
        htmlParts += buildVec2Html(controlId, label, xRange, yRange, step, decimalPlaces, value)
        val control = ControllerControl(
            id = controlId,
            type = "vec2",
            label = label,
            channel = channel,
            step = step,
            xMin = xRange.first,
            xMax = xRange.second,
            yMin = yRange.first,
            yMax = yRange.second,
            x = value.x.toDouble(),
            y = value.y.toDouble(),
            decimalPlaces = decimalPlaces
        )
        controls += control
        box.storeControlGroupValue(cached.cacheKey, control)
        return valueHandle(
            controlId,
            { old -> if (old.x == null || old.y == null) null else Vector2f(old.x.toFloat(), old.y.toFloat()) },
            { old, next -> old.copy(x = next.x.toDouble(), y = next.y.toDouble()) },
            { Vector2f(it) }
        )
    }

    /**
     * Adds a momentary button — down while it is held, up when it is let go.
     *
     * ```kotlin
     * controlGroup("voice") {
     *     button("gate") { down ->
     *         if (down) voice.start() else voice.stop()
     *     }
     * }
     * ```
     *
     * Genuinely different from [checkbox], and not only in appearance. A checkbox latches, so it can
     * describe a *state*; a button describes a *gesture*, and "while I am holding this" is a thing a
     * latching control cannot express at all. Every parameter surface eventually needs one: a synth
     * gate, a jog, a preview, a shutter.
     *
     * **A press is never cached.** Every other control in this builder restores its value across a
     * rerun, which is exactly what live-coding wants — recompiling must not snap every slider back to
     * its default. A cached press would instead re-fire on *every* rerun: edit one character of a DSP
     * and the note retriggers. So [onUpdate] is not called on a rerun, and the button always renders up.
     *
     * @param label text shown beside the button
     * @param onUpdate called with `true` on the way down and `false` on the way up
     */
    fun button(label: String, onUpdate: (Boolean) -> Unit) {
        // The id is still derived from the label and ordinal, so both control surfaces name the same
        // button and a rerun does not orphan the one on a tablet — only the *value* is uncached.
        val identity = controlIdentity("button", "button", label)
        registerButtonCallback(identity.id, onUpdate)
        htmlParts += buildButtonHtml(identity.id, label)
        controls += ControllerControl(
            id = identity.id,
            type = "button",
            label = label,
            channel = channel,
            checked = false
        )
    }

    /**
     * Adds a read-only meter: a level you can see, rather than a number you have to print.
     *
     * ```kotlin
     * controlGroup("voice") {
     *     val level = meter("level", min = 0.0, max = 1.0, source = { launch.meter("level") })
     * }
     * ```
     *
     * Returns a [MeterHandle], so a box that already has a loop can push instead:
     * `level.set(rms)`. Either way the value reaches the browser at most [meterUpdateHz] times a
     * second — a rate chosen because that is what a level display needs and a box rerun is nowhere
     * near it — riding the same message a slider's value does, so the remote tablet shows it too. The
     * tablet polls at 450 ms, so there it is an indicator rather than a VU meter.
     *
     * Pass [source] to have Field sample it for you on a single shared daemon thread; the sampler stops
     * when the box runs again without declaring this meter, and stops permanently if the source throws
     * (which is what a stopped engine does).
     *
     * A meter is an output, so it is **not** cached and it accepts no input: pressing it does nothing.
     *
     * @param label text shown beside the meter
     * @param min the level that reads empty
     * @param max the level that reads full
     * @param initialValue the level shown before the first update arrives
     * @param law how level maps to bar length; [ControlLaw.LOGARITHMIC] is usually right for a level
     * @param lawExponent taper strength for [ControlLaw.EXPONENTIAL] and [ControlLaw.BIPOLAR]
     * @param lawCenter the level at the middle of the bar for [ControlLaw.BIPOLAR]
     * @param significantDigits digits shown; nonlinear laws default to 4
     * @param source sampled at [meterUpdateHz] on a shared daemon thread when given
     */
    fun meter(
        label: String,
        min: Double = 0.0,
        max: Double = 1.0,
        initialValue: Double = min,
        law: ControlLaw = ControlLaw.LINEAR,
        lawExponent: Double = 2.0,
        lawCenter: Double = (min + max) / 2,
        significantDigits: Int = defaultSignificantDigits(law),
        source: (() -> Double)? = null
    ): MeterHandle {
        val identity = controlIdentity("meter", "meter", label)
        val spec = ControlLawSpec(law, min, max, lawExponent, lawCenter).also { it.validate(label) }
        val decimalPlaces = if (significantDigits > 0) 0 else 2
        val value = initialValue.coerceIn(minOf(min, max), maxOf(min, max))
        htmlParts += buildMeterHtml(identity.id, label, value, spec, significantDigits, decimalPlaces)
        controls += ControllerControl(
            id = identity.id,
            type = "meter",
            label = label,
            channel = channel,
            min = min,
            max = max,
            step = 0.0,
            value = value,
            decimalPlaces = decimalPlaces,
            law = law.wireName,
            lawExponent = spec.exponent,
            lawCenter = spec.center,
            significantDigits = significantDigits
        )
        val targetDocument = box.takeIf { it.isInsideInitialized() }?.inside?.filename
        val handle = MeterHandle(identity.id, targetDocument)
        if (source != null) {
            registerMeterSampler(controllerControlGroupKey(box.id, channel), handle, source)
        }
        return handle
    }

    /**
     * Adds a boolean toggle to this control group.
     *
     * @param label text shown beside the checkbox
     * @param initialValue value used when no cached value exists
     * @param onUpdate callback invoked with the selected state
     */
    fun checkbox(
        label: String,
        initialValue: Boolean = false,
        onUpdate: (Boolean) -> Unit
    ): ControlValueHandle<Boolean> {
        val cached = cachedControl("checkbox", "checkbox", label)
        val controlId = cached.id
        val value = cached.previous?.checked ?: initialValue
        registerCheckboxCallback(controlId, onUpdate)
        if (cached.previous?.checked != null) {
            onUpdate(value)
        }
        htmlParts += buildCheckboxHtml(controlId, label, value)
        val control = ControllerControl(
            id = controlId,
            type = "checkbox",
            label = label,
            channel = channel,
            checked = value
        )
        controls += control
        box.storeControlGroupValue(cached.cacheKey, control)
        return valueHandle(controlId, { it.checked }, { old, next -> old.copy(checked = next) })
    }

    /**
     * Adds a small text editor to this control group.
     *
     * Use this for names, labels, prompts, and other short text parameters rather than large source
     * or document content.
     *
     * @param label text shown beside the input
     * @param initialValue text used when no cached value exists
     * @param onUpdate callback invoked with the edited text
     */
    fun text(
        label: String,
        initialValue: String = "",
        onUpdate: (String) -> Unit
    ): ControlValueHandle<String> {
        val cached = cachedControl("string", "string", label)
        val controlId = cached.id
        val value = cached.previous?.text ?: initialValue
        registerStringCallback(controlId, onUpdate)
        if (cached.previous?.text != null) {
            onUpdate(value)
        }
        htmlParts += buildStringInputHtml(controlId, label, value)
        val control = ControllerControl(
            id = controlId,
            type = "string",
            label = label,
            channel = channel,
            text = value
        )
        controls += control
        box.storeControlGroupValue(cached.cacheKey, control)
        return valueHandle(controlId, { it.text }, { old, next -> old.copy(text = next) })
    }

    /**
     * Adds a small text editor. This is the descriptive alias for [text].
     */
    fun stringInput(
        label: String,
        initialValue: String = "",
        onUpdate: (String) -> Unit
    ) = text(label, initialValue, onUpdate)

    internal fun buildHtml(): String = htmlParts.joinToString("\n")
    internal fun buildControls(): List<ControllerControl> = controls.toList()
}

/**
 * Builds a compact set of related controls in one output disclosure.
 *
 * Values are cached by channel, control type, label, and occurrence. Rerunning the box therefore
 * preserves the visible control values and feeds them back through each callback. Keep labels and
 * ordering stable when you want that continuity.
 *
 * ```kotlin
 * var gain = 0.35
 * var enabled = true
 *
 * controlGroup("surface") {
 *     slider("gain", min = 0.0, max = 1.0, step = 0.01, initialValue = gain) {
 *         gain = it
 *     }
 *     checkbox("enabled", initialValue = enabled) {
 *         enabled = it
 *     }
 * }
 * ```
 *
 * Use explicit grouped controls when you need custom ranges or callback behavior. Use
 * [syncToProperty] when the main goal is a persistent editable box property.
 *
 * @param channel output disclosure that receives the complete control surface
 * @param block controls to add to the surface
 */
fun Box.controlGroup(channel: String = "controls", block: ControlGroupBuilder.() -> Unit) {
    val builder = ControlGroupBuilder(this, channel)
    builder.apply(block)
    val grouped = builder.buildHtml()
    registerControllerControlGroup(this, channel, builder.buildControls())
    if (grouped.isNotBlank()) {
        display(channel, """<div class="control-group">$grouped</div>""")
    }
}

// Convenience method for syncing controls to box properties
fun Box.syncToProperty(propertyName: String, initialValue: Any, channel: String = "controls", label: String = propertyName) {
    when (initialValue) {
        is Double -> {
            var value = initialValue
            slider(label, 0.0, 1.0, 0.01, value, channel) { newValue ->
                value = newValue
                setPropertyRaw(propertyName, value, doResync = true)
            }
            // Mark as persistable
            if (!Box.ioSeparateProperties.containsKey(propertyName) && !Box.ioPersistentProperties.contains(propertyName)) {
                Box.ioPersistentProperties.add(propertyName)
            }
        }
        is Boolean -> {
            var value = initialValue
            checkbox(label, value, channel) { newValue ->
                value = newValue
                setPropertyRaw(propertyName, value, doResync = true)
            }
            if (!Box.ioSeparateProperties.containsKey(propertyName) && !Box.ioPersistentProperties.contains(propertyName)) {
                Box.ioPersistentProperties.add(propertyName)
            }
        }
        is String -> {
            var value = initialValue
            stringInput(label, value, channel) { newValue ->
                value = newValue
                setPropertyRaw(propertyName, value, doResync = true)
            }
            if (!Box.ioSeparateProperties.containsKey(propertyName) && !Box.ioPersistentProperties.contains(propertyName)) {
                Box.ioPersistentProperties.add(propertyName)
            }
        }
        is Vector2f -> {
            var value = initialValue
            vec2Control(label, value, channel = channel) { newValue ->
                value = newValue
                setPropertyRaw(propertyName, value, doResync = true)
            }
            if (!Box.ioSeparateProperties.containsKey(propertyName) && !Box.ioPersistentProperties.contains(propertyName)) {
                Box.ioPersistentProperties.add(propertyName)
            }
        }
        is Vector4f -> {
            var value = initialValue
            colorPicker(label, value, channel) { newValue ->
                value = newValue
                setPropertyRaw(propertyName, value, doResync = true)
            }
            if (!Box.ioSeparateProperties.containsKey(propertyName) && !Box.ioPersistentProperties.contains(propertyName)) {
                Box.ioPersistentProperties.add(propertyName)
            }
        }
        else -> {
            lprintln("warning,controls", "syncToProperty: unsupported type for property '$propertyName': ${initialValue::class}")
        }
    }
}

fun Box.showLayers() {
    display("layers", "") // magic
}

fun <T> Box.tqdm(items: SizedIterable<T>, name: String = "progress"): Iterable<T> {
    val totalCount = items.size
    // Respect the global switch: when disabled, iterate transparently with no bar.
    if (!ProgressBars.enabled) return items

    val reporter = ProgressReporter(this, name)

    if (totalCount == 0) {
        reporter.finish()
        return items
    }

    reporter.update(0L, totalCount.toLong())

    return Iterable {
        val base = items.iterator()
        var index = 0L

        object : Iterator<T> {
            override fun hasNext(): Boolean {
                val has = base.hasNext()
                // Reaching the end removes the bar from the output-area stack (idempotent).
                if (!has) reporter.finish()
                return has
            }

            override fun next(): T {
                val value = base.next()
                index++
                reporter.update(index, totalCount.toLong())
                return value
            }
        }
    }
}

fun <T> Box.tqdm(items: List<T>, name: String = "progress"): Iterable<T> {
    return tqdm(sizedIterable(items.size) { items.iterator() }, name)
}

fun Box.tqdm(items: IntRange, name: String = "progress"): Iterable<Int> {
    return tqdm(sizedIterable(items.count()) { items.iterator() }, name)
}

fun Box.tqdm(items: Int, name: String = "progress"): Iterable<Int> {
    val range = 0 until items.coerceAtLeast(0)
    return tqdm(range, name)
}

fun <T> Box.tqdm(items: Iterable<T>, name: String = "progress"): Iterable<T> {
    @Suppress("UNCHECKED_CAST")
    return when (items) {
        is SizedIterable<*> -> tqdm(items as SizedIterable<T>, name)
        is List<T> -> tqdm(items, name)
        is Collection<T> -> tqdm(sizedIterable(items.size) { items.iterator() }, name)
        else -> tqdm(items.toList(), name)
    }
}
