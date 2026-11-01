package afield.tools.automation

import afield.app.diagnostics.named
import afield.app.protocol.Completion
import afield.app.protocol.handlers
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.nested
import afield.document.workspace.metadata
import afield.runtime.execution.plus
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.tools.docs.screenshot
import afield.ui.notation.Mark
import afield.ui.notation.string
import afield.ui.notation.with
import afield.ui.output.transcript
import afield.ui.output.tree

import afield.app.notifications.notify
import afield.app.protocol.AgentBoxScreenshot
import afield.app.protocol.AgentWindowScreenshot
import afield.app.protocol.ClearEditorOutputs
import afield.app.protocol.Execute
import afield.app.protocol.ExecutionReport
import afield.app.protocol.MacroOutputLine
import afield.app.protocol.Message
import afield.app.protocol.Notify
import afield.app.protocol.Rect
import afield.app.protocol.TranscriptEntry
import afield.app.protocol._send
import afield.app.protocol.computeIfAbsent
import afield.app.protocol.payload
import afield.app.protocol.send
import afield.app.server.PLAYWRIGHT_VIEWPORT_HEIGHT
import afield.app.server.PLAYWRIGHT_VIEWPORT_WIDTH
import afield.app.server.Server
import afield.app.server.SessionInformation
import afield.app.server.ReadOnlyDocuments
import afield.app.server.documents
import afield.app.server.ensurePlaywrightForSessionIfNeeded
import afield.app.server.globalPlaywriteContext
import afield.app.server.playwrightDispatcher
import afield.app.server.thisSessionInfo
import afield.audio.synth.*
import afield.data.table.get
import afield.document.graph.Box
import afield.document.graph.METAROOT_ID
import afield.document.graph.box
import afield.document.graph.doResync
import afield.document.graph.isTrue
import afield.document.graph.needsResync
import afield.document.graph.nestedIn
import afield.document.persistence.SavingContext
import afield.document.persistence.safeSave
import afield.document.workspace.ProjectEntry
import afield.document.workspace.listWorkspaceProjects
import afield.document.workspace.projectsDir
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.execution.ExecutionVerdict
import afield.runtime.execution.KotlinExecution
import afield.runtime.execution.launches
import afield.runtime.execution.sourceRevision
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.frames
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.map
import afield.tools.docs.DocsUiDriver
import afield.tools.docs.DocsUiRecorder
import afield.tools.docs.captureBoxScreenshot
import afield.tools.docs.captureClippedScreenshot
import afield.tools.docs.captureWindowScreenshot
import afield.tools.docs.computeDriveClip
import afield.tools.docs.computeSelectorClip
import afield.ui.output.templates.filter
import afield.ui.visualization.h

import afield.app.diagnostics.logging.lprintln
import afield.runtime.refreshMainLanguageRuntimes
import afield.runtime.execution._currentlyExecutingFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

/**
 * Records the operations a person or agent performs on a document and replays them as named macros.
 *
 * [OperationTranscript] logs edits, moves, executions, launches and stops as they happen
 * (`recordEdit`, `recordMove`, `recordExecuteFragment`, `recordLaunch`, `recordStop`), and stores
 * macros as YAML files in a `macros/` folder at document, project or workspace scope
 * (`listMacros`, `writeMacro`, `runMacro`). The backdoor's `macro write` and `macro run` commands
 * use this; the YAML format and examples are in `dev_docs/agents/OPERATION_TRANSCRIPT.md`.
 */

/** Runs one operation per key at a time; a caller arriving while it runs waits for the same result. */
internal class InFlightOperationCoalescer<K, V> {
    private val active = ConcurrentHashMap<K, CompletableFuture<V>>()

    fun run(key: K, onJoin: (() -> Unit)? = null, operation: () -> V): V {
        val mine = CompletableFuture<V>()
        val existing = active.putIfAbsent(key, mine)
        if (existing != null) {
            onJoin?.invoke()
            return existing.join()
        }

        try {
            return operation().also(mine::complete)
        } catch (failure: Throwable) {
            mine.completeExceptionally(failure)
            throw failure
        } finally {
            active.remove(key, mine)
        }
    }
}

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
        val output: List<MacroOutputLine> = emptyList(),
        val setupVerified: Boolean = true
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
        val output: List<MacroOutputLine> = emptyList(),
        val setupVerified: Boolean = true
    )

    private data class DocumentState(
        val documentPath: String,
        val lock: Any = Any(),
        val entries: MutableList<LinkedHashMap<String, Any?>> = mutableListOf(),
        var pendingEdit: PendingEdit? = null,
        var lastRecordedFrame: Long? = null,
        var loaded: Boolean = false,
        var dirty: Boolean = false,
        /** Entries before this index are never merged into (an AI turn's boundary; in memory only). */
        var mergeBarrier: Int = 0
    )

    private data class PendingEdit(
        val box: String,
        val property: String,
        val from: String,
        var to: String,
        var lastFrame: Long,
        /** Who made it: blank for a person, `ai4:<turn>` for an AI turn's tools. Never merged across. */
        val origin: String = ""
    )

    private data class MacroLocation(
        val scope: String,
        val projectName: String,
        val dir: File
    )

    private fun macroWriteRefusal(documentPath: String, location: MacroLocation, name: String = ""): MacroResult? {
        if (location.scope != "document" || !ReadOnlyDocuments.blocksWritesTo(normalizeDocumentPath(documentPath))) return null
        val target = if (name.isBlank()) location.dir else macroFile(location, name)
        ReadOnlyDocuments.refuseWrite("OperationTranscript.macro", target.absolutePath)
        return MacroResult(false, "The document is read-only; its macros cannot be changed.",
            name = name, path = target.absolutePath, scope = location.scope, projectName = location.projectName)
    }

    private data class MacroReplayContext(
        val macroName: String = "",
        val operationIndex: Int = -1,
        val operationCount: Int = -1,
        val launchIndex: Int = -1,
        val launchCount: Int = -1,
        val initializeCreatedBoxes: Boolean = true,
    )

    private data class MacroRunKey(
        val file: String,
        val targets: List<String>,
        val direction: String,
        val strict: Boolean,
        val initializeCreatedBoxes: Boolean,
        val replay: MacroReplayOptions = MacroReplayOptions(),
    )

    /**
     * How a run places what it replays. [asCopy] gives the boxes the macro creates fresh uids, so a
     * second run makes a second set; [offsetX]/[offsetY] shift their frames; [step] replays one
     * operation (0-based) of the macro and nothing else, which is how the panel steps through one.
     */
    data class MacroReplayOptions(
        val asCopy: Boolean = false,
        val offsetX: Double = 0.0,
        val offsetY: Double = 0.0,
        val step: Int = -1,
    )

    private val activeMacroRuns = InFlightOperationCoalescer<MacroRunKey, MacroRunResult>()

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

        val location = macroLocation(documentPath, scope, projectName)
            ?: return MacroResult(false, "No project macro scope is available for this document.")
        macroWriteRefusal(documentPath, location, cleanName)?.let { return it }

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
            withoutIdleWaits(coalesceOperationRuns(slice)) to startSelection
        } ?: run {
            checkpoint(documentPath)
            return MacroResult(false, "No macro start marker found.")
        }

        val (slice, sourceSelection) = sliceAndSelection
        val macroPath = macroFile(location, cleanName)
        val text = writeMacroYaml(cleanName, documentPath, slice, sourceSelection)
        macroPath.parentFile?.mkdirs()
        val wrote = macroPath.safeSave(text, createBackup = true, maxBackups = 10, atomic = true)
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
        macroWriteRefusal(documentPath, location, cleanName)?.let { return it }
        val file = macroFile(location, cleanName)
        file.parentFile?.mkdirs()
        val wrote = file.safeSave(contents.trimEnd() + "\n", createBackup = true, maxBackups = 10, atomic = true)
        val count = runCatching { loadMacroOperations(file).size }.getOrDefault(0)
        return MacroResult(
            // safeSave returns false when the requested contents are already on disk.
            success = true,
            message = if (wrote) "Wrote macro '$cleanName'." else "Macro '$cleanName' was unchanged.",
            name = cleanName,
            path = file.absolutePath,
            operationCount = count,
            content = file.readText(),
            scope = location.scope,
            projectName = location.projectName
        )
    }

    /**
     * Promote a macro to a broader scope so more documents can share it. The destination is the
     * project when this document belongs to one, otherwise the workspace. The source is copied
     * verbatim (a backup of any existing destination copy is kept), leaving the original in place.
     */
    fun promoteMacro(
        documentPath: String,
        name: String,
        scope: String = "document",
        projectName: String = ""
    ): MacroResult {
        if (documentPath.isBlank()) return MacroResult(false, "No document path available.")
        val cleanName = sanitizeMacroName(name)
        if (cleanName.isBlank()) return MacroResult(false, "Choose a macro to promote.", name = cleanName)
        val source = macroLocation(documentPath, scope, projectName)
            ?: return MacroResult(false, "No macro scope is available for this document.", name = cleanName)
        val sourceFile = macroFile(source, cleanName)
        if (!sourceFile.exists()) {
            return MacroResult(
                false,
                "No macro named '$cleanName' in the ${source.scope} scope.",
                name = cleanName,
                scope = source.scope,
                projectName = source.projectName
            )
        }
        // Escalate one step up the sharing hierarchy: document -> project (when in one) -> workspace.
        // Workspace is the broadest scope, so it is the terminus.
        val project = projectForDocument(documentPath, projectName)
        val targetScope = when {
            source.scope.equals("workspace", ignoreCase = true) -> "workspace"
            source.scope.equals("project", ignoreCase = true) -> "workspace"
            project != null -> "project"
            else -> "workspace"
        }
        val target = macroLocation(documentPath, targetScope, project?.name.orEmpty())
            ?: return MacroResult(
                false,
                "No $targetScope macro scope is available for this document.",
                name = cleanName,
                scope = source.scope,
                projectName = source.projectName
            )
        val destName = if (target.scope == "project") "project '${target.projectName}'" else "workspace"
        if (target.dir.canonicalFile == source.dir.canonicalFile) {
            return MacroResult(
                false,
                "Macro '$cleanName' is already in the $destName.",
                name = cleanName,
                scope = target.scope,
                projectName = target.projectName
            )
        }
        val targetFile = macroFile(target, cleanName)
        val contents = sourceFile.readText().trimEnd() + "\n"
        targetFile.parentFile?.mkdirs()
        val wrote = targetFile.safeSave(contents, createBackup = true, maxBackups = 10, atomic = true)
        val count = runCatching { loadMacroOperations(targetFile).size }.getOrDefault(0)
        return MacroResult(
            success = wrote || targetFile.exists(),
            message = if (wrote) {
                "Promoted macro '$cleanName' to the $destName."
            } else {
                "Macro '$cleanName' is already up to date in the $destName."
            },
            name = cleanName,
            path = targetFile.absolutePath,
            operationCount = count,
            content = contents,
            scope = target.scope,
            projectName = target.projectName
        )
    }

    /**
     * Rename a saved macro within its scope. The YAML's own `name:` line follows the file name, so
     * the panel and the palette never disagree about what the macro is called. Refuses to replace
     * an existing macro of the new name.
     */
    fun renameMacro(
        documentPath: String,
        name: String,
        newName: String,
        scope: String = "document",
        projectName: String = ""
    ): MacroResult {
        val cleanName = sanitizeMacroName(name)
        val cleanNew = sanitizeMacroName(newName)
        if (cleanName.isBlank() || cleanNew.isBlank()) return MacroResult(false, "Macro names cannot be empty.")
        val location = macroLocation(documentPath, scope, projectName)
            ?: return MacroResult(false, "No $scope macro scope is available for this document.", name = cleanName)
        macroWriteRefusal(documentPath, location, cleanName)?.let { return it }
        val source = macroFile(location, cleanName)
        if (!source.exists()) return MacroResult(false, "No macro named '$cleanName'.", name = cleanName, scope = location.scope)
        if (cleanNew == cleanName) return MacroResult(true, "Macro '$cleanName' already has that name.", name = cleanName, scope = location.scope)
        val target = macroFile(location, cleanNew)
        if (target.exists()) return MacroResult(false, "A macro named '$cleanNew' already exists.", name = cleanName, scope = location.scope)
        val text = renameMacroYaml(source.readText(), cleanNew)
        val wrote = target.safeSave(text, createBackup = false, atomic = true)
        if (!wrote && !target.exists()) return MacroResult(false, "Could not write '$cleanNew'.", name = cleanName, scope = location.scope)
        source.delete()
        return MacroResult(
            true,
            "Renamed macro '$cleanName' to '$cleanNew'.",
            name = cleanNew,
            path = target.absolutePath,
            content = target.readText(),
            operationCount = runCatching { loadMacroOperations(target).size }.getOrDefault(0),
            scope = location.scope,
            projectName = location.projectName
        )
    }

    /** The YAML of a macro with its top-level `name:` set to [name] (added when it has none). */
    internal fun renameMacroYaml(text: String, name: String): String {
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("name:") }
        val line = "name: ${formatScalar(name)}"
        if (index >= 0) lines[index] = line else lines.add(0, line)
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    /**
     * Delete a saved macro. The file is moved to the scope's `.deleted/` folder with a timestamp,
     * not erased, so a deletion from the panel can be recovered by hand; the macro lists only read
     * the scope folder itself.
     */
    fun deleteMacro(
        documentPath: String,
        name: String,
        scope: String = "document",
        projectName: String = ""
    ): MacroResult {
        val cleanName = sanitizeMacroName(name)
        if (cleanName.isBlank()) return MacroResult(false, "Choose a macro to delete.")
        val location = macroLocation(documentPath, scope, projectName)
            ?: return MacroResult(false, "No $scope macro scope is available for this document.", name = cleanName)
        macroWriteRefusal(documentPath, location, cleanName)?.let { return it }
        val source = macroFile(location, cleanName)
        if (!source.exists()) return MacroResult(false, "No macro named '$cleanName'.", name = cleanName, scope = location.scope)
        val trash = File(location.dir, ".deleted").also { it.mkdirs() }
        val stamp = Instant.now().toString().replace(":", "-")
        val target = File(trash, "$cleanName-$stamp.yaml")
        val moved = source.renameTo(target) || run {
            target.writeText(source.readText())
            source.delete()
        }
        return MacroResult(
            moved,
            if (moved) "Deleted macro '$cleanName' (kept in ${trash.name}/${target.name})." else "Could not delete '$cleanName'.",
            name = cleanName,
            path = target.absolutePath,
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
        macroWriteRefusal(documentPath, location, cleanName)?.let { return it }
        val file = macroFile(location, cleanName)
        val sourceSelection = selectionEntries(selection)
        val text = writeMacroYaml(cleanName, documentPath, coalesced, sourceSelection)
        file.parentFile?.mkdirs()
        val wrote = file.safeSave(text, createBackup = true, maxBackups = 10, atomic = true)
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
        if (!location.dir.isDirectory) macroWriteRefusal(documentPath, location)?.let { return it }
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
        projectName: String = "",
        initializeCreatedBoxes: Boolean = true,
        replay: MacroReplayOptions = MacroReplayOptions(),
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
        val normalizedDirection = if (direction.equals("undo", ignoreCase = true)) "undo" else "do"
        val normalizedTargets = targetBoxes.filter { it.isNotBlank() }
        val key = MacroRunKey(
            file = file.canonicalPath,
            targets = normalizedTargets,
            direction = normalizedDirection,
            strict = strict,
            initializeCreatedBoxes = initializeCreatedBoxes,
            replay = replay,
        )
        // Box code calling this (a box that replays a macro) runs on a thread with no session
        // bound; the document's own session is the one the macro's operations act on.
        val bound = runCatching { thisSessionInfo.get() }.getOrNull()
        val session = if (bound != null) null else documents.values.firstOrNull {
            normalizeDocumentPath(it.filename) == normalizeDocumentPath(documentPath)
        }
        if (session != null) thisSessionInfo.set(session)
        try {
            return activeMacroRuns.run(key) {
                afield.app.diagnostics.LatencySpans.time("transcript.run", detail = cleanName) {
                    runMacroOnce(
                        documentPath = documentPath,
                        name = cleanName,
                        targetBoxes = normalizedTargets,
                        direction = normalizedDirection,
                        strict = strict,
                        scope = scope,
                        projectName = projectName,
                        initializeCreatedBoxes = initializeCreatedBoxes,
                        replay = replay,
                    )
                }
            }
        } finally {
            if (session != null) thisSessionInfo.remove()
        }
    }

    private fun runMacroOnce(
        documentPath: String,
        name: String,
        targetBoxes: List<String>,
        direction: String,
        strict: Boolean,
        scope: String,
        projectName: String,
        initializeCreatedBoxes: Boolean,
        replay: MacroReplayOptions = MacroReplayOptions(),
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
        val loaded = loadMacroOperations(file)
        val relocated = if (replay.asCopy || replay.offsetX != 0.0 || replay.offsetY != 0.0) {
            relocateOperations(loaded, replay.asCopy, replay.offsetX, replay.offsetY)
        } else loaded
        val operations = if (replay.step >= 0) {
            val one = relocated.getOrNull(replay.step)
                ?: return MacroRunResult(
                    false,
                    "Macro '$cleanName' has no step ${replay.step + 1} (it has ${relocated.size}).",
                    cleanName, direction, targetBoxes, 0, 0
                )
            listOf(one)
        } else relocated
        val normalizedDirection = if (direction.equals("undo", ignoreCase = true)) "undo" else "do"
        val ordered = if (normalizedDirection == "undo") operations.asReversed() else operations
        val targets = targetBoxes.filter { it.isNotBlank() }.ifEmpty { listOf("") }
        val launchCount = ordered.count { operationVerb(it) in launchVerbs }
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        var applied = 0
        var skipped = 0
        val collected = java.util.Collections.synchronizedList(mutableListOf<MacroOutputLine>())
        val unverified = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val collect: (Message) -> Unit = { msg ->
            if (msg.name == "execution-dispatched") {
                val payload = msg.payload as? kotlinx.serialization.json.JsonObject
                unverified.add(payload?.get("uid")?.toString().orEmpty())
            }
            // Every engine answers a dispatched execution with a verdict; that is the receipt that
            // turns "dispatched" into "completed" (or into an error) for a non-Kotlin box.
            if (msg.name == "execution-verdict") {
                val payload = msg.payload as? kotlinx.serialization.json.JsonObject
                val uid = payload?.get("uid")?.toString().orEmpty()
                if (unverified.remove(uid)) {
                    val ok = (payload?.get("success") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                    if (!ok) errors += "execution of $uid failed: " +
                        ((payload?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "no message")
                }
            }
            synchronized(collected) { if (collected.size < 200) captureMacroOutput(msg, collected) }
        }

        targets.forEach { target ->
            if (normalizedDirection == "do" && errors.isNotEmpty()) {
                skipped += ordered.size
                return@forEach
            }
            val primarySource = firstOperationBox(relocated)
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
                            launchCount = launchCount,
                            initializeCreatedBoxes = initializeCreatedBoxes,
                        )
                        index++
                    }
                    val groupDiagnostics = mutableListOf<String>()
                    val groupBoxes = launchGroup.joinToString(",") { (op, _) -> operationBoxName(op).orEmpty() }
                    val result = runCatching {
                        afield.app.diagnostics.LatencySpans.time("transcript.launchGroup", detail = "$cleanName: $groupBoxes") {
                            applyMacroLaunchGroup(launchGroup, boxMap, collect, groupDiagnostics)
                        }
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
                    if (errors.isNotEmpty()) {
                        skipped += ordered.size - index
                        break
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
                    launchCount = launchCount,
                    initializeCreatedBoxes = initializeCreatedBoxes,
                )
                val result = runCatching {
                    afield.app.diagnostics.LatencySpans.time("transcript.op", box = operationBoxName(op), detail = "$cleanName: $verb") {
                        applyMacroOperation(op, boxMap, normalizedDirection, strict, context, collect)
                    }
                }
                if (result.isSuccess) {
                    if (result.getOrDefault(false)) applied++ else skipped++
                } else {
                    skipped++
                        errors += result.exceptionOrNull()?.let(::describeMacroFailure)
                            ?: "Unknown macro operation failure."
                }
                index++
                // A failed prerequisite must not launch dependent code. Undo remains
                // best-effort so a broken box cannot prevent later cleanup operations.
                if (normalizedDirection == "do" && errors.isNotEmpty()) {
                    skipped += ordered.size - index
                    break
                }
            }
        }
        // Give non-Kotlin executions a bounded moment to report their verdicts before the receipt is written.
        val verdictDeadline = System.nanoTime() + 10_000_000_000L
        // Only off the Runloop: verdicts are delivered through it, so waiting on it would only stall.
        if (!afield.runtime.lifecycle.Runloop.isMainThread) {
            while (unverified.isNotEmpty() && System.nanoTime() < verdictDeadline) Thread.sleep(20)
        }
        if (applied > 0) runCatching { doResync() }
        val noChangeMessage = if (errors.isEmpty() && applied == 0) {
            " No operations changed anything; check the target selection and source uid mapping."
        } else {
            ""
        }
        val dispatchMessage = if (unverified.isEmpty()) "" else
            " ${unverified.size} non-Kotlin execution(s) dispatched; setup completion is unverified."
        return MacroRunResult(
            success = errors.isEmpty(),
            message = "Ran macro '$cleanName': applied=$applied skipped=$skipped errors=${errors.size}.$noChangeMessage$dispatchMessage",
            name = cleanName,
            direction = normalizedDirection,
            targetBoxes = targetBoxes,
            applied = applied,
            skipped = skipped,
            errors = errors,
            output = collected.toList(),
            setupVerified = unverified.isEmpty() && errors.isEmpty()
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

    fun recordEdit(box: Box, property: String, from: String?, to: String?, origin: String = "") {
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
                    pending.origin == origin &&
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
                lastFrame = frame,
                origin = origin
            )
            state.dirty = true
        }
    }

    /**
     * Mark an AI turn's start or end: flush the pending edit and forbid merging into anything recorded so far.
     * Returns the cursor (the number of entries), which the session tree keeps as the turn's `op.from`/`op.to`.
     */
    fun turnBoundary(documentPath: String): Int {
        if (documentPath.isBlank()) return -1
        val state = stateFor(documentPath)
        return synchronized(state.lock) {
            ensureLoaded(state)
            flushPendingEdit(state)
            state.mergeBarrier = state.entries.size
            state.entries.size
        }
    }

    /** How many recorded edits were made under one of [origins]. */
    fun countOrigins(documentPath: String, origins: Set<String>): Int {
        if (documentPath.isBlank() || origins.isEmpty()) return 0
        return transcriptSnapshot(documentPath).count { ((it["edit"] as? Map<*, *>)?.get("origin")?.toString()) in origins }
    }

    /**
     * Undo every recorded edit made under one of [origins] (AI turns being rewound), newest first, strictly:
     * an edit whose property has changed since is skipped and reported, never overwritten.
     */
    fun undoOrigins(documentPath: String, origins: Set<String>): MacroResult {
        if (documentPath.isBlank() || origins.isEmpty()) return MacroResult(false, "Nothing to undo.")
        val snapshot = transcriptSnapshot(documentPath)
        val indices = snapshot.indices.filter { i -> ((snapshot[i]["edit"] as? Map<*, *>)?.get("origin")?.toString()) in origins }
        if (indices.isEmpty()) return MacroResult(true, "Those turns recorded no document edits.", direction = "undo")
        return undoTranscriptSelection(documentPath, indices, strict = true)
    }

    fun recordMove(box: Box, from: Rect, to: Rect) {
        if (from == to) return
        val documentPath = documentPathFor(box) ?: return
        val frame = Runloop.runLoop.frame
        val state = stateFor(documentPath)
        synchronized(state.lock) {
            ensureLoaded(state)
            flushPendingEdit(state)
            if (foldIntoPreviousCreate(state.entries, box.id, "frame", rectMap(to))) {
                state.lastRecordedFrame = frame
                state.dirty = true
                return
            }
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

    // Recorded executions name the box and where in it, not a copy of its code: a replay runs the
    // box as it is then (MacroReplayCode). A fragment keeps its text as `recordedCode`, which the
    // replay looks for in the box's current code; a whole-box launch keeps one only when what ran
    // was not the box's stored code.
    fun recordExecuteFragment(box: Box, execute: Execute) {
        recordExecutionEvent(
            box,
            "execute",
            linkedMapOf<String, Any?>(
                "box" to box.id,
                "property" to execute.property,
                "line" to execute.line,
                "recordedCode" to execute.code,
                "singleLine" to execute.singleLine,
                "type" to execute.type
            )
        )
    }

    fun recordLaunch(box: Box, execute: Execute, strand: String = execute.strand) {
        val payload = linkedMapOf<String, Any?>(
            "box" to box.id,
            "property" to execute.property,
            "line" to execute.line
        )
        val stored = box.getPropertyRaw(execute.property) as? String
        if (execute.type == "fragment" || stored?.trim() != execute.code.trim()) payload["recordedCode"] = execute.code
        payload["singleLine"] = execute.singleLine
        payload["type"] = execute.type
        payload["strand"] = strand.ifBlank { "default" }
        recordExecutionEvent(box, "launch", payload)
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

    /**
     * A box the person just made on the canvas. The frontend creates a box by sending its name and
     * then its frame (`createBox` in Main.kt), so the box's first property message is where the
     * server learns of it; the handlers call this once the value is applied. The entry carries the
     * box's name and frame, and the frame / first-value edits that follow are folded into it (see
     * [foldIntoPreviousCreate]), so a recording reads `create {box, name, frame, properties}` rather
     * than an edit from nothing. Without it a recording replayed into a fresh document edited boxes
     * that did not exist (audit, 2026-10-20: `applied=2 skipped=5`, no boxes).
     */
    fun recordCreate(box: Box) {
        if (box.id == "root" || box.id == METAROOT_ID) return
        val documentPath = documentPathFor(box) ?: return
        val frame = Runloop.runLoop.frame
        val state = stateFor(documentPath)
        synchronized(state.lock) {
            ensureLoaded(state)
            // The handlers for one new box run concurrently, so its frame, code or even a
            // connection can be recorded a moment before its create. The create goes where the box
            // is first named in this recording (it existed by then), and the moves and first
            // values of it that follow fold into it. A pending edit of this box stays pending and
            // folds once flushed.
            if (state.pendingEdit?.box != box.id) flushPendingEdit(state)
            val payload = linkedMapOf<String, Any?>("box" to box.id, "name" to box.name)
            val rect = runCatching { box.frame }.getOrNull()
            if (rect != null && !(rect.w == 0.0 && rect.h == 0.0)) payload["frame"] = rectMap(rect)
            val createEntry = linkedMapOf<String, Any?>("create" to payload)
            val since = state.entries.indexOfLast { it.containsKey("marker") } + 1
            val firstMention = (since until state.entries.size).firstOrNull { entryNames(state.entries[it], box.id) }
            if (firstMention == null) {
                appendWaitIfNeeded(state, frame)
                state.entries += createEntry
            } else {
                state.entries.add(firstMention, createEntry)
                var index = firstMention + 1
                while (index < state.entries.size) {
                    val entry = state.entries[index]
                    val verb = entry.keys.firstOrNull()?.lowercase().orEmpty()
                    if (verb in launchVerbs || verb == "execute" || verb == "execute-fragment") break
                    if (!entryNames(entry, box.id)) { index++; continue }
                    val move = entry["move"] as? Map<*, *>
                    val edit = entry["edit"] as? Map<*, *>
                    when {
                        move != null -> { payload["frame"] = move["to"]; state.entries.removeAt(index) }
                        edit != null && edit["origin"] == null &&
                            (edit["from"]?.toString().isNullOrEmpty() || edit["property"] == "name") -> {
                            val property = edit["property"]?.toString().orEmpty()
                            if (property == "name") payload["name"] = edit["to"]
                            else {
                                @Suppress("UNCHECKED_CAST")
                                val properties = (payload["properties"] as? MutableMap<String, Any?>)
                                    ?: linkedMapOf<String, Any?>().also { payload["properties"] = it }
                                properties[property] = edit["to"]
                            }
                            state.entries.removeAt(index)
                        }
                        else -> index++
                    }
                }
                // The box's current name and frame are the latest of all of these.
                payload["name"] = box.name
                if (rect != null && !(rect.w == 0.0 && rect.h == 0.0)) payload["frame"] = rectMap(rect)
            }
            state.lastRecordedFrame = frame
            state.dirty = true
        }
    }

    /** Whether [entry] names box [uid] (as its subject, or as either end of a connection). */
    private fun entryNames(entry: Map<String, Any?>, uid: String): Boolean {
        val payload = entry.values.firstOrNull() as? Map<*, *> ?: return false
        return listOf("box", "uid", "id", "target", "parent", "child").any { payload[it]?.toString() == uid }
    }

    /** A box the person deleted (the `-delete-box` action). Replays as a tombstone; undoes as a restore. */
    fun recordDelete(box: Box) {
        if (box.id == "root" || box.id == METAROOT_ID) return
        recordExecutionEvent(box, "delete", linkedMapOf("box" to box.id, "name" to box.name))
    }

    /** A connection the person made or removed (the `-connect` / `-disconnect` actions, the `g` key). */
    fun recordConnect(parent: Box, child: Box, connected: Boolean) {
        if (child.id == "root" || child.id == METAROOT_ID) return
        recordExecutionEvent(
            child,
            if (connected) "connect" else "disconnect",
            linkedMapOf("parent" to parent.id, "child" to child.id)
        )
    }

    /**
     * Fold a value into the `create` entry of [box]: `frame` sets the create's frame, `name` its
     * name, anything else lands in its `properties`. True when folded. The search walks back over
     * waits and over entries that do not name [box] (another box made in the same burst — the
     * canvas makes boxes faster than their messages arrive), and stops at the first entry that
     * names it (connections aside), which must be its create, or at any execute / launch, whose code might read the
     * box as it was. So an edit made after anything else happened to the box stays an edit.
     */
    private fun foldIntoPreviousCreate(
        entries: MutableList<LinkedHashMap<String, Any?>>,
        box: String,
        property: String,
        value: Any?
    ): Boolean {
        var index = entries.lastIndex
        while (index >= 0) {
            val entry = entries[index]
            val verb = entry.keys.firstOrNull()?.lowercase().orEmpty()
            if (verb == "wait") { index--; continue }
            if (verb in launchVerbs || verb == "execute" || verb == "execute-fragment" || verb == "marker") return false
            val payload = entry.values.firstOrNull() as? Map<*, *> ?: return false
            val names = listOf("box", "uid", "id", "target", "parent", "child").mapNotNull { payload[it]?.toString() }
            // A connection does not read a box's name, frame or code, so a value can fold past it.
            if (box !in names || verb == "connect" || verb == "disconnect") { index--; continue }
            if (verb != "create") return false
            break
        }
        if (index < 0) return false
        @Suppress("UNCHECKED_CAST")
        val payload = entries[index]["create"] as? MutableMap<String, Any?> ?: return false
        when (property) {
            "frame", "name" -> payload[property] = value
            else -> {
                @Suppress("UNCHECKED_CAST")
                val properties = (payload["properties"] as? MutableMap<String, Any?>)
                    ?: linkedMapOf<String, Any?>().also { payload["properties"] = it }
                properties[property] = value
            }
        }
        removeTrailingWaitsAfter(entries, index)
        return true
    }

    fun checkpoint(documentPath: String): Boolean {
        val state = states[normalizeDocumentPath(documentPath)] ?: return false
        val snapshot = synchronized(state.lock) {
            ensureLoaded(state)
            flushPendingEdit(state)
            if (!state.dirty) return false
            state.entries.map { LinkedHashMap(it) }
        }
        if (ReadOnlyDocuments.blocksWritesTo(normalizeDocumentPath(documentPath))) {
            ReadOnlyDocuments.refuseWrite("OperationTranscript.checkpoint", operationTranscriptFile(documentPath).absolutePath)
            return false
        }
        return try {
            val file = operationTranscriptFile(documentPath)
            file.parentFile?.mkdirs()
            val text = writeYamlList(snapshot)
            val wrote = file.safeSave(text, createBackup = true, maxBackups = 10, atomic = true)
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
        if (pending.origin.isBlank() && (pending.from.isEmpty() || pending.property == "name") &&
            foldIntoPreviousCreate(state.entries, pending.box, pending.property, pending.to)
        ) {
            state.lastRecordedFrame = pending.lastFrame
            state.dirty = true
            return
        }
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
        state.entries += editEntry(pending.box, pending.property, pending.from, pending.to, pending.origin)
        state.lastRecordedFrame = pending.lastFrame
        val appendLog =
            "append #$index edit ${pending.box}/${pending.property} from=${pending.from.length} chars to=${pending.to.length} chars frame=${pending.lastFrame}"
        println("[operation-transcript] $appendLog")
        lprintln("info,operation-transcript", appendLog)
    }

    private fun editEntry(box: String, property: String, from: String, to: String, origin: String = ""): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "edit" to linkedMapOf<String, Any?>(
                "box" to box,
                "property" to property,
                "from" to from,
                "to" to to
            ).also { if (origin.isNotBlank()) it["origin"] = origin }
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
        // Never across a turn boundary, and never across hands: a person's edit and an AI turn's edit of the
        // same property stay two entries, so undoing one (a rewound turn) leaves the other alone.
        if (editIndex < state.mergeBarrier) return false
        @Suppress("UNCHECKED_CAST")
        val payload = state.entries[editIndex]["edit"] as? MutableMap<String, Any?> ?: return false
        if ((payload["origin"]?.toString() ?: "") != pending.origin) return false
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

    /**
     * A recording's waits, less the ones that are only the person's pauses. A wait right after an
     * execute or launch is kept — what the code started may need the time — and every other wait
     * goes, so a macro replays at once instead of at the speed it was performed (the 2026-10-20
     * audit recording opened with 784 frames, thirteen seconds, of nothing).
     */
    internal fun withoutIdleWaits(entries: List<LinkedHashMap<String, Any?>>): List<LinkedHashMap<String, Any?>> =
        entries.filterIndexed { index, entry ->
            if (!entry.containsKey("wait")) return@filterIndexed true
            val previous = entries.getOrNull(index - 1)?.keys?.firstOrNull()?.lowercase()
            val next = entries.getOrNull(index + 1)
            next != null && previous != null && (previous in launchVerbs || previous in setOf("execute", "execute-fragment"))
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
        // A connection's two ends travel as from/to, so the panel can name both boxes.
        val from = (payload["from"] ?: payload["parent"])?.toString().orEmpty()
        val to = (payload["to"] ?: payload["child"])?.toString().orEmpty()
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
            "drive", "drive-ui", "playwright" -> {
                val out = (payload["video"] ?: payload["screenshot"] ?: "").orEmptyString()
                if (out.isBlank()) "drive UI" else "drive UI -> $out"
            }
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
            ?.filterNot { isInternalMacro(it) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()

    private val internalMacroMarker = Regex("""(?m)^internal:\s*true\s*$|^\s*\{.*"internal"\s*:\s*true""")

    /**
     * A macro with a top-level `internal: true` (in YAML, or `"internal":true` in a one-line JSON
     * transcript) is plumbing, not something a person saved: the docs runner's capture drives and
     * test fixtures. It still runs by name; it is left out of every macro list (the Macro Recorder
     * panel, the palette's Run macro entries, `macro list`).
     */
    internal fun isInternalMacro(file: File): Boolean =
        runCatching {
            file.bufferedReader().use { reader ->
                val head = CharArray(4096)
                val n = reader.read(head)
                n > 0 && internalMacroMarker.containsMatchIn(String(head, 0, n))
            }
        }.getOrDefault(false)

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

    /**
     * The box a macro "is about", which a run on a selection maps to the selected box. A box the
     * macro creates itself is never that box: mapping a `create` onto the selection would rename
     * and move the selected box instead of making a new one.
     */
    private fun firstOperationBox(operations: List<Map<String, Any?>>): String? {
        val created = createdBoxes(operations)
        return operations.asSequence()
            .filter { operationVerb(it) !in createVerbs }
            .mapNotNull { op ->
                val payload = op.values.firstOrNull() as? Map<*, *> ?: return@mapNotNull null
                (payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"])?.toString()
            }
            .firstOrNull { it !in created }
    }

    private val createVerbs = setOf("create", "new-box", "box")

    /** The uids a macro's own `create` operations make. */
    internal fun createdBoxes(operations: List<Map<String, Any?>>): Set<String> =
        operations.asSequence()
            .filter { operationVerb(it) in createVerbs }
            .mapNotNull { op ->
                val payload = op.values.firstOrNull() as? Map<*, *> ?: return@mapNotNull null
                (payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"])?.toString()
            }
            .toSet()

    /**
     * The operations of a macro rewritten for a replay "as a copy": every box the macro creates
     * gets a fresh uid (consistently, wherever the macro names it), and every frame those boxes
     * are given — at creation or by a later move — is shifted by ([dx], [dy]). Boxes the macro
     * did not create keep their uids and frames. Replaying the result twice makes two copies side
     * by side instead of re-editing one; replaying it with `fresh = false` and no offset is the
     * macro as recorded.
     */
    internal fun relocateOperations(
        operations: List<Map<String, Any?>>,
        fresh: Boolean,
        dx: Double,
        dy: Double,
        newUid: () -> String = { UUID.randomUUID().toString() },
    ): List<LinkedHashMap<String, Any?>> {
        val created = createdBoxes(operations)
        val renames = if (fresh) created.associateWith { newUid() } else emptyMap()
        fun shifted(rect: Any?): Any? {
            val map = rect as? Map<*, *> ?: return rect
            if (dx == 0.0 && dy == 0.0) return deepLinkedMap(map)
            return deepLinkedMap(map).also {
                it["x"] = (it["x"].asDoubleOrNull() ?: 0.0) + dx
                it["y"] = (it["y"].asDoubleOrNull() ?: 0.0) + dy
            }
        }
        val boxKeys = setOf("box", "uid", "id", "target", "parent", "child", "from", "to", "source")
        return operations.map { op ->
            val verb = op.keys.firstOrNull() ?: return@map deepLinkedMap(op)
            val payload = deepLinkedMap(op.values.firstOrNull())
            val subject = (payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"])?.toString()
            val lowered = verb.lowercase()
            if (lowered in createVerbs) {
                payload["frame"]?.let { payload["frame"] = shifted(it) }
                payload["rect"]?.let { payload["rect"] = shifted(it) }
            } else if (lowered in setOf("move", "frame", "set-rect") && subject in created) {
                listOf("from", "to", "frame", "value").forEach { key -> payload[key]?.let { payload[key] = shifted(it) } }
            }
            val keys = if (lowered == "connect" || lowered == "disconnect") boxKeys else boxKeys - setOf("from", "to")
            keys.forEach { key ->
                val value = payload[key]
                if (value is String) renames[value]?.let { payload[key] = it }
            }
            linkedMapOf<String, Any?>(verb to payload)
        }
    }

    private val launchVerbs = setOf("launch", "launch-all", "launch-fragment")

    private fun operationVerb(operation: Map<String, Any?>): String =
        operation.keys.firstOrNull()?.lowercase().orEmpty()

    /** The box an operation names (a `connect`'s child), for its latency span; null for `wait`, `await`, `save`. */
    private fun operationBoxName(operation: Map<String, Any?>): String? {
        val payload = operation.values.firstOrNull() as? Map<*, *> ?: return null
        return (payload["box"] ?: payload["uid"] ?: payload["id"] ?: payload["target"] ?: payload["child"])?.toString()
    }

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
                val target = box(
                    mapped,
                    createIfMissing = true,
                    initIfCreated = context.initializeCreatedBoxes,
                    root = info,
                )
                if (direction == "undo") {
                    tombstoneBox(target)
                    return true
                }
                (payload["name"] ?: payload["title"])?.toString()?.let { target.name = it }
                target.setPropertyRaw("_tombstone", false, doResync = true)
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
                if (action == "connect") {
                    parent.connect(child)
                    if (payload["nested"] == true) child.nestedIn = parent
                } else {
                    if (payload["nested"] == true && child.nestedIn === parent) child.nestedIn = null
                    parent.disconnect(child)
                }
                true
            }
            "execute", "execute-fragment" -> {
                if (direction == "undo") return false
                refreshMainLanguageRuntimes(thisSessionInfo.get().boxes.values)
                val target = mappedBox() ?: return false
                val exec = executeFromPayload(verb, target.id, payload, fallbackType = "fragment")
                // Await completion (like the launch path) so the fragment's output is
                // captured into the run result — fire-and-forget stalls headlessly.
                checkedMacroExecution(target, exec, collect) { reply ->
                    runBlocking { target.ke.executeFragmentAndWait(target, exec, reply) }
                }
                true
            }
            // Drive the attached Playwright page with an arbitrary Kotlin script (the
            // script gets a `ui: DocsUiDriver` in scope via fromContext), optionally
            // recording an .mp4 of the interaction and/or a final still. This is how docs
            // pages regenerate live UI captures (autocomplete, palettes, ...).
            "drive", "drive-ui", "playwright" -> {
                if (direction == "undo") return false
                val script = (payload["script"] ?: payload["code"])?.toString()
                    ?.takeIf { it.isNotBlank() } ?: return false
                val info = thisSessionInfo.get()

                // Attach the headless capture page (no-op if already healthy).
                runBlocking(playwrightDispatcher) { ensurePlaywrightForSessionIfNeeded(info) }
                val page = globalPlaywriteContext
                    ?: throw IllegalStateException("drive: Playwright is not active")

                // Host the script in a stable hidden box (reused across drives), unless
                // the op explicitly targets one. Publish the driver so `fromContext` finds it.
                val target = mappedBox()
                    ?: box("__docs_drive__", createIfMissing = true, initIfCreated = true, root = info)
                val driver = DocsUiDriver { globalPlaywriteContext ?: error("Playwright page unavailable") }
                driver.session = info
                target.properties["__docsUiDriver"] = driver

                // Completion/autocomplete captures trigger a real Ctrl+Space against this freshly
                // created box. The completion runs on the main runloop, but the driver fragment
                // below also runs there and pumps the runloop while it waits — so the K1 completion
                // delegate MUST already be built by the time the scripted completion fires. If its
                // heavy first bootstrap fired from inside the pumped wait it would re-enter the K1
                // engine mid-compile and corrupt it. Warm it here first, on the non-reentrant
                // runloop, so the scripted completion is a light, safe, reused compile. Gated: only
                // completion captures pay the warm-up cost.
                if (payload["warmCompletion"].asBooleanOrDefault(false)) {
                    val warmMs = measureTimeMillis {
                        runCatching {
                            runBlocking { inMain { target.ki.warmCompletion() }.join() }
                        }.onFailure {
                            lprintln("warning,docs", "drive warmCompletion failed: ${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                    lprintln("info,docs", "drive warmCompletion for box=${target.id} took ${warmMs}ms")
                    // ...and then the backend that will actually SERVE it. Warming K1 alone is how every
                    // completion figure in the docs came to be a picture of the fallback: the AA sidecar
                    // answers "session building" for the first seconds of its life, `complete` falls
                    // through to K1 exactly as designed, and the capture records that. Off the runloop —
                    // this waits on HTTP, and the runloop is what the driver pumps.
                    val backendMs = measureTimeMillis {
                        runCatching {
                            runBlocking { target.ki.warmCompletionBackend() }
                        }.onSuccess {
                            lprintln("info,docs", "drive warmCompletionBackend for box=${target.id}: $it")
                        }.onFailure {
                            lprintln(
                                "warning,docs",
                                "drive warmCompletionBackend failed: ${it.javaClass.simpleName}: ${it.message}"
                            )
                        }
                    }
                    lprintln("info,docs", "drive warmCompletionBackend for box=${target.id} took ${backendMs}ms")
                }

                val videoPath = payload["video"]?.toString()?.takeIf { it.isNotBlank() }
                val screenshotPath = payload["screenshot"]?.toString()?.takeIf { it.isNotBlank() }
                val fps = payload["fps"].asIntOrDefault(30)
                val capture = (payload["capture"] ?: "window-editor").toString().ifBlank { "window-editor" }
                val padding = payload["padding"]?.asDoubleOrNull() ?: 16.0
                val captureUid = (payload["captureUid"] ?: payload["captureBox"])?.toString()?.takeIf { it.isNotBlank() }
                    ?: if (capture == "box" || capture == "editor" || capture == "window-editor") target.id else null
                // Clip to an arbitrary overlay (command palette, popup, AI panel) that is not a box.
                val clipSelector = payload["clipSelector"]?.toString()?.takeIf { it.isNotBlank() }
                // Clip to a fixed CSS-px rectangle of the viewport (a fence's clip="x,y,w,h"), for a
                // frame no single element owns: a box and its editor, or a scene under the drawing
                // layer's toolbar. Used for the recording and, with no clipSelector, for the still,
                // which is then taken as the page stands rather than after a screenshot re-prep.
                val clipRect = (payload["clipRect"] as? List<*>)
                    ?.mapNotNull { (it as? Number)?.toDouble() ?: it?.toString()?.toDoubleOrNull() }
                    ?.takeIf { it.size == 4 && it[2] > 0.0 && it[3] > 0.0 }
                    ?.let { Rect(it[0], it[1], it[2], it[3]) }

                // Prepend the driver binding + resolved output paths (fully-qualified, so no
                // import is required in the doc fence body). Scripts capture transient overlays
                // with `ui.shotClip(out, ".selector")`. The extra prelude lines shift error
                // line numbers, but keep them off the author's first line.
                fun kstr(s: String?) = "\"" + (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                val prelude = "val ui: afield.tools.docs.DocsUiDriver = afield.document.context.fromContext(); " +
                    "val out: String = ${kstr(screenshotPath)}; val vid: String = ${kstr(videoPath)}; ui.scriptStarted()\n"
                // For an overlay still (clipSelector) capture inside the fragment, while the
                // overlay is still open — it closes on blur before the op's finally runs.
                val trailer = if (clipSelector != null && screenshotPath != null)
                    "\nui.shotClip(out, ${kstr(clipSelector)}, $padding)" else ""
                val exec = Execute(
                    uid = target.id,
                    type = "fragment",
                    line = 0,
                    code = prelude + script + trailer,
                    singleLine = false,
                    property = "code",
                    strand = "default"
                )

                // viewport=[w, h]: lay the page out at this size for the drive (Field's layout follows
                // the window), restored in the finally below, after the still is taken.
                val requestedViewport = (payload["viewport"] as? List<*>)
                    ?.mapNotNull { (it as? Number)?.toInt() ?: it?.toString()?.toDoubleOrNull()?.toInt() }
                    ?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }
                val originalViewport = if (requestedViewport != null) {
                    runBlocking(playwrightDispatcher) {
                        // A page with no fixed viewport is restored to the capture page's own size.
                        val before = page.viewportSize()
                            ?: com.microsoft.playwright.options.ViewportSize(PLAYWRIGHT_VIEWPORT_WIDTH, PLAYWRIGHT_VIEWPORT_HEIGHT)
                        page.setViewportSize(requestedViewport[0], requestedViewport[1])
                        before
                    }.also {
                        // Let the frontend's resize handlers lay the canvas and editor out again.
                        val until = System.currentTimeMillis() + 800L
                        while (System.currentTimeMillis() < until) {
                            if (Runloop.isMainThread) Runloop.runLoop.main.frame()
                            Thread.sleep(5L)
                        }
                    }
                } else null

                // hideToasts: Field's toasts (notify.js) stay on screen once shown, and in the small
                // window a `viewport=` drive uses they stack over the canvas the recording is of,
                // covering the keypress display's caption among other things. Hidden by a style
                // added before the first frame and removed in the finally below; nothing is dismissed.
                val hideToasts = payload["hideToasts"].asBooleanOrDefault(false)
                if (hideToasts) {
                    runCatching {
                        runBlocking(playwrightDispatcher) {
                            page.evaluate(
                                "() => { if (document.getElementById('docs-capture-hide-toasts')) return;" +
                                    " const s = document.createElement('style'); s.id = 'docs-capture-hide-toasts';" +
                                    " s.textContent = '.bubble.field-toast { visibility: hidden !important; }';" +
                                    " document.head.appendChild(s); }"
                            )
                        }
                    }.onFailure { throw IllegalStateException("drive: could not hide the toasts: ${it.message}", it) }
                }

                val recorder = if (videoPath != null) {
                    val clip = runCatching {
                        runBlocking(playwrightDispatcher) {
                            if (clipRect != null) clipRect
                            else if (clipSelector != null) computeSelectorClip(page, clipSelector, padding)
                                ?: computeDriveClip(page, capture, captureUid, padding)
                            else computeDriveClip(page, capture, captureUid, padding)
                        }
                    }.getOrNull()
                    DocsUiRecorder(page, clip, fps).also { driver.recorder = it; it.start() }
                } else null

                try {
                    checkedMacroExecution(target, exec, collect) { reply ->
                        runBlocking { target.ke.executeFragmentAndWait(target, exec, reply) }
                    }
                    check(driver.clipFailures.isEmpty()) {
                        "drive: clip selector(s) matched nothing visible, so no clipped still was written: " +
                            driver.clipFailures.joinToString(", ")
                    }
                } finally {
                    // A script that filmed its own video (ui.filmCanvasFlight) has written videoPath already.
                    if (recorder != null && videoPath != null && !recorder.discarded) {
                        val frames = runBlocking { recorder.stopAndEncode(java.nio.file.Path.of(videoPath)) }
                        lprintln("info,docs", "drive recorded $frames frames -> $videoPath")
                    }
                    // A clipSelector still is captured by the script itself (ui.shotClip(out, sel))
                    // while the overlay is open — overlays close on blur before this finally runs.
                    if (screenshotPath != null && clipSelector == null) {
                        runBlocking(playwrightDispatcher) {
                            when {
                                clipRect != null -> captureClippedScreenshot(page, java.nio.file.Path.of(screenshotPath), clipRect)
                                capture == "box" -> captureBoxScreenshot(
                                    info,
                                    AgentBoxScreenshot(uid = captureUid ?: target.id, path = screenshotPath, padding = padding)
                                )
                                else -> captureWindowScreenshot(
                                    info,
                                    AgentWindowScreenshot(
                                        path = screenshotPath,
                                        uid = captureUid,
                                        showEditor = capture == "editor" || capture == "window-editor",
                                        editorOnly = capture == "editor",
                                        padding = padding
                                    )
                                )
                            }
                        }
                    }
                    if (hideToasts || driver.keypressDisplayTouched) {
                        runCatching {
                            runBlocking(playwrightDispatcher) {
                                page.evaluate(
                                    "() => { document.getElementById('docs-capture-hide-toasts')?.remove();" +
                                        " window.fieldKeycast?.disable({ quiet: true }); }"
                                )
                            }
                        }.onFailure {
                            lprintln("warning,docs", "drive: could not restore the toasts or keypress display: ${it.message}")
                        }
                    }
                    if (originalViewport != null) {
                        runCatching {
                            runBlocking(playwrightDispatcher) {
                                page.setViewportSize(originalViewport.width, originalViewport.height)
                            }
                        }.onFailure {
                            lprintln("warning,docs", "drive: could not restore the viewport: ${it.message}")
                        }
                    }
                    target.properties.remove("__docsUiDriver")
                }
                true
            }
            "launch", "launch-all", "launch-fragment" -> {
                if (direction == "undo") {
                    val target = mappedBox() ?: return false
                    Server.stop(target, "transcript-undo-launch")
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
                Server.stop(target, "transcript-stop")
                true
            }
            "wait" -> {
                if (direction == "undo") return false
                val explicitMillis = payload["millis"]?.asLongOrDefault(-1L)
                    ?: payload["ms"]?.asLongOrDefault(-1L)
                    ?: payload["seconds"]?.asDoubleOrNull()?.let { (it * 1000.0).toLong() }
                val frames = payload["frames"]?.asLongOrDefault(-1L)
                if (explicitMillis != null && explicitMillis >= 0L) {
                    waitForMacroMillis(explicitMillis.coerceIn(0L, 30_000L))
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
                check(latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    "`$verb` timed out after 30 seconds waiting for SavingContext to complete `$path`"
                }
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
        refreshMainLanguageRuntimes(thisSessionInfo.get().boxes.values)

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

        // Reject unresolved prerequisites before launching anything in the group.
        if (skipped > 0) return 0 to operations.size
        var applied = 0
        for (launch in launches) {
            val result = runCatching {
                send(Message("clear-editor-outputs").payload(ClearEditorOutputs(launch.target.id)))
                notifyMacroLaunch(launch.target, launch.exec, launch.context)
                checkedMacroExecution(launch.target, launch.exec, collect) { reply ->
                    if (launch.target.ke is KotlinExecution) {
                        runBlocking { KotlinExecution.launchBatchAndWait(listOf(launch.target to launch.exec), reply) }
                    } else {
                        // Preserve the ordinary engine's launch path. The result below marks
                        // dispatch separately; only Kotlin currently promises awaited setup.
                        launch.target.ke.launchAll(launch.target, launch.exec, launch.exec.code, launch.exec.strand, reply)
                    }
                }
            }
            if (result.isFailure) {
                val failure = result.exceptionOrNull()!!
                if (diagnostics == null) throw failure
                diagnostics.add(failure.message ?: failure.javaClass.name)
                return applied to operations.size - applied
            }
            applied++
        }
        return applied to skipped
    }

    private fun checkedMacroExecution(
        target: Box,
        exec: Execute,
        collect: (Message) -> Unit,
        execute: ((Message) -> Unit) -> Unit
    ) {
        val verdict = ExecutionVerdict(target.id, exec.property, exec.code)
        execute { message ->
            verdict.observe(message)
            send(message)
            collect(message)
        }
        verdict.check()
        if (target.ke !is KotlinExecution) {
            collect(Message("execution-dispatched").payload(ExecutionReport(
                target.id, exec.property, sourceRevision(exec.code), mode = "macro",
                stage = "dispatched", success = true
            )))
        }
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
        val exec = executeFromPayload(verb, target.id, payload, fallbackType = payload["type"]?.toString() ?: "launch")
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

    private fun describeMacroFailure(failure: Throwable): String {
        val detail = failure.message?.takeIf { it.isNotBlank() }
        if (detail != null) return "${failure.javaClass.simpleName}: $detail"
        // A message-less throwable — ConcurrentModificationException, a bare NPE, the
        // InterruptedException waitForMacroMillis works around — used to reduce to nothing but its
        // class name, so a transcript replay failure reached CI with no way to locate it. Those are
        // exactly the concurrency faults that need a frame, and they are the ones with no message.
        return failure.javaClass.name + describeOrigin(failure)
    }

    /** First few non-JDK frames of [failure], for throwables that carry no message. */
    private fun describeOrigin(failure: Throwable, frames: Int = 4): String {
        val trace = failure.stackTrace ?: return ""
        val interesting = trace.filterNot {
            it.className.startsWith("java.") || it.className.startsWith("kotlin.")
        }.take(frames)
        val shown = if (interesting.isNotEmpty()) interesting else trace.take(frames).toList()
        if (shown.isEmpty()) return ""
        return shown.joinToString(prefix = " at ", separator = " <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
    }

    private fun waitForMacroMillis(millis: Long) {
        if (millis <= 0L) return
        // Macro playback runs on Dispatchers.IO. Coroutine delay avoids inheriting
        // a transient interrupt from compilation/launch work on a reused worker,
        // which made otherwise successful waits fail with a message-less
        // InterruptedException.
        runBlocking { delay(millis) }
    }

    private fun waitForRunloopFrames(frames: Long) {
        if (frames <= 0L) return
        val target = Runloop.runLoop.frame + frames
        runBlocking {
            withTimeout(30_000L) {
                while (Runloop.runLoop.frame < target) delay(1L)
            }
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
                html = html.take(8192)
            )
        )
    }

    private fun executeFromPayload(verb: String, uid: String, payload: Map<*, *>, fallbackType: String): Execute {
        val property = (payload["property"] ?: payload["prop"] ?: "code").toString()
        // A macro replays against the box as it is now: whole-box launches run the box's current
        // code, recorded fragments are found again in it (MacroReplayCode has the rules).
        val live = runCatching { box(uid, createIfMissing = false).getPropertyRaw(property) as? String }.getOrNull()
        val resolved = MacroReplayCode.resolve(verb, payload, live)
        resolved.note?.let { lprintln("info,operation-transcript", "macro `$verb` of `$uid`: $it") }
        return Execute(
            uid = uid,
            type = (payload["type"] ?: fallbackType).toString(),
            line = resolved.line,
            code = resolved.code,
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
