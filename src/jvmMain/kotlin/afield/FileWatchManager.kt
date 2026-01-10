package afield

import afield.logger.lprintln
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * FileWatchManager monitors workspace directories for external file changes
 * and reloads sidecar files into Box properties, syncing to the frontend.
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

        // Start the watch loop
        watchJob = GlobalScope.launch(Dispatchers.IO) {
            watchLoop()
        }

        lprintln("info,file-watch", "FileWatchManager started for ${sessionInfo.filename}")
    }

    /**
     * Stop watching for file changes
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false
        watchJob?.cancel()
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

                    if (file.isFile && isSidecarFile(file)) {
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

            // Find the box
            val box = sessionInfo.boxes[boxId]
            if (box == null) {
                lprintln("warning,file-watch", "Box not found for sidecar file: $filename")
                return
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
}
