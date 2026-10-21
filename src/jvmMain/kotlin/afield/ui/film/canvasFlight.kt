/**
 * Filming Field: [canvasFlight] flies the canvas through a document's bookmarks, live or to an MP4.
 *
 * The flight opens on the first bookmark and holds, then flies to each next one and holds there. Each
 * leg eases in and out, zooming in log space while the point at the centre of the window travels in a
 * straight line across the canvas. Every three.js view showing on the canvas turns slowly about its own
 * target meanwhile, and gets its camera back afterwards.
 *
 * Live, the flight runs in every window open on the document, on the browser's own clock. Filmed
 * (`record = "flight.mp4"`), it is rendered frame by frame in the capture page and encoded with
 * ffmpeg: the clock waits for each frame, so the film is smooth however long a frame takes.
 *
 * The arithmetic is `afield.app.canvas.CanvasFlightTimeline`; the browser side is
 * `CanvasFlightClient`; the recorder is `afield.tools.docs.recordCanvasFlight`. Docs:
 * `public/docs/filming_the_canvas.md`.
 */

package afield.ui.film

import afield.app.canvas.CanvasFlightSpec
import afield.app.canvas.CanvasFlightStop
import afield.app.canvas.CanvasFlightTimeline
import afield.app.protocol.AgentCanvasFlight
import afield.app.protocol.AgentCanvasFlightResponse
import afield.app.protocol.Message
import afield.app.protocol._send
import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.app.server.playCanvasFlight
import afield.app.server.playwrightScope
import afield.document.graph.Box
import afield.runtime.lifecycle.inMain
import afield.tools.docs.canvasFlightProblem
import afield.tools.docs.recordCanvasFlight
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** One stop of a [canvasFlight]: a bookmark, and optionally its own leg and hold in seconds. */
typealias FlightStop = CanvasFlightStop

/**
 * A canvas flight under way. A live flight is [done] at once (the windows fly it on their own); a
 * filmed one when its MP4 is written, and [result] then says how many frames went into it and where
 * each bookmark was reached.
 */
class CanvasFlight internal constructor(
    /** How long the flight lasts, holds included. */
    val durationSeconds: Double,
    /** The MP4 being filmed, or null for a live flight. */
    val path: String?,
    private val outcome: CompletableFuture<AgentCanvasFlightResponse>
) {
    val done: Boolean get() = outcome.isDone

    /** The finished film's report, or null while it is still filming. */
    val result: AgentCanvasFlightResponse? get() = outcome.getNow(null)

    /** Frames in the finished film, or null while it is still filming. */
    val frames: Int? get() = result?.frameCount

    /** Waits up to [timeoutSeconds] for the film to finish. Do not call it from box code's main thread. */
    fun await(timeoutSeconds: Double = 3600.0): AgentCanvasFlightResponse =
        outcome.get((timeoutSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)

    override fun toString(): String = when {
        path == null -> "canvas flight, ${"%.1f".format(durationSeconds)} s, live"
        !done -> "canvas flight, ${"%.1f".format(durationSeconds)} s, filming to $path"
        else -> "canvas flight, ${result?.message}"
    }
}

/**
 * Flies the canvas through [bookmarks] — names as the bookmarks panel shows them, or slots as
 * `"1"`..`"9"` — spending [legSeconds] flying to each and [holdSeconds] holding at each, the first
 * included. Every visible three.js view turns [orbitDegreesPerSecond] about its own target meanwhile
 * (0 keeps them still). A leg whose straight path would cross empty canvas zooms out on the way
 * unless [pullBack] is false.
 *
 * Without [record] the flight plays live in every window open on this document; any pan, zoom or
 * Escape stops it. With [record], a path to an `.mp4` (`~` is your home directory; see
 * [resolveFilmPath]), it is filmed frame by frame at [fps], [width] by [height] pixels
 * with [pixelRatio] pixels per CSS pixel of the window (null [width] and [height] keep the capture
 * window's size), and this box prints the frame count and the path when the film is written.
 *
 * ```kotlin
 * canvasFlight(listOf("overview", "plan", "close-ups"), legSeconds = 6.0, holdSeconds = 2.0)
 * canvasFlight(listOf("overview", "plan", "close-ups"), record = "~/Movies/field-flight.mp4")
 * ```
 */
fun Box.canvasFlight(
    bookmarks: List<String>,
    legSeconds: Double = 6.0,
    holdSeconds: Double = 2.0,
    orbitDegreesPerSecond: Double = 8.0,
    pullBack: Boolean = true,
    record: String? = null,
    fps: Int = 30,
    width: Int? = 1920,
    height: Int? = 1080,
    pixelRatio: Double = 1.5
): CanvasFlight = startCanvasFlight(
    CanvasFlightSpec(bookmarks.map { FlightStop(it) }, legSeconds, holdSeconds, orbitDegreesPerSecond, pullBack),
    record, fps, width, height, pixelRatio
)

/**
 * [canvasFlight] with a leg and hold per stop: `FlightStop("plan", legSeconds = 4.0, holdSeconds = 3.0)`.
 * A stop that leaves them null takes [legSeconds] and [holdSeconds].
 */
fun Box.canvasFlight(
    vararg stops: FlightStop,
    legSeconds: Double = 6.0,
    holdSeconds: Double = 2.0,
    orbitDegreesPerSecond: Double = 8.0,
    pullBack: Boolean = true,
    record: String? = null,
    fps: Int = 30,
    width: Int? = 1920,
    height: Int? = 1080,
    pixelRatio: Double = 1.5
): CanvasFlight = startCanvasFlight(
    CanvasFlightSpec(stops.toList(), legSeconds, holdSeconds, orbitDegreesPerSecond, pullBack),
    record, fps, width, height, pixelRatio
)

/** Stops a live canvas flight in every window open on this document, leaving the canvas where it is. */
fun Box.stopCanvasFlight() {
    val info = sessionOf(this)
    info.sessions.forEach { _send(it, Message("canvas-flight-stop")) }
}

/** A film's path: `~` is the home directory, and a relative path is against the box's working directory. */
fun resolveFilmPath(path: String): String {
    val expanded = if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.drop(1) else path
    return File(expanded).absoluteFile.normalize().path
}

private fun sessionOf(box: Box): SessionInformation =
    documents.values.firstOrNull { it.boxes[box.id] === box }
        ?: throw IllegalStateException("canvasFlight: box ${box.id} is not in an open document")

private fun Box.startCanvasFlight(
    spec: CanvasFlightSpec, record: String?, fps: Int, width: Int?, height: Int?, pixelRatio: Double
): CanvasFlight {
    val info = sessionOf(this)
    canvasFlightProblem(info, spec)?.let { throw IllegalArgumentException("canvasFlight: $it") }
    val duration = CanvasFlightTimeline(spec).duration
    if (record == null) {
        val reply = playCanvasFlight(info, spec)
        if (!reply.ok) throw IllegalStateException("canvasFlight: ${reply.message}")
        return CanvasFlight(duration, null, CompletableFuture.completedFuture(reply))
    }
    require(record.endsWith(".mp4", ignoreCase = true)) { "canvasFlight: record names an .mp4, got $record" }
    val path = resolveFilmPath(record)
    val outcome = CompletableFuture<AgentCanvasFlightResponse>()
    val box = this
    playwrightScope.launch {
        val reply = runCatching {
            recordCanvasFlight(info, AgentCanvasFlight(spec, path, fps, width, height, pixelRatio))
        }.getOrElse {
            AgentCanvasFlightResponse(ok = false, documentName = info.documentName, sessionKey = info.filename,
                path = path, message = it.message ?: it.toString())
        }
        outcome.complete(reply)
        inMain {
            if (reply.ok) box.println("Filmed ${reply.frameCount} frames (${"%.1f".format(reply.durationSec)} s at ${reply.fps} fps, " +
                "${reply.width}x${reply.height}, ${reply.threeViews} three.js view(s) turning) to ${reply.path}")
            else box.println("canvasFlight: the film failed: ${reply.message}")
        }
    }
    return CanvasFlight(duration, path, outcome)
}
