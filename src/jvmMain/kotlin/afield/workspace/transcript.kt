package afield

import afield.logger.lprintln
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val launchSnapshotProperties = listOf("code", "vertex", "fragment", "task", "mesh", "compute")
private val launchedPropertyChangeProperties = setOf("vertex", "fragment", "task", "mesh", "compute")

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
