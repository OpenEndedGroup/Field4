package afield.server

import afield.logger.lprintln
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import kotlin.concurrent.thread

private const val DOCS_ORIGIN = "https://openendedgroup.com"
private const val DOCS_ROOT = "/field4/"
private const val PROXY_ROOT = "/field-docs/"

@Serializable
data class DocsAnnotationAnchor(
    val exact: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val xRatio: Double? = null,
    val yRatio: Double? = null,
)

@Serializable
data class DocsAnnotation(
    val id: String = "",
    val pageUrl: String,
    val note: String,
    val tags: List<String> = emptyList(),
    val anchor: DocsAnnotationAnchor = DocsAnnotationAnchor(),
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
    val resolvedAtEpochMs: Long? = null,
    val resolution: String = "",
)

@Serializable private data class DocsAnnotationFile(val version: Int = 1, val annotations: List<DocsAnnotation> = emptyList())
@Serializable data class DocsAnnotationUpsert(val annotation: DocsAnnotation)
@Serializable data class DocsAnnotationResolve(val resolved: Boolean = true, val resolution: String = "")
@Serializable data class DocsImprovementLaunchRequest(val tags: List<String> = emptyList())
@Serializable data class DocsImprovementStatus(
    val running: Boolean = false,
    val status: String = "idle",
    val runId: String = "",
    val message: String = "",
    val startedAtEpochMs: Long = 0,
    val finishedAtEpochMs: Long = 0,
    val exitCode: Int? = null,
    val logTail: String = "",
)

private object DocsAnnotationStore {
    private val lock = Any()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    val path: Path = Paths.get(System.getProperty("user.home"), ".afield", "docs-annotations.json")

    fun list(): List<DocsAnnotation> = synchronized(lock) { read().annotations }

    fun upsert(input: DocsAnnotation): DocsAnnotation = synchronized(lock) {
        require(input.pageUrl.startsWith(DOCS_ORIGIN + DOCS_ROOT)) { "annotation page must be in Field docs" }
        require(input.note.isNotBlank()) { "annotation note is empty" }
        val now = System.currentTimeMillis()
        val old = read().annotations
        val id = input.id.ifBlank { UUID.randomUUID().toString() }
        val existing = old.firstOrNull { it.id == id }
        val saved = input.copy(
            id = id,
            tags = extractTags(input.note, input.tags),
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now,
        )
        write(old.filterNot { it.id == id } + saved)
        saved
    }

    fun resolve(id: String, request: DocsAnnotationResolve): DocsAnnotation? = synchronized(lock) {
        var result: DocsAnnotation? = null
        val now = System.currentTimeMillis()
        val updated = read().annotations.map {
            if (it.id != id) it else it.copy(
                resolvedAtEpochMs = if (request.resolved) now else null,
                resolution = if (request.resolved) request.resolution else "",
                updatedAtEpochMs = now,
            ).also { value -> result = value }
        }
        if (result != null) write(updated)
        result
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val current = read().annotations
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) false else true.also { write(updated) }
    }

    private fun extractTags(note: String, supplied: List<String>): List<String> =
        (supplied + Regex("(?<![\\w@])@([A-Za-z][A-Za-z0-9_-]{0,63})").findAll(note).map { it.groupValues[1] })
            .map { it.removePrefix("@").lowercase() }.filter { it.isNotBlank() }.distinct().sorted()

    private fun read(): DocsAnnotationFile = runCatching {
        if (!Files.exists(path)) DocsAnnotationFile()
        else json.decodeFromString<DocsAnnotationFile>(Files.readString(path))
    }.getOrElse {
        lprintln("warning,docs-annotations", "Could not read $path: ${it.message}")
        DocsAnnotationFile()
    }

    private fun write(annotations: List<DocsAnnotation>) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "docs-annotations-", ".json")
        Files.writeString(temp, json.encodeToString(DocsAnnotationFile(annotations = annotations)), StandardCharsets.UTF_8)
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

private object DocsImprovementTask {
    private val lock = Any()
    private var state = DocsImprovementStatus()
    fun status() = synchronized(lock) { state }
    fun start(tags: List<String>): DocsImprovementStatus = synchronized(lock) {
        if (state.running) return state
        val selected = DocsAnnotationStore.list().filter { it.resolvedAtEpochMs == null && (tags.isEmpty() || it.tags.any(tags::contains)) }
        if (selected.isEmpty()) return DocsImprovementStatus(message = "No unresolved annotations match this filter.")
        val runId = UUID.randomUUID().toString()
        state = DocsImprovementStatus(true, "running", runId, "Starting documentation improvement task.", System.currentTimeMillis())
        thread(name = "docs-annotation-improvement", isDaemon = true) { run(runId, selected) }
        state
    }
    private fun run(runId: String, notes: List<DocsAnnotation>) {
        val prompt = buildString {
            appendLine("Improve Field4 documentation in this checkout using the user annotations below.")
            appendLine("Make narrow source-grounded edits. After fixing a note, update ${DocsAnnotationStore.path} by setting resolvedAtEpochMs and a short resolution. Preserve unresolved notes and unrelated work.")
            notes.forEach { appendLine("- ${it.id} ${it.pageUrl} tags=${it.tags}: ${it.note} quote=${it.anchor.exact}") }
        }
        val log = StringBuilder()
        var code = -1
        try {
            val process = ProcessBuilder("codex", "exec", "--sandbox", "workspace-write", prompt)
                .directory(Paths.get(".").toAbsolutePath().normalize().toFile()).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (log.length < 120_000) log.appendLine(it) } }
            code = process.waitFor()
            finish(runId, code, if (code == 0) "Documentation improvement task finished." else "Documentation improvement task exited with $code.", log.toString())
        } catch (error: Exception) {
            finish(runId, code, "Could not run Codex: ${error.message}", log.appendLine(error.toString()).toString())
        }
    }
    private fun finish(runId: String, code: Int, message: String, log: String) = synchronized(lock) {
        if (state.runId == runId) state = state.copy(running = false, status = if (code == 0) "succeeded" else "failed", message = message, finishedAtEpochMs = System.currentTimeMillis(), exitCode = code, logTail = log.takeLast(120_000))
    }
}

private val docsProxyClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
    .followRedirects(HttpClient.Redirect.NORMAL).build()

fun Route.installDocsAnnotationRoutes() {
    get("/api/docs-annotations") {
        val page = call.request.queryParameters["page"]
        val tags = call.request.queryParameters.getAll("tag").orEmpty().map(String::lowercase)
        val from = call.request.queryParameters["from"]?.toLongOrNull()
        val to = call.request.queryParameters["to"]?.toLongOrNull()
        val includeResolved = call.request.queryParameters["resolved"] == "true"
        call.respond(DocsAnnotationStore.list().filter { item ->
            (page == null || item.pageUrl == page) && (tags.isEmpty() || item.tags.any(tags::contains)) &&
                (from == null || item.createdAtEpochMs >= from) && (to == null || item.createdAtEpochMs <= to) &&
                (includeResolved || item.resolvedAtEpochMs == null)
        })
    }
    post("/api/docs-annotations") { call.respond(DocsAnnotationStore.upsert(call.receive<DocsAnnotationUpsert>().annotation)) }
    put("/api/docs-annotations/{id}/resolve") {
        DocsAnnotationStore.resolve(call.parameters["id"].orEmpty(), call.receive<DocsAnnotationResolve>())?.let { call.respond(it) }
            ?: call.respond(HttpStatusCode.NotFound, "annotation not found")
    }
    delete("/api/docs-annotations/{id}") {
        if (DocsAnnotationStore.delete(call.parameters["id"].orEmpty())) call.respond(mapOf("ok" to true))
        else call.respond(HttpStatusCode.NotFound, "annotation not found")
    }
    get("/api/docs-improvement") { call.respond(DocsImprovementTask.status()) }
    post("/api/docs-improvement") { call.respond(DocsImprovementTask.start(call.receive<DocsImprovementLaunchRequest>().tags.map(String::lowercase))) }
    route(PROXY_ROOT.removeSuffix("/")) { get("{...}") { call.proxyFieldDocs() } }
}

private suspend fun ApplicationCall.proxyFieldDocs() {
    val incoming = URI(request.uri)
    val relative = incoming.rawPath.removePrefix(PROXY_ROOT).ifBlank { "index.html" }
    if (relative.contains("..")) return respond(HttpStatusCode.BadRequest, "invalid docs path")
    val target = URI.create("$DOCS_ORIGIN$DOCS_ROOT$relative" + incoming.rawQuery?.let { "?$it" }.orEmpty())
    val upstream = try {
        withContext(Dispatchers.IO) {
            docsProxyClient.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        }
    } catch (error: Exception) { return respond(HttpStatusCode.BadGateway, "documentation unavailable: ${error.message}") }
    val contentType = upstream.headers().firstValue(HttpHeaders.ContentType).orElse("application/octet-stream")
    if (contentType.lowercase().contains("text/html")) {
        var html = upstream.body().toString(StandardCharsets.UTF_8)
        val sauce = """<script>window.__FIELD_DOCS_UPSTREAM__=${Json.encodeToString(target.toString())};window.__FIELD_DOCS_PROXY__='$PROXY_ROOT';const fieldDocsAnnotationsScript=document.createElement('script');fieldDocsAnnotationsScript.src=window.location.origin+'/docsAnnotations.js';document.head.appendChild(fieldDocsAnnotationsScript);</script><base href="$DOCS_ORIGIN$DOCS_ROOT">"""
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
        val headMatch = head.find(html)
        html = if (headMatch != null) html.substring(0, headMatch.range.last + 1) + sauce + html.substring(headMatch.range.last + 1) else sauce + html
        respondText(html, ContentType.Text.Html, HttpStatusCode.fromValue(upstream.statusCode()))
    } else {
        respondBytes(upstream.body(), runCatching { ContentType.parse(contentType) }.getOrDefault(ContentType.Application.OctetStream), HttpStatusCode.fromValue(upstream.statusCode()))
    }
}
