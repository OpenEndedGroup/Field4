package afield.debug

import afield.*
import afield.logger.lprintln
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class RuntimeProbeTarget(
    val snapshot: KotlinDebugScriptSnapshot,
    val localLine: Int
)

internal data class RuntimeProbeSample(
    val hitIndex: Int,
    val threadName: String,
    val declaringType: String,
    val method: String,
    val line: Int,
    val resolution: String,
    val valueKind: String,
    val valueType: String?,
    val valueText: String,
    val visualValue: Any?,
    val receivedAtMs: Long
)

internal data class RuntimeProbeView(
    val status: String,
    val error: String?,
    val detail: String?,
    val scope: ScopeSnapshotPayload?,
    val samples: List<RuntimeProbeSample>
)

private class RuntimeProbeSession(
    private val request: DebugProbeRequest,
    private val target: RuntimeProbeTarget
) {
    @Volatile private var status: String = "waiting"
    @Volatile private var error: String? = null
    @Volatile private var detail: String? = null
    @Volatile private var scope: ScopeSnapshotPayload? = null
    private val samples = mutableListOf<RuntimeProbeSample>()

    fun recordAck() {
        status = "waiting"
    }

    fun recordSample(sample: RuntimeProbeSample) {
        synchronized(samples) {
            samples += sample
            while (samples.size > 128) {
                samples.removeAt(0)
            }
        }
        status = "sample"
        error = null
    }

    fun recordComplete(response: CompleteResponse) {
        status = if (response.error == null) "complete" else "error"
        error = response.error
        detail = response.detail
        scope = response.scope
    }

    fun recordError(message: String) {
        status = "error"
        error = message
        detail = null
        scope = null
    }

    fun renderHtml(): String = RuntimeDebugProbeService.renderProbeHtml(request, target, snapshot())

    private fun snapshot(): RuntimeProbeView = RuntimeProbeView(
        status = status,
        error = error,
        detail = detail,
        scope = scope,
        samples = synchronized(samples) { samples.toList() }
    )
}

object RuntimeDebugProbeService {
    private const val helperMainClass = "afield.debug.JdiProbeHelperKt"
    private const val probeChannel = "debug probe"
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val clientLock = Any()
    @Volatile private var client: LiveProbeClient? = null

    fun availabilityError(): String? {
        val port = jdwpPort() ?: return "Runtime debug probe is disabled. Start Field with `AFIELD_JDWP_PORT=<port>` to enable JDWP."
        if (port <= 0) return "Runtime debug probe JDWP port must be a positive integer."
        return null
    }

    fun startProbe(
        box: Box,
        request: DebugProbeRequest,
        execution: KotlinExecution,
        reply: (Message) -> Unit
    ) {
        val availability = availabilityError()
        if (availability != null) {
            reply(errorEvent(request, availability))
            return
        }

        val target = resolveTarget(box, request, execution)
        if (target == null) {
            reply(errorEvent(request, "No running compiled snippet matches the current editor text. Relaunch that box, then retry the probe."))
            return
        }

        val session = RuntimeProbeSession(request, target)
        session.recordAck()
        reply(renderEvent(request, "ack", session.renderHtml(), done = false, sequence = 0))

        val liveClient = try {
            client()
        } catch (t: Throwable) {
            lprintln("error,debug-probe", "failed to start helper: ${t.message}")
            session.recordError(t.message ?: "Failed to start debug helper")
            reply(renderEvent(request, "error", session.renderHtml(), done = true, sequence = 1))
            return
        }

        val probeRequest = ProbeVariableRequest(
            requestId = request.requestId,
            className = target.snapshot.className,
            sourceName = target.snapshot.sourceName,
            sourceNamePrefix = target.snapshot.sourceName.dropLastWhile { it.isDigit() },
            line = target.localLine,
            variableName = request.symbol,
            threadName = request.threadName,
            maxHits = request.maxHits,
            timeoutMs = request.timeoutMs
        )

        liveClient.probe(probeRequest) { response ->
            when (response) {
                is AckResponse -> Unit
                is SampleResponse -> {
                    val sample = RuntimeProbeSample(
                        hitIndex = response.hitIndex,
                        threadName = response.threadName,
                        declaringType = response.declaringType,
                        method = response.method,
                        line = response.line,
                        resolution = response.resolution,
                        valueKind = response.value.kind,
                        valueType = response.value.type,
                        valueText = response.value.text,
                        visualValue = toVisualizationValue(response.value),
                        receivedAtMs = System.currentTimeMillis()
                    )
                    session.recordSample(sample)
                    reply(
                        renderEvent(
                            request = request,
                            phase = "sample",
                            html = session.renderHtml(),
                            done = false,
                            sequence = response.hitIndex
                        )
                    )
                }

                is CompleteResponse -> {
                    session.recordComplete(response)
                    reply(
                        renderEvent(
                            request = request,
                            phase = "complete",
                            html = session.renderHtml(),
                            done = true,
                            sequence = response.hits + 1
                        )
                    )
                }

                is ErrorResponse -> {
                    session.recordError(response.detail ?: response.error)
                    reply(
                        renderEvent(
                            request = request,
                            phase = "error",
                            html = session.renderHtml(),
                            done = true,
                            sequence = 1
                        )
                    )
                }
            }
        }
    }

    private fun resolveTarget(
        box: Box,
        request: DebugProbeRequest,
        execution: KotlinExecution
    ): RuntimeProbeTarget? {
        val segment = execution.resolveDebugProbeSegment(box, request.allCode, request.line) ?: return null
        val snapshot = box.ki.context.debugScripts.latestMatchingSource(segment.expandedText) ?: return null
        return RuntimeProbeTarget(snapshot = snapshot, localLine = segment.localLine)
    }

    private fun client(): LiveProbeClient {
        client?.takeIf { it.isAlive() }?.let { return it }

        synchronized(clientLock) {
            client?.takeIf { it.isAlive() }?.let { return it }
            client?.close()

            val javaHome = System.getProperty("java.home")
            val javaName = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
            val javaBin = File(javaHome, "bin/$javaName")

            val next = LiveProbeClient(
                helperMainClass = helperMainClass,
                debugHost = jdwpHost(),
                debugPort = jdwpPort() ?: error("JDWP port missing"),
                javaBin = javaBin.absolutePath
            )
            client = next
            return next
        }
    }

    private fun jdwpHost(): String =
        System.getProperty("afield.jdwp.host")
            ?: System.getenv("AFIELD_JDWP_HOST")
            ?: "127.0.0.1"

    private fun jdwpPort(): Int? {
        val raw = System.getProperty("afield.jdwp.port")
            ?: System.getenv("AFIELD_JDWP_PORT")
            ?: return null
        return raw.toIntOrNull()
    }

    private fun errorEvent(request: DebugProbeRequest, message: String): Message =
        renderEvent(
            request = request,
            phase = "error",
            html = renderImmediateErrorHtml(request, message),
            done = true,
            sequence = 1
        )

    private fun renderEvent(
        request: DebugProbeRequest,
        phase: String,
        html: String,
        done: Boolean,
        sequence: Int
    ): Message = Message("debug-probe-event").payload(
        DebugProbeEvent(
            requestId = request.requestId,
            uid = request.uid,
            phase = phase,
            symbol = request.symbol,
            line = request.line,
            html = html,
            channel = probeChannel,
            annotation = probeAnnotation(request.symbol),
            done = done,
            sequence = sequence
        )
    )

    private fun renderImmediateErrorHtml(request: DebugProbeRequest, message: String): String = buildString {
        append("<div style='min-width:320px; max-width:900px; color:#ddd; font:12px/1.45 ui-monospace, SFMono-Regular, Menlo, monospace;'>")
        append("<div style='display:flex; align-items:center; gap:8px; flex-wrap:wrap;'>")
        append("<div style='font-size:13px; color:#ff9b9b;'><strong>debug probe</strong> <code>")
        append(escapeHtml(request.symbol))
        append("</code></div>")
        append(statusBadge("error", true))
        append("</div>")
        append("<div style='margin-top:8px; color:#ffb3b3;'>")
        append(escapeHtml(message))
        append("</div>")
        append("</div>")
    }

    internal fun renderProbeHtml(request: DebugProbeRequest, target: RuntimeProbeTarget, view: RuntimeProbeView): String = buildString {
        val samples = view.samples
        val latest = samples.lastOrNull()
        val errorMessage = view.error?.let { humanizeProbeError(it, samples.size) }
        val statusText = when {
            view.status == "waiting" -> "waiting"
            view.status == "sample" -> "sampling"
            view.status == "complete" -> "complete"
            else -> "error"
        }
        val statusColor = if (view.status == "error") "#ff9b9b" else "#9fd0ff"

        append("<div style='min-width:340px; max-width:980px; color:#ddd; font:12px/1.45 ui-monospace, SFMono-Regular, Menlo, monospace;'>")
        append("<div style='display:flex; align-items:center; gap:8px; flex-wrap:wrap;'>")
        append("<div style='font-size:13px; color:")
        append(statusColor)
        append(";'><strong>debug probe</strong> <code>")
        append(escapeHtml(request.symbol))
        append("</code></div>")
        append(statusBadge(statusText, view.status == "error"))
        append(metaChip("${samples.size}/${request.maxHits} samples"))
        latest?.let { append(metaChip(it.resolution)) }
        latest?.valueType?.takeIf { it.isNotBlank() }?.let { append(metaChip(it)) }
        append("</div>")

        append("<div style='margin-top:6px; color:#888;'>")
        append(escapeHtml(target.snapshot.sourceName))
        append(":")
        append(target.localLine)
        target.snapshot.className?.takeIf { it.isNotBlank() }?.let {
            append(" • ")
            append(escapeHtml(it))
        }
        append("</div>")

        append("<div style='display:flex; gap:8px; flex-wrap:wrap; margin-top:8px;'>")
        append(summaryCard("Arrivals", arrivalSummary(samples)))
        append(summaryCard("Latest", latest?.valueText?.let { truncateText(it) } ?: "Waiting for first hit"))
        append(summaryCard("Threads", threadSummary(samples)))
        append("</div>")

        if (errorMessage != null) {
            append("<div style='margin-top:10px; padding:8px 10px; border-radius:8px; background:rgba(120,24,24,0.34); color:#ffc6c6;'>")
            append(escapeHtml(errorMessage))
            view.detail?.takeIf { it.isNotBlank() }?.let {
                append("<div style='margin-top:4px; color:#ffdddd;'>")
                append(escapeHtml(it))
                append("</div>")
            }
            append("</div>")
        } else if (view.status == "waiting") {
            append("<div style='margin-top:10px; padding:8px 10px; border-radius:8px; background:rgba(60,80,120,0.18); color:#c9ddff;'>")
            append("Waiting for an execution hit on this line.")
            append("</div>")
        }

        if (samples.isNotEmpty()) {
            append(renderRecentSamples(samples))
        }

        if (shouldRenderVisualizer(samples.size, request.maxHits, view.status != "waiting")) {
            append("<div style='margin-top:12px;'>")
            append(samples.map { it.visualValue }.h2)
            append("</div>")
        }

        view.scope?.takeIf { it.symbols.isNotEmpty() }?.let {
            append(renderScopeSnapshot(it))
        }

        append("</div>")
    }

    private fun renderRecentSamples(samples: List<RuntimeProbeSample>): String = buildString {
        append("<div style='margin-top:12px;'>")
        append("<div style='color:#9ad; margin-bottom:6px;'>Recent hits</div>")
        append("<div style='display:grid; gap:6px;'>")
        samples.takeLast(8).forEach { sample ->
            append("<div style='padding:8px 10px; border-radius:8px; background:rgba(255,255,255,0.04);'>")
            append("<div style='display:flex; gap:8px; flex-wrap:wrap; align-items:center;'>")
            append(metaChip("#${sample.hitIndex}"))
            append(metaChip(formatTime(sample.receivedAtMs)))
            append(metaChip("${sample.declaringType}.${sample.method}:${sample.line}"))
            append(metaChip(sample.threadName))
            append("</div>")
            append("<div style='margin-top:6px; color:#f0f0f0;'><code>")
            append(escapeHtml(truncateText(sample.valueText)))
            append("</code></div>")
            append("</div>")
        }
        append("</div>")
        append("</div>")
    }

    private fun renderScopeSnapshot(scope: ScopeSnapshotPayload): String = buildString {
        append("<div style='margin-top:12px;'>")
        append("<div style='color:#9ad; margin-bottom:6px;'>Symbols in scope</div>")
        append("<div style='display:grid; gap:6px;'>")
        scope.symbols.forEach { symbol ->
            append("<div style='padding:8px 10px; border-radius:8px; background:rgba(255,255,255,0.04);'>")
            append("<div style='display:flex; gap:8px; flex-wrap:wrap; align-items:center;'>")
            append("<code>")
            append(escapeHtml(symbol.name))
            append("</code>")
            append(metaChip(symbol.resolution))
            symbol.type?.takeIf { it.isNotBlank() }?.let { append(metaChip(it)) }
            symbol.method?.takeIf { it.isNotBlank() }?.let {
                val where = if (symbol.line > 0) "$it:${symbol.line}" else it
                append(metaChip(where))
            }
            append("</div>")
            symbol.preview.takeIf { it.isNotBlank() }?.let {
                append("<div style='margin-top:6px; color:#ccc;'><code>")
                append(escapeHtml(it))
                append("</code></div>")
            }
            append("</div>")
        }
        if (scope.omittedCount > 0) {
            append("<div style='color:#888; margin-top:4px;'>")
            append(scope.omittedCount)
            append(" more symbol(s) omitted.</div>")
        }
        append("</div>")
        append("</div>")
    }

    private fun metaChip(text: String): String =
        "<span style='display:inline-block; padding:2px 8px; border-radius:999px; background:rgba(255,255,255,0.08); color:#cfd8e3;'>${escapeHtml(text)}</span>"

    private fun statusBadge(text: String, error: Boolean): String =
        "<span style='display:inline-block; padding:2px 8px; border-radius:999px; background:${if (error) "rgba(160,32,32,0.38)" else "rgba(56,92,152,0.32)"}; color:${if (error) "#ffd0d0" else "#d8e7ff"};'>${escapeHtml(text)}</span>"

    private fun summaryCard(label: String, value: String): String = buildString {
        append("<div style='min-width:180px; padding:8px 10px; border-radius:8px; background:rgba(255,255,255,0.04);'>")
        append("<div style='color:#888; margin-bottom:4px;'>")
        append(escapeHtml(label))
        append("</div>")
        append("<div style='color:#f0f0f0;'>")
        append(escapeHtml(value))
        append("</div>")
        append("</div>")
    }

    private fun arrivalSummary(samples: List<RuntimeProbeSample>): String {
        val first = samples.firstOrNull() ?: return "No hits yet"
        val last = samples.last()
        if (samples.size == 1) return formatTime(first.receivedAtMs)
        val deltaMs = (last.receivedAtMs - first.receivedAtMs).coerceAtLeast(0L)
        return "${formatTime(first.receivedAtMs)} - ${formatTime(last.receivedAtMs)} (${formatDuration(deltaMs)})"
    }

    private fun threadSummary(samples: List<RuntimeProbeSample>): String {
        if (samples.isEmpty()) return "No hits yet"
        val names = samples.map { it.threadName }.distinct()
        return if (names.size == 1) names.first() else "${names.size} threads (${names.first()})"
    }

    private fun formatTime(ms: Long): String =
        timeFormatter.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime())

    private fun formatDuration(ms: Long): String {
        if (ms < 1_000) return "${ms}ms"
        val seconds = ms / 1_000
        val millis = ms % 1_000
        return "${seconds}.${millis.toString().padStart(3, '0')}s"
    }

    private fun shouldRenderVisualizer(sampleCount: Int, maxHits: Int, settled: Boolean): Boolean {
        val threshold = minOf(4, maxHits.coerceAtLeast(2))
        return sampleCount >= threshold || (settled && sampleCount >= 2)
    }

    private fun humanizeProbeError(error: String, sampleCount: Int): String =
        when (error) {
            "symbol_not_visible" -> "The symbol was not visible at the executable location(s) for this line."
            "timed_out_waiting_for_hit" -> if (sampleCount > 0) "The probe timed out before collecting the requested samples." else "The probe timed out before the line executed."
            "missing_debug_info" -> "This class was loaded without local variable debug information."
            "thread_not_inspectable" -> "The debugger could not inspect the current thread state."
            "no_executable_location" -> "No executable bytecode location matched that source line."
            "class_not_loaded" -> "The generated class for this snippet is not loaded in the JVM yet."
            "thread_not_found" -> "The requested thread name was not found."
            "request_install_failed" -> "The debug helper failed while installing the probe."
            else -> error
        }

    private fun toVisualizationValue(value: ValuePayload): Any? =
        when (value.kind) {
            "null" -> null
            "primitive" -> when (value.type) {
                "boolean" -> value.text.toBooleanStrictOrNull() ?: value.text
                "byte" -> value.text.toByteOrNull() ?: value.text
                "short" -> value.text.toShortOrNull() ?: value.text
                "int" -> value.text.toIntOrNull() ?: value.text
                "long" -> value.text.toLongOrNull() ?: value.text
                "float" -> value.text.toFloatOrNull() ?: value.text
                "double" -> value.text.toDoubleOrNull() ?: value.text
                else -> value.text
            }

            "string" -> value.text
            else -> value.text
        }

    private fun probeAnnotation(symbol: String): String = "<i>debug ${escapeHtml(symbol)}</i>"

    private fun truncateText(text: String, maxChars: Int = 140): String {
        val compact = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (compact.length <= maxChars) return compact
        return compact.take(maxChars - 1) + "…"
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
