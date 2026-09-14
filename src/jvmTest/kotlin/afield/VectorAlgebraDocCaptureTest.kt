package afield

import afield.stdlib.*
import afield.stdlib.linalg.*
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Docs-capture harness for the "Vector algebra: swizzles & operators" explainer, which lives in
 * `public/docs/linear_algebra.md`.
 *
 * Like [ValueVisualizerDocCaptureTest], this is NOT a behavioural test: it is the honest
 * capture step for the docs page. Each worked-example value is built with the repository's current
 * swizzle/operator API and rendered by [ValueVisualizer.visualize],
 * whose HTML (with its auto-scaled arrow thumbnails) is rasterized to a PNG by Playwright/
 * chromium. Nothing is hand-drawn or synthesized — the bytes come straight from the visualizer.
 *
 * By default the PNGs go to a temp directory, so an ordinary `jvmTest` run asserts the whole render
 * path still works without touching the tree. The committed figures under
 * `public/docs/assets/vector_algebra/` are refreshed only on request — see [docsAssetDir]:
 *
 * ```
 * AFIELD_DOCS_ASSETS=1 ./gradlew jvmTest --tests 'afield.VectorAlgebraDocCaptureTest'
 * ```
 */
class VectorAlgebraDocCaptureTest {

    private val assetDir: Path by lazy { docsAssetDir("public/docs/assets/vector_algebra") }

    private val woff2Dir: Path by lazy {
        Paths.get(System.getProperty("user.dir"), "src", "jsMain", "resources", "woff2").toAbsolutePath()
    }

    /** Inline the shipped Iosevka faces so captures match the app font (see the sibling harness). */
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
        append("*{font-feature-settings:\"dlig\" 1;}")
        append("html,body{margin:0;padding:0;background:#1e1e1e;}")
        append("#capture{display:inline-block;padding:16px 18px;background:#1e1e1e;}")
        append("</style></head><body>")
        append("<div id='capture'>")
        append(fragment)
        append("</div></body></html>")
    }

    private var pageRef: com.microsoft.playwright.Page? = null

    private fun capture(name: String, value: Any?): Path {
        val fragment = ValueVisualizer.visualize(value)
        assertTrue(fragment.contains("value-viz"), "expected a real visualizer fragment for $name")
        val out = assetDir.resolve("$name.png")
        val page = pageRef ?: error("Playwright page not initialised")
        page.setContent(document(fragment))
        page.evaluate("document.fonts.ready")
        page.waitForTimeout(250.0)
        val el = page.querySelector("#capture") ?: error("capture element missing for $name")
        el.screenshot(com.microsoft.playwright.ElementHandle.ScreenshotOptions().setPath(out))
        assertTrue(Files.exists(out) && Files.size(out) > 0, "expected a non-empty PNG for $name")
        println("captured $name -> $out (${Files.size(out)} bytes)")
        return out
    }

    @Test
    fun `capture vector algebra assets`() {
        val pw = Playwright.create()
        val browser = pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        try {
            val ctx = browser.newContext(
                com.microsoft.playwright.Browser.NewContextOptions().setDeviceScaleFactor(2.0)
            )
            val page = ctx.newPage()
            pageRef = page

            // 1. Read swizzles: reorder, narrow, expand, and the fixed repeat `.yy`.
            run {
                val v = vec(1, 2, 3)
                capture(
                    "read_swizzles",
                    linkedMapOf(
                        "v" to vec(v),
                        "v.zyx (reorder)" to v.zyx,
                        "v.xy (narrow)" to v.xy,
                        "v.xxxx (expand)" to v.xxxx,
                        "v.yy (repeat, fixed)" to v.yy
                    )
                )
            }

            // 2. Write swizzle: v.xy = source.zw mutates the target in place.
            run {
                val target = vec(0, 0, 0, 0)
                val source = vec(1, 2, 3, 4)
                val before = vec(target)
                target.xy = source.zw
                capture(
                    "write_swizzle",
                    linkedMapOf(
                        "target (before)" to before,
                        "source" to vec(source),
                        "target.xy = source.zw" to vec(target)
                    )
                )
            }

            // 3. Algebra: component-wise + - * (vec/vec) and vec * scalar.
            run {
                val a = vec(2, 1)
                val b = vec(1, 3)
                capture(
                    "algebra",
                    linkedMapOf(
                        "a" to vec(a),
                        "b" to vec(b),
                        "a + b" to (a + b),
                        "a - b" to (a - b),
                        "a * 2" to (a * 2),
                        "a * b" to (a * b)
                    )
                )
            }

            // 4. Copy operators: invoke copy-with-changes, copy-then-block, and `len` rescale.
            run {
                val v = vec(3, 4) // length 5
                capture(
                    "copy_operators",
                    linkedMapOf(
                        "v" to vec(v),
                        "v(y = 1f)" to v(y = 1f),
                        "v { normalize() }" to v { normalize() },
                        "v len 2" to (v len 2),
                        "v (unchanged)" to vec(v)
                    )
                )
            }

            // 5. In-place assignment: a %= b copies b's components into a.
            run {
                val a = vec(9, 9)
                val b = vec(1, 2)
                val before = vec(a)
                a %= b
                capture(
                    "remassign",
                    linkedMapOf(
                        "a (before)" to before,
                        "b" to vec(b),
                        "a %= b" to vec(a)
                    )
                )
            }

            ctx.close()
        } finally {
            runCatching { browser.close() }
            runCatching { pw.close() }
        }
    }
}
