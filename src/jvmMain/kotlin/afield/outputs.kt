package afield

import afield.logger.lprintln
import afield.macros.Insert
import afield.server.Inspector
import afield.server.LogStream
import afield.stdlib.FLineIntersection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.joml.Vector2f
import org.joml.Vector4f
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * printing inside a for loop just gives the last output?
 */
interface HasOutput {
    fun print(message: Any?)
    fun println(message: Any?)
    fun display(message: Any?)
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

fun Box.progressBar(name: String, value: Double, details: String? = null) {
    val channel = name.trim().ifEmpty { "progress" }
    val inlineChannel = if (channel.startsWith("*")) channel else "*$channel"
    display(inlineChannel, buildInlineProgressBarHtml(value, details))
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
 * The returned Frames ends when the process exits; ending the Frames will kill the process.
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

    suspend fun reportEnd(exitCode: Int, terminated: Boolean = false) {
        if (reportedEnd) return
        reportedEnd = true
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

    runner.atEnd = suspend {
        process?.let { proc ->
            var terminated = false
            if (proc.isAlive) {
                terminated = true
                proc.destroy()
                if (!proc.waitFor(250, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor(250, TimeUnit.MILLISECONDS)
                }
                box.display(
                    outputChannel,
                    """<pre class="process-line stop" style="margin:0;font-family:monospace;">[$label] terminated</pre>"""
                )
            }
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
<iframe src='${ln}' style='border:none;padding:0;margin:0'>
""")
}

fun Box.column(channel: String = "debug", a : Any?) {
    val aa = a ?: "null"
    val ln = Inspector.linkFor(aa, "/browse")
    display(channel, """
<iframe src='${ln}' style='border:none;padding:0;margin:0'>
""")
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

// Slider support
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

fun Box.slider(
    label: String,
    min: Double = 0.0,
    max: Double = 1.0,
    step: Double = 0.01,
    initialValue: Double = (min + max) / 2,
    channel: String = "controls",
    onUpdate: (Double) -> Unit
) {
    val sliderId = "slider-${UUID.randomUUID()}"
    registerSliderCallback(sliderId, onUpdate)

    val html = buildSliderHtml(sliderId, label, min, max, step, initialValue)
    display(channel, html)
}

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

private fun buildSliderHtml(
    sliderId: String,
    label: String,
    min: Double,
    max: Double,
    step: Double,
    value: Double
): String {
    val decimalPlaces = calculateDecimalPlaces(step)
    val formattedValue = "%.${decimalPlaces}f".format(value)
    val viewWidth = 320
    val viewHeight = 44
    val centerY = viewHeight / 2
    val padding = 14
    val clampedValue = value.coerceIn(min, max)
    val trackLength = viewWidth - (padding * 2)
    val normalized = if (max - min == 0.0) 0.0 else (clampedValue - min) / (max - min)
    val cx = padding + (trackLength * normalized)
    return """
        <div class="field-control field-slider"
             data-slider-id="$sliderId"
             data-min="$min"
             data-max="$max"
             data-step="$step"
             data-decimal-places="$decimalPlaces"
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

// 2D Vector control (using JOML Vector2f)
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

// Color picker control (vec4, normalized - using JOML Vector4f)
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

// ------------------------------------------------------------------------
// DSL-style grouping of controls

private const val controlGroupCacheProperty = "_controlGroupCache"

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

private fun Box.updateCachedControlGroupValue(control: ControllerControl) {
    val cache = existingControlGroupCache() ?: return
    val cacheKey = cache.entries.firstOrNull { it.value.id == control.id }?.key ?: return
    cache[cacheKey] = control
}

internal fun registerControllerControlGroup(box: Box, channel: String, controls: List<ControllerControl>) {
    val key = controllerControlGroupKey(box.id, channel)
    controllerControlGroups[key] = controls
    controllerControlGroupBoxes[key] = box
}

internal fun controllerControlsForBox(boxId: String): List<ControllerControl> {
    val prefix = "$boxId\u0000"
    return controllerControlGroups.entries
        .filter { it.key.startsWith(prefix) }
        .sortedBy { it.key.substringAfter('\u0000') }
        .flatMap { it.value }
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

class ControlGroupBuilder(private val box: Box, private val channel: String) {
    private val htmlParts = mutableListOf<String>()
    private val controls = mutableListOf<ControllerControl>()
    private val ordinals = mutableMapOf<String, Int>()
    private val cache = box.controlGroupCache()

    private data class CachedControl(val cacheKey: String, val id: String, val previous: ControllerControl?)

    private fun cachedControl(type: String, prefix: String, label: String): CachedControl {
        val ordinalKey = "$type\u0000$label"
        val ordinal = ordinals.getOrDefault(ordinalKey, 0)
        ordinals[ordinalKey] = ordinal + 1
        val cacheKey = controlGroupControlKey(channel, type, label, ordinal)
        return CachedControl(cacheKey, stableControlId(prefix, box.id, cacheKey), cache[cacheKey])
    }

    fun slider(
        label: String,
        min: Double = 0.0,
        max: Double = 1.0,
        step: Double = 0.01,
        initialValue: Double = (min + max) / 2,
        onUpdate: (Double) -> Unit
    ) {
        val cached = cachedControl("slider", "slider", label)
        val sliderId = cached.id
        val value = cached.previous?.value?.coerceIn(min, max) ?: initialValue
        registerSliderCallback(sliderId, onUpdate)
        if (cached.previous?.value != null) {
            onUpdate(value)
        }
        htmlParts += buildSliderHtml(sliderId, label, min, max, step, value)
        val control = ControllerControl(
            id = sliderId,
            type = "slider",
            label = label,
            channel = channel,
            min = min,
            max = max,
            step = step,
            value = value,
            decimalPlaces = calculateDecimalPlaces(step)
        )
        controls += control
        cache[cached.cacheKey] = control
    }

    fun colorPicker(
        label: String,
        initialValue: Vector4f = Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
        onUpdate: (Vector4f) -> Unit
    ) {
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
        cache[cached.cacheKey] = control
    }

    fun vec2Control(
        label: String,
        initialValue: Vector2f = Vector2f(0.0f, 0.0f),
        xRange: Pair<Double, Double> = -1.0 to 1.0,
        yRange: Pair<Double, Double> = -1.0 to 1.0,
        step: Double = 0.01,
        onUpdate: (Vector2f) -> Unit
    ) {
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
        cache[cached.cacheKey] = control
    }

    fun checkbox(
        label: String,
        initialValue: Boolean = false,
        onUpdate: (Boolean) -> Unit
    ) {
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
        cache[cached.cacheKey] = control
    }

    fun text(
        label: String,
        initialValue: String = "",
        onUpdate: (String) -> Unit
    ) {
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
        cache[cached.cacheKey] = control
    }

    fun stringInput(
        label: String,
        initialValue: String = "",
        onUpdate: (String) -> Unit
    ) = text(label, initialValue, onUpdate)

    internal fun buildHtml(): String = htmlParts.joinToString("\n")
    internal fun buildControls(): List<ControllerControl> = controls.toList()
}

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
    val startedAt = System.nanoTime()

    fun stats(done: Int): String {
        val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val rate = done / elapsedSeconds.coerceAtLeast(1e-9)
        val remaining = (totalCount - done).coerceAtLeast(0)
        val etaSeconds = if (rate > 1e-9) remaining / rate else Double.POSITIVE_INFINITY
        val rateText = String.format(Locale.US, "%.1f", rate)
        return "$done/$totalCount $rateText it/s ${formatProgressDuration(elapsedSeconds)}<${formatProgressDuration(etaSeconds)}"
    }

    if (totalCount == 0) {
        progressBar(name, 1.0, stats(0))
        return items
    }

    progressBar(name, 0.0, stats(0))
    val total = totalCount.toDouble()

    return Iterable {
        val base = items.iterator()
        var index = 0
        var finished = false

        object : Iterator<T> {
            override fun hasNext(): Boolean {
                val has = base.hasNext()
                if (!has && !finished) {
                    finished = true
                    progressBar(name, 1.0, stats(totalCount))
                }
                return has
            }

            override fun next(): T {
                val value = base.next()
                index++
                progressBar(name, index / total, stats(index))
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
