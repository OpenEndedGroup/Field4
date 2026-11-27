package afield.ui.output

import afield.app.server.SessionInformation
import afield.document.workspace.FileManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One page a `web(...)` page visited.
 *
 * [from] is the address the same page showed just before, so following [from] links walks back
 * through what a page was used for; it is null for a page's first address. [box] and [channel] say
 * which page it was: the box (and its name at the time, [boxName]) and the output area. [inPage] is
 * true for a change of address without a load (a link inside a single-page site, or a `#` anchor).
 * [title] is the page's title, empty until the page reported one.
 */
data class WebVisit(
    val id: String,
    val time: Instant,
    val url: String,
    val title: String,
    val from: String?,
    val box: String,
    val boxName: String,
    val channel: String,
    val status: Int,
    val inPage: Boolean,
) {
    /** The host part of [url], such as `en.wikipedia.org`; empty if it has none. */
    val host: String get() = runCatching { URI(url).host }.getOrNull() ?: ""
}

/**
 * The pages one [WebProfile]'s `web(...)` pages have visited, from [WebProfile.history].
 *
 * Field writes every visit to a file as it happens: `field-history.jsonl` in a profile's
 * directory, or `web-history.jsonl` in the document's `files/` for pages without a profile. So the
 * history stays with the document (or project) the profile is in, and reading it needs no
 * Electron window. Each line of the file is one JSON record, readable by anything.
 *
 * ```kotlin
 * webProfile("github").history.visits(host = "github.com").takeLast(10).forEach {
 *     println("${it.time}  ${it.title.ifEmpty { it.url }}")
 * }
 * ```
 */
class WebHistory internal constructor(
    /** The file the visits are written to; it may not exist yet. */
    val file: File,
) {
    /**
     * The visits recorded, oldest first. Every filter is optional: [since] and [until] bound the
     * time, [host] matches the host or any subdomain of it, [contains] looks in the address and
     * title (ignoring case), [box] keeps one box's pages (by uid or name), and [limit] keeps only the
     * most recent that many.
     */
    fun visits(since: Instant? = null, until: Instant? = null, host: String? = null, contains: String? = null,
               box: String? = null, limit: Int? = null): List<WebVisit> {
        val all = read()
        val filtered = all.filter { visit ->
            (since == null || !visit.time.isBefore(since)) &&
                (until == null || visit.time.isBefore(until)) &&
                (host == null || visit.host == host || visit.host.endsWith(".$host")) &&
                (contains == null || visit.url.contains(contains, ignoreCase = true) || visit.title.contains(contains, ignoreCase = true)) &&
                (box == null || visit.box == box || visit.boxName == box)
        }
        return if (limit != null) filtered.takeLast(limit) else filtered
    }

    /**
     * The links followed between pages: each pair is an address and the one visited straight after it
     * in the same page, with how many times that happened. A graph of how the profile's pages were
     * browsed, ready to draw.
     */
    fun links(since: Instant? = null, host: String? = null): Map<Pair<String, String>, Int> =
        visits(since = since, host = host).mapNotNull { visit -> visit.from?.let { it to visit.url } }
            .groupingBy { it }.eachCount()

    /** Forget every visit: the file is deleted. */
    fun clear() {
        synchronized(WebHistoryWriter.lock) { file.delete() }
    }

    /** Reads the file, applying title records to the visits they name. */
    private fun read(): List<WebVisit> {
        if (!file.isFile) return emptyList()
        val visits = LinkedHashMap<String, WebVisit>()
        val lines = synchronized(WebHistoryWriter.lock) { file.readLines() }
        for (line in lines) {
            val o = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            val id = str("id") ?: continue
            when (str("type")) {
                "visit" -> visits[id] = WebVisit(
                    id = id,
                    time = Instant.ofEpochMilli((o["time"] as? JsonPrimitive)?.longOrNull ?: 0),
                    url = str("url") ?: continue,
                    title = str("title") ?: "",
                    from = str("from"),
                    box = str("box") ?: "",
                    boxName = str("boxName") ?: "",
                    channel = str("channel") ?: "",
                    status = (o["status"] as? JsonPrimitive)?.intOrNull ?: 0,
                    inPage = (o["inPage"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
                "title" -> visits[id]?.let { visits[id] = it.copy(title = str("title") ?: it.title) }
            }
        }
        return visits.values.toList()
    }

    override fun toString() = "WebHistory($file)"
}

/** The history of this profile's pages. */
val WebProfile.history: WebHistory
    get() = WebHistory(directory?.let { File(it, PROFILE_HISTORY_FILE) } ?: sharedHistoryFile(document))

internal const val PROFILE_HISTORY_FILE = "field-history.jsonl"

internal fun sharedHistoryFile(document: SessionInformation): File =
    FileManager.forSession(document).file("%document/web-history.jsonl")

/** Writes visit and title reports from Electron to the history files, off the server's threads. */
internal object WebHistoryWriter {
    val lock = Any()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "field-web-history").apply { isDaemon = true } }

    /** The latest visit in each page (document, box, channel): its id and address. */
    private val latest = ConcurrentHashMap<Triple<SessionInformation, String, String>, Pair<String, String>>()

    /** A title that arrived before its page's visit (a fast page reports its title first): address to title. */
    private val earlyTitles = ConcurrentHashMap<Triple<SessionInformation, String, String>, Pair<String, String>>()

    fun report(document: SessionInformation, message: JsonObject) {
        fun str(key: String) = (message[key] as? JsonPrimitive)?.contentOrNull
        val boxUid = str("boxUid") ?: return
        val channel = str("channel") ?: ""
        val url = str("url") ?: return
        if (!url.startsWith("http:") && !url.startsWith("https:")) return
        val directory = str("directory").orEmpty()
        val page = Triple(document, boxUid, channel)
        val file = runCatching {
            if (directory.isNotEmpty()) File(directory, PROFILE_HISTORY_FILE) else sharedHistoryFile(document)
        }.getOrNull() ?: return
        val record: JsonObject = when (str("kind")) {
            "visit" -> {
                val previous = latest[page]
                if (previous?.second == url) return // a reload, or a second report of one navigation
                val id = UUID.randomUUID().toString()
                latest[page] = id to url
                val early = earlyTitles.remove(page)?.takeIf { it.first == url }?.second
                JsonObject(buildMap {
                    put("type", JsonPrimitive("visit"))
                    put("id", JsonPrimitive(id))
                    put("time", JsonPrimitive(System.currentTimeMillis()))
                    put("url", JsonPrimitive(url))
                    early?.let { put("title", JsonPrimitive(it)) }
                    previous?.second?.let { put("from", JsonPrimitive(it)) }
                    put("box", JsonPrimitive(boxUid))
                    put("boxName", JsonPrimitive(document.boxes[boxUid]?.name ?: ""))
                    put("channel", JsonPrimitive(channel))
                    put("status", JsonPrimitive((message["status"] as? JsonPrimitive)?.intOrNull ?: 0))
                    put("inPage", JsonPrimitive((message["inPage"] as? JsonPrimitive)?.booleanOrNull ?: false))
                })
            }
            "title" -> {
                val title = str("title") ?: return
                val (id, latestUrl) = latest[page] ?: (null to null)
                if (id == null || latestUrl != url) {
                    // Not this page's latest address: either its visit has not been reported yet,
                    // or the page has already moved on. Keep it for a visit to that address.
                    earlyTitles[page] = url to title
                    return
                }
                JsonObject(mapOf("type" to JsonPrimitive("title"), "id" to JsonPrimitive(id),
                    "title" to JsonPrimitive(title)))
            }
            else -> return
        }
        io.execute {
            synchronized(lock) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(record.toString() + "\n")
                }
            }
        }
    }
}

/** Visit and title reports go to [WebHistoryWriter]; called from [WebProfileEvents]. */
internal fun reportWebNavigation(document: SessionInformation, message: JsonElement) {
    (message as? JsonObject)?.let { WebHistoryWriter.report(document, it) }
}
