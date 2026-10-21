/**
 * Filming a canvas flight to MP4, one frame at a time.
 *
 * [recordCanvasFlight] drives the flight's clock in the capture page (the server's Playwright page, or
 * the Electron window after `playwright-open --electron`): for each frame it sets the time
 * (`window.fieldCanvasFlight.seek`, `CanvasFlightClient` in the frontend), waits until the browser has
 * painted that frame, captures it over CDP and pipes the JPEG to ffmpeg. Nothing runs on the wall
 * clock, so a frame that takes a second to capture is the same frame as one that takes ten
 * milliseconds, and a loaded machine films the same movie as an idle one. Live screen capture would
 * drop frames instead.
 *
 * The page is sized for the film first (`width` by `height` pixels at `pixelRatio`, through CDP device
 * metrics) and put back afterwards. The report says how many frames were captured and encoded (counted
 * again by ffprobe), and for every stop the frame that reaches it and the camera in that frame.
 */

package afield.tools.docs

import afield.app.canvas.BookmarkData
import afield.app.canvas.CanvasFlightSpec
import afield.app.canvas.CanvasFlightTimeline
import afield.app.diagnostics.logging.lprintln
import afield.app.protocol.AgentCanvasFlight
import afield.app.protocol.AgentCanvasFlightResponse
import afield.app.protocol.CanvasFlightCamera
import afield.app.protocol.CanvasFlightStopReport
import afield.app.server.SessionInformation
import afield.app.server.ensurePlaywrightForSessionIfNeeded
import afield.app.server.globalPlaywriteContext
import afield.app.server.playwrightDispatcher
import com.google.gson.JsonObject
import com.microsoft.playwright.CDPSession
import com.microsoft.playwright.Page
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val flightJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** How long one frame may take to set and paint before the page counts as stalled. */
private const val FLIGHT_FRAME_TIMEOUT_MS = 20_000

/** A filmed stop counts as reached within this many CSS px of its translation, and this fraction of its zoom. */
private const val FLIGHT_REACHED_PX = 1.0
private const val FLIGHT_REACHED_ZOOM = 1e-3

/** This document's bookmarks, as the root box's `bookmarks` property holds them. */
fun documentBookmarks(info: SessionInformation): BookmarkData {
    val raw = info.root.properties["bookmarks"] as? String
    if (raw.isNullOrBlank()) return BookmarkData(bookmarks = emptyList())
    return runCatching { flightJson.decodeFromString(BookmarkData.serializer(), raw) }
        .getOrElse { BookmarkData(bookmarks = emptyList()) }
}

/** The first problem with flying [spec] in [info]'s document, or null: a bad value or a bookmark it lacks. */
fun canvasFlightProblem(info: SessionInformation, spec: CanvasFlightSpec): String? {
    spec.problem()?.let { return it }
    val bookmarks = documentBookmarks(info)
    spec.stops.forEach { stop ->
        val found = bookmarks.find(stop.bookmark) ?: return bookmarks.describeMissing(stop.bookmark)
        if (found.scrollPosition == null) return "Bookmark \"${found.name}\" recorded no view"
    }
    return null
}

/** Frames in a film of [durationSec] at [fps]: the first at 0, the last at the end. */
internal fun canvasFlightFrameCount(durationSec: Double, fps: Int): Int =
    maxOf(1, (durationSec * fps).roundToInt())

/** The flight time of frame [index] of [frames]: steps of 1/[fps], the last frame exactly at the end. */
internal fun canvasFlightFrameTime(index: Int, frames: Int, fps: Int, durationSec: Double): Double =
    if (index >= frames - 1) durationSec else minOf(durationSec, index.toDouble() / fps)

/** The first frame at or after [arrivalSec]. */
internal fun canvasFlightArrivalFrame(arrivalSec: Double, frames: Int, fps: Int): Int =
    minOf(frames - 1, ceil(arrivalSec * fps - 1e-6).toInt().coerceAtLeast(0))

private fun failed(info: SessionInformation, request: AgentCanvasFlight, message: String) = AgentCanvasFlightResponse(
    ok = false, documentName = info.documentName, sessionKey = info.filename, path = request.path, message = message
)

/**
 * Films [request]'s flight in [info]'s document to `request.path`. Runs on the Playwright thread;
 * opens the capture page on the document if it is not there already.
 */
suspend fun recordCanvasFlight(info: SessionInformation, request: AgentCanvasFlight): AgentCanvasFlightResponse =
    withContext(playwrightDispatcher) {
        val path = request.path ?: return@withContext failed(info, request, "Filming a flight needs a path for the .mp4")
        // The bookmarks are checked by the page that films, against what it shows: a script that has
        // just saved one in that page may be ahead of the server's copy of the root box.
        request.spec.problem()?.let { return@withContext failed(info, request, it) }
        val fps = request.fps
        if (fps !in 1..120) return@withContext failed(info, request, "fps must be 1 to 120, got $fps")
        val sized = request.width != null && request.height != null
        if (sized && (request.width!! !in 64..7680 || request.height!! !in 64..4320))
            return@withContext failed(info, request, "a film is 64x64 to 7680x4320 pixels, got ${request.width}x${request.height}")
        if (!request.pixelRatio.isFinite() || request.pixelRatio !in 0.5..4.0)
            return@withContext failed(info, request, "pixelRatio must be 0.5 to 4, got ${request.pixelRatio}")

        val opened = runCatching { ensurePlaywrightForSessionIfNeeded(info) }.getOrElse {
            return@withContext failed(info, request, "Could not open the capture page: ${it.message}")
        }
        if (!opened.ok) return@withContext failed(info, request, "Could not open the capture page: ${opened.error}")
        val page = globalPlaywriteContext ?: return@withContext failed(info, request, "Playwright is not active")
        runCatching { filmFlight(info, page, request, path) }.getOrElse {
            lprintln("warning,video", "Canvas flight film to $path failed: ${it.message}")
            failed(info, request, it.message ?: it::class.simpleName ?: "canvas flight film failed")
        }
    }

private fun filmFlight(info: SessionInformation, page: Page, request: AgentCanvasFlight, path: String): AgentCanvasFlightResponse {
    val fps = request.fps
    val outputPath = Path.of(path).toAbsolutePath()
    outputPath.parent?.let { Files.createDirectories(it) }
    val cdp = page.context().newCDPSession(page)
    val playwrightViewport = page.viewportSize()
    var resized = false
    var began = false
    try {
        if (request.width != null && request.height != null) {
            cdp.send("Emulation.setDeviceMetricsOverride", JsonObject().apply {
                addProperty("width", (request.width / request.pixelRatio).roundToInt())
                addProperty("height", (request.height / request.pixelRatio).roundToInt())
                addProperty("deviceScaleFactor", request.pixelRatio)
                addProperty("mobile", false)
            })
            resized = true
        }
        // Let the window, the canvas panes and the three.js views take the new size before the flight
        // measures the pane it flies in.
        repeat(3) { awaitPaintedFrames(page, "canvas flight: settling the window size") }

        val specJson = flightJson.encodeToString(CanvasFlightSpec.serializer(), request.spec)
        @Suppress("UNCHECKED_CAST")
        val begin = page.evaluate("(spec) => window.fieldCanvasFlight ? window.fieldCanvasFlight.beginRecording(spec) : ({ok:false, error:'this page has no canvas flight (an older Field?)'})", specJson) as? Map<String, Any?>
            ?: error("the page did not answer beginRecording")
        if (begin["ok"] != true) error(begin["error"]?.toString() ?: "the flight could not start")
        began = true
        val duration = (begin["duration"] as Number).toDouble()
        check(abs(duration - CanvasFlightTimeline(request.spec).duration) < 1e-6) {
            "the page's flight lasts $duration s, the server's ${CanvasFlightTimeline(request.spec).duration} s"
        }
        val threeViews = (begin["viewers"] as? Number)?.toInt() ?: 0
        @Suppress("UNCHECKED_CAST")
        val stops = (begin["stops"] as? List<Map<String, Any?>>).orEmpty()
        val frames = canvasFlightFrameCount(duration, fps)
        val arrivalFrames = stops.map { canvasFlightArrivalFrame((it["arrival"] as Number).toDouble(), frames, fps) }
        val filmed = arrayOfNulls<CanvasFlightCamera>(stops.size)

        val encoder = FlightEncoder(outputPath, fps, request.width, request.height)
        var width = 0
        var height = 0
        try {
            for (i in 0 until frames) {
                val t = canvasFlightFrameTime(i, frames, fps, duration)
                @Suppress("UNCHECKED_CAST")
                val shown = evaluateWithin(page, "(t) => window.fieldCanvasFlight.seek(t)", t,
                    FLIGHT_FRAME_TIMEOUT_MS, "canvas flight: painting frame ${i + 1} of $frames") as? Map<String, Any?>
                    ?: error("frame ${i + 1}: the page returned no camera")
                arrivalFrames.forEachIndexed { s, f -> if (f == i) filmed[s] = camera(shown) }
                val jpeg = captureJpeg(cdp)
                if (i == 0) imageSize(jpeg)?.let { (w, h) -> width = w; height = h }
                encoder.write(jpeg)
                if ((i + 1) % (fps * 5) == 0) lprintln("info,video", "Canvas flight: ${i + 1} of $frames frames")
            }
        } catch (t: Throwable) {
            encoder.abandon()
            throw t
        }
        encoder.close()
        val encodedFrames = countEncodedFrames(outputPath)
        val reports = stops.mapIndexed { s, stop ->
            val target = camera(stop["target"])
            val got = filmed[s]
            CanvasFlightStopReport(
                bookmark = stop["bookmark"].toString(),
                name = stop["name"].toString(),
                slot = (stop["slot"] as? Number)?.toInt(),
                arrivalSec = (stop["arrival"] as Number).toDouble(),
                frame = arrivalFrames[s],
                target = target,
                saved = camera(stop["saved"]),
                filmed = got,
                reached = got != null && abs(got.x - target.x) <= FLIGHT_REACHED_PX && abs(got.y - target.y) <= FLIGHT_REACHED_PX &&
                    abs(got.zoom / target.zoom - 1.0) <= FLIGHT_REACHED_ZOOM
            )
        }
        val outW = request.width ?: width
        val outH = request.height ?: height
        val problems = buildList {
            if (encodedFrames != null && encodedFrames != frames) add("the file holds $encodedFrames frames, $frames were filmed")
            reports.filter { !it.reached }.forEach { add("the camera never reached \"${it.name}\" (frame ${it.frame}: ${it.filmed} vs ${it.target})") }
        }
        lprintln("info,video", "Canvas flight: filmed $frames frames (${"%.2f".format(Locale.US, duration)} s at $fps fps) to $outputPath")
        return AgentCanvasFlightResponse(
            ok = problems.isEmpty(),
            documentName = info.documentName,
            sessionKey = info.filename,
            path = outputPath.toString(),
            durationSec = duration,
            fps = fps,
            frameCount = frames,
            encodedFrames = encodedFrames,
            width = outW,
            height = outH,
            threeViews = threeViews,
            stops = reports,
            message = problems.joinToString("; ").ifEmpty { "Filmed $frames frames to $outputPath" }
        )
    } finally {
        if (began) runCatching { page.evaluate("() => window.fieldCanvasFlight && window.fieldCanvasFlight.endRecording()") }
        if (resized) {
            runCatching { cdp.send("Emulation.clearDeviceMetricsOverride") }
            // The shared capture page has Playwright's own viewport and density; put them back.
            playwrightViewport?.let { v -> runCatching { page.setViewportSize(v.width, v.height) } }
        }
        runCatching { cdp.detach() }
    }
}

private fun camera(raw: Any?): CanvasFlightCamera {
    val m = raw as? Map<*, *> ?: error("expected a camera, got $raw")
    return CanvasFlightCamera((m["x"] as Number).toDouble(), (m["y"] as Number).toDouble(), (m["zoom"] as Number).toDouble())
}

/** The whole page as a JPEG, at the page's device pixels (the film's size when it was sized for it). */
private fun captureJpeg(cdp: CDPSession): ByteArray {
    val reply = cdp.send("Page.captureScreenshot", JsonObject().apply {
        addProperty("format", "jpeg")
        addProperty("quality", 92)
        addProperty("captureBeyondViewport", false)
    })
    val data = reply.get("data")?.asString ?: error("Page.captureScreenshot returned no data")
    return Base64.getDecoder().decode(data)
}

private fun imageSize(jpeg: ByteArray): Pair<Int, Int>? = runCatching {
    javax.imageio.ImageIO.read(jpeg.inputStream())?.let { it.width to it.height }
}.getOrNull()

/** Frames in [file] as ffprobe counts them, or null when there is no ffprobe. */
internal fun countEncodedFrames(file: Path): Int? {
    val probe = File(FFMPEG).resolveSibling("ffprobe").takeIf { it.canExecute() }?.path ?: "ffprobe"
    return runCatching {
        val process = ProcessBuilder(probe, "-v", "error", "-select_streams", "v:0", "-count_packets",
            "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", file.toString())
            .redirectErrorStream(true).start()
        // csv output ends the row with a separator: "600,".
        val out = process.inputStream.bufferedReader().readText().trim().substringBefore(',')
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) null else out.toIntOrNull()
    }.getOrNull()
}

/**
 * JPEG frames in, H.264 MP4 out: ffmpeg reading concatenated JPEGs on stdin, one frame each at [fps],
 * so the film is never held in memory. Scaled to [width] by [height] when given (a no-op when the
 * page was sized for it), otherwise padded to even dimensions as yuv420p needs.
 */
internal class FlightEncoder(private val output: Path, fps: Int, width: Int?, height: Int?) : AutoCloseable {
    private val args = buildList {
        addAll(listOf(FFMPEG, "-y", "-loglevel", "error"))
        addAll(listOf("-f", "image2pipe", "-vcodec", "mjpeg", "-framerate", fps.toString(), "-i", "-"))
        // JPEG frames are full range; the film is the limited range every player and editor expects.
        val size = if (width != null && height != null) "scale=$width:$height:flags=lanczos:in_range=pc:out_range=tv"
            else "pad=ceil(iw/2)*2:ceil(ih/2)*2,scale=in_range=pc:out_range=tv"
        addAll(listOf("-vf", "$size,format=yuv420p"))
        addAll(listOf("-c:v", "libx264", "-crf", "18", "-preset", "medium", "-pix_fmt", "yuv420p", "-color_range", "tv",
            "-r", fps.toString(), "-movflags", "+faststart", "-an", output.toString()))
    }
    private val log = File.createTempFile("field-canvas-flight-ffmpeg-", ".log")
    private val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
    private val stdin = process.outputStream.buffered(1 shl 20)
    private var closed = false

    fun write(jpeg: ByteArray) {
        try {
            stdin.write(jpeg)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("ffmpeg stopped reading frames: ${log.readText().take(2000)}", e)
        }
    }

    /** Stops ffmpeg without a film, when filming failed: the failure is the news, not ffmpeg's. */
    fun abandon() {
        if (closed) return
        closed = true
        runCatching { stdin.close() }
        process.destroyForcibly()
        log.delete()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { stdin.close() }
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("ffmpeg did not finish encoding $output")
        }
        val exit = process.exitValue()
        val text = log.readText()
        log.delete()
        check(exit == 0) { "ffmpeg encoding failed (exit $exit): ${args.joinToString(" ")}\n$text" }
    }
}
