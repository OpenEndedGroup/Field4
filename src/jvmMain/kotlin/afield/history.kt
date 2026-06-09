package afield

import afield.logger.lprintln
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    private val versionCacheLock = Any()

    /**
     * Data class representing a single version in document history
     */
    data class VersionInfo(
        val index: Int,
        val file: File,
        val timestamp: Long,
        val boxIds: Set<String>,
        val boxCount: Int,
        val byteSize: Long
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

    private data class PersistentChangeKinds(
        val changedIds: Set<String> = emptySet(),
        val editedBoxes: Int = 0,
        val movedBoxes: Int = 0
    )

    private val topologyProperties = setOf("children", "parents", "nested", "nestedIn")
    private val hiddenScrubProperties = setOf(
        "code_decorationState",
        "_restored_from_version",
        "_tombstone",
        "source_id",
        "templateId",
        "preamble",
        "isRemoteJSRoot",
        "isPythonRoot"
    )
    private val hiddenScrubPropertyPatterns = listOf(
        Regex(""".*_editorState$"""),
        Regex(""".*_decorationState$"""),
        Regex(""".*_viewState$"""),
        Regex(""".*_cursorState$"""),
        Regex(""".*_selectionState$""")
    )
    private val missingDefaultValues = mapOf(
        "autorun" to false,
        "_tombstone" to false
    )
    private const val fullTextDiffLimit = 220_000

    /**
     * Called by saving.kt when a new version is saved.
     * This updates the cache incrementally without reparsing all files.
     *
     * @param documentPath The path to the document directory
     * @param boxIds The set of box IDs in the new version
     * @param timestamp The timestamp of the save (defaults to now)
     */
    fun onVersionSaved(documentPath: String, boxIds: Set<String>, timestamp: Long = System.currentTimeMillis()) {
        synchronized(versionCacheLock) {
            val cached = versionCache[documentPath]

            if (cached == null) {
                return
            } else {
                val currentMetadata = File(documentPath, "metadata.edn")
                if (currentMetadata.exists()) {
                    val lastVersion = cached.lastOrNull()
                    if (lastVersion?.file?.name == "metadata.edn") {
                        val backupsDir = File(documentPath, "backups")
                        val backupFiles = backupsDir.listFiles { file ->
                            file.name.startsWith("metadata_backup_") && file.name.endsWith(".edn")
                        }?.sortedBy { it.name }.orEmpty()
                        val replacementBackup = backupFiles.getOrNull(lastVersion.index)
                        if (replacementBackup != null) {
                            cached[lastVersion.index] = VersionInfo(
                                index = lastVersion.index,
                                file = replacementBackup,
                                timestamp = replacementBackup.lastModified(),
                                boxIds = lastVersion.boxIds,
                                boxCount = lastVersion.boxCount,
                                byteSize = replacementBackup.length()
                            )
                        } else if (lastVersion.index < backupFiles.size) {
                            versionCache[documentPath] = scanVersionsFromDisk(documentPath).toMutableList()
                            return
                        }
                    }

                    if (cached.lastOrNull()?.file?.name != "metadata.edn") {
                        cached.add(
                            VersionInfo(
                                index = cached.size,
                                file = currentMetadata,
                                timestamp = timestamp,
                                boxIds = boxIds,
                                boxCount = boxIds.size,
                                byteSize = currentMetadata.length()
                            )
                        )
                    } else if (cached.isNotEmpty()) {
                        cached[cached.lastIndex] = VersionInfo(
                            index = cached.lastIndex,
                            file = currentMetadata,
                            timestamp = timestamp,
                            boxIds = boxIds,
                            boxCount = boxIds.size,
                            byteSize = currentMetadata.length()
                        )
                    }
                }
            }
        }
    }

    /**
     * List all backup versions for a document directory.
     * Uses cached data if available, otherwise scans from disk.
     */
    fun listVersions(documentPath: String): List<VersionInfo> {
        synchronized(versionCacheLock) {
            val cached = versionCache[documentPath]
            if (cached != null) {
                return cached.toList()
            }

            val versions = scanVersionsFromDisk(documentPath)
            versionCache[documentPath] = versions.toMutableList()
            return versions
        }
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

        return allFiles.mapIndexed { index, file ->
            val timestamp = file.lastModified()
            VersionInfo(
                index = index,
                file = file,
                timestamp = timestamp,
                boxIds = emptySet(),
                boxCount = 0,
                byteSize = file.length()
            )
        }
    }

    fun boxIdsForVersion(documentPath: String, version: VersionInfo): Set<String> {
        return extractBoxIds(version.file, SavingContext(), documentPath)
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
        boxFilter: Set<String> = emptySet(),
        propertyFilter: Set<String> = emptySet()
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
                boxDiff(uid, current, target, propertyFilter)
            }.sortedWith(
                compareByDescending<HistoryBoxChange> { boxChangeWeight(it) }
                    .thenBy { it.name.lowercase() }
            )

            HistoryVersionDiff(
                versionIndex = targetVersionIndex,
                currentIndex = versions.lastOrNull()?.index ?: -1,
                generatedAt = System.currentTimeMillis(),
                basis = "current",
                baselineIndex = versions.lastOrNull()?.index ?: -1,
                boxFilter = boxFilter.toList().sorted(),
                properties = propertyFilter.toList().sorted(),
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

    fun diffVersionAgainstPrevious(
        documentPath: String,
        targetVersionIndex: Int,
        boxFilter: Set<String> = emptySet(),
        propertyFilter: Set<String> = emptySet()
    ): HistoryVersionDiff? {
        val versions = listVersions(documentPath)
        if (targetVersionIndex < 0 || targetVersionIndex >= versions.size) {
            lprintln("error,history", "Invalid version index for previous diff: $targetVersionIndex")
            return null
        }

        if (targetVersionIndex == 0) {
            return HistoryVersionDiff(
                versionIndex = targetVersionIndex,
                currentIndex = versions.lastOrNull()?.index ?: -1,
                generatedAt = System.currentTimeMillis(),
                basis = "previous",
                baselineIndex = -1,
                boxFilter = boxFilter.toList().sorted(),
                properties = propertyFilter.toList().sorted()
            )
        }

        return try {
            val savingContext = SavingContext()
            val previousSnapshot = loadVersionSnapshot(versions[targetVersionIndex - 1], documentPath, savingContext)
            val targetSnapshot = loadVersionSnapshot(versions[targetVersionIndex], documentPath, savingContext)

            val previousStates = snapshotStates(previousSnapshot, savingContext)
            val targetStates = snapshotStates(targetSnapshot, savingContext)
            val ids = (previousStates.keys + targetStates.keys)
                .filter { it != "root" }
                .filter { boxFilter.isEmpty() || it in boxFilter }
                .toSet()

            val boxChanges = ids.mapNotNull { uid ->
                val previous = previousStates[uid]
                val target = targetStates[uid]
                boxDiff(uid, previous, target, propertyFilter)
            }.sortedWith(
                compareByDescending<HistoryBoxChange> { boxChangeWeight(it) }
                    .thenBy { it.name.lowercase() }
            )

            HistoryVersionDiff(
                versionIndex = targetVersionIndex,
                currentIndex = versions.lastOrNull()?.index ?: -1,
                generatedAt = System.currentTimeMillis(),
                basis = "previous",
                baselineIndex = versions[targetVersionIndex - 1].index,
                boxFilter = boxFilter.toList().sorted(),
                properties = propertyFilter.toList().sorted(),
                boxes = boxChanges,
                changedBoxCount = boxChanges.size,
                propertyChangeCount = boxChanges.sumOf { it.properties.size },
                lineStats = sumLineStats(boxChanges.map { it.lineStats })
            )
        } catch (e: Exception) {
            lprintln("error,history", "Failed to compute previous scrub diff: ${e.message}")
            e.printStackTrace()
            null
	        }
	    }

	    private fun liveStatesForRailStats(
	        documentPath: String,
	        sessionInfo: SessionInformation,
	        savingContext: SavingContext = SavingContext()
	    ): Map<String, HistoryBoxState> {
	        return snapshotStates(liveSnapshot(documentPath, sessionInfo, savingContext), savingContext)
	    }

	    private fun lineStatsAgainstLive(
	        documentPath: String,
	        sessionInfo: SessionInformation,
	        targetVersionIndex: Int,
	        boxFilter: Set<String> = emptySet(),
	        propertyFilter: Set<String> = emptySet(),
	        savingContext: SavingContext = SavingContext(),
	        currentStates: Map<String, HistoryBoxState>? = null
	    ): HistoryLineStats? {
	        val versions = listVersions(documentPath)
	        val version = versions.firstOrNull { it.index == targetVersionIndex } ?: return null
	        if (version.index == versions.lastOrNull()?.index) return HistoryLineStats()

	        return try {
	            val liveStates = currentStates ?: liveStatesForRailStats(documentPath, sessionInfo, savingContext)
	            val targetStates = snapshotStates(loadVersionSnapshot(version, documentPath, savingContext), savingContext)
	            val ids = (liveStates.keys + targetStates.keys)
	                .filter { it != "root" }
	                .filter { boxFilter.isEmpty() || it in boxFilter }
	                .toSet()
	            sumLineStats(
	                ids.mapNotNull { uid ->
	                    boxDiff(uid, liveStates[uid], targetStates[uid], propertyFilter)?.lineStats
	                }
	            )
	        } catch (e: Exception) {
	            lprintln("warning,history", "Failed to compute live rail line stats for $targetVersionIndex: ${e.message}")
	            null
	        }
	    }

	    fun streamRailLineStatsAgainstLive(
	        documentPath: String,
	        sessionInfo: SessionInformation,
	        targetVersionIndexes: List<Int>,
	        boxFilter: Set<String> = emptySet(),
	        propertyFilter: Set<String> = emptySet(),
	        shouldContinue: () -> Boolean = { true },
	        emit: (Int, HistoryLineStats) -> Unit
	    ) {
	        val savingContext = SavingContext()
	        val currentStates = liveStatesForRailStats(documentPath, sessionInfo, savingContext)
	        targetVersionIndexes.forEach { versionIndex ->
	            if (!shouldContinue()) return
	            val stats = lineStatsAgainstLive(
	                documentPath,
	                sessionInfo,
	                versionIndex,
	                boxFilter,
	                propertyFilter,
	                savingContext,
	                currentStates
	            ) ?: HistoryLineStats()
	            emit(versionIndex, stats)
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
        target: HistoryBoxState?,
        propertyFilter: Set<String> = emptySet()
    ): HistoryBoxChange? {
        if (current == null && target == null) return null
        fun propertyAllowed(property: String) = propertyFilter.isEmpty() || property in propertyFilter

        val status = when {
            current == null -> "target-only"
            target == null -> "present-only"
            else -> "changed"
        }
        val name = target?.name ?: current?.name ?: uid.take(8)
        val properties = when {
            current == null -> target!!.properties.keys.mapNotNull {
                if (!propertyAllowed(it)) return@mapNotNull null
                propertyChange(it, false, null, true, target.properties[it])
            }
            target == null -> current.properties.keys.mapNotNull {
                if (!propertyAllowed(it)) return@mapNotNull null
                propertyChange(it, true, current.properties[it], false, null)
            }
            else -> {
                val names = (current.properties.keys + target.properties.keys).toSortedSet()
                names.mapNotNull { property ->
                    if (!propertyAllowed(property)) return@mapNotNull null
                    val hasCurrent = current.properties.containsKey(property)
                    val hasTarget = target.properties.containsKey(property)
                    val currentValue = current.properties[property]
                    val targetValue = target.properties[property]
                    if (hasCurrent && hasTarget && canonicalValue(currentValue) == canonicalValue(targetValue)) {
                        null
                    } else {
                        propertyChange(
                            property,
                            hasCurrent,
                            if (hasCurrent) currentValue else null,
                            hasTarget,
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
        hasCurrent: Boolean,
        currentValue: Any?,
        hasTarget: Boolean,
        targetValue: Any?
    ): HistoryPropertyChange? {
        if (!interestingHistoryPropertyChange(property, hasCurrent, currentValue, hasTarget, targetValue)) return null
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
        val currentText = if (kind == "text") fullTextForDiff(currentValue) else ""
        val targetText = if (kind == "text") fullTextForDiff(targetValue) else ""

        return HistoryPropertyChange(
            property = property,
            status = status,
            kind = kind,
            currentPreview = previewValue(currentValue),
            targetPreview = previewValue(targetValue),
            currentText = currentText,
            targetText = targetText,
            lineStats = lineStats
        )
    }

    private fun interestingHistoryPropertyChange(
        property: String,
        hasCurrent: Boolean,
        currentValue: Any?,
        hasTarget: Boolean,
        targetValue: Any?
    ): Boolean {
        if (isScrubInternalProperty(property)) return false
        if (hasCurrent && !hasTarget) return false
        if (isHistoryMissingLike(currentValue) && isHistoryMissingLike(targetValue)) return false

        val defaultValue = missingDefaultValues[property]
        if (defaultValue != null) {
            if (hasCurrent && !hasTarget && canonicalValue(currentValue) == canonicalValue(defaultValue)) return false
            if (!hasCurrent && hasTarget && canonicalValue(targetValue) == canonicalValue(defaultValue)) return false
        }
        return true
    }

    private fun isHistoryMissingLike(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isBlank()
        is Collection<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        else -> false
    }

    private fun isScrubInternalProperty(property: String): Boolean {
        if (property in hiddenScrubProperties) return true
        return hiddenScrubPropertyPatterns.any { it.matches(property) }
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

    private fun fullTextForDiff(value: Any?): String {
        val text = valueToText(value)
        return if (text.length <= fullTextDiffLimit) text else ""
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
        fun canTouch(name: String) =
            isPersistent(name) &&
                !isScrubInternalProperty(name) &&
                (includeTopology || name !in topologyProperties)

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

    fun computeDiffStat(documentPath: String, previous: VersionInfo, current: VersionInfo): HistoryDiffStats {
        return computeVersionDiff(documentPath, previous, current)
    }

    fun searchVersionTextChanges(documentPath: String, query: String): List<Int> {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return listVersions(documentPath).map { it.index }

        val versions = listVersions(documentPath)
        if (versions.size < 2) return emptyList()

        val matches = mutableListOf<Int>()
        for (i in 1 until versions.size) {
            try {
                if (versionTextChangeMatches(documentPath, versions[i - 1], versions[i], needle)) {
                    matches += versions[i].index
                }
            } catch (e: Exception) {
                lprintln("warning,history", "Failed to search version ${versions[i].index}: ${e.message}")
            }
        }
        return matches
    }

    fun searchVersionTextChangesAgainstLive(
        documentPath: String,
        sessionInfo: SessionInformation,
        query: String
    ): List<Int> {
        val needle = query.trim().lowercase()
        val versions = listVersions(documentPath)
        if (needle.isBlank()) return versions.map { it.index }
        if (versions.isEmpty()) return emptyList()

        val matches = mutableListOf<Int>()
        val savingContext = SavingContext()
        val liveSnapshot = liveSnapshot(documentPath, sessionInfo, savingContext)
        val liveProps = extractTextProperties(
            liveSnapshot.transfers,
            liveSnapshot.directory,
            savingContext,
            liveSnapshot.preferInlineFiles
        )

        versions.forEach { version ->
            try {
                val versionProps = loadTextualBoxProps(version, documentPath)
                if (textChangeMapsMatch(versionProps, liveProps, needle)) {
                    matches += version.index
                }
            } catch (e: Exception) {
                lprintln("warning,history", "Failed to search live diff for version ${version.index}: ${e.message}")
            }
        }
        return matches
    }

    fun blameLineHistory(
        documentPath: String,
        uid: String,
        property: String,
        lineNumber: Int,
        lineText: String = "",
        propertyText: String = ""
    ): List<Int> {
        val versions = listVersions(documentPath)
        if (versions.size < 2 || uid.isBlank() || property.isBlank()) return emptyList()

        val textsByVersion = versions.associate { version ->
            version.index to loadBoxPropertyText(version, documentPath, uid, property)
        }
        val latest = versions.last()
        val latestText = textsByVersion[latest.index] ?: ""
        val startingText = propertyText
        val latestLines = splitHistoryLines(startingText)
        var trackedLine = (lineNumber - 1).coerceAtLeast(0)
        val exactLineText = lineText.replace("\r\n", "\n").replace("\r", "\n")
        if (
            exactLineText.isNotEmpty() &&
            latestLines.getOrNull(trackedLine) != exactLineText
        ) {
            nearestLineIndex(latestLines, exactLineText, trackedLine)?.let {
                trackedLine = it
            }
        }

        if (startingText != latestText) {
            trackedLine = traceLineBack(latestText, startingText, trackedLine)
                .previousLine
                ?: return emptyList()
        }

        val matches = mutableListOf<Int>()
        for (i in versions.lastIndex downTo 1) {
            val currentText = textsByVersion[versions[i].index] ?: ""
            val previousText = textsByVersion[versions[i - 1].index] ?: ""
            val trace = traceLineBack(previousText, currentText, trackedLine)
            if (trace.touched) {
                matches += versions[i].index
            }
            trackedLine = trace.previousLine ?: break
        }
        return matches.asReversed()
    }

    fun selectionChangeVersions(
        documentPath: String,
        uid: String,
        property: String,
        startLine: Int,
        endLine: Int,
        selectedText: String = "",
        propertyText: String = ""
    ): List<Int> {
        val versions = listVersions(documentPath)
        if (versions.size < 2 || uid.isBlank() || property.isBlank() || isScrubInternalProperty(property)) {
            return emptyList()
        }

        val needles = selectionNeedles(
            selectedText = selectedText,
            propertyText = propertyText,
            startLine = startLine,
            endLine = endLine
        )
        if (needles.isEmpty()) return emptyList()

        val matches = mutableListOf<Int>()
        for (i in 1 until versions.size) {
            try {
                val previousText = loadBoxPropertyText(versions[i - 1], documentPath, uid, property)
                val currentText = loadBoxPropertyText(versions[i], documentPath, uid, property)
                if (previousText != currentText && changedLinesContainAny(previousText, currentText, needles)) {
                    matches += versions[i].index
                }
            } catch (e: Exception) {
                lprintln("warning,history", "Failed to inspect selection history for version ${versions[i].index}: ${e.message}")
            }
        }
        return matches
    }

    fun propertyTextAtVersion(
        documentPath: String,
        versionIndex: Int,
        uid: String,
        property: String
    ): String? {
        val version = listVersions(documentPath).firstOrNull { it.index == versionIndex } ?: return null
        return loadBoxPropertyText(version, documentPath, uid, property)
    }

    private fun selectionNeedles(
        selectedText: String,
        propertyText: String,
        startLine: Int,
        endLine: Int
    ): List<String> {
        val explicit = splitHistoryLines(selectedText.replace("\r\n", "\n").replace("\r", "\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (explicit.isNotEmpty()) return explicit

        val lines = splitHistoryLines(propertyText.replace("\r\n", "\n").replace("\r", "\n"))
        if (lines.isEmpty()) return emptyList()
        val first = (minOf(startLine, endLine) - 1).coerceIn(0, lines.lastIndex)
        val last = (maxOf(startLine, endLine) - 1).coerceIn(first, lines.lastIndex)
        return lines.subList(first, last + 1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private data class LineTrace(val previousLine: Int?, val touched: Boolean)

    private fun splitHistoryLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split('\n')

    private fun traceLineBack(oldText: String, newText: String, newLineIndex: Int): LineTrace {
        val oldLines = splitHistoryLines(oldText)
        val newLines = splitHistoryLines(newText)
        if (newLineIndex !in newLines.indices) return LineTrace(null, false)

        if (oldLines.size * newLines.size > 120000) {
            val line = newLines[newLineIndex]
            if (oldLines.getOrNull(newLineIndex) == line) {
                return LineTrace(newLineIndex, false)
            }
            return LineTrace(nearestLineIndex(oldLines, line, newLineIndex), true)
        }

        val pairs = lcsPairs(oldLines, newLines)
        val aligned = pairs.firstOrNull { it.second == newLineIndex }
        if (aligned != null) return LineTrace(aligned.first, false)

        val before = pairs.lastOrNull { it.second < newLineIndex }
        val after = pairs.firstOrNull { it.second > newLineIndex }
        val previousLine = when {
            before != null && after != null -> {
                val oldStart = before.first + 1
                val oldEnd = after.first
                if (oldStart < oldEnd) {
                    (oldStart + (newLineIndex - before.second - 1)).coerceIn(oldStart, oldEnd - 1)
                } else {
                    null
                }
            }
            before != null -> (before.first + (newLineIndex - before.second)).takeIf { it in oldLines.indices }
            after != null -> (after.first - (after.second - newLineIndex)).takeIf { it in oldLines.indices }
            oldLines.isNotEmpty() -> newLineIndex.coerceAtMost(oldLines.lastIndex)
            else -> null
        }
        return LineTrace(previousLine, true)
    }

    private fun nearestLineIndex(lines: List<String>, line: String, preferredIndex: Int): Int? {
        var bestIndex: Int? = null
        var bestDistance = Int.MAX_VALUE
        lines.forEachIndexed { index, candidate ->
            if (candidate == line) {
                val distance = kotlin.math.abs(index - preferredIndex)
                if (distance < bestDistance) {
                    bestIndex = index
                    bestDistance = distance
                }
            }
        }
        return bestIndex
    }

    private fun lcsPairs(oldLines: List<String>, newLines: List<String>): List<Pair<Int, Int>> {
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

        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = oldLines.size
        var j = newLines.size
        while (i > 0 && j > 0) {
            when {
                oldLines[i - 1] == newLines[j - 1] -> {
                    pairs += (i - 1) to (j - 1)
                    i--
                    j--
                }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        return pairs.asReversed()
    }

    private fun loadBoxPropertyText(
        version: VersionInfo,
        documentPath: String,
        uid: String,
        property: String
    ): String {
        if (isScrubInternalProperty(property)) return ""
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
        val raw = transfers.firstOrNull { it.id == uid }?.properties?.get(property) ?: return ""
        val resolved = savingContext.resolveStoredValue(
            raw,
            versionDir,
            preferInlineFiles = preferInlineFiles
        )
        return resolvedHistoryText(resolved)
    }

    private fun resolvedHistoryText(resolved: Any?): String = when (resolved) {
        null -> ""
        is String -> resolved
        is Number, is Boolean -> resolved.toString()
        is List<*> -> resolved.joinToString("\n") { it?.toString() ?: "" }
        is Set<*> -> resolved.joinToString("\n") { it?.toString() ?: "" }
        is Map<*, *> -> resolved.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        else -> resolved.toString()
    }

    private fun versionTextChangeMatches(
        documentPath: String,
        previous: VersionInfo,
        current: VersionInfo,
        needle: String
    ): Boolean {
        val prevProps = loadTextualBoxProps(previous, documentPath)
        val currProps = loadTextualBoxProps(current, documentPath)

        return textChangeMapsMatch(prevProps, currProps, needle)
    }

    private fun textChangeMapsMatch(
        prevProps: Map<String, Map<String, String>>,
        currProps: Map<String, Map<String, String>>,
        needle: String
    ): Boolean {
        val ids = (prevProps.keys + currProps.keys).toSet()
        ids.forEach { id ->
            val oldProps = prevProps[id].orEmpty()
            val newProps = currProps[id].orEmpty()
            val propNames = (oldProps.keys + newProps.keys)
                .filter { it in diffTextProperties && !isScrubInternalProperty(it) }
                .toSet()
            propNames.forEach { name ->
                val oldText = oldProps[name] ?: ""
                val newText = newProps[name] ?: ""
                if (oldText != newText && changedLinesContain(oldText, newText, needle)) return true
            }
        }

        return false
    }

    private fun changedLinesContain(oldText: String, newText: String, needle: String): Boolean {
        fun unmatchedLinesContain(source: List<String>, other: List<String>): Boolean {
            val remaining = other.groupingBy { it }.eachCount().toMutableMap()
            source.forEach { line ->
                val available = remaining[line] ?: 0
                if (available > 0) {
                    remaining[line] = available - 1
                } else if (line.lowercase().contains(needle)) {
                    return true
                }
            }
            return false
        }

        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')
        return unmatchedLinesContain(newLines, oldLines) || unmatchedLinesContain(oldLines, newLines)
    }

    private fun changedLinesContainAny(oldText: String, newText: String, needles: List<String>): Boolean {
        val normalizedNeedles = needles.map { it.lowercase() }.filter { it.isNotBlank() }
        if (normalizedNeedles.isEmpty()) return false
        return normalizedNeedles.any { changedLinesContain(oldText, newText, it) }
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
        val changeKinds = computePersistentChangeKinds(documentPath, previous, current)
	        val changedBoxIds = changeKinds.changedIds
	        val boxLineStats = linkedMapOf<String, HistoryLineStats>()

	        fun addBoxLineStats(id: String, stats: HistoryLineStats) {
	            if (stats.added == 0 && stats.removed == 0 && stats.moved == 0 && stats.changed == 0) return
	            val existing = boxLineStats[id] ?: HistoryLineStats()
	            boxLineStats[id] = HistoryLineStats(
	                added = existing.added + stats.added,
	                removed = existing.removed + stats.removed,
	                moved = existing.moved + stats.moved,
	                changed = existing.changed + stats.changed
	            )
	        }

	        var linesAddedFromNew = 0
	        addedIds.forEach { id ->
	            val addedLines = sumLines(currProps[id])
	            linesAddedFromNew += addedLines
	            addBoxLineStats(id, HistoryLineStats(added = addedLines))
	            if (!currProps[id]?.get("code").isNullOrEmpty()) {
	                changedCodeBoxes += displayNameForChangedBox(id, currProps[id], prevProps[id])
	            }
	        }

	        var linesRemovedFromDeleted = 0
	        deletedIds.forEach { id ->
	            val removedLines = sumLines(prevProps[id])
	            linesRemovedFromDeleted += removedLines
	            addBoxLineStats(id, HistoryLineStats(removed = removedLines))
	            if (!prevProps[id]?.get("code").isNullOrEmpty()) {
	                changedCodeBoxes += displayNameForChangedBox(id, currProps[id], prevProps[id])
	            }
        }

        var linesAddedFromChanges = 0
        var linesRemovedFromChanges = 0
        var linesChangedFromChanges = 0
        sharedIds.forEach { id ->
            val oldProps = prevProps[id].orEmpty()
            val newProps = currProps[id].orEmpty()
            if ((oldProps["code"] ?: "") != (newProps["code"] ?: "")) {
                changedCodeBoxes += displayNameForChangedBox(id, newProps, oldProps)
            }
            val propNames = (oldProps.keys + newProps.keys)
                .toSet()
                .filter { it in diffTextProperties && !isScrubInternalProperty(it) }
            propNames.forEach { name ->
                val oldText = oldProps[name] ?: ""
                val newText = newProps[name] ?: ""
                val stats = computeDetailedLineStats(oldText, newText)
	                linesAddedFromChanges += stats.added
	                linesRemovedFromChanges += stats.removed
	                linesChangedFromChanges += stats.changed
	                addBoxLineStats(id, stats)
	            }
	        }

        val totalAdded = linesAddedFromNew + linesAddedFromChanges
        val totalRemoved = linesRemovedFromDeleted + linesRemovedFromChanges

        return HistoryDiffStats(
            boxesAdded = addedIds.size,
            boxesDeleted = deletedIds.size,
            boxesEdited = changeKinds.editedBoxes,
            boxesMoved = changeKinds.movedBoxes,
            linesAddedFromNew = linesAddedFromNew,
            linesRemovedFromDeleted = linesRemovedFromDeleted,
            linesAddedFromChanges = linesAddedFromChanges,
            linesRemovedFromChanges = linesRemovedFromChanges,
            totalLinesAdded = totalAdded,
	            totalLinesRemoved = totalRemoved,
	            totalLinesChanged = linesChangedFromChanges,
	            changedCodeBoxes = changedCodeBoxes.distinct(),
	            changedBoxIds = changedBoxIds.toList().sorted(),
	            boxLineStats = boxLineStats.entries.map { (uid, stats) ->
	                HistoryBoxLineStats(
	                    uid = uid,
	                    added = stats.added,
	                    removed = stats.removed,
	                    moved = stats.moved,
	                    changed = stats.changed
	                )
	            }
	        )
	    }

    private fun computeChangedPersistentBoxIds(
        documentPath: String,
        previous: VersionInfo,
        current: VersionInfo
    ): Set<String> = computePersistentChangeKinds(documentPath, previous, current).changedIds

    private fun computePersistentChangeKinds(
        documentPath: String,
        previous: VersionInfo,
        current: VersionInfo
    ): PersistentChangeKinds {
        return try {
            val savingContext = SavingContext()
            val previousStates = snapshotStates(loadVersionSnapshot(previous, documentPath, savingContext), savingContext)
            val currentStates = snapshotStates(loadVersionSnapshot(current, documentPath, savingContext), savingContext)
            val changedIds = linkedSetOf<String>()
            var editedBoxes = 0
            var movedBoxes = 0
            (previousStates.keys + currentStates.keys).forEach { id ->
                val oldState = previousStates[id]
                val newState = currentStates[id]
                val oldProps = oldState?.let(::comparablePersistentProperties).orEmpty()
                val newProps = newState?.let(::comparablePersistentProperties).orEmpty()
                if (oldState != null && newState != null && oldProps == newProps) return@forEach

                changedIds += id
                val frameMoved = oldState != null &&
                    newState != null &&
                    oldProps["frame"] != newProps["frame"]
                val nonFrameChanged = oldState == null ||
                    newState == null ||
                    oldProps.filterKeys { it != "frame" } != newProps.filterKeys { it != "frame" }
                if (frameMoved) movedBoxes++
                if (nonFrameChanged) editedBoxes++
            }
            PersistentChangeKinds(
                changedIds = changedIds,
                editedBoxes = editedBoxes,
                movedBoxes = movedBoxes
            )
        } catch (e: Exception) {
            lprintln("warning,history", "Failed to compute changed box ids ${previous.index} -> ${current.index}: ${e.message}")
            PersistentChangeKinds()
        }
    }

    private fun comparablePersistentProperties(state: HistoryBoxState): Map<String, String> {
        return state.properties
            .filterKeys { !isScrubInternalProperty(it) }
            .filter { (name, value) ->
                val defaultValue = missingDefaultValues[name]
                defaultValue == null || canonicalValue(value) != canonicalValue(defaultValue)
            }
            .mapValues { (_, value) -> canonicalValue(value) }
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
                if (name !in diffTextProperties || isScrubInternalProperty(name)) return@forEach
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
        synchronized(versionCacheLock) {
            versionCache.remove(documentPath)
        }
    }
}

private val historyStatsJobs = ConcurrentHashMap<String, Job>()
private val historyRailStatsJobs = ConcurrentHashMap<String, Job>()

private fun historyVersionDtos(documentPath: String, indexes: Set<Int>? = null): List<HistoryVersion> {
    val names = HistoryManager.getVersionNames(documentPath)
    val activityByVersion = HistoryMetrics.activityByVersion(documentPath)
    return HistoryManager.listVersions(documentPath)
        .filter { indexes == null || it.index in indexes }
        .map { v ->
            HistoryVersion(
                index = v.index,
                filename = v.file.name,
                timestamp = v.timestamp,
                boxCount = v.boxCount,
                byteSize = v.byteSize,
                boxIds = v.boxIds.toList().sorted(),
                message = names[v.index] ?: if (v.file.name == "metadata.edn") "Current" else "",
                diffStats = null,
                activityStats = activityByVersion[v.index]
            )
        }
}

/**
 * Install RPC handlers for document history operations
 */
@RunsAt(installHandlers)
@OptIn(DelicateCoroutinesApi::class)
fun installHistoryHandlers() {
    handlers["list-history-versions"] = mutableSetOf({ message, reply ->
        val info = thisSessionInfo.get()
        val session = thisSession.get()
        val versions = HistoryManager.listVersions(info.filename)
        val names = HistoryManager.getVersionNames(info.filename)
        val activityByVersion = HistoryMetrics.activityByVersion(info.filename)

        val historyVersions = versions.map { v ->
            HistoryVersion(
                index = v.index,
                filename = v.file.name,
                timestamp = v.timestamp,
                boxCount = v.boxCount,
                byteSize = v.byteSize,
                boxIds = v.boxIds.toList().sorted(),
                message = names[v.index] ?: if (v.file.name == "metadata.edn") "Current" else "",
                diffStats = null,
                activityStats = activityByVersion[v.index]
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

        val total = (versions.size - 1).coerceAtLeast(0)
        if (total == 0) {
            reply(
                Message("history-version-stats-progress").payload(
                    HistoryVersionStatsProgress(
                        versionIndex = -1,
                        completed = 0,
                        total = 0,
                        done = true
                    )
                )
            )
        } else {
            reply(
                Message("history-version-stats-progress").payload(
                    HistoryVersionStatsProgress(
                        versionIndex = -1,
                        completed = 0,
                        total = total,
                        done = false
                    )
                )
            )
            val statsKey = "${System.identityHashCode(session)}:${info.filename}"
            historyStatsJobs.remove(statsKey)?.cancel()
            val documentPath = info.filename
            val versionSnapshot = versions.toList()
            val job = GlobalScope.launch(Dispatchers.IO) {
                try {
                    for (i in 1 until versionSnapshot.size) {
                        if (!isActive) return@launch
                        val boxIds = try {
                            HistoryManager.boxIdsForVersion(documentPath, versionSnapshot[i])
                        } catch (e: Exception) {
                            lprintln("warning,history", "Failed to stream box ids for version $i: ${e.message}")
                            emptySet()
                        }
                        if (!isActive) return@launch
                        val stats = try {
                            HistoryManager.computeDiffStat(documentPath, versionSnapshot[i - 1], versionSnapshot[i])
                        } catch (e: Exception) {
                            lprintln("warning,history", "Failed to stream diff stat ${i - 1} -> $i: ${e.message}")
                            null
                        }
                        if (!isActive) return@launch
                        if (stats != null && i == versionSnapshot.lastIndex) {
                            HistoryMetrics.recordEditStats(documentPath, versionSnapshot[i].index, stats)
                        }
                        if (!isActive) return@launch
                        reply(
                            Message("history-version-stats-progress").payload(
                                HistoryVersionStatsProgress(
                                    versionIndex = versionSnapshot[i].index,
                                    completed = i,
                                    total = total,
                                    diffStats = stats,
                                    boxCount = boxIds.size,
                                    boxIds = boxIds.toList().sorted(),
                                    done = i == total
                                )
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    lprintln("warning,history", "History stats stream failed for $documentPath: ${t.message}")
                    reply(
                        Message("history-version-stats-progress").payload(
                            HistoryVersionStatsProgress(
                                versionIndex = -1,
                                completed = total,
                                total = total,
                                done = true
                            )
                        )
                    )
                } finally {
                    historyStatsJobs.remove(statsKey, coroutineContext[Job])
                }
            }
            historyStatsJobs[statsKey] = job
        }
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
        val diff = if (req.basis == "previous") {
            HistoryManager.diffVersionAgainstPrevious(
                info.filename,
                req.versionIndex,
                req.boxIds.toSet(),
                req.properties.toSet()
            )
        } else {
            HistoryManager.diffVersionAgainstLive(
                info.filename,
                info,
                req.versionIndex,
                req.boxIds.toSet(),
                req.properties.toSet()
            )
        }

        if (diff != null) {
            reply(Message("history-version-diff").payload(diff))
        } else {
            reply(Message("history-version-diff-error").payload(mapOf("error" to "Failed to diff version")))
        }

	        Handled.yes
	    })

	    handlers["history-rail-line-stats"] = mutableSetOf(railLineStatsHandler@{ message, reply ->
	        val req = Json.decodeFromJsonElement<HistoryRailLineStatsRequest>(message.payload!!)
	        val info = thisSessionInfo.get()
	        val documentPath = info.filename
	        val versions = HistoryManager.listVersions(documentPath)
	        val requestedIndexes = req.versionIndexes.ifEmpty { versions.map { it.index } }
	        val validIndexes = requestedIndexes.toSet()
	        val orderedIndexes = versions.map { it.index }.filter { it in validIndexes }
	        val boxFilter = req.boxIds.toSet()
	        val sortedFilter = boxFilter.toList().sorted()
	        val propertyFilter = req.properties.toSet()
	        val sortedProperties = propertyFilter.toList().sorted()
	        val total = orderedIndexes.size
	        val basis = if (req.basis == "previous") "previous" else "current"
	        val statsKey = "${System.identityHashCode(thisSession.get())}:$documentPath:rail"

	        historyRailStatsJobs.remove(statsKey)?.cancel()
	        if (total == 0 || boxFilter.isEmpty()) {
	            reply(
	                Message("history-rail-line-stats-progress").payload(
	                    HistoryRailLineStatsProgress(
	                        basis = basis,
	                        versionIndex = -1,
	                        completed = 0,
	                        total = 0,
	                        boxFilter = sortedFilter,
	                        properties = sortedProperties,
	                        done = true
	                    )
	                )
	            )
	            return@railLineStatsHandler Handled.yes
	        }

	        reply(
	            Message("history-rail-line-stats-progress").payload(
	                HistoryRailLineStatsProgress(
	                    basis = basis,
	                    versionIndex = -1,
	                    completed = 0,
	                    total = total,
	                    boxFilter = sortedFilter,
	                    properties = sortedProperties,
	                    done = false
	                )
	            )
	        )

	        val job = GlobalScope.launch(Dispatchers.IO) {
	            try {
	                var completed = 0
	                if (basis == "current") {
	                    HistoryManager.streamRailLineStatsAgainstLive(
	                        documentPath,
	                        info,
	                        orderedIndexes,
	                        boxFilter,
	                        propertyFilter,
	                        shouldContinue = { isActive }
	                    ) { versionIndex, stats ->
	                        completed++
	                        reply(
	                            Message("history-rail-line-stats-progress").payload(
	                                HistoryRailLineStatsProgress(
	                                    basis = basis,
	                                    versionIndex = versionIndex,
	                                    completed = completed,
	                                    total = total,
	                                    boxFilter = sortedFilter,
	                                    properties = sortedProperties,
	                                    lineStats = stats,
	                                    done = completed == total
	                                )
	                            )
	                        )
	                    }
	                } else {
	                    orderedIndexes.forEach { versionIndex ->
	                        if (!isActive) return@launch
	                        val stats = HistoryManager.diffVersionAgainstPrevious(
	                            documentPath,
	                            versionIndex,
	                            boxFilter,
	                            propertyFilter
	                        )?.lineStats ?: HistoryLineStats()
	                        completed++
	                        reply(
	                            Message("history-rail-line-stats-progress").payload(
	                                HistoryRailLineStatsProgress(
	                                    basis = basis,
	                                    versionIndex = versionIndex,
	                                    completed = completed,
	                                    total = total,
	                                    boxFilter = sortedFilter,
	                                    properties = sortedProperties,
	                                    lineStats = stats,
	                                    done = completed == total
	                                )
	                            )
	                        )
	                    }
	                }
	            } catch (e: CancellationException) {
	                throw e
	            } catch (e: Exception) {
	                lprintln("warning,history", "History rail line stats stream failed for $documentPath: ${e.message}")
	                reply(
	                    Message("history-rail-line-stats-progress").payload(
	                        HistoryRailLineStatsProgress(
	                            basis = basis,
	                            versionIndex = -1,
	                            completed = total,
	                            total = total,
	                            boxFilter = sortedFilter,
	                            properties = sortedProperties,
	                            done = true
	                        )
	                    )
	                )
	            } finally {
	                historyRailStatsJobs.remove(statsKey, coroutineContext[Job])
	            }
	        }
	        historyRailStatsJobs[statsKey] = job

	        Handled.yes
	    })

	    handlers["search-history-versions"] = mutableSetOf({ message, reply ->
        val req = Json.decodeFromJsonElement<HistorySearchRequest>(message.payload!!)
        val info = thisSessionInfo.get()
        val basis = if (req.basis == "current") "current" else "previous"
        val matches = if (basis == "current") {
            HistoryManager.searchVersionTextChangesAgainstLive(info.filename, info, req.query)
        } else {
            HistoryManager.searchVersionTextChanges(info.filename, req.query)
        }
        reply(
            Message("history-search-results").payload(
                HistorySearchResult(
                    query = req.query,
                    basis = basis,
                    versionIndexes = matches
                )
            )
        )
        Handled.yes
    })

    handlers["store-history-screenshot"] = mutableSetOf({ message, reply ->
        val req = Json.decodeFromJsonElement<HistoryScreenshotStoreRequest>(message.payload!!)
        val info = thisSessionInfo.get()
        val versionIndex = if (req.versionIndex >= 0) {
            req.versionIndex
        } else {
            HistoryMetrics.currentVersionIndex(info.filename)
        }
        val documentPath = info.filename
        GlobalScope.launch(Runloop.miscPool.asCoroutineDispatcher()) {
            val ref = HistoryMetrics.storeScreenshotDataUrl(
                documentPath = documentPath,
                channel = req.channel,
                dataUrl = req.dataUrl,
                versionIndex = versionIndex,
                width = req.width,
                height = req.height
            )
            if (ref != null) {
                reply(Message("store-history-screenshot-success").payload(HistoryScreenshotAdded(versionIndex, ref)))
            } else {
                reply(Message("store-history-screenshot-error").payload(mapOf("error" to "Failed to store screenshot")))
            }
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

    handlers["blame-history-line"] = mutableSetOf({ message, reply ->
        val req = Json.decodeFromJsonElement<HistoryLineBlameRequest>(message.payload!!)
        val info = thisSessionInfo.get()
        val matches = HistoryManager.blameLineHistory(
            info.filename,
            req.uid,
            req.property,
            req.lineNumber,
            req.lineText,
            req.propertyText
        )
        reply(
            Message("history-line-blame-results").payload(
                HistoryLineBlameResult(
                    uid = req.uid,
                    property = req.property,
                    lineNumber = req.lineNumber,
                    lineText = req.lineText,
                    versionIndexes = matches
                )
            )
        )

        Handled.yes
    })

    handlers["history-selection-versions"] = mutableSetOf({ message, reply ->
        val req = Json.decodeFromJsonElement<HistorySelectionVersionsRequest>(message.payload!!)
        val info = thisSessionInfo.get()
        val matches = HistoryManager.selectionChangeVersions(
            info.filename,
            req.uid,
            req.property,
            req.startLine,
            req.endLine,
            req.selectedText,
            req.propertyText
        ).toSet()
        reply(
            Message("history-selection-versions-result").payload(
                HistorySelectionVersionsResult(
                    uid = req.uid,
                    property = req.property,
                    startLine = req.startLine,
                    endLine = req.endLine,
                    versions = historyVersionDtos(info.filename, matches)
                )
            )
        )

        Handled.yes
    })

    handlers["history-property-version"] = mutableSetOf({ message, reply ->
        val req = Json.decodeFromJsonElement<HistoryPropertyVersionRequest>(message.payload!!)
        val info = thisSessionInfo.get()
        val version = historyVersionDtos(info.filename, setOf(req.versionIndex)).firstOrNull()
        val value = HistoryManager.propertyTextAtVersion(
            info.filename,
            req.versionIndex,
            req.uid,
            req.property
        ) ?: ""
        reply(
            Message("history-property-version-result").payload(
                HistoryPropertyVersionResult(
                    uid = req.uid,
                    property = req.property,
                    versionIndex = req.versionIndex,
                    version = version,
                    value = value
                )
            )
        )

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
