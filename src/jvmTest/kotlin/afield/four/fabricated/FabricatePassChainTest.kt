package afield.four.fabricated

import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a pass chain has to get right before a GPU is involved at all.
 *
 * Every claim in here is one that a *rendered picture cannot check*. A pyramid rung planned one
 * halving short, an aperture pass that quietly ran at full resolution, a composite sampling the wrong
 * intermediate, a `sourceTex` bound to a texture written later in the same chain — each of those
 * produces an image that is soft, or sharp, or bright, in a way nobody looking at it can distinguish
 * from the effect working. That is the failure mode this file exists for, and it is why
 * [FabricatePassChain.plan] was written as a pure function over data rather than as a side effect of
 * allocating Vulkan objects.
 *
 * The one thing here that is *not* obvious arithmetic is the slot packing. Its correctness condition
 * is subtle and worth stating: a slot written by pass `j` may be handed to pass `i > j` only when
 * `lastConsumer[j] < i`. That is simultaneously "pass j's output is dead" and "pass i does not read
 * pass j", because a pass that read pass j would have pushed `lastConsumer[j]` to at least its own
 * index. One condition, two guarantees — so [slotSharingNeverAliasesALiveInput] checks the second
 * directly rather than trusting the first.
 */
class FabricatePassChainTest {

    private val srgb = VK_FORMAT_B8G8R8A8_SRGB
    private val half = VK_FORMAT_R16G16B16A16_SFLOAT

    private fun spec(
        name: String,
        size: FabricatePassSize = FabricatePassSize.MatchSource,
        format: Int? = null,
        source: String? = null,
        extra: List<FabricatePassInput> = emptyList()
    ) = FabricatePassSpec(name, "// $name", size = size, format = format, source = source, extraInputs = extra)

    // --- sizes ---

    @Test
    fun defaultSizeMatchesTheSourceAndAChainOfThemStaysFullResolution() {
        val plan = FabricatePassChain.plan(listOf(spec("a"), spec("b"), spec("c")), 800, 600, srgb)
        plan.passes.forEach { pass ->
            assertEquals(800, pass.shape.width, "${pass.name} width")
            assertEquals(600, pass.shape.height, "${pass.name} height")
            assertEquals(srgb, pass.shape.format, "${pass.name} format")
        }
    }

    @Test
    fun fractionOfSourceCompoundsIntoAPyramid() {
        val plan = FabricatePassChain.plan(
            FabricatePassLibrary.downsamplePyramid(levels = 4),
            1024, 1024, srgb
        )
        // The whole reason for the layer: each rung is half the one before, not half the input.
        assertEquals(listOf(512, 256, 128, 64), plan.passes.map { it.shape.width })
        assertEquals(listOf(512, 256, 128, 64), plan.passes.map { it.shape.height })
    }

    @Test
    fun fractionOfChainInputIsAnAbsoluteRungWhateverPrecedesIt() {
        val plan = FabricatePassChain.plan(
            listOf(
                spec("down", FabricatePassSize.FractionOfSource(1, 8)),
                spec("back", FabricatePassSize.FractionOfChainInput(1, 2)),
                spec("full", FabricatePassSize.MatchChainInput)
            ),
            1024, 512, srgb
        )
        assertEquals(128 to 64, plan.passes[0].shape.width to plan.passes[0].shape.height)
        assertEquals(512 to 256, plan.passes[1].shape.width to plan.passes[1].shape.height)
        assertEquals(1024 to 512, plan.passes[2].shape.width to plan.passes[2].shape.height)
    }

    @Test
    fun fractionsFloorAndClampAtOneTexelSoALongPyramidBottomsOutRatherThanVanishing() {
        // 1023 halved is 511, not 511.5 rounded up: a rung must never claim texels its source lacks.
        val odd = FabricatePassChain.plan(FabricatePassLibrary.downsamplePyramid(levels = 2), 1023, 7, srgb)
        assertEquals(listOf(511, 255), odd.passes.map { it.shape.width })
        assertEquals(listOf(3, 1), odd.passes.map { it.shape.height })

        // And a pyramid deeper than the image is legal, bottoming out at 1x1 rather than 0x0 — a
        // zero-sized attachment is a Vulkan error a long way from the line that asked for it.
        val deep = FabricatePassChain.plan(FabricatePassLibrary.downsamplePyramid(levels = 12), 64, 64, srgb)
        assertEquals(1, deep.passes.last().shape.width)
        assertEquals(1, deep.passes.last().shape.height)
        assertTrue(deep.passes.all { it.shape.width >= 1 && it.shape.height >= 1 })
    }

    // --- formats ---

    @Test
    fun formatIsInheritedFromTheSourceUnlessTheSpecAsksAndThenItPropagates() {
        val plan = FabricatePassChain.plan(
            listOf(spec("keep"), spec("lift", format = half), spec("inherit-the-lift")),
            256, 256, srgb
        )
        assertEquals(srgb, plan.passes[0].shape.format)
        assertEquals(half, plan.passes[1].shape.format)
        // Inheritance follows the *source*, so a half-float rung makes everything downstream of it
        // half-float too until something says otherwise. That is the property that lets a bloom
        // pyramid be declared by setting one format on the bright pass.
        assertEquals(half, plan.passes[2].shape.format)
    }

    @Test
    fun aHalfFloatShapeCostsTwiceAnEightBitOneOfTheSameSize() {
        assertEquals(256L * 256L * 4L, FabricatePassShape(256, 256, srgb).bytes)
        assertEquals(256L * 256L * 8L, FabricatePassShape(256, 256, half).bytes)
    }

    @Test
    fun mipLevelsAreCountedInTheShapeCost() {
        val flat = FabricatePassShape(64, 64, srgb, mipLevels = 1).bytes
        val mipped = FabricatePassShape(64, 64, srgb, mipLevels = 7).bytes
        assertTrue(mipped > flat, "a mip chain costs more than one level")
        // 64x64 + 32x32 + ... + 1x1 = 5461 texels, against 4096 for level 0 alone.
        assertEquals(5461L * 4L, mipped)
    }

    // --- input wiring ---

    @Test
    fun theDefaultSourceIsThePreviousPassAndTheFirstPassReadsTheChainInput() {
        val plan = FabricatePassChain.plan(listOf(spec("a"), spec("b")), 64, 64, srgb)
        assertEquals(FabricatePassRef.ChainInput, plan.passes[0].inputs.single().from)
        assertEquals(FabricatePassRef.Pass(0, "a"), plan.passes[1].inputs.single().from)
    }

    @Test
    fun anExplicitSourceForksTheChainAndNamedInputsRejoinIt() {
        val plan = FabricatePassChain.plan(
            listOf(
                spec("blur", FabricatePassSize.Half),
                spec("join", source = FabricatePassChain.CHAIN_INPUT, extra = listOf(FabricatePassInput("blurTex", "blur")))
            ),
            512, 512, srgb
        )
        val join = plan.passes[1]
        // Sized from its *primary* input, which is the full-resolution chain input, while still
        // sampling the half-resolution blur. That combination is the composite pass, and it is the
        // one the old linear-only stack could not express at all.
        assertEquals(512, join.shape.width)
        assertEquals(FabricatePassRef.ChainInput, join.inputs[0].from)
        assertEquals(FabricatePassChain.PRIMARY_SAMPLER, join.inputs[0].samplerName)
        assertEquals("blurTex", join.inputs[1].samplerName)
        assertEquals(FabricatePassRef.Pass(0, "blur"), join.inputs[1].from)
    }

    @Test
    fun aChainMayOnlyReadBackwards() {
        // Forward reference: the target does not exist yet when the reader runs.
        val forward = assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(listOf(spec("a", source = "b"), spec("b")), 64, 64, srgb)
        }
        assertTrue("only read backwards" in forward.message!!, forward.message!!)

        // Self reference: read-and-write of one attachment in one pass.
        val self = assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(listOf(spec("a", source = "a")), 64, 64, srgb)
        }
        assertTrue("only read backwards" in self.message!!, self.message!!)
    }

    @Test
    fun badChainsAreRejectedWithTheNameInTheMessage() {
        assertFailsWith<IllegalArgumentException> { FabricatePassChain.plan(emptyList(), 64, 64, srgb) }
        assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(listOf(spec("a"), spec("a")), 64, 64, srgb)
        }
        assertFailsWith<IllegalArgumentException> { FabricatePassChain.plan(listOf(spec(" ")), 64, 64, srgb) }
        assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(listOf(spec(FabricatePassChain.CHAIN_INPUT)), 64, 64, srgb)
        }
        val missing = assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(listOf(spec("a"), spec("b", source = "nope")), 64, 64, srgb)
        }
        assertTrue("nope" in missing.message!! && "'b'" in missing.message!!, missing.message!!)
        assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(
                listOf(spec("a"), spec("b", extra = listOf(FabricatePassInput("t", "a"), FabricatePassInput("t", "a")))),
                64, 64, srgb
            )
        }
    }

    // --- lifetimes and slot sharing ---

    @Test
    fun aLinearChainRetiresEachPassAsSoonAsTheNextHasRunAndTheOutputNeverDies() {
        val plan = FabricatePassChain.plan(listOf(spec("a"), spec("b"), spec("c")), 64, 64, srgb)
        assertEquals(1, plan.passes[0].lastConsumer)
        assertEquals(2, plan.passes[1].lastConsumer)
        // Strictly past the end: the chain output is read by whoever presents it.
        assertTrue(plan.passes[2].lastConsumer >= plan.passCount, "the chain output must outlive the chain")
    }

    @Test
    fun aPassHeldForALaterCompositeStaysLiveThroughEverythingBetween() {
        val plan = FabricatePassChain.plan(
            listOf(
                spec("bright"),
                spec("blur-a"),
                spec("blur-b"),
                spec("join", source = "bright", extra = listOf(FabricatePassInput("blurTex", "blur-b")))
            ),
            64, 64, srgb
        )
        assertEquals(3, plan["bright"]!!.lastConsumer, "bright is held until the join reads it")
        assertEquals(2, plan["blur-a"]!!.lastConsumer)
    }

    @Test
    fun slotsAreSharedOnlyBetweenPassesOfTheSameShape() {
        val plan = FabricatePassChain.plan(
            listOf(
                spec("full-a"),
                spec("small", FabricatePassSize.Half),
                spec("full-b", size = FabricatePassSize.MatchChainInput),
                spec("full-c", size = FabricatePassSize.MatchChainInput)
            ),
            256, 256, srgb
        )
        val shapes = plan.slots.map { it.shape }
        plan.slots.forEach { slot ->
            slot.passIndices.forEach { index ->
                assertEquals(slot.shape, plan.passes[index].shape, "pass ${plan.passes[index].name} on a wrong-shape slot")
            }
        }
        assertTrue(shapes.contains(FabricatePassShape(128, 128, srgb)), "the half-resolution pass needs its own slot")
        assertTrue(plan.targetCount < plan.passCount, "four same-shape-ish passes should share something")
    }

    /**
     * The packing invariant, checked directly rather than via the lifetime it is derived from.
     *
     * For every pass, no sampler it reads may be bound to a pass that has *already been overwritten*
     * — i.e. whose slot was handed to some pass between it and this reader. If this ever fails, the
     * chain renders from a texture containing a different pass's output, which on a real GPU looks
     * like a plausible but wrong image and on a validator looks like nothing at all.
     */
    @Test
    fun slotSharingNeverAliasesALiveInput() {
        val chains = listOf(
            FabricatePassLibrary.apertureChain(),
            FabricatePassLibrary.bloomPyramid(levels = 4),
            FabricatePassLibrary.downsamplePyramid(levels = 5) + FabricatePassLibrary.separableBlur(source = "reduce-5"),
            FabricatePassLibrary.bloomPyramid(levels = 2) + FabricatePassLibrary.apertureChain()
        )
        chains.forEachIndexed { chainIndex, passes ->
            val plan = FabricatePassChain.plan(passes, 1024, 1024, srgb)
            plan.passes.forEach { reader ->
                reader.inputs.forEach { input ->
                    val ref = input.from
                    if (ref is FabricatePassRef.Pass) {
                        val producer = plan.passes[ref.index]
                        // Anybody who took the producer's slot strictly between producing and reading
                        // has destroyed the very texture this sampler is bound to.
                        val clobberers = plan.passesOn(producer.slot).filter {
                            it.index > producer.index && it.index <= reader.index
                        }
                        assertTrue(
                            clobberers.isEmpty(),
                            "chain $chainIndex: '${reader.name}' reads '${producer.name}' " +
                                    "but slot ${producer.slot} was retaken by ${clobberers.map { it.name }}"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun everyPassGetsExactlyOneSlotAndEverySlotIsUsed() {
        val plan = FabricatePassChain.plan(FabricatePassLibrary.bloomPyramid(levels = 4), 1024, 1024, srgb)
        assertEquals(plan.passCount, plan.slots.sumOf { it.passIndices.size }, "passes must partition the slots")
        assertTrue(plan.slots.all { it.passIndices.isNotEmpty() }, "an empty slot was allocated")
        plan.passes.forEach { pass ->
            assertTrue(pass.index in plan.slots[pass.slot].passIndices, "${pass.name} is not on the slot it claims")
        }
    }

    // --- cost, with floors under the numbers ---

    @Test
    fun theApertureChainCostsAFractionOfWhatTheOldApiWouldHaveAllocated() {
        val plan = FabricatePassChain.plan(FabricatePassLibrary.apertureChain(), 1024, 1024, srgb)

        // The declared shape of the hand-wired chain: reduce 1/2, reduce 1/4, two apertures on the
        // cheap 1/4 image, and a full-resolution composite.
        assertEquals(listOf("reduce-1", "reduce-2", "aperture-near", "aperture-far", "composite"), plan.passes.map { it.name })
        assertEquals(512, plan["reduce-1"]!!.shape.width)
        assertEquals(256, plan["reduce-2"]!!.shape.width)
        assertEquals(256, plan["aperture-near"]!!.shape.width, "the aperture must run on the reduced image, not full res")
        assertEquals(256, plan["aperture-far"]!!.shape.width)
        assertEquals(1024, plan["composite"]!!.shape.width, "the composite must rejoin at full resolution")

        // The counterfactual is what addShaderEffect allocates for the same five passes today: five
        // full-resolution 8-bit targets, 4 MiB each.
        assertEquals(5L * 1024 * 1024 * 4, plan.naiveBytes)

        // Exact totals rather than a slack ratio, because the interesting failure is a shape drifting
        // by one halving and a ratio floor set an order of magnitude low would absorb it silently.
        //   1.00 MiB (512²) + 0.25 (256²) + 0.25 + 0.25 + 4.00 (1024²)
        val mib = 1024L * 1024L
        assertEquals(mib + 3L * (mib / 4L) + 4L * mib, plan.unsharedBytes, "unshared: 5.75 MiB")
        // Slot sharing hands aperture-far the target reduce-2 has finished with: 4 targets, not 5.
        assertEquals(4, plan.targetCount, "five passes should plan onto four targets")
        assertEquals(mib + (mib / 4L) + (mib / 4L) + 4L * mib, plan.plannedBytes, "planned: 5.50 MiB")

        // Floors stated next to the measurement, not an order of magnitude under it.
        val naiveOverUnshared = plan.naiveBytes.toDouble() / plan.unsharedBytes.toDouble()
        val naiveOverPlanned = plan.naiveBytes.toDouble() / plan.plannedBytes.toDouble()
        assertTrue(naiveOverUnshared > 3.4, "multi-resolution shapes alone should beat 3.4x, got $naiveOverUnshared")
        assertTrue(naiveOverPlanned > 3.6, "with slot sharing it should beat 3.6x, got $naiveOverPlanned")
        assertTrue(plan.plannedBytes < plan.unsharedBytes, "slot sharing must actually share something")
    }

    @Test
    fun aBloomPyramidIsWiredUpTheWayItsNameClaims() {
        val plan = FabricatePassChain.plan(FabricatePassLibrary.bloomPyramid(levels = 4), 1024, 1024, srgb)

        // Down the pyramid...
        assertEquals(listOf(512, 256, 128, 64), (1..4).map { plan["bloom-down-$it"]!!.shape.width })
        // ...and back up it, each rung added onto the one above.
        assertEquals(128, plan["bloom-up-1"]!!.shape.width)
        assertEquals(256, plan["bloom-up-2"]!!.shape.width)
        assertEquals(512, plan["bloom-up-3"]!!.shape.width)
        assertEquals(FabricatePassRef.Pass(plan["bloom-down-4"]!!.index, "bloom-down-4"), plan["bloom-up-1"]!!.inputs[1].from)
        assertEquals(FabricatePassRef.Pass(plan["bloom-up-1"]!!.index, "bloom-up-1"), plan["bloom-up-2"]!!.inputs[1].from)

        // Every rung half-float, because a bright pass into an 8-bit target clips the highlights it
        // exists to isolate — a per-pass format decision, and therefore also new.
        listOf("bloom-bright", "bloom-down-1", "bloom-down-4", "bloom-up-3").forEach { name ->
            assertEquals(half, plan[name]!!.shape.format, "$name should be half-float")
        }
        // The composite rejoins in the chain's own 8-bit format at full resolution.
        assertEquals(srgb, plan["bloom-composite"]!!.shape.format)
        assertEquals(1024, plan["bloom-composite"]!!.shape.width)
        assertSame(plan.output, plan["bloom-composite"], "the composite is the chain output")
    }

    @Test
    fun theReportNamesEveryPassAndStatesTheSaving() {
        val plan = FabricatePassChain.plan(FabricatePassLibrary.apertureChain(), 1024, 1024, srgb)
        val report = plan.report()
        plan.passes.forEach { assertTrue(it.name in report, "${it.name} missing from the report") }
        assertTrue("256x256" in report, "the reduced resolution should be visible in the report")
        assertTrue("slot" in report && "memory" in report && "saving" in report, report)
    }

    @Test
    fun aMoreExpensiveChainIsPlannedAsMoreExpensive() {
        // Guards against the saving being an artefact of one hand-picked chain: a chain that keeps
        // everything at full resolution must plan to (nearly) its naive cost, and a deep pyramid must
        // plan to far less. If the ratio were computed wrongly these two would not separate.
        val flat = FabricatePassChain.plan(List(5) { spec("p$it") }, 1024, 1024, srgb)
        val deep = FabricatePassChain.plan(FabricatePassLibrary.downsamplePyramid(levels = 5), 1024, 1024, srgb)
        assertTrue(flat.unsharedBytes == flat.naiveBytes, "an all-full-res chain has nothing to save")
        assertTrue(deep.unsharedBytes * 4 < deep.naiveBytes, "a five-rung pyramid should save over 4x on shapes alone")
        assertNotEquals(flat.plannedBytes, deep.plannedBytes)
    }
}
