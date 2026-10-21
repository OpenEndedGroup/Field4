/**
 * Filming any web page frame by frame: the page filmer behind `film-page` on the backdoor.
 *
 * The canvas flight ([recordCanvasFlight]) drives Field's own canvas. Some things worth filming are
 * other pages: a chat lens exported to a standalone HTML file, a three.js view on its own page. [filmPage]
 * opens the page in a capture context of its own (so the shared Field page is untouched), runs a setup
 * script, then steps a frame script through the film's clock, waiting for each frame to paint before it
 * captures it and pipes it to ffmpeg, exactly as the flight does. Each script's answer is JSON text
 * ("null" for none): [evaluateWithin] waits for a truthy answer, and an `undefined` would run it again.
 */

package afield.tools.docs

import afield.app.diagnostics.logging.lprintln
import afield.app.protocol.AgentPageFilm
import afield.app.protocol.AgentPageFilmResponse
import afield.app.server.ensureSharedPlaywrightBrowser
import afield.app.server.playwrightDispatcher
import com.google.gson.JsonObject
import com.microsoft.playwright.Browser
import com.microsoft.playwright.options.ViewportSize
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.math.roundToInt

private const val PAGE_FRAME_TIMEOUT_MS = 30_000

/** Films [request]'s page to its path. Runs on the Playwright thread. */
suspend fun filmPage(request: AgentPageFilm): AgentPageFilmResponse = withContext(playwrightDispatcher) {
    fun failed(message: String) = AgentPageFilmResponse(ok = false, url = request.url, path = request.path, message = message)
    if (!request.durationSec.isFinite() || request.durationSec <= 0.0 || request.durationSec > 3600.0)
        return@withContext failed("durationSec must be more than 0 and at most 3600, got ${request.durationSec}")
    if (request.fps !in 1..120) return@withContext failed("fps must be 1 to 120, got ${request.fps}")
    if (request.width !in 64..7680 || request.height !in 64..4320) return@withContext failed("a film is 64x64 to 7680x4320 pixels")
    if (!request.pixelRatio.isFinite() || request.pixelRatio !in 0.5..4.0) return@withContext failed("pixelRatio must be 0.5 to 4")
    runCatching { film(ensureSharedPlaywrightBrowser().first, request) }.getOrElse {
        lprintln("warning,video", "Page film of ${request.url} failed: ${it.message}")
        failed(it.message ?: it::class.simpleName ?: "page film failed")
    }
}

private fun film(browser: Browser, request: AgentPageFilm): AgentPageFilmResponse {
    val output = Path.of(request.path).toAbsolutePath()
    output.parent?.let { Files.createDirectories(it) }
    val context = browser.newContext(
        Browser.NewContextOptions()
            .setViewportSize(ViewportSize((request.width / request.pixelRatio).roundToInt(), (request.height / request.pixelRatio).roundToInt()))
            .setDeviceScaleFactor(request.pixelRatio)
            .setIgnoreHTTPSErrors(true)
    )
    try {
        val page = context.newPage()
        page.navigate(request.url, com.microsoft.playwright.Page.NavigateOptions().setTimeout(request.loadTimeoutMs.toDouble()))
        val setupResult = request.setup?.let {
            evaluateWithin(page, "async () => JSON.stringify(await ($it)()) ?? \"null\"", null, request.loadTimeoutMs, "page film: setup")?.toString()
        }
        if (request.settleMs > 0) page.waitForTimeout(request.settleMs.toDouble())
        awaitPaintedFrames(page, "page film: first paint")
        val frames = canvasFlightFrameCount(request.durationSec, request.fps)
        val cdp = context.newCDPSession(page)
        var lastFrameResult: String? = null
        val encoder = FlightEncoder(output, request.fps, request.width, request.height)
        val dragging = request.dragDx != 0.0 || request.dragDy != 0.0
        val viewport = page.viewportSize()
        val x0 = request.dragX ?: (viewport.width / 2.0)
        val y0 = request.dragY ?: (viewport.height / 2.0)
        try {
            if (dragging) {
                page.mouse().move(x0, y0)
                page.mouse().down()
            }
            val preroll = request.prerollFrames.coerceIn(0, 200_000)
            for (step in 0 until preroll + frames) {
                val i = step - preroll
                val filming = i >= 0
                val t = if (filming) canvasFlightFrameTime(i, frames, request.fps, request.durationSec) else i.toDouble() / request.fps
                if (request.frame != null) {
                    lastFrameResult = evaluateWithin(
                        page, "async (a) => JSON.stringify(await (${request.frame})(a.t, a.i, a.n)) ?? \"null\"",
                        mapOf("t" to t, "i" to i, "n" to frames), PAGE_FRAME_TIMEOUT_MS, "page film: frame ${i + 1} of $frames"
                    )?.toString()
                }
                if (dragging) page.mouse().move(x0 + request.dragDx * step, y0 + request.dragDy * step)
                awaitPaintedFrames(page, "page film: painting frame ${i + 1} of $frames")
                if (!filming) continue
                val shot = cdp.send("Page.captureScreenshot", JsonObject().apply {
                    addProperty("format", "jpeg"); addProperty("quality", 92); addProperty("captureBeyondViewport", false)
                })
                encoder.write(Base64.getDecoder().decode(shot.get("data").asString))
                if ((i + 1) % (request.fps * 5) == 0) lprintln("info,video", "Page film: ${i + 1} of $frames frames")
            }
            if (dragging) page.mouse().up()
        } catch (t: Throwable) {
            encoder.abandon()
            throw t
        } finally {
            runCatching { cdp.detach() }
        }
        encoder.close()
        val encoded = countEncodedFrames(output)
        val ok = encoded == null || encoded == frames
        lprintln("info,video", "Page film: filmed $frames frames of ${request.url} to $output")
        return AgentPageFilmResponse(
            ok = ok, url = request.url, path = output.toString(), durationSec = request.durationSec, fps = request.fps,
            frameCount = frames, encodedFrames = encoded, width = request.width, height = request.height,
            setupResult = setupResult, lastFrameResult = lastFrameResult,
            message = if (ok) "Filmed $frames frames to $output" else "the file holds $encoded frames, $frames were filmed"
        )
    } finally {
        runCatching { context.close() }
    }
}
