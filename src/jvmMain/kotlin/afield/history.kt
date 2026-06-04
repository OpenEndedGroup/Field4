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
        "vertex_fragment",
        "compute",
        "task",
        "mesh",
        "raygen",
        "miss",
        "closesthit",
        "anyhit",
        "intersection",
        "callable",
        "fragment_s",
        "vertex_s",
        "compute_s",
        "task_s",
        "mesh_s",
        "raygen_s",
        "miss_s",
        "closesthit_s",
        "anyhit_s",
        "intersection_s",
        "callable_s",
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

    private data class VersionSnapshot(
        val transfers: List<BoxTransferEdn>,
        val directory: String,
        val preferInlineFiles: Boolean
    )

    private data class HistoryBoxState(
        val uid: String,
        val name: String,
        val properties: Map<String, Any?>,
        val frame: Rect?,
        val children: List<String>,
        val parents: List<String>
    )

    private val topologyProperties = setOf("children", "parents", "nested", "nestedIn")

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
            val savingContext = SavingContext()
            val targetSnapshot = loadVersionSnapshot(targetVersion, documentPath, savingContext)
            val activeTargetBoxIds = targetSnapshot.transfers
                .filterNot { it.id == "root" || isTombstoned(it, targetSnapshot.directory, savingContext) }
                .map { it.id }
                .toSet()
            val currentBoxIds = sessionInfo.boxes.keys.toSet()

            // Process boxes:
            // 1. Restore the persistent properties for every box represented by the target version.
            targetSnapshot.transfers.forEach { transfer ->
                val targetBox = if (transfer.id == "root") {
                    sessionInfo.root
                } else {
                    box(transfer.id, createIfMissing = true, initIfCreated = false, root = sessionInfo)
                }

                restorePersistentProperties(
                    transfer,
                    targetBox,
                    savingContext,
                    targetSnapshot.directory,
                    sessionInfo,
                    targetSnapshot.preferInlineFiles
                )

                if (targetBox.id != "root") {
                    val tombstonedInTarget = isTombstoned(transfer, targetSnapshot.directory, savingContext)
                    targetBox.setPropertyRaw("_tombstone", tombstonedInTarget)
                    targetBox.setPropertyRaw("_restored_from_version", targetVersionIndex)
                }
            }

            // 2. Tombstone boxes not active in the target version (but don't delete them)
            currentBoxIds.forEach { boxId ->
                if (boxId !in activeTargetBoxIds && boxId != "root") {
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

    fun diffVersionAgainstLive(
        documentPath: String,
        sessionInfo: SessionInformation,
        targetVersionIndex: Int,
        boxFilter: Set<String> = emptySet()
    ): HistoryVersionDiff? {
        val versions = listVersions(documentPath)
        if (targetVersionIndex < 0 || targetVersionIndex >= versions.size) {
            lprintln("error,history", "Invalid version index for diff: $targetVersionIndex")
            return null
        }

        return try {
            val savingContext = SavingContext()
            val currentSnapshot = liveSnapshot(documentPath, sessionInfo, savingContext)
            val targetSnapshot = loadVersionSnapshot(versions[targetVersionIndex], documentPath, savingContext)

            val currentStates = snapshotStates(currentSnapshot, savingContext)
            val targetStates = snapshotStates(targetSnapshot, savingContext)
            val ids = (currentStates.keys + targetStates.keys)
                .filter { it != "root" }
                .filter { boxFilter.isEmpty() || it in boxFilter }
                .toSet()

            val boxChanges = ids.mapNotNull { uid ->
                val current = currentStates[uid]
                val target = targetStates[uid]
                boxDiff(uid, current, target)
            }.sortedWith(
                compareByDescending<HistoryBoxChange> { boxChangeWeight(it) }
                    .thenBy { it.name.lowercase() }
            )

            HistoryVersionDiff(
                versionIndex = targetVersionIndex,
                currentIndex = versions.lastOrNull()?.index ?: -1,
                generatedAt = System.currentTimeMillis(),
                boxFilter = boxFilter.toList().sorted(),
                boxes = boxChanges,
                changedBoxCount = boxChanges.size,
                propertyChangeCount = boxChanges.sumOf { it.properties.size },
                lineStats = sumLineStats(boxChanges.map { it.lineStats })
            )
        } catch (e: Exception) {
            lprintln("error,history", "Failed to compute scrub diff: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    fun cherryPickVersion(
        documentPath: String,
        sessionInfo: SessionInformation,
        targetVersionIndex: Int,
        selections: List<HistoryCherryPickSelection>,
        includeTopology: Boolean = true
    ): CherryPickHistoryResult? {
        val versions = listVersions(documentPath)
        if (targetVersionIndex < 0 || targetVersionIndex >= versions.size) {
            lprintln("error,history", "Invalid version index for cherry-pick: $targetVersionIndex")
            return null
        }
        if (selections.isEmpty()) {
            return CherryPickHistoryResult(targetVersionIndex, 0, 0, 0)
        }

        return try {
            val savingContext = SavingContext()
            val targetSnapshot = loadVersionSnapshot(versions[targetVersionIndex], documentPath, savingContext)
            val transfersById = targetSnapshot.transfers.associateBy { it.id }

            var boxesTouched = 0
            var propertiesTouched = 0
            var tombstonedBoxes = 0

            selections
                .filter { it.uid.isNotBlank() && it.uid != "root" }
                .distinctBy { it.uid to it.properties.sorted().joinToString("|") }
                .forEach { selection ->
                    val source = transfersById[selection.uid]
                    val tombstonedInTarget = source == null ||
                        isTombstoned(source, targetSnapshot.directory, savingContext)

                    if (tombstonedInTarget) {
                        val existing = sessionInfo.boxes[selection.uid]
                        if (existing != null && !existing.getPropertyRaw("_tombstone").isTrue()) {
                            existing.setPropertyRaw("_tombstone", true)
                            existing.setPropertyRaw("_restored_from_version", targetVersionIndex)
                            boxesTouched++
                            tombstonedBoxes++
                        }
                        return@forEach
                    }

                    val targetBox = box(
                        selection.uid,
                        createIfMissing = true,
                        initIfCreated = false,
                        root = sessionInfo
                    )
                    val selectedProperties = selection.properties
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                        .ifEmpty { null }

                    val touched = restoreSelectedPersistentProperties(
                        source!!,
                        targetBox,
                        savingContext,
                        targetSnapshot.directory,
                        sessionInfo,
                        targetSnapshot.preferInlineFiles,
                        selectedProperties,
                        includeTopology
                    )

                    targetBox.setPropertyRaw("_tombstone", false)
                    targetBox.setPropertyRaw("_restored_from_version", targetVersionIndex)

                    if (includeTopology && selectedPropertiesRestoresTopology(selectedProperties, source)) {
                        reconcileLiveTopology(targetBox, sessionInfo)
                    }

                    boxesTouched++
                    propertiesTouched += touched
                }

            val allBoxes = sessionInfo.boxes.values.toMutableSet()
            allBoxes.add(sessionInfo.root)
            savingContext.save(
                documentPath,
                allBoxes,
                "Cherry-picked from version $targetVersionIndex"
            )

            versionCache.remove(documentPath)
            listVersions(documentPath)

            inMain {
                sessionInfo.topologyGeneration++
                doResync()
                notify(
                    "Cherry-picked $boxesTouched boxes from version $targetVersionIndex",
                    "history"
                )
            }

            CherryPickHistoryResult(
                versionIndex = targetVersionIndex,
                boxesTouched = boxesTouched,
                propertiesTouched = propertiesTouched,
                tombstonedBoxes = tombstonedBoxes
            )
        } catch (e: Exception) {
            lprintln("error,history", "Failed to cherry-pick history version: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun loadVersionSnapshot(
        version: VersionInfo,
        documentPath: String,
        savingContext: SavingContext
    ): VersionSnapshot {
        val versionDir = if (version.file.name == "metadata.edn") {
            documentPath
        } else {
            version.file.parentFile?.parentFile?.absolutePath ?: documentPath
        }
        val transfers = if (version.file.name == "metadata.edn") {
            savingContext.readEdnTransfers(versionDir)
        } else {
            savingContext.readEdnTransfersFromString(version.file.readText())
        }
        return VersionSnapshot(
            transfers = transfers,
            directory = versionDir,
            preferInlineFiles = version.file.name != "metadata.edn"
        )
    }

    private fun liveSnapshot(
        documentPath: String,
        sessionInfo: SessionInformation,
        savingContext: SavingContext
    ): VersionSnapshot {
        val allBoxes = sessionInfo.boxes.values.toMutableSet()
        allBoxes.add(sessionInfo.root)
        val edn = savingContext.exportEdnPackage(allBoxes, documentPath)
        return VersionSnapshot(
            transfers = savingContext.readEdnTransfersFromString(edn),
            directory = documentPath,
            preferInlineFiles = true
        )
    }

    private fun snapshotStates(
        snapshot: VersionSnapshot,
        savingContext: SavingContext
    ): Map<String, HistoryBoxState> {
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        fun isPersistent(name: String) = patterns.any { name.matches(it) }

        return snapshot.transfers
            .filterNot { it.id == "root" || isTombstoned(it, snapshot.directory, savingContext) }
            .associate { transfer ->
                val props = LinkedHashMap<String, Any?>()
                transfer.properties.forEach { (name, rawValue) ->
                    if (!isPersistent(name)) return@forEach
                    props[name] = savingContext.resolveStoredValue(
                        rawValue,
                        snapshot.directory,
                        preferInlineFiles = snapshot.preferInlineFiles
                    )
                }

                val name = (props["name"] as? String)?.takeIf { it.isNotBlank() }
                    ?: transfer.id.take(8)
                val frame = props["frame"] as? Rect

                transfer.id to HistoryBoxState(
                    uid = transfer.id,
                    name = name,
                    properties = props,
                    frame = frame,
                    children = topologyIds(props["children"]),
                    parents = topologyIds(props["parents"])
                )
            }
    }

    private fun boxDiff(
        uid: String,
        current: HistoryBoxState?,
        target: HistoryBoxState?
    ): HistoryBoxChange? {
        if (current == null && target == null) return null

        val status = when {
            current == null -> "target-only"
            target == null -> "present-only"
            else -> "changed"
        }
        val name = target?.name ?: current?.name ?: uid.take(8)
        val properties = when {
            current == null -> target!!.properties.keys.map { propertyChange(it, null, target.properties[it]) }
            target == null -> current.properties.keys.map { propertyChange(it, current.properties[it], null) }
            else -> {
                val names = (current.properties.keys + target.properties.keys).toSortedSet()
                names.mapNotNull { property ->
                    val hasCurrent = current.properties.containsKey(property)
                    val hasTarget = target.properties.containsKey(property)
                    val currentValue = current.properties[property]
                    val targetValue = target.properties[property]
                    if (hasCurrent && hasTarget && canonicalValue(currentValue) == canonicalValue(targetValue)) {
                        null
                    } else {
                        propertyChange(
                            property,
                            if (hasCurrent) currentValue else null,
                            if (hasTarget) targetValue else null
                        )
                    }
                }
            }
        }

        if (status == "changed" && properties.isEmpty()) return null

        return HistoryBoxChange(
            uid = uid,
            name = name,
            status = status,
            properties = properties,
            currentFrame = current?.frame,
            targetFrame = target?.frame,
            currentChildren = current?.children.orEmpty(),
            targetChildren = target?.children.orEmpty(),
            currentParents = current?.parents.orEmpty(),
            targetParents = target?.parents.orEmpty(),
            lineStats = sumLineStats(properties.map { it.lineStats })
        )
    }

    private fun propertyChange(
        property: String,
        currentValue: Any?,
        targetValue: Any?
    ): HistoryPropertyChange {
        val hasCurrent = currentValue != null
        val hasTarget = targetValue != null
        val kind = propertyKind(property, currentValue, targetValue)
        val status = when {
            !hasCurrent && hasTarget -> "target-only"
            hasCurrent && !hasTarget -> "present-only"
            property == "frame" -> "moved"
            else -> "changed"
        }
        val lineStats = if (kind == "text") {
            computeDetailedLineStats(valueToText(currentValue), valueToText(targetValue))
        } else {
            HistoryLineStats()
        }

        return HistoryPropertyChange(
            property = property,
            status = status,
            kind = kind,
            currentPreview = previewValue(currentValue),
            targetPreview = previewValue(targetValue),
            lineStats = lineStats
        )
    }

    private fun propertyKind(property: String, currentValue: Any?, targetValue: Any?): String = when {
        property == "frame" -> "frame"
        property in topologyProperties -> "topology"
        property in diffTextProperties -> "text"
        currentValue is String || targetValue is String -> "text"
        else -> "value"
    }

    private fun selectedPropertiesRestoresTopology(
        selectedProperties: Set<String>?,
        source: BoxTransferEdn
    ): Boolean {
        if (selectedProperties != null) return selectedProperties.any { it in topologyProperties }
        return source.properties.keys.any { it in topologyProperties }
    }

    private fun boxChangeWeight(change: HistoryBoxChange): Int {
        val lineWeight = change.lineStats.added + change.lineStats.removed +
            change.lineStats.moved + change.lineStats.changed
        val statusWeight = when (change.status) {
            "target-only", "present-only" -> 500
            else -> 0
        }
        return statusWeight + change.properties.size * 20 + lineWeight
    }

    private fun sumLineStats(stats: List<HistoryLineStats>): HistoryLineStats {
        return HistoryLineStats(
            added = stats.sumOf { it.added },
            removed = stats.sumOf { it.removed },
            moved = stats.sumOf { it.moved },
            changed = stats.sumOf { it.changed }
        )
    }

    private fun valueToText(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Collection<*> -> value.joinToString("\n") { canonicalValue(it) }
        is Map<*, *> -> value.entries.joinToString("\n") { "${canonicalValue(it.key)}: ${canonicalValue(it.value)}" }
        else -> value.toString()
    }

    private fun previewValue(value: Any?): String {
        val text = when (value) {
            null -> ""
            is Rect -> "${trimNumber(value.x)}, ${trimNumber(value.y)}, ${trimNumber(value.w)}, ${trimNumber(value.h)}"
            is Collection<*> -> value.map { canonicalValue(it) }.joinToString(", ")
            is Map<*, *> -> value.entries.joinToString(", ") { "${canonicalValue(it.key)}: ${canonicalValue(it.value)}" }
            else -> value.toString()
        }.replace("\r\n", "\n").replace("\n", " | ").trim()

        return if (text.length <= 180) text else text.take(177) + "..."
    }

    private fun trimNumber(value: Double): String {
        val long = value.toLong()
        return if (value == long.toDouble()) long.toString() else "%.3f".format(value)
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        null -> "<absent>"
        is Box -> "box:${value.id}"
        is Rect -> "rect:${trimNumber(value.x)},${trimNumber(value.y)},${trimNumber(value.w)},${trimNumber(value.h)}"
        is String -> value
        is Number, is Boolean -> value.toString()
        is Collection<*> -> value.map { canonicalValue(it) }.sorted().joinToString(prefix = "[", postfix = "]")
        is Map<*, *> -> value.entries
            .map { canonicalValue(it.key) to canonicalValue(it.value) }
            .sortedBy { it.first }
            .joinToString(prefix = "{", postfix = "}") { "${it.first}:${it.second}" }
        else -> boxRefIdLike(value)?.let { "box:$it" } ?: value.toString()
    }

    private fun topologyIds(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is Box -> listOf(value.id)
        is Collection<*> -> value.mapNotNull { topologyIds(it).firstOrNull() }.sorted()
        else -> boxRefIdLike(value)?.let { listOf(it) } ?: emptyList()
    }

    private fun boxRefIdLike(value: Any?): String? {
        if (value == null) return null
        if (value is Box) return value.id
        if (value is String) return value.removePrefix(":").takeIf { it.isNotBlank() }
        val text = value.toString()
        return Regex("""BoxRef\(id=([^)]+)\)""").find(text)?.groupValues?.getOrNull(1)
    }

    private fun computeDetailedLineStats(oldText: String, newText: String): HistoryLineStats {
        val oldLines = if (oldText.isEmpty()) emptyList() else oldText.split('\n')
        val newLines = if (newText.isEmpty()) emptyList() else newText.split('\n')
        if (oldLines.isEmpty() && newLines.isEmpty()) return HistoryLineStats()

        if (oldLines.size * newLines.size > 120000) {
            val delta = newLines.size - oldLines.size
            return HistoryLineStats(
                added = delta.coerceAtLeast(0),
                removed = (-delta).coerceAtLeast(0)
            )
        }

        val common = multisetOverlap(oldLines, newLines)
        val lcs = lcsCount(oldLines, newLines)
        val rawAdded = (newLines.size - common).coerceAtLeast(0)
        val rawRemoved = (oldLines.size - common).coerceAtLeast(0)
        val changed = minOf(rawAdded, rawRemoved)

        return HistoryLineStats(
            added = rawAdded - changed,
            removed = rawRemoved - changed,
            moved = (common - lcs).coerceAtLeast(0),
            changed = changed
        )
    }

    private fun multisetOverlap(a: List<String>, b: List<String>): Int {
        val counts = mutableMapOf<String, Int>()
        a.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        var total = 0
        b.forEach { line ->
            val count = counts[line] ?: 0
            if (count > 0) {
                counts[line] = count - 1
                total++
            }
        }
        return total
    }

    private fun lcsCount(oldLines: List<String>, newLines: List<String>): Int {
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
        return dp[oldLines.size][newLines.size]
    }

    /**
     * Replace a live box's persistent properties with the properties from a saved transfer.
     */
    private fun restorePersistentProperties(
        source: BoxTransferEdn,
        target: Box,
        savingContext: SavingContext,
        documentPath: String,
        sessionInfo: SessionInformation,
        preferInlineFiles: Boolean
    ) {
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        fun isPersistent(name: String) = patterns.any { name.matches(it) }

        val sourcePersistentNames = source.properties.keys.filter(::isPersistent).toSet()
        val removedNames = target.properties.keys
            .filter { isPersistent(it) && it !in sourcePersistentNames }
            .toList()
        removedNames.forEach { name ->
            val removed = target.properties.remove(name)
            if (removed != null && Box.uiResyncProperties.any { it.matches(name) }) {
                needsResync.add(target to name)
            }
        }

        source.properties.forEach { (name, rawValue) ->
            if (!isPersistent(name)) return@forEach
            val value = savingContext.resolveStoredValueWithBoxes(
                rawValue,
                documentPath,
                filterId = { it },
                root = sessionInfo,
                preferInlineFiles = preferInlineFiles
            )
            if (value != null) {
                target.setPropertyRaw(name, value)
            }
        }
    }

    private fun restoreSelectedPersistentProperties(
        source: BoxTransferEdn,
        target: Box,
        savingContext: SavingContext,
        documentPath: String,
        sessionInfo: SessionInformation,
        preferInlineFiles: Boolean,
        selectedProperties: Set<String>?,
        includeTopology: Boolean
    ): Int {
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        fun isPersistent(name: String) = patterns.any { name.matches(it) }
        fun canTouch(name: String) = isPersistent(name) && (includeTopology || name !in topologyProperties)

        val sourcePersistentNames = source.properties.keys
            .filter(::canTouch)
            .toSet()
        val requestedNames = selectedProperties
            ?.filter(::canTouch)
            ?.toSet()
            ?: sourcePersistentNames

        var touched = 0

        if (selectedProperties == null) {
            val removedNames = target.properties.keys
                .filter(::canTouch)
                .filter { it !in sourcePersistentNames }
                .toList()
            removedNames.forEach { name ->
                val removed = target.properties.remove(name)
                if (removed != null && Box.uiResyncProperties.any { it.matches(name) }) {
                    needsResync.add(target to name)
                    touched++
                }
            }
        }

        requestedNames.forEach { name ->
            val rawValue = source.properties[name]
            if (rawValue == null) {
                val removed = target.properties.remove(name)
                if (removed != null && Box.uiResyncProperties.any { it.matches(name) }) {
                    needsResync.add(target to name)
                }
                if (removed != null) touched++
                return@forEach
            }

            val value = savingContext.resolveStoredValueWithBoxes(
                rawValue,
                documentPath,
                filterId = { it },
                root = sessionInfo,
                preferInlineFiles = preferInlineFiles
            )
            if (value != null) {
                target.setPropertyRaw(name, value)
                touched++
            }
        }

        return touched
    }

    private fun reconcileLiveTopology(box: Box, sessionInfo: SessionInformation) {
        val allBoxes = sessionInfo.boxes.values.toMutableSet()
        allBoxes.add(sessionInfo.root)

        val desiredChildren = box.children.toSet()
        val linkedChildren = allBoxes.filter { it.parents.contains(box) }.toSet()
        (linkedChildren - desiredChildren).forEach { child ->
            child.parents.remove(box)
            needsResync.add(child to "parents")
        }
        desiredChildren.forEach { child ->
            if (!child.parents.contains(box)) {
                child.parents.add(box)
                needsResync.add(child to "parents")
            }
        }
        needsResync.add(box to "children")

        val desiredParents = box.parents.toSet()
        val linkedParents = allBoxes.filter { it.children.contains(box) }.toSet()
        (linkedParents - desiredParents).forEach { parent ->
            parent.children.remove(box)
            needsResync.add(parent to "children")
        }
        desiredParents.forEach { parent ->
            if (!parent.children.contains(box)) {
                parent.children.add(box)
                needsResync.add(parent to "children")
            }
        }
        needsResync.add(box to "parents")
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
        val changedCodeBoxes = mutableListOf<String>()

        var linesAddedFromNew = 0
        addedIds.forEach { id ->
            linesAddedFromNew += sumLines(currProps[id])
            if (!currProps[id]?.get("code").isNullOrEmpty()) {
                changedCodeBoxes += displayNameForChangedBox(id, currProps[id], prevProps[id])
            }
        }

        var linesRemovedFromDeleted = 0
        deletedIds.forEach { id ->
            linesRemovedFromDeleted += sumLines(prevProps[id])
            if (!prevProps[id]?.get("code").isNullOrEmpty()) {
                changedCodeBoxes += displayNameForChangedBox(id, currProps[id], prevProps[id])
            }
        }

        var linesAddedFromChanges = 0
        var linesRemovedFromChanges = 0
        sharedIds.forEach { id ->
            val oldProps = prevProps[id].orEmpty()
            val newProps = currProps[id].orEmpty()
            if ((oldProps["code"] ?: "") != (newProps["code"] ?: "")) {
                changedCodeBoxes += displayNameForChangedBox(id, newProps, oldProps)
            }
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
            totalLinesRemoved = totalRemoved,
            changedCodeBoxes = changedCodeBoxes.distinct()
        )
    }

    private fun displayNameForChangedBox(
        id: String,
        currentProps: Map<String, String>?,
        previousProps: Map<String, String>?
    ): String {
        val name = currentProps?.get("name")
            ?.takeIf { it.isNotBlank() }
            ?: previousProps?.get("name")?.takeIf { it.isNotBlank() }

        return name ?: id.take(8)
    }

    private fun loadTextualBoxProps(version: VersionInfo, documentPath: String): Map<String, Map<String, String>> {
        val versionDir = if (version.file.name == "metadata.edn") {
            documentPath
        } else {
            version.file.parentFile?.parentFile?.absolutePath ?: documentPath
        }
        val preferInlineFiles = version.file.name != "metadata.edn"

        val savingContext = SavingContext()
        val transfers = if (version.file.name == "metadata.edn") {
            savingContext.readEdnTransfers(versionDir)
        } else {
            savingContext.readEdnTransfersFromString(version.file.readText())
        }.filterNot { isTombstoned(it, versionDir, savingContext) }

        return extractTextProperties(transfers, versionDir, savingContext, preferInlineFiles)
    }

    private fun extractTextProperties(
        transfers: List<BoxTransferEdn>,
        documentPath: String,
        savingContext: SavingContext,
        preferInlineFiles: Boolean
    ): Map<String, Map<String, String>> {
        return transfers.associate { transfer ->
            val props = mutableMapOf<String, String>()
            transfer.properties.forEach { (name, rawValue) ->
                if (name !in diffTextProperties) return@forEach
                val resolved = savingContext.resolveStoredValue(
                    rawValue,
                    documentPath,
                    preferInlineFiles = preferInlineFiles
                )
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

    handlers["diff-history-version"] = mutableSetOf({ message, reply ->
        val req = Json.decodeFromJsonElement<HistoryDiffRequest>(message.payload!!)
        val info = thisSessionInfo.get()
        val diff = HistoryManager.diffVersionAgainstLive(
            info.filename,
            info,
            req.versionIndex,
            req.boxIds.toSet()
        )

        if (diff != null) {
            reply(Message("history-version-diff").payload(diff))
        } else {
            reply(Message("history-version-diff-error").payload(mapOf("error" to "Failed to diff version")))
        }

        Handled.yes
    })

    handlers["cherry-pick-history-version"] = mutableSetOf({ message, reply ->
        val req = Json.decodeFromJsonElement<CherryPickHistoryRequest>(message.payload!!)
        val info = thisSessionInfo.get()
        val result = HistoryManager.cherryPickVersion(
            info.filename,
            info,
            req.versionIndex,
            req.selections,
            req.includeTopology
        )

        if (result != null) {
            reply(Message("cherry-pick-history-success").payload(result))
        } else {
            reply(Message("cherry-pick-history-error").payload(mapOf("error" to "Failed to cherry-pick version")))
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
