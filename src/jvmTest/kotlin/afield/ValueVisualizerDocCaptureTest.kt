package afield

import afield.graphics.FLine
import afield.stdlib.Functions
import afield.stdlib.linalg.Matrix2d
import afield.stdlib.linalg.Matrix3d
import afield.stdlib.linalg.Matrix3f
import afield.stdlib.linalg.Matrix4d
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import afield.stdlib.vec
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Docs-capture harness for the "Reading your values" explainer.
 *
 * This is NOT a behavioural test of [ValueVisualizer] (that lives in
 * [FnPlotVisualizerTest]); it is the honest capture step for the docs page. For each
 * worked-example value it calls the REAL [ValueVisualizer.visualize], wraps the emitted
 * HTML *unchanged* in a minimal dark document (the editor background the renderer targets),
 * and rasterizes the rendered element to a PNG with Playwright/chromium. Nothing is
 * hand-drawn or synthesized — the bytes come straight from the visualizer.
 *
 * Assets are written under `public/docs/assets/value_visualizers/`. Run with:
 *   ./gradlew jvmTest --tests 'afield.ValueVisualizerDocCaptureTest'
 */
class ValueVisualizerDocCaptureTest {

    private data class Camera(
        val position: Vector3f,
        val fieldOfView: Double,
        val active: Boolean,
        val layers: List<String>
    )

    private val assetDir: Path by lazy {
        // Tests run with the working dir at the module root; fall back defensively.
        val candidates = listOf(
            Paths.get("public", "docs", "assets", "value_visualizers"),
            Paths.get(System.getProperty("user.dir"), "public", "docs", "assets", "value_visualizers")
        )
        val chosen = candidates.first()
        Files.createDirectories(chosen)
        chosen
    }

    /** Assets for the matrix-algebra section (in vector_algebra.md) live in their own folder. */
    private val matrixAssetDir: Path by lazy {
        val chosen = Paths.get("public", "docs", "assets", "matrix_algebra")
        Files.createDirectories(chosen)
        chosen
    }

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

    private fun capture(name: String, value: Any?): Path = capture(assetDir, name, value)

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

            // 1. Fn: reflective tree + appended safe SVG plot (the FnPlot headline function).
            val fn = with(Functions) { sin(10).over(0.0..3.0) }
            capture("fn_sin_plot", fn)

            // 2. FLine: node stats + path signature + inline SVG thumbnail (a spiral).
            val spiral = FLine()
            run {
                val turns = 5.0
                val steps = 140
                for (i in 0..steps) {
                    val t = i.toDouble() / steps
                    val a = t * turns * 2.0 * Math.PI
                    val r = 8.0 + t * 60.0
                    val x = 80.0 + Math.cos(a) * r
                    val y = 80.0 + Math.sin(a) * r
                    if (i == 0) spiral.moveTo(x, y) else spiral.lineTo(x, y)
                }
            }
            capture("fline_spiral", spiral)

            // 3. Vectors: compact colored tokens for vec2 / vec3 / vec4.
            capture(
                "vectors",
                listOf(vec(0.5, 1.5), vec(1.0, 2.0, 3.0), vec(0.25, 0.5, 1.0, 2.0))
            )

            // 4. Matrices + quaternion, keyed by a map (self-labeling, also shows the tree).
            val mat3 = Matrix3f().set(2f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 1f)
            val mat4 = Matrix4f().identity()
            mat4.set(3, 0, 5f); mat4.set(3, 1, -2f); mat4.set(3, 2, 1f)
            val quat = Quaternionf().rotateY(0.6f)
            capture(
                "linalg_map",
                linkedMapOf(
                    "scale (mat3)" to mat3,
                    "translate (mat4)" to mat4,
                    "spin (quat)" to quat
                )
            )

            // 5. Numeric collection: 48-sample sine DoubleArray -> preview elision + μ/σ/range.
            val samples = DoubleArray(48) { i -> Math.sin(i / 48.0 * 2.0 * Math.PI) }
            capture("double_array", samples)

            // 6. Generic reflective tree for an arbitrary (data) class.
            val cam = Camera(
                position = Vector3f(0f, 1.5f, -4f),
                fieldOfView = 55.0,
                active = true,
                layers = listOf("dots", "lines", "labels")
            )
            capture("reflective_tree", cam)

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
