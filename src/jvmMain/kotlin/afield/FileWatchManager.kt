package afield

import afield.logger.lprintln
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * FileWatchManager monitors workspace directories for external file changes
 * and reloads sidecar files into Box properties, syncing to the frontend.
 * 
 * Enhanced with self-healing capabilities:
 * - Detects orphaned sidecar files (files without corresponding boxes)
 * - Auto-creates boxes with sensible defaults (frame, name)
 * - Watches metadata.edn for external changes
 * - Reconciles box hierarchy when metadata is edited externally
 */
class FileWatchManager(private val sessionInfo: SessionInformation) {

    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()
    private val fileTimestamps = ConcurrentHashMap<String, Long>()
    private val internalSaveTimestamps = ConcurrentHashMap<String, Long>()
    // Grace window to treat shortly-after writes as internal saves, avoiding misfired "external" reloads
    private val internalSaveGraceMs = 15000L
    private var watchJob: Job? = null
    private var isRunning = false
    private var orphanCheckJob: Job? = null
    
    // Track metadata.edn timestamp to detect external changes
    private var metadataTimestamp: Long = 0

    /**
     * Start watching the workspace directory for external file changes
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
        val metadataFile = File(workspaceDir, METADATA_EDN)
        if (metadataFile.exists()) {
            metadataTimestamp = metadataFile.lastModified()
        }

        // Start the watch loop
        watchJob = GlobalScope.launch(Dispatchers.IO) {
            watchLoop()
        }
        
        // Start periodic orphan check (every 5 seconds)
        orphanCheckJob = GlobalScope.launch(Dispatchers.IO) {
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
     * Stop watching for file changes
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false
        watchJob?.cancel()
        orphanCheckJob?.cancel()
        watchKeys.keys.forEach { it.cancel() }
        watchKeys.clear()
        fileTimestamps.clear()
        internalSaveTimestamps.clear()

        try {
            watchService.close()
        } catch (e: Exception) {
            lprintln("error,file-watch", "Error closing watch service: ${e.message}")
        }

        lprintln("info,file-watch", "FileWatchManager stopped for ${sessionInfo.filename}")
    }

    /**
     * Mark a file as saved by Field internally (so we don't reload it)
     */
    fun markInternalSave(file: File) {
        if (!file.exists()) {
            return
        }
        val lastModified = file.lastModified()
        // Track the precise mtime we wrote so later events with a newer timestamp
        // are treated as external edits rather than our own save.
        internalSaveTimestamps[file.absolutePath] = lastModified
        fileTimestamps[file.absolutePath] = lastModified
    }

    private fun registerDirectory(path: Path) {
        try {
            val key = path.register(
                watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE
            )
            watchKeys[key] = path
        } catch (e: Exception) {
            lprintln("error,file-watch", "Failed to register directory ${path}: ${e.message}")
        }
    }

    private fun scanAndRecordTimestamps(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile && isSidecarFile(file)) {
                fileTimestamps[file.absolutePath] = file.lastModified()
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

                    // Handle metadata.edn changes
                    if (file.name == METADATA_EDN) {
                        handleMetadataChange(file)
                    } else if (file.isFile && isSidecarFile(file)) {
                        handleFileChange(file)
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

    private suspend fun handleFileChange(file: File) {
        val absolutePath = file.absolutePath

        // Small delay to ensure file write is complete
        delay(100.milliseconds)

        if (!file.exists()) return

        val currentTime = file.lastModified()
        val lastKnownTime = fileTimestamps[absolutePath]
        val lastInternalSave = internalSaveTimestamps[absolutePath]

        // Treat events within the grace window of our last internal write as ours to avoid
        // late-firing events from previous saves being treated as external edits.
        if (lastInternalSave != null && currentTime <= lastInternalSave + internalSaveGraceMs) {
            fileTimestamps[absolutePath] = currentTime
            return
        }

        // Check if file was modified
        if (lastKnownTime == null || currentTime > lastKnownTime) {
            // External change detected
            fileTimestamps[absolutePath] = currentTime
            reloadSidecarFile(file)
        }
    }

    private suspend fun reloadSidecarFile(file: File) {
        try {
            val filename = file.name

            // Parse filename: <boxId>_<propertyName>_.<extension>
            // Note the pattern has an underscore before the dot
            val lastUnderscore = filename.lastIndexOf("_.")
            if (lastUnderscore == -1) return

            val beforeLastUnderscore = filename.substring(0, lastUnderscore)
            val parts = beforeLastUnderscore.split("_")
            if (parts.size < 2) return

            val boxId = parts[0]
            val propertyName = parts[1]

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
                        box.inside.pendingSidecarSaves += box.id to propertyName
                    }

                    lprintln("info,file-watch", "Reloaded external change: $propertyName in box ${box.name} (${box.id})")
                    notify("Reloaded external change to $propertyName in ${box.name}", "file-watch")
                }
            }

        } catch (e: Exception) {
            lprintln("error,file-watch", "Error reloading sidecar file ${file.name}: ${e.message}")
        }
    }
    
    /**
     * Handle changes to metadata.edn file
     */
    private suspend fun handleMetadataChange(file: File) {
        try {
            if (!file.exists()) return
            
            val currentTime = file.lastModified()
            
            // Check if this is an internal save
            val lastInternalSave = internalSaveTimestamps[file.absolutePath]
            if (lastInternalSave != null && currentTime <= lastInternalSave + internalSaveGraceMs) {
                metadataTimestamp = currentTime
                return
            }
            
            // Check if file was actually modified
            if (currentTime <= metadataTimestamp) {
                return
            }
            
            metadataTimestamp = currentTime
            
            lprintln("info,file-watch", "External change detected to metadata.edn")
            
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
     * Reconcile the current box hierarchy with metadata.edn
     * This handles external additions/deletions to metadata
     */
    private fun reconcileMetadata(workspaceDir: File) {
        try {
            val savingContext = SavingContext()
            val transfers = savingContext.readEdnTransfers(workspaceDir.absolutePath)
            
            val metadataBoxIds = transfers.map { it.id }.toSet()
            val currentBoxIds = sessionInfo.boxes.keys.filter { it != "root" }.toSet()
            
            // Find boxes in metadata but not in current session (newly added externally)
            val newBoxIds = metadataBoxIds - currentBoxIds
            if (newBoxIds.isNotEmpty()) {
                lprintln("info,file-watch", "Found ${newBoxIds.size} new boxes in metadata.edn: $newBoxIds")
                
                // Load these boxes
                for (transfer in transfers.filter { it.id in newBoxIds }) {
                    val box = box(transfer.id, createIfMissing = true, initIfCreated = true, root = sessionInfo)
                    
                    // Apply properties from metadata
                    for ((name, value) in transfer.properties) {
                        val resolved = savingContext.resolveStoredValue(value, workspaceDir.absolutePath)
                        if (resolved != null) {
                            box.setPropertyRaw(name, resolved, doResync = true)
                        }
                    }
                    
                    lprintln("info,file-watch", "Created box from metadata: ${box.name} (${box.id})")
                }
                
                notify("Loaded ${newBoxIds.size} new box(es) from external metadata changes", "file-watch")
            }
            
            // Find boxes in current session but not in metadata (removed externally)
            val removedBoxIds = currentBoxIds - metadataBoxIds
            if (removedBoxIds.isNotEmpty()) {
                lprintln("info,file-watch", "Found ${removedBoxIds.size} boxes removed from metadata.edn: $removedBoxIds")
                
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
    
    /**
     * Check for orphaned sidecar files and auto-create boxes for them
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun checkForOrphanedFiles(workspaceDir: File) {
        try {
            val sidecarFiles = workspaceDir.listFiles()?.filter { it.isFile && isSidecarFile(it) } ?: return
            
            for (file in sidecarFiles) {
                val filename = file.name
                val lastUnderscore = filename.lastIndexOf("_.")
                if (lastUnderscore == -1) continue
                
                val beforeLastUnderscore = filename.substring(0, lastUnderscore)
                val parts = beforeLastUnderscore.split("_")
                if (parts.size < 2) continue
                
                val boxId = parts[0]
                val propertyName = parts[1]
                
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
     * Auto-create a box for an orphaned sidecar file
     * Returns the created box or null if creation failed
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun autoCreateBoxForSidecarFile(file: File, boxId: String, propertyName: String): Box? {
        try {
            // Create the box
            val box = box(boxId, createIfMissing = true, initIfCreated = true, root = sessionInfo)
            
            // Generate a sensible name from the filename
            val baseName = file.nameWithoutExtension.replace("_", " ").trim()
            val displayName = if (baseName.contains(boxId)) {
                // Extract meaningful part after box ID
                baseName.substringAfter(boxId).replace("_", " ").trim().ifEmpty { "Box $boxId" }
            } else {
                baseName
            }.replaceFirstChar { it.uppercase() }
            
            box.name = displayName
            
            // Calculate a non-overlapping frame position
            val frame = calculateNewBoxFrame()
            box.frame = frame
            
            // Read and set the file content
            val content = file.readText()
            box.setPropertyRaw(propertyName, content, doResync = true)
            
            // Mark for persistence
            if (box.isInsideInitialized()) {
                box.inside.pendingSidecarSaves += box.id to propertyName
                // Also ensure the box itself is saved to metadata
                box.inside.isDirty = true
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
     * Calculate a non-overlapping frame for a new box
     * Uses a simple grid layout starting at (50, 50) with 350x250 boxes
     */
    private fun calculateNewBoxFrame(): Rect {
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
        
        // Try to find a non-overlapping position
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
        
        // Fallback: just use a random offset if we couldn't find a good spot
        val randomOffset = (Math.random() * 100).toInt()
        return Rect(startX + randomOffset, startY + randomOffset, boxWidth, boxHeight)
    }
}
