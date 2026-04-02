package afield

import afield.logger.lprintln
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

/**
 * Manages document history and version restoration.
 *
 * This system uses the backup files created by safeSave() to provide:
 * - A timeline of all document versions
 * - Ability to restore any previous version
 * - Tracking of version transitions
 * - Support for future forking/merging scenarios
 *
 * To optimize performance, this manager maintains an in-memory cache of version metadata
 * that is updated incrementally when saves occur, avoiding the need to reparse all
 * backup files on every request.
 */
object HistoryManager {

    /**
     * Properties we consider when computing line diffs between versions.
     * These focus on textual code/content fields.
     */
    private val diffTextProperties = setOf(
        "code",
        "fragment",
        "vertex",
        "compute",
        "task",
        "mesh",
        "fragment_s",
        "vertex_s",
        "compute_s",
        "task_s",
        "mesh_s",
        "preamble",
        "customization",
        "metadata",
        "name",
        "filename"
    )

    /**
     * In-memory cache of version lists per document.
     * Key: document path, Value: list of versions
     */
    private val versionCache = mutableMapOf<String, MutableList<VersionInfo>>()

    /**
     * Data class representing a single version in document history
     */
    data class VersionInfo(
        val index: Int,
        val file: File,
        val timestamp: Long,
        val boxIds: Set<String>,
        val boxCount: Int
    )

    /**
     * Data class representing a transition between versions
     */
    @Serializable
    data class Transition(
        val fromIndex: Int,
        val toIndex: Int,
        val timestamp: Long
    )

    /**
     * Data class for the versions.edn file format
     */
    data class VersionsData(
        val transitions: MutableList<Transition> = mutableListOf(),
        val names: MutableMap<Int, String> = mutableMapOf()
    )

    /**
     * Called by saving.kt when a new version is saved.
     * This updates the cache incrementally without reparsing all files.
     *
     * @param documentPath The path to the document directory
     * @param boxIds The set of box IDs in the new version
     * @param timestamp The timestamp of the save (defaults to now)
     */
    fun onVersionSaved(documentPath: String, boxIds: Set<String>, timestamp: Long = System.currentTimeMillis()) {
        val cached = versionCache[documentPath]

        if (cached == null) {
            // First save for this document - initialize cache
            val versions = scanVersionsFromDisk(documentPath)
            versionCache[documentPath] = versions.toMutableList()
        } else {
            // Incremental update - add the new version
            val currentMetadata = File(documentPath, "metadata.edn")
            if (currentMetadata.exists()) {
                // Check if we already have the current version or need to add it
                val lastVersion = cached.lastOrNull()
                if (lastVersion == null || lastVersion.file.name != "metadata.edn") {
                    // Add new version
                    val newVersion = VersionInfo(
                        index = cached.size,
                        file = currentMetadata,
                        timestamp = timestamp,
                        boxIds = boxIds,
                        boxCount = boxIds.size
                    )
                    cached.add(newVersion)
                } else {
                    // Update existing current version
                    cached[cached.size - 1] = VersionInfo(
                        index = cached.size - 1,
                        file = currentMetadata,
                        timestamp = timestamp,
                        boxIds = boxIds,
                        boxCount = boxIds.size
                    )
                }
            }
        }
    }

    /**
     * List all backup versions for a document directory.
     * Uses cached data if available, otherwise scans from disk.
     */
    fun listVersions(documentPath: String): List<VersionInfo> {
        // Check cache first
        val cached = versionCache[documentPath]
        if (cached != null) {
            return cached
        }

        // Not in cache - scan from disk and populate cache
        val versions = scanVersionsFromDisk(documentPath)
        versionCache[documentPath] = versions.toMutableList()
        return versions
    }

    /**
     * Scan versions from disk (used for initial load or cache miss).
     * This is the expensive operation we want to minimize.
     */
    private fun scanVersionsFromDisk(documentPath: String): List<VersionInfo> {
        val docDir = File(documentPath)

        if (!docDir.exists()) {
            lprintln("warning,history", "Document directory does not exist: ${docDir.absolutePath}")
            return emptyList()
        }

        val backupsDir = File(docDir, "backups")

        // Get all metadata backup files
        val backupFiles = if (backupsDir.exists() && backupsDir.isDirectory) {
            backupsDir.listFiles { file ->
                file.name.startsWith("metadata_backup_") && file.name.endsWith(".edn")
            }?.sortedBy { it.name } ?: emptyList()
        } else {
            lprintln("info,history", "No backups directory found at ${backupsDir.absolutePath}")
            emptyList()
        }

        // Add the current metadata.edn as the latest version (if it exists)
        val currentMetadata = File(docDir, "metadata.edn")
        val allFiles = if (currentMetadata.exists()) {
            backupFiles + currentMetadata
        } else {
            backupFiles
        }

        if (allFiles.isEmpty()) {
            lprintln("info,history", "No version history found for document at $documentPath")
            return emptyList()
        }

        // Reuse SavingContext for all operations
        val savingContext = SavingContext()

        return allFiles.mapIndexed { index, file ->
            val timestamp = file.lastModified()
            val docPath = if (file.name == "metadata.edn") docDir.absolutePath else docDir.absolutePath
            val boxIds = extractBoxIds(file, savingContext, docPath)
            VersionInfo(
                index = index,
                file = file,
                timestamp = timestamp,
                boxIds = boxIds,
                boxCount = boxIds.size
            )
        }
    }

    /**
     * Extract box IDs from a metadata.edn file without fully parsing it
     */
    private fun extractBoxIds(file: File, savingContext: SavingContext, documentPath: String): Set<String> {
        return try {
            val ednText = file.readText()
            val transfers = savingContext.readEdnTransfersFromString(ednText)
            transfers
                .filterNot { isTombstoned(it, documentPath, savingContext) || it.id == "root" }
                .map { it.id }
                .toSet()
        } catch (e: Exception) {
            lprintln("warning,history", "Failed to read box IDs from ${file.name}: ${e.message}")
            emptySet()
        }
    }

    private fun isTombstoned(
        transfer: BoxTransferEdn,
        documentPath: String,
        savingContext: SavingContext
    ): Boolean {
        val raw = transfer.properties["_tombstone"] ?: return false
        val resolved = savingContext.resolveStoredValue(raw, documentPath)
        return (resolved as? Boolean) == true
    }

    /**
     * Restore a document to a specific version
     *
     * This:
     * 1. Loads boxes from the target version
     * 2. Tombstones boxes not in that version
     * 3. Un-tombstones boxes that were in that version
     * 4. Records the transition in versions.edn
     */
    fun restoreVersion(
        documentPath: String,
        sessionInfo: SessionInformation,
        targetVersionIndex: Int
    ): Boolean {
        val versions = listVersions(documentPath)
        if (targetVersionIndex < 0 || targetVersionIndex >= versions.size) {
            lprintln("error,history", "Invalid version index: $targetVersionIndex")
            return false
        }

        val targetVersion = versions[targetVersionIndex]
        val currentVersion = versions.lastOrNull() ?: return false

        lprintln("info,history", "Restoring version $targetVersionIndex (${targetVersion.boxCount} boxes)")

        try {
            // Load the target version's metadata
            val targetBoxes = if (targetVersion.file.name == "metadata.edn") {
                // It's the current file
                SavingContext().load(documentPath, upgradeOnLoad = false)
            } else {
                // It's a backup file - load from the backup
                loadFromBackupFile(targetVersion.file)
            }

            val targetBoxIds = targetBoxes.map { it.id }.toSet()
            val currentBoxIds = sessionInfo.boxes.keys

            // Process boxes:
            // 1. Restore properties for boxes in target version
            targetBoxes.forEach { targetBox ->
                val existingBox = sessionInfo.boxes[targetBox.id] ?: sessionInfo.root.takeIf { it.id == targetBox.id }
                if (existingBox != null) {
                    // Box exists - restore its properties and un-tombstone
                    copyProperties(targetBox, existingBox)
                    existingBox.setPropertyRaw("_tombstone", false)  // Explicitly un-tombstone
                    existingBox.setPropertyRaw("_restored_from_version", targetVersionIndex)
                } else {
                    // Box doesn't exist - create a new box in the current session
                    val newBox = Box(targetBox.id)
                    newBox.inside = sessionInfo
                    newBox.name = targetBox.name
                    copyProperties(targetBox, newBox)
                    sessionInfo.boxes[newBox.id] = newBox
                    newBox.setPropertyRaw("_restored_from_version", targetVersionIndex)
                }
            }

            // 2. Tombstone boxes not in target version (but don't delete them)
            currentBoxIds.forEach { boxId ->
                if (boxId !in targetBoxIds && boxId != "root") {
                    val box = sessionInfo.boxes[boxId]
                    if (box != null) {
                        box.setPropertyRaw("_tombstone", true)
                        lprintln("debug,history", "Tombstoned box $boxId (not in version $targetVersionIndex)")
                    }
                }
            }

            // 3. Record the transition
            recordTransition(documentPath, currentVersion.index, targetVersionIndex)

            // 4. Save the current state
            val savingContext = SavingContext()
            val allBoxes = sessionInfo.boxes.values.toMutableSet()
            allBoxes.add(sessionInfo.root)
            savingContext.save(
                documentPath,
                allBoxes,
                "Restored from version $targetVersionIndex"
            )

            // 5. Rebuild version cache to reflect the new current state
            versionCache.remove(documentPath)
            listVersions(documentPath)

            // 5. Update topology and notify in main thread
            inMain {
                sessionInfo.topologyGeneration++
                doResync()
                notify("Restored to version $targetVersionIndex (${targetVersion.boxCount} boxes)", "history")
            }

            return true
        } catch (e: Exception) {
            lprintln("error,history", "Failed to restore version: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * Load boxes from a backup file
     */
    private fun loadFromBackupFile(backupFile: File): Set<Box> {
        val parentDir = backupFile.parentFile.parentFile // Go up from backups/ to document dir
        return SavingContext().loadFromEdnText(
            backupFile.readText(),
            parentDir.absolutePath,
            filterId = { it }
        )
    }

    /**
     * Copy properties from one box to another
     */
    private fun copyProperties(source: Box, target: Box) {
        // Get all persistent properties from source
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        source.properties.forEach { (name, value) ->
            if (patterns.any { name.matches(it) }) {
                if (value != null)
                    target.setPropertyRaw(name, value)
            }
        }
    }

    /**
     * Record a version transition in versions.edn
     */
    private fun recordTransition(documentPath: String, fromIndex: Int, toIndex: Int) {
        val versionsFile = File(documentPath, "versions.edn")
        versionsFile.parentFile?.mkdirs()
        val data = loadVersionsData(documentPath)

        data.transitions.add(
            Transition(
                fromIndex = fromIndex,
                toIndex = toIndex,
                timestamp = System.currentTimeMillis()
            )
        )

        saveVersionsData(versionsFile, data)
    }

    /**
     * Set a friendly name for a version
     */
    fun setVersionName(documentPath: String, versionIndex: Int, name: String): Boolean {
        val versionsFile = File(documentPath, "versions.edn")
        versionsFile.parentFile?.mkdirs()
        val data = loadVersionsData(documentPath)
        data.names[versionIndex] = name
        return try {
            saveVersionsData(versionsFile, data)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get names map
     */
    fun getVersionNames(documentPath: String): Map<Int, String> {
        return loadVersionsData(documentPath).names
    }

    /**
     * Load versions.edn data
     */
    private fun loadVersionsData(documentPath: String): VersionsData {
        val versionsFile = File(documentPath, "versions.edn")

        if (!versionsFile.exists()) {
            return VersionsData()
        }

        return try {
            val content = versionsFile.readText()
            val root = Edn.read(content, Edn.CustomTypes())

            when (root) {
                is Map<*, *> -> {
                    val transitionsList = root["transitions"] as? List<*> ?: emptyList<Any?>()
                    val transitions = transitionsList.mapNotNull { item ->
                        val map = item as? Map<*, *> ?: return@mapNotNull null
                        Transition(
                            fromIndex = (map["fromIndex"] as? Number)?.toInt() ?: -1,
                            toIndex = (map["toIndex"] as? Number)?.toInt() ?: -1,
                            timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
                        )
                    }.toMutableList()

                    val namesMap = mutableMapOf<Int, String>()
                    val rawNames = root["names"]
                    if (rawNames is Map<*, *>) {
                        rawNames.forEach { (k, v) ->
                            val key = (k as? Number)?.toInt() ?: return@forEach
                            val value = v?.toString() ?: return@forEach
                            namesMap[key] = value
                        }
                    }

                    VersionsData(transitions = transitions, names = namesMap)
                }

                else -> VersionsData()
            }
        } catch (e: Exception) {
            lprintln("warning,history", "Failed to load versions.edn: ${e.message}")
            VersionsData()
        }
    }

    /**
     * Save versions.edn data
     */
    private fun saveVersionsData(file: File, data: VersionsData) {
        try {
            file.parentFile?.mkdirs()
            val transitionsData = data.transitions.map { transition ->
                linkedMapOf<String, Any?>(
                    "fromIndex" to transition.fromIndex,
                    "toIndex" to transition.toIndex,
                    "timestamp" to transition.timestamp
                )
            }

            val payload = linkedMapOf<String, Any?>(
                "transitions" to transitionsData,
                "names" to data.names
            )

            val ednText = Edn.write(payload, Edn.CustomTypes())
            file.writeText(ednText)

            lprintln("info,history", "Saved versions.edn with ${data.transitions.size} transitions")
        } catch (e: Exception) {
            lprintln("error,history", "Failed to save versions.edn: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Get the version transitions for visualization
     */
    fun getTransitions(documentPath: String): List<Transition> {
        return loadVersionsData(documentPath).transitions
    }

    /**
     * Compute line diff summaries between consecutive versions for UI display.
     */
    fun computeDiffStats(documentPath: String, versions: List<VersionInfo>): List<HistoryDiffStats?> {
        if (versions.isEmpty()) return emptyList()

        val stats = MutableList<HistoryDiffStats?>(versions.size) { null }
        for (i in 1 until versions.size) {
            stats[i] = try {
                computeVersionDiff(documentPath, versions[i - 1], versions[i])
            } catch (e: Exception) {
                lprintln("warning,history", "Failed to diff versions ${i - 1} -> $i: ${e.message}")
                null
            }
        }
        return stats
    }

    private fun computeVersionDiff(
        documentPath: String,
        previous: VersionInfo,
        current: VersionInfo
    ): HistoryDiffStats {
        val prevProps = loadTextualBoxProps(previous, documentPath)
        val currProps = loadTextualBoxProps(current, documentPath)

        val prevIds = prevProps.keys
        val currIds = currProps.keys

        val addedIds = currIds - prevIds
        val deletedIds = prevIds - currIds
        val sharedIds = prevIds intersect currIds

        var linesAddedFromNew = 0
        addedIds.forEach { id ->
            linesAddedFromNew += sumLines(currProps[id])
        }

        var linesRemovedFromDeleted = 0
        deletedIds.forEach { id ->
            linesRemovedFromDeleted += sumLines(prevProps[id])
        }

        var linesAddedFromChanges = 0
        var linesRemovedFromChanges = 0
        sharedIds.forEach { id ->
            val oldProps = prevProps[id].orEmpty()
            val newProps = currProps[id].orEmpty()
            val propNames = (oldProps.keys + newProps.keys).toSet().filter { it in diffTextProperties }
            propNames.forEach { name ->
                val oldText = oldProps[name] ?: ""
                val newText = newProps[name] ?: ""
                val (added, removed) = computeLineChanges(oldText, newText)
                linesAddedFromChanges += added
                linesRemovedFromChanges += removed
            }
        }

        val totalAdded = linesAddedFromNew + linesAddedFromChanges
        val totalRemoved = linesRemovedFromDeleted + linesRemovedFromChanges

        return HistoryDiffStats(
            boxesAdded = addedIds.size,
            boxesDeleted = deletedIds.size,
            linesAddedFromNew = linesAddedFromNew,
            linesRemovedFromDeleted = linesRemovedFromDeleted,
            linesAddedFromChanges = linesAddedFromChanges,
            linesRemovedFromChanges = linesRemovedFromChanges,
            totalLinesAdded = totalAdded,
            totalLinesRemoved = totalRemoved
        )
    }

    private fun loadTextualBoxProps(version: VersionInfo, documentPath: String): Map<String, Map<String, String>> {
        val versionDir = if (version.file.name == "metadata.edn") {
            documentPath
        } else {
            version.file.parentFile?.parentFile?.absolutePath ?: documentPath
        }

        val savingContext = SavingContext()
        val transfers = if (version.file.name == "metadata.edn") {
            savingContext.readEdnTransfers(versionDir)
        } else {
            savingContext.readEdnTransfersFromString(version.file.readText())
        }.filterNot { isTombstoned(it, versionDir, savingContext) }

        return extractTextProperties(transfers, versionDir, savingContext)
    }

    private fun extractTextProperties(
        transfers: List<BoxTransferEdn>,
        documentPath: String,
        savingContext: SavingContext
    ): Map<String, Map<String, String>> {
        return transfers.associate { transfer ->
            val props = mutableMapOf<String, String>()
            transfer.properties.forEach { (name, rawValue) ->
                if (name !in diffTextProperties) return@forEach
                val resolved = savingContext.resolveStoredValue(rawValue, documentPath)
                val text = when (resolved) {
                    null -> ""
                    is String -> resolved
                    is Number, is Boolean -> resolved.toString()
                    is List<*> -> resolved.joinToString("\n") { it?.toString() ?: "" }
                    is Set<*> -> resolved.joinToString("\n") { it?.toString() ?: "" }
                    is Map<*, *> -> resolved.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                    else -> resolved.toString()
                }
                props[name] = text
            }
            transfer.id to props
        }
    }

    private fun sumLines(props: Map<String, String>?): Int {
        return props?.values?.sumOf { lineCount(it) } ?: 0
    }

    private fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        return text.split('\n').size
    }

    private fun computeLineChanges(oldText: String, newText: String): Pair<Int, Int> {
        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')

        // Protect against very large inputs with a simple fallback.
        if (oldLines.size * newLines.size > 20000) {
            val delta = newLines.size - oldLines.size
            return Pair(delta.coerceAtLeast(0), (-delta).coerceAtLeast(0))
        }

        val dp = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
        for (i in 1..oldLines.size) {
            for (j in 1..newLines.size) {
                dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val lcs = dp[oldLines.size][newLines.size]
        val added = newLines.size - lcs
        val removed = oldLines.size - lcs
        return Pair(added, removed)
    }

    /**
     * Clear the version cache for a document.
     * Call this when the document is closed or if you need to force a rescan.
     */
    fun clearCache(documentPath: String) {
        versionCache.remove(documentPath)
    }
}

/**
 * Install RPC handlers for document history operations
 */
@RunsAt(installHandlers)
fun installHistoryHandlers() {
    handlers["list-history-versions"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        // Force a fresh scan to reflect any new disk backups
        HistoryManager.clearCache(info.filename)
        val versions = HistoryManager.listVersions(info.filename)
        val names = HistoryManager.getVersionNames(info.filename)
        val diffStats = HistoryManager.computeDiffStats(info.filename, versions)

        val historyVersions = versions.map { v ->
            HistoryVersion(
                index = v.index,
                filename = v.file.name,
                timestamp = v.timestamp,
                boxCount = v.boxCount,
                message = names[v.index] ?: if (v.file.name == "metadata.edn") "Current" else "",
                diffStats = diffStats.getOrNull(v.index)
            )
        }

        val transitions = HistoryManager.getTransitions(info.filename).map {
            VersionTransition(
                fromIndex = it.fromIndex,
                toIndex = it.toIndex,
                timestamp = it.timestamp
            )
        }

        reply(
            Message("history-versions-list")
                .payload(
                    HistoryVersionsList(
                        versions = historyVersions,
                        transitions = transitions
                    )
                )
        )
        Handled.yes
    })

    handlers["restore-history-version"] = mutableSetOf({ message, reply ->
        val req = Json.decodeFromJsonElement<RestoreVersion>(message.payload!!)
        val info = thisSessionInfo.get()

        val success = HistoryManager.restoreVersion(
            info.filename,
            info,
            req.versionIndex
        )

        if (success) {
            reply(Message("restore-version-success").payload(mapOf("versionIndex" to req.versionIndex)))
        } else {
            reply(Message("restore-version-error").payload(mapOf("error" to "Failed to restore version")))
        }

        Handled.yes
    })

    handlers["name-history-version"] = mutableSetOf({ message, reply ->
        val req = Json.decodeFromJsonElement<NameHistoryVersion>(message.payload!!)
        val info = thisSessionInfo.get()
        val success = HistoryManager.setVersionName(info.filename, req.versionIndex, req.name)
        if (success) {
            reply(Message("name-history-version-success"))
        } else {
            reply(Message("name-history-version-error").payload(mapOf("error" to "Failed to name version")))
        }
        Handled.yes
    })
}
