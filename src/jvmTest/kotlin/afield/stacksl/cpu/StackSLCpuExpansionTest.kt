package afield.stacksl.cpu

import afield.stacksl.StackSLCompileOptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does the CPU backend compile a `{{ stack.auto.<slot> }}` slot the way production does?
 *
 * [StackSLCpu.stackAutoOptions] is a hand copy of the option set in
 * `afield.stdlib.shaders.compileStackAutoShaderFunctionReporting`, which is the real expansion path
 * a live shader goes through. The two cannot *share* the constant: `shaders.kt` imports
 * `afield.four.Renderer` and most of the graphics stack, and a backend whose whole purpose is to
 * need no graphics context cannot depend on it. Inverting the dependency — moving the option set
 * down into `afield.stacksl` — would be the real fix and is a change to the GPU path, which this
 * branch deliberately does not touch.
 *
 * So the copies are pinned by reading the other file's source text. That is a blunt instrument and
 * worth being honest about: it can only see a literal argument list, it breaks on reformatting, and
 * it says nothing about *behaviour*. What it does catch is the one failure that matters and is
 * otherwise silent — somebody adding or changing an option on the production side, leaving the CPU
 * backend quietly compiling programs under different rules from the shader it claims to mirror.
 *
 * The alternative was to write nothing and let the KDoc claim a guard existed. It did, briefly, and
 * that is worse than a blunt guard.
 */
class StackSLCpuExpansionTest {

    private val productionFile = File(
        System.getProperty("user.dir"),
        "src/jvmMain/kotlin/afield/stdlib/shaders.kt"
    )

    /**
     * The named arguments of the `StackSLCompileOptions(...)` call inside
     * `compileStackAutoShaderFunctionReporting`, as `name` to the raw source text of the value.
     */
    private fun productionArguments(): Map<String, String> {
        val text = productionFile.readText()
        val fnAt = text.indexOf("private fun compileStackAutoShaderFunctionReporting(")
        assertTrue(fnAt >= 0, "could not find compileStackAutoShaderFunctionReporting in ${productionFile.name}")
        val callAt = text.indexOf("StackSLCompileOptions(", fnAt)
        assertTrue(callAt in (fnAt + 1) until (fnAt + 4000), "no StackSLCompileOptions call in that function")

        // Balanced-paren scan from the opening bracket, so a nested `listOf(...)` does not end it.
        var i = text.indexOf('(', callAt)
        var depth = 0
        val start = i + 1
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        val body = text.substring(start, i)

        // Split on top-level commas only.
        val args = mutableListOf<String>()
        var current = StringBuilder()
        var nesting = 0
        body.forEach { ch ->
            when {
                ch == '(' || ch == '[' -> { nesting++; current.append(ch) }
                ch == ')' || ch == ']' -> { nesting--; current.append(ch) }
                ch == ',' && nesting == 0 -> { args += current.toString(); current = StringBuilder() }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) args += current.toString()

        return args.mapNotNull { arg ->
            val eq = arg.indexOf('=')
            if (eq < 0) null else arg.take(eq).trim() to arg.substring(eq + 1).trim()
        }.toMap()
    }

    /**
     * Every option production names is named here too, and the fixed ones have the same values.
     *
     * `prefix`, `entrypoint`, `useEntrypointAsRoot` and `sampleTextures` are per-call on the
     * production side (they come from the slot's `StackAutoShaderTarget`), so only their *presence*
     * is checked. The four that are hard-coded there are checked for value.
     */
    @Test
    fun `the CPU backend's stack-auto options match the production expansion path`() {
        val production = productionArguments()
        val mine = StackSLCpu.stackAutoOptions("program", "source")

        assertEquals(
            setOf("prefix", "entrypoint", "useEntrypointAsRoot", "tolerant", "includeHeader",
                "declareSamplers", "externalizeConstants", "sampleTextures"),
            production.keys,
            "the production stack.auto option set changed; StackSLCpu.stackAutoOptions must follow it"
        )

        // The four production hard-codes, against what the CPU backend actually built.
        assertEquals("false", production.getValue("tolerant"))
        assertEquals(false, mine.tolerant)
        assertEquals("false", production.getValue("includeHeader"))
        assertEquals(false, mine.includeHeader)
        assertEquals("false", production.getValue("declareSamplers"))
        assertEquals(false, mine.declareSamplers)
        assertEquals("false", production.getValue("externalizeConstants"))
        assertEquals(false, mine.externalizeConstants)

        // And the per-call ones are shaped the same way: one sampler name, `main` as the root.
        assertTrue("listOf(" in production.getValue("sampleTextures"), production.getValue("sampleTextures"))
        assertEquals(listOf("source"), mine.sampleTextures)
        assertEquals("main", mine.entrypoint)
        assertEquals(false, mine.useEntrypointAsRoot)
        assertEquals("program", mine.prefix)
    }

    /**
     * Neither side overrides `defaultValues`, so the compiler's own defaults for a missing operand
     * are in force on both. Checked because it is an option that exists, is not named at either
     * site, and would change what a half-written program compiles to if one side started setting it.
     */
    @Test
    fun `neither side overrides the dangling-operand defaults`() {
        assertEquals(StackSLCompileOptions().defaultValues, StackSLCpu.stackAutoOptions("program").defaultValues)
        assertTrue("defaultValues" !in productionArguments().keys)
    }

    /**
     * The expansion puts the emitted GLSL in *verbatim* and refuses a template with no slot.
     *
     * The `$` check is not hypothetical: `Regex.replace(String)` interprets `$1` and `\` in its
     * replacement, and the emitter writes plenty of both into shader text. The lambda overload does
     * not, which is why [StackSLCpu.expandStackAuto] uses it.
     */
    @Test
    fun `expandStackAuto substitutes verbatim and refuses a template with no slot`() {
        val template = "// before\n{{ stack.auto.program }}\n// after\n"
        val expanded = StackSLCpu.expandStackAuto(template, "uv saturate")
        val glsl = StackSLCpu.compile("uv saturate").glsl
        assertEquals("// before\n$glsl\n// after\n", expanded)

        val noSlot = runCatching { StackSLCpu.expandStackAuto("// nothing here\n", "uv saturate") }
        assertTrue(noSlot.isFailure, "a template with no slot should be refused")
        assertTrue("stack.auto.program" in noSlot.exceptionOrNull()!!.message!!)

        // Whitespace inside the tag is tolerated, as the production template regex tolerates it.
        assertEquals(
            StackSLCpu.expandStackAuto("{{ stack.auto.program }}", "uv saturate"),
            StackSLCpu.expandStackAuto("{{stack.auto.program}}", "uv saturate")
        )
    }

    /** A program that does not compile is refused with the compiler's own error text. */
    @Test
    fun `a broken program is refused with the compiler's diagnostics`() {
        val broken = runCatching {
            StackSLCpu.expandStackAuto("{{ stack.auto.program }}", "uv notaword")
        }
        assertTrue(broken.isFailure)
        val message = broken.exceptionOrNull()!!.message!!
        assertTrue("does not compile" in message, message)
        assertTrue("notaword" in message, message)
    }
}
