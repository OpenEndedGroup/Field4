package afield.app.server

import afield.document.graph.box_kind
import afield.document.graph.contents
import afield.document.workspace.FileManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

/** Only image references actually authored in this open text frame may resolve through FileManager. */
internal fun textFrameImageFile(info: SessionInformation, uid: String, spec: String): File? = runCatching {
    val box = info.boxes[uid] ?: return null
    if (box.box_kind != "text-frame" || box.getPropertyRaw("_tombstone") == true) return null
    if (spec.isBlank() || spec.length > 4096 || spec.contains('\u0000')) return null
    val references = Regex("""!\[[^\]]*\]\(([^)]+)\)""").findAll(box.contents).map { it.groupValues[1] }
    if (spec !in references) return null
    val file = FileManager.forSession(info).file(spec).canonicalFile
    file.takeIf { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "avif") && it.length() <= 20L * 1024 * 1024 }
}.getOrNull()

/** Resolve paths against this server's workspace; refuse ambiguous short document names. */
internal fun textFrameAssetDocument(open: Collection<SessionInformation>, key: String?, workspace: File): SessionInformation? {
    if (key.isNullOrBlank()) return null
    val sessions = open.distinct()
    val requested = runCatching { (if (File(key).isAbsolute) File(key) else File(workspace, key)).canonicalPath }.getOrNull()
    sessions.firstOrNull { runCatching { File(it.filename).canonicalPath == requested }.getOrDefault(false) }?.let { return it }
    return sessions.filter { it.filename == key || it.documentName == key }.singleOrNull()
}

/** Serve local Markdown images, including named directories and search-root paths. */
fun Route.installTextFrameAssetRoutes() {
    get("/api/text-frame-asset") {
        val key = call.request.queryParameters["document"]
        val info = textFrameAssetDocument(documents.values, key, File(home))
        val file = info?.let { textFrameImageFile(it, call.request.queryParameters["box"].orEmpty(), call.request.queryParameters["path"].orEmpty()) }
        if (file == null) call.respond(HttpStatusCode.NotFound)
        else {
            call.response.headers.append("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.respondFile(file)
        }
    }
}
