package afield.app.server

import afield.document.workspace.*
import afield.document.graph.Box
import afield.document.persistence.DocumentShutdownFlush
import afield.ui.output.FieldMarkdown
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class KitPackageRequest(val path: String = "", val id: String = "", val startingPoint: String = "", val name: String = "", val document: String = "")

@Serializable
data class KitLibraryResponse(val kits: List<KitPackageStatus>, val restartRequired: Boolean, val bundled: List<String>)

/** Local kit lifecycle APIs used by Welcome; registration never executes the package's code. */
fun Route.installKitRoutes(restart: () -> Unit) {
    suspend fun library(): KitLibraryResponse = withContext(Dispatchers.IO) {
        val installed = KitPackages.installed().associate { it.id to it.revision }
        val bundled = File(app, "templates").listFiles().orEmpty().filter { File(it, KitPackages.MANIFEST).isFile }.map { it.path }
        KitLibraryResponse(KitPackages.statuses(KitRuntime.errors), installed != KitPackages.active(), bundled)
    }
    get("/api/kits") { call.respond(library()) }
    post("/api/kits/install") {
        try {
            val request = call.receive<KitPackageRequest>()
            withContext(Dispatchers.IO) { KitPackages.install(File(request.path.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home")))) }
            call.respond(library())
        } catch (error: Exception) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "Kit installation failed"))) }
    }
    post("/api/kits/remove") {
        val request = call.receive<KitPackageRequest>()
        withContext(Dispatchers.IO) { KitPackages.remove(request.id) }
        call.respond(library())
    }
    get("/api/kits/restart-status") { call.respond(KitRestarts.status) }
    post("/api/kits/restart") {
        try {
            restart()
            call.respond(KitRestarts.status)
        } catch (error: Exception) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "Could not prepare kit runtime"))) }
    }
    post("/api/kits/create") {
        try {
            val request = call.receive<KitPackageRequest>()
            val response = withContext(Dispatchers.IO) { KitInstances.create(File(home), request.id, request.startingPoint, request.name) }
            recordRecentDocument(response.filename)
            broadcastWelcomeEvent("kit-document-created")
            call.respond(response)
        } catch (error: Exception) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "Could not create kit document"))) }
    }
    post("/api/kits/open-host") {
        try {
            val request = call.receive<KitPackageRequest>()
            val kit = KitPackages.installed().singleOrNull { it.id == request.id } ?: error("Kit is not installed")
            val url = withContext(Dispatchers.IO) { KitDedicatedHosts.open(kit, request.startingPoint.ifBlank { null }) }
            call.respond(mapOf("url" to url))
        } catch (error: Exception) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "Could not open kit host"))) }
    }
    get("/api/kits/guide") {
        val id = call.request.queryParameters["id"]
        val kit = KitPackages.installed().singleOrNull { it.id == id }
        if (kit == null) { call.respond(HttpStatusCode.NotFound); return@get }
        val manifest = KitPackages.read(File(kit.directory))
        val markdown = if (manifest.guide.isBlank()) manifest.description else KitPackages.child(File(kit.directory), manifest.guide).readText()
        call.response.headers.append("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'")
        call.respondText("<!doctype html><meta charset=utf-8><style>body{background:#000;color:#eee;font:16px/1.5 Iosevka,monospace;padding:24px;max-width:900px}a{color:inherit}pre{white-space:pre-wrap}</style>" + FieldMarkdown.render(markdown), ContentType.Text.Html)
    }
    get("/api/kits/instances") {
        val records = withContext(Dispatchers.IO) {
            File(home).listFiles().orEmpty().filter { it.isDirectory && File(it, KitInstances.RECORD).isFile }
                .map { document -> KitInstances.inspect(document) }
        }
        call.respond(records)
    }
    post("/api/kits/inspect") {
        try {
            val request = call.receive<KitPackageRequest>()
            val document = KitPackages.child(File(home), request.document)
            require(document.parentFile.canonicalFile == File(home).canonicalFile) { "Use a workspace document name" }
            call.respond(withContext(Dispatchers.IO) { KitInstances.inspect(document) })
        } catch (error: Exception) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "Could not inspect kit instance"))) }
    }
    post("/api/kits/migrate") {
        try {
            val request = call.receive<KitPackageRequest>()
            val document = KitPackages.child(File(home), request.document)
            require(document.parentFile.canonicalFile == File(home).canonicalFile) { "Use a workspace document name" }
            val live = documents.values.firstOrNull { File(it.filename).canonicalFile == document.canonicalFile }
            require(live == null || (live.sessions.isEmpty() && !live.keepRunningWhenDetached)) { "Close this document and its running session before updating" }
            if (live != null) {
                DocumentShutdownFlush.saveIfPending(live, inline = true, reason = "kit-migration")
                live.cleanup()
                documents.entries.removeIf { it.value === live }
            }
            call.respond(withContext(Dispatchers.IO) { KitInstances.migrate(document) })
        } catch (error: Exception) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "Could not migrate kit instance"))) }
    }
    post("/api/kits/activate") {
        try {
            val request = call.receive<KitPackageRequest>()
            val info = documents.values.singleOrNull { it.documentName == request.document && !it.ephemeral }
            if (info == null || (info.sessions.isEmpty() && !info.keepRunningWhenDetached)) {
                val directory = KitPackages.child(File(home), request.document)
                require(directory.parentFile.canonicalFile == File(home).canonicalFile && KitInstances.record(directory) != null) { "Unknown kit document" }
                call.respond(mapOf("url" to ("/index.html?file=" + java.net.URLEncoder.encode(request.document, Charsets.UTF_8))))
                return@post
            }
            KitRuntime.activate(info)
            val boxes = KitRuntime.launchBoxes(info) ?: error("This document has no package record")
            Box.withBoxContextNow(info.root) { launchBoxesInOrder(boxes) }
            call.respond(mapOf("message" to "Launching ${boxes.size} kit boxes in order."))
        } catch (error: Exception) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "Could not activate kit"))) }
    }
}
