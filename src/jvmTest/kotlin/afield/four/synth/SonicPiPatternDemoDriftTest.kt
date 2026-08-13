package afield.four.synth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The loadable demo and the code shown on the docs page are the **same code**, character for
 * character — and every API name it uses still exists.
 *
 * A demo artifact has a characteristic failure, described at length in
 * [afield.graphics.ThreeInspectorDemoDriftTest]: it is written once, executed by nothing, and six
 * weeks later it does not compile. That test's dodge is better than this one's — its demo is *also*
 * the body of a `diffstatic_runner` fence, so the docs build compiles and runs it. These three
 * boxes are `diffstatic` instead, because two of them need a Sonic Pi install and a docs build that
 * depends on one is a docs build that fails on CI for a reason that is not a regression.
 *
 * So the compile check is done by reflection instead: every public name the boxes call is looked up
 * here. That catches the overwhelmingly likely cause of a demo rotting — something was renamed —
 * without pretending to be a compiler. It does not catch a changed signature, and the worklog for
 * `nightly/2026-08-12/1-pat-plays-sonic-pi` says so.
 *
 * The three boxes were run in a real Field against a real Sonic Pi 5 when they were written; that
 * is a stronger statement than this file makes, and an older one every day.
 */
class SonicPiPatternDemoDriftTest {

    private val demo = File("public/docs/assets/sonicpi_pattern_demo/metadata.edn")
    private val page = File("public/docs/sonic_pi.md")

    /**
     * Every `:code` string in the demo document, unescaped, in document order.
     *
     * A hand-rolled reader rather than an EDN parser, for the reason
     * [afield.graphics.ThreeInspectorDemoDriftTest] gives: there is no EDN dependency on the JVM
     * test classpath, and the three escapes the writer emits are the three this undoes. It throws
     * on any other escape rather than passing it through, so a future demo that needs `\t` fails
     * loudly here instead of comparing unequal for a reason nobody can see.
     */
    private fun demoCode(): List<String> {
        assertTrue(demo.isFile, "the demo artifact is missing at ${demo.path}")
        val text = demo.readText()
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = text.indexOf(":code \"", from)
            if (start < 0) break
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
            out += builder.toString()
            from = i
        }
        return out
    }

    /** The bodies of the `diffstatic` fences in the demo section, `:::static` markers removed. */
    private fun fenceCode(): List<String> {
        assertTrue(page.isFile, "the docs page is missing at ${page.path}")
        val lines = page.readLines()
        val sectionAt = lines.indexOfFirst { it.startsWith("### A document to open") }
        assertTrue(sectionAt >= 0, "${page.path} has no 'A document to open' section")
        val endAt = lines.subList(sectionAt + 1, lines.size)
            .indexOfFirst { it.startsWith("## ") }
            .let { if (it < 0) lines.size else sectionAt + 1 + it }

        val out = mutableListOf<String>()
        var i = sectionAt
        while (i < endAt) {
            if (lines[i].startsWith("```diffstatic")) {
                val close = lines.subList(i + 1, endAt).indexOfFirst { it.trimEnd() == "```" }
                assertTrue(close >= 0, "a fence in the demo section is never closed")
                val body = lines.subList(i + 1, i + 1 + close)
                assertEquals(":::static", body.first(), "the fence should open with the :::static marker")
                out += body.drop(1).joinToString("\n") + "\n"
                i += close + 1
            }
            i++
        }
        return out
    }

    @Test
    fun `the demo document and the code shown on the page are the same`() {
        val fromDemo = demoCode()
        val fromPage = fenceCode()
        assertEquals(3, fromDemo.size, "the demo should have three boxes")
        assertEquals(
            fromDemo.size,
            fromPage.size,
            "the demo has ${fromDemo.size} boxes but the page shows ${fromPage.size}",
        )
        for ((index, pair) in fromDemo.zip(fromPage).withIndex()) {
            val (a, b) = pair
            if (a == b) continue
            // A diff a human can act on, rather than 2000 characters of "expected … but was …".
            val al = a.lines()
            val bl = b.lines()
            val at = (0 until maxOf(al.size, bl.size)).firstOrNull { al.getOrNull(it) != bl.getOrNull(it) }
            error(
                "demo box ${index + 1} and its listing on ${page.name} have drifted at line ${at?.plus(1)}:\n" +
                    "  metadata.edn:  ${al.getOrNull(at ?: 0)}\n" +
                    "  sonic_pi.md:   ${bl.getOrNull(at ?: 0)}\n" +
                    "Regenerate one from the other. The page has to show the artifact a human would open."
            )
        }
    }

    @Test
    fun `the demo still exercises both directions and the committed measurement is shown`() {
        val code = demoCode().joinToString("\n")
        listOf(
            "sonicPiSource(",       // compile, to text
            "SonicPiVocabulary",    // the installed vocabulary
            "SonicPiInstruments(",  // atom resolution
            "sonicPiPattern(",      // compile, played
            "sonicPiLayer(",        // a layer with its own synth
            "sonicPiLive(",         // drive
            "with gain(",           // a control parameter
            "sine().range(",        // sampled per note
        ).forEach { assertTrue(it in code, "the demo no longer mentions `$it`") }

        assertTrue(
            File("public/docs/assets/sonic_pi/pattern_onsets.png").length() > 5_000,
            "the committed onset measurement is missing or empty",
        )
        assertTrue(
            "assets/sonic_pi/pattern_onsets.png" in page.readText(),
            "the onset measurement is committed but never shown on the page",
        )
        assertTrue(
            "assets/sonicpi_pattern_demo/metadata.edn" in page.readText(),
            "the page never says where the loadable demo is",
        )
    }

    /**
     * Every name the demo calls resolves, so a rename breaks this rather than the human's morning.
     *
     * Reflection over the file class `afield.four.synth.SonicPiPatternsKt`, plus the two types the
     * demo names directly. Not a compiler — a changed *signature* still slips through — but a
     * rename is what actually happens, and it is what this catches.
     */
    @Test
    fun `every API name the demo uses still exists`() {
        val file = Class.forName("afield.four.synth.SonicPiPatternsKt")
        val functions = file.methods.map { it.name }.toSet()
        listOf("sonicPiSource", "sonicPiPattern", "sonicPiLayer", "sonicPiLive", "sonicPiProgram")
            .forEach { assertTrue(it in functions, "SonicPiPatternsKt no longer declares `$it`") }

        // The two types the boxes spell out, and the members they reach for.
        assertTrue(SonicPiVocabulary.samples.size > 200, "the vocabulary the demo prints is empty")
        val kit = SonicPiInstruments()
        assertEquals(SonicPiVoice.Sample("bd_haus"), kit.resolve("bd"), "the demo's `bd` no longer resolves")
        assertEquals(null, kit.resolve("nonesuch"), "the demo's unresolvable atom now resolves")

        // The parameter helpers the demo composes, from the neighbouring Pat files.
        val algebra = Class.forName("afield.four.synth.PatAlgebraKt").methods.map { it.name }.toSet()
        listOf("gain", "cutoff", "with").forEach {
            assertTrue(it in algebra, "PatAlgebraKt no longer declares `$it`")
        }
    }
}
