package afield

import afield.stdlib.Img
import afield.stdlib.canny
import afield.stdlib.connectedComponents
import afield.stdlib.gaussianBlur
import afield.stdlib.gaussianPyramid
import afield.stdlib.goodFeaturesToTrack
import afield.stdlib.harrisCorners
import afield.stdlib.houghCircles
import afield.stdlib.houghLines
import afield.stdlib.laplacianPyramid
import afield.stdlib.luma
import afield.stdlib.opticalFlowLK
import afield.stdlib.rotate
import afield.stdlib.sobel
import afield.stdlib.thresholdOtsu
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Docs-capture harness for the imaging section of `public/docs/visualizers.md`.
 *
 * Like [ValueVisualizerDocCaptureTest], this is not a behavioural test (that is
 * [ImagingVisualizerTest]) — it is the honest capture step. Each fixture is a REAL imaging pipeline;
 * its value goes through the REAL [ValueVisualizer.visualize]; the emitted HTML is wrapped
 * *unchanged* in a minimal dark document and rasterized with Playwright/chromium. Nothing is
 * hand-drawn, and nothing is redrawn to look better than the renderer actually draws it.
 *
 * Every `<details>` is forced open before the shot, so the captures show the disclosure content
 * (the per-channel strip, histogram and stats) that a reader would have to click for.
 *
 * **Output location.** By default this writes to a temporary directory and only asserts the PNGs
 * came out non-empty, so an ordinary `jvmTest` run leaves nothing behind in the repository (unlike
 * `ArraySparklineVisualizerTest`, which litters a `sparkline-proof/` directory into whatever tree it
 * runs in). Set `AFIELD_IMAGING_VIZ_DIR` to regenerate the committed docs assets:
 *
 * ```
 * AFIELD_IMAGING_VIZ_DIR=public/docs/assets/imaging_visualizers \
 *   ./gradlew jvmTest --tests 'afield.ImagingVisualizerDocCaptureTest'
 * ```
 */
class ImagingVisualizerDocCaptureTest {

    private val assetDir: Path by lazy {
        val configured = System.getenv("AFIELD_IMAGING_VIZ_DIR")
            ?: System.getProperty("afield.imagingViz.assetDir")
        val dir = if (configured != null) Paths.get(configured)
        else Files.createTempDirectory("imaging-visualizers-capture")
        Files.createDirectories(dir)
        println("ImagingVisualizerDocCaptureTest: writing to ${dir.toAbsolutePath()}")
        dir
    }

    private val woff2Dir: Path by lazy {
        Paths.get(System.getProperty("user.dir"), "src", "jsMain", "resources", "woff2").toAbsolutePath()
    }

    /** The real shipped Iosevka faces, inlined as data URIs (see ValueVisualizerDocCaptureTest). */
    private fun fontFaceCss(): String {
        val faces = listOf(
            Triple("iosevka-light.woff2", 300, "normal"),
            Triple("iosevka-regular.woff2", 400, "normal"),
            Triple("iosevka-italic.woff2", 400, "italic"),
            Triple("iosevka-bold.woff2", 700, "normal")
        )
        return faces.filter { Files.exists(woff2Dir.resolve(it.first)) }.joinToString("\n") { (file, weight, style) ->
            val b64 = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(woff2Dir.resolve(file)))
            "@font-face{font-family:'Iosevka Web';font-weight:$weight;font-style:$style;" +
                "src:url('data:font/woff2;base64,$b64') format('woff2');}"
        }
    }

    private fun document(fragment: String): String = buildString {
        append("<!doctype html><html><head><meta charset='utf-8'><style>")
        append(fontFaceCss())
        append("html,body{margin:0;padding:0;background:#1e1e1e;}")
        append("#capture{display:inline-block;padding:16px 18px;background:#1e1e1e;}")
        append("</style></head><body><div id='capture'>")
        append(fragment)
        append("</div></body></html>")
    }

    private var pageRef: com.microsoft.playwright.Page? = null

    private fun capture(name: String, value: Any?): Path {
        val fragment = ValueVisualizer.visualize(value)
        assertTrue(fragment.contains("value-viz"), "expected a real visualizer fragment for $name")
        val page = pageRef ?: error("Playwright page not initialised")
        page.setContent(document(fragment))
        // Show the disclosure: a closed <details> would hide the half of the visualizer this page
        // is documenting.
        page.evaluate("document.querySelectorAll('details').forEach(d => d.open = true)")
        page.evaluate("document.fonts.ready")
        page.waitForTimeout(160.0)
        val out = assetDir.resolve("$name.png")
        val el = page.querySelector("#capture") ?: error("capture element missing for $name")
        el.screenshot(com.microsoft.playwright.ElementHandle.ScreenshotOptions().setPath(out))
        assertTrue(Files.exists(out) && Files.size(out) > 0, "expected a non-empty PNG for $name")
        println("captured $name -> $out (${Files.size(out)} bytes)")
        return out
    }

    /**
     * A synthetic scene with everything the detectors need: a bright rectangle (corners), two
     * straight rulings (Hough lines), a disc (a Hough circle), a colour gradient background and a
     * checker patch (texture for the flow tracker). Deterministic, no asset file needed.
     */
    private fun scene(): Img = Img.generateRGB(240, 180) { x, y ->
        val u = x / 239f
        val v = y / 179f
        var r = 0.10f + 0.30f * u
        var g = 0.12f + 0.22f * v
        var b = 0.34f - 0.20f * u
        // Bright rectangle: four strong corners.
        if (x in 30..90 && y in 26..76) { r = 0.94f; g = 0.86f; b = 0.42f }
        // Disc.
        if (hypot(x - 172.0, y - 62.0) <= 30.0) { r = 0.30f; g = 0.80f; b = 0.92f }
        // Two rulings.
        if (x in 150..152) { r = 0.98f; g = 0.98f; b = 0.98f }
        if (y in 140..142) { r = 0.98f; g = 0.98f; b = 0.98f }
        // Checker patch, for optical flow to lock onto.
        if (x in 24..104 && y in 104..164 && ((x / 8 + y / 8) % 2 == 0)) { r = 0.88f; g = 0.30f; b = 0.55f }
        Triple(r, g, b)
    }

    @Test
    fun `capture imaging visualizer gallery assets`() {
        val pw = Playwright.create()
        val browser = pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        try {
            val ctx = browser.newContext(
                com.microsoft.playwright.Browser.NewContextOptions().setDeviceScaleFactor(2.0)
            )
            val page = ctx.newPage()
            pageRef = page

            val rgb = scene()
            val gray = rgb.luma()

            // 1. The headline: a 3-channel image, direct mode, channels split in the disclosure.
            capture("img_rgb", rgb)

            // 2. A signed Sobel dx: the diverging case.
            capture("img_signed_dx", gray.sobel().dx)

            // 3. An unnormalised response: the auto-ranged case.
            capture("img_autoranged", gray.sobel().magnitude().let { m -> m * 25f })

            // 4. NaN / infinity handling.
            capture(
                "img_non_finite",
                Img.generate(96, 72) { x, y ->
                    when {
                        x in 40..47 && y in 30..37 -> Float.NaN
                        x == 70 && y == 20 -> Float.POSITIVE_INFINITY
                        else -> (x + y) / 166f
                    }
                }
            )

            // 5. RGBA over the checkerboard: a radial alpha falloff.
            capture(
                "img_rgba",
                run {
                    val w = 140
                    val h = 140
                    val data = FloatArray(w * h * 4)
                    for (y in 0 until h) for (x in 0 until w) {
                        val i = (y * w + x) * 4
                        data[i] = 0.95f
                        data[i + 1] = 0.55f
                        data[i + 2] = 0.20f
                        val d = hypot(x - 70.0, y - 70.0) / 66.0
                        data[i + 3] = (1.0 - d).coerceIn(0.0, 1.0).toFloat()
                    }
                    Img(w, h, 4, data)
                }
            )

            // 6. Gradient: four panels.
            capture("gradient", gray.sobel())

            // 7. Pyramids.
            capture("gaussian_pyramid", rgb.gaussianPyramid(5))
            capture("laplacian_pyramid", gray.laplacianPyramid(4))

            // 8. Corners.
            capture("corners", gray.harrisCorners(minDistance = 8, maxCount = 40))

            // 9. Hough lines and circles, from real detections on the real scene.
            val edges = gray.canny(0.10, 0.25)
            capture("hough_lines", houghLines(edges, maxLines = 8, threshold = 40))
            capture("hough_circles", houghCircles(edges, minRadius = 20, maxRadius = 40, maxCircles = 4))

            // 10. Optical flow between the scene and a rotated copy of it.
            val moved = rgb.rotate(0.06).luma()
            val seeds = gray.goodFeaturesToTrack(maxCount = 90, minDistance = 9)
            capture("flow_field", opticalFlowLK(gray, moved, seeds.map { it.toVec2() }, levels = 3))

            // 11. Connected components on an Otsu mask.
            capture("components", gray.gaussianBlur(1.2).thresholdOtsu().connectedComponents())

            // 12. A filmstrip of a blur sweep.
            capture("filmstrip", (0 until 5).map { i -> rgb.gaussianBlur(0.001 + i * 1.6) })

            ctx.close()
        } finally {
            runCatching { browser.close() }
            runCatching { pw.close() }
        }
    }
}
