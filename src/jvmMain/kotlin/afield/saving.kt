@file:OptIn(ExperimentalSerializationApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package afield

import afield.ai.core.Ai4AutoBoxNamer
import afield.logger.SaveReloadLog
import afield.logger.lprintln
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class Box_transfer(val id: String, val properties: Map<String, Pair<String, JsonElement>>)

data class BoxTransferEdn(val id: String, val properties: Map<String, Any?>)

private data class BoxRef(val id: String)
private data class FileRef(val path: String)
private data class FileInline(val path: String, val inline: String)

class SavingContext {

    private object SkipRef

    private data class PartialSaveBlock(
        val existingCount: Int,
        val snapshotCount: Int,
        val missingCount: Int,
        val missingPreview: String
    )

    private data class MarkdownBlockProperty(
        val name: String,
        val language: String,
        val text: String,
        val path: String?
    )

    private data class ExtractedNarrative(
        val property: String,
        val text: String,
        val tags: List<String>
    )

    fun exportEdnPackage(boxes: Set<Box>, directory: String): String {
        val transfers = toTransferInline(boxes, directory)
        val payloadBoxes = transfers.map { transfer ->
            linkedMapOf<String, Any?>(
                "id" to transfer.id,
                "properties" to transfer.properties
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "version" to 2,
            "boxes" to payloadBoxes,
            "persistentProperties" to Box.ioPersistentProperties.toList()
        )
        return Edn.write(payload, ednCustom)
    }

    fun exportDocumentMarkdown(boxes: Set<Box>, directory: String): String {
        val transfers = toTransferInline(boxes, directory)
        val snapshot = SaveSnapshot(
            transfers = transfers,
            persistentProperties = Box.ioPersistentProperties.toList(),
            fileWatchManager = null,
            propertyCount = transfers.sumOf { it.properties.size },
            sidecarCount = transfers.sumOf { transfer -> transfer.properties.values.count { it is FileInline } },
            sidecarChars = transfers.sumOf { transfer ->
                transfer.properties.values.sumOf { (it as? FileInline)?.inline?.length?.toLong() ?: 0L }
            }
        )
        return buildDocumentMarkdown(snapshot, File(directory).name)
    }

    fun listTemplates(dir: String): List<String> {
        val directory = File(dir)
        if (!directory.exists()) return emptyList()

        val files = directory.listFiles() ?: return emptyList()
        val result = mutableListOf<String>()

        // Add subdirectories containing metadata files
        files.filter { file ->
            file.isDirectory && (File(file, METADATA_EDN).exists() || File(file, METADATA_JSON).exists())
        }.mapTo(result) { it.name }

        // Add standalone metadata.edn files (but not the main metadata.edn)
        files.filter { file ->
            file.isFile && file.name.endsWith(".edn") && file.name != METADATA_EDN
        }.mapTo(result) { it.nameWithoutExtension }

        return result
    }

    @OptIn(ExperimentalUuidApi::class)
    fun cloneInto(dir: String): Set<Box> {
        val seen = mutableMapOf<String, String>("root" to "root")
        return load(dir, { id ->
            val next = seen.computeIfAbsent(id) { Uuid.random().toString() }
            next
        }, upgradeOnLoad = false)
    }

    var altered = false

    private fun withoutMetarootTopology(name: String, value: Any?): Any? {
        if (name != "parents" && name != "children") return value
        return when (value) {
            is Set<*> -> value.filterNot { it is Box && it.id == METAROOT_ID }.toCollection(LinkedHashSet())
            is List<*> -> value.filterNot { it is Box && it.id == METAROOT_ID }
            else -> value
        }
    }

    /**
     * Serializes each box's persistent properties for save. Only properties whose name matches
     * one of [Box.ioPersistentProperties] (regex-matched) are written; others are dropped.
     *
     * Root-box persistence relies on this path: callers that save (see `allBoxes.add(info.root)`
     * in Server.kt before `SavingContext().save(...)`) explicitly add the root box to the save set,
     * so its persistent root properties (e.g. "bookmarks", which is listed in
     * [Box.ioPersistentProperties]) are serialized here. On load, `box("root")` resolves back to
     * SessionInformation.root (Boxes.kt) and `setPropertyRaw` restores the property onto it.
     */
    fun toTransfer(boxes: Set<Box>, directory: String): List<BoxTransferEdn> {
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        return boxes.map { box ->
            val properties = LinkedHashMap<String, Any?>()
            box.properties.forEach { (name, rawValue) ->
                if (!patterns.any { name.matches(it) }) return@forEach
                val value = withoutMetarootTopology(name, rawValue)
                if (value == null) return@forEach

                val shouldUseSidecar = box.id != "root" && Box.ioSeparateProperties.containsKey(name)
                if (shouldUseSidecar) {
                    val extension = Box.ioSeparateProperties[name]!!
                    val contents = value as? String ?: value.toString()
                    properties[name] = toFile(box.id, name, extension, directory, contents)
                } else {
                    val normalized = normalizeValue(value)
                    if (normalized != null) {
                        properties[name] = normalized
                    }
                }
            }
            BoxTransferEdn(box.id, properties)
        }
    }

    private fun toTransferInline(boxes: Set<Box>, directory: String): List<BoxTransferEdn> {
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        return boxes.map { box ->
            val properties = LinkedHashMap<String, Any?>()
            box.properties.forEach { (name, rawValue) ->
                if (!patterns.any { name.matches(it) }) return@forEach
                val value = withoutMetarootTopology(name, rawValue)
                if (value == null) return@forEach

                val shouldUseSidecar = box.id != "root" && Box.ioSeparateProperties.containsKey(name)
                if (shouldUseSidecar) {
                    val extension = Box.ioSeparateProperties[name]!!
                    val contents = value as? String ?: value.toString()
                    properties[name] = FileInline(filenameFor(box.id, name, extension), contents)
                } else {
                    val normalized = normalizeInlineValue(value, directory)
                    if (normalized != null) {
                        properties[name] = normalized
                    }
                }
            }
            BoxTransferEdn(box.id, properties)
        }
    }

    fun checkDifference(
        p: String,
        contents: String,
        createBackup: Boolean = true,
        maxBackups: Int? = null
    ): Boolean {
        if (!File(p).exists()) {
            File(p).safeSave(contents, createBackup, maxBackups)
            return true
        } else {
            try {
                return File(p).safeSave(contents, createBackup, maxBackups)
            } catch (ex: Exception) {
                File(p).writeText(contents)
                return true
            }
        }
    }

    private fun toFile(
        id: String,
        name: String,
        extension: String,
        directory: String,
        contents: String
    ): FileInline {
        val path = filenameFor(id, name, extension)
        val cd = checkDifference(File(directory, path).path, contents, maxBackups = 10)
        altered = altered || cd
        return FileInline(path, contents)
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        null -> null
        is Rect -> value
        is String -> value
        is Number -> value
        is Boolean -> value
        is Box -> BoxRef(value.id)
        is BoxRef -> value
        is FileRef -> value
        is FileInline -> value
        is List<*> -> value.map { normalizeValue(it) }
        is Set<*> -> value.mapTo(LinkedHashSet()) { normalizeValue(it) }
        is Map<*, *> -> {
            val mapped = LinkedHashMap<Any?, Any?>()
            for ((k, v) in value) {
                mapped[normalizeValue(k)] = normalizeValue(v)
            }
            mapped
        }

        else -> {
            lprintln("warning,save", "unhandled class $value ${value.javaClass}")
            null
        }
    }

    private fun normalizeInlineValue(value: Any?, directory: String): Any? = when (value) {
        null -> null
        is Rect -> value
        is String -> value
        is Number -> value
        is Boolean -> value
        is Box -> BoxRef(value.id)
        is BoxRef -> value
        is FileRef -> {
            val file = File(directory, value.path)
            val inline = if (file.exists()) file.readText() else ""
            FileInline(value.path, inline)
        }

        is FileInline -> value
        is List<*> -> value.map { normalizeInlineValue(it, directory) }
        is Set<*> -> value.mapTo(LinkedHashSet()) { normalizeInlineValue(it, directory) }
        is Map<*, *> -> {
            val mapped = LinkedHashMap<Any?, Any?>()
            for ((k, v) in value) {
                mapped[normalizeInlineValue(k, directory)] = normalizeInlineValue(v, directory)
            }
            mapped
        }

        else -> {
            lprintln("warning,save", "unhandled class $value ${value.javaClass}")
            null
        }
    }

    /**
     * Save boxes to disk, optimized to minimize main thread blocking.
     *
     * This method works in two phases:
     * 1. Snapshot phase (main thread): Quickly capture all box properties into memory structures
     * 2. I/O phase (background thread): Write files, create backups, and perform git operations
     *
     * This approach prevents frame hiccups in VR by moving heavy I/O operations off the main thread.
     */
    fun save(fn: String, boxes: Set<Box>, message: String? = null, onComplete: (() -> Unit)? = null) {
        // Phase 1: Snapshot in main thread - capture all data we need
        val snapshotStartedAt = System.nanoTime()
        val snapshot = snapshotBoxes(boxes, fn)
        val snapshotMs = SaveReloadLog.logTiming(
            "save-snapshot",
            snapshotStartedAt,
            "doc=$fn boxes=${snapshot.transfers.size} properties=${snapshot.propertyCount} sidecars=${snapshot.sidecarCount} sidecarChars=${snapshot.sidecarChars}"
        )
        if (snapshotMs >= 250L) {
            lprintln(
                "warning,save",
                "slow save snapshot doc=$fn ms=$snapshotMs boxes=${snapshot.transfers.size} properties=${snapshot.propertyCount} sidecars=${snapshot.sidecarCount}"
            )
        }

        // Phase 2: Perform I/O operations in background thread
        saveInBackground(snapshot, fn, boxes, message, onComplete, System.nanoTime())
    }

    /**
     * Phase 1: Snapshot all box properties in the main thread.
     * This is a quick operation that just reads properties without any I/O.
     */
    private fun snapshotBoxes(boxes: Set<Box>, directory: String): SaveSnapshot {
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        var propertyCount = 0
        var sidecarCount = 0
        var sidecarChars = 0L
        val boxSnapshots = boxes.map { box ->
            val properties = LinkedHashMap<String, Any?>()
            box.properties.forEach { (name, value) ->
                if (!patterns.any { name.matches(it) }) return@forEach
                if (value == null) return@forEach

                // For separate properties, capture the content now
                val shouldUseSidecar = box.id != "root" && Box.ioSeparateProperties.containsKey(name)
                if (shouldUseSidecar) {
                    val extension = Box.ioSeparateProperties[name]!!
                    val contents = value as? String ?: value.toString()
                    properties[name] = FileInline(filenameFor(box.id, name, extension), contents)
                    propertyCount++
                    sidecarCount++
                    sidecarChars += contents.length.toLong()
                } else {
                    val normalized = normalizeValue(value)
                    if (normalized != null) {
                        properties[name] = normalized
                        propertyCount++
                    }
                }
            }
            BoxTransferEdn(box.id, properties)
        }

        val watchManager = try {
            thisSessionInfo.get()?.fileWatchManager
        } catch (e: Exception) {
            null
        } ?: boxes.firstOrNull { it.isInsideInitialized() }?.inside?.fileWatchManager

        return SaveSnapshot(
            transfers = boxSnapshots,
            persistentProperties = Box.ioPersistentProperties.toList(),
            fileWatchManager = watchManager,
            propertyCount = propertyCount,
            sidecarCount = sidecarCount,
            sidecarChars = sidecarChars
        )
    }

    /**
     * Phase 2: Perform all I/O operations in a background thread.
     * This includes file writing and backups.
     */
    private fun saveInBackground(
        snapshot: SaveSnapshot,
        fn: String,
        boxes: Set<Box>,
        message: String?,
        onComplete: (() -> Unit)?,
        scheduledAtNs: Long
    ) {
        // Launch on background thread pool - using the project's standard miscPool
        GlobalScope.launch(Runloop.miscPool.asCoroutineDispatcher()) {
            val totalStartedAt = System.nanoTime()
            val launchDelayMs = (totalStartedAt - scheduledAtNs) / 1_000_000L
            var root = fn
            var ensureMs = 0L
            var lockWaitMs = 0L
            var guardMs = 0L
            var sidecarMs = 0L
            var ednMs = 0L
            var metadataMs = 0L
            var markdownBuildMs = 0L
            var markdownSaveMs = 0L
            var recorderMs = 0L
            var historyMs = 0L
            var metricsMs = 0L
            var versionIndexMs = 0L
            var screenshotMs = 0L
            var notifyMs = 0L
            var mainScheduleMs = 0L
            var altered = false
            var metadataChanged = false
            var markdownChanged = false
            var changedCount = 0
            var touchedCount = 0
            var versionIndex = -1
            try {
                val ensureStartedAt = System.nanoTime()
                root = ensureDirectory(fn)
                ensureMs = SaveReloadLog.elapsedMillis(ensureStartedAt)
                val lock = saveLocks.computeIfAbsent(File(root).canonicalPath) { Any() }
                val lockStartedAt = System.nanoTime()
                synchronized(lock) {
                    lockWaitMs = SaveReloadLog.elapsedMillis(lockStartedAt)
                    val guardStartedAt = System.nanoTime()
                    val blocked = suspiciousPartialAutosave(root, snapshot, message)
                    guardMs = SaveReloadLog.elapsedMillis(guardStartedAt)
                    blocked?.let {
                        val detail =
                            "doc=$fn boxes=${it.snapshotCount}/${it.existingCount} missing=${it.missingCount} missingPreview=${it.missingPreview}"
                        SaveReloadLog.log("save-blocked-partial", detail)
                        lprintln("error,save", "Blocked suspicious partial autosave: $detail")
                        _send(
                            Message("notify").payload(
                                Notify(
                                    "Blocked suspicious partial autosave: ${it.snapshotCount}/${it.existingCount} boxes. metadata.edn was left untouched.",
                                    "save"
                                )
                            ),
                            document = Regex.escape(fn)
                        )
                        return@launch
                    }

                    val touched = mutableListOf<String>()
                    val alteredFiles = mutableListOf<String>()
                    // Write all files based on the snapshot
                    val sidecarStartedAt = System.nanoTime()
                    snapshot.transfers.forEach { transfer ->
                        transfer.properties.forEach { (name, value) ->
                            if (value is FileInline) {
                                val target = File(root, value.path).path
                                touched.add(target)
                                val cd = checkDifference(target, value.inline, maxBackups = 10)
                                altered = altered || cd
                                if (cd) alteredFiles.add(target)
                            }
                        }
                    }
                    sidecarMs = SaveReloadLog.elapsedMillis(sidecarStartedAt)

                    // Build and write the EDN metadata
                    val ednStartedAt = System.nanoTime()
                    val payloadBoxes = snapshot.transfers.map { transfer ->
                        linkedMapOf<String, Any?>(
                            "id" to transfer.id,
                            "properties" to transfer.properties
                        )
                    }
                    val payload = linkedMapOf<String, Any?>(
                        "version" to 2,
                        "boxes" to payloadBoxes,
                        "persistentProperties" to snapshot.persistentProperties
                    )
                    val ednText = Edn.write(payload, ednCustom)
                    ednMs = SaveReloadLog.elapsedMillis(ednStartedAt)
                    val metadataPath = File(root, METADATA_EDN).path
                    touched.add(metadataPath)
                    val metadataStartedAt = System.nanoTime()
                    metadataChanged = checkDifference(metadataPath, ednText)
                    metadataMs = SaveReloadLog.elapsedMillis(metadataStartedAt)
                    altered = altered || metadataChanged
                    if (metadataChanged) alteredFiles.add(metadataPath)

                    val markdownBuildStartedAt = System.nanoTime()
                    val markdownText = buildDocumentMarkdown(snapshot, File(root).name)
                    markdownBuildMs = SaveReloadLog.elapsedMillis(markdownBuildStartedAt)
                    val markdownPath = File(root, DOCUMENT_MARKDOWN).path
                    touched.add(markdownPath)
                    val markdownSaveStartedAt = System.nanoTime()
                    markdownChanged = checkDifference(markdownPath, markdownText, createBackup = false)
                    markdownSaveMs = SaveReloadLog.elapsedMillis(markdownSaveStartedAt)
                    altered = altered || markdownChanged
                    if (markdownChanged) alteredFiles.add(markdownPath)
                    touchedCount = touched.size
                    changedCount = alteredFiles.size

                    if (altered) {
                        val recorderStartedAt = System.nanoTime()
                        if (metadataChanged) {
                            FlightRecorder.checkpointOnMetadataChange(fn)
                        }
                        OperationTranscript.checkpoint(fn)
                        recorderMs = SaveReloadLog.elapsedMillis(recorderStartedAt)
                    }

                    SaveReloadLog.log(
                        "save",
                        "doc=$fn altered=$altered touched=${touched.size} changed=${alteredFiles.size} message=${message ?: ""} files=[${
                            touched.joinToString(
                                ","
                            )
                        }], changed=[${alteredFiles.joinToString(",")}]"
                    )

                    if (altered) {
                        // Notify history manager about the new version
                        val savedAt = System.currentTimeMillis()
                        val boxIds = snapshot.transfers.map { it.id }.toSet()
                        val historyStartedAt = System.nanoTime()
                        HistoryManager.onVersionSaved(fn, boxIds, savedAt)
                        historyMs = SaveReloadLog.elapsedMillis(historyStartedAt)
                        val metricsStartedAt = System.nanoTime()
                        HistoryMetrics.recordCurrentSave(fn, savedAt)
                        metricsMs = SaveReloadLog.elapsedMillis(metricsStartedAt)
                        val versionStartedAt = System.nanoTime()
                        versionIndex = HistoryMetrics.currentVersionIndex(fn)
                        versionIndexMs = SaveReloadLog.elapsedMillis(versionStartedAt)
                        val screenshotStartedAt = System.nanoTime()
                        HistoryScreenshotCapture.captureFourWindowsOnSave(fn, versionIndex, savedAt)
                        screenshotMs = SaveReloadLog.elapsedMillis(screenshotStartedAt)
                        val notifyStartedAt = System.nanoTime()
                        _send(
                            Message("history-changed").payload(
                                HistoryChanged(
                                    filename = fn,
                                    timestamp = savedAt,
                                    versionIndex = versionIndex,
                                    boxCount = boxIds.size,
                                    byteSize = File(root, METADATA_EDN).length(),
                                    boxIds = boxIds.toList().sorted()
                                )
                            ),
                            document = Regex.escape(fn)
                        )
                        notifyMs = SaveReloadLog.elapsedMillis(notifyStartedAt)

                        // Mark saved sidecar files - needs to be done in main thread
                        val mainScheduleStartedAt = System.nanoTime()
                        inMain {
                            val mainStartedAt = System.nanoTime()
                            notify("saved file at <<timeago:${System.currentTimeMillis()}>>", "*saved")

                            val watchManager = snapshot.fileWatchManager
                            if (watchManager == null) {
                                SaveReloadLog.log("save-warning", "doc=$fn missing-watch-manager")
                            }
                            watchManager?.let {
                                val metadataFile = File(root, METADATA_EDN)
                                if (metadataFile.exists()) {
                                    it.markInternalSave(metadataFile)
                                }
                                File(root).listFiles()?.forEach { file ->
                                    if (file.isFile && file.name.contains("_") &&
                                        Box.ioSeparateProperties.values.contains(file.extension)
                                    ) {
                                        it.markInternalSave(file)
                                    }
                                }
                            }
                            onComplete?.invoke()
                            Ai4AutoBoxNamer.scheduleAfterSave(fn, boxes, message)
                            val mainMs = SaveReloadLog.elapsedMillis(mainStartedAt)
                            SaveReloadLog.log(
                                "save-main-timing",
                                "ms=$mainMs doc=$fn watchManager=${watchManager != null} touched=$touchedCount"
                            )
                        }
                        mainScheduleMs = SaveReloadLog.elapsedMillis(mainScheduleStartedAt)
                    } else {
                        val mainScheduleStartedAt = System.nanoTime()
                        inMain {
                            val mainStartedAt = System.nanoTime()
                            onComplete?.invoke()
                            Ai4AutoBoxNamer.scheduleAfterSave(fn, boxes, message)
                            SaveReloadLog.logTiming(
                                "save-main-timing",
                                mainStartedAt,
                                "doc=$fn unchanged=true",
                                thresholdMs = 25L
                            )
                        }
                        mainScheduleMs = SaveReloadLog.elapsedMillis(mainScheduleStartedAt)
                    }
                }
            } catch (e: Exception) {
                SaveReloadLog.log("save-error", "doc=$fn error=${e.message}")
                lprintln("error,save", "Error during background save: ${e.message}")
                e.printStackTrace()
            } finally {
                val totalMs = SaveReloadLog.elapsedMillis(totalStartedAt)
                SaveReloadLog.log(
                    "save-timing",
                    "totalMs=$totalMs doc=$fn root=$root altered=$altered metadataChanged=$metadataChanged markdownChanged=$markdownChanged touched=$touchedCount changed=$changedCount versionIndex=$versionIndex launchDelayMs=$launchDelayMs ensureMs=$ensureMs lockWaitMs=$lockWaitMs guardMs=$guardMs sidecarMs=$sidecarMs ednMs=$ednMs metadataMs=$metadataMs markdownBuildMs=$markdownBuildMs markdownSaveMs=$markdownSaveMs recorderMs=$recorderMs historyMs=$historyMs metricsMs=$metricsMs versionIndexMs=$versionIndexMs screenshotScheduleMs=$screenshotMs notifyMs=$notifyMs mainScheduleMs=$mainScheduleMs"
                )
                if (totalMs >= 1000L) {
                    lprintln(
                        "warning,save",
                        "slow save doc=$fn totalMs=$totalMs sidecarMs=$sidecarMs metadataMs=$metadataMs markdownBuildMs=$markdownBuildMs markdownSaveMs=$markdownSaveMs historyMs=$historyMs metricsMs=$metricsMs screenshotScheduleMs=$screenshotMs"
                    )
                }
            }
        }
    }

    /**
     * Data class to hold a snapshot of box data captured in the main thread.
     */
    private data class SaveSnapshot(
        val transfers: List<BoxTransferEdn>,
        val persistentProperties: List<String>,
        val fileWatchManager: FileWatchManager?,
        val propertyCount: Int,
        val sidecarCount: Int,
        val sidecarChars: Long
    )

    private fun suspiciousPartialAutosave(root: String, snapshot: SaveSnapshot, message: String?): PartialSaveBlock? {
        if (!message.isNullOrBlank()) return null

        val metadataFile = File(root, METADATA_EDN)
        if (!metadataFile.isFile) return null

        val existingIds = try {
            readEdnTransfers(root).map { it.id }.toSet()
        } catch (e: Exception) {
            SaveReloadLog.log("save-guard-warning", "doc=$root could-not-read-existing-metadata error=${e.message}")
            return null
        }
        val snapshotIds = snapshot.transfers.map { it.id }.toSet()
        if (existingIds.size < 4 || snapshotIds.isEmpty()) return null
        if (snapshotIds.size >= existingIds.size) return null

        val missing = existingIds - snapshotIds
        val missingThreshold = max(4, (existingIds.size + 3) / 4)
        if (missing.size < missingThreshold) return null

        return PartialSaveBlock(
            existingCount = existingIds.size,
            snapshotCount = snapshotIds.size,
            missingCount = missing.size,
            missingPreview = missing.take(8).joinToString(",")
        )
    }

    private fun buildDocumentMarkdown(snapshot: SaveSnapshot, documentName: String): String {
        val ordered = parentFirst(snapshot.transfers)
        val out = StringBuilder()

        out.append("# Field Document")
        if (documentName.isNotBlank()) {
            out.append(": ").append(markdownInline(documentName))
        }
        out.append("\n\n")
        out.append("This is a machine-readable narrative mirror of `metadata.edn`. ")
        out.append("The canonical storage format is still EDN; this file is written beside it for code and AI tools.\n\n")
        appendFence(
            out,
            "edn",
            "field-document",
            mapOf("version" to "1", "source" to METADATA_EDN),
            Edn.write(
                linkedMapOf<String, Any?>(
                    "format" to "field-document-md",
                    "version" to 1,
                    "source" to METADATA_EDN,
                    "boxes" to ordered.size,
                    "persistentProperties" to snapshot.persistentProperties
                ),
                ednCustom
            )
        )

        ordered.forEachIndexed { index, transfer ->
            appendBoxMarkdown(out, transfer, index)
        }

        return out.toString()
    }

    private fun appendBoxMarkdown(out: StringBuilder, transfer: BoxTransferEdn, index: Int) {
        val name = (transfer.properties["name"] as? String)?.ifBlank { null }
        val blockProperties = transfer.properties.mapNotNull { (propertyName, value) ->
            toMarkdownBlockProperty(propertyName, value, transfer.properties)
        }
        val blockNames = blockProperties.mapTo(mutableSetOf()) { it.name }
        val headerProperties = LinkedHashMap<String, Any?>()
        transfer.properties.forEach { (name, value) ->
            if (!blockNames.contains(name)) {
                headerProperties[name] = value
            }
        }
        val blockManifest = blockProperties.map { block ->
            linkedMapOf<String, Any?>(
                "name" to block.name,
                "language" to block.language,
                "path" to block.path,
                "lines" to lineCount(block.text),
                "characters" to block.text.length,
                "sha256" to sha256Hex(block.text)
            ).filterValues { it != null }
        }
        val parents = boxRefIds(transfer.properties["parents"])
        val children = boxRefIds(transfer.properties["children"])
        val narratives = blockProperties.mapNotNull { block ->
            extractLeadingNarrative(block.name, block.language, block.text)
        }

        out.append("## ").append(index + 1).append(". ")
        out.append(markdownInline(name ?: transfer.id)).append("\n\n")
        out.append("<!-- field-box id=\"").append(htmlAttribute(transfer.id)).append("\" index=\"")
            .append(index).append("\" -->\n\n")

        if (narratives.isNotEmpty()) {
            out.append("### Narrative\n\n")
            narratives.forEach { narrative ->
                out.append("From `").append(markdownInline(narrative.property)).append("`")
                if (narrative.tags.isNotEmpty()) {
                    out.append(" tags: ")
                    out.append(narrative.tags.joinToString(" ") { "`#$it`" })
                }
                out.append("\n\n")
                out.append(narrative.text.trim()).append("\n\n")
            }
        }

        val boxHeader = linkedMapOf<String, Any?>(
            "id" to transfer.id,
            "index" to index,
            "name" to name,
            "parents" to parents,
            "children" to children,
            "properties" to headerProperties,
            "blockProperties" to blockManifest
        ).filterValues { it != null }
        appendFence(
            out,
            "edn",
            "field-box",
            mapOf("id" to transfer.id, "role" to "properties"),
            Edn.write(boxHeader, ednCustom)
        )

        blockProperties.forEach { block ->
            appendFence(
                out,
                block.language,
                "field-property",
                buildMap {
                    put("box", transfer.id)
                    put("name", block.name)
                    block.path?.let { put("path", it) }
                },
                block.text
            )
        }
    }

    private fun parentFirst(transfers: List<BoxTransferEdn>): List<BoxTransferEdn> {
        val byId = transfers.associateBy { it.id }
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val ordered = mutableListOf<BoxTransferEdn>()

        fun visit(transfer: BoxTransferEdn) {
            if (visited.contains(transfer.id)) return
            if (!visiting.add(transfer.id)) return
            boxRefIds(transfer.properties["parents"]).forEach { parentId ->
                byId[parentId]?.let { visit(it) }
            }
            visiting.remove(transfer.id)
            visited.add(transfer.id)
            ordered += transfer
        }

        byId["root"]?.let { visit(it) }
        transfers.forEach { visit(it) }
        return ordered
    }

    private fun toMarkdownBlockProperty(
        name: String,
        value: Any?,
        properties: Map<String, Any?>
    ): MarkdownBlockProperty? {
        val path: String?
        val text: String
        when (value) {
            is FileInline -> {
                path = value.path
                text = value.inline
            }

            is String -> {
                if (!shouldWriteStringAsBlock(name, value)) return null
                path = null
                text = value
            }

            else -> return null
        }
        return MarkdownBlockProperty(
            name = name,
            language = markdownLanguage(name, path, properties),
            text = text,
            path = path
        )
    }

    private fun shouldWriteStringAsBlock(name: String, value: String): Boolean {
        if (name == "name" || name == "filename" || name == "code_language") return false
        if (Box.ioSeparateProperties.containsKey(name)) return true
        return value.contains('\n')
    }

    private fun markdownLanguage(name: String, path: String?, properties: Map<String, Any?>): String {
        if (name == "code") {
            val declared = (properties["code_language"] as? String)?.trim()?.lowercase()
            if (!declared.isNullOrBlank()) return normalizeMarkdownLanguage(declared)
        }
        val extension = path?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
            ?: Box.ioSeparateProperties[name]
        return normalizeMarkdownLanguage(extension ?: "text")
    }

    private fun normalizeMarkdownLanguage(language: String): String = when (language.lowercase()) {
        "kt", "kts", "kotlin" -> "kotlin"
        "js", "javascript" -> "javascript"
        "ts", "typescript" -> "typescript"
        "py", "python" -> "python"
        "clj", "clojure" -> "clojure"
        "yml", "yaml" -> "yaml"
        "md", "markdown" -> "markdown"
        "glsl" -> "glsl"
        "slang" -> "slang"
        "stacksl" -> "stacksl"
        "edn" -> "edn"
        else -> language.ifBlank { "text" }
    }

    private fun extractLeadingNarrative(property: String, language: String, text: String): ExtractedNarrative? {
        val lines = text.lines()
        val collected = mutableListOf<String>()
        var index = 0
        while (index < lines.size && lines[index].isBlank()) index++

        if (index < lines.size && lines[index].trimStart().startsWith("/*")) {
            var first = true
            while (index < lines.size) {
                val raw = lines[index]
                val trimmed = raw.trim()
                var body = raw.trimStart()
                if (first) {
                    body = body.removePrefix("/*")
                    first = false
                }
                val closes = body.contains("*/")
                body = body.substringBefore("*/")
                collected += cleanupCommentLine(body)
                index++
                if (closes) break
            }
        }

        while (index < lines.size) {
            val raw = lines[index]
            val trimmed = raw.trimStart()
            val body = when {
                trimmed.startsWith("//") -> trimmed.removePrefix("//")
                trimmed.startsWith("#") && language in setOf(
                    "python",
                    "yaml",
                    "text",
                    "markdown"
                ) -> trimmed.removePrefix("#")

                trimmed.startsWith(";") && language == "clojure" -> trimmed.removePrefix(";")
                collected.isNotEmpty() && raw.isBlank() -> ""
                else -> break
            }
            collected += cleanupCommentLine(body)
            index++
        }

        val narrative = collected.joinToString("\n").trim()
        if (narrative.isBlank()) return null
        val tags = Regex("""(?<![\w/])#([A-Za-z0-9][A-Za-z0-9_:/.-]*)""")
            .findAll(narrative)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        return ExtractedNarrative(property, narrative, tags)
    }

    private fun cleanupCommentLine(line: String): String {
        return line.trimStart().removePrefix("*").trimStart()
    }

    private fun appendFence(
        out: StringBuilder,
        language: String,
        marker: String,
        metadata: Map<String, String>,
        text: String
    ) {
        val longestFence = Regex("`+").findAll(text).map { it.value.length }.maxOrNull() ?: 0
        val fence = "`".repeat(maxOf(3, longestFence + 1))
        out.append(fence).append(language.ifBlank { "text" }).append(" [").append(marker)
        metadata.forEach { (key, value) ->
            out.append(' ').append(key).append("=\"").append(fenceInfoValue(value)).append('"')
        }
        out.append("]\n")
        out.append(text)
        if (!text.endsWith("\n")) out.append('\n')
        out.append(fence).append("\n\n")
    }

    private fun boxRefIds(value: Any?): List<String> = when (value) {
        is BoxRef -> listOf(value.id)
        is Collection<*> -> value.flatMap { boxRefIds(it) }
        else -> emptyList()
    }

    private fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        return text.count { it == '\n' } + if (text.endsWith("\n")) 0 else 1
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val out = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            out.append(hex[value ushr 4])
            out.append(hex[value and 0x0f])
        }
        return out.toString()
    }

    private fun markdownInline(value: String): String {
        return value.replace(Regex("""[\r\n]+"""), " ").trim()
    }

    private fun htmlAttribute(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun fenceInfoValue(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    /**
     * Save boxes as a standalone template file (metadata.edn without subdirectory)
     */
    fun saveAsTemplate(templateName: String, boxes: Set<Box>, templatesDir: String) {
        val ednText = exportEdnPackage(boxes, templatesDir)
        val templateFile = File(templatesDir, "$templateName.edn")
        templateFile.writeText(ednText)
    }


    fun load(
        fn: String,
        filterId: (String) -> String = { it },
        upgradeOnLoad: Boolean = true,
        initIfCreated: Boolean = true
    ): Set<Box> {
        val file = File(fn)

        // Check if fn points to a standalone .edn file
        if (file.isFile && file.name.endsWith(".edn")) {
            val ednText = file.readText()
            val directory = file.absoluteFile.parentFile?.absolutePath ?: file.absolutePath
            return loadFromEdnText(ednText, directory, filterId, initIfCreated = initIfCreated)
        }

        // Otherwise, treat as directory
        val root = ensureDirectory(fn)
        val ednFile = File(root, METADATA_EDN)
        if (ednFile.exists()) {
            return loadEdn(root, filterId, initIfCreated = initIfCreated)
        }

        val jsonFile = File(root, METADATA_JSON)
        if (!jsonFile.exists()) {
            throw FileNotFoundException("missing $METADATA_EDN or $METADATA_JSON in $root")
        }

        val boxes = loadJson(root, filterId, initIfCreated = initIfCreated)
        if (upgradeOnLoad) {
            try {
                save(root, boxes, "upgrade to edn")
            } catch (ex: Exception) {
                lprintln("error,save", "error upgrading $root to EDN: ${ex.message}")
            }
        }
        return boxes
    }

    fun resolveStoredValue(value: Any?, directory: String, preferInlineFiles: Boolean = false): Any? {
        return resolveValue(value, directory, { it }, createBoxes = false, preferInlineFiles = preferInlineFiles)
    }

    fun readInitialBoxFrames(fn: String, filterId: (String) -> String = { it }): List<InitialBoxFrame> {
        val file = File(fn)
        val transfers = when {
            file.isFile && file.name.endsWith(".edn") -> readEdnTransfersFromString(file.readText())
            else -> {
                val root = File(fn)
                val ednFile = File(root, METADATA_EDN)
                if (!ednFile.isFile) {
                    throw FileNotFoundException("missing $METADATA_EDN in ${root.path}")
                }
                readEdnTransfers(root.path)
            }
        }

        return transfers.mapNotNull { transfer ->
            if (transfer.id == "root") return@mapNotNull null
            if (transfer.properties["_tombstone"].isTrue()) return@mapNotNull null
            val frame = transfer.properties["frame"] as? Rect
                ?: coerceRect(transfer.properties["frame"])
                ?: return@mapNotNull null
            InitialBoxFrame(filterId(transfer.id), frame)
        }
    }

    fun updateRootMetadata(directory: String, metadata: String): Boolean {
        val rootFile = File(directory, METADATA_EDN)
        if (!rootFile.isFile) {
            throw FileNotFoundException("missing $METADATA_EDN in $directory")
        }

        val rootValue = Edn.read(rootFile.readText(), ednCustom)
        val persistentProperties = when (rootValue) {
            is Map<*, *> -> {
                val savedProps = mapLookup(rootValue, "persistentProperties")
                when (savedProps) {
                    is Collection<*> -> savedProps.mapNotNull { it?.toString() }
                        .ifEmpty { Box.ioPersistentProperties.toList() }

                    else -> Box.ioPersistentProperties.toList()
                }
            }

            else -> Box.ioPersistentProperties.toList()
        }

        val transfers = readEdnTransfers(directory)
        if (transfers.isEmpty()) {
            throw IllegalArgumentException("document has no boxes")
        }

        val targetIndex = transfers.indexOfFirst { it.id == "root" }.let { if (it >= 0) it else 0 }
        val updatedTransfers = transfers.mapIndexed { index, transfer ->
            if (index != targetIndex) {
                transfer
            } else {
                val updatedProperties = LinkedHashMap<String, Any?>()
                updatedProperties.putAll(transfer.properties)
                if (metadata.isBlank()) {
                    updatedProperties.remove("metadata")
                } else {
                    updatedProperties["metadata"] = metadata
                }
                BoxTransferEdn(transfer.id, updatedProperties)
            }
        }

        val payloadBoxes = updatedTransfers.map { transfer ->
            linkedMapOf<String, Any?>(
                "id" to transfer.id,
                "properties" to transfer.properties
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "version" to 2,
            "boxes" to payloadBoxes,
            "persistentProperties" to persistentProperties
        )

        return checkDifference(rootFile.path, Edn.write(payload, ednCustom))
    }

    fun resolveStoredValueWithBoxes(
        value: Any?,
        directory: String,
        filterId: (String) -> String = { it },
        allowedRefIds: Set<String>? = null,
        root: SessionInformation? = null,
        preferInlineFiles: Boolean = false
    ): Any? {
        return resolveValue(
            value,
            directory,
            filterId,
            createBoxes = true,
            allowedRefIds = allowedRefIds,
            root = root,
            preferInlineFiles = preferInlineFiles
        )
    }

    private fun normalizedEdnKey(key: Any?): String? {
        val text = key?.toString() ?: return null
        return if (text.startsWith(":")) text.substring(1) else text
    }

    private fun mapLookup(map: Map<*, *>, key: String): Any? {
        map[key]?.let { return it }
        map[":$key"]?.let { return it }
        return map.entries.firstOrNull { normalizedEdnKey(it.key) == key }?.value
    }

    private fun numberLikeToDouble(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun coerceRect(value: Any?): Rect? {
        val items = value as? List<*> ?: return null
        if (items.size != 4) return null
        val x = numberLikeToDouble(items[0]) ?: return null
        val y = numberLikeToDouble(items[1]) ?: return null
        val w = numberLikeToDouble(items[2]) ?: return null
        val h = numberLikeToDouble(items[3]) ?: return null
        return Rect(x, y, w, h)
    }

    private fun coerceBoxRefLike(value: Any?): BoxRef? = when (value) {
        is BoxRef -> value
        is String -> {
            val id = value.removePrefix(":").trim()
            if (id.isEmpty()) null else BoxRef(id)
        }

        else -> null
    }

    private fun normalizeTransferPropertyValue(name: String, value: Any?): Any? = when (name) {
        "frame", "viewport" -> coerceRect(value) ?: value
        "parents", "children", "nested" -> {
            val collection = value as? Collection<*>
            if (collection == null) {
                value
            } else {
                val refs = LinkedHashSet<BoxRef>()
                var valid = true
                for (item in collection) {
                    val ref = coerceBoxRefLike(item)
                    if (ref == null) {
                        valid = false
                        break
                    }
                    refs.add(ref)
                }
                if (valid) refs else value
            }
        }

        "nestedIn" -> coerceBoxRefLike(value) ?: value
        else -> value
    }

    /**
     * Merges persistent properties from a document with the current global set.
     * This ensures that all properties that were previously saved in a document
     * continue to be saved, even if they're not in the current global set.
     *
     * Note: This function intentionally modifies global state (Box.ioPersistentProperties).
     * This is by design to ensure all loaded documents' properties are preserved across saves.
     * Documents are loaded sequentially in this application, so concurrent access is not a concern.
     *
     * @param rootValue The root EDN value from metadata.edn (should be a Map)
     */
    private fun mergePersistentProperties(rootValue: Map<*, *>) {
        val savedProps = mapLookup(rootValue, "persistentProperties")
        if (savedProps != null) {
            val propsList = when (savedProps) {
                is List<*> -> savedProps.mapNotNull { it?.toString() }
                is Set<*> -> savedProps.mapNotNull { it?.toString() }
                else -> emptyList()
            }
            // Merge the saved properties with the current global set
            // mapNotNull ensures only valid string values are added
            Box.ioPersistentProperties.addAll(propsList)
        }
    }

    fun readEdnTransfers(directory: String): List<BoxTransferEdn> {
        val rootValue = Edn.read(File(directory, METADATA_EDN).readText(), ednCustom)
        val boxesValue = when (rootValue) {
            is Map<*, *> -> {
                // Merge persistent properties from the document with the current global set
                mergePersistentProperties(rootValue)
                mapLookup(rootValue, "boxes")
            }

            is List<*> -> rootValue
            is Set<*> -> rootValue.toList()
            else -> throw IllegalArgumentException("expected EDN map or list in ${File(directory, METADATA_EDN).path}")
        }
        val boxList = when (boxesValue) {
            null -> emptyList()
            is List<*> -> boxesValue
            is Set<*> -> boxesValue.toList()
            else -> throw IllegalArgumentException("expected boxes list in ${File(directory, METADATA_EDN).path}")
        }

        return boxList.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = mapLookup(map, "id")?.toString() ?: return@mapNotNull null
            val propsRaw = mapLookup(map, "properties") as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val props = LinkedHashMap<String, Any?>()
            for ((k, v) in propsRaw) {
                val key = normalizedEdnKey(k) ?: continue
                props[key] = normalizeTransferPropertyValue(key, v)
            }
            BoxTransferEdn(id, props)
        }
    }

    fun readEdnTransfersFromString(ednText: String): List<BoxTransferEdn> {
        val rootValue = Edn.read(ednText, ednCustom)
        val boxesValue = when (rootValue) {
            is Map<*, *> -> {
                // Merge persistent properties from the document with the current global set
                mergePersistentProperties(rootValue)
                mapLookup(rootValue, "boxes")
            }

            is List<*> -> rootValue
            is Set<*> -> rootValue.toList()
            else -> throw IllegalArgumentException("expected EDN map or list in provided text")
        }
        val boxList = when (boxesValue) {
            null -> emptyList()
            is List<*> -> boxesValue
            is Set<*> -> boxesValue.toList()
            else -> throw IllegalArgumentException("expected boxes list in provided text")
        }

        return boxList.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = mapLookup(map, "id")?.toString() ?: return@mapNotNull null
            val propsRaw = mapLookup(map, "properties") as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val props = LinkedHashMap<String, Any?>()
            for ((k, v) in propsRaw) {
                val key = normalizedEdnKey(k) ?: continue
                props[key] = normalizeTransferPropertyValue(key, v)
            }
            BoxTransferEdn(id, props)
        }
    }

    private fun resolveValue(
        value: Any?,
        directory: String,
        filterId: (String) -> String,
        createBoxes: Boolean,
        allowedRefIds: Set<String>? = null,
        root: SessionInformation? = null,
        preferInlineFiles: Boolean = false,
        initIfCreated: Boolean = true
    ): Any? = when (value) {
        null -> null
        is FileRef -> File(directory, value.path).readText()
        is FileInline -> {
            val file = File(directory, value.path)
            if (!preferInlineFiles && file.exists()) file.readText() else value.inline
        }

        is BoxRef -> {
            if (allowedRefIds != null && value.id !in allowedRefIds) {
                SkipRef
            } else if (!createBoxes) {
                value
            } else {
                val sourceId = value.id
                val id = filterId(sourceId)
                val resolvedRoot = root ?: thisSessionInfo.get()
                if (resolvedRoot == null) {
                    SaveReloadLog.log(
                        "resolve-boxref-missing-root",
                        "id=$id source=$sourceId directory=$directory"
                    )
                    SkipRef
                } else {
                    val v = box(id, createIfMissing = true, initIfCreated = initIfCreated, root = resolvedRoot)
                    if (id != sourceId) {
                        v.setPropertyRaw("source_id", sourceId)
                    }
                    v
                }
            }
        }

        is List<*> -> {
            val resolved = mutableListOf<Any?>()
            value.forEach { item ->
                val result =
                    resolveValue(item, directory, filterId, createBoxes, allowedRefIds, root, preferInlineFiles, initIfCreated)
                if (result !== SkipRef) {
                    resolved.add(result)
                }
            }
            resolved
        }

        is Set<*> -> {
            val resolved = LinkedHashSet<Any?>()
            value.forEach { item ->
                val result =
                    resolveValue(item, directory, filterId, createBoxes, allowedRefIds, root, preferInlineFiles, initIfCreated)
                if (result !== SkipRef) {
                    resolved.add(result)
                }
            }
            resolved
        }

        is Map<*, *> -> {
            val mapped = LinkedHashMap<Any?, Any?>()
            for ((k, v) in value) {
                val keyResolved =
                    resolveValue(k, directory, filterId, createBoxes, allowedRefIds, root, preferInlineFiles, initIfCreated)
                if (keyResolved === SkipRef) continue
                val valueResolved =
                    resolveValue(v, directory, filterId, createBoxes, allowedRefIds, root, preferInlineFiles, initIfCreated)
                if (valueResolved === SkipRef) continue
                mapped[keyResolved] = valueResolved
            }
            mapped
        }

        is EdnValue -> {
            lprintln("warning,decode", "can't decode $value")
            null
        }

        else -> value
    }

    private fun loadEdn(root: String, filterId: (String) -> String, initIfCreated: Boolean = true): Set<Box> {
        val transfers = readEdnTransfers(root)
        return instantiateTransfers(transfers, root, filterId, createBoxes = true, initIfCreated = initIfCreated)
    }

    private fun instantiateTransfers(
        transfers: List<BoxTransferEdn>,
        root: String,
        filterId: (String) -> String,
        createBoxes: Boolean,
        allowedRefIds: Set<String>? = null,
        preferInlineFiles: Boolean = false,
        initIfCreated: Boolean = true
    ): Set<Box> {
        val boxes = transfers.map { transfer ->
            val id = filterId(transfer.id)
            val b = box(id, createIfMissing = createBoxes, initIfCreated = initIfCreated)
            transfer.properties.forEach { (name, value) ->
                val v = resolveValue(
                    value,
                    root,
                    filterId,
                    createBoxes = createBoxes,
                    allowedRefIds = allowedRefIds,
                    preferInlineFiles = preferInlineFiles,
                    initIfCreated = initIfCreated
                )
                if (v != null) {
                    b.setPropertyRaw(name, v)
                }
            }
            if (id != transfer.id) {
                b.setPropertyRaw("source_id", transfer.id)
            }
            b
        }.toSet()

        reconcileLoadedTopology(boxes)
        return boxes
    }

    private fun reconcileLoadedTopology(boxes: Set<Box>) {
        // Loading through setPropertyRaw restores the forward refs but skips the normal mutation helpers
        // that would repair inverse topology on boxes outside the loaded payload.
        fun ensureConnection(parent: Box, child: Box) {
            if (!parent.children.contains(child)) {
                parent.setPropertyRaw("children", parent.children.toMutableSet().apply { add(child) })
            }
            if (!child.parents.contains(parent)) {
                child.setPropertyRaw("parents", child.parents.toMutableSet().apply { add(parent) })
            }
        }

        boxes.forEach { box ->
            box.parents
                .filter { !it.getPropertyRaw("_tombstone").isTrue() }
                .forEach { parent -> ensureConnection(parent, box) }

            box.children
                .filter { !it.getPropertyRaw("_tombstone").isTrue() }
                .forEach { child -> ensureConnection(box, child) }

            box.nested
                .filter { !it.getPropertyRaw("_tombstone").isTrue() }
                .forEach { child ->
                    if (child.nestedIn != box) {
                        child.setPropertyRaw("nestedIn", box)
                    }
                    ensureConnection(box, child)
                }

            val nestedParent = box.nestedIn
            if (nestedParent != null && !nestedParent.getPropertyRaw("_tombstone").isTrue()) {
                if (!nestedParent.nested.contains(box)) {
                    nestedParent.setPropertyRaw("nested", nestedParent.nested.toMutableSet().apply { add(box) })
                }
                ensureConnection(nestedParent, box)
            }
        }
    }

    fun loadFromEdnText(
        ednText: String,
        directory: String,
        filterId: (String) -> String = { it },
        allowedRefIds: Set<String>? = null,
        preferInlineFiles: Boolean = false,
        initIfCreated: Boolean = true
    ): Set<Box> {
        val transfers = readEdnTransfersFromString(ednText)
        return instantiateTransfers(
            transfers,
            directory,
            filterId,
            createBoxes = true,
            allowedRefIds = allowedRefIds,
            preferInlineFiles = preferInlineFiles,
            initIfCreated = initIfCreated
        )
    }

    private fun loadJson(root: String, filterId: (String) -> String, initIfCreated: Boolean = true): Set<Box> {
        val transfers = format.decodeFromString<Set<Box_transfer>>(File(root, METADATA_JSON).readText())
        return transfers.map {
            val id = filterId(it.id)
            val b = box(id, createIfMissing = true, initIfCreated = initIfCreated)
            it.properties.forEach { (name, element) ->
                val v = fromTypedPair(element, root, filterId, initIfCreated = initIfCreated)
                if (v != null) {
                    b.setPropertyRaw(name, v)
                }
            }

            if (id != it.id) {
                b.setPropertyRaw("source_id", it.id)
            }
            b
        }.toSet()
    }

    fun fromTypedPair(
        element: Pair<String, JsonElement>,
        directory: String,
        filterId: (String) -> String = { it },
        createBoxes: Boolean = true,
        initIfCreated: Boolean = true
    ): Any? = when (element.first) {
        "__file__" -> File(directory, format.decodeFromJsonElement<String>(element.second)).readText()
        "Rect" -> format.decodeFromJsonElement<Rect>(element.second) as Any
        "String" -> format.decodeFromJsonElement<String>(element.second) as Any
        "Int" -> format.decodeFromJsonElement<Int>(element.second) as Any
        "Number" -> format.decodeFromJsonElement<Number>(element.second) as Any
        "Boolean" -> format.decodeFromJsonElement<Boolean>(element.second) as Any
        "Box" -> {
            val sourceId = format.decodeFromJsonElement<String>(element.second)
            if (!createBoxes) {
                BoxRef(sourceId)
            } else {
                val id = filterId(sourceId)
                val v = box(id, createIfMissing = true, initIfCreated = initIfCreated)
                if (id != sourceId) {
                    v.setPropertyRaw("source_id", sourceId)
                }
                v
            }
        }

        "List" -> format.decodeFromJsonElement<List<Pair<String, JsonElement>>>(element.second).map {
            fromTypedPair(it, directory, filterId, createBoxes, initIfCreated)
        }.toMutableList() as Any

        "Set" -> format.decodeFromJsonElement<List<Pair<String, JsonElement>>>(element.second).map {
            fromTypedPair(it, directory, filterId, createBoxes, initIfCreated)
        }.toMutableSet() as Any

        else -> {
            lprintln("error,decode", "can't decode $element")
            null
        }
    }

    companion object {
        val format = Json {
            prettyPrint = true
        }

        const val METADATA_EDN = "metadata.edn"
        const val METADATA_JSON = "metadata.json"
        const val DOCUMENT_MARKDOWN = "document.md"
        const val TRANSCRIPT_EDN = "transcript.edn"
        const val OPERATIONS_YAML = "operations.yaml"

        private val saveLocks = ConcurrentHashMap<String, Any>()

        private val ednCustom = Edn.CustomTypes().apply {
            registerEdn(Rect::class, "afield/rect", { rect ->
                EdnValue.VectorVal(
                    listOf(
                        EdnValue.Number(rect.x.toString()),
                        EdnValue.Number(rect.y.toString()),
                        EdnValue.Number(rect.w.toString()),
                        EdnValue.Number(rect.h.toString())
                    )
                )
            }, { value ->
                val vec = value as? EdnValue.VectorVal
                    ?: throw IllegalArgumentException("expected vector for Rect, got $value")
                val items = vec.values
                if (items.size != 4) {
                    throw IllegalArgumentException("expected 4 values for Rect, got ${items.size}")
                }
                Rect(
                    ednValueToDouble(items[0]),
                    ednValueToDouble(items[1]),
                    ednValueToDouble(items[2]),
                    ednValueToDouble(items[3])
                )
            })

            registerStringPayload(BoxRef::class, "afield/box", { it.id }, { BoxRef(it) })
            registerEdn(FileInline::class, "afield/file-inline", { file ->
                EdnValue.MapVal(
                    linkedMapOf(
                        EdnValue.Str("path") to EdnValue.Str(file.path),
                        EdnValue.Str("inline") to EdnValue.Str(file.inline)
                    )
                )
            }, { value ->
                val map = value as? EdnValue.MapVal
                    ?: throw IllegalArgumentException("expected map for FileInline, got $value")
                var pathValue = map.values[EdnValue.Str("path")] as? EdnValue.Str
                var inlineValue = map.values[EdnValue.Str("inline")] as? EdnValue.Str
                if (pathValue == null && inlineValue == null) {
                    throw IllegalArgumentException("missing path/inline for FileInline: $value")
                }
                FileInline(pathValue?.value ?: "<<unset>>", inlineValue!!.value)
            })
            registerStringPayload(FileRef::class, "afield/file", { it.path }, { FileRef(it) })
        }

        fun filenameFor(id: String, name: String, extension: String): String {
            return id + "_" + name + "_." + extension
        }

        private fun ednValueToDouble(value: EdnValue): Double = when (value) {
            is EdnValue.Number -> value.token.toDouble()
            is EdnValue.Str -> value.value.toDouble()
            else -> throw IllegalArgumentException("expected number, got $value")
        }
    }

    fun ensureDirectory(fn: String): String {
        File(fn).mkdirs()
        ensureAgentGuides(fn)
        return fn
    }

    /**
     * Seed a workspace with human and agent orientation without overwriting user files.
     *
     * `templates/WORKSPACE_README.md` becomes `README.md` and
     * `templates/WORKSPACE_AGENTS.md` becomes `AGENTS.md`. Each file is copied only when the source
     * exists and the destination does not. Guide discovery or copying failures are logged but never
     * fail workspace creation.
     */
    private fun ensureAgentGuides(workspaceDir: String) {
        try {
            val workspace = File(workspaceDir)
            if (!workspace.exists() || !workspace.isDirectory) return

            // Copy the workspace onboarding set into new workspaces:
            //   README.md  — human orientation (what this folder is, how to open it)
            //   AGENTS.md  — agent onboarding, auto-discovered by agent tooling; onboards
            //                an agent that opens this folder to the transcript/backdoor path.
            // Both point at the canonical automation docs under dev_docs/agents/.
            val guidesToCopy = mapOf(
                "templates/WORKSPACE_README.md" to "README.md",
                "templates/WORKSPACE_AGENTS.md" to "AGENTS.md"
            )

            // Get the repository root (go up from wherever we are)
            val repoRoot = findRepositoryRoot() ?: return

            guidesToCopy.forEach { (sourcePath, destName) ->
                val sourceFile = File(repoRoot, sourcePath)
                val destFile = File(workspace, destName)

                // Only copy if source exists and destination doesn't exist
                if (sourceFile.exists() && !destFile.exists()) {
                    try {
                        sourceFile.copyTo(destFile, overwrite = false)
                        lprintln("info,workspace", "Copied $destName to workspace: $workspaceDir")
                    } catch (e: Exception) {
                        // Don't fail workspace creation if guides can't be copied
                        lprintln("warning,workspace", "Could not copy $destName: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            // Silently continue if guide copying fails - it's not critical
            lprintln("debug,workspace", "Guide copying failed: ${e.message}")
        }
    }

    /**
     * Search at most ten parent directories from `user.dir` for `build.gradle.kts` or `.git`.
     *
     * If neither marker is found, return `user.dir` so callers can still attempt source lookup and
     * degrade through their normal missing-file checks.
     */
    private fun findRepositoryRoot(): File? {
        // Try common locations relative to the classpath
        var current = File(System.getProperty("user.dir"))

        // Look up the tree for repository markers
        var attempts = 10
        while (attempts > 0 && current.parent != null) {
            // Check for repository markers
            if (File(current, "build.gradle.kts").exists() ||
                File(current, ".git").exists()
            ) {
                return current
            }
            current = current.parentFile ?: break
            attempts--
        }

        // Fallback: use current directory
        return File(System.getProperty("user.dir"))
    }
}


/**
 * Safely saves text to a file, creating backups of previous versions.
 *
 * Changed from "backup" to "backups" directory to match the requirement
 * that previous metadata.edn files should be stored in backups/ directory.
 *
 * @param text The text content to save
 * @param maxBackups Maximum backups retained for this file, or null for unlimited retention
 * @return true if the file was modified, false if content was unchanged
 */
fun File.safeSave(
    text: String,
    createBackup: Boolean = true,
    maxBackups: Int? = null
): Boolean {
    require(maxBackups == null || maxBackups >= 0) { "maxBackups must be non-negative" }
    val file = this
    val startedAt = System.nanoTime()
    val existed = file.exists()
    var changed = false
    var currentChars = -1
    var readCurrentMs = 0L
    var latestBackupMs = 0L
    var backupCompareMs = 0L
    var nextBackupMs = 0L
    var renameMs = 0L
    var pruneMs = 0L
    var prunedBackups = 0
    var writeMs = 0L
    var backupCreated = false
    var alreadyBackedUp = false
    var latestBackupName = ""
    try {
        if (existed) {
            // Read current file content
            val readStartedAt = System.nanoTime()
            val currentContent = file.readText()
            readCurrentMs = SaveReloadLog.elapsedMillis(readStartedAt)
            currentChars = currentContent.length
            if (currentContent != text) {
                if (createBackup) {
                    val backupDir = File(file.parentFile, "backups")
                    val latestStartedAt = System.nanoTime()
                    val latestBackup = file.latestSafeSaveBackup(backupDir)
                    latestBackupMs = SaveReloadLog.elapsedMillis(latestStartedAt)
                    latestBackupName = latestBackup?.name ?: ""
                    val compareStartedAt = System.nanoTime()
                    alreadyBackedUp = latestBackup?.let { backup ->
                        runCatching { backup.readText() == currentContent }.getOrDefault(false)
                    } ?: false
                    backupCompareMs = SaveReloadLog.elapsedMillis(compareStartedAt)

                    if (!alreadyBackedUp) {
                        if (!backupDir.exists()) {
                            backupDir.mkdirs()
                        }

                        val nextStartedAt = System.nanoTime()
                        val backupFile = file.nextSafeSaveBackup(backupDir)
                        nextBackupMs = SaveReloadLog.elapsedMillis(nextStartedAt)
                        // Rename the current file to the backup file name in the backup directory
                        val renameStartedAt = System.nanoTime()
                        backupCreated = file.renameTo(backupFile)
                        renameMs = SaveReloadLog.elapsedMillis(renameStartedAt)
                        if (backupCreated && maxBackups != null) {
                            val pruneStartedAt = System.nanoTime()
                            prunedBackups = file.pruneSafeSaveBackups(backupDir, maxBackups)
                            pruneMs = SaveReloadLog.elapsedMillis(pruneStartedAt)
                        }
                    }
                }
            } else {
                // If the content is the same, do nothing.
                return false
            }
        }
        // Write the new text to the file
        val writeStartedAt = System.nanoTime()
        file.writeText(text)
        writeMs = SaveReloadLog.elapsedMillis(writeStartedAt)
        changed = true
        return true
    } finally {
        val totalMs = SaveReloadLog.elapsedMillis(startedAt)
        if (changed || totalMs >= 250L) {
            SaveReloadLog.log(
                "safe-save-timing",
                "ms=$totalMs path=${file.path} changed=$changed existed=$existed createBackup=$createBackup maxBackups=${maxBackups ?: "unlimited"} inputChars=${text.length} currentChars=$currentChars readCurrentMs=$readCurrentMs latestBackupMs=$latestBackupMs backupCompareMs=$backupCompareMs nextBackupMs=$nextBackupMs renameMs=$renameMs pruneMs=$pruneMs prunedBackups=$prunedBackups writeMs=$writeMs backupCreated=$backupCreated alreadyBackedUp=$alreadyBackedUp latestBackup=$latestBackupName"
            )
        }
        if (totalMs >= 1000L) {
            lprintln(
                "warning,save",
                "slow safeSave path=${file.path} ms=$totalMs readCurrentMs=$readCurrentMs latestBackupMs=$latestBackupMs backupCompareMs=$backupCompareMs nextBackupMs=$nextBackupMs renameMs=$renameMs writeMs=$writeMs"
            )
        }
    }
}

private fun File.safeSaveBackupPrefix(): String = "${nameWithoutExtension}_backup_"

private fun File.safeSaveBackupName(index: Int): String {
    val indexStr = String.format("%05d", index)
    return if (extension.isNotEmpty()) {
        "${safeSaveBackupPrefix()}$indexStr.$extension"
    } else {
        "${safeSaveBackupPrefix()}$indexStr"
    }
}

private fun File.safeSaveBackupIndex(backup: File): Int? {
    val prefix = safeSaveBackupPrefix()
    val suffix = if (extension.isNotEmpty()) ".${extension}" else ""
    val name = backup.name
    if (!name.startsWith(prefix)) return null
    if (suffix.isNotEmpty() && !name.endsWith(suffix)) return null
    val index = name.removePrefix(prefix).let {
        if (suffix.isNotEmpty()) it.removeSuffix(suffix) else it
    }
    return index.toIntOrNull()
}

private fun File.latestSafeSaveBackup(backupDir: File): File? {
    if (!backupDir.isDirectory) return null
    return backupDir.listFiles()
        ?.mapNotNull { backup -> safeSaveBackupIndex(backup)?.let { it to backup } }
        ?.maxByOrNull { it.first }
        ?.second
}

private fun File.nextSafeSaveBackup(backupDir: File): File {
    val nextIndex = backupDir.listFiles()
        ?.mapNotNull(::safeSaveBackupIndex)
        ?.maxOrNull()
        ?.plus(1)
        ?: 0
    return File(backupDir, safeSaveBackupName(nextIndex))
}

private fun File.pruneSafeSaveBackups(backupDir: File, maxBackups: Int): Int {
    if (!backupDir.isDirectory) return 0
    val obsolete = backupDir.listFiles()
        ?.mapNotNull { backup -> safeSaveBackupIndex(backup)?.let { it to backup } }
        ?.sortedByDescending { it.first }
        ?.drop(maxBackups)
        .orEmpty()
    return obsolete.count { (_, backup) -> backup.delete() }
}
