package afield.document.persistence

import afield.animation.keyframes.Handle
import afield.app.diagnostics.named
import afield.app.server.flag
import afield.data.table.cell
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.nested
import afield.document.graph.nestedIn
import afield.document.workspace.metadata
import afield.runtime.execution.plus
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.frames
import afield.runtime.lifecycle.loop
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.notation.Mark
import afield.ui.notation.string
import afield.ui.notation.with
import afield.ui.output.column
import afield.ui.output.history
import afield.ui.output.update

import afield.app.notifications.notify
import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.audio.synth.*
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.doResync
import afield.document.graph.isTrue
import afield.document.graph.reconcileNestingEdges
import afield.document.graph.retireBoxes
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.map
import afield.ui.output.templates.filter
import afield.app.diagnostics.logging.SaveReloadLog
import afield.app.diagnostics.logging.lprintln
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Reloads an open document's properties when their sidecar files are edited outside Field.
 *
 * A [FileWatchManager] watches one document folder and tells Field's own saves apart from external
 * edits by content hash, so editing a box's `.kt` or `.glsl` sidecar in another editor updates the box
 * in the running session.
 */

/**
 * Keeps one open document synchronized with external edits to its workspace directory.
 *
 * The watcher registers only [SessionInformation.filename] itself (not subdirectories) for create,
 * modify, and delete events. Sidecar names are decoded against [Box.ioSeparateProperties]; an
 * external create or modification reloads the property on Field's main thread and resynchronizes
 * the box. If the referenced box is absent, the watcher creates it, assigns a free grid position,
 * loads the property, and saves the document. Sidecars that already existed when [start] ran are
 * recorded as the baseline and are not treated as newly dropped orphan files.
 *
 * [SavingContext] calls [markMetadataWrite] the moment it has written `metadata.edn`, and
 * [markInternalSave] for the sidecars once the whole save has finished. A watch event is ignored
 * when the file's content still hashes to what Field wrote, whenever it arrives, and so is an event
 * for a property the session still considers a pending local save. Any other content is an external
 * edit, even one that leaves the file the same length moments after a save. Deleting a sidecar externally
 * only clears the watcher's bookkeeping: it deliberately does not erase the in-memory property.
 * Deleting `metadata.edn` is ignored.
 *
 * An external `metadata.edn` modification is reconciled rather than loaded as a replacement
 * session. New active boxes are added, and topology references to tombstoned boxes are removed.
 * A box is tombstoned only if it was in the last `metadata.edn` Field wrote or loaded and is absent
 * from the new file; a box created since then has never been on disk, so no edit of the file can
 * have removed it. A property of a box already in the session is applied when the new file's value
 * differs from the one in that last file, which is what makes it an external change: a value the
 * session changed since its last save, and the file did not, is left alone.
 *
 * The manager owns an IO coroutine scope and a Java [WatchService]. [stop] is terminal for an
 * instance because it cancels that scope and closes the watch service; create a new manager to
 * restart watching. [SessionInformation] owns this lifecycle.
 */
class FileWatchManager(private val sessionInfo: SessionInformation) {

    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()
    private val fileTimestamps = ConcurrentHashMap<String, Long>()
    private val startupSidecarFiles = ConcurrentHashMap.newKeySet<String>()
    private data class InternalSave(val lastModified: Long, val size: Long, val sha256: String)
    internal data class SidecarPath(val boxId: String, val propertyName: String)

    private val internalSaveInfo = ConcurrentHashMap<String, InternalSave>()

    // The text the watcher itself last put into a property on reload, by (box id, property). A reload
    // marks the sidecar pending, so that the next save refreshes the inline copy in metadata.edn, and
    // until that save lands the property looks like a local edit. Without this record a second
    // external edit inside that window was skipped as "pending local" and then overwritten on disk by
    // the save, which wrote the first edit back. See [pendingIsLocalEdit].
    private val watcherLoadedValues = ConcurrentHashMap<Pair<String, String>, String>()
    private var watchJob: Job? = null
    private var isRunning = false
    private var orphanCheckJob: Job? = null
    
    // Track metadata.edn timestamp to detect external changes
    private var metadataTimestamp: Long = 0
    // Hash of the metadata.edn content last handled (read at start, written by Field, or reconciled),
    // so a repeated watch event for unchanged content is ignored without trusting mtime resolution.
    @Volatile private var metadataSeenHash: String? = null

    // Box ids in the last metadata.edn Field wrote or loaded: the only boxes an external edit of the
    // file can have removed. Written by the save thread ([markMetadataWrite]) and by reconciliation
    // on the main thread, so both go through [onDiskLock]; [onDiskGeneration] counts the
    // replacements, which lets a reconciliation tell that a save overtook its read.
    private val onDiskLock = Any()
    private var onDiskBoxIds: Set<String> = emptySet()
    private var onDiskGeneration = 0L
    // The text of that same metadata.edn: the baseline that tells an external property edit from a
    // value the session changed since. Null until Field has written or read the file.
    private var onDiskText: String? = null

    // Dedicated coroutine scope for file watching with proper lifecycle management
    private val watchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private companion object {
        const val ROOT_BOX_ID = "root"
        val TOPOLOGY_KEYS = setOf("children", "parents", "nested", "nestedIn")
    }

    private fun isReservedBoxId(boxId: String): Boolean = boxId == ROOT_BOX_ID

    /**
     * Establish the current sidecars as the baseline, register the workspace, and start the event
     * loop plus the five-second orphan scan. Calling this while already running is a no-op.
     */
    fun start() {
        if (isRunning) return

        isRunning = true
        val workspaceDir = File(sessionInfo.filename).absoluteFile

        // Register the workspace directory
        registerDirectory(workspaceDir.toPath())

        // Initialize file timestamps
        scanAndRecordTimestamps(workspaceDir)
        
        // Initialize metadata.edn timestamp, and the boxes it holds as the on-disk baseline
        val metadataFile = File(workspaceDir, SavingContext.METADATA_EDN)
        if (metadataFile.exists()) {
            metadataTimestamp = metadataFile.lastModified()
            val generation = synchronized(onDiskLock) { onDiskGeneration }
            try {
                val text = metadataFile.readText()
                metadataSeenHash = sha256(text.toByteArray(Charsets.UTF_8))
                val ids = SavingContext().readEdnTransfersFromString(text, recordInto = workspaceDir.absolutePath).map { it.id }
                adoptOnDiskBoxIds(ids, text, generation)
            } catch (e: Exception) {
                // Leaves the baseline empty, which tombstones nothing until Field next writes the file.
                lprintln("warning,file-watch", "Could not read box ids from ${metadataFile.path}: ${e.message}")
            }
        }

        // Start the watch loop
        watchJob = watchScope.launch {
            watchLoop()
        }
        
        // Start periodic orphan check (every 5 seconds)
        orphanCheckJob = watchScope.launch {
            delay(5000.milliseconds) // Initial delay
            while (isRunning) {
                try {
                    checkForOrphanedFiles(workspaceDir)
                } catch (e: Exception) {
                    lprintln("error,file-watch", "Error checking for orphaned files: ${e.message}")
                }
                delay(5000.milliseconds)
            }
        }

        lprintln("info,file-watch", "FileWatchManager started for ${sessionInfo.filename} with self-healing enabled")
    }

    /**
     * Cancel both jobs, discard all baselines and save signatures, and close the watch service.
     * Calling this when not running is a no-op; a stopped instance cannot be started again.
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false
        watchJob?.cancel()
        orphanCheckJob?.cancel()
        watchScope.cancel() // Cancel the entire scope to clean up all coroutines
        watchKeys.keys.forEach { it.cancel() }
        watchKeys.clear()
        fileTimestamps.clear()
        startupSidecarFiles.clear()
        internalSaveInfo.clear()
        watcherLoadedValues.clear()

        try {
            watchService.close()
        } catch (e: Exception) {
            lprintln("error,file-watch", "Error closing watch service: ${e.message}")
        }

        lprintln("info,file-watch", "FileWatchManager stopped for ${sessionInfo.filename}")
    }

    /**
     * Record a content hash of the file after a Field save so the resulting watch event is not
     * mistaken for an external edit. [written] is the text Field wrote, when the caller has it; a file
     * whose size and mtime match the last record keeps that record's hash rather than being re-read.
     * Missing files are ignored.
     */
    fun markInternalSave(file: File, written: String? = null) {
        if (!file.exists()) {
            return
        }
        val lastModified = file.lastModified()
        val size = file.length()
        val previous = internalSaveInfo[file.absolutePath]
        val hash = when {
            written != null -> sha256(written.toByteArray(Charsets.UTF_8))
            previous != null && previous.lastModified == lastModified && previous.size == size -> previous.sha256
            else -> sha256(file.readBytes())
        }
        internalSaveInfo[file.absolutePath] = InternalSave(lastModified = lastModified, size = size, sha256 = hash)
        fileTimestamps[file.absolutePath] = lastModified
        SaveReloadLog.log(
            "internal-save",
            "doc=${sessionInfo.filename} path=${file.absolutePath} mtime=$lastModified size=$size"
        )
    }

    /**
     * Record that Field has just saved `metadata.edn` holding [boxIds]: those become the on-disk
     * baseline, and, if [written] (the save changed the file), its signature is registered as
     * [markInternalSave] does. [SavingContext] calls this straight after writing the file, on the
     * save thread, because the rest of a save (Markdown mirror, history, sidecar bookkeeping) took
     * ~900 ms on a loaded host, and the file's own watch event arrived before a registration made
     * at the end, was taken as external, and tombstoned a box created during the save.
     */
    fun markMetadataWrite(file: File, boxIds: Collection<String>, written: Boolean, text: String? = null) {
        synchronized(onDiskLock) {
            onDiskBoxIds = boxIds.filterNot(::isReservedBoxId).toSet()
            if (text != null) onDiskText = text
            onDiskGeneration++
        }
        if (written) markInternalSave(file, text)
    }

    /** Replace the on-disk baseline with [boxIds], unless a save has replaced it since [readGeneration]. */
    private fun adoptOnDiskBoxIds(boxIds: Collection<String>, text: String, readGeneration: Long): Boolean =
        synchronized(onDiskLock) {
            if (onDiskGeneration != readGeneration) return@synchronized false
            onDiskBoxIds = boxIds.filterNot(::isReservedBoxId).toSet()
            onDiskText = text
            onDiskGeneration++
            true
        }

    /** True when the file still holds exactly what Field last wrote to it. */
    private fun isInternalSaveEvent(file: File): Boolean {
        val info = internalSaveInfo[file.absolutePath] ?: return false
        if (file.length() != info.size) return false
        return runCatching { sha256(file.readBytes()) == info.sha256 }.getOrDefault(false)
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun registerDirectory(path: Path) {
        try {
            val key = path.register(
                watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE
            )
            watchKeys[key] = path
        } catch (e: Exception) {
            lprintln("error,file-watch", "Failed to register directory ${path}: ${e.message}")
        }
    }

    private fun scanAndRecordTimestamps(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile && isSidecarFile(file)) {
                val absolutePath = file.absolutePath
                fileTimestamps[absolutePath] = file.lastModified()
                startupSidecarFiles += absolutePath
            }
        }
    }

    private fun isSidecarFile(file: File): Boolean {
        val name = file.name

        // Check if it matches the pattern: <boxId>_<propertyName>_.<extension>
        if (!name.contains("_")) return false

        val extension = file.extension

        // Check if extension matches known sidecar extensions
        return Box.ioSeparateProperties.values.contains(extension)
    }

    internal fun parseSidecarPath(file: File): SidecarPath? {
        val filename = file.name
        val lastUnderscore = filename.lastIndexOf("_.")
        if (lastUnderscore == -1) return null

        // filenameFor(id, name, ext) == "$id" + "_" + "$name" + "_." + ext, so the text before the
        // trailing "_." is "<boxId>_<propertyName>". Both a box id and a property name may themselves
        // contain underscores (e.g. box "fn_fit_demo", property "remote_js"), so we cannot simply split
        // on the first underscore — that mis-parsed "fn_fit_demo_code" into box "fn" / property
        // "fit_demo_code", spawning a spurious phantom box. Property names come from a fixed vocabulary
        // (Box.ioSeparateProperties keys) narrowed by the file extension, so match a known property as a
        // suffix and take everything before it as the box id.
        val beforeLastUnderscore = filename.substring(0, lastUnderscore)
        if (beforeLastUnderscore.isBlank()) return null

        val extension = file.extension
        val candidateProperties = Box.ioSeparateProperties
            .filterValues { it.equals(extension, ignoreCase = true) }
            .keys
            // Longest property first so e.g. "vertex_fragment" wins over "fragment".
            .sortedByDescending { it.length }

        val suffixMatches = candidateProperties.mapNotNull { property ->
            when {
                // Whole string is the property → no box id, not a real sidecar.
                beforeLastUnderscore == property -> null
                beforeLastUnderscore.endsWith("_$property") ->
                    beforeLastUnderscore.dropLast(property.length + 1)
                        .takeIf { it.isNotBlank() }
                        ?.let { SidecarPath(it, property) }
                else -> null
            }
        }

        // A genuinely ambiguous name (e.g. box "a_vertex" / "fragment" vs box "a" / "vertex_fragment")
        // is resolved by preferring the parse whose box id already exists; otherwise the longest known
        // property suffix wins.
        suffixMatches.firstOrNull { sessionInfo.boxes.containsKey(it.boxId) }?.let { return it }
        suffixMatches.firstOrNull()?.let { return it }

        // Fallback for files that don't match any known property (external drops named loosely):
        // preserve the original first-underscore heuristic.
        val firstUnderscore = beforeLastUnderscore.indexOf("_")
        if (firstUnderscore == -1) return null
        val boxId = beforeLastUnderscore.substring(0, firstUnderscore)
        val propertyName = beforeLastUnderscore.substring(firstUnderscore + 1)
        if (boxId.isBlank() || propertyName.isBlank()) return null
        return SidecarPath(boxId, propertyName)
    }

    private suspend fun watchLoop() {
        while (isRunning && !Thread.currentThread().isInterrupted) {
            try {
                // Poll for events with a timeout
                val key = watchService.poll(1, java.util.concurrent.TimeUnit.SECONDS)

                if (key == null) {
                    continue
                }

                val dir = watchKeys[key]
                if (dir == null) {
                    key.cancel()
                    continue
                }

                for (event in key.pollEvents()) {
                    val kind = event.kind()

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue
                    }

                    @Suppress("UNCHECKED_CAST")
                    val ev = event as WatchEvent<Path>
                    val filename = ev.context()
                    val child = dir.resolve(filename)
                    val file = child.toFile()

                    val isDelete = kind == StandardWatchEventKinds.ENTRY_DELETE

                    // Handle metadata.edn changes (its deletion is ignored)
                    if (file.name == SavingContext.METADATA_EDN) {
                        if (!isDelete) handleMetadataChange(file)
                    } else if (isSidecarFile(file)) {
                        if (isDelete) handleSidecarDeletion(file)
                        else if (file.isFile) handleFileChange(file)
                    }
                }

                // Reset the key
                val valid = key.reset()
                if (!valid) {
                    watchKeys.remove(key)
                }

            } catch (e: InterruptedException) {
                break
            } catch (e: ClosedWatchServiceException) {
                break
            } catch (e: Exception) {
                lprintln("error,file-watch", "Error in watch loop: ${e.message}")
                delay(1000.milliseconds)
            }
        }
    }

    /**
     * External deletion of a sidecar file. We stop tracking it (so a later re-create is treated as a
     * fresh external change) and leave the box property untouched — deleting the sidecar on disk should
     * not silently wipe the in-memory/persisted property.
     */
    private fun handleSidecarDeletion(file: File) {
        val absolutePath = file.absolutePath
        val wasTracked = fileTimestamps.remove(absolutePath) != null
        startupSidecarFiles.remove(absolutePath)
        if (wasTracked) {
            lprintln("file-watch", "External deletion of sidecar ${file.name} observed; stopped tracking it (box property left unchanged).")
        }
    }

    private suspend fun handleFileChange(file: File) {
        val absolutePath = file.absolutePath

        // Small delay to ensure file write is complete
        delay(100.milliseconds)

        if (!file.exists()) return

        val currentTime = file.lastModified()
        val lastKnownTime = fileTimestamps[absolutePath]
        val sidecarPath = parseSidecarPath(file)
        SaveReloadLog.log(
            "watch-event",
            "doc=${sessionInfo.filename} path=$absolutePath mtime=$currentTime size=${file.length()} lastKnown=${lastKnownTime ?: -1}"
        )
        // Treat events matching our last internal write signature as ours to avoid
        // late-firing events from previous saves being treated as external edits.
        if (isInternalSaveEvent(file)) {
            fileTimestamps[absolutePath] = currentTime
            SaveReloadLog.log(
                "watch-skip-internal",
                "doc=${sessionInfo.filename} path=$absolutePath mtime=$currentTime size=${file.length()}"
            )
            return
        }

        if (sidecarPath != null && pendingIsLocalEdit(sidecarPath.boxId, sidecarPath.propertyName)) {
            fileTimestamps[absolutePath] = currentTime
            SaveReloadLog.log(
                "watch-skip-pending-local",
                "doc=${sessionInfo.filename} path=$absolutePath box=${sidecarPath.boxId} property=${sidecarPath.propertyName} mtime=$currentTime size=${file.length()}"
            )
            return
        }

        // Modified: a newer mtime, or (since the hash check above failed) content that differs from what
        // Field wrote, which catches an edit landing within the mtime resolution of Field's own save.
        if (lastKnownTime == null || currentTime > lastKnownTime || internalSaveInfo.containsKey(absolutePath)) {
            // External change detected
            fileTimestamps[absolutePath] = currentTime
            SaveReloadLog.log(
                "watch-reload",
                "doc=${sessionInfo.filename} path=$absolutePath mtime=$currentTime size=${file.length()}"
            )
            reloadSidecarFile(file)
            // The session now holds this content; a duplicate event for it is not another edit.
            markInternalSave(file)
        }
    }

    /**
     * Whether a sidecar event must yield to an unsaved change made in Field. True when the property
     * has a pending sidecar save and its value is not the one this watcher loaded from disk: someone
     * edited it in Field since, and that edit wins until it is saved. A pending save that only exists
     * because of this watcher's own reload does not count, so a second external edit made before
     * that save is reloaded rather than overwritten.
     */
    internal fun pendingIsLocalEdit(boxId: String, propertyName: String): Boolean {
        if (!sessionInfo.hasPendingSidecarSave(boxId, propertyName)) return false
        val loaded = watcherLoadedValues[boxId to propertyName] ?: return true
        return sessionInfo.boxes[boxId]?.getPropertyRaw(propertyName) != loaded
    }

    internal fun recordWatcherLoad(boxId: String, propertyName: String, content: String) {
        watcherLoadedValues[boxId to propertyName] = content
    }

    private suspend fun reloadSidecarFile(file: File) {
        try {
            val filename = file.name
            val sidecarPath = parseSidecarPath(file) ?: return
            val boxId = sidecarPath.boxId
            val propertyName = sidecarPath.propertyName

            // Root is a reserved box and is never sourced from sidecar files.
            if (isReservedBoxId(boxId)) {
                SaveReloadLog.log(
                    "watch-skip-reserved-sidecar",
                    "doc=${sessionInfo.filename} path=${file.absolutePath} box=$boxId property=$propertyName"
                )
                return
            }

            // Find the box - if not found, try to create it
            var box = sessionInfo.boxes[boxId]
            if (box == null) {
                lprintln("info,file-watch", "Box not found for sidecar file: $filename - attempting auto-creation")
                box = autoCreateBoxForSidecarFile(file, boxId, propertyName)
                if (box == null) {
                    lprintln("warning,file-watch", "Could not auto-create box for sidecar file: $filename")
                    return
                }
            }

            // Check if this property should be stored separately
            if (!Box.ioSeparateProperties.containsKey(propertyName)) {
                return
            }

            // Read the file content
            val content = file.readText()

            // Update the box property
            inMain {
                val currentValue = box.getPropertyRaw(propertyName)

                // Only update if content has actually changed
                if (currentValue != content) {
                    box.setPropertyRaw(propertyName, content, doResync = true)
                    recordWatcherLoad(box.id, propertyName, content)
                    // External change to a sidecar-backed property needs persistence
                    if (Box.ioSeparateProperties.containsKey(propertyName) && box.isInsideInitialized()) {
                        box.inside.markSidecarPending(box.id, propertyName)
                    }

                    lprintln("info,file-watch", "Reloaded external change: $propertyName in box ${box.name} (${box.id})")
                    notify("Reloaded external change to $propertyName in ${box.name}", "file-watch")
                    SaveReloadLog.log(
                        "reload-sidecar",
                        "doc=${sessionInfo.filename} path=${file.absolutePath} box=${box.id} property=$propertyName"
                    )
                }
            }

        } catch (e: Exception) {
            lprintln("error,file-watch", "Error reloading sidecar file ${file.name}: ${e.message}")
        }
    }
    
    /**
     * Debounce and reconcile a newer external `metadata.edn` modification on Field's main thread.
     * Field's own writes and content already handled are ignored, judged by content hash.
     */
    internal suspend fun handleMetadataChange(file: File) {
        try {
            if (!file.exists()) return
            
            val currentTime = file.lastModified()
            SaveReloadLog.log(
                "watch-metadata-event",
                "doc=${sessionInfo.filename} path=${file.absolutePath} mtime=$currentTime size=${file.length()} lastKnown=$metadataTimestamp"
            )
            
            // Check if this is an internal save
            if (isInternalSaveEvent(file)) {
                metadataTimestamp = currentTime
                metadataSeenHash = internalSaveInfo[file.absolutePath]?.sha256
                SaveReloadLog.log(
                    "watch-metadata-skip-internal",
                    "doc=${sessionInfo.filename} path=${file.absolutePath} mtime=$currentTime size=${file.length()}"
                )
                return
            }

            // Content, not mtime: an edit inside the filesystem's timestamp resolution is still an edit.
            val hash = sha256(file.readBytes())
            if (hash == metadataSeenHash) {
                return
            }
            metadataSeenHash = hash
            metadataTimestamp = currentTime
            
            lprintln("info,file-watch", "External change detected to metadata.edn")
            SaveReloadLog.log(
                "watch-metadata-reload",
                "doc=${sessionInfo.filename} path=${file.absolutePath} mtime=$currentTime size=${file.length()}"
            )
            
            // Small delay to ensure file write is complete
            delay(200.milliseconds)
            
            // Reload the metadata and reconcile
            inMain {
                try {
                    reconcileMetadata(file.parentFile)
                } catch (e: Exception) {
                    lprintln("error,file-watch", "Error reconciling metadata: ${e.message}")
                    e.printStackTrace()
                }
            }
            
        } catch (e: Exception) {
            lprintln("error,file-watch", "Error handling metadata change: ${e.message}")
        }
    }
    
    /**
     * Add active boxes found only on disk, and tombstone session boxes that the previous on-disk
     * `metadata.edn` held and this one does not.
     *
     * This is intentionally not a general merge: properties of boxes already present in the
     * session are left alone. Topology values loaded for new boxes are restricted to active boxes
     * plus the root, and references to tombstoned boxes are removed.
     *
     * A session box missing from the file is not evidence that anything removed it: it may have
     * been created after the save that wrote the file took its snapshot, and so have never been on
     * disk. That is also what this pass sees when a save's own watch event is taken for an external
     * edit. So removal is judged against the box ids of the last `metadata.edn` Field wrote or
     * loaded. If a save replaces that baseline while the file is being read, the read may predate
     * the save, so no box is tombstoned.
     */
    private fun reconcileMetadata(workspaceDir: File) {
        try {
            val savingContext = SavingContext()
            val (previousOnDiskBoxIds, readGeneration, previousText) =
                synchronized(onDiskLock) { Triple(onDiskBoxIds, onDiskGeneration, onDiskText) }
            val text = File(workspaceDir, SavingContext.METADATA_EDN).readText()
            afield.document.workspace.Kits.declareRecorded(workspaceDir)
            val transfers = savingContext.readEdnTransfersFromString(text, recordInto = workspaceDir.absolutePath)

            val metadataBoxIds = transfers.map { it.id }.filterNot(::isReservedBoxId).toSet()
            val baselineCurrent = adoptOnDiskBoxIds(metadataBoxIds, text, readGeneration)
            val tombstonedIds = transfers.mapNotNull { transfer ->
                val raw = transfer.properties["_tombstone"] ?: return@mapNotNull null
                val resolved = savingContext.resolveStoredValue(raw, workspaceDir.absolutePath)
                if (resolved == true) transfer.id else null
            }.filterNot(::isReservedBoxId).toSet()
            val activeMetadataBoxIds = metadataBoxIds - tombstonedIds
            val allowedRefIds = activeMetadataBoxIds + ROOT_BOX_ID
            val currentBoxIds = sessionInfo.boxes.keys.filterNot(::isReservedBoxId).toSet()
            
            // Find boxes in metadata but not in current session (newly added externally)
            val newBoxIds = activeMetadataBoxIds - currentBoxIds
            if (newBoxIds.isNotEmpty()) {
                lprintln("info,file-watch", "Found ${newBoxIds.size} new boxes in metadata.edn: $newBoxIds")
                SaveReloadLog.log(
                    "metadata-new-boxes",
                    "doc=${sessionInfo.filename} count=${newBoxIds.size} ids=[${newBoxIds.joinToString(",")}]"
                )
                
                // Load these boxes
                val created = mutableListOf<Box>()
                for (transfer in transfers.filter { it.id in newBoxIds }) {
                    val box = box(transfer.id, createIfMissing = true, initIfCreated = true, root = sessionInfo)
                    created += box

                    // Apply properties from metadata
                    for ((name, value) in transfer.properties) {
                        val resolved = if (name in TOPOLOGY_KEYS) {
                            savingContext.resolveStoredValueWithBoxes(
                                value,
                                workspaceDir.absolutePath,
                                allowedRefIds = allowedRefIds,
                                root = sessionInfo
                            )
                        } else {
                            savingContext.resolveStoredValue(value, workspaceDir.absolutePath)
                        }
                        val sanitized = sanitizeTopologyValue(name, resolved, tombstonedIds)
                        if (sanitized != null) {
                            box.setPropertyRaw(name, sanitized, doResync = true)
                        }
                    }
                    
                    lprintln("info,file-watch", "Created box from metadata: ${box.name} (${box.id})")
                }

                // This loop sets forward refs on the new boxes only — [TOPOLOGY_KEYS] carries
                // `nested`/`nestedIn` in, but the inverse edge belongs on a box that was already live
                // in the session and is never revisited here. An externally added nested box is
                // exactly what an agent writing `metadata.edn` produces, so it is worth repairing at
                // the point the box arrives rather than at the next document open. Scoped to the new
                // boxes: their own `nested` and `nestedIn` are the only new information.
                val repaired = reconcileNestingEdges(created)
                if (repaired > 0) {
                    lprintln("info,file-watch", "re-derived $repaired nest edge(s) for externally added boxes")
                }

                notify("Loaded ${newBoxIds.size} new box(es) from external metadata changes", "file-watch")
            }
            
            applyExternalPropertyEdits(
                transfers, previousText, baselineCurrent, currentBoxIds, tombstonedIds, allowedRefIds,
                savingContext, workspaceDir
            )

            // Find boxes that were on disk and are no longer (removed externally). Session boxes
            // that were never on disk are not removals.
            val missingBoxIds = currentBoxIds - metadataBoxIds
            val removedBoxIds = if (baselineCurrent) missingBoxIds intersect previousOnDiskBoxIds else emptySet()
            val keptBoxIds = missingBoxIds - removedBoxIds
            if (keptBoxIds.isNotEmpty()) {
                SaveReloadLog.log(
                    "metadata-kept-missing-boxes",
                    "doc=${sessionInfo.filename} count=${keptBoxIds.size} baselineCurrent=$baselineCurrent ids=[${keptBoxIds.joinToString(",")}]"
                )
            }
            if (removedBoxIds.isNotEmpty()) {
                lprintln("info,file-watch", "Found ${removedBoxIds.size} boxes removed from metadata.edn: $removedBoxIds")
                SaveReloadLog.log(
                    "metadata-removed-boxes",
                    "doc=${sessionInfo.filename} count=${removedBoxIds.size} ids=[${removedBoxIds.joinToString(",")}]"
                )
                
                // Mark as tombstones instead of deleting
                val removed = mutableListOf<Box>()
                for (boxId in removedBoxIds) {
                    val box = sessionInfo.boxes[boxId]
                    if (box != null) {
                        if (!box.getPropertyRaw("_tombstone").isTrue()) removed += box
                        box.setPropertyRaw("_tombstone", true, doResync = true)
                        lprintln("info,file-watch", "Marked box as removed: ${box.name} (${box.id})")
                    }
                }
                // A box removed from metadata.edn stops running and releases its engine, as a deleted
                // one does.
                retireBoxes(removed, "file-watch-removal")
                
                notify("Marked ${removedBoxIds.size} box(es) as removed from external metadata changes", "file-watch")
            }
            
        } catch (e: Exception) {
            lprintln("error,file-watch", "Error reconciling metadata: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Apply to boxes already in the session the properties an external edit changed: a value in the
     * new file that differs from the same box's value in [previousText], the last `metadata.edn` Field
     * wrote or read. Comparing against that file rather than against the live box is what keeps a
     * value the session changed since its last save. Topology and `_tombstone` are left to the
     * add/remove passes; a property deleted from the file is not removed from the box (the next save
     * writes it back), and that is reported. With no usable baseline nothing is applied, and the
     * notice says so, rather than the next save silently overwriting the edit.
     */
    private fun applyExternalPropertyEdits(
        transfers: List<BoxTransferEdn>,
        previousText: String?,
        baselineCurrent: Boolean,
        currentBoxIds: Set<String>,
        tombstonedIds: Set<String>,
        allowedRefIds: Set<String>,
        savingContext: SavingContext,
        workspaceDir: File
    ) {
        val existing = transfers.filter { it.id in currentBoxIds && it.id !in tombstonedIds }
        if (existing.isEmpty()) return
        val baseline = previousText?.takeIf { baselineCurrent }
            ?.let { runCatching { savingContext.readEdnTransfersFromString(it) }.getOrNull() }
            ?.associateBy { it.id }
        if (baseline == null) {
            val why = if (previousText == null) "no earlier metadata.edn to compare with" else "a save overtook the read"
            lprintln("warning,file-watch", "metadata.edn changed externally; property edits to existing boxes not applied ($why)")
            notify("metadata.edn changed on disk, but edits to existing boxes were not applied ($why); the next save will overwrite them", "file-watch")
            return
        }
        val applied = mutableListOf<String>()
        val notRemoved = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        for (transfer in existing) {
            val before = baseline[transfer.id] ?: continue
            val box = sessionInfo.boxes[transfer.id] ?: continue
            for ((name, value) in transfer.properties) {
                if (name in TOPOLOGY_KEYS || name == "_tombstone") continue
                val previous = before.properties[name]
                if (previous == value) continue
                // A sidecar property is stored as `#afield/file-inline {path, inline}`. When the edit
                // is to the inline copy (same path), the inline text is what the author changed;
                // resolving it normally would read the untouched sidecar instead and change nothing.
                val inlineEdit = value is FileInline && previous is FileInline && previous.path == value.path
                val resolved = savingContext.resolveStoredValue(value, workspaceDir.absolutePath, preferInlineFiles = inlineEdit)
                    ?: continue
                if (box.getPropertyRaw(name) == resolved) {
                    unchanged += "${transfer.id}.$name"
                    continue
                }
                box.setPropertyRaw(name, resolved, doResync = true)
                applied += "${transfer.id}.$name"
            }
            for (name in before.properties.keys - transfer.properties.keys) {
                if (name !in TOPOLOGY_KEYS) notRemoved += "${transfer.id}.$name"
            }
        }
        if (applied.isNotEmpty()) {
            SaveReloadLog.log("metadata-applied-properties", "doc=${sessionInfo.filename} count=${applied.size} [${applied.joinToString(",")}]")
            lprintln("info,file-watch", "applied ${applied.size} external property edit(s) from metadata.edn: $applied")
            notify("Applied ${applied.size} property edit(s) from metadata.edn", "file-watch")
        }
        if (unchanged.isNotEmpty()) {
            lprintln("info,file-watch", "metadata.edn edits to $unchanged already match the session; nothing applied")
        }
        if (notRemoved.isNotEmpty()) {
            lprintln("warning,file-watch", "metadata.edn no longer lists $notRemoved; properties are kept and the next save writes them back")
        }
    }

    private fun sanitizeTopologyValue(
        name: String,
        value: Any?,
        tombstonedIds: Set<String>
    ): Any? {
        if (name !in TOPOLOGY_KEYS) return value
        return when (value) {
            null -> null
            is Box -> {
                if (value.getPropertyRaw("_tombstone").isTrue() || value.id in tombstonedIds) null else value
            }
            is Collection<*> -> {
                value.mapNotNull { it as? Box }
                    .filter { it.id !in tombstonedIds && !it.getPropertyRaw("_tombstone").isTrue() }
                    .toMutableSet()
            }
            else -> null
        }
    }
    
    /**
     * Find sidecars created or changed after [start] whose decoded box id is absent, and create the
     * corresponding boxes. The root id and unchanged startup sidecars are ignored.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun checkForOrphanedFiles(workspaceDir: File) {
        try {
            val sidecarFiles = workspaceDir.listFiles()?.filter { it.isFile && isSidecarFile(it) } ?: return
            
            for (file in sidecarFiles) {
                val filename = file.name
                val sidecarPath = parseSidecarPath(file) ?: continue
                val boxId = sidecarPath.boxId
                val propertyName = sidecarPath.propertyName

                if (isReservedBoxId(boxId)) continue
                val absolutePath = file.absolutePath
                val startupTimestamp = fileTimestamps[absolutePath]
                if (absolutePath in startupSidecarFiles && startupTimestamp != null && file.lastModified() <= startupTimestamp) {
                    continue
                }
                
                // Check if box exists
                if (!sessionInfo.boxes.containsKey(boxId)) {
                    lprintln("info,file-watch", "Found orphaned sidecar file: $filename")
                    
                    inMain {
                        autoCreateBoxForSidecarFile(file, boxId, propertyName)
                    }
                }
            }
        } catch (e: Exception) {
            lprintln("error,file-watch", "Error checking for orphaned files: ${e.message}")
        }
    }
    
    /**
     * Create and initialize a non-root box for an orphaned sidecar, load its property, mark the
     * property pending, and request a save. Returns `null` if any part of creation fails.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun autoCreateBoxForSidecarFile(file: File, boxId: String, propertyName: String): Box? {
        try {
            if (isReservedBoxId(boxId)) {
                return null
            }

            // Create the box
            val box = box(boxId, createIfMissing = true, initIfCreated = true, root = sessionInfo)
            
            // Generate a sensible name from the filename
            // 1. Start with filename without extension
            val baseName = file.nameWithoutExtension
            // 2. Replace underscores with spaces
            val withSpaces = baseName.replace("_", " ").trim()
            // 3. Extract meaningful part: if it contains box ID, take the part after
            val meaningfulPart = if (withSpaces.contains(boxId, ignoreCase = true)) {
                withSpaces.substringAfter(boxId, "").trim()
            } else {
                withSpaces
            }
            // 4. Use a default if empty, otherwise capitalize first letter. What is left is usually
            // just the property name, which would call every dropped code file "Code"; the box id is
            // the one thing the author chose, so it names the box instead.
            val displayName = if (meaningfulPart.isEmpty()) {
                "Box $boxId"
            } else if (meaningfulPart.equals(propertyName, ignoreCase = true)) {
                boxId
            } else {
                meaningfulPart.replaceFirstChar { it.uppercase() }
            }
            
            box.name = displayName
            
            // Calculate a non-overlapping frame position
            val frame = calculateNewBoxFrame(boxId)
            box.frame = frame
            
            // Read and set the file content
            val content = file.readText()
            box.setPropertyRaw(propertyName, content, doResync = true)
            recordWatcherLoad(box.id, propertyName, content)

            // Mark for persistence
            if (box.isInsideInitialized()) {
                box.inside.markSidecarPending(box.id, propertyName)
                // Also ensure the box itself is saved to metadata
                // Saving below will persist metadata; no extra dirty flag needed.
            }
            
            lprintln("info,file-watch", "Auto-created box: $displayName ($boxId) with frame $frame")
            notify("Auto-created box '$displayName' from external file: ${file.name}", "file-watch")
            
            // Trigger a save to persist the new box
            inMain {
                try {
                    val workspaceDir = File(sessionInfo.filename).absoluteFile
                    val savingContext = SavingContext()
                    savingContext.save(workspaceDir.absolutePath, sessionInfo.boxes.values.toSet())
                } catch (e: Exception) {
                    lprintln("error,file-watch", "Error saving after auto-creating box: ${e.message}")
                }
            }
            
            return box
        } catch (e: Exception) {
            lprintln("error,file-watch", "Error auto-creating box for ${file.name}: ${e.message}")
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Return the first unoccupied cell among 100 cells in a four-column grid of 350x250 boxes,
     * starting at (50, 50) with 20 pixels between cells. If the grid is full, use [boxId] to choose
     * a deterministic non-negative diagonal offset from the origin.
     */
    private fun calculateNewBoxFrame(boxId: String): Rect {
        val startX = 50.0
        val startY = 50.0
        val boxWidth = 350.0
        val boxHeight = 250.0
        val padding = 20.0
        val columns = 4
        
        // Find all existing frames
        val existingFrames = sessionInfo.boxes.values
            .filter { it.id != "root" && !it.properties["_tombstone"].isTrue() }
            .mapNotNull { it.frame }
        
        // Try to find a non-overlapping position in grid
        var row = 0
        var col = 0
        var maxAttempts = 100
        
        while (maxAttempts > 0) {
            val x = startX + col * (boxWidth + padding)
            val y = startY + row * (boxHeight + padding)
            val testFrame = Rect(x, y, boxWidth, boxHeight)
            
            // Check if this frame overlaps with any existing frame
            val overlaps = existingFrames.any { existing ->
                testFrame.intersects(existing)
            }
            
            if (!overlaps) {
                return testFrame
            }
            
            // Move to next position
            col++
            if (col >= columns) {
                col = 0
                row++
            }
            maxAttempts--
        }
        
        // Fallback: use deterministic offset based on box ID hash
        // This ensures boxes created simultaneously get unique positions
        val hashOffset = (boxId.hashCode().toLong() and 0x7FFFFFFF) % 200 // 0-199 pixel offset
        return Rect(startX + hashOffset, startY + hashOffset, boxWidth, boxHeight)
    }
}
