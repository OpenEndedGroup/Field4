package afield

import afield.logger.SaveReloadLog
import afield.logger.lprintln
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
 * [SavingContext] calls [markInternalSave] after its own writes. Matching watch events are ignored
 * while their size and modification-time signature remains valid, as are events for properties
 * that the session still considers pending local saves. Deleting a sidecar externally only clears
 * the watcher's bookkeeping: it deliberately does not erase the in-memory property. Deleting
 * `metadata.edn` is ignored.
 *
 * An external `metadata.edn` modification is reconciled rather than loaded as a replacement
 * session. New active boxes are added, boxes absent from the file are tombstoned, and topology
 * references to tombstoned boxes are removed. Existing boxes are not otherwise overwritten by the
 * reconciliation pass.
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
    private data class InternalSave(val lastModified: Long, val size: Long, val wallClockMs: Long)
    internal data class SidecarPath(val boxId: String, val propertyName: String)

    private val internalSaveInfo = ConcurrentHashMap<String, InternalSave>()
    // Grace window to treat shortly-after writes as internal saves, avoiding misfired "external" reloads
    private val internalSaveGraceMs = 15000L
    // Window to honor internal save signatures before expiring them
    private val internalSaveWindowMs = 120000L
    private var watchJob: Job? = null
    private var isRunning = false
    private var orphanCheckJob: Job? = null
    
    // Track metadata.edn timestamp to detect external changes
    private var metadataTimestamp: Long = 0
    
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
        
        // Initialize metadata.edn timestamp
        val metadataFile = File(workspaceDir, SavingContext.METADATA_EDN)
        if (metadataFile.exists()) {
            metadataTimestamp = metadataFile.lastModified()
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

        try {
            watchService.close()
        } catch (e: Exception) {
            lprintln("error,file-watch", "Error closing watch service: ${e.message}")
        }

        lprintln("info,file-watch", "FileWatchManager stopped for ${sessionInfo.filename}")
    }

    /**
     * Record the file's modification time and size after a Field save so the resulting watch event
     * is not mistaken for an external edit. Missing files are ignored.
     */
    fun markInternalSave(file: File) {
        if (!file.exists()) {
            return
        }
        val lastModified = file.lastModified()
        // Track the precise mtime we wrote so later events with a newer timestamp
        // are treated as external edits rather than our own save.
        internalSaveInfo[file.absolutePath] = InternalSave(
            lastModified = lastModified,
            size = file.length(),
            wallClockMs = System.currentTimeMillis()
        )
        fileTimestamps[file.absolutePath] = lastModified
        SaveReloadLog.log(
            "internal-save",
            "doc=${sessionInfo.filename} path=${file.absolutePath} mtime=$lastModified size=${file.length()}"
        )
    }

    private fun isInternalSaveEvent(file: File, currentTime: Long): Boolean {
        val info = internalSaveInfo[file.absolutePath] ?: return false
        val now = System.currentTimeMillis()
        if (now - info.wallClockMs > internalSaveWindowMs) {
            internalSaveInfo.remove(file.absolutePath)
            return false
        }
        val sizeMatches = file.length() == info.size
        val timeMatches = currentTime <= info.lastModified + internalSaveGraceMs
        return sizeMatches && timeMatches
    }

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
        if (isInternalSaveEvent(file, currentTime)) {
            fileTimestamps[absolutePath] = currentTime
            SaveReloadLog.log(
                "watch-skip-internal",
                "doc=${sessionInfo.filename} path=$absolutePath mtime=$currentTime size=${file.length()}"
            )
            return
        }

        if (sidecarPath != null && sessionInfo.hasPendingSidecarSave(sidecarPath.boxId, sidecarPath.propertyName)) {
            fileTimestamps[absolutePath] = currentTime
            SaveReloadLog.log(
                "watch-skip-pending-local",
                "doc=${sessionInfo.filename} path=$absolutePath box=${sidecarPath.boxId} property=${sidecarPath.propertyName} mtime=$currentTime size=${file.length()}"
            )
            return
        }

        // Check if file was modified
        if (lastKnownTime == null || currentTime > lastKnownTime) {
            // External change detected
            fileTimestamps[absolutePath] = currentTime
            SaveReloadLog.log(
                "watch-reload",
                "doc=${sessionInfo.filename} path=$absolutePath mtime=$currentTime size=${file.length()}"
            )
            reloadSidecarFile(file)
        }
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
     * Internal-save signatures and non-increasing modification times are ignored.
     */
    private suspend fun handleMetadataChange(file: File) {
        try {
            if (!file.exists()) return
            
            val currentTime = file.lastModified()
            SaveReloadLog.log(
                "watch-metadata-event",
                "doc=${sessionInfo.filename} path=${file.absolutePath} mtime=$currentTime size=${file.length()} lastKnown=$metadataTimestamp"
            )
            
            // Check if this is an internal save
            if (isInternalSaveEvent(file, currentTime)) {
                metadataTimestamp = currentTime
                SaveReloadLog.log(
                    "watch-metadata-skip-internal",
                    "doc=${sessionInfo.filename} path=${file.absolutePath} mtime=$currentTime size=${file.length()}"
                )
                return
            }
            
            // Check if file was actually modified
            if (currentTime <= metadataTimestamp) {
                return
            }
            
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
     * Add active boxes found only on disk and tombstone session boxes absent from disk.
     *
     * This is intentionally not a general merge: properties of boxes already present in the
     * session are left alone. Topology values loaded for new boxes are restricted to active boxes
     * plus the root, and references to tombstoned boxes are removed.
     */
    private fun reconcileMetadata(workspaceDir: File) {
        try {
            val savingContext = SavingContext()
            val transfers = savingContext.readEdnTransfers(workspaceDir.absolutePath)
            
            val metadataBoxIds = transfers.map { it.id }.filterNot(::isReservedBoxId).toSet()
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
            
            // Find boxes in current session but not in metadata (removed externally)
            val removedBoxIds = currentBoxIds - metadataBoxIds
            if (removedBoxIds.isNotEmpty()) {
                lprintln("info,file-watch", "Found ${removedBoxIds.size} boxes removed from metadata.edn: $removedBoxIds")
                SaveReloadLog.log(
                    "metadata-removed-boxes",
                    "doc=${sessionInfo.filename} count=${removedBoxIds.size} ids=[${removedBoxIds.joinToString(",")}]"
                )
                
                // Mark as tombstones instead of deleting
                for (boxId in removedBoxIds) {
                    val box = sessionInfo.boxes[boxId]
                    if (box != null) {
                        box.setPropertyRaw("_tombstone", true, doResync = true)
                        lprintln("info,file-watch", "Marked box as removed: ${box.name} (${box.id})")
                    }
                }
                
                notify("Marked ${removedBoxIds.size} box(es) as removed from external metadata changes", "file-watch")
            }
            
        } catch (e: Exception) {
            lprintln("error,file-watch", "Error reconciling metadata: ${e.message}")
            e.printStackTrace()
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
            // 4. Use a default if empty, otherwise capitalize first letter
            val displayName = if (meaningfulPart.isEmpty()) {
                "Box $boxId"
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
