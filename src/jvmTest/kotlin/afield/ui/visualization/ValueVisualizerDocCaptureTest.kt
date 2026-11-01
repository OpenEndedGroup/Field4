package afield.ui.visualization

import afield.app.server.app
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.map
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.tools.docs.format
import afield.ui.output.column
import afield.ui.output.md
import afield.ui.output.stats
import afield.ui.output.tree

import afield.data.table.get
import afield.document.graph.layers
import afield.tools.docs.docsAssetDir
import afield.tools.docs.screenshot
import afield.ui.notation.with
import afield.ui.output.templates.filter
import afield.math.linalg.Matrix2d
import afield.math.linalg.Matrix3d
import afield.math.linalg.Matrix4d
import afield.math.linalg.Matrix4f
import afield.math.linalg.vec
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Docs-capture harness for the matrix-algebra section of `linear_algebra.md`.
 *
 * This is NOT a behavioural test of [ValueVisualizer] (that lives in
 * [FnPlotVisualizerTest]); it is the honest capture step for the docs page. For each
 * worked-example value it calls the REAL [ValueVisualizer.visualize], wraps the emitted
 * HTML *unchanged* in a minimal dark document (the editor background the renderer targets),
 * and rasterizes the rendered element to a PNG with Playwright/chromium. Nothing is
 * hand-drawn or synthesized — the bytes come straight from the visualizer.
 *
 * By default the PNGs go to a temp directory, so an ordinary `jvmTest` run asserts the whole render
 * path still works without touching the tree. The committed figures under
 * `public/docs/assets/matrix_algebra/` are refreshed only on request, see [docsAssetDir]:
 *
 * ```
 * AFIELD_DOCS_ASSETS=1 ./gradlew jvmTest --tests 'afield.ui.visualization.ValueVisualizerDocCaptureTest'
 * ```
 */
class ValueVisualizerDocCaptureTest {

    /** Assets for the matrix-algebra section of `linear_algebra.md` live in their own folder. */
    private val matrixAssetDir: Path by lazy { docsAssetDir("public/docs/assets/matrix_algebra") }

    /** Absolute path to the shipped webfont directory, resolved from the module root. */
    private val woff2Dir: Path by lazy {
        Paths.get(System.getProperty("user.dir"), "src", "jsMain", "resources", "woff2").toAbsolutePath()
    }

    /**
     * Register the REAL shipped `Iosevka Web` faces so captures use the same font the app does —
     * including its `->` / `=>` ligatures — instead of a Menlo stand-in. The bytes are inlined as
     * base64 `data:` URIs: `setContent` runs in an `about:blank` context where `file://` font
     * URLs are blocked (Chromium silently falls back to system mono), so embedding is what makes
     * the real font actually load. Faithful captures are the whole point of this harness.
     */
    private fun fontFaceCss(): String {
        val faces = listOf(
            Triple("iosevka-light.woff2", 300, "normal"),
            Triple("iosevka-lightitalic.woff2", 300, "italic"),
            Triple("iosevka-regular.woff2", 400, "normal"),
            Triple("iosevka-italic.woff2", 400, "italic"),
            Triple("iosevka-bold.woff2", 700, "normal"),
            Triple("iosevka-bolditalic.woff2", 700, "italic")
        )
        return faces.filter { Files.exists(woff2Dir.resolve(it.first)) }.joinToString("\n") { (file, weight, style) ->
            val b64 = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(woff2Dir.resolve(file)))
            "@font-face{font-family:'Iosevka Web';font-weight:$weight;font-style:$style;" +
                "src:url('data:font/woff2;base64,$b64') format('woff2');}"
        }
    }

    private fun document(fragment: String): String = buildString {
        append("<!doctype html><html><head><meta charset='utf-8'>")
        append("<style>")
        append(fontFaceCss())
        // Mirror the app: the global rule forces discretionary ligatures on; the value-viz root
        // additionally enables contextual (`calt`) so `->` ligates in the display.
        append("*{font-feature-settings:\"dlig\" 1;}")
        append("html,body{margin:0;padding:0;background:#1e1e1e;}")
        append("#capture{display:inline-block;padding:16px 18px;background:#1e1e1e;}")
        append("</style></head><body>")
        append("<div id='capture'>")
        append(fragment)
        append("</div></body></html>")
    }

    private fun capture(dir: Path, name: String, value: Any?): Path {
        val fragment = ValueVisualizer.visualize(value)
        assertTrue(fragment.contains("value-viz"), "expected a real visualizer fragment for $name")
        val out = dir.resolve("$name.png")
        val page = pageRef ?: error("Playwright page not initialised")
        page.setContent(document(fragment))
        // Wait for the real webfonts to load before shooting; otherwise the ~1.2 MB Iosevka
        // faces miss the frame and the capture falls back to system mono (no `->` ligature).
        page.evaluate("document.fonts.ready")
        page.waitForTimeout(250.0)
        val el = page.querySelector("#capture") ?: error("capture element missing for $name")
        el.screenshot(
            com.microsoft.playwright.ElementHandle.ScreenshotOptions().setPath(out)
        )
        assertTrue(Files.exists(out) && Files.size(out) > 0, "expected a non-empty PNG for $name")
        println("captured $name -> $out (${Files.size(out)} bytes)")
        return out
    }

    private var pageRef: com.microsoft.playwright.Page? = null

    @Test
    fun `capture value visualizer gallery assets`() {
        val pw = Playwright.create()
        val browser = pw.chromium().launch(
            BrowserType.LaunchOptions().setHeadless(true)
        )
        try {
            val ctx = browser.newContext(
                com.microsoft.playwright.Browser.NewContextOptions().setDeviceScaleFactor(2.0)
            )
            val page = ctx.newPage()
            pageRef = page

            // ---- vector_algebra.md (Matrices section): the generated f64 matrix family + f32 bridge --------
            // Every value below is the REAL result of the doc's snippets, rendered through the
            // same visualizer as a diverging heatmap. Numbers cross-checked against DoubleMatrixTest.

            // 1. Construct: column-major Matrix2d(m00, m01, m10, m11).
            capture(matrixAssetDir, "construct_mat2", Matrix2d(1.0, 2.0, 3.0, 4.0))

            // 2. Multiply: A*B = [[23,31],[34,46]] (mul composes onto a copy of A).
            val a2 = Matrix2d(1.0, 2.0, 3.0, 4.0)
            val b2 = Matrix2d(5.0, 6.0, 7.0, 8.0)
            capture(matrixAssetDir, "multiply_mat2", Matrix2d(a2).mul(b2))

            // 3. Inverse round-trip: A * A⁻¹ ≈ identity (det(A) = -2, so A is invertible).
            capture(matrixAssetDir, "inverse_identity_mat2", Matrix2d(a2).mul(Matrix2d(a2).invert()))

            // 4. A 3×3 linear map with det = 25 (labelled heatmap).
            val m3d = Matrix3d().set(
                2.0, 0.0, 1.0,
                1.0, 3.0, 0.0,
                0.0, 1.0, 4.0,
            )
            capture(matrixAssetDir, "linear_mat3", m3d)

            // 5. Compose an affine transform: scale then translate (Matrix4d), det = 24.
            capture(
                matrixAssetDir,
                "affine_mat4",
                Matrix4d().scale(2.0, 3.0, 4.0).translate(5.0, 6.0, 7.0)
            )

            // 6. f32 bridge: rotation lives on Matrix4f (the f64 family has no rotation helper).
            capture(matrixAssetDir, "rotate_mat4f", Matrix4f().rotateY(0.6f))

            // 7. f32 bridge: transform a point through a matrix (result is a Vector3f).
            //    translation(5,6,7) applied to (1,2,3) -> (6,8,10).
            capture(matrixAssetDir, "transform_point", Matrix4f().translation(5f, 6f, 7f) * vec(1, 2, 3))

            ctx.close()
        } finally {
            runCatching { browser.close() }
            runCatching { pw.close() }
        }
    }
}
