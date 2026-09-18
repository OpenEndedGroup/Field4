package afield.four.fabricated

import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT
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

    // --- re-declaring a chain ---

    @Test
    fun replanningTheSameDeclarationIsIdenticalSoATargetCanBeReused() {
        // The CPU half of `addPassChain` being idempotent. `addShaderEffect` decides whether to reuse
        // a pass's existing target by comparing its width, height, format and mip count against the
        // freshly planned shape, so that decision is only sound if planning the same declaration
        // twice gives the same shapes. Asserting it here means the GPU-side reuse rule rests on
        // something checked rather than assumed.
        val declaration = FabricatePassLibrary.stableFluid(resolution = 128, iterations = 4)
        val first = FabricatePassChain.plan(declaration, 512, 512, srgb)
        val second = FabricatePassChain.plan(declaration, 512, 512, srgb)
        assertEquals(first.passes.map { it.name }, second.passes.map { it.name })
        assertEquals(first.passes.map { it.shape }, second.passes.map { it.shape })
        assertEquals(first.plannedBytes, second.plannedBytes)
    }

    @Test
    fun changingTheResolutionChangesEveryShapeSoNoTargetCanBeReused() {
        // The other half of the rule, and the one worth stating: a shape that differs is what makes
        // the builder allocate rather than carry over, which for a persistent pass is exactly the
        // "you get a new, empty fluid" behaviour. If the shapes did *not* differ, editing the
        // resolution would silently do nothing.
        val small = FabricatePassChain.plan(FabricatePassLibrary.stableFluid(resolution = 128, iterations = 4), 512, 512, srgb)
        val large = FabricatePassChain.plan(FabricatePassLibrary.stableFluid(resolution = 256, iterations = 4), 512, 512, srgb)
        assertEquals(small.passes.map { it.name }, large.passes.map { it.name }, "the names are what get replaced")
        small.passes.zip(large.passes).forEach { (a, b) ->
            assertNotEquals(a.shape, b.shape, "${a.name} kept its shape across a resolution change")
        }
    }

    // --- persistent passes: the one reference that may point forwards ---

    private fun state(name: String, resolution: Int = 64) = FabricatePassSpec(
        name,
        "// $name",
        size = FabricatePassSize.Absolute(resolution, resolution),
        format = half,
        persistent = true
    )

    @Test
    fun aPreviousFrameReferenceMayNameAPassThatHasNotRunYet() {
        // The whole point. `reader` runs first and reads `field`, which runs second — legal only
        // because it reads last frame's copy, which already exists when this frame starts.
        val plan = FabricatePassChain.plan(
            listOf(
                spec("reader", size = FabricatePassSize.Absolute(64, 64), format = half, source = "field")
                    .copy(sourcePreviousFrame = true),
                state("field")
            ),
            256, 256, srgb
        )
        assertEquals(
            FabricatePassRef.PreviousFrame(1, "field"),
            plan.passes[0].inputs.single().from
        )
        // And it resolves to the persistent pass's shape, not to anything the walk had computed yet.
        assertEquals(64, plan.passes[0].shape.width)
    }

    @Test
    fun aPersistentPassMayReadItsOwnPreviousFrame() {
        val plan = FabricatePassChain.plan(
            listOf(state("field").copy(source = "field", sourcePreviousFrame = true)),
            256, 256, srgb
        )
        assertEquals(FabricatePassRef.PreviousFrame(0, "field"), plan.passes[0].inputs.single().from)
    }

    @Test
    fun aPreviousFrameReferenceIsOnlyLegalAgainstAPersistentPass() {
        // Without this the reference would silently bind *this* frame's target — which is the one
        // being written — and the read would be a race rather than an error.
        val failure = assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(
                listOf(
                    spec("plain"),
                    spec("reader", source = "plain").copy(sourcePreviousFrame = true)
                ),
                256, 256, srgb
            )
        }
        assertTrue("not persistent" in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun aPersistentPassMustStateASizeAndFormatThatDoNotDependOnItsSource() {
        // MatchSource cannot be resolved for a pass whose output is read before its source is known.
        val badSize = assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(
                listOf(FabricatePassSpec("field", "//", format = half, persistent = true)),
                256, 256, srgb
            )
        }
        assertTrue("depends on its source" in (badSize.message ?: ""), badSize.message ?: "")

        val badFormat = assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(
                listOf(
                    FabricatePassSpec(
                        "field", "//",
                        size = FabricatePassSize.Absolute(64, 64),
                        persistent = true
                    )
                ),
                256, 256, srgb
            )
        }
        assertTrue("format" in (badFormat.message ?: ""), badFormat.message ?: "")
    }

    @Test
    fun aPersistentTargetIsNeverSharedAndCostsTwoImages() {
        // Three passes of identical shape. The two plain ones may share; the persistent one may not
        // take a slot and may not offer its own, because its contents have to survive to next frame.
        val shape = FabricatePassSize.Absolute(64, 64)
        val plan = FabricatePassChain.plan(
            listOf(
                spec("a", size = shape, format = half),
                state("field"),
                spec("b", size = shape, format = half, source = "a"),
                spec("c", size = shape, format = half, source = "b")
            ),
            256, 256, srgb
        )
        val field = plan["field"]!!
        assertTrue(field.persistent)
        assertEquals(listOf(field.index), plan.slots[field.slot].passIndices.toList(), "a persistent slot is exclusive")
        assertTrue(plan.slots[field.slot].persistent)
        assertEquals(2, plan.slots[field.slot].imageCount)
        assertEquals(field.shape.bytes * 2, plan.slots[field.slot].bytes)
        // No plain pass took it.
        plan.passes.filter { !it.persistent }.forEach { assertNotEquals(field.slot, it.slot, it.name) }
        assertEquals(listOf("field"), plan.stateNamesForTest())
    }

    @Test
    fun aPersistentPassOutlivesTheWholeChainEvenWhenNothingThisFrameReadsIt() {
        val plan = FabricatePassChain.plan(
            listOf(state("field"), spec("visible", size = FabricatePassSize.Absolute(64, 64), format = half)),
            256, 256, srgb
        )
        assertEquals(plan.passCount, plan["field"]!!.lastConsumer, "a persistent pass is read by next frame")
        assertTrue("persistent" in plan.report(), plan.report())
        assertTrue("field@prev" !in plan.report(), "nothing read it this frame")
    }

    // --- the fluid ---

    @Test
    fun theFluidChainIsWiredUpTheWayItsNameClaims() {
        val fluid = FabricatePassLibrary.stableFluid(resolution = 128, iterations = 4)
        val plan = FabricatePassChain.plan(fluid, 512, 512, srgb)

        // Exactly two state fields, and they are the two the docs name.
        assertEquals(listOf("fluid-velocity", "fluid-dye"), plan.persistentPasses.map { it.name })

        // Advection starts from last frame's velocity — the forward reference.
        val advect = plan["fluid-advect"]!!
        assertEquals(0, advect.index, "advection is the first pass of the frame")
        assertEquals(
            FabricatePassRef.PreviousFrame(plan["fluid-velocity"]!!.index, "fluid-velocity"),
            advect.inputs.single().from
        )
        assertTrue(advect.index < plan["fluid-velocity"]!!.index, "and it reads a pass that runs after it")

        // The Jacobi ladder: iterate k reads iterate k-1 and the one fixed divergence, and nothing
        // else. Getting this wrong — reading the divergence of the wrong pass, or iterate k-2 —
        // converges to something, just not to the pressure.
        (1..4).forEach { k ->
            val iterate = plan["fluid-pressure-$k"]!!
            assertEquals("sourceTex", iterate.inputs[0].samplerName)
            assertEquals(FabricatePassRef.Pass(plan["fluid-pressure-${k - 1}"]!!.index, "fluid-pressure-${k - 1}"), iterate.inputs[0].from)
            assertEquals("divergenceTex", iterate.inputs[1].samplerName)
            assertEquals(FabricatePassRef.Pass(plan["fluid-divergence"]!!.index, "fluid-divergence"), iterate.inputs[1].from)
            assertEquals(VK_FORMAT_R16_SFLOAT, iterate.shape.format, "the pressure field is one channel")
        }

        // The dye reads its own previous frame *and* this frame's velocity: both kinds of reference
        // in one pass, which is the distinction the planner had to learn.
        val dye = plan["fluid-dye"]!!
        assertEquals(FabricatePassRef.PreviousFrame(dye.index, "fluid-dye"), dye.inputs[0].from)
        assertEquals(FabricatePassRef.Pass(plan["fluid-velocity"]!!.index, "fluid-velocity"), dye.inputs[1].from)

        // Projection subtracts the *last* iterate's gradient, not an earlier one.
        assertEquals(
            FabricatePassRef.Pass(plan["fluid-pressure-4"]!!.index, "fluid-pressure-4"),
            plan["fluid-velocity"]!!.inputs[1].from
        )

        // Every pass is on the simulation grid, not the chain input's resolution.
        plan.passes.forEach { assertEquals(128, it.shape.width, it.name) }
    }

    @Test
    fun droppingVorticityDropsTheCurlPassAndItsBinding() {
        // Not a style choice: the force shader compiles its curl sampler in or out, so a chain with
        // vorticity off must not declare a binding there is no pass to fill.
        val with = FabricatePassChain.plan(FabricatePassLibrary.stableFluid(resolution = 64, iterations = 2), 256, 256, srgb)
        val without = FabricatePassChain.plan(
            FabricatePassLibrary.stableFluid(resolution = 64, iterations = 2, vorticity = 0f), 256, 256, srgb
        )
        assertTrue(with["fluid-curl"] != null)
        assertEquals(null, without["fluid-curl"])
        assertEquals(2, with["fluid-force"]!!.inputs.size)
        assertEquals(1, without["fluid-force"]!!.inputs.size)
        assertEquals(with.passCount - 1, without.passCount)
    }

    @Test
    fun aFluidCostsTwoPingPongPairsAndTheRestIsScratch() {
        val plan = FabricatePassChain.plan(FabricatePassLibrary.stableFluid(resolution = 256, iterations = 20), 1024, 1024, srgb)
        val persistentSlots = plan.slots.filter { it.persistent }
        assertEquals(2, persistentSlots.size)
        // 256x256 RGBA16F is 512 KiB; two of them, doubled for the ping-pong, is 2 MiB of state.
        assertEquals(2L * 2 * 256 * 256 * 8, persistentSlots.sumOf { it.bytes })
        // And the whole thing is still far cheaper than a full-resolution target per pass, which is
        // what the old API would have allocated for a 27-pass chain at 1024².
        assertTrue(plan.unsharedBytes * 8 < plan.naiveBytes, "planned ${plan.unsharedBytes} vs naive ${plan.naiveBytes}")
    }

    // --- the jump flood ---

    @Test
    fun theJumpFloodStrideSequenceHalvesFromHalfTheResolutionDownToOne() {
        // The stride sequence is the whole algorithm. One rung short and the far corner of the image
        // never hears about a seed in the near one, which reads as a distance field that is correct
        // in the middle and wrong at the edges — not as a broken pass.
        val plan = FabricatePassChain.plan(
            FabricatePassLibrary.jumpFloodDistance(resolution = 256, plusOne = false), 512, 512, srgb
        )
        val strides = plan.passes.mapNotNull { it.spec.push["stride"] as? Int }
        assertEquals(listOf(128, 64, 32, 16, 8, 4, 2, 1), strides)
        assertEquals(listOf("jfa-seed", "jfa-distance"), listOf(plan.passes.first().name, plan.passes.last().name))
    }

    @Test
    fun jfaPlusOneRepeatsTheFinalStrideOnePass() {
        val plan = FabricatePassChain.plan(FabricatePassLibrary.jumpFloodDistance(resolution = 256), 512, 512, srgb)
        assertEquals(listOf(1, 1), plan.passes.mapNotNull { it.spec.push["stride"] as? Int }.takeLast(2))
        assertEquals(
            FabricatePassRef.Pass(plan["jfa-flood-plus1"]!!.index, "jfa-flood-plus1"),
            plan["jfa-distance"]!!.inputs[0].from
        )
    }

    @Test
    fun theDistancePassKeepsTheOriginalMaskBoundForTheSign() {
        // The flood knows where the nearest seed is, not which side of it a texel is on, so the sign
        // has to come from the mask. A signed chain that lost that binding would produce a field
        // that is everywhere positive — a plausible unsigned distance field.
        val plan = FabricatePassChain.plan(
            FabricatePassLibrary.jumpFloodDistance(resolution = 64, signed = true), 256, 256, srgb
        )
        val distance = plan["jfa-distance"]!!
        assertEquals("maskTex", distance.inputs[1].samplerName)
        assertEquals(FabricatePassRef.ChainInput, distance.inputs[1].from)
        assertEquals(1, distance.spec.push["signedField"])
        assertEquals(1, plan["jfa-seed"]!!.spec.push["boundary"])
    }

    @Test
    fun aJumpFloodRejectsAResolutionItCannotFloodOrCannotAddressExactly() {
        val notPowerOfTwo = assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.jumpFloodDistance(resolution = 300)
        }
        assertTrue("power of two" in (notPowerOfTwo.message ?: ""), notPowerOfTwo.message ?: "")

        // 4096 texel coordinates do not survive a half-float round trip, and the resulting field is
        // subtly wrong rather than obviously broken — so it is refused rather than approximated.
        val tooBigForHalf = assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.jumpFloodDistance(resolution = 4096)
        }
        assertTrue("half-float" in (tooBigForHalf.message ?: ""), tooBigForHalf.message ?: "")
        FabricatePassLibrary.jumpFloodDistance(resolution = 4096, format = VK_FORMAT_R32G32B32A32_SFLOAT)
    }

    // --- frame history ---

    @Test
    fun frameHistoryIsIndependentOfPersistenceInBothDirections() {
        // The claim the library's second consumer exists to make, on the planning side. Neither
        // declaration implies the other, and the planner must not quietly couple them: a pass that
        // took history and got a ping-pong pair it never reads would waste two images of a
        // full-resolution target, and a pass that is persistent without history is every simulation
        // the library already ships.
        val plan = FabricatePassChain.plan(
            listOf(
                spec("plain"),
                FabricatePassSpec("history-only", "// h", source = "plain", frameHistory = true),
                FabricatePassSpec(
                    "persistent-only",
                    "// p",
                    size = FabricatePassSize.MatchChainInput,
                    format = half,
                    source = "plain",
                    persistent = true
                ),
                FabricatePassSpec(
                    "both",
                    "// b",
                    size = FabricatePassSize.MatchChainInput,
                    format = half,
                    source = "plain",
                    extraInputs = listOf(FabricatePassInput("historyTex", "both", previousFrame = true)),
                    persistent = true,
                    frameHistory = true
                )
            ),
            256, 256, srgb
        )
        assertEquals(listOf("history-only", "both"), plan.historyPasses.map { it.name })
        assertEquals(listOf("persistent-only", "both"), plan.persistentPasses.map { it.name })
        // And a history-only pass costs one image, not two.
        assertEquals(1, plan.slots.single { it.passIndices.contains(1) }.imageCount)
        assertEquals(
            FabricatePassChain.PERSISTENT_IMAGE_COUNT,
            plan.slots.single { it.passIndices.contains(3) }.imageCount
        )
    }

    @Test
    fun aHistoryOnlyPassStillSharesATargetSlot() {
        // The other half of the independence claim, and the one with a cost attached. `persistent`
        // makes a slot unshareable because its contents have to survive to the next frame.
        // `frameHistory` gives a pass two push constants and nothing else, so it has to stay as
        // packable as any other pass — otherwise switching on a debug view would silently allocate a
        // full-resolution target that nothing can ever reuse.
        val plan = FabricatePassChain.plan(
            listOf(
                spec("a"),
                FabricatePassSpec("b", "// b", source = "a", frameHistory = true),
                FabricatePassSpec("c", "// c", source = "b", frameHistory = true)
            ),
            256, 256, srgb
        )
        assertEquals(3, plan.passCount)
        assertTrue(plan.targetCount < plan.passCount, "history-only passes did not share a slot")
        assertTrue(plan.slots.none { it.persistent }, "a history-only pass planned a persistent slot")
    }

    @Test
    fun theReportNamesTheHistoryPassesAndOnlyThose() {
        // The report is the audit a person reads *before* looking at a picture that will not
        // obviously be wrong, so "+history" against the wrong pass is a real defect.
        val report = FabricatePassChain.plan(
            listOf(spec("quiet"), FabricatePassSpec("loud", "// l", source = "quiet", frameHistory = true)),
            256, 256, srgb
        ).report()
        val lines = report.lines().filter { it.trimStart().startsWith("#") }
        assertEquals(2, lines.size, report)
        assertTrue(lines[0].contains("quiet") && !lines[0].contains("+history"), lines[0])
        assertTrue(lines[1].contains("loud") && lines[1].contains("+history"), lines[1])
    }

    @Test
    fun temporalAccumulateDeclaresBothHalvesAndReadsItsOwnPreviousFrame() {
        val specs = FabricatePassLibrary.temporalAccumulate(name = "taa", resolution = 320 to 240)
        val only = specs.single()
        assertTrue(only.persistent, "a TAA pass with no persistent target has nothing to accumulate into")
        assertTrue(only.frameHistory, "a TAA pass with no frame history reads the wrong texel once the camera moves")
        // Three extra inputs now, and which is which matters: the accumulation buffer is read as
        // LAST frame, while the motion vectors and depth are read as THIS frame. Getting that
        // backwards is a pass that reprojects by where things were going to be.
        assertEquals(
            listOf(
                FabricatePassInput("historyTex", "taa", previousFrame = true),
                FabricatePassInput("motionTex", FabricatePassChain.GBUFFER_MOTION),
                FabricatePassInput("depthTex", FabricatePassChain.GBUFFER_DEPTH)
            ),
            only.extraInputs
        )
        assertTrue(
            only.extraInputs.filter { it.previousFrame }.map { it.samplerName } == listOf("historyTex"),
            "only the accumulation buffer is a previous-frame read; the g-buffer's are this frame's"
        )
        // And the opt-out is a real opt-out rather than a flag that changes nothing: without it the
        // pass is back to the one-plane approximation and declares neither external texture.
        val approximate = FabricatePassLibrary
            .temporalAccumulate(name = "taa", resolution = 320 to 240, perPixelMotion = false)
            .single()
        assertEquals(
            listOf(FabricatePassInput("historyTex", "taa", previousFrame = true)),
            approximate.extraInputs
        )
        // And it plans, which is what would fail if a persistent pass's size were allowed to depend
        // on its source.
        val plan = FabricatePassChain.plan(specs, 640, 480, srgb)
        assertEquals(320, plan.passes.single().shape.width)
        assertEquals(240, plan.passes.single().shape.height)
        assertEquals(listOf("taa"), plan.historyPasses.map { it.name })
    }

    @Test
    fun cameraMotionBlurTakesHistoryWithoutAPersistentTarget() {
        val only = FabricatePassLibrary.cameraMotionBlur(name = "mb").single()
        assertTrue(only.frameHistory)
        assertTrue(!only.persistent, "camera motion blur takes every sample from the current image")
        assertTrue(only.extraInputs.isEmpty(), "it reads no previous frame at all")
        val tooMany = assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.cameraMotionBlur(taps = FabricatePassLibrary.MAX_MOTION_BLUR_TAPS + 1)
        }
        assertTrue(FabricatePassLibrary.MAX_MOTION_BLUR_TAPS.toString() in tooMany.message.orEmpty())
    }

    @Test
    fun theHistoryConsumersRejectTheValuesThatWouldSilentlyDoNothing() {
        // A blend of 1.0 never takes a new sample, so the accumulation buffer freezes at whatever it
        // first held — which is an image, and a plausible one, and permanent.
        assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.temporalAccumulate(blend = 1f, resolution = 64 to 64)
        }
        assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.temporalAccumulate(rejection = -0.1f, resolution = 64 to 64)
        }
        assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.temporalAccumulate(resolution = 0 to 64)
        }
        // A mode outside 0..3 falls through the shader's else and renders the beauty pass, which
        // looks exactly like a debug view somebody forgot to switch on.
        assertFailsWith<IllegalArgumentException> { FabricatePassLibrary.temporalDebug(mode = -1) }
        assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.temporalDebug(mode = FabricatePassLibrary.MOTION_DISPLAY_MAX_MODE + 1)
        }
        (0..FabricatePassLibrary.MOTION_DISPLAY_MAX_MODE).forEach { mode ->
            assertEquals(mode, FabricatePassLibrary.temporalDebug(mode = mode).single().push["mode"])
        }
    }

    @Test
    fun anExternalReferenceIsAHoleThePlanDeclaresRatherThanATextureItResolves() {
        // The planner has never seen a renderer, so it cannot resolve these to an image view. What
        // it must do is carry the request through intact, so the builder can bind it later.
        val specs = FabricatePassLibrary.temporalAccumulate(name = "taa", resolution = 64 to 64)
        val plan = FabricatePassChain.plan(specs, 64, 64, srgb)
        val byName = plan.passes.single().inputs.associate { it.samplerName to it.from }
        assertEquals(
            FabricatePassRef.External(FabricatePassChain.GBUFFER_MOTION),
            byName.getValue("motionTex")
        )
        assertEquals(
            FabricatePassRef.External(FabricatePassChain.GBUFFER_DEPTH),
            byName.getValue("depthTex")
        )
        // And it survives into the audit, so a chain that reads the g-buffer says so out loud.
        assertTrue(FabricatePassChain.GBUFFER_MOTION in plan.report())
    }

    @Test
    fun anExternalReferenceMayNotBeASourceAPreviousFrameOrAPassName() {
        // A source, because a pass inherits its size from its source and this one has no size here.
        val asSource = assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(
                listOf(
                    FabricatePassSpec(
                        name = "reads-motion",
                        fragment = "// none",
                        source = FabricatePassChain.GBUFFER_MOTION
                    )
                ),
                64, 64, srgb
            )
        }
        assertTrue(
            "extra input" in (asSource.message ?: ""),
            "the refusal should point at the remedy, was: ${asSource.message}"
        )

        // A previous frame, because the renderer overwrites it every frame and keeps no history —
        // and a motion vector already encodes the step from last frame, so asking is a mistake.
        val asHistory = assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(
                listOf(
                    FabricatePassSpec(
                        name = "reads-motion",
                        fragment = "// none",
                        extraInputs = listOf(
                            FabricatePassInput("m", FabricatePassChain.GBUFFER_MOTION, previousFrame = true)
                        )
                    )
                ),
                64, 64, srgb
            )
        }
        assertTrue("keeps no history" in (asHistory.message ?: ""), asHistory.message ?: "")

        // A pass name, because a pass answering to the sentinel would shadow the real texture.
        val asName = assertFailsWith<IllegalArgumentException> {
            FabricatePassChain.plan(
                listOf(FabricatePassSpec(name = FabricatePassChain.GBUFFER_MOTION, fragment = "// none")),
                64, 64, srgb
            )
        }
        assertTrue("reserved" in (asName.message ?: ""), asName.message ?: "")
    }

    @Test
    fun everyHistoryShaderDeclaresTheTwoConstantsTheBuilderBinds() {
        // The contract between FabricatePassChainBuilder and these shaders is by NAME, and a name
        // the shader does not declare is skipped rather than throwing — which is the right tolerance
        // and also means a typo produces a pass that renders with an identity reprojection and no
        // error anywhere. This is the only place that can catch it.
        val shaders = mapOf(
            "temporalAccumulate" to FabricatePassLibrary.temporalAccumulateFragment(),
            "temporalDebug" to FabricatePassLibrary.temporalDebugFragment(),
            "cameraMotionBlur" to FabricatePassLibrary.cameraMotionBlurFragment(9)
        )
        shaders.forEach { (name, source) ->
            assertTrue("mat4 reprojection;" in source, "$name does not declare `mat4 reprojection`")
            assertTrue("vec4 historyParams;" in source, "$name does not declare `vec4 historyParams`")
            // And the shared helper, so no shader can quietly reimplement the Y flip.
            assertTrue("fieldHistoryUv" in source, "$name does not use fieldHistoryUv")
            val block = source.substringAfter("push_constant").substringBefore("} push;")
            assertTrue(
                block.indexOf("reprojection") < block.indexOf("historyParams"),
                "$name orders the two history constants unexpectedly"
            )
        }
        // The Y flip is carried exactly once, as the same expression the g-buffer writes its motion
        // vectors with. A sign difference is an image that is stable on a pan and smears on a tilt.
        assertTrue("vec2(0.5, -0.5)" in FabricatePassLibrary.FRAME_HISTORY_GLSL)
        // Each helper is DEFINED exactly once. The pattern is anchored on the open paren on
        // purpose: `vec2 fieldHistoryUv` is a prefix of `vec2 fieldHistoryUvPerPixel`, so the
        // unanchored form counted the per-pixel helper as a second definition of the one-plane one
        // the day it was added, and reported 2 where 1 was meant.
        assertEquals(
            1,
            Regex(Regex.escape("vec2 fieldHistoryUv(")).findAll(FabricatePassLibrary.FRAME_HISTORY_GLSL).count(),
            "the one-plane helper is defined more than once"
        )
        assertEquals(
            1,
            Regex(Regex.escape("vec2 fieldHistoryUvPerPixel(")).findAll(FabricatePassLibrary.FRAME_HISTORY_GLSL).count(),
            "the per-pixel helper is defined more than once"
        )
        // Which shader CALLS which. The test is on the call and not on the name, because
        // FRAME_HISTORY_GLSL defines both helpers into every shader that includes it — so
        // `"fieldHistoryUvPerPixel" in source` is true of every fragment ever built, and asserting
        // it proves nothing in either direction. An unused GLSL function is free; the call is the
        // behaviour.
        val perPixelCall = "fieldHistoryUvPerPixel(motionTex"
        assertTrue(
            perPixelCall in FabricatePassLibrary.temporalAccumulateFragment(),
            "TAA must read per-pixel motion by default — one matrix cannot describe per-object motion"
        )
        assertTrue(
            perPixelCall in FabricatePassLibrary.temporalDebugFragment(),
            "the debug view must show the field the accumulating pass actually uses"
        )
        assertTrue(
            perPixelCall !in FabricatePassLibrary.temporalAccumulateFragment(perPixelMotion = false),
            "the opt-out must really fall back to the one-plane form"
        )
        assertTrue(
            perPixelCall !in FabricatePassLibrary.cameraMotionBlurFragment(9),
            "camera motion blur is camera-only by definition; per-pixel motion would make its name wrong"
        )
        // The samplers the per-pixel path binds are declared only when it is on.
        assertTrue("sampler2D motionTex;" in FabricatePassLibrary.temporalAccumulateFragment())
        assertTrue("sampler2D depthTex;" in FabricatePassLibrary.temporalAccumulateFragment())
        assertTrue("sampler2D motionTex;" !in FabricatePassLibrary.temporalAccumulateFragment(perPixelMotion = false))
    }

    // --- summarised quadtrees ---

    @Test
    fun aSummaryPyramidIsACompleteQuadtreeOnEveryPlaneAtOnce() {
        // Three planes, each a full ladder from the leaf level to the root, and the levels are
        // exact halvings. "Exact" is the claim worth asserting: a 2x2 reduction of a level with an
        // odd side drops a row, which does not fail and does not look wrong — it biases every
        // centroid above it towards the origin by a fraction of a cell.
        val specs = FabricatePassLibrary.summaryPyramid(resolution = 64)
        val plan = FabricatePassChain.plan(specs, 256, 256, srgb)
        val leaf = 6
        assertEquals(FabricatePassLibrary.SUMMARY_PLANES.size * (leaf + 1), plan.passCount)
        FabricatePassLibrary.SUMMARY_PLANES.forEach { plane ->
            (leaf downTo 0).forEach { depth ->
                val name = FabricatePassLibrary.summaryPlane("summary", plane, depth)
                val pass = plan[name] ?: error("$name is not in the plan:\n${plan.report()}")
                assertEquals(1 shl depth, pass.shape.width, "$name width")
                assertEquals(1 shl depth, pass.shape.height, "$name height")
                assertEquals(VK_FORMAT_R32G32B32A32_SFLOAT, pass.shape.format, "$name format")
            }
        }
    }

    @Test
    fun everyReductionReadsTheLevelBelowItOnItsOwnPlaneAndNothingElse() {
        // The three ladders are interleaved in the pass list, so "reads the level below" and "reads
        // its own plane" are two different mistakes with the same symptom: a summary that is a
        // plausible statistic of the wrong thing. Neither is visible in a picture and neither is
        // visible in the memory report.
        val plan = FabricatePassChain.plan(FabricatePassLibrary.summaryPyramid(resolution = 16), 64, 64, srgb)
        FabricatePassLibrary.SUMMARY_PLANES.forEach { plane ->
            (3 downTo 0).forEach { depth ->
                val pass = plan.passes.single { it.name == FabricatePassLibrary.summaryPlane("summary", plane, depth) }
                assertEquals(1, pass.inputs.size, "${pass.name} binds more than its own child level")
                val from = pass.inputs.single().from
                assertEquals(
                    FabricatePassLibrary.summaryPlane("summary", plane, depth + 1),
                    (from as FabricatePassRef.Pass).name,
                    "${pass.name} reduces the wrong level or the wrong plane"
                )
            }
        }
    }

    @Test
    fun theTwoProducersShareTheirReductionLaddersExactly() {
        // The unification claim, asserted rather than commented: a raster pyramid and a point
        // quadtree are two ways of *seeding* a level, and everything above the leaf is the same
        // pass. So for every shared plane the reduce fragments must be the identical string — not
        // an equivalent one, because two copies of this arithmetic would be two things to keep in
        // step, and the one that drifted would still render.
        val raster = FabricatePassChain
            .plan(FabricatePassLibrary.summaryPyramid(prefix = "field", resolution = 16), 64, 64, srgb)
        val points = FabricatePassChain.plan(
            FabricatePassLibrary.mortonPointQuadtree(prefix = "cloud", points = FabricatePassChain.CHAIN_INPUT, pointGrid = 16, depth = 4),
            64, 64, srgb
        )
        FabricatePassLibrary.SUMMARY_PLANES.forEach { plane ->
            (3 downTo 0).forEach { depth ->
                val one = raster.passes.single { it.name == FabricatePassLibrary.summaryPlane("field", plane, depth) }
                val other = points.passes.single { it.name == FabricatePassLibrary.summaryPlane("cloud", plane, depth) }
                assertSame(
                    one.spec.fragment,
                    other.spec.fragment,
                    "the $plane reduction is a different string for the two producers at depth $depth"
                )
                assertEquals(one.shape.width, other.shape.width, "$plane depth $depth")
            }
        }
        // And the range plane is the point producer's alone: a raster cell's contents are its
        // texels, so there is no index for it to carry.
        assertTrue(points.passes.any { FabricatePassLibrary.SUMMARY_RANGE in it.name })
        assertTrue(raster.passes.none { FabricatePassLibrary.SUMMARY_RANGE in it.name })
    }

    @Test
    fun onlyTheValuePlaneChangesWhenTheStatisticsDo() {
        // The switch has to reach exactly one plane. Changing how the *spatial* moments merge would
        // be a silent change to every centroid in the tree; changing nothing at all would be a
        // switch that appears to work because the variance channel still holds a number.
        val pooled = FabricatePassLibrary.summaryPyramid(resolution = 16)
            .associate { it.name to it.fragment }
        val raw = FabricatePassLibrary
            .summaryPyramid(resolution = 16, statistics = FabricatePassLibrary.SummaryStatistics.RAW_MOMENTS)
            .associate { it.name to it.fragment }
        assertEquals(pooled.keys, raw.keys, "the two forms plan different passes")
        val differing = pooled.keys.filter { pooled.getValue(it) != raw.getValue(it) }.toSet()
        assertEquals(
            (4 downTo 0).map { FabricatePassLibrary.summaryPlane("summary", FabricatePassLibrary.SUMMARY_VALUE, it) }.toSet(),
            differing
        )
        // The pooled reduction is the one carrying the between-children term; that term IS the
        // difference between the two forms, so its absence is what a silently-reverted switch
        // would look like.
        assertTrue(
            "delta * delta" in FabricatePassLibrary.SUMMARY_VALUE_REDUCE_POOLED_FRAGMENT,
            "the pooled reduction has lost its between-children term, which makes it a weighted " +
                    "average of variances that ignores every difference between the cells it merged"
        )
    }

    @Test
    fun aPointQuadtreeSortsBeforeItBracketsAndBracketsBeforeItSummarises() {
        // The order is the whole algorithm, and the planner is what enforces it: every one of these
        // references is backwards-only, so a chain whose leaf pass ran before its sort would be
        // refused rather than produce a summary of unsorted points.
        val specs = FabricatePassLibrary.mortonPointQuadtree(
            prefix = "q",
            points = FabricatePassChain.CHAIN_INPUT,
            pointGrid = 16,
            depth = 3
        )
        val plan = FabricatePassChain.plan(specs, 64, 64, srgb)
        val order = plan.passes.map { it.name }
        assertEquals(listOf("q-code", "q-rank", "q-sorted"), order.take(3))
        assertTrue(order.indexOf("q-range-3") < order.indexOf("q-value-3"), order.toString())

        // The leaf summary is invoked for a *node*, and reads points through the sorted index. Both
        // extra bindings are load-bearing and neither can be inferred from the pass's own name.
        val leaf = plan.passes.single { it.name == "q-value-3" }
        assertEquals(
            listOf("sourceTex" to "q-range-3", "sortedTex" to "q-sorted", "pointsTex" to FabricatePassChain.CHAIN_INPUT),
            leaf.inputs.map { input ->
                input.samplerName to when (val from = input.from) {
                    is FabricatePassRef.Pass -> from.name
                    FabricatePassRef.ChainInput -> FabricatePassChain.CHAIN_INPUT
                    else -> from.toString()
                }
            }
        )
        // The point texture is read by three leaf passes and the code pass, so it outlives all of
        // them — which for a chain-input reference costs nothing, and for a *pass* would be the
        // thing that stops its slot being reused underneath them.
        assertEquals(8, plan[FabricatePassLibrary.summaryPlane("q", FabricatePassLibrary.SUMMARY_RANGE, 3)]!!.shape.width)
    }

    @Test
    fun theBinarySearchGetsEnoughStepsToBracketEveryKeyItCanBeGiven() {
        // A binary search one step short does not fail, it returns a bound that is *near* the right
        // one — so a node gets a range starting a few points early, and its summary is a plausible
        // average of slightly the wrong points. Nothing downstream can tell. So the step count is
        // checked by running the same loop the shader runs, over every count the chain allows and
        // every key position in it.
        listOf(4, 16, 64, 100, 256, 1024, FabricatePassLibrary.MAX_POINT_QUADTREE_POINTS).forEach { count ->
            val steps = FabricatePassLibrary.pointSearchSteps(count)
            (0..count).forEach { target ->
                // Keys are 0,1,…count-1; lowerBound(target) must land exactly on `target`.
                var lo = 0
                var hi = count
                repeat(steps) {
                    if (lo < hi) {
                        val mid = (lo + hi) / 2
                        if (mid < target) lo = mid + 1 else hi = mid
                    }
                }
                assertEquals(target, lo, "a $steps-step search over $count keys stopped short of $target")
            }
        }
    }

    @Test
    fun theBitBudgetAndThePointBudgetAreRefusedRatherThanRounded() {
        // A Morton code is an identity, not a quantity, so a code that rounds is a point that
        // changes cell — and because the rounding lands in the *low* bits of an fp32 significand
        // while the tree reads from the top, the point moves at the leaf level of a tree whose
        // upper levels look perfect.
        val bits = assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.mortonPointQuadtree(
                points = "p",
                pointGrid = 16,
                depth = FabricatePassLibrary.MAX_EXACT_MORTON_BITS / 2 + 1
            )
        }
        assertTrue(FabricatePassLibrary.MAX_EXACT_MORTON_BITS.toString() in bits.message.orEmpty(), bits.message.orEmpty())

        val budget = assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.mortonPointQuadtree(points = "p", pointGrid = 128, depth = 4)
        }
        assertTrue("rank" in budget.message.orEmpty(), budget.message.orEmpty())

        // A non-power-of-two grid makes an index divided by N inexact, and an index that rounds
        // reads a different point rather than failing.
        assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.mortonPointQuadtree(points = "p", pointGrid = 12, depth = 3)
        }
        // And an odd pyramid leaf, for the row a 2x2 reduce would drop.
        assertFailsWith<IllegalArgumentException> { FabricatePassLibrary.summaryPyramid(resolution = 96) }
        assertFailsWith<IllegalArgumentException> { FabricatePassLibrary.summaryPyramid(resolution = 16, coarsest = 5) }
        assertFailsWith<IllegalArgumentException> { FabricatePassLibrary.summaryReadout(channel = 4) }
    }

    @Test
    fun stoppingAtACoarsestDepthDropsOnlyTheLevelsAboveIt() {
        // The knob exists because a consumer that reads two levels should not pay for eight, and
        // the failure it has to not have is dropping the *leaf* instead of the root.
        val specs = FabricatePassLibrary.summaryPyramid(resolution = 64, coarsest = 4)
        val plan = FabricatePassChain.plan(specs, 64, 64, srgb)
        val depths = plan.passes
            .filter { FabricatePassLibrary.SUMMARY_VALUE in it.name }
            .map { it.name.substringAfterLast('-').toInt() }
            .sorted()
        assertEquals(listOf(4, 5, 6), depths)
        assertEquals(64, plan[FabricatePassLibrary.summaryPlane("summary", FabricatePassLibrary.SUMMARY_VALUE, 6)]!!.shape.width)
    }

    @Test
    fun theEllipseOverlayBindsAllThreePlanesOfOneLevelAtItsOwnResolution() {
        // The overlay is the only pass in either chain that reads three planes at once, and it is
        // the one whose mistakes are invisible: bound to the wrong *level*, it draws a coarser grid
        // of correct ellipses, which looks exactly like a deliberate choice.
        val specs = FabricatePassLibrary.summaryPyramid(prefix = "field", resolution = 32) +
                FabricatePassLibrary.summaryEllipses(prefix = "field", depth = 2, over = FabricatePassChain.CHAIN_INPUT)
        val plan = FabricatePassChain.plan(specs, 512, 512, srgb)
        val overlay = plan.passes.last()
        assertEquals(512, overlay.shape.width, "the overlay must run at the image's resolution, not the summary's")
        assertEquals(
            listOf("sourceTex" to FabricatePassChain.CHAIN_INPUT, "valueTex" to "field-value-2", "momentTex" to "field-moment-2", "extentTex" to "field-extent-2"),
            overlay.inputs.map { input ->
                input.samplerName to when (val from = input.from) {
                    is FabricatePassRef.Pass -> from.name
                    FabricatePassRef.ChainInput -> FabricatePassChain.CHAIN_INPUT
                    else -> from.toString()
                }
            }
        )
        // The cell count is compiled in, so the shader and the level it reads cannot disagree.
        assertTrue("const int CELLS = 4;" in overlay.spec.fragment, overlay.spec.fragment)
    }

    @Test
    fun anAdaptiveCutIsTwoPassesPerLevelAtTwoDifferentResolutions() {
        // The shape is the claim: the decision runs at the *tree's* resolution and the drawing at
        // the *image's*, which is why a descent is a chain rather than a loop inside one pass.
        val specs = FabricatePassLibrary.summaryPyramid(prefix = "field", resolution = 64) +
                FabricatePassLibrary.adaptiveSummaryCells(prefix = "field", coarsest = 1, finest = 4)
        val plan = FabricatePassChain.plan(specs, 512, 512, srgb)
        (1..4).forEach { depth ->
            val select = plan["adaptive-select-$depth"] ?: error("no select at depth $depth")
            val draw = plan["adaptive-$depth"] ?: error("no draw at depth $depth")
            assertEquals(1 shl depth, select.shape.width, "the select pass runs per cell")
            assertEquals(512, draw.shape.width, "the draw pass runs per pixel")
        }
        assertEquals(8, plan.passes.size - FabricatePassLibrary.summaryPyramid(prefix = "field", resolution = 64).size)
    }

    @Test
    fun eachLevelOfTheDescentReadsItsParentsFlagsAndTheTopLevelHasNone() {
        // `reached` is a conjunction over the whole ancestor chain, and it is *propagated* one
        // level at a time rather than recomputed. Bind the wrong level's flags and the cut is
        // still a picture of cells, just not a tiling — which is exactly the failure the smoke's
        // area sum catches and a reader cannot.
        val specs = FabricatePassLibrary.summaryPyramid(prefix = "field", resolution = 32) +
                FabricatePassLibrary.adaptiveSummaryCells(prefix = "field", coarsest = 2, finest = 4)
        val plan = FabricatePassChain.plan(specs, 256, 256, srgb)

        val top = plan["adaptive-select-2"]!!
        assertTrue(top.inputs.none { it.samplerName == "parentTex" }, "the top of a descent has no parent")
        assertTrue(
            "parentTex" !in top.spec.fragment,
            "the parent sampler must be compiled out at the top, not left declared and unbound"
        )
        (3..4).forEach { depth ->
            val select = plan["adaptive-select-$depth"]!!
            val parent = select.inputs.single { it.samplerName == "parentTex" }
            assertEquals("adaptive-select-${depth - 1}", (parent.from as FabricatePassRef.Pass).name)
        }
        // And the draw passes composite in order, each over the one above it.
        assertEquals(
            FabricatePassRef.ChainInput,
            plan["adaptive-2"]!!.inputs.first().from,
            "the coarsest draw goes over the chain input"
        )
        (3..4).forEach { depth ->
            assertEquals(
                "adaptive-${depth - 1}",
                (plan["adaptive-$depth"]!!.inputs.first().from as FabricatePassRef.Pass).name
            )
        }
    }

    @Test
    fun theFinestLevelOfACutMayNotRefineAndOnlyTheCoarsestDims() {
        // Two off-by-one-shaped mistakes that both render. A cut whose last level may still refine
        // drops every cell that was open there — holes in the tiling. And `underGain` applied at
        // every level darkens a coarse cell once per level below it, which reads as the cut
        // shading itself by depth: a plausible picture of nothing.
        val specs = FabricatePassLibrary.adaptiveSummaryCells(coarsest = 1, finest = 3, underGain = 0.4f)
        val mayRefine = specs.filter { it.name.startsWith("adaptive-select-") }.map { it.push["mayRefine"] }
        assertEquals(listOf(1, 1, 0), mayRefine)
        val dims = specs.filter { !it.name.startsWith("adaptive-select-") }.map { it.push["underGain"] }
        assertEquals(listOf(0.4f, 1f, 1f), dims)
    }

    @Test
    fun aCutRefusesTheRangesThatWouldNotBeADescent() {
        assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.adaptiveSummaryCells(coarsest = 4, finest = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            FabricatePassLibrary.adaptiveSummaryCells(threshold = -1f)
        }
        // One level is a legal cut and is exactly the old one-level overlay, which is the honest
        // degenerate case rather than something to refuse.
        assertEquals(2, FabricatePassLibrary.adaptiveSummaryCells(coarsest = 3, finest = 3).size)
    }

    @Test
    fun onlyTheVarianceCriterionCaresWhichStatisticsThePyramidCarries() {
        // The switch reaches one criterion, and it has to: the other three read weight, occupancy
        // and moments, which are the same channels either way. A criterion that silently changed
        // with the representation would be a cut that moved when you changed how the variance
        // merges.
        FabricatePassLibrary.SubdivisionCriterion.entries.forEach { criterion ->
            val pooled = FabricatePassLibrary.summarySelectFragment(
                criterion, FabricatePassLibrary.SummaryStatistics.POOLED, hasParent = true
            )
            val raw = FabricatePassLibrary.summarySelectFragment(
                criterion, FabricatePassLibrary.SummaryStatistics.RAW_MOMENTS, hasParent = true
            )
            val differs = pooled != raw
            assertEquals(
                criterion == FabricatePassLibrary.SubdivisionCriterion.VALUE_VARIANCE,
                differs,
                "$criterion should ${if (differs) "not " else ""}depend on the statistics"
            )
        }
    }

    @Test
    fun theCovariancePassTakesAllThreePlanesAndRunsAtTheLevelsOwnSize() {
        // Its size matters in a way the overlay's does not: it is one output per *cell*, so it has
        // to be the level's size and not the image's. `MatchSource` against the moment plane is how
        // that is guaranteed rather than restated.
        val specs = FabricatePassLibrary.summaryPyramid(prefix = "field", resolution = 32) +
                FabricatePassLibrary.summaryCovariance(prefix = "field", depth = 3, gain = 100f)
        val plan = FabricatePassChain.plan(specs, 512, 512, srgb)
        val covariance = plan.passes.last()
        assertEquals(8, covariance.shape.width)
        assertEquals(
            listOf("sourceTex" to "field-moment-3", "valueTex" to "field-value-3", "extentTex" to "field-extent-3"),
            covariance.inputs.map { it.samplerName to (it.from as FabricatePassRef.Pass).name }
        )
    }
}

/** Test-only: the persistent pass names, without going through a built chain. */
private fun FabricatePassChainPlan.stateNamesForTest(): List<String> = persistentPasses.map { it.name }
