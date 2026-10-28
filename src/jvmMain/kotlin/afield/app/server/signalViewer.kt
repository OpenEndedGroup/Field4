package afield.app.server

import afield.ui.visualization.SignalViewer
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

/**
 * The HTTP side of the frontend signal viewer: one route that answers range queries over a
 * [SignalViewer] a box is currently showing.
 *
 * `GET /signal-viewer/{id}` takes a `start`, an `end` and a `points` count and returns that much of
 * the signal as JSON, which is how the plot zooms and pans without the box running again. The id
 * names an entry in the viewer's weak registry, so once a box replaces its output the viewer it was
 * showing becomes collectable and the route answers `410 Gone` rather than resampling a signal whose
 * evaluators have been released.
 *
 * Sampling is confined to two threads and abandoned after twelve seconds, and the window call is
 * handed a check that throws once the client has gone, so an abandoned zoom stops between sample
 * batches instead of finishing a query nobody will read.
 */

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
