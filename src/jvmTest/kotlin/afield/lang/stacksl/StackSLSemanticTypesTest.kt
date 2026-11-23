package afield.lang.stacksl

import afield.lang.stacksl.StackSLSemanticType.ComplexSpin
import afield.lang.stacksl.StackSLSemanticType.RawVec4
import afield.lang.stacksl.StackSLSemanticType.RealInvariant
import afield.lang.stacksl.cpu.GlslValue
import afield.lang.stacksl.cpu.StackSLWires
import afield.lang.stacksl.cpu.newInvocation
import afield.lang.stacksl.cpu.parseGlslShader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StackSLSemanticTypesTest {
    @Test
    fun complexSquareUsesExistingVec4Entrypoint() {
        val result = checked("@types assume-c1 dup *")
        val analysis = requireNotNull(result.analysis.semantics)
        assertEquals(ComplexSpin(2), analysis.outputType)
        assertEquals(listOf(StackSLInputAssumption("input.xy", ComplexSpin(1))), analysis.assumptions)
        assertTrue(analysis.equivarianceCertified)
        assertTrue(result.glsl.contains("vec4 stacksl(vec4 stacksl_in)"))
        assertContentEquals(floatArrayOf(-5f, 12f, 0f, 0f), evaluate(result))

        val legacy = checked("dup *")
        assertEquals(null, legacy.analysis.semantics)
        assertContentEquals(floatArrayOf(4f, 9f, 16f, 25f), evaluate(legacy))
    }

    @Test
    fun cacheSeparatesIdenticalRepackingAtDifferentWeights() {
        val result = checked("@types dup assume-c1 swap assume-c2 conj *")
        val analysis = requireNotNull(result.analysis.semantics)
        assertEquals(ComplexSpin(-1), analysis.outputType)
        assertEquals(setOf(ComplexSpin(1), ComplexSpin(2)), analysis.assumptions.map { it.type }.toSet())
        assertFalse(analysis.equivarianceCertified)
        assertTrue(analysis.uncertifiedReasons.any { "Overlapping inputs" in it })
        assertContentEquals(floatArrayOf(13f, 0f, 0f, 0f), evaluate(result))
    }

    @Test
    fun definitionsResolveAtEachCallAndApplyKeepsMarksLocal() {
        val source = """
            @types : square dup * ;
            dup square >raw
            assume-c1 'square apply >squareResult
            input assume-c1 conj >conjugate
            <conjugate <raw <squareResult rot swap over drop rot drop *
        """.trimIndent()
        val result = checked(source)
        val analysis = requireNotNull(result.analysis.semantics)
        assertTrue(analysis.equivarianceCertified)
        assertEquals(ComplexSpin(1), analysis.outputType)
        assertContentEquals(floatArrayOf(26f, 39f, 0f, 0f), evaluate(result))

        val legacy = checked(": conj dup * ; conj")
        assertNull(legacy.analysis.semantics)
        assertContentEquals(floatArrayOf(4f, 9f, 16f, 25f), evaluate(legacy))

        val localMark = checked("@types : square dup * =saved ; assume-c1 =saved 'square apply drop <saved")
        assertEquals(ComplexSpin(1), localMark.analysis.semantics?.outputType)
        assertContentEquals(floatArrayOf(2f, 3f, 0f, 0f), evaluate(localMark))
    }

    @Test
    fun incompatibleWeightsAndUnderflowHaveLocatedDiagnostics() {
        val bad = compileStackSL("@types\nassume-c1 dup dup * +", StackSLCompileOptions(tolerant = true))
        assertFalse(bad.ok)
        val diagnostic = bad.diagnostics.first { it.token == "+" }
        assertEquals(2, diagnostic.location?.line)
        assertEquals(21, diagnostic.location?.column)
        assertTrue("ComplexSpin(1)" in diagnostic.message && "ComplexSpin(2)" in diagnostic.message)
        assertTrue("as-raw" in diagnostic.message)
        assertFalse(requireNotNull(bad.analysis.semantics).equivarianceCertified)

        assertFalse(compileStackSL("@types assume-c1 *").ok)
        assertTrue(checked("*").diagnostics.any { it.severity == StackSLSeverity.WARNING })
        assertFalse(compileStackSL("@types : two dup ; assume-c1 'two apply").ok)
    }

    @Test
    fun onlyDirectReadsAcceptAnAssumption() {
        for (source in listOf("dup *", ".xy", "as-raw", "assume-c1 as-raw", "1", "?_stacksl_v1", "?pc.points[idx]")) {
            val result = compileStackSL("@types $source assume-c1")
            assertFalse(result.ok, source)
            assertFalse(requireNotNull(result.analysis.semantics).equivarianceCertified, source)
        }
        val raw = checked("@types assume-c1 as-raw .w")
        val analysis = requireNotNull(raw.analysis.semantics)
        assertFalse(analysis.equivarianceCertified)
        assertEquals(RawVec4, analysis.outputType)
        assertContentEquals(FloatArray(4), evaluate(raw))
    }

    @Test
    fun realAndComplexAlgebraFollowTheirLaneLayouts() {
        val cases = listOf(
            "assume-c1 dup +" to floatArrayOf(4f, 6f, 0f, 0f),
            "assume-c1 0 +" to floatArrayOf(2f, 3f, 0f, 0f),
            "assume-c1 0 swap -" to floatArrayOf(-2f, -3f, 0f, 0f),
            "assume-c1 i *" to floatArrayOf(-3f, 2f, 0f, 0f),
            "assume-c1 2 swap *" to floatArrayOf(4f, 6f, 0f, 0f),
            "assume-c1 dup 3 * 0.25 mix" to floatArrayOf(3f, 4.5f, 0f, 0f),
            "assume-c1 time assume-r0 *" to floatArrayOf(10f, 15f, 0f, 0f),
            "assume-c1 0 cis *" to floatArrayOf(2f, 3f, 0f, 0f),
            "assume-c0 4 to-complex +" to floatArrayOf(6f, 3f, 0f, 0f),
            "assume-c0 .re" to FloatArray(4) { 2f },
            "assume-c0 imag" to FloatArray(4) { 3f },
            "assume-c1 norm2" to FloatArray(4) { 13f },
            "drop 0 tanh cos exp" to FloatArray(4) { kotlin.math.exp(1f) }
        )
        for ((program, expected) in cases) {
            val result = checked("@types $program")
            val analysis = requireNotNull(result.analysis.semantics)
            assertTrue(analysis.equivarianceCertified, "$program: ${analysis.uncertifiedReasons}")
            assertContentEquals(expected, evaluate(result), program)
        }
        assertEquals(RealInvariant, checked("@types assume-c0 .im").analysis.semantics?.outputType)
    }

    @Test
    fun typedWordsRejectUnsafeLaneAccessAndUnsupportedMixing() {
        val invalid = listOf(
            "assume-c1 real", "assume-c1 .im", "assume-c1 .z", "assume-c1 .w",
            "assume-c1 dup >x", "assume-c1 [0x]", "assume-c1 normalize",
            "assume-c1 2 /", "assume-c0 4 +", "assume-c1 uv +",
            "assume-c1 dup uv mix", "assume-c2147483647 dup *", "assume-c-2147483648 conj",
            "drop sample", ": pattern ; p uv uv uv 'pattern phacelle.4"
        )
        for (program in invalid) {
            val result = compileStackSL("@types\n$program")
            assertFalse(result.ok, program)
            assertFalse(requireNotNull(result.analysis.semantics).equivarianceCertified, program)
        }
        val located = compileStackSL("@types\nassume-c1 .z")
        val diagnostic = located.diagnostics.first()
        assertEquals(".z", diagnostic.token)
        assertEquals(StackSLSourceLocation(2, 11, 17), diagnostic.location)
        assertTrue("ComplexSpin(1)" in diagnostic.message && "as-raw" in diagnostic.message)
    }

    @Test
    fun certificationFollowsLiveValuesAndAccountsForWrites() {
        val pure = checked("@types input assume-c2 drop uv noise drop assume-c1")
        val analysis = requireNotNull(pure.analysis.semantics)
        assertEquals(listOf(StackSLInputAssumption("input.xy", ComplexSpin(1))), analysis.assumptions)
        assertTrue(analysis.equivarianceCertified)

        for (program in listOf("uv ->0 assume-c1", "uv ->?color assume-c1", "?host noise", "drop time")) {
            assertFalse(requireNotNull(checked("@types $program").analysis.semantics).equivarianceCertified, program)
        }
        val centeredConflict = checked("@types p assume-c1 swap assume-c1 +")
        assertFalse(requireNotNull(centeredConflict.analysis.semantics).equivarianceCertified)
    }

    @Test
    fun adjustedWiresRemainRealInvariantsAndNeverActAsLiteralZero() {
        val options = StackSLCompileOptions(externalizeConstants = true)
        assertFalse(compileStackSL("@types assume-c1 0 +", options).ok)
        val source = "@types : scale 0 * ; assume-c1 scale"
        val result = checked(source, options)
        val analysis = requireNotNull(result.analysis.semantics)
        assertTrue(analysis.equivarianceCertified)
        assertEquals(StackSLInputAssumption("stacksl_wires[0]", RealInvariant), analysis.assumptions.last())
        assertEquals(ComplexSpin(1), analysis.outputType)
        assertEquals("multiply b", result.analysis.danglingWires.single().consumedAs)
        val wired = StackSLWires.lift(source)
        val invocation = parseGlslShader(wired.glsl).newInvocation(wired.globals(doubleArrayOf(2.0)))
        val value = invocation.call(wired.prefix, listOf(GlslValue.vec4(2f, 3f, 4f, 5f)))
        assertContentEquals(floatArrayOf(4f, 6f, 0f, 0f), value.c)
    }

    @Test
    fun optionAndNamedEntrypointUseTheSameTypedLowering() {
        val viaMode = checked("assume-c1 dup *", StackSLCompileOptions(semanticTypes = true))
        val viaDirective = checked("@types : main assume-c1 dup * ;", StackSLCompileOptions(useEntrypointAsRoot = true))
        assertEquals(viaMode.analysis.semantics, viaDirective.analysis.semantics)
        assertEquals(viaMode.glsl, viaDirective.glsl)
    }

    private fun checked(source: String, options: StackSLCompileOptions = StackSLCompileOptions()): StackSLCompileResult =
        compileStackSL(source, options).also { assertTrue(it.ok, it.diagnostics.joinToString("\n")) }

    private fun evaluate(result: StackSLCompileResult): FloatArray =
        parseGlslShader(result.glsl).newInvocation().call("stacksl", listOf(GlslValue.vec4(2f, 3f, 4f, 5f))).c
}
