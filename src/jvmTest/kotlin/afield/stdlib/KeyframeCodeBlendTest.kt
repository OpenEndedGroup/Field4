package afield.stdlib

import afield.four.buffers.Structure
import afield.stacksl.cpu.CpuRaster
import afield.stacksl.cpu.GlslValue
import afield.stacksl.cpu.StackSLCpu
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector4f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for `keyframeCodeBlend.kt` — the structural blend between two *code* keyframes.
 *
 * ## The claim each group of tests holds down
 *
 * The feature makes one strong claim and one weak one, and they need different kinds of test. The
 * weak claim is about the alignment: two sources that differ only in numbers produce a site per
 * number and no divergence, and two that differ in anything else produce a divergence and blend by
 * stepping. That is checked directly, by counting.
 *
 * The strong claim is about the rewrite: a [CodeBlendMode.RUNTIME] program with the blend parameter
 * at 0 must *be* the left keyframe, and at 1 must *be* the right one. Counting cannot see that, and
 * neither can reading the generated text, so [theRuntimeRewriteIsTheEndpointsAtBothEnds] renders all
 * four programs through the CPU StackSL interpreter and compares rasters. That test is the reason
 * `?pc.keyframeBlend .x mix` can be trusted as a substitution for a literal: it is checked against
 * the interpreter rather than against an argument about stack depth.
 *
 * ## Why there is no GPU here
 *
 * `afield.stacksl.cpu.StackSLCpu` compiles the same StackSL through the same
 * `StackSLCompiler.compile` the live `stack.auto` setter uses, then interprets the resulting GLSL,
 * so a program can be *run* — including its push constants — with no device, window or renderer. The
 * push-constant convention is exercised for real here: [BLEND_TEMPLATE] declares
 * `vec4 keyframeBlend` in a block called `pc`, exactly as the convention asks a shader to, and the
 * uniform is supplied under the name `pc.keyframeBlend`.
 *
 * The one part that cannot run headlessly is a Kotlin [CodeBlendMode.RUNTIME] blend actually
 * evaluating: a `SourceBackedBlock` compiles its source inside a live box through K2. The generated
 * text is asserted here, and `dev_docs/agents/transcripts/keyframe_code_blend.yaml` compiles and
 * runs it in a real Field.
 */
class KeyframeCodeBlendTest {

    private companion object {
        /**
         * A fragment shader with the blend push constant the convention describes, and a
         * `stack.auto` slot to put a program in.
         *
         * `pc.keyframeBlend` is a real member of a real push-constant block: the GLSL the blender
         * emits reads it by that name, and [StackSLCpu.renderFragment] supplies it by that name too,
         * so nothing here is stubbed out.
         */
        val BLEND_TEMPLATE = """
            #version 450
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;

            layout(push_constant) uniform Controls {
                vec4 keyframeBlend;
            } pc;

            {{ stack.auto.program }}

            void main() {
                vec4 stackInput = vec4(fragUV, 0.0, 0.0);
                outColor = vec4(clamp(program(stackInput).rgb, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        const val DIM = 24

        val RUNTIME = CodeBlendOptions(mode = CodeBlendMode.RUNTIME)
        val STATIC = CodeBlendOptions(mode = CodeBlendMode.STATIC)

        /** Render [program] at [blend] on the blend push constant. */
        fun render(program: String, blend: Double): CpuRaster {
            val v = keyframeBlendVector(blend)
            return StackSLCpu.renderFragment(
                StackSLCpu.expandStackAuto(BLEND_TEMPLATE, program),
                DIM,
                DIM,
                uniforms = mapOf("pc.keyframeBlend" to GlslValue.vec4(v.x, v.y, v.z, v.w)),
                parallel = false
            )
        }

        /** The largest per-channel difference between two rasters of the same size. */
        fun maxDifference(a: CpuRaster, b: CpuRaster): Float {
            assertEquals(a.rgba.size, b.rgba.size, "rasters must be the same size to compare")
            var worst = 0f
            for (i in a.rgba.indices) worst = maxOf(worst, abs(a.rgba[i] - b.rgba[i]))
            return worst
        }
    }

    // ---- StackSL: the alignment ------------------------------------------------------------

    @Test
    fun oneChangedNumberIsOneMovingSite() {
        val plan = planStackSLCodeBlend("uv 4.0 * fbm", "uv 9.0 * fbm", STATIC)

        assertEquals(1, plan.sites.size, "one number pair was aligned")
        assertEquals(1, plan.movingSites.size)
        assertTrue(plan.divergences.isEmpty(), "the two programs differ only in that number")
        assertEquals(CodeBlendSiteKind.NUMBER, plan.movingSites[0].kind)
        assertEquals(listOf(4.0), plan.movingSites[0].left)
        assertEquals(listOf(9.0), plan.movingSites[0].right)
        assertEquals(CodeBlendMode.STATIC, plan.effectiveMode)
    }

    @Test
    fun anUnchangedNumberIsAPairedSiteButNotAMovingOne() {
        val plan = planStackSLCodeBlend("uv 4.0 * 0.5 +", "uv 9.0 * 0.5 +", STATIC)

        assertEquals(2, plan.sites.size, "both numbers aligned")
        assertEquals(1, plan.movingSites.size, "only one of them differs")
        // The fixed one is copied through untouched rather than re-formatted.
        assertTrue("0.5 +" in plan.bake(0.5), plan.bake(0.5))
    }

    @Test
    fun aStaticBakeIsTheEndpointsAtTheEndsAndTheMidpointBetween() {
        val plan = planStackSLCodeBlend("uv 4.0 * fbm", "uv 9.0 * fbm", STATIC)

        assertEquals("uv 4.0 * fbm", plan.bake(0.0))
        assertEquals("uv 6.5 * fbm", plan.bake(0.5))
        assertEquals("uv 9.0 * fbm", plan.bake(1.0))
        // Not clamped, for the same reason the numeric lanes are not.
        assertEquals("uv 14.0 * fbm", plan.bake(2.0))
    }

    @Test
    fun commentsAndLayoutSurviveABake() {
        val left = """
            # the warp
            uv 4.0 *   (a note) fbm
        """.trimIndent()
        val right = """
            # the warp
            uv 9.0 *   (a note) fbm
        """.trimIndent()

        val baked = planStackSLCodeBlend(left, right, STATIC).bake(0.5)

        assertEquals(
            """
            # the warp
            uv 6.5 *   (a note) fbm
            """.trimIndent(),
            baked,
            "everything outside a site is copied through byte for byte"
        )
    }

    @Test
    fun aVectorLiteralBlendsComponentWise() {
        val plan = planStackSLCodeBlend("[0.2,0.4,0.6] input *", "[0.8,0.4,0.0] input *", STATIC)

        assertEquals(1, plan.movingSites.size)
        assertEquals(CodeBlendSiteKind.VECTOR, plan.movingSites[0].kind)
        assertEquals("[0.5,0.4,0.3] input *", plan.bake(0.5))
    }

    @Test
    fun aStructuralDifferenceIsADivergenceAndStepsInstead() {
        val plan = planStackSLCodeBlend("uv 4.0 * fbm", "uv 4.0 * noise", RUNTIME)

        assertTrue(plan.divergences.isNotEmpty(), "fbm and noise do not align")
        assertEquals(1, plan.divergences.size, "one substitution, reported once")
        assertEquals("fbm", plan.divergences[0].left)
        assertEquals("noise", plan.divergences[0].right)
        assertTrue(!plan.blendable)
        assertEquals(CodeBlendMode.STEP, plan.effectiveMode, "a divergent pair steps whatever was asked")
        assertTrue(plan.downgraded)
        assertTrue("structurally" in plan.downgradeReason, plan.downgradeReason)
    }

    @Test
    fun anExtraWordOnOneSideIsReportedWithTheSideItIsMissingFrom() {
        val plan = planStackSLCodeBlend("uv fbm", "uv fbm saturate", RUNTIME)

        assertEquals(1, plan.divergences.size)
        assertEquals("", plan.divergences[0].left)
        assertEquals("saturate", plan.divergences[0].right)
        assertTrue("only the right source" in plan.divergences[0].toString())
    }

    @Test
    fun aStackSelectorsIndicesAreAddressesAndAreNeverBlended() {
        // `[0x,1y]` reads channel x of stack slot 0 and y of slot 1. Interpolating those digits
        // would silently retarget the selector, so the token has to stay opaque.
        val plan = planStackSLCodeBlend("uv dup [0x,1y]", "uv dup [1x,1y]", STATIC)

        assertTrue(plan.sites.none { it.leftText.startsWith("[0x") })
        assertTrue(plan.divergences.isNotEmpty(), "a changed selector is a structural change")
    }

    @Test
    fun anArityVariantsSuffixIsNotANumberToBlend() {
        // `fbm3.2` is the three-dimensional fbm at arity 2, not the number 3.2.
        val plan = planStackSLCodeBlend("uv fbm3.2", "uv fbm4.2", STATIC)

        assertTrue(plan.sites.isEmpty(), "no numeric site inside a word")
        assertEquals(1, plan.divergences.size)
    }

    // ---- StackSL: what a runtime blend can and cannot reach ---------------------------------

    @Test
    fun aModifierChainCanOnlyBeBlendedByRewritingTheSource() {
        val plan = planStackSLCodeBlend("uv 0.5s2 *", "uv 0.9s2 *", RUNTIME)

        assertEquals(1, plan.movingSites.size)
        assertEquals(CodeBlendSiteKind.MODIFIER, plan.movingSites[0].kind)
        assertTrue(!plan.movingSites[0].runtimeCapable)
        assertTrue(!plan.runtimeCapable)
        assertEquals(CodeBlendMode.STATIC, plan.effectiveMode, "RUNTIME falls back rather than pretending")
        assertTrue(plan.downgraded)
        // The frequency `2` is unchanged, so it is written back as `2` rather than as `2.0`.
        assertEquals("uv 0.7s2 *", plan.bake(0.5))
    }

    @Test
    fun aModifierChainsFrequencyAndPhaseBlendToo() {
        val plan = planStackSLCodeBlend("uv 0.5s2p0.0 *", "uv 0.5s4p1.0 *", STATIC)

        assertEquals(1, plan.movingSites.size)
        assertEquals(listOf(0.5, 2.0, 0.0), plan.movingSites[0].left)
        assertEquals(listOf(0.5, 4.0, 1.0), plan.movingSites[0].right)
        assertEquals("uv 0.5s3.0p0.5 *", plan.bake(0.5))
    }

    @Test
    fun aRandomRangeIsLiteralOnlyAsWell() {
        val plan = planStackSLCodeBlend("uv 0.2_0.4 *", "uv 0.6_0.8 *", RUNTIME)

        assertEquals(CodeBlendSiteKind.RANGE, plan.movingSites.single().kind)
        assertEquals(CodeBlendMode.STATIC, plan.effectiveMode)
        assertEquals("uv 0.4_0.6 *", plan.bake(0.5))
    }

    @Test
    fun aRuntimeRewriteReadsThePushConstantAndCompiles() {
        val plan = planStackSLCodeBlend("uv 4.0 * fbm", "uv 9.0 * fbm", RUNTIME)

        assertEquals(CodeBlendMode.RUNTIME, plan.effectiveMode)
        assertEquals("uv 4.0 9.0 ?pc.keyframeBlend .x mix * fbm", plan.runtimeSource)

        val compiled = StackSLCpu.compile(plan.runtimeSource)
        assertTrue(compiled.ok, compiled.diagnostics.joinToString("\n"))
        assertTrue("pc.keyframeBlend" in compiled.glsl, "the external read reaches the GLSL verbatim")
    }

    @Test
    fun theLaneChoosesWhichComponentTheProgramReads() {
        val smooth = planStackSLCodeBlend(
            "uv 4.0 *", "uv 9.0 *",
            CodeBlendOptions(mode = CodeBlendMode.RUNTIME, lane = CodeBlendLane.SMOOTH)
        )
        assertTrue(".z mix" in smooth.runtimeSource, smooth.runtimeSource)

        val renamed = planStackSLCodeBlend(
            "uv 4.0 *", "uv 9.0 *",
            CodeBlendOptions(mode = CodeBlendMode.RUNTIME, stackSLRead = "controls.morph")
        )
        assertTrue("?controls.morph .x mix" in renamed.runtimeSource, renamed.runtimeSource)
    }

    @Test
    fun aRuntimeProgramDoesNotDependOnT() {
        val scope = KeyframeScope()
        val slot = "look"
        var source = "uv 4.0 * fbm"
        scope.target(slot, get = { StackSLString(source) }, set = { source = it.toString() })
        scope.codeBlend = RUNTIME

        val a = scope.Key(mutableMapOf(slot to StackSLString("uv 4.0 * fbm")))
        val b = scope.Key(mutableMapOf(slot to StackSLString("uv 9.0 * fbm")))

        val atQuarter = scope.interpolate(a, b, 0.25)[slot].toString()
        val atThreeQuarters = scope.interpolate(a, b, 0.75)[slot].toString()

        assertEquals(atQuarter, atThreeQuarters, "one program for every t is the whole point")
        assertTrue("mix" in atQuarter, atQuarter)
    }

    /**
     * The endpoint test: rendering the runtime program with the blend at 0 must give the same
     * picture as rendering the left keyframe, and at 1 the same as the right.
     *
     * This is what makes `a b ?blend .x mix` trustworthy as a substitution for a literal. The
     * midpoint is checked as well, but only for being *between* the two — an interpolation of a
     * program is not required to look like anything in particular, only to move.
     */
    @Test
    fun theRuntimeRewriteIsTheEndpointsAtBothEnds() {
        val left = "uv 2.0 * fbm 0.2 +"
        val right = "uv 7.0 * fbm 0.8 +"
        val plan = planStackSLCodeBlend(left, right, RUNTIME)
        assertEquals(CodeBlendMode.RUNTIME, plan.effectiveMode)
        assertEquals(2, plan.movingSites.size)

        val leftRaster = render(left, 0.0)
        val rightRaster = render(right, 0.0)
        val blended = plan.runtimeSource

        assertTrue(
            maxDifference(render(blended, 0.0), leftRaster) < 1e-5f,
            "at blend 0 the runtime program is the left keyframe"
        )
        assertTrue(
            maxDifference(render(blended, 1.0), rightRaster) < 1e-5f,
            "at blend 1 the runtime program is the right keyframe"
        )

        // And it really moves: the midpoint is neither endpoint, and the endpoints are not each other.
        assertTrue(maxDifference(leftRaster, rightRaster) > 0.05f, "the two keyframes look different")
        val middle = render(blended, 0.5)
        assertTrue(maxDifference(middle, leftRaster) > 1e-3f, "the midpoint is not the left keyframe")
        assertTrue(maxDifference(middle, rightRaster) > 1e-3f, "the midpoint is not the right keyframe")
    }

    /**
     * The static bake has to agree with the runtime rewrite: both are the same interpolation, one
     * spent at compile time and one at draw time, so at any given `t` they must render the same
     * picture.
     */
    @Test
    fun theStaticBakeAndTheRuntimeRewriteAgree() {
        val left = "uv 2.0 * fbm 0.2 +"
        val right = "uv 7.0 * fbm 0.8 +"
        val runtime = planStackSLCodeBlend(left, right, RUNTIME).runtimeSource
        val baked = planStackSLCodeBlend(left, right, STATIC).bake(0.35)

        assertTrue(
            maxDifference(render(runtime, 0.35), render(baked, 0.0)) < 1e-5f,
            "the two modes are the same blend, differently paid for"
        )
    }

    // ---- The scope's side: modes, the blend lane and the plan cache -------------------------

    @Test
    fun aScopeStepsCodeUntilItIsToldNotTo() {
        val scope = KeyframeScope()
        val a = scope.Key(mutableMapOf("look" to StackSLString("uv 4.0 * fbm")))
        val b = scope.Key(mutableMapOf("look" to StackSLString("uv 9.0 * fbm")))

        assertEquals(CodeBlendMode.STEP, scope.codeBlend.mode, "stepping is still the default")
        assertEquals("uv 4.0 * fbm", scope.interpolate(a, b, 0.5)["look"].toString())
        assertEquals("uv 9.0 * fbm", scope.interpolate(a, b, 1.0)["look"].toString())
    }

    @Test
    fun aStaticScopeBlendProducesADifferentProgramPerT() {
        val scope = KeyframeScope()
        scope.codeBlend = STATIC
        val a = scope.Key(mutableMapOf("look" to StackSLString("uv 4.0 * fbm")))
        val b = scope.Key(mutableMapOf("look" to StackSLString("uv 9.0 * fbm")))

        assertEquals("uv 6.5 * fbm", scope.interpolate(a, b, 0.5)["look"].toString())
        assertEquals("uv 5.25 * fbm", scope.interpolate(a, b, 0.25)["look"].toString())
        assertTrue(scope.interpolate(a, b, 0.5)["look"] is StackSLString, "still code, not a string")
    }

    @Test
    fun theBlendLaneIsWrittenWhenAndOnlyWhenSomethingWatchesIt() {
        val structure = Structure()
        val scope = KeyframeScope()
        scope.codeBlend = RUNTIME
        val a = scope.Key(mutableMapOf("look" to StackSLString("uv 4.0 *")))
        val b = scope.Key(mutableMapOf("look" to StackSLString("uv 9.0 *")))

        assertNull(scope.interpolate(a, b, 0.5)[keyframeBlendKey], "no target, no lane")

        scope.target(keyframeBlendKey, structure)
        val lane = scope.interpolate(a, b, 0.25)[keyframeBlendKey]
        assertTrue(lane is Vector4f, "the lane is the blend vec4")
        assertEquals(0.25f, lane.x)
        assertEquals(0.25f, lane.y)
        assertEquals(0.75f, lane.w)
    }

    @Test
    fun theBlendLaneIsNotWrittenWhileTheModeIsStep() {
        val scope = KeyframeScope()
        scope.target(keyframeBlendKey, Structure())
        val a = scope.Key(mutableMapOf("look" to StackSLString("uv 4.0 *")))
        val b = scope.Key(mutableMapOf("look" to StackSLString("uv 9.0 *")))

        assertNull(scope.interpolate(a, b, 0.5)[keyframeBlendKey])
    }

    @Test
    fun theBlendLaneOverridesTheLerpOfTwoCapturedVec4s() {
        // Both keys carry the lane, because it is a registered target and so `capture()` reads it.
        // Left to the ordinary vec4 path it would lerp between two stale blend vectors.
        val scope = KeyframeScope()
        scope.codeBlend = RUNTIME
        scope.target(keyframeBlendKey, Structure())
        val a = scope.Key(mutableMapOf(keyframeBlendKey to Vector4f(9f, 9f, 9f, 9f)))
        val b = scope.Key(mutableMapOf(keyframeBlendKey to Vector4f(-9f, -9f, -9f, -9f)))

        assertEquals(0.25f, (scope.interpolate(a, b, 0.25)[keyframeBlendKey] as Vector4f).x)
    }

    @Test
    fun theBlendLaneCanBeRetimedLikeAnyOtherLane() {
        val scope = KeyframeScope()
        scope.codeBlend = RUNTIME
        scope.target(keyframeBlendKey, Structure())
        scope.remap[keyframeBlendKey] = { t -> t * t }
        val a = scope.Key(mutableMapOf("look" to StackSLString("uv 4.0 *")))
        val b = scope.Key(mutableMapOf("look" to StackSLString("uv 9.0 *")))

        assertEquals(0.25f, (scope.interpolate(a, b, 0.5)[keyframeBlendKey] as Vector4f).x)
    }

    @Test
    fun plansAreCachedPerPairOfSourcesAndDroppedWhenTheOptionsChange() {
        val scope = KeyframeScope()
        scope.codeBlend = RUNTIME
        val a = scope.Key(mutableMapOf("look" to StackSLString("uv 4.0 *")))
        val b = scope.Key(mutableMapOf("look" to StackSLString("uv 9.0 *")))

        val first = scope.codeBlendPlans(a, b).getValue("look")
        assertSame(first, scope.codeBlendPlans(a, b).getValue("look"), "the same pair, the same plan")

        scope.codeBlend = STATIC
        val second = scope.codeBlendPlans(a, b).getValue("look")
        assertTrue(first !== second, "new options, new plan")
        assertEquals(CodeBlendMode.STATIC, second.options.mode)
    }

    @Test
    fun aSlotThatIsCodeOnOneSideOnlyHasNoPlan() {
        val scope = KeyframeScope()
        scope.codeBlend = STATIC
        val a = scope.Key(mutableMapOf("look" to StackSLString("uv 4.0 *"), "gain" to 0.5f))
        val b = scope.Key(mutableMapOf("look" to StackSLString("uv 9.0 *"), "gain" to 1.5f))

        assertEquals(setOf("look"), scope.codeBlendPlans(a, b).keys)
    }

    @Test
    fun twoBlocksFromDifferentBoxesStepRatherThanBlend() {
        val scope = KeyframeScope()
        scope.codeBlend = STATIC
        val a = scope.Key(mutableMapOf("fn" to SourceBackedBlock<Any?>("boxA", "0.5f")))
        val b = scope.Key(mutableMapOf("fn" to SourceBackedBlock<Any?>("boxB", "1.5f")))

        assertTrue(scope.codeBlendPlans(a, b).isEmpty(), "no box to compile the blend in")
        val stepped = scope.interpolate(a, b, 0.5)["fn"] as SourceBackedBlock<*>
        assertEquals("0.5f", stepped.source)
        assertEquals("boxA", stepped.boxId)
    }

    @Test
    fun aRuntimeKotlinBlendReusesOneBlockAndPublishesT() {
        val scope = KeyframeScope()
        scope.codeBlend = RUNTIME
        val a = scope.Key(mutableMapOf("fn" to SourceBackedBlock<Any?>("box1", "0.5f * 2f")))
        val b = scope.Key(mutableMapOf("fn" to SourceBackedBlock<Any?>("box1", "1.5f * 2f")))

        val first = scope.interpolate(a, b, 0.25)["fn"] as SourceBackedBlock<*>
        val second = scope.interpolate(a, b, 0.75)["fn"] as SourceBackedBlock<*>
        assertSame(first, second, "one block, compiled once")
        assertEquals("box1", first.boxId)

        val plan = scope.codeBlendPlans(a, b).getValue("fn")
        assertEquals(0.75, keyframeBlendAmount(plan.runtimeId), "the last t interpolated is the live one")
    }

    @Test
    fun aStaticKotlinBlendBakesTheSourceIntoAFreshBlock() {
        val scope = KeyframeScope()
        scope.codeBlend = STATIC
        val a = scope.Key(mutableMapOf("fn" to SourceBackedBlock<Any?>("box1", "0.5f * 2f")))
        val b = scope.Key(mutableMapOf("fn" to SourceBackedBlock<Any?>("box1", "1.5f * 2f")))

        val blended = scope.interpolate(a, b, 0.5)["fn"] as SourceBackedBlock<*>
        assertEquals("1.0f * 2f", blended.source)
    }

    // ---- Kotlin: the lexer's two pieces of knowledge ----------------------------------------

    @Test
    fun aKotlinLiteralKeepsItsSuffixThroughABake() {
        val plan = planKotlinCodeBlend("radius * 0.5f + 2.0", "radius * 1.5f + 3.0", STATIC)

        assertEquals(2, plan.movingSites.size)
        assertEquals("radius * 1.0f + 2.5", plan.bake(0.5))
    }

    @Test
    fun anIntegerLiteralBlendsToAnInteger() {
        // `2.0` would not compile where an Int is wanted, so an integer literal rounds.
        assertEquals("steps = 2", planKotlinCodeBlend("steps = 1", "steps = 3", STATIC).bake(0.5))
        assertEquals("n = 2L", planKotlinCodeBlend("n = 1L", "n = 3L", STATIC).bake(0.5))
        // Half way between 1 and 4 is 2.5, and rounding is away from zero, so it is 3 and not 2.
        assertEquals("steps = 3", planKotlinCodeBlend("steps = 1", "steps = 4", STATIC).bake(0.5))
        assertEquals("steps = 2", planKotlinCodeBlend("steps = 1", "steps = 4", STATIC).bake(0.25))
    }

    /**
     * An integer literal and a decimal one do not pair, even though both are numbers.
     *
     * A blend rebuilds from the *left* source, so pairing `1` with `2.0` would write the answer back
     * as `1` — an `Int` literal where a `Double` was written, which can change which overload the
     * surrounding expression resolves to. Reporting it as a divergence steps the slot instead.
     */
    @Test
    fun anIntegerLiteralDoesNotPairWithADecimalOne() {
        val plan = planKotlinCodeBlend("steps = 1", "steps = 2.0", STATIC)

        assertTrue(plan.sites.isEmpty())
        assertEquals(1, plan.divergences.size)
        assertEquals(CodeBlendMode.STEP, plan.effectiveMode)

        // Two decimals, or two integers, pair as usual.
        assertEquals(1, planKotlinCodeBlend("steps = 1.0", "steps = 2.0", STATIC).sites.size)
        assertEquals(1, planKotlinCodeBlend("steps = 1", "steps = 2", STATIC).sites.size)
        // A float suffix makes a literal decimal whether or not it has a point.
        assertEquals(1, planKotlinCodeBlend("gain = 1f", "gain = 2.5f", STATIC).sites.size)
    }

    @Test
    fun aPrefixSignBelongsToTheNumberAfterIt() {
        val plan = planKotlinCodeBlend("vec(-1f, 0f)", "vec(1f, 0f)", STATIC)

        assertTrue(plan.blendable, plan.divergences.joinToString("\n"))
        assertEquals(1, plan.movingSites.size)
        assertEquals(CodeBlendSiteKind.VECTOR, plan.movingSites[0].kind)
        assertEquals(listOf(-1.0, 0.0), plan.movingSites[0].left)
        assertEquals("vec(0.0f, 0f)", plan.bake(0.5))
    }

    @Test
    fun aSubtractionIsNotAPrefixSign() {
        // `a - 1f` against `a - 3f` is a blend of `1f` and `3f`, with the operator left alone.
        val plan = planKotlinCodeBlend("a - 1f", "a - 3f", STATIC)

        assertTrue(plan.blendable)
        assertEquals(listOf(1.0), plan.movingSites.single().left)
        assertEquals("a - 2.0f", plan.bake(0.5))
    }

    @Test
    fun aVectorConstructorIsOneSiteRatherThanThree() {
        val plan = planKotlinCodeBlend(
            "Vector3f(0f, 0f, 0f)", "Vector3f(1f, 2f, 3f)", STATIC
        )

        assertEquals(1, plan.sites.size)
        assertEquals(CodeBlendSiteKind.VECTOR, plan.sites[0].kind)
        assertEquals(3, plan.sites[0].left.size)
        assertEquals("Vector3f(0.5f, 1.0f, 1.5f)", plan.bake(0.5))
    }

    /**
     * A quaternion has to slerp, and this checks the value against an independent oracle rather than
     * against a second call: the half-way point between identity and a quarter turn is an eighth
     * turn, and its `w` is `cos(pi/8)` whatever the implementation does.
     */
    @Test
    fun aQuaternionBlendsAlongTheShorterArcRatherThanComponentWise() {
        val quarter = sqrt(0.5).toFloat()
        val plan = planKotlinCodeBlend(
            "Quaternionf(0f, 0f, 0f, 1f)",
            "Quaternionf(0f, 0f, ${quarter}f, ${quarter}f)",
            STATIC
        )

        assertEquals(1, plan.sites.size)
        assertEquals(CodeBlendSiteKind.QUATERNION, plan.sites[0].kind)
        assertEquals(CodeBlendInterpolation.SLERP, plan.sites[0].kind.interpolation)

        val mid = plan.sites[0].blend(0.5)
        val length = sqrt(mid.sumOf { it * it })
        assertTrue(abs(length - 1.0) < 1e-5, "a slerped quaternion stays a unit quaternion; was $length")

        val angle = 2.0 * acos(mid[3].coerceIn(-1.0, 1.0))
        assertTrue(abs(angle - PI / 4) < 1e-4, "half of a quarter turn is an eighth turn; was $angle rad")

        // A component-wise lerp of the same two would not be a unit quaternion, which is the point.
        val naive = (0..3).map { (plan.sites[0].left[it] + plan.sites[0].right[it]) / 2.0 }
        assertTrue(abs(sqrt(naive.sumOf { it * it }) - 1.0) > 1e-3, "the naive answer really is wrong")
    }

    @Test
    fun aDualQuaternionBlendsAsAScrewMotion() {
        val plan = planKotlinCodeBlend(
            "DualQuaternionf(Quaternionf(0f, 0f, 0f, 1f), Quaternionf(0f, 0f, 0f, 0f))",
            "DualQuaternionf(Quaternionf(0f, 0f, 0f, 1f), Quaternionf(1f, 0f, 0f, 0f))",
            STATIC
        )

        assertEquals(1, plan.sites.size)
        assertEquals(CodeBlendSiteKind.DUAL_QUATERNION, plan.sites[0].kind)
        assertEquals(8, plan.sites[0].left.size)
        // Pure translation: the real half is identity throughout and the dual half is half way.
        val mid = plan.sites[0].blend(0.5)
        assertTrue(abs(mid[3] - 1.0) < 1e-5, "the rotation half stays identity; was ${mid[3]}")
        assertTrue(abs(mid[4] - 0.5) < 1e-5, "the translation half is half way; was ${mid[4]}")
    }

    @Test
    fun aStringLiteralIsOpaqueAndAChangedOneIsADivergence() {
        val plan = planKotlinCodeBlend("""label("a", 1f)""", """label("b", 2f)""", STATIC)

        assertEquals(1, plan.divergences.size)
        assertEquals("\"a\"", plan.divergences[0].left)
        assertEquals(CodeBlendMode.STEP, plan.effectiveMode)
    }

    @Test
    fun hexAndUnsignedLiteralsAreNotBlended() {
        assertTrue(planKotlinCodeBlend("mask = 0x0F", "mask = 0x1F", STATIC).sites.isEmpty())
        assertTrue(planKotlinCodeBlend("n = 1u", "n = 3u", STATIC).sites.isEmpty())
        // ...and a changed one is a divergence rather than a silent no-op.
        assertEquals(1, planKotlinCodeBlend("mask = 0x0F", "mask = 0x1F", STATIC).divergences.size)
    }

    @Test
    fun commentsAreTriviaAndDoNotBreakAnAlignment() {
        val plan = planKotlinCodeBlend(
            "/* the /* nested */ gain */ gain * 0.5f // and a tail",
            "/* the /* nested */ gain */ gain * 1.5f // and a different tail",
            STATIC
        )

        assertTrue(plan.blendable, plan.divergences.joinToString("\n"))
        assertEquals(1, plan.movingSites.size)
        assertTrue(plan.bake(0.5).startsWith("/* the /* nested */ gain */ gain * 1.0"), plan.bake(0.5))
    }

    @Test
    fun aRuntimeKotlinRewriteCallsKfMixAgainstTheRegistry() {
        val plan = planKotlinCodeBlend("gain * 0.5f", "gain * 1.5f", RUNTIME)

        assertEquals(CodeBlendMode.RUNTIME, plan.effectiveMode)
        assertEquals(
            "gain * afield.stdlib.kfMix(0.5f, 1.5f, afield.stdlib.keyframeBlendAmount(${plan.runtimeId}, 0))",
            plan.runtimeSource
        )
        // Fully qualified, so the generated text resolves in a box that imported nothing.
        assertTrue("afield.stdlib." in plan.runtimeSource)
    }

    @Test
    fun aRuntimeKotlinRewritePassesWholeConstructorsToKfMix() {
        val plan = planKotlinCodeBlend(
            "Quaternionf(0f, 0f, 0f, 1f)", "Quaternionf(0f, 0f, 1f, 0f)", RUNTIME
        )

        assertTrue(
            plan.runtimeSource.startsWith(
                "afield.stdlib.kfMix(Quaternionf(0f, 0f, 0f, 1f), Quaternionf(0f, 0f, 1f, 0f), "
            ),
            plan.runtimeSource
        )
    }

    // ---- The parameter itself ----------------------------------------------------------------

    @Test
    fun theBlendVectorIsTheSameTInFourShapes() {
        val v = keyframeBlendVector(0.25)
        assertEquals(0.25f, v.x)
        assertEquals(0.25f, v.y)
        assertEquals(0.15625f, v.z, "smoothstep(0, 1, 0.25)")
        assertEquals(0.75f, v.w)

        val past = keyframeBlendVector(1.5)
        assertEquals(1.5f, past.x, "x is unclamped")
        assertEquals(1.0f, past.y)
        assertEquals(1.0f, past.z)
        assertEquals(0.0f, past.w)
    }

    @Test
    fun theRegistryReadsWhatDriveWroteAndZeroOtherwise() {
        val plan = planKotlinCodeBlend("0.5f", "1.5f", RUNTIME)

        assertEquals(0.0, keyframeBlendAmount(plan.runtimeId), "a fresh slot reads 0")
        plan.drive(0.75)
        assertEquals(0.75, keyframeBlendAmount(plan.runtimeId, CodeBlendLane.RAW.component))
        assertEquals(0.75, keyframeBlendAmount(plan.runtimeId, CodeBlendLane.CLAMPED.component))
        assertEquals(0.0, keyframeBlendAmount(-1), "an unknown slot reads 0 rather than throwing")
        assertEquals(0.0, keyframeBlendAmount(plan.runtimeId, 9), "so does an unknown component")
    }

    @Test
    fun aStackSLPlanIgnoresDrive() {
        // The StackSL parameter travels as a push constant; the registry is the Kotlin half only.
        val plan = planStackSLCodeBlend("uv 4.0 *", "uv 9.0 *", RUNTIME)
        plan.drive(0.5)
        assertEquals(0.0, keyframeBlendAmount(plan.runtimeId))
    }

    @Test
    fun theMixHelpersKeepTheirArgumentTypes() {
        assertEquals(1.0f, kfMix(0.5f, 1.5f, 0.5), "a Float blend stays a Float")
        assertEquals(1.0, kfMix(0.5, 1.5, 0.5))
        assertEquals(2, kfMix(1, 3, 0.5))
        assertEquals(2L, kfMix(1L, 3L, 0.5))
        assertEquals(Vector4f(0.5f, 0.5f, 0.5f, 0.5f), kfMix(Vector4f(0f, 0f, 0f, 0f), Vector4f(1f, 1f, 1f, 1f), 0.5))

        val quarter = sqrt(0.5).toFloat()
        val slerped = kfMix(Quaternionf(0f, 0f, 0f, 1f), Quaternionf(0f, 0f, quarter, quarter), 0.5)
        val length = sqrt((slerped.x * slerped.x + slerped.y * slerped.y +
            slerped.z * slerped.z + slerped.w * slerped.w).toDouble())
        assertTrue(abs(length - 1.0) < 1e-5, "kfMix on quaternions slerps; length was $length")
    }

    // ---- The breakdown -----------------------------------------------------------------------

    @Test
    fun theBreakdownNamesEverySiteAndEveryDivergence() {
        val blended = planStackSLCodeBlend("uv 4.0 * fbm", "uv 9.0 * fbm", RUNTIME)
        val text = blended.describe()
        assertTrue("1 moving site(s) of 1" in text, text)
        assertTrue("RUNTIME -> RUNTIME" in text, text)
        assertTrue("number: 4.0 -> 9.0" in text, text)

        val divergent = planStackSLCodeBlend("uv fbm", "uv noise", RUNTIME)
        assertTrue("divergence:" in divergent.describe(), divergent.describe())
        assertTrue("downgraded:" in divergent.describe(), divergent.describe())
    }

    @Test
    fun theBreakdownRendersAsHtmlWithTheMidpointAndTheProgram() {
        val html = planStackSLCodeBlend("uv 4.0 * fbm", "uv 9.0 * fbm", RUNTIME).toHTML()

        assertTrue("RUNTIME" in html, html)
        assertTrue("6.5" in html, "the midpoint of the one moving site is shown")
        assertTrue("runtime program" in html)
        assertTrue("?pc.keyframeBlend" in html)
        // A divergence's text goes through the escaper, so a program with angle brackets is safe.
        val escaped = planStackSLCodeBlend("uv <", "uv >", RUNTIME).toHTML()
        assertTrue("&lt;" in escaped, escaped)
        assertTrue("`<` vs" !in escaped, "the raw character must not reach the page")
    }

    @Test
    fun aPlanWithNothingToBlendSaysSoRatherThanRewriting() {
        val plan = planStackSLCodeBlend("uv 4.0 * fbm", "uv 4.0 * fbm", RUNTIME)

        assertTrue(plan.blendable)
        assertTrue(plan.movingSites.isEmpty())
        assertEquals("uv 4.0 * fbm", plan.runtimeSource, "no sites, no rewrite")
        assertEquals("uv 4.0 * fbm", plan.bake(0.5))
        assertTrue("nothing to blend" in plan.toHTML())
    }

    @Test
    fun aPlanReportsItselfInOneLine() {
        val plan = planStackSLCodeBlend("uv 4.0 *", "uv 9.0 *", RUNTIME)
        assertEquals("CodeBlendPlan(stacksl, RUNTIME->RUNTIME, 1/1 moving, 0 divergent)", plan.toString())
        assertNotNull(plan.leftSource)
        assertEquals("uv 9.0 *", plan.rightSource)
        assertEquals(CodeBlendLanguage.STACKSL, plan.language)
    }
}
