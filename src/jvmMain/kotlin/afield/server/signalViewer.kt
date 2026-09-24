package afield.server

import afield.stdlib.SignalViewer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** Read-only, opaque source IDs; never accepts or compiles code from a browser request. */
fun Route.installSignalViewerRoutes() {
    val sampling = Dispatchers.Default.limitedParallelism(2)
    get("/signal-viewer/{id}") {
        val source = SignalViewer.find(call.parameters["id"].orEmpty())
        if (source == null) {
            call.respondText("Signal expired. Run the source box again.", status = HttpStatusCode.Gone)
            return@get
        }
        try {
            val q = call.request.queryParameters
            val from = q["start"]?.toDoubleOrNull() ?: throw IllegalArgumentException("Missing start")
            val to = q["end"]?.toDoubleOrNull() ?: throw IllegalArgumentException("Missing end")
            val points = q["points"]?.toIntOrNull() ?: throw IllegalArgumentException("Missing points")
            val result = withContext(sampling) {
                withTimeout(12_000) {
                    val context = coroutineContext
                    source.window(from, to, points) { context.ensureActive() }
                }
            }
            call.response.headers.append("Cache-Control", "no-store")
            call.respondText(result.toJson(), ContentType.Application.Json)
        } catch (e: IllegalArgumentException) {
            call.respondText(e.message ?: "Invalid signal window", status = HttpStatusCode.BadRequest)
        }
    }
}
