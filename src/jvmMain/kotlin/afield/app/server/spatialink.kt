package afield.app.server

import afield.io.spatialink.SpatialInkFitter
import afield.io.spatialink.SpatialInkRequest
import afield.io.spatialink.spatialInkJson
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Native spatial ink. Each connection owns its fit cache and its own workspace snapshot. */
internal fun Route.installSpatialInkRoutes(directory: File = File(home, ".spatial-ink")) {
    webSocket("/spatial-ink/ws") {
        if (refuseCrossSiteFieldWebSocket("/spatial-ink/ws")) return@webSocket
        val fitter = SpatialInkFitter()
        val session = UUID.randomUUID().toString()
        var previousRevision = 0
        for (frame in incoming) {
            if (frame !is Frame.Text || frame.data.size > 1536 * 1024) {
                close(CloseReason(CloseReason.Codes.TOO_BIG, "Expected a spatial ink text frame up to 1.5 MiB"))
                break
            }
            val text = frame.readText()
            val request = try {
                spatialInkJson.decodeFromString<SpatialInkRequest>(text).also { require(it.revision > previousRevision) }
            } catch (_: IllegalArgumentException) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid spatial ink snapshot"))
                break
            }
            val fitted = try {
                withContext(Dispatchers.Default) { fitter.update(request) }
            } catch (_: IllegalArgumentException) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid spatial ink geometry"))
                break
            }
            val reply = spatialInkJson.encodeToString(fitted)
            withContext(Dispatchers.IO) {
                Files.createDirectories(directory.toPath())
                saveSpatialInkSnapshot(File(directory, "$session.raw.json"), text)
                saveSpatialInkSnapshot(File(directory, "$session.json"), reply)
            }
            previousRevision = request.revision
            send(reply)
        }
    }
}

private fun saveSpatialInkSnapshot(file: File, text: String) {
    val temp = File(file.parentFile, file.name + ".tmp")
    try {
        temp.writeText(text)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temp.delete()
    }
}
