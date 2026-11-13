/**
 * Filming any web page frame by frame: the page filmer behind `film-page` on the backdoor.
 *
 * The canvas flight ([recordCanvasFlight]) drives Field's own canvas. Some things worth filming are
 * other pages: a chat lens exported to a standalone HTML file, a three.js view on its own page. [filmPage]
 * opens the page in a capture context of its own (so the shared Field page is untouched), runs a setup
 * script, then steps a frame script through the film's clock, waiting for each frame to paint before it
 * captures it and pipes it to ffmpeg, exactly as the flight does. Each script's answer is JSON text
 * ("null" for none): [evaluateWithin] waits for a truthy answer, and an `undefined` would run it again.
 *
 * A timeline ([FilmTimeline]) films a Field document: `filmStage.js` lays its stage out once, then before each
 * frame the server makes the frame's drags on the boxes and the page puts each view where the frame's keys
 * ask, resolving only once the views have drawn it. With a frames folder each frame is written as a file and
 * the film encoded at the end, so a shoot that stopped resumes where it got to.
 */

package afield.tools.docs

import afield.app.diagnostics.logging.lprintln
import afield.app.protocol.AgentPageFilm
import afield.app.protocol.AgentPageFilmResponse
import afield.app.protocol.AgentProgress
import afield.app.server.SessionInformation
import afield.app.server.buildPlaywrightDocumentUrl
import afield.app.server.ensureSharedPlaywrightBrowser
import afield.app.server.playwrightDispatcher
import com.google.gson.JsonObject
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ViewportSize
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject as JsonMap

private const val PAGE_FRAME_TIMEOUT_MS = 30_000

/**
 * Films [request]'s page to its path. Runs on the Playwright thread. [session] is the requesting document: what
 * the url `@session` opens, and where a timeline's box names are found. [progress] hears how far the film has got.
 */
suspend fun filmPage(
    request: AgentPageFilm,
    session: SessionInformation? = null,
    progress: (AgentProgress) -> Unit = {},
): AgentPageFilmResponse = withContext(playwrightDispatcher) {
    fun failed(message: String) = AgentPageFilmResponse(ok = false, url = request.url, path = request.path, message = message)
    val timeline = try {
        request.timeline?.let { FilmTimeline.parse(resolveTimelineBoxes(it, session?.boxes?.values?.map { b -> b.id to b.name }, request.boxAliases)) }
    } catch (e: IllegalArgumentException) { return@withContext failed(e.message ?: "the timeline is not valid") }
    val effective = timeline?.let { request.withTimelineDefaults(it) } ?: request
    if (!effective.durationSec.isFinite() || effective.durationSec <= 0.0 || effective.durationSec > 3600.0)
        return@withContext failed("durationSec must be more than 0 and at most 3600, got ${effective.durationSec}")
    if (effective.fps !in 1..120) return@withContext failed("fps must be 1 to 120, got ${effective.fps}")
    if (effective.width !in 64..7680 || effective.height !in 64..4320) return@withContext failed("a film is 64x64 to 7680x4320 pixels")
    if (!effective.pixelRatio.isFinite() || effective.pixelRatio !in 0.5..4.0) return@withContext failed("pixelRatio must be 0.5 to 4")
    val url = try { resolveFilmUrl(effective.url, session) } catch (e: IllegalArgumentException) { return@withContext failed(e.message!!) }
    runCatching { film(ensureSharedPlaywrightBrowser().first, effective.copy(url = url), timeline, progress) }.getOrElse {
        lprintln("warning,video", "Page film of $url failed: ${it.message}")
        failed(it.message ?: it::class.simpleName ?: "page film failed")
    }
}

/** A timeline's own duration, rate and size fill what the request leaves unset (0). */
fun AgentPageFilm.withTimelineDefaults(timeline: FilmTimeline): AgentPageFilm = copy(
    durationSec = if (durationSec > 0.0) durationSec else timeline.duration ?: durationSec,
    fps = if (fps > 0) fps else timeline.fps ?: 30,
    width = if (width > 0) width else timeline.width ?: 1920,
    height = if (height > 0) height else timeline.height ?: 1080,
    pixelRatio = if (pixelRatio > 0.0) pixelRatio else timeline.pixelRatio ?: 1.0,
)

/**
 * The film's url: `@session` (with an optional `?query` appended) is [session]'s own document as another client
 * of it, so a scratch document, whose url carries a session key made up for this run, films as a named one does.
 */
internal fun resolveFilmUrl(url: String, session: SessionInformation?): String {
    if (!url.startsWith("@session")) return url
    val info = session ?: throw IllegalArgumentException("film-page @session needs a document: this request came from no session")
    return sessionFilmUrl(buildPlaywrightDocumentUrl(info), url.removePrefix("@session"))
}

/** [documentUrl] (a capture page's url for a document) as a film client's, with [extra] (`?a=b`) appended. */
internal fun sessionFilmUrl(documentUrl: String, extra: String): String {
    val base = documentUrl.replace("user=playwright", "user=film")
    val query = extra.removePrefix("?").removePrefix("&")
    return if (query.isBlank()) base else "$base&$query"
}

/**
 * [text] with every `"box"` that names one of [boxes] (uid to name) by an alias of [aliases] or by its name replaced
 * by its uid; a uid is kept. Null [boxes] (no document) leaves the text as it is.
 */
internal fun resolveTimelineBoxes(text: String, boxes: List<Pair<String, String>>?, aliases: Map<String, String> = emptyMap()): String {
    if (boxes == null) return text
    val root = try { Json.parseToJsonElement(text) } catch (_: Exception) { return text }
    fun resolve(name: String): String {
        if (boxes.any { it.first == name }) return name
        aliases[name.removePrefix("@")]?.let { uid -> if (boxes.any { it.first == uid }) return uid }
        val named = boxes.filter { it.second == name }
        return when (named.size) {
            1 -> named.single().first
            0 -> throw FilmTimelineException("the timeline names a box \"$name\", and the document has no box of that name or uid " +
                "(its boxes: ${boxes.map { it.second }.filter { it != "root" }.sorted().joinToString().ifEmpty { "none" }})")
            else -> throw FilmTimelineException("the timeline names a box \"$name\", and ${named.size} boxes have that name: give its uid")
        }
    }
    fun walk(e: JsonElement): JsonElement = when (e) {
        is JsonMap -> JsonMap(e.mapValues { (k, v) -> if (k == "box" && v is JsonPrimitive && v.isString) JsonPrimitive(resolve(v.content)) else walk(v) })
        is JsonArray -> JsonArray(e.map(::walk))
        else -> e
    }
    return walk(root).toString()
}

internal fun filmFrameFile(dir: Path, i: Int): Path = dir.resolve("frame_%06d.jpg".format(i))

/** The first frame a resumed film makes: the first missing from [dir], so a frame half-written last time is made again. */
internal fun firstMissingFrame(dir: Path, frames: Int): Int = (0 until frames).firstOrNull { !filmFrameFile(dir, it).exists() } ?: frames

private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** How fast frames are coming, over the last few, and so how long the rest will take. Only progress reads this clock. */
internal class FilmRate(private val window: Int = 30, private val now: () -> Long = System::nanoTime) {
    private val stamps = ArrayDeque<Long>()
    private val started = now()
    fun tick() { stamps.addLast(now()); while (stamps.size > window + 1) stamps.removeFirst() }
    val perSecond: Double? get() = if (stamps.size < 2) null else (stamps.size - 1) / ((stamps.last() - stamps.first()) / 1e9)
    val elapsed: Double get() = (now() - started) / 1e9
}

/**
 * The frames folder's description of its film. A resume refuses a folder another film wrote: frames of a different
 * timeline, size or rate would encode into one film that is neither.
 */
internal fun filmManifest(request: AgentPageFilm, frames: Int): JsonMap = buildJsonObject {
    put("fps", request.fps); put("width", request.width); put("height", request.height)
    put("pixelRatio", request.pixelRatio); put("frames", frames)
    put("timeline", request.timeline?.let(::sha256)); put("frameScript", request.frame?.let(::sha256))
}

internal fun manifestDifferences(was: JsonMap, now: JsonMap): List<String> =
    listOf("fps", "width", "height", "pixelRatio", "frames", "timeline", "frameScript").filter { was[it] != now[it] }

private fun film(browser: Browser, request: AgentPageFilm, timeline: FilmTimeline?, progress: (AgentProgress) -> Unit): AgentPageFilmResponse {
    val output = Path.of(request.path).toAbsolutePath()
    output.parent?.let { Files.createDirectories(it) }
    val frames = canvasFlightFrameCount(request.durationSec, request.fps)
    val resuming = request.resume || request.fromFrame != null
    val framesDir = request.framesDir?.let { Path.of(it).toAbsolutePath() }
        ?: if (resuming) error("resuming a film needs its frames folder (framesDir)") else null
    var first = 0
    if (framesDir != null) {
        Files.createDirectories(framesDir)
        val manifest = filmManifest(request, frames)
        val manifestFile = framesDir.resolve("film.json")
        if (resuming && manifestFile.exists()) {
            val differs = manifestDifferences(Json.parseToJsonElement(Files.readString(manifestFile)).jsonObject, manifest)
            if (differs.isNotEmpty()) error("the frames in $framesDir are of another film (its ${differs.joinToString()} differ): film to an empty folder")
        }
        if (!resuming) Files.list(framesDir).use { files -> files.filter { it.fileName.toString().startsWith("frame_") }.forEach(Files::delete) }
        first = if (!resuming) 0 else (request.fromFrame ?: firstMissingFrame(framesDir, frames)).coerceIn(0, frames)
        Files.writeString(manifestFile, manifest.toString())
    }
    val context = browser.newContext(
        Browser.NewContextOptions()
            .setViewportSize(ViewportSize((request.width / request.pixelRatio).roundToInt(), (request.height / request.pixelRatio).roundToInt()))
            .setDeviceScaleFactor(request.pixelRatio)
            .setIgnoreHTTPSErrors(true)
    )
    val rate = FilmRate()
    fun report(phase: String, done: Int, total: Int, detail: String = "") {
        val perSecond = rate.perSecond
        runCatching { progress(AgentProgress("film-page", phase, done, total, perSecond, perSecond?.let { (total - done) / it }, rate.elapsed, detail)) }
    }
    try {
        val page = context.newPage()
        page.navigate(request.url, Page.NavigateOptions().setTimeout(request.loadTimeoutMs.toDouble()))
        report("loading", 0, frames, "page loaded")
        val setupResult = request.setup?.let {
            json(evaluateWithin(page, "async () => JSON.stringify(await ($it)()) ?? \"null\"", null, request.loadTimeoutMs, "page film: setup"))
        }
        var stage: JsonElement? = null
        var poses = emptyMap<String, FilmPose>()
        if (timeline != null) {
            val spec = buildJsonObject {
                timeline.stage?.let { put("stage", it.json) }
                put("views", JsonArray(timeline.views.map { JsonPrimitive(it) }))
                put("timeoutMs", request.loadTimeoutMs)
            }
            val installed = json(evaluateWithin(page,
                "async (spec) => { const m = await import('/filmStage.js'); return JSON.stringify(await m.install(JSON.parse(spec))); }",
                spec.toString(), request.loadTimeoutMs + 5_000, "page film: the stage")) as? JsonMap
            stage = installed?.get("regions")
            poses = (installed?.get("poses") as? JsonMap)?.mapNotNull { (view, p) -> FilmPose.of(p)?.let { view to it } }?.toMap().orEmpty()
            report("loading", 0, frames, "stage laid out")
        }
        if (request.settleMs > 0) page.waitForTimeout(request.settleMs.toDouble())
        awaitPaintedFrames(page, "page film: first paint")
        fun time(i: Int) = canvasFlightFrameTime(i, frames, request.fps, request.durationSec)
        fun drags(previousT: Double?, t: Double) {
            for (a in timeline?.serverActions(previousT, t).orEmpty()) {
                if (!afield.ui.visualization.dragLines(a.box, a.channel, a.phase, a.x, a.y))
                    error("the timeline drags the \"${a.channel}\" drawing of box ${a.box}, which takes no drags (its lines(...) has no onDrag)")
            }
        }
        // Resuming: the boxes are put where the frames before [first] left them, by making their drags again.
        if (timeline != null && first > 0) {
            for (i in 0 until first) {
                drags(if (i == 0) null else time(i - 1), time(i))
                if ((i + 1) % request.fps == 0 || i == first - 1) report("replaying", i + 1, first)
            }
        }
        val cdp = context.newCDPSession(page)
        var lastFrameResult: JsonElement? = null
        val encoder = if (framesDir == null) FlightEncoder(output, request.fps, request.width, request.height) else null
        val dragging = request.dragDx != 0.0 || request.dragDy != 0.0
        val viewport = page.viewportSize()
        val x0 = request.dragX ?: (viewport.width / 2.0)
        val y0 = request.dragY ?: (viewport.height / 2.0)
        try {
            if (dragging) {
                page.mouse().move(x0, y0)
                page.mouse().down()
            }
            val preroll = if (first > 0) 0 else request.prerollFrames.coerceIn(0, 200_000)
            for (step in -preroll until frames - first) {
                val i = step + first
                val filming = step >= 0
                val t = if (filming) time(i) else step.toDouble() / request.fps
                if (timeline != null && filming) {
                    val previousT = if (i == 0) null else time(i - 1)
                    drags(previousT, t)
                    val state = timeline.frame(i, t, previousT, poses)
                    lastFrameResult = try {
                        json(evaluateWithin(page, "(s) => window.FieldFilm.frame(s).then((r) => JSON.stringify(r))", state.toString(),
                            request.frameTimeoutMs, "page film: frame ${i + 1} of $frames"))
                    } catch (e: IllegalStateException) {
                        val waiting = runCatching { page.evaluate("() => (window.FieldFilm?.status().waiting ?? []).join('; ')") }.getOrNull()
                        throw IllegalStateException("${e.message}; it was waiting for ${(waiting as? String)?.ifBlank { null } ?: "nothing it could name"}", e)
                    }
                }
                if (request.frame != null) {
                    lastFrameResult = json(evaluateWithin(
                        page, "async (a) => JSON.stringify(await (${request.frame})(a.t, a.i, a.n)) ?? \"null\"",
                        mapOf("t" to t, "i" to i, "n" to frames), PAGE_FRAME_TIMEOUT_MS, "page film: frame ${i + 1} of $frames"
                    ))
                }
                if (dragging) page.mouse().move(x0 + request.dragDx * (step + preroll), y0 + request.dragDy * (step + preroll))
                awaitPaintedFrames(page, "page film: painting frame ${i + 1} of $frames")
                if (!filming) continue
                val shot = cdp.send("Page.captureScreenshot", JsonObject().apply {
                    addProperty("format", "jpeg"); addProperty("quality", 92); addProperty("captureBeyondViewport", false)
                })
                val jpeg = Base64.getDecoder().decode(shot.get("data").asString)
                if (encoder != null) encoder.write(jpeg)
                else {
                    // Written whole or not at all, so a frame cut off by a stop is made again on resume.
                    val file = filmFrameFile(framesDir!!, i)
                    val partial = file.resolveSibling(file.fileName.toString() + ".part")
                    Files.write(partial, jpeg)
                    Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
                rate.tick()
                report("filming", i + 1, frames)
                if ((i + 1) % (request.fps * 5) == 0) lprintln("info,video", "Page film: ${i + 1} of $frames frames")
            }
            if (dragging) page.mouse().up()
        } catch (t: Throwable) {
            encoder?.abandon()
            throw t
        } finally {
            runCatching { cdp.detach() }
        }
        if (encoder != null) encoder.close()
        else {
            val missing = (0 until frames).firstOrNull { !filmFrameFile(framesDir!!, it).exists() }
            if (missing != null) error("frame $missing is missing from $framesDir; resume the film to make it")
            report("encoding", frames, frames)
            FlightEncoder(output, request.fps, request.width, request.height).use { enc ->
                for (i in 0 until frames) enc.write(filmFrameFile(framesDir!!, i).readBytes())
            }
        }
        val encoded = countEncodedFrames(output)
        val ok = encoded == null || encoded == frames
        if (ok && framesDir != null && !request.keepFrames) framesDir.toFile().deleteRecursively()
        lprintln("info,video", "Page film: filmed ${frames - first} frames of ${request.url} to $output")
        return AgentPageFilmResponse(
            ok = ok, url = request.url, path = output.toString(), durationSec = request.durationSec, fps = request.fps,
            frameCount = frames, encodedFrames = encoded, width = request.width, height = request.height,
            setupResult = setupResult, lastFrameResult = lastFrameResult, stage = stage, firstFrame = first,
            framesDir = framesDir?.takeIf { it.exists() }?.toString(), elapsedSec = rate.elapsed,
            message = if (ok) "Filmed $frames frames to $output" + (if (first > 0) " (frames ${first + 1} on in this run)" else "")
                else "the file holds $encoded frames, $frames were filmed"
        )
    } finally {
        runCatching { context.close() }
    }
}

/** A script's answer, JSON text, as JSON; text that is not JSON stays a string. */
private fun json(answer: Any?): JsonElement {
    val text = answer as? String ?: return JsonNull
    return runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
}
