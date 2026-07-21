@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package afield

import afield.four.util.openWindowsSnapshot
import afield.logger.SaveReloadLog
import afield.logger.lprintln
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object HistoryScreenshotCapture {
    private const val DEFAULT_CAPTURE_DELAY_MS = 250L
    private const val DEFAULT_MIN_CAPTURE_INTERVAL_MS = 30_000L
    private const val DEFAULT_MAX_CAPTURE_PIXELS = 12_200_000L

    private data class PendingCapture(
        val documentPath: String,
        val versionIndex: Int,
        val timestamp: Long
    )

    private data class CapturableWindow(
        val window: afield.four.util.Window,
        val pixels: Long
    )

    private val pendingCaptures = ConcurrentHashMap<String, PendingCapture>()
    private val scheduledDocuments = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val lastCaptureAt = ConcurrentHashMap<String, Long>()

    fun captureFourWindowsOnSave(
        documentPath: String,
        versionIndex: Int = HistoryMetrics.currentVersionIndex(documentPath),
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (!fourWindowScreenshotsEnabled()) return
        if (versionIndex < 0) {
            SaveReloadLog.log(
                "history-four-screenshot-skip",
                "doc=$documentPath reason=no-current-version versionIndex=$versionIndex"
            )
            return
        }
        pendingCaptures[documentPath] = PendingCapture(documentPath, versionIndex, timestamp)
        scheduleCapture(documentPath)
        SaveReloadLog.log(
            "history-four-screenshot-scheduled",
            "doc=$documentPath versionIndex=$versionIndex timestamp=$timestamp pending=${pendingCaptures.size}"
        )
    }

    private fun scheduleCapture(documentPath: String) {
        if (!scheduledDocuments.add(documentPath)) return
        GlobalScope.launch(Runloop.miscPool.asCoroutineDispatcher()) {
            val scheduledAt = System.nanoTime()
            var delayMs = 0L
            var minIntervalDelayMs = 0L
            try {
                delayMs = configuredMillis(
                    property = "afield.history.fourScreenshotDelayMs",
                    env = "AFIELD_HISTORY_FOUR_SCREENSHOT_DELAY_MS",
                    defaultValue = DEFAULT_CAPTURE_DELAY_MS
                )
                delay(delayMs)

                var pending = pendingCaptures.remove(documentPath) ?: return@launch
                val minInterval = configuredMillis(
                    property = "afield.history.fourScreenshotMinIntervalMs",
                    env = "AFIELD_HISTORY_FOUR_SCREENSHOT_MIN_INTERVAL_MS",
                    defaultValue = DEFAULT_MIN_CAPTURE_INTERVAL_MS
                )
                val elapsed = System.currentTimeMillis() - (lastCaptureAt[documentPath] ?: 0L)
                if (elapsed < minInterval) {
                    minIntervalDelayMs = minInterval - elapsed
                    delay(minIntervalDelayMs)
                    pending = pendingCaptures.remove(documentPath) ?: pending
                }

                lastCaptureAt[documentPath] = System.currentTimeMillis()
                SaveReloadLog.log(
                    "history-four-screenshot-start",
                    "doc=$documentPath versionIndex=${pending.versionIndex} delayMs=$delayMs minIntervalDelayMs=$minIntervalDelayMs queueWaitMs=${
                        SaveReloadLog.elapsedMillis(
                            scheduledAt
                        )
                    }"
                )
                captureNow(pending)
            } finally {
                scheduledDocuments.remove(documentPath)
                if (pendingCaptures.containsKey(documentPath)) {
                    scheduleCapture(documentPath)
                }
            }
        }
    }

    private suspend fun captureNow(pending: PendingCapture) {
        val totalStartedAt = System.nanoTime()
        val documentPath = pending.documentPath
        val versionIndex = pending.versionIndex
        val timestamp = pending.timestamp
        val windowsStartedAt = System.nanoTime()
        val maxPixels = configuredLong(
            property = "afield.history.fourScreenshotMaxPixels",
            env = "AFIELD_HISTORY_FOUR_SCREENSHOT_MAX_PIXELS",
            defaultValue = DEFAULT_MAX_CAPTURE_PIXELS
        )
        var unsupportedCount = 0
        var oversizedCount = 0
        var foreignCount = 0
        // Only capture windows that belong to the document being saved. Windows are process-global,
        // so without this a save of document A would snapshot document B's renderer window (or a
        // leaked/stale window) into A's history — the root of the four-window / four-window-1 /
        // four-window-2 channel fragmentation. When we can't resolve the document's own windows
        // (e.g. its renderer isn't held as a box property), fall back to all open windows so we
        // never regress capture for documents that don't follow the convention.
        val ownedWindows = documentOwnedWindows(documentPath)
        val windows = openWindowsSnapshot().mapNotNull { window ->
            if (ownedWindows.isNotEmpty() && window !in ownedWindows) {
                foreignCount++
                return@mapNotNull null
            }
            val renderer = window.associatedRenderer
            if (renderer == null || !renderer.supportsDirectWindowCapture(window)) {
                unsupportedCount++
                return@mapNotNull null
            }
            val pixels = window.width.toLong() * window.height.toLong()
            if (maxPixels > 0L && pixels > maxPixels) {
                oversizedCount++
                SaveReloadLog.log(
                    "history-four-screenshot-skip",
                    "doc=$documentPath versionIndex=$versionIndex reason=oversized-window size=${window.width}x${window.height} pixels=$pixels maxPixels=$maxPixels"
                )
                return@mapNotNull null
            }
            CapturableWindow(window, pixels)
        }
        val windowsMs = SaveReloadLog.elapsedMillis(windowsStartedAt)
        if (windows.isEmpty()) {
            SaveReloadLog.logTiming(
                "history-four-screenshot-capture",
                totalStartedAt,
                "doc=$documentPath versionIndex=$versionIndex windows=0 owned=${ownedWindows.size} foreign=$foreignCount unsupported=$unsupportedCount oversized=$oversizedCount maxPixels=$maxPixels windowsMs=$windowsMs"
            )
            return
        }

        windows.forEachIndexed { index, candidate ->
            val window = candidate.window
            val windowStartedAt = System.nanoTime()
            val renderer = window.associatedRenderer ?: return@forEachIndexed
            val channel = if (windows.size == 1) "four-window" else "four-window-${index + 1}"
            var reserveMs = 0L
            var captureMs = 0L
            var addRefMs = 0L
            var metricsRefWritten = false
            var sendMs = 0L
            var ok = false
            var error = ""
            val reserveStartedAt = System.nanoTime()
            val reserved = HistoryMetrics.reserveScreenshotFile(
                documentPath = documentPath,
                versionIndex = versionIndex,
                channel = channel,
                timestamp = timestamp + index,
                width = window.width,
                height = window.height,
                extension = "jpg"
            )
            reserveMs = SaveReloadLog.elapsedMillis(reserveStartedAt)
            if (reserved == null) {
                SaveReloadLog.log(
                    "history-four-screenshot-window",
                    "ms=${SaveReloadLog.elapsedMillis(windowStartedAt)} doc=$documentPath versionIndex=$versionIndex channel=$channel ok=false reason=reserve-failed reserveMs=$reserveMs size=${window.width}x${window.height}"
                )
                return@forEachIndexed
            }
            val (file, ref) = reserved
            runCatching {
                val captureStartedAt = System.nanoTime()
                renderer.captureWindowToFile(window, file.path)
                captureMs = SaveReloadLog.elapsedMillis(captureStartedAt)
                if (fourWindowScreenshotMetricsEnabled()) {
                    val addRefStartedAt = System.nanoTime()
                    HistoryMetrics.addScreenshotRef(documentPath, versionIndex, ref)
                    addRefMs = SaveReloadLog.elapsedMillis(addRefStartedAt)
                    metricsRefWritten = true
                }
                val sendStartedAt = System.nanoTime()
                _send(
                    Message("history-screenshot-added").payload(
                        HistoryScreenshotAdded(versionIndex, ref)
                    ),
                    document = Regex.escape(documentPath)
                )
                sendMs = SaveReloadLog.elapsedMillis(sendStartedAt)
                ok = true
            }.onFailure {
                error = it.message ?: it::class.simpleName ?: "unknown"
                lprintln(
                    "warning,history",
                    "Failed to capture $channel for history version $versionIndex: ${it.message}"
                )
            }.also {
                val windowMs = SaveReloadLog.elapsedMillis(windowStartedAt)
                SaveReloadLog.log(
                    "history-four-screenshot-window",
                    "ms=$windowMs doc=$documentPath versionIndex=$versionIndex channel=$channel ok=$ok error=$error reserveMs=$reserveMs captureMs=$captureMs addRefMs=$addRefMs metricsRefWritten=$metricsRefWritten sendMs=$sendMs size=${window.width}x${window.height} pixels=${candidate.pixels} maxPixels=$maxPixels path=${ref.path}"
                )
                if (windowMs >= 1000L) {
                    lprintln(
                        "warning,history",
                        "slow four screenshot doc=$documentPath channel=$channel ms=$windowMs captureMs=$captureMs addRefMs=$addRefMs"
                    )
                }
            }
        }
        SaveReloadLog.logTiming(
            "history-four-screenshot-capture",
            totalStartedAt,
            "doc=$documentPath versionIndex=$versionIndex windows=${windows.size} owned=${ownedWindows.size} foreign=$foreignCount unsupported=$unsupportedCount oversized=$oversizedCount maxPixels=$maxPixels windowsMs=$windowsMs"
        )
    }

    // The set of afield.four windows owned by [documentPath], discovered by scanning that
    // document's boxes for Renderer-typed property values (renderers are held as box properties;
    // see agent_backdoor's getPropertyRaw("renderer")) and collecting their windows. Naming-
    // agnostic — matches any property that holds a Renderer, not just "renderer". Returns empty
    // when the document or its renderers can't be resolved, which the caller treats as "capture
    // all open windows" (prior behavior).
    private fun documentOwnedWindows(documentPath: String): Set<afield.four.util.Window> {
        val sessionInfo = documents.values.firstOrNull {
            it.filename == documentPath ||
                    it.documentName == documentPath ||
                    java.io.File(it.filename).name == documentPath
        } ?: return emptySet()
        val windows = LinkedHashSet<afield.four.util.Window>()
        runCatching {
            sessionInfo.boxes.values.toList().forEach { box ->
                box.properties.values.toList().forEach { value ->
                    if (value is afield.four.Renderer) windows += value.windows
                }
            }
        }
        return windows
    }

    private fun fourWindowScreenshotsEnabled(): Boolean {
        val configured = System.getProperty("afield.history.fourScreenshots")
            ?: System.getenv("AFIELD_HISTORY_FOUR_SCREENSHOTS")
            ?: return true
        return !(configured.equals("false", ignoreCase = true) ||
                configured.equals("no", ignoreCase = true) ||
                configured == "0")
    }

    private fun fourWindowScreenshotMetricsEnabled(): Boolean {
        val configured = System.getProperty("afield.history.fourScreenshotWriteMetrics")
            ?: System.getenv("AFIELD_HISTORY_FOUR_SCREENSHOT_WRITE_METRICS")
            ?: return false
        return configured.equals("true", ignoreCase = true) ||
                configured.equals("yes", ignoreCase = true) ||
                configured == "1"
    }

    private fun configuredMillis(property: String, env: String, defaultValue: Long): Long {
        val value = System.getProperty(property) ?: System.getenv(env)
        return value?.toLongOrNull()?.coerceAtLeast(0L) ?: defaultValue
    }

    private fun configuredLong(property: String, env: String, defaultValue: Long): Long {
        val value = System.getProperty(property) ?: System.getenv(env)
        return value?.toLongOrNull()?.coerceAtLeast(0L) ?: defaultValue
    }
}
