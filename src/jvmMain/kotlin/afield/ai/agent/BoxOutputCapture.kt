package afield.ai.agent

import afield.BoxScreenshotRequest
import afield.ExpectedOutputs
import afield.SessionInformation
import afield.buildPlaywrightDocumentUrl
import afield.ensureSharedPlaywrightBrowser
import afield.logger.lprintln
import afield.playwrightDispatcher
import afield.playwrightSessionReadyScript
import afield.requestBoxScreenshot
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.Clip
import com.microsoft.playwright.options.WaitUntilState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * One captured picture of a box's output area, or the reason there is none.
 *
 * [image] is the decoded capture (device pixels). [path] is where the PNG was kept as evidence, or
 * blank when the host keeps none (tests).
 */
data class BoxOutputShot(
    val success: Boolean,
    val message: String,
    val image: BufferedImage? = null,
    val path: String = "",
    val channels: List<String> = emptyList(),
) {
    companion object {
        fun failed(message: String, channels: List<String> = emptyList()) = BoxOutputShot(false, message, channels = channels)
    }
}

/**
 * Measurements of a picture that a check, a log line, or a model can use without looking.
 *
 * Colours are binned to 4 bits a channel. The *background* is the most common bin; [inkFraction] is
 * the share of pixels that differ from it by more than [INK_TOLERANCE] in some channel.
 */
data class PictureStats(
    val width: Int,
    val height: Int,
    val inkFraction: Double,
    /** Up to six `#rrggbb` → share, most common first (bin averages). */
    val dominant: List<Pair<String, Double>>,
) {
    companion object {
        const val INK_TOLERANCE = 24

        fun of(image: BufferedImage): PictureStats {
            val w = image.width
            val h = image.height
            val bins = HashMap<Int, LongArray>() // key -> [count, r, g, b]
            val step = if (w.toLong() * h > 4_000_000L) 2 else 1
            var total = 0L
            for (y in 0 until h step step) for (x in 0 until w step step) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xff; val g = (rgb shr 8) and 0xff; val b = rgb and 0xff
                val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                val acc = bins.getOrPut(key) { LongArray(4) }
                acc[0]++; acc[1] += r.toLong(); acc[2] += g.toLong(); acc[3] += b.toLong()
                total++
            }
            if (total == 0L) return PictureStats(w, h, 0.0, emptyList())
            val sorted = bins.values.sortedByDescending { it[0] }
            val bg = sorted.first()
            val bgRgb = intArrayOf((bg[1] / bg[0]).toInt(), (bg[2] / bg[0]).toInt(), (bg[3] / bg[0]).toInt())
            var ink = 0L
            for ((_, acc) in bins) {
                val c = intArrayOf((acc[1] / acc[0]).toInt(), (acc[2] / acc[0]).toInt(), (acc[3] / acc[0]).toInt())
                if ((0..2).any { kotlin.math.abs(c[it] - bgRgb[it]) > INK_TOLERANCE }) ink += acc[0]
            }
            val dominant = sorted.take(6).map { acc ->
                "#%02x%02x%02x".format((acc[1] / acc[0]).toInt(), (acc[2] / acc[0]).toInt(), (acc[3] / acc[0]).toInt()) to
                    acc[0].toDouble() / total
            }
            return PictureStats(w, h, ink.toDouble() / total, dominant)
        }

        /** Share of [image]'s pixels within [tolerance] (per channel) of [hex] (`#rrggbb`). */
        fun coverage(image: BufferedImage, hex: String, tolerance: Int = 48): Double {
            val target = parseHex(hex)
            var hit = 0L
            var total = 0L
            val step = if (image.width.toLong() * image.height > 4_000_000L) 2 else 1
            for (y in 0 until image.height step step) for (x in 0 until image.width step step) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xff; val g = (rgb shr 8) and 0xff; val b = rgb and 0xff
                if (kotlin.math.abs(r - target[0]) <= tolerance && kotlin.math.abs(g - target[1]) <= tolerance &&
                    kotlin.math.abs(b - target[2]) <= tolerance) hit++
                total++
            }
            return if (total == 0L) 0.0 else hit.toDouble() / total
        }

        /** One bar found by [bars]: its columns, its height in pixels, and which of the colours fills most of it. */
        data class Bar(val x0: Int, val x1: Int, val height: Int, val colour: Int)

        /**
         * Vertical bars drawn in any of [hexes]: runs of adjacent columns holding at least 3 pixels of
         * those colours. A bar's height is its tallest column; its colour is the index into [hexes]
         * with the most pixels in it. Left to right.
         */
        fun bars(image: BufferedImage, hexes: List<String>, tolerance: Int = 48): List<Bar> {
            val targets = hexes.map(::parseHex)
            val out = mutableListOf<Bar>()
            var start = -1; var height = 0; val counts = IntArray(targets.size)
            fun close(x: Int) {
                if (start >= 0) out += Bar(start, x - 1, height, counts.indices.maxByOrNull { counts[it] } ?: 0)
                start = -1; height = 0; counts.fill(0)
            }
            for (x in 0 until image.width) {
                var column = 0
                val here = IntArray(targets.size)
                for (y in 0 until image.height) {
                    val rgb = image.getRGB(x, y)
                    val r = (rgb shr 16) and 0xff; val g = (rgb shr 8) and 0xff; val b = rgb and 0xff
                    val hit = targets.indexOfFirst { t ->
                        kotlin.math.abs(r - t[0]) <= tolerance && kotlin.math.abs(g - t[1]) <= tolerance && kotlin.math.abs(b - t[2]) <= tolerance
                    }
                    if (hit >= 0) { column++; here[hit]++ }
                }
                if (column >= 3) {
                    if (start < 0) start = x
                    height = maxOf(height, column)
                    for (i in here.indices) counts[i] += here[i]
                } else close(x)
            }
            close(image.width)
            return out
        }

        fun parseHex(hex: String): IntArray {
            val h = hex.trim().removePrefix("#")
            require(h.length == 6 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "colour must be #rrggbb, not '$hex'" }
            return intArrayOf(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
        }
    }

    /** `#ffffff 62%, #1f77b4 12%, …` */
    fun describeDominant(): String = dominant.joinToString(", ") { (hex, share) -> "$hex ${"%.1f".format(share * 100)}%" }
}

/** A JPEG data URL of [image], its long side scaled down to at most [maxSide] pixels. */
internal fun jpegDataUrl(image: BufferedImage, maxSide: Int = 1200, quality: Float = 0.85f): String {
    val scale = minOf(1.0, maxSide.toDouble() / maxOf(image.width, image.height))
    val w = maxOf(1, (image.width * scale).toInt())
    val h = maxOf(1, (image.height * scale).toInt())
    val rgb = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = rgb.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.drawImage(image, 0, 0, w, h, null)
    g.dispose()
    val out = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    try {
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            writer.write(null, IIOImage(rgb, null, null), params)
        }
    } finally {
        writer.dispose()
    }
    return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
}

/**
 * The server-owned browser page AI4 looks through: a picture of one box's output area, taken headless,
 * whether or not anyone has Field open.
 *
 * # Why a page of its own
 *
 * The backdoor and the docs runner share one global page ([afield.globalPlaywriteContext]) and navigate
 * it wherever their current session is. AI4 capturing through it would drag it to another document in
 * the middle of someone else's capture sequence. So this opens its own context in the same Chromium
 * ([ensureSharedPlaywrightBrowser]) and navigates only that.
 *
 * # Lifecycle
 *
 * One page per document, keyed by `SessionInformation.filename` (a second capture of the same document
 * reuses it; a keyed map self-replaces). At most [MAX_PAGES]; the least recently used is closed to make
 * room. A page is released:
 *  - by [release], which [afield.closeHeadlessDocument] calls *before* it decides whether the document
 *    still has clients — otherwise this page's own websocket would keep every headless task document
 *    alive for ever;
 *  - after [IDLE_RELEASE_MS] without a capture, **unless** it is the only client of an ephemeral
 *    document: detaching the last client of a scratch document retires it (cleanupDetachedSession), and
 *    a headless task can easily spend two minutes between looks. Those are released by their owner.
 *
 * A late panel is not a problem: a page that attaches after a box ran asks for `replay-output-areas`
 * and gets every retained panel (OutputAreaReplay), so "run first, look later" works.
 */
object BoxOutputCapture {
    const val MAX_PAGES = 4
    const val IDLE_RELEASE_MS = 120_000L
    private const val NAVIGATION_TIMEOUT_MS = 20_000.0
    private const val READY_TIMEOUT_MS = 60_000.0
    private const val VIEWPORT_W = 1600
    private const val VIEWPORT_H = 1400
    private const val MAX_KEPT_CAPTURES = 300

    private class Held(val info: SessionInformation, val context: BrowserContext, val page: Page, val browser: Browser) {
        @Volatile var lastUsed = System.currentTimeMillis()
    }

    // Touched only on the Playwright thread.
    private val held = LinkedHashMap<String, Held>()

    private val sweeper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "afield-box-output-capture-sweeper").apply { isDaemon = true }
    }.also { exec ->
        exec.scheduleWithFixedDelay({ runCatching { runBlocking(playwrightDispatcher) { sweepIdle() } } }, 30, 30, TimeUnit.SECONDS)
    }

    /** Where captures are kept as evidence: `AFIELD_AI4_CAPTURE_DIR`, else `~/.afield/logs/ai4_box_captures`. */
    val captureDir: File
        get() = System.getenv("AFIELD_AI4_CAPTURE_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".afield/logs/ai4_box_captures")

    /** How many capture pages are open — for tests of the release rules and for `status`. */
    fun openPages(): Int = runBlocking(playwrightDispatcher) { held.values.count { !it.page.isClosed } }

    fun isHeld(info: SessionInformation): Boolean = runBlocking(playwrightDispatcher) {
        held[info.filename]?.page?.isClosed == false
    }

    /**
     * Capture [uid]'s output area in [info]. Blocking; call it off the Runloop and off the Playwright
     * thread (it marshals onto the latter, and the page needs the former to answer its websocket).
     */
    fun capture(info: SessionInformation, uid: String, settleMs: Int = 300): BoxOutputShot {
        check(!afield.Runloop.isMainThread) { "BoxOutputCapture.capture blocks; call it off the Runloop" }
        val box = info.boxes[uid] ?: return BoxOutputShot.failed("No box '$uid' in ${info.documentName}.")
        val channels = box.output.retainedAreas.channels()
        if (channels.isEmpty()) return BoxOutputShot.failed(
            "'${box.name}' has no output panel: it has not displayed anything since it was last cleared. " +
                "println text is not a panel (read it with ReadBoxOutputRequest); display(\"main\", html) makes one."
        )
        if (settleMs > 0) Thread.sleep(settleMs.toLong().coerceAtMost(5_000))
        return runBlocking(playwrightDispatcher) {
            val first = runCatching { captureOnce(info, uid, channels) }
            first.getOrElse { failure ->
                // A browser reset elsewhere (the global page's recovery closes the shared browser) kills
                // this context too. Drop it and try once more on a fresh one.
                lprintln("warning,ai4", "box output capture failed for $uid (${failure.message}); retrying on a fresh page")
                dropHeld(info.filename)
                runCatching { captureOnce(info, uid, channels) }.getOrElse {
                    BoxOutputShot.failed("Could not capture '${box.name}': ${it.message ?: it.javaClass.simpleName}", channels)
                }
            }
        }
    }

    /** Close [info]'s capture page, if one is open. Safe from any thread. */
    fun release(info: SessionInformation) {
        if (afield.Runloop.isMainThread) {
            // Never block the Runloop on the Playwright thread; closing can wait a moment.
            afield.playwrightScope.launch { dropHeld(info.filename) }
            return
        }
        runBlocking(playwrightDispatcher) { dropHeld(info.filename) }
    }

    private fun dropHeld(key: String) {
        val h = held.remove(key) ?: return
        runCatching { h.context.close() }
    }

    private fun sweepIdle() {
        val now = System.currentTimeMillis()
        held.entries.toList().forEach { (key, h) ->
            val soleClientOfScratch = h.info.ephemeral && h.info.sessions.size <= 1
            if (h.page.isClosed || !h.browser.isConnected) dropHeld(key)
            else if (now - h.lastUsed > IDLE_RELEASE_MS && !soleClientOfScratch) dropHeld(key)
        }
    }

    private fun pageFor(info: SessionInformation): Page {
        val key = info.filename
        held[key]?.let { h ->
            if (!h.page.isClosed && h.browser.isConnected) {
                h.lastUsed = System.currentTimeMillis()
                return h.page
            }
            dropHeld(key)
        }
        while (held.size >= MAX_PAGES) {
            // Prefer evicting a page whose document will not be retired by losing it.
            val victim = held.entries.minByOrNull { (_, h) ->
                (if (h.info.ephemeral && h.info.sessions.size <= 1) Long.MAX_VALUE / 2 else 0L) + h.lastUsed
            }?.key ?: break
            dropHeld(victim)
        }
        val (browser, _) = ensureSharedPlaywrightBrowser()
        val context = browser.newContext(
            Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_W, VIEWPORT_H)
                .setScreenSize(VIEWPORT_W, VIEWPORT_H)
                .setDeviceScaleFactor(1.0)
                .setIgnoreHTTPSErrors(true)
        )
        val page = context.newPage()
        held[key] = Held(info, context, page, browser)
        val url = buildPlaywrightDocumentUrl(info).replace("user=playwright", "user=ai4-capture")
        page.navigate(url, Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS).setWaitUntil(WaitUntilState.COMMIT))
        page.waitForFunction(playwrightSessionReadyScript(info), null,
            Page.WaitForFunctionOptions().setTimeout(READY_TIMEOUT_MS))
        return page
    }

    private fun captureOnce(info: SessionInformation, uid: String, channels: List<String>): BoxOutputShot {
        val page = pageFor(info)
        val prep = requestBoxScreenshot(page, BoxScreenshotRequest(
            uid = uid, padding = 12.0, panIntoView = true, hideMonaco = true, openOutputs = true,
            preserveSelection = true, includeNotifications = false, expectOutputs = ExpectedOutputs.PANEL
        ))
        val clip = prep.clip
        if (!prep.success || clip == null) {
            return BoxOutputShot.failed(prep.message.ifBlank { "the page could not frame the box's output" }, channels)
        }
        val bytes = page.screenshot(Page.ScreenshotOptions().setClip(Clip(clip.x, clip.y, clip.w, clip.h)).setTimeout(15_000.0))
        held[info.filename]?.lastUsed = System.currentTimeMillis()
        val image = ImageIO.read(bytes.inputStream())
            ?: return BoxOutputShot.failed("the screenshot did not decode", channels)
        val path = runCatching { keep(bytes, info, uid) }.getOrElse {
            lprintln("warning,ai4", "could not keep box capture: ${it.message}"); ""
        }
        return BoxOutputShot(true, "captured", image, path, channels)
    }

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss-SSS").withZone(ZoneOffset.UTC)

    private fun keep(png: ByteArray, info: SessionInformation, uid: String): String {
        val dir = captureDir.apply { mkdirs() }
        val safeUid = uid.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
        val file = File(dir, "${stamp.format(Instant.now())}-$safeUid.png")
        file.writeBytes(png)
        dir.listFiles { f -> f.name.endsWith(".png") }?.sortedByDescending { it.name }?.drop(MAX_KEPT_CAPTURES)?.forEach { it.delete() }
        return file.absolutePath
    }
}
