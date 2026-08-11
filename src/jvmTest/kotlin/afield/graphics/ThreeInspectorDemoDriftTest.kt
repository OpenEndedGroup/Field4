package afield.graphics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The loadable demo and the docs capture of it are the **same code**, character for character.
 *
 * A demo artifact has a characteristic failure that no test in this repo was catching: it is written
 * once, it is never executed by anything, and six weeks later it does not compile. `brief_house_demo`
 * and `fline_hit_records_demo` are both in that position today — referenced from prose, verified by
 * nobody.
 *
 * The dodge here is cheap and total. The demo's Kotlin is *also* the body of a `diffstatic_runner`
 * fence in `public/docs/fline_examples.md`, so the docs build compiles it, runs it in a real box and
 * screenshots the result. If it stops compiling, the capture fails and the docs build says so. All that
 * is then needed is a guard that the two copies have not diverged — which is this file.
 *
 * Character for character, deliberately, rather than "semantically equivalent". A comparison with any
 * slack in it is a comparison somebody will eventually satisfy by editing one side, and the whole value
 * of the arrangement is that the screenshot is a picture of the artifact the human would open rather
 * than of a tidied-up cousin of it.
 */
class ThreeInspectorDemoDriftTest {

    private val demo = File("public/docs/assets/three_inspector_demo/metadata.edn")
    private val page = File("public/docs/fline_examples.md")
    private val screenshot = "assets/three_inspector_demo/opened.png"

    /**
     * The `:code` string of the demo document's single box, unescaped.
     *
     * A hand-rolled reader rather than an EDN parser: the repo has no EDN dependency on the JVM test
     * classpath, and the three escapes the writer emits — backslash, quote, newline — are the three this
     * needs to undo. It throws on any other escape rather than passing it through, so a future demo that
     * needs `\t` fails loudly here instead of comparing unequal for a reason nobody can see.
     */
    private fun demoCode(): String {
        assertTrue(demo.isFile, "the demo artifact is missing at ${demo.path}")
        val text = demo.readText()
        val start = text.indexOf(":code \"")
        assertTrue(start >= 0, "the demo has no :code property")
        val builder = StringBuilder()
        var i = start + ":code \"".length
        while (i < text.length) {
            val c = text[i]
            if (c == '"') break
            if (c == '\\') {
                when (val escape = text[i + 1]) {
                    'n' -> builder.append('\n')
                    '"' -> builder.append('"')
                    '\\' -> builder.append('\\')
                    else -> error("the demo's :code uses an escape this reader does not know: \\$escape")
                }
                i += 2
            } else {
                builder.append(c)
                i += 1
            }
        }
        return builder.toString()
    }

    /** The body of the runner fence that produces [screenshot], with its `:::static` marker removed. */
    private fun fenceCode(): String {
        assertTrue(page.isFile, "the docs page is missing at ${page.path}")
        val lines = page.readLines()
        val open = lines.indexOfFirst { it.startsWith("```diffstatic_runner") && it.contains(screenshot) }
        assertTrue(open >= 0, "no runner fence in ${page.path} captures $screenshot")
        val close = lines.subList(open + 1, lines.size).indexOfFirst { it.trimEnd() == "```" }
        assertTrue(close >= 0, "the fence capturing $screenshot is never closed")
        val body = lines.subList(open + 1, open + 1 + close)
        assertEquals(":::static", body.first(), "the fence should open with the :::static marker")
        return body.drop(1).joinToString("\n") + "\n"
    }

    @Test
    fun `the demo document and the captured fence are the same code`() {
        val fromDemo = demoCode()
        val fromFence = fenceCode()
        if (fromDemo != fromFence) {
            // A diff a human can act on, rather than 4000 characters of "expected … but was …".
            val a = fromDemo.lines()
            val b = fromFence.lines()
            val at = (0 until maxOf(a.size, b.size)).firstOrNull { a.getOrNull(it) != b.getOrNull(it) }
            error(
                "the demo artifact and its docs capture have drifted at line ${at?.plus(1)}:\n" +
                    "  metadata.edn: ${a.getOrNull(at ?: 0)}\n" +
                    "  fline_examples.md: ${b.getOrNull(at ?: 0)}\n" +
                    "Regenerate one from the other; the screenshot has to be a picture of the artifact " +
                    "the human would actually open."
            )
        }
        assertTrue(fromDemo.length > 1500, "the demo shrank to ${fromDemo.length} characters")
    }

    /**
     * The demo actually exercises all three capabilities, and its screenshot is committed.
     *
     * Guarding the *content* as well as the equality, because two identical copies of a demo that no
     * longer mentions the section tool are still two identical copies.
     */
    @Test
    fun `the demo exercises all three capabilities and its capture is committed`() {
        val code = demoCode()
        listOf("threeInspect(", "ThreeExplode(", "ThreeMeasurement(", "ThreeMeasureElement.face", "view.section(")
            .forEach { assertTrue(code.contains(it), "the demo no longer mentions `$it`") }
        assertTrue(
            File("public/docs/assets/three_inspector_demo/opened.png").length() > 10_000,
            "the demo's capture is missing or empty",
        )
        assertTrue(page.readText().contains("![The three-inspector demo document, opened]("), "the capture is not shown")
    }
}
