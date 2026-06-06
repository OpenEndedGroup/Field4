@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package afield

import afield.four.util.openWindowsSnapshot
import afield.logger.lprintln
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

object HistoryScreenshotCapture {
    fun captureFourWindowsOnSave(
        documentPath: String,
        versionIndex: Int = HistoryMetrics.currentVersionIndex(documentPath),
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (versionIndex < 0) return
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
                height = window.height
            ) ?: return@forEachIndexed
            val (file, ref) = reserved
            GlobalScope.launch(Runloop.miscPool.asCoroutineDispatcher()) {
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
    }
}
