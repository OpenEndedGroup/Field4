@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package afield

import afield.four.util.openWindowsSnapshot
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
    private const val DEFAULT_MIN_CAPTURE_INTERVAL_MS = 0L

    private data class PendingCapture(
        val documentPath: String,
        val versionIndex: Int,
        val timestamp: Long
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
        if (versionIndex < 0) return
        pendingCaptures[documentPath] = PendingCapture(documentPath, versionIndex, timestamp)
        scheduleCapture(documentPath)
    }

    private fun scheduleCapture(documentPath: String) {
        if (!scheduledDocuments.add(documentPath)) return
        GlobalScope.launch(Runloop.miscPool.asCoroutineDispatcher()) {
            try {
                delay(configuredMillis(
                    property = "afield.history.fourScreenshotDelayMs",
                    env = "AFIELD_HISTORY_FOUR_SCREENSHOT_DELAY_MS",
                    defaultValue = DEFAULT_CAPTURE_DELAY_MS
                ))

                var pending = pendingCaptures.remove(documentPath) ?: return@launch
                val minInterval = configuredMillis(
                    property = "afield.history.fourScreenshotMinIntervalMs",
                    env = "AFIELD_HISTORY_FOUR_SCREENSHOT_MIN_INTERVAL_MS",
                    defaultValue = DEFAULT_MIN_CAPTURE_INTERVAL_MS
                )
                val elapsed = System.currentTimeMillis() - (lastCaptureAt[documentPath] ?: 0L)
                if (elapsed < minInterval) {
                    delay(minInterval - elapsed)
                    pending = pendingCaptures.remove(documentPath) ?: pending
                }

                lastCaptureAt[documentPath] = System.currentTimeMillis()
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
        val documentPath = pending.documentPath
        val versionIndex = pending.versionIndex
        val timestamp = pending.timestamp
        val windows = openWindowsSnapshot().filter { window ->
            val renderer = window.associatedRenderer
            renderer != null && renderer.supportsDirectWindowCapture(window)
        }
        if (windows.isEmpty()) return

        windows.forEachIndexed { index, window ->
            val renderer = window.associatedRenderer ?: return@forEachIndexed
            val channel = if (windows.size == 1) "four-window" else "four-window-${index + 1}"
            val reserved = HistoryMetrics.reserveScreenshotFile(
                documentPath = documentPath,
                versionIndex = versionIndex,
                channel = channel,
                timestamp = timestamp + index,
                width = window.width,
                height = window.height,
                extension = "jpg"
            ) ?: return@forEachIndexed
            val (file, ref) = reserved
            runCatching {
                renderer.captureWindowToFile(window, file.path)
                HistoryMetrics.addScreenshotRef(documentPath, versionIndex, ref)
                _send(
                    Message("history-screenshot-added").payload(
                        HistoryScreenshotAdded(versionIndex, ref)
                    ),
                    document = Regex.escape(documentPath)
                )
            }.onFailure {
                lprintln(
                    "warning,history",
                    "Failed to capture $channel for history version $versionIndex: ${it.message}"
                )
            }
        }
    }

    private fun fourWindowScreenshotsEnabled(): Boolean {
        val configured = System.getProperty("afield.history.fourScreenshots")
            ?: System.getenv("AFIELD_HISTORY_FOUR_SCREENSHOTS")
            ?: return true
        return !(configured.equals("false", ignoreCase = true) ||
            configured.equals("no", ignoreCase = true) ||
            configured == "0")
    }

    private fun configuredMillis(property: String, env: String, defaultValue: Long): Long {
        val value = System.getProperty(property) ?: System.getenv(env)
        return value?.toLongOrNull()?.coerceAtLeast(0L) ?: defaultValue
    }
}
