package afield

import afield.logger.SaveReloadLog
import afield.logger.lprintln
import java.io.File
import java.util.Base64

object HistoryMetrics {
    private const val METRICS_EDN = "history_metrics.edn"
    private val metricsLock = Any()

    fun recordCurrentSave(documentPath: String, timestamp: Long = System.currentTimeMillis()) {
        val startedAt = System.nanoTime()
        var versionMs = 0L
        var summaryMs = 0L
        var lockWaitMs = 0L
        var loadMs = 0L
        var computeMs = 0L
        var saveMs = 0L
        var recordCount = 0
        val versionStartedAt = System.nanoTime()
        val versionIndex = currentVersionIndex(documentPath)
        versionMs = SaveReloadLog.elapsedMillis(versionStartedAt)
        if (versionIndex < 0) {
            SaveReloadLog.logTiming(
                "history-metrics-record-save",
                startedAt,
                "doc=$documentPath skipped=true reason=no-current-version versionMs=$versionMs"
            )
            return
        }

        val summaryStartedAt = System.nanoTime()
        val summary = FlightRecorder.activitySummaryForDocument(documentPath, untilMillis = timestamp)
        summaryMs = SaveReloadLog.elapsedMillis(summaryStartedAt)

        val lockStartedAt = System.nanoTime()
        synchronized(metricsLock) {
            lockWaitMs = SaveReloadLog.elapsedMillis(lockStartedAt)
            val loadStartedAt = System.nanoTime()
            val existing = loadRecords(documentPath).toMutableList()
            loadMs = SaveReloadLog.elapsedMillis(loadStartedAt)
            val computeStartedAt = System.nanoTime()
            val previous = existing
                .filter { it.versionIndex < versionIndex }
                .maxByOrNull { it.versionIndex }
                ?.activity
            val sameFieldLaunch = previous?.fieldLaunchId == summary.fieldLaunchId
            val previousByBox = previous?.perBox.orEmpty().associateBy { it.uid }

            val perBox = summary.perBox.map { box ->
                val previousBox = if (sameFieldLaunch) previousByBox[box.uid] else null
                HistoryBoxActivity(
                    uid = box.uid,
                    name = box.name,
                    executions = box.executions,
                    launches = box.launches,
                    executionDelta = (box.executions - (previousBox?.executions ?: 0)).coerceAtLeast(0),
                    launchDelta = (box.launches - (previousBox?.launches ?: 0)).coerceAtLeast(0)
                )
            }

            val activity = HistoryActivityStats(
                fieldLaunchId = summary.fieldLaunchId,
                fieldLaunchStartedAt = summary.fieldLaunchStartedAt,
                executions = summary.executions,
                launches = summary.launches,
                executionDelta = if (sameFieldLaunch) {
                    (summary.executions - (previous?.executions ?: 0)).coerceAtLeast(0)
                } else {
                    summary.executions
                },
                launchDelta = if (sameFieldLaunch) {
                    (summary.launches - (previous?.launches ?: 0)).coerceAtLeast(0)
                } else {
                    summary.launches
                },
                editedBoxes = 0,
                movedBoxes = 0,
                perBox = perBox,
                screenshots = screenshotRefs(documentPath, versionIndex, timestamp)
            )

            existing.removeAll { it.versionIndex == versionIndex }
            existing += MetricRecord(versionIndex, timestamp, activity)
            recordCount = existing.size
            computeMs = SaveReloadLog.elapsedMillis(computeStartedAt)
            val saveStartedAt = System.nanoTime()
            saveRecords(documentPath, existing.sortedBy { it.versionIndex })
            saveMs = SaveReloadLog.elapsedMillis(saveStartedAt)
        }
        val totalMs = SaveReloadLog.logTiming(
            "history-metrics-record-save",
            startedAt,
            "doc=$documentPath versionIndex=$versionIndex records=$recordCount versionMs=$versionMs summaryMs=$summaryMs lockWaitMs=$lockWaitMs loadMs=$loadMs computeMs=$computeMs saveMs=$saveMs"
        )
        if (totalMs >= 500L) {
            lprintln(
                "warning,history",
                "slow history metrics record save doc=$documentPath ms=$totalMs versionMs=$versionMs summaryMs=$summaryMs loadMs=$loadMs saveMs=$saveMs"
            )
        }
    }

    fun recordEditStats(documentPath: String, versionIndex: Int, stats: HistoryDiffStats) {
        synchronized(metricsLock) {
            val records = loadRecords(documentPath).toMutableList()
            val index = records.indexOfFirst { it.versionIndex == versionIndex }
            if (index < 0) return
            val record = records[index]
            records[index] = record.copy(
                activity = record.activity.copy(
                    editedBoxes = stats.boxesEdited,
                    movedBoxes = stats.boxesMoved
                )
            )
            saveRecords(documentPath, records.sortedBy { it.versionIndex })
        }
    }

    fun activityByVersion(documentPath: String): Map<Int, HistoryActivityStats> {
        val activity = loadRecords(documentPath).associate {
            it.versionIndex to it.activity.copy(
                screenshots = validScreenshotRefs(documentPath, it.activity.screenshots)
            )
        }.toMutableMap()
        diskScreenshotRefsByVersion(documentPath).forEach { (versionIndex, refs) ->
            val existing = activity[versionIndex] ?: HistoryActivityStats()
            activity[versionIndex] = existing.copy(
                screenshots = mergeScreenshotRefs(existing.screenshots + refs)
            )
        }
        return activity
    }

    fun addScreenshotRef(
        documentPath: String,
        versionIndex: Int,
        channel: String,
        path: String,
        timestamp: Long = System.currentTimeMillis(),
        width: Int = 0,
        height: Int = 0
    ) {
        val startedAt = System.nanoTime()
        var lockWaitMs = 0L
        var loadMs = 0L
        var saveMs = 0L
        var recordCount = 0
        val lockStartedAt = System.nanoTime()
        synchronized(metricsLock) {
            lockWaitMs = SaveReloadLog.elapsedMillis(lockStartedAt)
            val loadStartedAt = System.nanoTime()
            val records = loadRecords(documentPath).toMutableList()
            loadMs = SaveReloadLog.elapsedMillis(loadStartedAt)
            val index = records.indexOfFirst { it.versionIndex == versionIndex }
            val record = if (index >= 0) {
                records[index]
            } else {
                MetricRecord(
                    versionIndex = versionIndex,
                    timestamp = timestamp,
                    activity = HistoryActivityStats()
                )
            }
            val nextScreenshots = (record.activity.screenshots.filterNot {
                it.channel == channel && it.path == path
            } + HistoryScreenshotRef(channel, path, timestamp, width, height))
                .sortedWith(compareBy<HistoryScreenshotRef> { it.channel }.thenBy { it.timestamp })
            val nextRecord = record.copy(activity = record.activity.copy(screenshots = nextScreenshots))
            if (index >= 0) {
                records[index] = nextRecord
            } else {
                records += nextRecord
            }
            recordCount = records.size
            val saveStartedAt = System.nanoTime()
            saveRecords(documentPath, records.sortedBy { it.versionIndex })
            saveMs = SaveReloadLog.elapsedMillis(saveStartedAt)
        }
        val totalMs = SaveReloadLog.logTiming(
            "history-metrics-add-screenshot",
            startedAt,
            "doc=$documentPath versionIndex=$versionIndex channel=$channel path=$path records=$recordCount lockWaitMs=$lockWaitMs loadMs=$loadMs saveMs=$saveMs"
        )
        if (totalMs >= 500L) {
            lprintln(
                "warning,history",
                "slow history metrics screenshot doc=$documentPath ms=$totalMs channel=$channel loadMs=$loadMs saveMs=$saveMs"
            )
        }
    }

    fun storeScreenshotDataUrl(
        documentPath: String,
        channel: String,
        dataUrl: String,
        versionIndex: Int = currentVersionIndex(documentPath),
        width: Int = 0,
        height: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ): HistoryScreenshotRef? {
        val startedAt = System.nanoTime()
        var decodeMs = 0L
        var writeMs = 0L
        var addRefMs = 0L
        if (versionIndex < 0) {
            SaveReloadLog.logTiming(
                "history-store-screenshot-data-url",
                startedAt,
                "doc=$documentPath channel=$channel skipped=true reason=no-current-version"
            )
            return null
        }
        val safeChannel = sanitizeChannel(channel)
        val base64 = dataUrl.substringAfter(",", dataUrl).trim()
        val decodeStartedAt = System.nanoTime()
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrElse {
            decodeMs = SaveReloadLog.elapsedMillis(decodeStartedAt)
            SaveReloadLog.logTiming(
                "history-store-screenshot-data-url",
                startedAt,
                "doc=$documentPath versionIndex=$versionIndex channel=$safeChannel ok=false error=decode-failed decodeMs=$decodeMs"
            )
            lprintln("warning,history", "Failed to decode screenshot for $documentPath: ${it.message}")
            return null
        }
        decodeMs = SaveReloadLog.elapsedMillis(decodeStartedAt)
        val extension = screenshotExtension(dataUrl, bytes)
        if (extension == null) {
            SaveReloadLog.logTiming(
                "history-store-screenshot-data-url",
                startedAt,
                "doc=$documentPath versionIndex=$versionIndex channel=$safeChannel ok=false error=invalid-screenshot decodeMs=$decodeMs bytes=${bytes.size}"
            )
            lprintln("warning,history", "Ignoring empty or invalid screenshot for $documentPath channel $safeChannel")
            return null
        }
        val root = File(documentPath, "history_screenshots/$safeChannel")
        root.mkdirs()
        val file = File(root, "v%05d_%d.$extension".format(versionIndex, timestamp))
        return runCatching {
            val writeStartedAt = System.nanoTime()
            file.writeBytes(bytes)
            writeMs = SaveReloadLog.elapsedMillis(writeStartedAt)
            val ref = HistoryScreenshotRef(
                channel = safeChannel,
                path = file.relativeTo(File(documentPath)).path,
                timestamp = timestamp,
                width = width,
                height = height
            )
            val addRefStartedAt = System.nanoTime()
            addScreenshotRef(documentPath, versionIndex, safeChannel, ref.path, timestamp, width, height)
            addRefMs = SaveReloadLog.elapsedMillis(addRefStartedAt)
            val totalMs = SaveReloadLog.logTiming(
                "history-store-screenshot-data-url",
                startedAt,
                "doc=$documentPath versionIndex=$versionIndex channel=$safeChannel ok=true extension=$extension bytes=${bytes.size} decodeMs=$decodeMs writeMs=$writeMs addRefMs=$addRefMs path=${ref.path}"
            )
            if (totalMs >= 500L) {
                lprintln(
                    "warning,history",
                    "slow data-url history screenshot doc=$documentPath channel=$safeChannel ms=$totalMs decodeMs=$decodeMs writeMs=$writeMs addRefMs=$addRefMs"
                )
            }
            ref
        }.getOrElse {
            SaveReloadLog.logTiming(
                "history-store-screenshot-data-url",
                startedAt,
                "doc=$documentPath versionIndex=$versionIndex channel=$safeChannel ok=false error=${it.message ?: it::class.simpleName} decodeMs=$decodeMs writeMs=$writeMs addRefMs=$addRefMs bytes=${bytes.size}"
            )
            lprintln("warning,history", "Failed to write screenshot for $documentPath: ${it.message}")
            null
        }
    }

    fun reserveScreenshotFile(
        documentPath: String,
        versionIndex: Int = currentVersionIndex(documentPath),
        channel: String,
        timestamp: Long = System.currentTimeMillis(),
        width: Int = 0,
        height: Int = 0,
        extension: String = "png"
    ): Pair<File, HistoryScreenshotRef>? {
        if (versionIndex < 0) return null
        val safeChannel = sanitizeChannel(channel)
        val safeExtension = sanitizeScreenshotExtension(extension)
        val root = File(documentPath, "history_screenshots/$safeChannel")
        root.mkdirs()
        val file = File(root, "v%05d_%d.$safeExtension".format(versionIndex, timestamp))
        val ref = HistoryScreenshotRef(
            channel = safeChannel,
            path = file.relativeTo(File(documentPath)).path,
            timestamp = timestamp,
            width = width,
            height = height
        )
        return file to ref
    }

    fun addScreenshotRef(
        documentPath: String,
        versionIndex: Int,
        ref: HistoryScreenshotRef
    ) {
        addScreenshotRef(
            documentPath = documentPath,
            versionIndex = versionIndex,
            channel = ref.channel,
            path = ref.path,
            timestamp = ref.timestamp,
            width = ref.width,
            height = ref.height
        )
    }

    private data class MetricRecord(
        val versionIndex: Int,
        val timestamp: Long,
        val activity: HistoryActivityStats
    )

    fun currentVersionIndex(documentPath: String): Int {
        val startedAt = System.nanoTime()
        val docDir = File(documentPath)
        val backups = File(docDir, "backups").listFiles { file ->
            file.name.startsWith("metadata_backup_") && file.name.endsWith(".edn")
        }?.size ?: 0
        val result = if (File(docDir, SavingContext.METADATA_EDN).exists()) backups else backups - 1
        val elapsedMs = SaveReloadLog.logTiming(
            "history-current-version-index",
            startedAt,
            "doc=$documentPath backups=$backups result=$result",
            thresholdMs = 100L
        )
        if (elapsedMs >= 500L) {
            lprintln(
                "warning,history",
                "slow currentVersionIndex doc=$documentPath ms=$elapsedMs backups=$backups result=$result"
            )
        }
        return result
    }

    private fun sanitizeChannel(channel: String): String =
        channel
            .ifBlank { "screenshot" }
            .replace(Regex("""[^A-Za-z0-9_.-]+"""), "-")
            .trim('-')
            .ifBlank { "screenshot" }

    private fun looksLikePng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()

    private fun looksLikeJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

    private fun looksLikeScreenshot(bytes: ByteArray): Boolean =
        looksLikePng(bytes) || looksLikeJpeg(bytes)

    private fun screenshotExtension(dataUrl: String, bytes: ByteArray): String? {
        val prefix = dataUrl.substringBefore(",", "").lowercase()
        return when {
            prefix.contains("image/png") && looksLikePng(bytes) -> "png"
            prefix.contains("image/jpeg") && looksLikeJpeg(bytes) -> "jpg"
            prefix.contains("image/jpg") && looksLikeJpeg(bytes) -> "jpg"
            looksLikePng(bytes) -> "png"
            looksLikeJpeg(bytes) -> "jpg"
            else -> null
        }
    }

    private fun sanitizeScreenshotExtension(extension: String): String =
        when (extension.lowercase().trimStart('.')) {
            "jpg", "jpeg" -> "jpg"
            else -> "png"
        }

    private fun supportedScreenshotExtension(extension: String): Boolean =
        extension.lowercase() == "png" ||
            extension.lowercase() == "jpg" ||
            extension.lowercase() == "jpeg"

    private fun metricsFile(documentPath: String): File =
        File(documentPath, METRICS_EDN)

    private fun loadRecords(documentPath: String): List<MetricRecord> {
        val file = metricsFile(documentPath)
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = Edn.read(file.readText(), Edn.CustomTypes()) as? Map<*, *> ?: return@runCatching emptyList()
            val records = root["records"] as? List<*> ?: return@runCatching emptyList()
            records.mapNotNull(::recordFromAny)
        }.getOrElse {
            lprintln("warning,history", "Failed to read history metrics for $documentPath: ${it.message}")
            emptyList()
        }
    }

    private fun saveRecords(documentPath: String, records: List<MetricRecord>) {
        val payload = linkedMapOf<String, Any?>(
            "version" to 1,
            "records" to records.map(::recordToMap)
        )
        val file = metricsFile(documentPath)
        file.parentFile?.mkdirs()
        file.safeSave(Edn.write(payload, Edn.CustomTypes()))
    }

    private fun recordFromAny(value: Any?): MetricRecord? {
        val map = value as? Map<*, *> ?: return null
        val versionIndex = (map["versionIndex"] as? Number)?.toInt() ?: return null
        val timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
        val activity = activityFromAny(map["activity"]) ?: HistoryActivityStats()
        return MetricRecord(versionIndex, timestamp, activity)
    }

    private fun recordToMap(record: MetricRecord): Map<String, Any?> =
        linkedMapOf(
            "versionIndex" to record.versionIndex,
            "timestamp" to record.timestamp,
            "activity" to activityToMap(record.activity)
        )

    private fun activityFromAny(value: Any?): HistoryActivityStats? {
        val map = value as? Map<*, *> ?: return null
        return HistoryActivityStats(
            fieldLaunchId = map["fieldLaunchId"]?.toString() ?: "",
            fieldLaunchStartedAt = (map["fieldLaunchStartedAt"] as? Number)?.toLong() ?: 0L,
            executions = (map["executions"] as? Number)?.toInt() ?: 0,
            launches = (map["launches"] as? Number)?.toInt() ?: 0,
            executionDelta = (map["executionDelta"] as? Number)?.toInt() ?: 0,
            launchDelta = (map["launchDelta"] as? Number)?.toInt() ?: 0,
            editedBoxes = (map["editedBoxes"] as? Number)?.toInt() ?: 0,
            movedBoxes = (map["movedBoxes"] as? Number)?.toInt() ?: 0,
            perBox = (map["perBox"] as? List<*>)?.mapNotNull(::boxActivityFromAny).orEmpty(),
            screenshots = (map["screenshots"] as? List<*>)?.mapNotNull(::screenshotFromAny).orEmpty()
        )
    }

    private fun activityToMap(activity: HistoryActivityStats): Map<String, Any?> =
        linkedMapOf(
            "fieldLaunchId" to activity.fieldLaunchId,
            "fieldLaunchStartedAt" to activity.fieldLaunchStartedAt,
            "executions" to activity.executions,
            "launches" to activity.launches,
            "executionDelta" to activity.executionDelta,
            "launchDelta" to activity.launchDelta,
            "editedBoxes" to activity.editedBoxes,
            "movedBoxes" to activity.movedBoxes,
            "perBox" to activity.perBox.map(::boxActivityToMap),
            "screenshots" to activity.screenshots.map(::screenshotToMap)
        )

    private fun boxActivityFromAny(value: Any?): HistoryBoxActivity? {
        val map = value as? Map<*, *> ?: return null
        val uid = map["uid"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return HistoryBoxActivity(
            uid = uid,
            name = map["name"]?.toString() ?: "",
            executions = (map["executions"] as? Number)?.toInt() ?: 0,
            launches = (map["launches"] as? Number)?.toInt() ?: 0,
            executionDelta = (map["executionDelta"] as? Number)?.toInt() ?: 0,
            launchDelta = (map["launchDelta"] as? Number)?.toInt() ?: 0
        )
    }

    private fun boxActivityToMap(activity: HistoryBoxActivity): Map<String, Any?> =
        linkedMapOf(
            "uid" to activity.uid,
            "name" to activity.name,
            "executions" to activity.executions,
            "launches" to activity.launches,
            "executionDelta" to activity.executionDelta,
            "launchDelta" to activity.launchDelta
        )

    private fun screenshotFromAny(value: Any?): HistoryScreenshotRef? {
        val map = value as? Map<*, *> ?: return null
        val channel = map["channel"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val path = map["path"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return HistoryScreenshotRef(
            channel = channel,
            path = path,
            timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L,
            width = (map["width"] as? Number)?.toInt() ?: 0,
            height = (map["height"] as? Number)?.toInt() ?: 0
        )
    }

    private fun screenshotToMap(ref: HistoryScreenshotRef): Map<String, Any?> =
        linkedMapOf(
            "channel" to ref.channel,
            "path" to ref.path,
            "timestamp" to ref.timestamp,
            "width" to ref.width,
            "height" to ref.height
        )

    private fun validScreenshotRefs(
        documentPath: String,
        refs: List<HistoryScreenshotRef>
    ): List<HistoryScreenshotRef> {
        val docDir = File(documentPath)
        val root = File(docDir, "history_screenshots").canonicalFile
        return refs.filter { ref ->
            runCatching {
                val file = File(docDir, ref.path).canonicalFile
                file.path.startsWith(root.path + File.separator) &&
                    file.isFile &&
                    file.length() > 8 &&
                    file.inputStream().use { input ->
                        val header = ByteArray(8)
                        input.read(header) == 8 && looksLikeScreenshot(header)
                    }
            }.getOrDefault(false)
        }
    }

    private val screenshotFilePattern = Regex("""^v(\d+)_(\d+)\.(png|jpg|jpeg)$""", RegexOption.IGNORE_CASE)

    private fun diskScreenshotRefsByVersion(documentPath: String): Map<Int, List<HistoryScreenshotRef>> {
        val docDir = File(documentPath)
        val root = File(docDir, "history_screenshots")
        if (!root.exists()) return emptyMap()

        val refs = linkedMapOf<Int, MutableList<HistoryScreenshotRef>>()
        root.listFiles { file -> file.isDirectory }.orEmpty().forEach { channelDir ->
            channelDir.listFiles { file -> file.isFile && supportedScreenshotExtension(file.extension) }.orEmpty()
                .forEach { file ->
                    val match = screenshotFilePattern.matchEntire(file.name) ?: return@forEach
                    val versionIndex = match.groupValues[1].toIntOrNull() ?: return@forEach
                    val timestamp = match.groupValues[2].toLongOrNull() ?: file.lastModified()
                    if (!isValidScreenshotFile(root, file)) return@forEach
                    refs.getOrPut(versionIndex) { mutableListOf() } += HistoryScreenshotRef(
                        channel = channelDir.name,
                        path = file.relativeTo(docDir).path,
                        timestamp = timestamp
                    )
                }
        }
        return refs.mapValues { (_, value) -> mergeScreenshotRefs(value) }
    }

    private fun mergeScreenshotRefs(refs: List<HistoryScreenshotRef>): List<HistoryScreenshotRef> {
        val byKey = linkedMapOf<Pair<String, String>, HistoryScreenshotRef>()
        refs.forEach { ref ->
            byKey[ref.channel to ref.path] = ref
        }
        return byKey.values.sortedWith(compareBy<HistoryScreenshotRef> { it.channel }.thenBy { it.timestamp })
    }

    private fun isValidScreenshotFile(root: File, file: File): Boolean {
        return runCatching {
            val canonicalRoot = root.canonicalFile
            val canonicalFile = file.canonicalFile
            canonicalFile.path.startsWith(canonicalRoot.path + File.separator) &&
                canonicalFile.isFile &&
                supportedScreenshotExtension(canonicalFile.extension) &&
                canonicalFile.length() > 8 &&
                canonicalFile.inputStream().use { input ->
                    val header = ByteArray(8)
                    input.read(header) == 8 && looksLikeScreenshot(header)
                }
        }.getOrDefault(false)
    }

    private fun screenshotRefs(
        documentPath: String,
        versionIndex: Int,
        timestamp: Long
    ): List<HistoryScreenshotRef> {
        val root = File(documentPath, "history_screenshots")
        if (!root.exists()) return emptyList()
        return root.listFiles { file -> file.isDirectory }.orEmpty().flatMap { channelDir ->
            channelDir.listFiles { file ->
                file.isFile && supportedScreenshotExtension(file.extension) &&
                    file.name.startsWith(String.format("v%05d_", versionIndex))
            }.orEmpty().map { file ->
                HistoryScreenshotRef(
                    channel = channelDir.name,
                    path = file.relativeTo(File(documentPath)).path,
                    timestamp = timestamp
                )
            }
        }.sortedWith(compareBy<HistoryScreenshotRef> { it.channel }.thenBy { it.path })
    }
}
