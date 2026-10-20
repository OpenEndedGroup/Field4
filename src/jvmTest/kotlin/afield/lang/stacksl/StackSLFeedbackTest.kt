package afield.lang.stacksl

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shader [StackSLFeedback] draws with and the lowering panel it shows, without a device.
 *
 * The programs are StackTrinket's fifteen presets, verbatim from `madp/stacktrinket.html`: they are
 * written with the `time` and `hash` words, never the raw lanes, so they must compile unchanged in
 * Field's dialect (`vec4(uv, hash, time)`) even though the page passes `vec4(uv, time, hash)`.
 */
class StackSLFeedbackTest {

    private val presets = linkedMapOf(
        "pulse ring" to "p\nlength\n0.5 0.08 pulse\nwhite *",
        "feedback fog" to "prev 0.982 *\nuv 5.5 * 0.12t + fbm\npalette 0.08 *\n+",
        "gather luma" to "uv 0.002 * gather\nluma\nwhite *",
        "ryb field" to "uv 4.0 * time 0.2 * + fbm\ndup dup 0.18 + swap 0.42 + vec3\nryb2rgb\nsaturate",
        "oklab grade" to "p\ndup length 0.45 0.18 pulse\ntime 0.18 * + palette\n0.85 saturation\n1.2 contrast\nsaturate",
        "wave clocks" to "p length 0.35 0.07 pulse\n0.5 |c1.3p0.2 0.25 |s2.1p1.0 + 0.2 |tri0.7 +\npalette\n*\n0.4 |pulse3.0d0.18 +\nsaturate",
        "noisy compass" to "p ]wr18\n0.35n2 +\n0.2grain80 +\npalette\n0.25 |fbm3 +\np fbm ]c2p0.2 +\n0.1ang +\nsaturate",
        "wire ribbons" to "p\ndup length 0.38 0.05 pulse\nswap 6.0 * 0.22t + sin\n0.12_0.88 median +\npalette\n*\n0.8r 0.35g + 0.12b + +\nsaturate",
        "persistent bloom" to "prev 0.965 *\np length 0.32 0.05 pulse\n0.08t palette\n*\n+",
        "store channels" to "0-> 0.985 *\np length 0.5 0.08 |sh10 pulse\n0.08 *\n+\ndup ->0\ndup 0.65 * ->1\ndup 0.35 * ->2\nsaturate",
        // The one preset that needs translating: Field requires a definition to be net zero on the
        // stack, and the page's rings push a value without taking one. Each now drops the input it
        // is called with, which the page's version ignored anyway.
        "subroutine cse" to ": ring-a\n  drop\n  0-> 0.982 *\n  p length 0.42 0.035 pulse 0.08 *\n  +\n;\n\n: ring-b\n  drop\n  0-> 0.982 *\n" +
            "  p length 0.52 0.035 pulse 0.05 *\n  +\n;\n\ndrop\ninput ring-a\ninput ring-b\n+\ninput ring-a\n+ 0.333 *\ndup ->0\npalette\nsaturate",
        "feedback drift" to "0.004wx7 0.004wy5 vec2 sample\n0.986 *\np length 0.42 0.035 pulse\npalette\n0.05 *\n+\nsaturate",
        "four memory bands" to "uv 4.0 * 0.06t + fbm\ndup 0.17 +\ndup 0.41 +\ndup 0.73 +\nvec4\nprev\n0.015_0.18\nema\nsaturate",
        "braided transport" to "p ]wa10 0.006 *\np ]wr12 0.006 *\nvec2 sample\np ]wx15 0.004 *\np ]wy11 0.004 *\nvec2 sample\n" +
            "+ 0.5 *\nprev 0.92 * +\nuv 8.0 * 0.03t + fbm palette 0.04 *\n+\nsaturate",
        "long memory ecology" to "prev\ndup xy 0.012 * sample\nswap zw 0.009 * sample\n+ 0.5 *\np ]wr9 0.006 * 0.004c0.17 +\n" +
            "p ]wa7 0.006 * 0.004s0.11 +\nvec2 sample\n0.65 * +\nuv 5.0 * prev 0.4 * + 0.02t + fbm\n" +
            "dup 0.23 + dup 0.37 + dup 0.61 + vec4\n0.08 * +\nprev\n0.006_0.09\nema\ndup average\n0.015pulse0.23d0.09\n+ +\nmirror\nsaturate",
    )

    private fun compile(source: String) = StackSLCompiler.compile(source, StackSLFeedbackShader.compileOptions)

    @Test
    fun `every StackTrinket preset compiles in Field's dialect`() {
        presets.forEach { (name, source) ->
            val r = compile(source)
            assertTrue(r.ok, "$name: ${r.diagnostics}")
            assertTrue(Regex("""\bvec4 ${StackSLFeedbackShader.PREFIX}\(vec4 \w+\)""").containsMatchIn(r.glsl), "$name has no ${StackSLFeedbackShader.PREFIX}(vec4) entry point")
        }
    }

    @Test
    fun `the feedback presets read the previous frame through state`() {
        listOf("feedback fog", "persistent bloom", "store channels", "four memory bands", "braided transport", "long memory ecology").forEach { name ->
            val r = compile(presets.getValue(name))
            assertEquals(setOf(StackSLFeedbackShader.STATE), r.analysis.requiredSamplers.map { it }.toSet(), name)
            assertTrue("texture(${StackSLFeedbackShader.STATE}" in r.glsl || "(${StackSLFeedbackShader.STATE}," in r.glsl, "$name does not sample state")
        }
        assertTrue(compile(presets.getValue("pulse ring")).analysis.requiredSamplers.isEmpty())
    }

    @Test
    fun `the panel reads the stack as StackTrinket does`() {
        val l = StackSLLowering(presets.getValue("pulse ring"), compile(presets.getValue("pulse ring")), null, running = true)
        assertTrue(l.ok)
        // The page says exactly this for the pulse ring: the input is still under the ring.
        assertEquals("stack depth 2 after compile; 1 leftover value under the returned top.", l.guidance)
        assertEquals(2, l.result.analysis.danglingWires.size)
        val html = l.toHTML()
        assertTrue("drawing" in html && "pulse" in html && "wires 2" in html, html)
        assertTrue(l.numberedGlsl.lines().first().startsWith("  1 | "))
        assertTrue("lowered GLSL" in html)
        assertTrue("vec4 ${StackSLFeedbackShader.PREFIX}(" in l.glslHtml && "  1 | " in l.glslHtml)
    }

    @Test
    fun `a bad program says where, and that the last one is still drawing`() {
        val source = "p length\n0.5 0.08 pulze"
        val l = StackSLLowering(source, compile(source), null, running = true)
        assertFalse(l.ok)
        val html = l.toHTML()
        assertTrue("last good program is still drawing" in html, html)
        assertTrue(Regex("""error 2:\d+ """).containsMatchIn(html), html)
        assertTrue("pulze" in html, html)
    }

    @Test
    fun `the fragment shader and every preset compile to SPIR-V`() {
        val validator = (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .plus(listOf("/opt/homebrew/bin", "/usr/local/bin"))
            .map { File(it, "glslangValidator") }
            .firstOrNull { it.canExecute() }
        if (validator == null) {
            println("SKIPPED StackSLFeedbackTest SPIR-V check: glslangValidator not found. The feedback shader was NOT checked by a shader compiler.")
            return
        }
        val dir = java.nio.file.Files.createTempDirectory("stacksl-feedback").toFile()
        try {
            fun validate(name: String, text: String): Pair<Boolean, String> {
                val f = File(dir, "$name.frag").apply { writeText(text) }
                val p = ProcessBuilder(validator.absolutePath, "-V", "--target-env", "vulkan1.2", "-S", "frag", f.absolutePath, "-o", File(dir, "$name.spv").absolutePath)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return false to "timed out" }
                return (p.exitValue() == 0) to out
            }
            assertFalse(validate("negative", "#version 450\nvoid main() { not glsl; }\n").first, "the negative control compiled")
            val failures = presets.mapNotNull { (name, source) ->
                val (ok, out) = validate(name.replace(' ', '_'), StackSLFeedbackShader.fragment(compile(source).glsl))
                if (ok) null else "--- $name ---\n$out"
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
