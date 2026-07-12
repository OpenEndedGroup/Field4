package afield

import afield.logger.lprintln
import afield.stdlib._currentlyExecutingFragment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Instant
import java.util.UUID

object OperationTranscript {
    private const val COALESCE_FRAMES = 12L
    private const val WAIT_THRESHOLD_FRAMES = 2L
    private const val MACROS_DIR = "macros"

    data class MacroInfo(val name: String, val path: String, val operationCount: Int)
    data class MacroResult(
        val success: Boolean,
        val message: String,
        val name: String = "",
        val path: String = "",
        val operationCount: Int = 0,
        val macros: List<String> = emptyList(),
        val content: String = "",
        val recording: Boolean = false,
        val draft: String = "",
        val selection: List<String> = emptyList(),
        val documentMacros: List<String> = emptyList(),
        val projectMacros: List<String> = emptyList(),
        val workspaceMacros: List<String> = emptyList(),
        val projectName: String = "",
        val scope: String = "document",
        val direction: String = "",
        val applied: Int = 0,
        val skipped: Int = 0,
        val errors: List<String> = emptyList(),
        val entries: List<TranscriptEntry> = emptyList(),
        val offset: Int = 0,
        val limit: Int = 0,
        val total: Int = 0,
        val hasMore: Boolean = false,
        val prompt: String = "",
        val output: List<MacroOutputLine> = emptyList()
    )

    data class MacroRunResult(
        val success: Boolean,
        val message: String,
        val name: String,
        val direction: String,
        val targetBoxes: List<String>,
        val applied: Int,
        val skipped: Int,
        val errors: List<String> = emptyList(),
        // Box output/errors emitted while executing/launching boxes during this run,
        // so an agent driving a transcript can see println/_print output and errors.
        val output: List<MacroOutputLine> = emptyList()
    )

    private data class DocumentState(
        val documentPath: String,
        val lock: Any = Any(),
        val entries: MutableList<LinkedHashMap<String, Any?>> = mutableListOf(),
        var pendingEdit: PendingEdit? = null,
        var lastRecordedFrame: Long? = null,
        var loaded: Boolean = false,
        var dirty: Boolean = false
    )

    private data class PendingEdit(
        val box: String,
        val property: String,
        val from: String,
        var to: String,
        var lastFrame: Long
    )

    private data class MacroLocation(
        val scope: String,
        val projectName: String,
        val dir: File
    )

    private data class MacroReplayContext(
        val macroName: String = "",
        val operationIndex: Int = -1,
        val operationCount: Int = -1,
        val launchIndex: Int = -1,
        val launchCount: Int = -1
    )

    private data class MacroLaunchRequest(
        val target: Box,
        val exec: Execute,
        val context: MacroReplayContext
    )

    private val states = java.util.concurrent.ConcurrentHashMap<String, DocumentState>()

    fun markStart(label: String = "", selection: List<String> = emptyList()): MacroResult {
        val documentPath = currentDocumentPath() ?: return MacroResult(false, "No current document for macro start.")
        val markerName = label.ifBlank { "macro-${Instant.now()}" }
        val cleanSelection = selection.filter { it.isNotBlank() }.distinct()
        appendMarker(documentPath, "start", markerName, cleanSelection)
        checkpoint(documentPath)
        return MacroResult(
            true,
            "Started macro recording marker '$markerName'.",
            name = markerName,
            selection = cleanSelection,
            recording = true
        )
    }

    fun markEndAndSave(name: String, label: String = "", scope: String = "document", projectName: String = ""): MacroResult {
        val documentPath = currentDocumentPath() ?: return MacroResult(false, "No current document for macro end.")
        val cleanName = sanitizeMacroName(name)
        if (cleanName.isBlank()) return MacroResult(false, "Macro name cannot be empty.")

        val state = stateFor(documentPath)
        val frame = Runloop.runLoop.frame
        val endId = UUID.randomUUID().toString()
        val sliceAndSelection = synchronized(state.lock) {
            ensureLoaded(state)
            flushPendingEdit(state)
            val endEntry = markerEntry("end", label.ifBlank { cleanName }, endId, frame)
            state.entries += endEntry
            state.dirty = true

            val endIndex = state.entries.lastIndex
            val startIndex = state.entries
                .subList(0, endIndex)
                .indexOfLast { markerKind(it) == "start" }
            if (startIndex < 0) {
                return@synchronized null
            }
            val startSelection = markerSelection(state.entries[startIndex])
            val slice = state.entries
                .subList(startIndex + 1, endIndex)
                .filterNot { it.containsKey("marker") }
                .map { deepLinkedMap(it) }
            coalesceOperationRuns(slice) to startSelection
        } ?: run {
            checkpoint(documentPath)
            return MacroResult(false, "No macro start marker found.")
        }

        val (slice, sourceSelection) = sliceAndSelection
        val location = macroLocation(documentPath, scope, projectName)
            ?: return MacroResult(false, "No project macro scope is available for this document.")
        val macroPath = macroFile(location, cleanName)
        val text = writeMacroYaml(cleanName, documentPath, slice, sourceSelection)
        macroPath.parentFile?.mkdirs()
        val wrote = macroPath.safeSave(text, createBackup = true, maxBackups = 10)
        checkpoint(documentPath)
        return MacroResult(
            success = wrote,
            message = if (wrote) "Saved macro '$cleanName' with ${slice.size} operation(s)." else "Macro '$cleanName' was unchanged.",
            name = cleanName,
            path = macroPath.absolutePath,
            operationCount = slice.size,
            selection = sourceSelection.mapNotNull { it["uid"]?.toString() },
            scope = location.scope,
            projectName = location.projectName
        )
    }

    fun listMacros(
        documentPath: String = currentDocumentPath().orEmpty(),
        scope: String = "document",
        projectName: String = ""
    ): MacroResult {
        if (documentPath.isBlank()) return MacroResult(false, "No document path available.")
        val location = macroLocation(documentPath, scope, projectName)
            ?: return MacroResult(false, "No project macro scope is available for this document.")
        val names = macroNames(location.dir)
        return MacroResult(
            true,
            "Found ${names.size} macro(s).",
            macros = names,
            scope = location.scope,
            projectName = location.projectName
        )
    }

    fun readMacro(documentPath: String, name: String, scope: String = "document", projectName: String = ""): MacroResult {
        val location = macroLocation(documentPath, scope, projectName)
            ?: return MacroResult(false, "No project macro scope is available for this document.", name = sanitizeMacroName(name))
        val file = macroFile(location, sanitizeMacroName(name))
        if (!file.exists()) return MacroResult(false, "No macro named '${sanitizeMacroName(name)}'.", name = sanitizeMacroName(name))
        val operations = loadMacroOperations(file)
        return MacroResult(
            true,
            "Loaded macro '${file.nameWithoutExtension}'.",
            name = file.nameWithoutExtension,
            path = file.absolutePath,
            operationCount = operations.size,
            content = file.readText(),
            scope = location.scope,
            projectName = location.projectName
        )
    }

    fun writeMacro(
        documentPath: String,
        name: String,
        contents: String,
        scope: String = "document",
        projectName: String = ""
    ): MacroResult {
        val cleanName = sanitizeMacroName(name)
        if (cleanName.isBlank()) return MacroResult(false, "Macro name cannot be empty.")
        val location = macroLocation(documentPath, scope, projectName)
            ?: return MacroResult(false, "No project macro scope is available for this document.", name = cleanName)
        val file = macroFile(location, cleanName)
        file.parentFile?.mkdirs()
        val wrote = file.safeSave(contents.trimEnd() + "\n", createBackup = true, maxBackups = 10)
        val count = runCatching { loadMacroOperations(file).size }.getOrDefault(0)
        return MacroResult(
            success = wrote,
            message = if (wrote) "Wrote macro '$cleanName'." else "Macro '$cleanName' was unchanged.",
            name = cleanName,
            path = file.absolutePath,
            operationCount = count,
            content = file.readText(),
            scope = location.scope,
            projectName = location.projectName
        )
    }

    fun saveTranscriptSelectionAsMacro(
        documentPath: String,
        name: String,
        indices: List<Int>,
        selection: List<String> = emptyList(),
        scope: String = "document",
        projectName: String = ""
    ): MacroResult {
        if (documentPath.isBlank()) return MacroResult(false, "No document path available.")
        val cleanName = sanitizeMacroName(name)
        if (cleanName.isBlank()) return MacroResult(false, "Macro name cannot be empty.")
        val snapshot = transcriptSnapshot(documentPath)
        val cleanIndices = indices.distinct().filter { it in snapshot.indices }.sorted()
        if (cleanIndices.isEmpty()) {
            return MacroResult(false, "Select one or more transcript operations to save.")
        }
        val operations = cleanIndices
            .map { snapshot[it] }
            .filter { entry ->
                entry.keys.firstOrNull()?.lowercase() !in setOf("wait", "marker")
            }
            .map { deepLinkedMap(it) }
        if (operations.isEmpty()) {
            return MacroResult(false, "Selected transcript entries do not contain any macro operations.")
        }
        val coalesced = coalesceOperationRuns(operations)
        val location = macroLocation(documentPath, scope, projectName)
            ?: return MacroResult(false, "No project macro scope is available for this document.", name = cleanName)
        val file = macroFile(location, cleanName)
        val sourceSelection = selectionEntries(selection)
        val text = writeMacroYaml(cleanName, documentPath, coalesced, sourceSelection)
        file.parentFile?.mkdirs()
        val wrote = file.safeSave(text, createBackup = true, maxBackups = 10)
        return MacroResult(
            success = wrote,
            message = if (wrote) {
                "Saved selected transcript as macro '$cleanName' with ${coalesced.size} operation(s)."
            } else {
                "Macro '$cleanName' was unchanged."
            },
            name = cleanName,
            path = file.absolutePath,
            operationCount = coalesced.size,
            content = file.readText(),
            selection = sourceSelection.mapNotNull { it["uid"]?.toString() },
            scope = location.scope,
            projectName = location.projectName
        )
    }

    fun macroDirectory(documentPath: String, scope: String = "document", projectName: String = ""): MacroResult {
        if (documentPath.isBlank()) return MacroResult(false, "No document path available.")
        val location = macroLocation(documentPath, scope, projectName)
            ?: return MacroResult(false, "No macro scope is available for this document.")
        location.dir.mkdirs()
        return MacroResult(
            true,
            "Macro folder is ${location.dir.absolutePath}.",
            path = location.dir.absolutePath,
            scope = location.scope,
            projectName = location.projectName
        )
    }

    fun runMacro(
        documentPath: String,
        name: String,
        targetBoxes: List<String> = emptyList(),
        direction: String = "do",
        strict: Boolean = false,
        scope: String = "document",
        projectName: String = ""
    ): MacroRunResult {
        val cleanName = sanitizeMacroName(name)
        val location = macroLocation(documentPath, scope, projectName)
        if (location == null) {
            return MacroRunResult(false, "No project macro scope is available for this document.", cleanName, direction, targetBoxes, 0, 0)
        }
        val file = macroFile(location, cleanName)
        if (!file.exists()) {
            return MacroRunResult(false, "No macro named '$cleanName'.", cleanName, direction, targetBoxes, 0, 0)
        }
        val operations = loadMacroOperations(file)
        val normalizedDirection = if (direction.equals("undo", ignoreCase = true)) "undo" else "do"
        val ordered = if (normalizedDirection == "undo") operations.asReversed() else operations
        val targets = targetBoxes.filter { it.isNotBlank() }.ifEmpty { listOf("") }
        val launchCount = ordered.count { operationVerb(it) in launchVerbs }
        val errors = mutableListOf<String>()
        var applied = 0
        var skipped = 0
        val collected = java.util.Collections.synchronizedList(mutableListOf<MacroOutputLine>())
        val collect: (Message) -> Unit = { msg -> captureMacroOutput(msg, collected) }

        targets.forEach { target ->
            val primarySource = firstOperationBox(operations)
            val boxMap = if (target.isNotBlank() && primarySource != null) mapOf(primarySource to target) else emptyMap()
            var launchIndex = 0
            var index = 0
            while (index < ordered.size) {
                if (normalizedDirection == "do" && operationVerb(ordered[index]) in launchVerbs) {
                    val launchGroup = mutableListOf<Pair<Map<String, Any?>, MacroReplayContext>>()
                    while (index < ordered.size && operationVerb(ordered[index]) in launchVerbs) {
                        val op = ordered[index]
                        launchGroup += op to MacroReplayContext(
                            macroName = cleanName,
                            operationIndex = index + 1,
                            operationCount = ordered.size,
                            launchIndex = ++launchIndex,
                            launchCount = launchCount
                        )
                        index++
                    }
                    val groupDiagnostics = mutableListOf<String>()
                    val result = runCatching {
                        applyMacroLaunchGroup(launchGroup, boxMap, collect, groupDiagnostics)
                    }
                    if (result.isSuccess) {
                        val (groupApplied, groupSkipped) = result.getOrThrow()
                        applied += groupApplied
                        skipped += groupSkipped
                        // Surface loud launch failures (missing box / empty code) so the run
                        // reports success=false and the message names what went wrong.
                        errors += groupDiagnostics
                    } else {
                        skipped += launchGroup.size
                        errors += result.exceptionOrNull()?.message ?: "Unknown macro launch group failure."
                    }
                    continue
                }

                val op = ordered[index]
                val verb = operationVerb(op)
                val context = MacroReplayContext(
                    macroName = cleanName,
                    operationIndex = index + 1,
                    operationCount = ordered.size,
                    launchIndex = if (verb in launchVerbs) ++launchIndex else -1,
                    launchCount = launchCount
                )
                val result = runCatching {
                    applyMacroOperation(op, boxMap, normalizedDirection, strict, context, collect)
                }
                if (result.isSuccess) {
                    if (result.getOrDefault(false)) applied++ else skipped++
                } else {
                    skipped++
                    errors += result.exceptionOrNull()?.message ?: "Unknown macro operation failure."
                }
                index++
            }
        }
        if (applied > 0) runCatching { doResync() }
        val noChangeMessage = if (errors.isEmpty() && applied == 0) {
            " No operations changed anything; check the target selection and source uid mapping."
        } else {
            ""
        }
        return MacroRunResult(
            success = errors.isEmpty(),
            message = "Ran macro '$cleanName': applied=$applied skipped=$skipped errors=${errors.size}.$noChangeMessage",
            name = cleanName,
            direction = normalizedDirection,
            targetBoxes = targetBoxes,
            applied = applied,
            skipped = skipped,
            errors = errors,
            output = collected.toList()
        )
    }

    fun macroState(documentPath: String = currentDocumentPath().orEmpty(), scope: String = "document", projectName: String = ""): MacroResult {
        if (documentPath.isBlank()) return MacroResult(false, "No document path available.")
        val documentMacroNames = macroNames(documentMacrosDir(documentPath))
        val projectEntry = projectForDocument(documentPath, projectName)
        val projectMacroNames = projectEntry?.let { macroNames(projectMacrosDir(documentPath, it.name)) }.orEmpty()
        val workspaceMacroNames = macroNames(workspaceMacrosDir(documentPath))
        val requestedLocation = macroLocation(documentPath, scope, projectName)
        val state = stateFor(documentPath)
        val draftPair = synchronized(state.lock) {
            ensureLoaded(state)
            flushPendingEdit(state)
            val lastMarkerIndex = state.entries.indexOfLast { it.containsKey("marker") }
            if (lastMarkerIndex < 0 || markerKind(state.entries[lastMarkerIndex]) != "start") {
                false to (emptyList<LinkedHashMap<String, Any?>>() to emptyList<LinkedHashMap<String, Any?>>())
            } else {
                val selection = markerSelection(state.entries[lastMarkerIndex])
                val ops = state.entries
                    .subList(lastMarkerIndex + 1, state.entries.size)
                    .filterNot { it.containsKey("marker") }
                    .map { deepLinkedMap(it) }
                true to (coalesceOperationRuns(ops) to selection)
            }
        }
        val recording = draftPair.first
        val operations = draftPair.second.first
        val sourceSelection = draftPair.second.second
        return MacroResult(
            success = true,
            message = if (recording) "Recording ${operations.size} operation(s)." else "Macro recorder is idle.",
            operationCount = operations.size,
            macros = requestedLocation?.let { macroNames(it.dir) } ?: documentMacroNames,
            content = "",
            recording = recording,
            draft = "",
            selection = sourceSelection.mapNotNull { it["uid"]?.toString() },
            documentMacros = documentMacroNames,
            projectMacros = projectMacroNames,
            workspaceMacros = workspaceMacroNames,
            projectName = projectEntry?.name.orEmpty(),
            scope = requestedLocation?.scope ?: "document"
        )
    }

    fun transcriptPage(documentPath: String, offset: Int = 0, limit: Int = 80, includeWaits: Boolean = true): MacroResult {
        if (documentPath.isBlank()) return MacroResult(false, "No document path available.")
        val cleanLimit = limit.coerceIn(1, 250)
        val snapshot = transcriptSnapshot(documentPath)
            .mapIndexed { index, entry -> index to entry }
            .filter { (_, entry) -> includeWaits || entry.keys.firstOrNull()?.lowercase() != "wait" }
        val cleanOffset = if (offset < 0) {
            (snapshot.size - cleanLimit).coerceAtLeast(0)
        } else {
            offset.coerceIn(0, snapshot.size)
        }
        val page = snapshot
            .drop(cleanOffset)
            .take(cleanLimit)
            .map { (entryIndex, entry) -> transcriptEntry(entryIndex, entry) }
        val pageLog =
            "page document=${File(documentPath).name} offset=$cleanOffset limit=$cleanLimit total=${snapshot.size} entries=${page.size} includeWaits=$includeWaits"
        println("[operation-transcript] $pageLog")
        lprintln("info,operation-transcript", pageLog)
        return MacroResult(
            success = true,
            message = "Loaded transcript entries ${if (page.isEmpty()) 0 else cleanOffset + 1}-${cleanOffset + page.size} of ${snapshot.size}.",
            entries = page,
            offset = cleanOffset,
            limit = cleanLimit,
            total = snapshot.size,
            hasMore = cleanOffset + page.size < snapshot.size
        )
    }

    fun undoTranscriptSelection(
        documentPath: String,
        indices: List<Int>,
        strict: Boolean = true
    ): MacroResult {
        if (documentPath.isBlank()) return MacroResult(false, "No document path available.")
        val snapshot = transcriptSnapshot(documentPath)
        val cleanIndices = indices.distinct().filter { it in snapshot.indices }.sortedDescending()
        if (cleanIndices.isEmpty()) {
            return MacroResult(false, "Select one or more transcript operations to undo.")
        }
        val errors = mutableListOf<String>()
        var applied = 0
        var skipped = 0
        cleanIndices.forEach { index ->
            val entry = snapshot[index]
            val result = runCatching { applyMacroOperation(entry, emptyMap(), "undo", strict) }
            if (result.isSuccess) {
                if (result.getOrDefault(false)) applied++ else skipped++
            } else {
                skipped++
                errors += "#$index ${entry.keys.firstOrNull().orEmpty()}: ${result.exceptionOrNull()?.message ?: "Unknown undo failure."}"
            }
        }
        if (applied > 0) runCatching { doResync() }
        val noChangeMessage = if (errors.isEmpty() && applied == 0) {
            " No operations changed anything; later edits may already have removed the recorded change."
        } else {
            ""
        }
        return MacroResult(
            success = errors.isEmpty(),
            message = "Undid ${cleanIndices.size} transcript entr${if (cleanIndices.size == 1) "y" else "ies"}: applied=$applied skipped=$skipped errors=${errors.size}.$noChangeMessage",
            operationCount = cleanIndices.size,
            direction = "undo",
            applied = applied,
            skipped = skipped,
            errors = errors
        )
    }

    fun aiUndoPrompt(documentPath: String, indices: List<Int>, selection: List<String> = emptyList()): MacroResult {
        if (documentPath.isBlank()) return MacroResult(false, "No document path available.")
        val snapshot = transcriptSnapshot(documentPath)
        val cleanIndices = indices.distinct().filter { it in snapshot.indices }.sorted()
        if (cleanIndices.isEmpty()) {
            return MacroResult(false, "Select one or more transcript operations for AI4 to reason about.")
        }
        val selectedEntries = cleanIndices.map { it to snapshot[it] }
        val operationYaml = writeYamlList(selectedEntries.map { it.second })
        val summaries = selectedEntries.joinToString("\n") { (index, entry) ->
            val summary = transcriptEntry(index, entry).summary
            "- #$index $summary"
        }
        val contextSelection = selection.filter { it.isNotBlank() }.distinct()
        val promptText = buildString {
            appendLine("You are helping undo a selected slice of Field's front-end operation transcript.")
            appendLine()
            appendLine("Goal:")
            appendLine("- Undo the intent of the selected transcript entries while preserving unrelated later work in the document.")
            appendLine("- Prefer the established AI4 proposed-changes workflow for text/property edits, so the user can inspect and accept/reject changes.")
            appendLine("- If you need to run a macro or transcript operation and it errors, undo your attempted changes, report the error, and ask what the user wants to do next.")
            appendLine("- Be generous: boxes may have changed since these entries were recorded, so use the from/to pair as context rather than blindly overwriting current values.")
            appendLine()
            appendLine("Current selected boxes in the UI:")
            if (contextSelection.isEmpty()) appendLine("- none") else contextSelection.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Selected transcript entries:")
            appendLine(summaries)
            appendLine()
            appendLine("YAML context:")
            appendLine("```yaml")
            append(operationYaml.trimEnd())
            appendLine()
            appendLine("```")
            appendLine()
            appendLine("Please inspect the affected boxes/properties as needed, propose the undo as AI4 provisional changes when possible, and explain any uncertainty before applying irreversible operations.")
        }
        return MacroResult(
            success = true,
            message = "Prepared AI4 undo prompt for ${cleanIndices.size} transcript entr${if (cleanIndices.size == 1) "y" else "ies"}.",
            operationCount = cleanIndices.size,
            prompt = promptText
        )
    }

    fun recordEdit(box: Box, property: String, from: String?, to: String?) {
        if (from == to) return
        if (property.endsWith("_editorState")) return
        val documentPath = documentPathFor(box) ?: return
        val frame = Runloop.runLoop.frame
        val state = stateFor(documentPath)
        synchronized(state.lock) {
            ensureLoaded(state)
            val pending = state.pendingEdit
            if (
                pending != null &&
                    pending.box == box.id &&
                    pending.property == property &&
                    frame - pending.lastFrame <= COALESCE_FRAMES
            ) {
                pending.to = to.orEmpty()
                pending.lastFrame = frame
                state.dirty = true
                return
            }
            flushPendingEdit(state)
            state.pendingEdit = PendingEdit(
                box = box.id,
                property = property,
                from = from.orEmpty(),
                to = to.orEmpty(),
                lastFrame = frame
            )
            state.dirty = true
        }
    }

    fun recordMove(box: Box, from: Rect, to: Rect) {
        if (from == to) return
        val documentPath = documentPathFor(box) ?: return
        val frame = Runloop.runLoop.frame
        val state = stateFor(documentPath)
        synchronized(state.lock) {
            ensureLoaded(state)
            flushPendingEdit(state)
            if (mergeMoveIntoPrevious(state, box.id, to)) {
                state.lastRecordedFrame = frame
                state.dirty = true
                return
            }
            appendWaitIfNeeded(state, frame)
            if (mergeMoveIntoPrevious(state, box.id, to)) {
                state.lastRecordedFrame = frame
                state.dirty = true
                return
            }
            state.entries += moveEntry(box.id, from, to)
            state.lastRecordedFrame = frame
            state.dirty = true
        }
    }

    fun recordExecuteFragment(box: Box, execute: Execute) {
        recordExecutionEvent(
            box,
            "execute",
            linkedMapOf(
                "box" to box.id,
                "property" to execute.property,
                "line" to execute.line,
                "code" to execute.code,
                "singleLine" to execute.singleLine,
                "type" to execute.type
            )
        )
    }

    fun recordLaunch(box: Box, execute: Execute, strand: String = execute.strand) {
        recordExecutionEvent(
            box,
            "launch",
            linkedMapOf(
                "box" to box.id,
                "property" to execute.property,
                "line" to execute.line,
                "code" to execute.code,
                "singleLine" to execute.singleLine,
                "type" to execute.type,
                "strand" to strand.ifBlank { "default" }
            )
        )
    }

    fun recordStop(box: Box) {
        recordExecutionEvent(
            box,
            "stop",
            linkedMapOf(
                "box" to box.id
            )
        )
    }

    fun checkpoint(documentPath: String): Boolean {
        val state = states[normalizeDocumentPath(documentPath)] ?: return false
        val snapshot = synchronized(state.lock) {
            ensureLoaded(state)
            flushPendingEdit(state)
            if (!state.dirty) return false
            state.entries.map { LinkedHashMap(it) }
        }
        return try {
            val file = operationTranscriptFile(documentPath)
            file.parentFile?.mkdirs()
            val text = writeYamlList(snapshot)
            val wrote = file.safeSave(text, createBackup = true, maxBackups = 10)
            synchronized(state.lock) {
                state.dirty = false
            }
            wrote
        } catch (t: Throwable) {
            lprintln("warning,operation-transcript", "Failed to checkpoint operation transcript for $documentPath: ${t.message}")
            false
        }
    }

    private fun recordExecutionEvent(box: Box, verb: String, payload: LinkedHashMap<String, Any?>) {
        val documentPath = documentPathFor(box) ?: return
        val frame = Runloop.runLoop.frame
        val state = stateFor(documentPath)
        synchronized(state.lock) {
            ensureLoaded(state)
            flushPendingEdit(state)
            appendWaitIfNeeded(state, frame)
            state.entries += linkedMapOf(verb to payload)
            state.lastRecordedFrame = frame
            state.dirty = true
        }
    }

    private fun flushPendingEdit(state: DocumentState) {
        val pending = state.pendingEdit ?: return
        state.pendingEdit = null
        if (pending.from == pending.to) return
        if (mergePendingEditIntoPrevious(state, pending)) {
            state.lastRecordedFrame = pending.lastFrame
            state.dirty = true
            return
        }
        appendWaitIfNeeded(state, pending.lastFrame)
        if (mergePendingEditIntoPrevious(state, pending)) {
            state.lastRecordedFrame = pending.lastFrame
            state.dirty = true
            return
        }
        val index = state.entries.size
        state.entries += editEntry(pending.box, pending.property, pending.from, pending.to)
        state.lastRecordedFrame = pending.lastFrame
        val appendLog =
            "append #$index edit ${pending.box}/${pending.property} from=${pending.from.length} chars to=${pending.to.length} chars frame=${pending.lastFrame}"
        println("[operation-transcript] $appendLog")
        lprintln("info,operation-transcript", appendLog)
    }

    private fun editEntry(box: String, property: String, from: String, to: String): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "edit" to linkedMapOf(
                "box" to box,
                "property" to property,
                "from" to from,
                "to" to to
            )
        )

    private fun moveEntry(box: String, from: Rect, to: Rect): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "move" to linkedMapOf(
                "box" to box,
                "from" to rectMap(from),
                "to" to rectMap(to)
            )
        )

    private fun mergePendingEditIntoPrevious(state: DocumentState, pending: PendingEdit): Boolean {
        val editIndex = previousEditableIndexFor(state.entries, pending.box, pending.property) ?: return false
        @Suppress("UNCHECKED_CAST")
        val payload = state.entries[editIndex]["edit"] as? MutableMap<String, Any?> ?: return false
        payload["to"] = pending.to
        removeTrailingWaitsAfter(state.entries, editIndex)
        val mergeLog =
            "merge #$editIndex edit ${pending.box}/${pending.property} from=${payload["from"]?.toString()?.length ?: 0} chars to=${pending.to.length} chars frame=${pending.lastFrame}"
        println("[operation-transcript] $mergeLog")
        lprintln("info,operation-transcript", mergeLog)
        return true
    }

    private fun previousEditableIndexFor(
        entries: List<Map<String, Any?>>,
        box: String,
        property: String
    ): Int? {
        var index = entries.lastIndex
        while (index >= 0) {
            val entry = entries[index]
            if (entry.containsKey("wait")) {
                index--
                continue
            }
            val payload = entry["edit"] as? Map<*, *> ?: return null
            val entryBox = payload["box"]?.toString().orEmpty()
            val entryProperty = payload["property"]?.toString().orEmpty()
            return if (entryBox == box && entryProperty == property) index else null
        }
        return null
    }

    private fun mergeMoveIntoPrevious(state: DocumentState, box: String, to: Rect): Boolean {
        val moveIndex = previousMoveIndexFor(state.entries, box) ?: return false
        @Suppress("UNCHECKED_CAST")
        val payload = state.entries[moveIndex]["move"] as? MutableMap<String, Any?> ?: return false
        payload["to"] = rectMap(to)
        removeTrailingWaitsAfter(state.entries, moveIndex)
        val mergeLog = "merge #$moveIndex move $box to=${rectSummary(to)}"
        println("[operation-transcript] $mergeLog")
        lprintln("info,operation-transcript", mergeLog)
        return true
    }

    private fun previousMoveIndexFor(
        entries: List<Map<String, Any?>>,
        box: String
    ): Int? {
        var index = entries.lastIndex
        while (index >= 0) {
            val entry = entries[index]
            if (entry.containsKey("wait")) {
                index--
                continue
            }
            val payload = entry["move"] as? Map<*, *> ?: return null
            val entryBox = payload["box"]?.toString().orEmpty()
            return if (entryBox == box) index else null
        }
        return null
    }

    private fun removeTrailingWaitsAfter(entries: MutableList<LinkedHashMap<String, Any?>>, editIndex: Int) {
        var index = entries.lastIndex
        while (index > editIndex) {
            if (entries[index].containsKey("wait")) entries.removeAt(index) else return
            index--
        }
    }

    private fun coalesceOperationRuns(entries: List<LinkedHashMap<String, Any?>>): List<LinkedHashMap<String, Any?>> {
        val out = mutableListOf<LinkedHashMap<String, Any?>>()
        entries.forEach { entry ->
            val editPayload = entry["edit"] as? Map<*, *>
            if (editPayload != null) {
                val box = editPayload["box"]?.toString().orEmpty()
                val property = editPayload["property"]?.toString().orEmpty()
                val previous = previousEditableIndexFor(out, box, property)
                if (previous == null) {
                    out += deepLinkedMap(entry)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val previousPayload = out[previous]["edit"] as? MutableMap<String, Any?> ?: run {
                        out += deepLinkedMap(entry)
                        return@forEach
                    }
                    previousPayload["to"] = editPayload["to"]?.toString().orEmpty()
                    removeTrailingWaitsAfter(out, previous)
                }
                return@forEach
            }

            val movePayload = entry["move"] as? Map<*, *>
            if (movePayload != null) {
                val box = movePayload["box"]?.toString().orEmpty()
                val previous = previousMoveIndexFor(out, box)
                if (previous == null) {
                    out += deepLinkedMap(entry)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val previousPayload = out[previous]["move"] as? MutableMap<String, Any?> ?: run {
                        out += deepLinkedMap(entry)
                        return@forEach
                    }
                    previousPayload["to"] = deepLinkedMap(movePayload["to"])
                    removeTrailingWaitsAfter(out, previous)
                }
                return@forEach
            }

            out += deepLinkedMap(entry)
        }
        return out
    }

    private fun appendWaitIfNeeded(state: DocumentState, frame: Long) {
        val previous = state.lastRecordedFrame ?: return
        val delta = frame - previous
        if (delta >= WAIT_THRESHOLD_FRAMES) {
            state.entries += linkedMapOf("wait" to linkedMapOf("frames" to delta))
        }
    }

    private fun stateFor(documentPath: String): DocumentState =
        states.computeIfAbsent(normalizeDocumentPath(documentPath)) { DocumentState(documentPath = it) }

    private fun transcriptSnapshot(documentPath: String): List<LinkedHashMap<String, Any?>> {
        val state = stateFor(documentPath)
        return synchronized(state.lock) {
            ensureLoaded(state)
            flushPendingEdit(state)
            state.entries.map { deepLinkedMap(it) }
        }
    }

    private fun transcriptEntry(index: Int, entry: Map<String, Any?>): TranscriptEntry {
        val verb = entry.keys.firstOrNull().orEmpty()
        val payload = entry.values.firstOrNull() as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val boxId = if (verb.lowercase() in setOf("marker", "wait")) {
            ""
        } else {
            (payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"])?.toString().orEmpty()
        }
        val property = (payload["property"] ?: payload["prop"])?.toString().orEmpty()
        val from = payload["from"]?.toString().orEmpty()
        val to = payload["to"]?.toString().orEmpty()
        val frame = when {
            verb == "wait" -> payload["frames"]?.toString().orEmpty()
            verb == "marker" -> payload["runloopFrame"]?.toString().orEmpty()
            else -> payload["frame"]?.toString().orEmpty()
        }
        val at = payload["at"]?.toString().orEmpty()
        fun compactValue(value: String): String {
            val compact = value
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .ifBlank { "empty" }
            return if (compact.length > 48) compact.take(45).trimEnd() + "..." else compact
        }
        val summary = when (verb.lowercase()) {
            "edit", "set", "set-string" -> {
                buildString {
                    append("edit")
                    if (property.isNotBlank()) append(" ").append(property)
                    if (boxId.isNotBlank()) append(" of ").append(boxId)
                    append(" to ").append(compactValue(to))
                }
            }
            "move", "frame", "set-rect" -> if (boxId.isBlank()) "move" else "move $boxId"
            "execute", "execute-fragment" -> {
                buildString {
                    append("execute")
                    val type = (payload["type"] ?: "fragment").orEmptyString()
                    if (type.isNotBlank() && type != property) append(" ").append(type)
                    if (property.isNotBlank()) append(" ").append(property)
                    if (boxId.isNotBlank()) append(" of ").append(boxId)
                }
            }
            "launch", "launch-all", "launch-fragment" -> {
                buildString {
                    append("launch")
                    val type = (payload["type"] ?: "").orEmptyString()
                    if (type.isNotBlank() && type != "launch" && type != property) append(" ").append(type)
                    if (property.isNotBlank()) append(" ").append(property)
                    if (boxId.isNotBlank()) append(" of ").append(boxId)
                    val strand = (payload["strand"] ?: "").orEmptyString()
                    if (strand.isNotBlank() && strand != "default") append(" strand ").append(strand)
                }
            }
            "stop", "stop-all", "stop-box" -> if (boxId.isBlank()) "stop" else "stop $boxId"
            "connect" -> "connect ${(payload["parent"] ?: payload["from"] ?: payload["source"] ?: payload["box"]).orEmptyString()} -> ${(payload["child"] ?: payload["to"] ?: payload["target"]).orEmptyString()}"
            "disconnect" -> "disconnect ${(payload["parent"] ?: payload["from"] ?: payload["source"] ?: payload["box"]).orEmptyString()} -> ${(payload["child"] ?: payload["to"] ?: payload["target"]).orEmptyString()}"
            "create", "new-box", "box" -> "create ${(payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"]).orEmptyString()} ${(payload["name"] ?: "").orEmptyString()}".trim()
            "delete", "tombstone" -> "delete ${(payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"]).orEmptyString()}".trim()
            "wait" -> {
                val millis = payload["millis"] ?: payload["ms"]
                if (millis != null) {
                    "wait ${millis}ms"
                } else {
                    val frames = payload["frames"]?.toString() ?: "?"
                    "wait $frames ${if (frames.toLongOrNull() == 1L) "frame" else "frames"}"
                }
            }
            "marker" -> "marker ${(payload["kind"] ?: "").orEmptyString()} ${(payload["label"] ?: "").orEmptyString()}".trim()
            else -> verb.ifBlank { "entry" }
        }
        return TranscriptEntry(
            index = index,
            verb = verb,
            summary = summary,
            yaml = writeYamlList(listOf(entry)),
            box = boxId,
            property = property,
            from = from,
            to = to,
            frame = frame,
            at = at,
            selectable = verb.lowercase() !in setOf("wait", "marker")
        )
    }

    private fun ensureLoaded(state: DocumentState) {
        if (state.loaded) return
        state.loaded = true
        val file = operationTranscriptFile(state.documentPath)
        if (!file.exists()) return
        runCatching {
            val loaded = Yaml().load<Any?>(file.readText())
            val entries = loaded as? List<*> ?: return@runCatching
            state.entries += entries.mapNotNull { mapOfStringAny(it) }
            state.lastRecordedFrame = null
        }.onFailure {
            lprintln("warning,operation-transcript", "Failed to read operation transcript ${file.absolutePath}: ${it.message}")
        }
    }

    private fun operationTranscriptFile(documentPath: String): File =
        File(normalizeDocumentPath(documentPath), SavingContext.OPERATIONS_YAML)

    private fun appendMarker(documentPath: String, kind: String, label: String, selection: List<String> = emptyList()) {
        val state = stateFor(documentPath)
        synchronized(state.lock) {
            ensureLoaded(state)
            flushPendingEdit(state)
            state.entries += markerEntry(kind, label, UUID.randomUUID().toString(), Runloop.runLoop.frame, selectionEntries(selection))
            state.dirty = true
        }
    }

    private fun markerEntry(
        kind: String,
        label: String,
        id: String,
        frame: Long,
        selection: List<LinkedHashMap<String, Any?>> = emptyList()
    ): LinkedHashMap<String, Any?> {
        val marker = linkedMapOf<String, Any?>(
            "kind" to kind,
            "label" to label,
            "id" to id,
            "runloopFrame" to frame,
            "at" to Instant.now().toString()
        )
        if (selection.isNotEmpty()) marker["selection"] = selection
        return linkedMapOf(
            "marker" to marker
        )
    }

    private fun markerKind(entry: Map<String, Any?>): String? =
        (entry["marker"] as? Map<*, *>)?.get("kind")?.toString()

    private fun markerSelection(entry: Map<String, Any?>): List<LinkedHashMap<String, Any?>> {
        val marker = entry["marker"] as? Map<*, *> ?: return emptyList()
        val raw = marker["selection"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { mapOfStringAny(it) }
    }

    private fun selectionEntries(selection: List<String>): List<LinkedHashMap<String, Any?>> =
        selection.filter { it.isNotBlank() }.distinct().map { uid ->
            val name = runCatching { box(uid, createIfMissing = false).name }.getOrDefault("")
            linkedMapOf(
                "uid" to uid,
                "name" to name
            )
        }

    private fun normalizeDocumentPath(documentPath: String): String =
        File(documentPath).absolutePath

    private fun currentDocumentPath(): String? =
        runCatching { thisSessionInfo.get()?.filename }.getOrNull()?.let(::normalizeDocumentPath)

    private fun documentMacrosDir(documentPath: String): File =
        File(normalizeDocumentPath(documentPath), MACROS_DIR)

    private fun workspaceMacrosDir(documentPath: String): File {
        val documentDir = File(normalizeDocumentPath(documentPath)).canonicalFile
        val workspace = documentDir.parentFile ?: documentDir
        return File(workspace, MACROS_DIR)
    }

    private fun projectMacrosDir(documentPath: String, projectName: String): File {
        val documentDir = File(normalizeDocumentPath(documentPath)).canonicalFile
        val workspace = documentDir.parentFile ?: documentDir
        return File(File(projectsDir(workspace.absolutePath), projectName), MACROS_DIR)
    }

    private fun macroLocation(documentPath: String, scope: String, projectName: String = ""): MacroLocation? {
        return when {
            scope.equals("project", ignoreCase = true) -> {
                val project = projectForDocument(documentPath, projectName) ?: return null
                MacroLocation("project", project.name, projectMacrosDir(documentPath, project.name))
            }
            scope.equals("workspace", ignoreCase = true) -> MacroLocation("workspace", "", workspaceMacrosDir(documentPath))
            else -> MacroLocation("document", "", documentMacrosDir(documentPath))
        }
    }

    private fun projectForDocument(documentPath: String, requestedProject: String = ""): ProjectEntry? {
        val documentDir = File(normalizeDocumentPath(documentPath)).canonicalFile
        val workspace = documentDir.parentFile ?: return null
        val documentName = documentDir.name
        val projects = runCatching { listWorkspaceProjects(workspace.absolutePath) }.getOrDefault(emptyList())
        val cleanRequested = requestedProject.trim()
        return if (cleanRequested.isNotEmpty()) {
            projects.firstOrNull { it.name == cleanRequested }
        } else {
            projects.firstOrNull { project -> project.documents.any { it == documentName } }
        }
    }

    private fun macroNames(dir: File): List<String> =
        dir.takeIf { it.exists() }
            ?.listFiles { file -> file.isFile && file.extension.lowercase() in setOf("yaml", "yml") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()

    private fun macroFile(location: MacroLocation, name: String): File =
        File(location.dir, sanitizeMacroName(name) + ".yaml")

    private fun sanitizeMacroName(name: String): String =
        name.trim()
            .replace(Regex("[^A-Za-z0-9_.-]+"), "-")
            .trim('-', '.', '_')

    private fun writeMacroYaml(
        name: String,
        documentPath: String,
        operations: List<Map<String, Any?>>,
        sourceSelection: List<Map<String, Any?>> = emptyList()
    ): String =
        buildString {
            append("name: ")
            append(formatScalar(name))
            append("\ncreated: ")
            append(formatScalar(Instant.now().toString()))
            append("\ndocument: ")
            append(formatScalar(File(documentPath).name))
            if (sourceSelection.isNotEmpty()) {
                append("\nsourceSelection:\n")
                sourceSelection.forEach { selection ->
                    append("  -\n")
                    appendYamlValue(selection, indent = 4)
                }
            }
            append("\noperations:\n")
            operations.forEach { entry ->
                entry.forEach { (key, value) ->
                    append("  - ")
                    append(key)
                    append(":\n")
                    appendYamlValue(value, indent = 6)
                }
            }
        }

    private fun documentPathFor(box: Box): String? {
        val path = if (box.isInsideInitialized()) {
            box.inside.filename
        } else {
            runCatching { thisSessionInfo.get()?.filename }.getOrNull()
        }
        return path?.let(::normalizeDocumentPath)
    }

    private fun rectMap(rect: Rect): Map<String, Any?> = linkedMapOf(
        "x" to rect.x,
        "y" to rect.y,
        "w" to rect.w,
        "h" to rect.h
    )

    private fun rectSummary(rect: Rect): String =
        "${rect.x},${rect.y}+${rect.w}x${rect.h}"

    private fun mapOfStringAny(value: Any?): LinkedHashMap<String, Any?>? {
        val map = value as? Map<*, *> ?: return null
        val out = linkedMapOf<String, Any?>()
        map.forEach { (key, entryValue) ->
            if (key is String) {
                out[key] = when (entryValue) {
                    is Map<*, *> -> mapOfStringAny(entryValue)
                    is List<*> -> entryValue.map { item -> if (item is Map<*, *>) mapOfStringAny(item) else item }
                    else -> entryValue
                }
            }
        }
        return out
    }

    private fun deepLinkedMap(value: Any?): LinkedHashMap<String, Any?> {
        val source = value as? Map<*, *> ?: return linkedMapOf()
        val out = linkedMapOf<String, Any?>()
        source.forEach { (key, entryValue) ->
            if (key is String) {
                out[key] = when (entryValue) {
                    is Map<*, *> -> deepLinkedMap(entryValue)
                    is List<*> -> entryValue.map { item -> if (item is Map<*, *>) deepLinkedMap(item) else item }
                    else -> entryValue
                }
            }
        }
        return out
    }

    private fun loadMacroOperations(file: File): List<LinkedHashMap<String, Any?>> {
        val loaded = Yaml().load<Any?>(file.readText())
        val rawOps = when (loaded) {
            is List<*> -> loaded
            is Map<*, *> -> (loaded["operations"] as? List<*>) ?: (loaded["ops"] as? List<*>) ?: emptyList<Any?>()
            else -> emptyList()
        }
        return rawOps.mapNotNull { mapOfStringAny(it) }
    }

    private fun firstOperationBox(operations: List<Map<String, Any?>>): String? =
        operations.asSequence()
            .mapNotNull { op ->
                val payload = op.values.firstOrNull() as? Map<*, *> ?: return@mapNotNull null
                (payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"])?.toString()
            }
            .firstOrNull()

    private val launchVerbs = setOf("launch", "launch-all", "launch-fragment")

    private fun operationVerb(operation: Map<String, Any?>): String =
        operation.keys.firstOrNull()?.lowercase().orEmpty()

    private fun applyMacroOperation(
        operation: Map<String, Any?>,
        boxMap: Map<String, String>,
        direction: String,
        strict: Boolean,
        context: MacroReplayContext = MacroReplayContext(),
        collect: (Message) -> Unit = {}
    ): Boolean {
        val verb = operation.keys.firstOrNull()?.lowercase() ?: return false
        val payload = operation.values.firstOrNull() as? Map<*, *> ?: return false
        fun mappedBox(): Box? {
            val raw = (payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"])?.toString() ?: return null
            val mapped = boxMap[raw] ?: raw
            return runCatching { box(mapped, createIfMissing = false) }.getOrNull()
        }

        return when (verb) {
            "edit", "set", "set-string" -> {
                val target = mappedBox() ?: return false
                val property = (payload["property"] ?: payload["prop"] ?: "code").toString()
                val recordedFrom = payload["from"]?.toString().orEmpty()
                val recordedTo = payload["to"]?.toString()
                    ?: payload["value"]?.toString()
                    ?: payload["text"]?.toString()
                    ?: return false
                val current = if (property == "name") target.name else target.getPropertyRaw(property)?.toString().orEmpty()
                val next = transformText(
                    current = current,
                    expected = if (direction == "undo") recordedTo else recordedFrom,
                    replacement = if (direction == "undo") recordedFrom else recordedTo,
                    strict = strict
                ) ?: return false
                if (property == "name") target.name = next else target.setPropertyRaw(property, next, doResync = true)
                true
            }
            "move", "frame", "set-rect" -> {
                val target = mappedBox() ?: return false
                val frameValue = payload[if (direction == "undo") "from" else "to"] ?: payload["frame"] ?: payload["value"] ?: return false
                val frameMap = frameValue as? Map<*, *> ?: return false
                target.frame = rectFromMap(frameMap)
                true
            }
            "create", "new-box", "box" -> {
                val raw = (payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"])?.toString() ?: return false
                val mapped = boxMap[raw] ?: raw
                if (mapped == "root" || mapped == METAROOT_ID) return false
                val info = thisSessionInfo.get()
                val target = box(mapped, createIfMissing = true, initIfCreated = true, root = info)
                if (direction == "undo") {
                    tombstoneBox(target)
                    return true
                }
                target.setPropertyRaw("_tombstone", false, doResync = true)
                (payload["name"] ?: payload["title"])?.toString()?.let { target.name = it }
                val frameMap = (payload["frame"] ?: payload["rect"]) as? Map<*, *>
                if (frameMap != null) target.frame = rectFromMap(frameMap)
                (payload["properties"] as? Map<*, *>)?.forEach { (key, value) ->
                    val property = key?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (value != null) target.setPropertyRaw(property, value, doResync = true)
                }
                info.root.connect(target)
                needsResync.add(info.root to "children")
                needsResync.add(target to "parents")
                needsResync.add(info.root to "actions")
                needsResync.add(target to "actions")
                target.resyncAll()
                info.topologyGeneration++
                true
            }
            "delete", "tombstone" -> {
                val target = mappedBox() ?: return false
                if (target.id == "root" || target.id == METAROOT_ID) return false
                if (direction == "undo") {
                    val info = thisSessionInfo.get()
                    target.setPropertyRaw("_tombstone", false, doResync = true)
                    info.root.connect(target)
                    target.resyncAll()
                    info.topologyGeneration++
                } else {
                    tombstoneBox(target)
                }
                true
            }
            "connect", "disconnect" -> {
                val parentRaw = (payload["parent"] ?: payload["from"] ?: payload["source"] ?: payload["box"])?.toString() ?: return false
                val childRaw = (payload["child"] ?: payload["to"] ?: payload["target"])?.toString() ?: return false
                val parent = runCatching { box(boxMap[parentRaw] ?: parentRaw, createIfMissing = false) }.getOrNull() ?: return false
                val child = runCatching { box(boxMap[childRaw] ?: childRaw, createIfMissing = false) }.getOrNull() ?: return false
                val action = if (direction == "undo") {
                    if (verb == "connect") "disconnect" else "connect"
                } else verb
                if (action == "connect") parent.connect(child) else parent.disconnect(child)
                true
            }
            "execute", "execute-fragment" -> {
                if (direction == "undo") return false
                val target = mappedBox() ?: return false
                val exec = executeFromPayload(target.id, payload, fallbackType = "fragment")
                // Await completion (like the launch path) so the fragment's output is
                // captured into the run result — fire-and-forget stalls headlessly.
                runBlocking { target.ke.executeFragmentAndWait(target, exec) { send(it); collect(it) } }
                true
            }
            "launch", "launch-all", "launch-fragment" -> {
                if (direction == "undo") {
                    val target = mappedBox() ?: return false
                    target.ke.stop(target)
                    return true
                }
                // Route through the group path so an unresolved box / empty code becomes a
                // loud, logged diagnostic instead of a silent skip.
                val (applied, _) = applyMacroLaunchGroup(listOf(operation to context), boxMap, collect)
                applied > 0
            }
            "stop", "stop-all", "stop-box" -> {
                if (direction == "undo") return false
                val target = mappedBox() ?: return false
                target.ke.stop(target)
                true
            }
            "wait" -> {
                if (direction == "undo") return false
                val explicitMillis = payload["millis"]?.asLongOrDefault(-1L)
                    ?: payload["ms"]?.asLongOrDefault(-1L)
                    ?: payload["seconds"]?.asDoubleOrNull()?.let { (it * 1000.0).toLong() }
                val frames = payload["frames"]?.asLongOrDefault(-1L)
                if (explicitMillis != null && explicitMillis >= 0L) {
                    Thread.sleep(explicitMillis.coerceIn(0L, 30_000L))
                } else if (frames != null && frames >= 0L) {
                    waitForRunloopFrames(frames.coerceIn(0L, 30_000L))
                }
                if (payload["pending"].asBooleanOrDefault(false)) awaitPending(payload)
                true
            }
            // Barrier op: wait for all previously-fired executeFragments to finish and
            // launches to reach their first frame, so their output is captured/observable.
            "await", "sync", "settle", "wait-pending" -> {
                if (direction == "undo") return false
                awaitPending(payload)
                true
            }
            // Persist the current (transient) document's boxes as a metadata.edn document
            // at the given path, so an agent can save the assembled scene if desired.
            "save", "save-document", "save-as" -> {
                if (direction == "undo") return false
                val path = (payload["path"] ?: payload["to"] ?: payload["fn"] ?: payload["document"])
                    ?.toString()?.takeIf { it.isNotBlank() } ?: return false
                val info = thisSessionInfo.get()
                val boxes = info.boxes.values
                    .filter { it.id != "root" && it.id != METAROOT_ID && !it.getPropertyRaw("_tombstone").isTrue() }
                    .toSet()
                val latch = java.util.concurrent.CountDownLatch(1)
                SavingContext().save(path, boxes, onComplete = { latch.countDown() })
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                true
            }
            "marker" -> false
            else -> false
        }
    }

    private fun applyMacroLaunchGroup(
        operations: List<Pair<Map<String, Any?>, MacroReplayContext>>,
        boxMap: Map<String, String>,
        collect: (Message) -> Unit = {},
        diagnostics: MutableList<String>? = null
    ): Pair<Int, Int> {
        // Resolve every op explicitly. A launch that can't find its box or has no code
        // to run is a loud, specific failure (logged + notified + surfaced in `errors`),
        // never a silent skip — this is the documented execution verb, so it must be
        // obvious when it does nothing.
        val launches = mutableListOf<MacroLaunchRequest>()
        var skipped = 0
        operations.forEach { (operation, context) ->
            when (val resolution = resolveLaunch(operation, boxMap, context)) {
                is LaunchResolution.Ready -> launches.add(resolution.request)
                is LaunchResolution.Failed -> {
                    skipped++
                    lprintln("error,operation-transcript", "macro launch failed: ${resolution.reason}")
                    diagnostics?.add(resolution.reason)
                    notify(resolution.reason, "macro-launch-error")
                }
                LaunchResolution.NotLaunch -> skipped++
            }
        }
        if (launches.isEmpty()) return 0 to operations.size

        val cleared = mutableSetOf<String>()
        launches.forEach { launch ->
            if (cleared.add(launch.target.id)) {
                send(Message("clear-editor-outputs").payload(ClearEditorOutputs(launch.target.id)))
            }
            notifyMacroLaunch(launch.target, launch.exec, launch.context)
        }

        val requests = launches.map { it.target to it.exec }
        if (requests.all { (target, _) -> target.ke is KotlinExecution }) {
            runBlocking {
                KotlinExecution.launchBatchAndWait(requests) { send(it); collect(it) }
            }
        } else {
            requests.forEach { (target, exec) ->
                target.ke.launchAll(target, exec, exec.code, strand = exec.strand, reply = { send(it); collect(it) })
            }
        }

        return launches.size to skipped
    }

    private sealed interface LaunchResolution {
        data class Ready(val request: MacroLaunchRequest) : LaunchResolution
        data class Failed(val reason: String) : LaunchResolution
        object NotLaunch : LaunchResolution
    }

    private fun resolveLaunch(
        operation: Map<String, Any?>,
        boxMap: Map<String, String>,
        context: MacroReplayContext
    ): LaunchResolution {
        val verb = operation.keys.firstOrNull()?.lowercase() ?: return LaunchResolution.NotLaunch
        if (verb !in launchVerbs) return LaunchResolution.NotLaunch
        val payload = operation.values.firstOrNull() as? Map<*, *>
            ?: return LaunchResolution.Failed("`$verb` op has no payload map")
        val raw = (payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"])?.toString()
            ?: return LaunchResolution.Failed("`$verb` op is missing a box id (expected `box`, `uid`, `id`, or `target`)")
        val mapped = boxMap[raw] ?: raw
        val info = thisSessionInfo.get()
        val target = info.boxes[mapped]
            ?: return LaunchResolution.Failed(
                "`$verb` target box `$mapped` is not present in the document${describePresentBoxes(info)}"
            )
        val exec = executeFromPayload(target.id, payload, fallbackType = payload["type"]?.toString() ?: "launch")
        if (exec.code.isBlank()) {
            return LaunchResolution.Failed(
                "`$verb` target box `$mapped` has no code to run: neither the op nor the box's " +
                    "`${exec.property}` property carries any code"
            )
        }
        return LaunchResolution.Ready(MacroLaunchRequest(target, exec, context))
    }

    private fun describePresentBoxes(info: SessionInformation): String {
        val ids = info.boxes.keys.filter { it != "root" && it != METAROOT_ID }.sorted()
        return if (ids.isEmpty()) {
            " (no user boxes are loaded — check the document/workspace path: `--document` is resolved relative to the workspace root)"
        } else {
            " (present: ${ids.joinToString(", ")})"
        }
    }

    private fun notifyMacroLaunch(target: Box, execute: Execute, context: MacroReplayContext) {
        val macro = context.macroName.ifBlank { "macro" }
        val launchProgress = if (context.launchIndex > 0 && context.launchCount > 0) {
            " ${context.launchIndex}/${context.launchCount}"
        } else {
            ""
        }
        val operationProgress = if (context.operationIndex > 0 && context.operationCount > 0) {
            " op ${context.operationIndex}/${context.operationCount}"
        } else {
            ""
        }
        val strand = execute.strand.ifBlank { "default" }
        val property = execute.property.ifBlank { "code" }
        val label = target.name.ifBlank { target.id.take(8) }
        val message = "Macro '${escapeNotifyText(macro)}' launch$launchProgress$operationProgress: " +
            "${escapeNotifyText(label)}.${escapeNotifyText(property)} strand ${escapeNotifyText(strand)}"
        val notifyMessage = Message("notify").payload(Notify(message, "macro-launch"))
        val documentPath = documentPathFor(target)
        if (documentPath == null) {
            notify(message, "macro-launch")
        } else {
            _send(notifyMessage, document = Regex.escape(documentPath))
        }
        lprintln(
            "info,operation-transcript",
            "macro launch name=$macro launchIndex=${context.launchIndex}/${context.launchCount} " +
                "op=${context.operationIndex}/${context.operationCount} box=${target.id} property=$property strand=$strand"
        )
    }

    private fun escapeNotifyText(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun tombstoneBox(target: Box) {
        if (target.id == "root" || target.id == METAROOT_ID) return
        val info = thisSessionInfo.get()
        if (target.nestedIn != null) {
            target.nestedIn = null
        }
        target.setPropertyRaw("_tombstone", true, doResync = true)
        info.root.disconnect(target)
        needsResync.add(info.root to "children")
        needsResync.add(target to "parents")
        needsResync.add(info.root to "actions")
        needsResync.add(target to "actions")
        info.topologyGeneration++
    }

    private fun waitForRunloopFrames(frames: Long) {
        if (frames <= 0L) return
        val target = Runloop.runLoop.frame + frames
        while (Runloop.runLoop.frame < target) {
            Thread.sleep(1L)
        }
    }

    // Barrier: advance runloop frames until every previously-fired executeFragment has
    // finished (no box still has `_currentlyExecutingFragment`), holding for a couple of
    // settle frames so launches reach their first rendered frame. Bounded by maxFrames.
    // Pumping frames (not Thread.sleep) is what lets the queued inMain fragment work run
    // and its output flow back through the `collect` reply into the run result.
    private fun awaitPending(payload: Map<*, *>) {
        val info = runCatching { thisSessionInfo.get() }.getOrNull() ?: return
        val maxFrames = payload["maxFrames"]?.asLongOrDefault(-1L)?.takeIf { it >= 0L } ?: 600L
        val settleFrames = payload["settleFrames"]?.asLongOrDefault(-1L)?.takeIf { it >= 0L } ?: 2L
        val startFrame = Runloop.runLoop.frame
        var idle = 0L
        while (Runloop.runLoop.frame - startFrame < maxFrames) {
            val anyFragment = info.boxes.values.any {
                runCatching { it._currentlyExecutingFragment }.getOrDefault(false)
            }
            val advanced = Runloop.runLoop.frame - startFrame
            if (!anyFragment) {
                idle++
                if (idle >= settleFrames && advanced >= 1L) break
            } else {
                idle = 0L
            }
            val before = Runloop.runLoop.frame
            while (Runloop.runLoop.frame == before && Runloop.runLoop.frame - startFrame < maxFrames) {
                Thread.sleep(1L)
            }
        }
    }

    // Capture box output/error messages (from execute/launch replies) so a transcript
    // run can report what the boxes printed. Only "output" messages carry box stdout.
    private fun captureMacroOutput(message: Message, sink: MutableList<MacroOutputLine>) {
        if (message.name != "output") return
        val payload = message.payload as? JsonObject ?: return
        fun str(k: String) = (payload[k] as? JsonPrimitive)?.contentOrNull
        fun int(k: String) = (payload[k] as? JsonPrimitive)?.intOrNull
        val html = str("html")?.trim().orEmpty()
        if (html.isEmpty()) return
        sink.add(
            MacroOutputLine(
                uid = str("uid").orEmpty(),
                kind = str("kind") ?: "normal",
                line = int("line") ?: -1,
                html = html
            )
        )
    }

    private fun executeFromPayload(uid: String, payload: Map<*, *>, fallbackType: String): Execute {
        val property = (payload["property"] ?: payload["prop"] ?: "code").toString()
        // Fall back to the box's stored property when the op carries no code, so a
        // `create` (which sets e.g. `code`) followed by a bare `execute`/`launch`
        // runs that stored code instead of an empty string (which silently no-ops).
        val explicitCode = payload["code"]?.toString()
        val code = if (!explicitCode.isNullOrEmpty()) {
            explicitCode
        } else {
            runCatching { box(uid, createIfMissing = false).getPropertyRaw(property) as? String }
                .getOrNull().orEmpty()
        }
        return Execute(
            uid = uid,
            type = (payload["type"] ?: fallbackType).toString(),
            line = payload["line"].asIntOrDefault(0),
            code = code,
            singleLine = payload["singleLine"].asBooleanOrDefault(false),
            property = property,
            strand = (payload["strand"] ?: "default").toString().ifBlank { "default" }
        )
    }

    private fun transformText(current: String, expected: String, replacement: String, strict: Boolean): String? {
        if (current == expected) return replacement
        val diff = changedMiddle(expected, replacement) ?: return if (strict) null else replacement
        val oldMiddle = diff.first
        val newMiddle = diff.second
        if (oldMiddle.isEmpty()) {
            if (current.startsWith(diff.third) && current.endsWith(diff.fourth)) {
                return diff.third + newMiddle + current.removePrefix(diff.third)
            }
            return if (strict) null else replacement
        }
        val first = current.indexOf(oldMiddle)
        if (first >= 0 && current.indexOf(oldMiddle, first + oldMiddle.length) < 0) {
            return current.replaceFirst(oldMiddle, newMiddle)
        }
        return if (strict) null else replacement
    }

    private fun changedMiddle(before: String, after: String): Quad<String, String, String, String>? {
        var prefix = 0
        val maxPrefix = minOf(before.length, after.length)
        while (prefix < maxPrefix && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(before.length - prefix, after.length - prefix)
        while (suffix < maxSuffix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
        val beforeMiddle = before.substring(prefix, before.length - suffix)
        val afterMiddle = after.substring(prefix, after.length - suffix)
        val prefixText = before.substring(0, prefix)
        val suffixText = before.substring(before.length - suffix)
        return Quad(beforeMiddle, afterMiddle, prefixText, suffixText)
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun Any?.orEmptyString(): String =
        this?.toString().orEmpty()

    private fun Any?.asDouble(): Double = when (this) {
        is Number -> toDouble()
        else -> toString().toDouble()
    }

    private fun Any?.asDoubleOrNull(): Double? = when (this) {
        is Number -> toDouble()
        null -> null
        else -> toString().toDoubleOrNull()
    }

    private fun Any?.asLongOrDefault(default: Long): Long = when (this) {
        is Number -> toLong()
        null -> default
        else -> toString().toLongOrNull() ?: default
    }

    private fun rectFromMap(frameMap: Map<*, *>): Rect =
        Rect(
            frameMap["x"].asDouble(),
            frameMap["y"].asDouble(),
            frameMap["w"].asDouble(),
            frameMap["h"].asDouble()
        )

    private fun Any?.asIntOrDefault(default: Int): Int = when (this) {
        is Number -> toInt()
        null -> default
        else -> toString().toIntOrNull() ?: default
    }

    private fun Any?.asBooleanOrDefault(default: Boolean): Boolean = when (this) {
        is Boolean -> this
        null -> default
        else -> toString().toBooleanStrictOrNull() ?: default
    }

    private fun writeYamlList(entries: List<Map<String, Any?>>): String {
        if (entries.isEmpty()) return "[]\n"
        return buildString {
            entries.forEach { entry ->
                entry.forEach { (key, value) ->
                    append("- ")
                    append(key)
                    append(":\n")
                    appendYamlValue(value, indent = 4)
                }
            }
        }
    }

    private fun StringBuilder.appendYamlValue(value: Any?, indent: Int) {
        val prefix = " ".repeat(indent)
        when (value) {
            is Map<*, *> -> value.forEach { (key, entryValue) ->
                append(prefix)
                append(key.toString())
                append(":")
                when {
                    entryValue is Map<*, *> -> {
                        append("\n")
                        appendYamlValue(entryValue, indent + 2)
                    }
                    entryValue is List<*> -> {
                        append("\n")
                        appendYamlValue(entryValue, indent + 2)
                    }
                    entryValue is String && shouldUseBlockScalar(key.toString(), entryValue) -> {
                        appendBlockScalar(entryValue, indent)
                    }
                    else -> {
                        append(" ")
                        append(formatScalar(entryValue))
                        append("\n")
                    }
                }
            }

            is List<*> -> {
                if (value.isEmpty()) {
                    append(prefix)
                    append("[]\n")
                } else {
                    value.forEach { item ->
                        append(prefix)
                        append("-")
                        when (item) {
                            is Map<*, *> -> {
                                append("\n")
                                appendYamlValue(item, indent + 2)
                            }
                            is List<*> -> {
                                append("\n")
                                appendYamlValue(item, indent + 2)
                            }
                            is String -> {
                                if (shouldUseBlockScalar(null, item)) {
                                    appendBlockScalar(item, indent)
                                } else {
                                    append(" ")
                                    append(formatScalar(item))
                                    append("\n")
                                }
                            }
                            else -> {
                                append(" ")
                                append(formatScalar(item))
                                append("\n")
                            }
                        }
                    }
                }
            }

            else -> {
                append(prefix)
                append(formatScalar(value))
                append("\n")
            }
        }
    }

    private fun shouldUseBlockScalar(key: String?, value: String): Boolean {
        if (value.isEmpty()) return false
        if (value.contains('\n')) return true
        val textKey = key?.lowercase() in setOf("from", "to", "value", "text", "code")
        return textKey || value.contains('"') || value.contains('\\')
    }

    private fun StringBuilder.appendBlockScalar(value: String, indent: Int) {
        val contentIndent = " ".repeat(indent + 2)
        val preservesFinalNewline = value.endsWith("\n")
        append(" ")
        append(if (preservesFinalNewline) "|" else "|-")
        append("\n")
        val body = if (preservesFinalNewline) value.dropLast(1) else value
        body.lines().forEach { line ->
            append(contentIndent)
            append(line)
            append("\n")
        }
    }

    private fun formatScalar(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> {
            val text = value.toString()
            if (text.isEmpty()) "\"\"" else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}
