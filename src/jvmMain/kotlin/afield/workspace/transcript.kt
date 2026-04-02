package afield

import afield.logger.lprintln
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val launchSnapshotProperties = listOf(
    "code",
    "vertex",
    "fragment",
    "task",
    "mesh",
    "compute",
    "vertex_s",
    "fragment_s",
    "task_s",
    "mesh_s",
    "compute_s"
)
private val launchedPropertyChangeProperties = setOf(
    "vertex",
    "fragment",
    "task",
    "mesh",
    "compute",
    "vertex_s",
    "fragment_s",
    "task_s",
    "mesh_s",
    "compute_s"
)

object FlightRecorder {

    private data class DocumentState(
        val documentPath: String,
        val lock: Any = Any(),
        val entries: MutableList<LinkedHashMap<String, Any?>> = mutableListOf(),
        val launchedBoxes: MutableSet<String> = mutableSetOf(),
        var loaded: Boolean = false,
        var dirty: Boolean = false
    )

    private val states = ConcurrentHashMap<String, DocumentState>()

    fun recordExecuteEntrypoint(entrypoint: String, box: Box, execute: Execute) {
        val documentPath = documentPathFor(box) ?: return
        append(stateFor(documentPath)) { state, nowMs, nowIso ->
            eventBase(
                state = state,
                box = box,
                nowMs = nowMs,
                nowIso = nowIso,
                event = "execute",
                entrypoint = entrypoint
            ).apply {
                this["type"] = execute.type
                this["line"] = execute.line
                this["strand"] = execute.strand
                this["singleLine"] = execute.singleLine
                this["code"] = execute.code
            }
        }
    }

    fun recordExecuteSymbolEntrypoint(entrypoint: String, box: Box, executeSymbol: ExecuteSymbol) {
        val documentPath = documentPathFor(box) ?: return
        append(stateFor(documentPath)) { state, nowMs, nowIso ->
            eventBase(
                state = state,
                box = box,
                nowMs = nowMs,
                nowIso = nowIso,
                event = "execute-symbol",
                entrypoint = entrypoint
            ).apply {
                this["line"] = executeSymbol.line
                this["symbol"] = executeSymbol.symbol
                this["code"] = "${executeSymbol.symbol}()"
            }
        }
    }

    fun recordLaunchEntrypoint(entrypoint: String, box: Box, execute: Execute, strand: String = execute.strand) {
        val documentPath = documentPathFor(box) ?: return
        append(stateFor(documentPath)) { state, nowMs, nowIso ->
            state.launchedBoxes += box.id
            eventBase(
                state = state,
                box = box,
                nowMs = nowMs,
                nowIso = nowIso,
                event = "launch",
                entrypoint = entrypoint
            ).apply {
                this["type"] = execute.type
                this["line"] = execute.line
                this["strand"] = strand
                this["singleLine"] = execute.singleLine
                this["code"] = execute.code
                this["properties"] = launchPropertySnapshot(box, codeOverride = execute.code)
            }
        }
    }

    fun recordStopEntrypoint(entrypoint: String, box: Box) {
        val documentPath = documentPathFor(box) ?: return
        append(stateFor(documentPath)) { state, nowMs, nowIso ->
            val wasLaunched = state.launchedBoxes.contains(box.id)
            state.launchedBoxes -= box.id
            eventBase(
                state = state,
                box = box,
                nowMs = nowMs,
                nowIso = nowIso,
                event = "stop",
                entrypoint = entrypoint
            ).apply {
                this["wasLaunched"] = wasLaunched
                this["properties"] = launchPropertySnapshot(box, codeOverride = null)
            }
        }
    }

    fun recordTrackedPropertyChange(box: Box, property: String, oldValue: Any?, newValue: Any?) {
        if (property !in launchedPropertyChangeProperties) return
        if (oldValue == newValue) return

        val documentPath = documentPathFor(box) ?: return
        val state = states[documentPath] ?: return

        append(state, guard = { it.launchedBoxes.contains(box.id) }) { s, nowMs, nowIso ->
            eventBase(
                state = s,
                box = box,
                nowMs = nowMs,
                nowIso = nowIso,
                event = "property-change",
                entrypoint = "setPropertyRaw"
            ).apply {
                this["property"] = property
                this["oldValue"] = oldValue?.toString()
                this["newValue"] = newValue?.toString()
            }
        }
    }

    fun checkpointOnMetadataChange(documentPath: String): Boolean {
        val normalizedDocumentPath = normalizeDocumentPath(documentPath)
        val state = states[normalizedDocumentPath] ?: return false
        val snapshot = synchronized(state.lock) {
            ensureLoaded(state)
            if (!state.dirty || state.entries.isEmpty()) return false
            state.entries.map { LinkedHashMap(it) }
        }

        return try {
            val wrote = saveSnapshot(normalizedDocumentPath, snapshot)
            if (wrote) {
                synchronized(state.lock) {
                    state.dirty = false
                }
            }
            wrote
        } catch (t: Throwable) {
            lprintln(
                "warning,transcript",
                "Failed to checkpoint transcript for $normalizedDocumentPath: ${t.message}"
            )
            false
        }
    }

    @JvmStatic
    fun entriesForDocument(documentPath: String): List<LinkedHashMap<String, Any?>> {
        val normalizedDocumentPath = normalizeDocumentPath(documentPath)
        val state = states[normalizedDocumentPath]
        if (state != null) {
            synchronized(state.lock) {
                ensureLoaded(state)
                if (state.entries.isNotEmpty()) {
                    return state.entries.map { LinkedHashMap(it) }
                }
            }
        }
        return readEntriesFromDisk(normalizedDocumentPath)
    }

    @JvmStatic
    fun entriesFor(box: Box): List<LinkedHashMap<String, Any?>> {
        val documentPath = documentPathFor(box) ?: return emptyList()
        return entriesForDocument(documentPath)
    }

    @JvmStatic
    fun payloadForDocument(documentPath: String): LinkedHashMap<String, Any?> {
        val entries = entriesForDocument(documentPath)
        return linkedMapOf(
            "version" to 1,
            "entries" to entries
        )
    }

    @JvmStatic
    fun payloadFor(box: Box): LinkedHashMap<String, Any?> {
        val documentPath = documentPathFor(box) ?: return linkedMapOf(
            "version" to 1,
            "entries" to emptyList<Any?>()
        )
        return payloadForDocument(documentPath)
    }

    @JvmStatic
    fun htmlForDocument(documentPath: String): String = renderTranscriptHtml(entriesForDocument(documentPath))

    @JvmStatic
    fun htmlFor(box: Box): String {
        val documentPath = documentPathFor(box) ?: return ""
        return htmlForDocument(documentPath)
    }

    private fun stateFor(documentPath: String): DocumentState =
        states.computeIfAbsent(normalizeDocumentPath(documentPath)) { DocumentState(documentPath = it) }

    private fun append(
        state: DocumentState,
        guard: ((DocumentState) -> Boolean)? = null,
        builder: (DocumentState, Long, String) -> LinkedHashMap<String, Any?>
    ) {
        synchronized(state.lock) {
            ensureLoaded(state)
            if (guard != null && !guard(state)) return

            val nowMs = System.currentTimeMillis()
            val nowIso = Instant.ofEpochMilli(nowMs).toString()
            val entry = builder(state, nowMs, nowIso)
            entry["index"] = state.entries.size
            state.entries += entry
            state.dirty = true
        }
    }

    private fun ensureLoaded(state: DocumentState) {
        if (state.loaded) return
        state.loaded = true
        // Start fresh per Field process launch.
        // We intentionally do not load any previous transcript from disk.
    }

    private fun saveSnapshot(documentPath: String, snapshot: List<LinkedHashMap<String, Any?>>): Boolean {
        val file = transcriptFile(documentPath)
        file.parentFile?.mkdirs()
        val payload = linkedMapOf<String, Any?>(
            "version" to 1,
            "entries" to snapshot
        )
        val text = Edn.write(payload, Edn.CustomTypes())
        return file.safeSave(text)
    }

    private fun readEntriesFromDisk(documentPath: String): List<LinkedHashMap<String, Any?>> {
        val file = transcriptFile(documentPath)
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = Edn.read(file.readText()) as? Map<*, *> ?: return@runCatching emptyList()
            val entries = root["entries"] as? List<*> ?: return@runCatching emptyList()
            entries.mapNotNull { entry ->
                val map = mapOfStringAny(entry) ?: return@mapNotNull null
                LinkedHashMap(map)
            }
        }.getOrElse {
            lprintln("warning,transcript", "Failed to read transcript from $documentPath: ${it.message}")
            emptyList()
        }
    }

    private fun renderTranscriptHtml(snapshot: List<LinkedHashMap<String, Any?>>): String {
        if (snapshot.isEmpty()) return ""
        return buildString {
            append("<div class=\"field-transcript\" style=\"display:flex;flex-direction:column;gap:18px;\">")
            snapshot.forEach { entry ->
                append(renderTranscriptEntryHtml(entry))
            }
            append("</div>")
        }
    }

    private fun renderTranscriptEntryHtml(entry: Map<String, Any?>): String {
        val event = entry["event"]?.toString().orEmpty()
        val box = mapOfStringAny(entry["box"])
        val primaryProperty = transcriptPrimaryProperty(entry)
        val line = entry["line"].asInt()?.takeIf { it > 0 }
        val heading = buildString {
            append(transcriptEventLabel(event))
            box?.let {
                append(" · ")
                append(boxLinkHtml(it, property = primaryProperty, line = line))
            }
            if (!primaryProperty.isNullOrBlank()) {
                append(" <span style=\"font-size:0.72em;font-weight:400;color:rgba(255,255,255,0.56);\">")
                append(escapeHtml(primaryProperty))
                append("</span>")
            }
        }

        return buildString {
            append(
                """
                <section class="field-transcript-entry" style="padding:16px 18px;border-radius:12px;border:1px solid rgba(255,255,255,0.08);background:rgba(17,20,24,0.9);box-shadow:0 8px 30px rgba(0,0,0,0.18);">
                  <h2 style="margin:0 0 10px 0;font-size:18px;line-height:1.25;font-weight:560;color:#f6fbff;">$heading</h2>
                """.trimIndent()
            )
            append(renderTranscriptFacts(entry, box))
            append(renderTranscriptBody(entry, box, primaryProperty))
            append("</section>")
        }
    }

    private fun renderTranscriptFacts(entry: Map<String, Any?>, box: Map<String, Any?>?): String {
        val facts = linkedMapOf<String, String>()
        entry["timestamp"]?.toString()?.takeIf { it.isNotBlank() }?.let { facts["Time"] = it }
        entry["entrypoint"]?.toString()?.takeIf { it.isNotBlank() }?.let { facts["Entrypoint"] = it }
        entry["language"]?.toString()?.takeIf { it.isNotBlank() }?.let { facts["Language"] = it }
        entry["document"]?.toString()?.takeIf { it.isNotBlank() }?.let { facts["Document"] = it.substringAfterLast(File.separatorChar) }
        entry["strand"]?.toString()?.takeIf { it.isNotBlank() }?.let { facts["Strand"] = it }
        entry["line"].asInt()?.takeIf { it > 0 }?.let { facts["Line"] = it.toString() }
        entry["symbol"]?.toString()?.takeIf { it.isNotBlank() }?.let { facts["Symbol"] = it }
        entry["singleLine"]?.toString()?.takeIf { it.isNotBlank() }?.let { facts["Single Line"] = it }
        entry["wasLaunched"]?.toString()?.takeIf { it.isNotBlank() }?.let { facts["Was Launched"] = it }

        box?.get("frame")?.let { frameAny ->
            val frame = mapOfStringAny(frameAny)
            if (frame != null) {
                val x = frame["x"]?.toString()
                val y = frame["y"]?.toString()
                val w = frame["w"]?.toString()
                val h = frame["h"]?.toString()
                if (x != null && y != null && w != null && h != null) {
                    facts["Frame"] = "($x, $y) $w×$h"
                }
            }
        }

        if (facts.isEmpty()) return ""

        return buildString {
            append("<div style=\"display:flex;flex-wrap:wrap;gap:8px 16px;margin:0 0 12px 0;font-size:12px;line-height:1.45;color:rgba(255,255,255,0.7);\">")
            facts.forEach { (label, value) ->
                append("<span><span style=\"color:rgba(255,255,255,0.46);text-transform:uppercase;letter-spacing:0.06em;\">")
                append(escapeHtml(label))
                append("</span> <span style=\"color:#edf5fb;\">")
                append(escapeHtml(value))
                append("</span></span>")
            }
            append("</div>")
        }
    }

    private fun renderTranscriptBody(
        entry: Map<String, Any?>,
        box: Map<String, Any?>?,
        primaryProperty: String?
    ): String {
        return buildString {
            val code = entry["code"]?.toString()
            if (!code.isNullOrBlank()) {
                append(renderCodeBlock(
                    title = when (entry["event"]?.toString()) {
                        "execute-symbol" -> "Expanded Code"
                        else -> "Code"
                    },
                    box = box,
                    property = primaryProperty,
                    content = code
                ))
            }

            if (entry["event"] == "property-change") {
                val property = entry["property"]?.toString()?.ifBlank { primaryProperty } ?: primaryProperty
                entry["oldValue"]?.toString()?.let {
                    append(renderCodeBlock("Previous Value", box, property, it))
                }
                entry["newValue"]?.toString()?.let {
                    append(renderCodeBlock("New Value", box, property, it))
                }
            }

            val properties = mapOfStringAny(entry["properties"])
            if (!properties.isNullOrEmpty()) {
                append(renderPropertySnapshot(properties, box))
            }
        }
    }

    private fun renderPropertySnapshot(properties: Map<String, Any?>, box: Map<String, Any?>?): String {
        val orderedKeys = linkedSetOf<String>().apply {
            addAll(launchSnapshotProperties)
            addAll(properties.keys)
        }
        val sections = orderedKeys.mapNotNull { property ->
            val value = properties[property]?.toString() ?: return@mapNotNull null
            renderCodeBlock("Saved ${property.replaceFirstChar { it.uppercase() }}", box, property, value)
        }
        if (sections.isEmpty()) return ""
        return sections.joinToString("")
    }

    private fun renderCodeBlock(
        title: String,
        box: Map<String, Any?>?,
        property: String?,
        content: String
    ): String {
        val heading = if (box != null && !property.isNullOrBlank()) {
            boxLinkHtml(box, property = property, label = title)
        } else {
            escapeHtml(title)
        }
        return """
            <div style="margin:14px 0 0 0;">
              <div style="margin:0 0 6px 0;font-size:11px;letter-spacing:0.08em;text-transform:uppercase;color:rgba(255,255,255,0.5);">$heading</div>
              <pre style="margin:0;padding:12px 14px;border-radius:10px;background:rgba(0,0,0,0.32);border:1px solid rgba(255,255,255,0.06);color:#edf5fb;white-space:pre-wrap;word-break:break-word;font-size:12px;line-height:1.5;"><code>${escapeHtml(content)}</code></pre>
            </div>
        """.trimIndent()
    }

    private fun transcriptEventLabel(event: String): String = when (event) {
        "execute" -> "Execute"
        "execute-symbol" -> "Execute Symbol"
        "launch" -> "Launch"
        "stop" -> "Stop"
        "property-change" -> "Property Change"
        else -> event.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun transcriptPrimaryProperty(entry: Map<String, Any?>): String? {
        val property = entry["property"]?.toString()?.takeIf { it.isNotBlank() }
        if (property != null) return property

        val type = entry["type"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return when (type) {
            "code", "vertex", "fragment", "task", "mesh", "compute",
            "vertex_s", "fragment_s", "task_s", "mesh_s", "compute_s",
            "preamble", "introduction" -> type
            else -> null
        }
    }

    private fun boxLinkHtml(
        box: Map<String, Any?>,
        property: String? = null,
        line: Int? = null,
        label: String? = null
    ): String {
        val uid = box["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return escapeHtml(label ?: "box")
        val boxName = box["name"]?.toString()?.takeIf { it.isNotBlank() } ?: uid
        val linkLabel = label ?: boxName
        val query = linkedMapOf<String, String>().apply {
            property?.takeIf { it.isNotBlank() }?.let { this["property"] = it }
            line?.let {
                this["scrollToLine"] = it.toString()
                this["highlightLine"] = it.toString()
            }
        }
        val href = buildString {
            append("#box:")
            append(urlEncode(uid))
            if (query.isNotEmpty()) {
                append("?")
                append(query.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" })
            }
        }
        return buildString {
            append("<a href=\"")
            append(href)
            append("\" data-box-uid=\"")
            append(escapeHtml(uid))
            append("\"")
            property?.takeIf { it.isNotBlank() }?.let {
                append(" data-box-property=\"")
                append(escapeHtml(it))
                append("\"")
            }
            line?.let {
                append(" data-box-scroll-to-line=\"")
                append(it)
                append("\" data-box-highlight-line=\"")
                append(it)
                append("\"")
            }
            append(" style=\"color:rgba(var(--field-cyan-rgb,72,201,255),0.98);text-decoration:none;cursor:pointer;\">")
            append(escapeHtml(linkLabel))
            append("</a>")
        }
    }

    private fun mapOfStringAny(value: Any?): Map<String, Any?>? {
        val map = value as? Map<*, *> ?: return null
        val out = linkedMapOf<String, Any?>()
        map.forEach { (key, entryValue) ->
            if (key is String) out[key] = entryValue
        }
        return out
    }

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> this.toInt()
        is String -> this.toIntOrNull()
        else -> null
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun eventBase(
        state: DocumentState,
        box: Box,
        nowMs: Long,
        nowIso: String,
        event: String,
        entrypoint: String
    ): LinkedHashMap<String, Any?> {
        return linkedMapOf(
            "timestamp" to nowIso,
            "timestampMillis" to nowMs,
            "event" to event,
            "entrypoint" to entrypoint,
            "document" to state.documentPath,
            "box" to boxDescriptor(box),
            "language" to box.ke.languageName
        )
    }

    private fun boxDescriptor(box: Box): Map<String, Any?> {
        val frame = runCatching { box.frame }.getOrNull()
        return linkedMapOf(
            "id" to box.id,
            "name" to (box.getPropertyRaw("name", "")?.toString() ?: ""),
            "frame" to if (frame != null) {
                linkedMapOf(
                    "x" to frame.x,
                    "y" to frame.y,
                    "w" to frame.w,
                    "h" to frame.h
                )
            } else {
                null
            }
        )
    }

    private fun launchPropertySnapshot(box: Box, codeOverride: String?): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        launchSnapshotProperties.forEach { property ->
            val value = if (property == "code" && codeOverride != null) {
                codeOverride
            } else {
                box.getPropertyRaw(property)
            }
            if (value != null) {
                out[property] = value.toString()
            }
        }
        return out
    }

    private fun transcriptFile(documentPath: String): File =
        File(documentPath, SavingContext.TRANSCRIPT_EDN)

    private fun normalizeDocumentPath(documentPath: String): String =
        File(documentPath).absolutePath

    private fun documentPathFor(box: Box): String? {
        val path = if (box.isInsideInitialized()) {
            box.inside.filename
        } else {
            runCatching { thisSessionInfo.get()?.filename }.getOrNull()
        }
        return path?.let(::normalizeDocumentPath)
    }
}
