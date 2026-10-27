package afield.ai.conversation.chatlog

import afield.app.server.docsAccessPolicy
import afield.app.server.docsPassPolicy
import afield.app.server.refuseUnlessSameSiteDocsClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The lens viewer's two read routes ([ChatLensViewer]). Both answer only for a document that registered a chat
 * archive, and only to this machine and this site: chats can hold private work, and the archive may be another
 * workspace than the server's. Pictures are served only by blob name (`<sha16>.<ext>`) from the chat's own
 * `ai4_blobs/`.
 */

private val blobName = Regex("[0-9a-f]{16}\\.(jpeg|jpg|png|webp|gif)")

/**
 * Mounts `/api/chatlog/lens`, which answers [ChatLensViewer.payload] as JSON, and `/api/chatlog/blob`,
 * which serves one picture from a chat's own blob directory. Called once while the server builds its
 * routing.
 */
fun Route.installChatlogRoutes() {
    get("/api/chatlog/lens") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(docsAccessPolicy))) return@get
        val p = call.request.queryParameters
        val document = p["document"].orEmpty()
        val chat = p["chat"].orEmpty()
        val result = runCatching { ChatLensViewer.payload(document, chat, requireNotNull(p["lens"]?.ifBlank { null }) { "which lens?" }) }
        result.onSuccess { call.respondText(Json.encodeToString(JsonObject.serializer(), it), ContentType.Application.Json) }
            .onFailure { call.respondText(it.message ?: "not found", status = HttpStatusCode.NotFound) }
    }
    get("/api/chatlog/blob") {
        if (call.refuseUnlessSameSiteDocsClient(docsPassPolicy(docsAccessPolicy))) return@get
        val p = call.request.queryParameters
        val ref = p["ref"].orEmpty()
        val file = runCatching {
            require(blobName.matches(ref))
            ChatLensViewer.store(p["document"])!!.blobFile(p["chat"].orEmpty(), ref)
        }.getOrNull()
        if (file == null) call.respond(HttpStatusCode.NotFound)
        else {
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.respondFile(file)
        }
    }
}
