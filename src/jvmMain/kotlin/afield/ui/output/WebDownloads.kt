package afield.ui.output

import afield.app.notifications.notify
import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.document.workspace.FileManager
import afield.runtime.execution.go
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A file a `web(...)` page downloaded, from [onDownload]. [file] is where it was saved, in the
 * document's `files/downloads/`; [state] is `completed`, `cancelled` or `interrupted` (only a
 * completed one is whole), or `elsewhere` when Chromium finished it but saved it somewhere else
 * (it does when a test harness such as Playwright has taken over the tab's downloads), so [file]
 * does not exist. [box] is the uid of the box whose page it came from, when Field can tell
 * (a page with a profile), else empty.
 */
data class WebDownload(
    val url: String,
    val file: File,
    val state: String,
    val bytes: Long,
    val mimeType: String,
    val box: String,
) {
    /** True when the whole file arrived. */
    val completed: Boolean get() = state == "completed"
}

private class DownloadWatch(val document: SessionInformation, val downloads: Channel<WebDownload>)

private val downloadWatches = ConcurrentHashMap<Pair<String, String>, DownloadWatch>()

/**
 * Call [onDone] for every download a `web(...)` page in this document finishes from now on.
 *
 * A file a site offers for download (a link with `download`, an attachment, a file the page makes)
 * is saved, without asking, into the document's `files/downloads/`, under the name the site gave it
 * (` (1)`, ` (2)` ... added if that name is taken); this hears each one when it ends. One watch per
 * box and [name]: running the box again replaces it, stopping the box ends it. [onDone] runs on
 * Field's runloop. Needs Field's Electron window.
 *
 * ```kotlin
 * onDownload { d -> if (d.completed) println("got ${d.file.name}, ${d.bytes} bytes") }
 * ```
 */
fun Box.onDownload(name: String = "downloads", onDone: suspend (WebDownload) -> Unit) {
    val key = id to name
    val watch = DownloadWatch(inside, Channel(Channel.UNLIMITED))
    downloadWatches.put(key, watch)?.takeIf { it !== watch }?.downloads?.close()
    WebDownloads.tellElectron(inside)
    go {
        coroutineContext.job.invokeOnCompletion {
            downloadWatches.remove(key, watch)
            watch.downloads.close()
        }
        for (download in watch.downloads) onDone(download)
    }
}

/** The folder a document's web() downloads go to: `downloads/` in its `files/`. */
fun Box.webDownloadsFolder(): File = WebDownloads.folder(inside)

internal object WebDownloads {
    fun folder(document: SessionInformation): File =
        FileManager.forSession(document).file("%document/downloads").absoluteFile

    /** Tells the document's Electron tab where its web() downloads go. Quietly does nothing without one. */
    fun tellElectron(document: SessionInformation) {
        val folder = runCatching { folder(document) }.getOrNull() ?: return
        runCatching {
            LiveOutputIframeBridge.send(document, OutputIframeTarget("", ""), "profile", method = "downloads-dir",
                params = JsonObject(mapOf("folder" to JsonPrimitive(folder.path))))
        }
    }

    fun dispatch(document: SessionInformation, message: JsonObject) {
        fun str(key: String) = (message[key] as? JsonPrimitive)?.contentOrNull ?: ""
        val download = WebDownload(
            url = str("url"),
            file = File(str("file").ifEmpty { return }),
            state = str("state"),
            bytes = (message["bytes"] as? JsonPrimitive)?.longOrNull ?: 0,
            mimeType = str("mimeType"),
            box = str("boxUid"),
        )
        if (download.completed) notify("Downloaded ${download.file.name} into files/downloads", "web")
        for (watch in downloadWatches.values) {
            if (watch.document === document) watch.downloads.trySend(download)
        }
    }
}
