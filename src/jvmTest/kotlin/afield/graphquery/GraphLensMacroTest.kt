package afield.graphquery

import afield.macros.MacroRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the compile-checked path DSL.
 *
 * Two halves. The expansion tests drive [expandGraphLens] directly and assert on the Kotlin
 * source it emits. The runtime tests exercise the *result* of that expansion, hand-written
 * exactly as the macro would emit it — which is what proves the emitted shape actually
 * compiles and behaves, including that `var`/`val` pick different [boundLens] overloads.
 */
class GraphLensMacroTest {

    class Osc(var freq: Float, val fixedGain: Float, var label: String)

    class Rig(val carrier: Osc, var name: String)

    // ── expansion ───────────────────────────────────────────────────────────

    private fun expand(call: String): String = expandGraphLens(listOf(call)).here

    @Test
    fun expandsAMultiSegmentPathIntoABoundPropertyReference() {
        assertEquals(
            "afield.graphquery.boundLens(rig.carrier::freq, \"carrier/freq\")",
            expand("(rig, \"carrier/freq\")")
        )
    }

    @Test
    fun expandsASingleSegmentPathAgainstTheReceiverItself() {
        assertEquals(
            "afield.graphquery.boundLens(rig::name, \"name\")",
            expand("(rig, \"name\")")
        )
    }

    @Test
    fun expandsADeepPath() {
        assertEquals(
            "afield.graphquery.boundLens(a.b.c.d::e, \"b/c/d/e\")",
            expand("(a, \"b/c/d/e\")")
        )
    }

    @Test
    fun toleratesWhitespaceAndComplexReceiverExpressions() {
        assertEquals(
            "afield.graphquery.boundLens(rigs[0].carrier::freq, \"carrier/freq\")",
            expand("( rigs[0] , \"carrier/freq\" )")
        )
        assertEquals(
            "afield.graphquery.boundLens(build(1, 2).carrier::freq, \"carrier/freq\")",
            expand("(build(1, 2), \"carrier/freq\")"),
            "a comma inside the receiver must not be read as an argument separator"
        )
    }

    @Test
    fun ignoresRedundantPathSeparators() {
        assertEquals(
            "afield.graphquery.boundLens(rig.carrier::freq, \"carrier/freq\")",
            expand("(rig, \"/carrier//freq/\")")
        )
    }

    // ── refusals: the guarantee is "if it compiles, it was checked" ──────────

    @Test
    fun refusesWildcardsRatherThanDegradingSilently() {
        val failure = assertFailsWith<MacroRegistry.MacroExpansionProblem> {
            expand("(rig, \"**/freq\")")
        }
        assertTrue(
            failure.message!!.contains("graphPaths"),
            "the refusal must point at the reflective API: ${failure.message}"
        )
    }

    @Test
    fun refusesListIndicesAndMapKeys() {
        assertFailsWith<MacroRegistry.MacroExpansionProblem> { expand("(rig, \"voices/0/freq\")") }
        assertFailsWith<MacroRegistry.MacroExpansionProblem> { expand("(rig, \"by name/freq\")") }
    }

    @Test
    fun refusesANonLiteralPath() {
        assertFailsWith<MacroRegistry.MacroExpansionProblem> { expand("(rig, pathVariable)") }
        assertFailsWith<MacroRegistry.MacroExpansionProblem> { expand("(rig, \"a/\" + suffix)") }

        val interpolated = assertFailsWith<MacroRegistry.MacroExpansionProblem> {
            expand("(rig, \"carrier/\$name\")")
        }
        assertTrue(
            interpolated.message!!.contains("graphPath"),
            "interpolation must be refused with a pointer to the runtime API: ${interpolated.message}"
        )
    }

    @Test
    fun refusesMalformedCalls() {
        assertFailsWith<MacroRegistry.MacroExpansionProblem> { expand("(rig)") }
        assertFailsWith<MacroRegistry.MacroExpansionProblem> { expand("(rig, \"a\", \"b\")") }
        assertFailsWith<MacroRegistry.MacroExpansionProblem> { expand("(rig, \"\")") }
        assertFailsWith<MacroRegistry.MacroExpansionProblem> { expand("rig, \"a\"") }
        assertFailsWith<MacroRegistry.MacroExpansionProblem> { expandGraphLens(listOf("<Float>", "(rig, \"a\")")) }
    }

    // ── the emitted shape, written out as the macro would emit it ────────────

    /**
     * This is the load-bearing case: the code below is literally what the macro produces.
     * That it compiles at all is the proof that the expansion is well-formed, and the
     * inferred types are checked by the compiler rather than asserted here.
     */
    @Test
    fun emittedFormIsTypedAndWritableForVars() {
        val rig = Rig(Osc(220f, 0.7f, "sine"), "rig")

        val freq = boundLens(rig.carrier::freq, "carrier/freq")

        // Inferred as StaticLens<Float>: this would not compile if T were Any?.
        val value: Float = freq.get()
        assertEquals(220f, value)

        assertTrue(freq.writable)
        freq.set(110f)
        assertEquals(110f, rig.carrier.freq)

        freq.update { it * 2f }
        assertEquals(220f, rig.carrier.freq)
        assertEquals("carrier/freq", freq.path)
    }

    /** A `val` selects the read-only overload, so writability is decided at compile time. */
    @Test
    fun emittedFormIsReadOnlyForVals() {
        val rig = Rig(Osc(220f, 0.7f, "sine"), "rig")

        val gain = boundLens(rig.carrier::fixedGain, "carrier/fixedGain")

        assertFalse(gain.writable, "a val must not produce a writable lens")
        assertEquals(0.7f, gain.get())
        assertFailsWith<IllegalStateException> { gain.set(1f) }
    }

    /** Element types other than Float infer just as well. */
    @Test
    fun emittedFormInfersAnyElementType() {
        val rig = Rig(Osc(220f, 0.7f, "sine"), "rig")

        val label: StaticLens<String> = boundLens(rig.carrier::label, "carrier/label")
        label.set("saw")
        assertEquals("saw", rig.carrier.label)

        val name: StaticLens<String> = boundLens(rig::name, "name")
        name.update { it.uppercase() }
        assertEquals("RIG", rig.name)
    }

    /** The static lens agrees with the reflective lookup it replaces. */
    @Test
    fun staticAndReflectiveResolutionAgree() {
        val rig = Rig(Osc(220f, 0.7f, "sine"), "rig")

        val static = boundLens(rig.carrier::freq, "carrier/freq")
        val reflective = rig.graphPath("carrier/freq")!!

        assertEquals(reflective.get(), static.get())

        static.set(330f)
        assertEquals(330f, reflective.get(), "both address the same slot")

        reflective.set(440f)
        assertEquals(440f, static.get())
    }

    // ── argument splitting ──────────────────────────────────────────────────

    @Test
    fun argumentSplitterRespectsNestingAndStrings() {
        assertEquals(listOf("a", "b"), splitTopLevelArguments("a, b"))
        assertEquals(listOf("f(a, b)", "c"), splitTopLevelArguments("f(a, b), c"))
        assertEquals(listOf("m[\"x, y\"]", "c"), splitTopLevelArguments("m[\"x, y\"], c"))
        assertEquals(listOf("\"a, b\""), splitTopLevelArguments("\"a, b\""))
    }

    @Test
    fun identifierCheckMatchesKotlinRules() {
        assertTrue(isKotlinIdentifier("freq"))
        assertTrue(isKotlinIdentifier("_private"))
        assertTrue(isKotlinIdentifier("osc2"))
        assertFalse(isKotlinIdentifier("2osc"))
        assertFalse(isKotlinIdentifier("has space"))
        assertFalse(isKotlinIdentifier("*"))
        assertFalse(isKotlinIdentifier(""))
    }

    @Test
    fun stringLiteralParserRejectsAnythingComputed() {
        assertEquals("a/b", parseSimpleStringLiteral("\"a/b\""))
        assertEquals(null, parseSimpleStringLiteral("variable"))
        assertEquals(null, parseSimpleStringLiteral("\"a\" + \"b\""))
        assertEquals(null, parseSimpleStringLiteral("\"a\$x\""))
    }
}
