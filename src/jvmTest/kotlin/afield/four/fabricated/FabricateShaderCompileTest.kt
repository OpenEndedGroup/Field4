package afield.four.fabricated

import afield.four.modules.ShaderKind
import afield.four.modules.ShaderSPIRVUtils
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * GPU-free validation of the Fabricate GLSL that Kotlin compilation cannot check: the strings are
 * fed through shaderc (Vulkan 1.3 / SPIR-V 1.5) so a malformed shader fails the build instead of
 * only blowing up at pipeline-creation time on a machine with a Vulkan device. Covers the deferred
 * lighting fragment (multi-light loop + buffer_reference + skybox reconstruction) and the tone-map
 * and bloom post-effect fragments.
 */
class FabricateShaderCompileTest {

    private fun compiles(name: String, source: String) {
        val spirv = ShaderSPIRVUtils.compileShader(name, source, ShaderKind.FRAGMENT_SHADER)
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "$name produced empty SPIR-V")
    }

    @Test
    fun lightingFragmentCompiles() {
        // Exercises GL_EXT_buffer_reference lights loop, invViewProj skybox ray, and PBR helpers.
        compiles("fabricate-lighting.frag", StandardRenderer.lightingFragment)
    }

    @Test
    fun toneMapFragmentCompiles() {
        compiles("fabricate-tonemap.frag", TONEMAP_FRAGMENT)
    }

    @Test
    fun bloomFragmentsCompile() {
        compiles("fabricate-bloom-bright.frag", BLOOM_BRIGHT_FRAGMENT)
        compiles("fabricate-bloom-blur.frag", BLOOM_BLUR_FRAGMENT)
        compiles("fabricate-bloom-composite.frag", BLOOM_COMPOSITE_FRAGMENT)
    }

    /**
     * The [FabricatePassLibrary] shaders, which need this more than the ones above do.
     *
     * Two of them are only reachable through a multi-resolution chain, so the first machine to find a
     * syntax error in them would otherwise be one with a GPU running a specific chain shape — and the
     * aperture fragment interpolates `MAX_APERTURE_TAPS` into its own loop bound from Kotlin, which is
     * a string-template seam no Kotlin compiler checks and no reader would think to.
     */
    @Test
    fun passLibraryFragmentsCompile() {
        compiles("fabricate-downsample-13tap.frag", FabricatePassLibrary.DOWNSAMPLE_13TAP_FRAGMENT)
        compiles("fabricate-aperture.frag", FabricatePassLibrary.APERTURE_FRAGMENT)
        compiles("fabricate-upsample-add.frag", FabricatePassLibrary.UPSAMPLE_ADD_FRAGMENT)
        compiles("fabricate-composite.frag", FabricatePassLibrary.COMPOSITE_FRAGMENT)
    }

    /**
     * The fluid shaders, and the pair of them that differ by string interpolation.
     *
     * `fluidForceFragment` compiles its vorticity term *in or out* rather than branching on it,
     * because a `sampler2D curlTex` left in the SPIR-V of a chain that has no `curl` pass fails in
     * descriptor setup. Both halves of that switch are separate source strings that no Kotlin
     * compiler looks at, so both are compiled here.
     */
    @Test
    fun fluidFragmentsCompile() {
        compiles("fabricate-fluid-advect-velocity.frag", FabricatePassLibrary.FLUID_ADVECT_VELOCITY_FRAGMENT)
        compiles("fabricate-fluid-curl.frag", FabricatePassLibrary.FLUID_CURL_FRAGMENT)
        compiles("fabricate-fluid-force-vorticity.frag", FabricatePassLibrary.fluidForceFragment(true))
        compiles("fabricate-fluid-force-plain.frag", FabricatePassLibrary.fluidForceFragment(false))
        compiles("fabricate-fluid-divergence.frag", FabricatePassLibrary.FLUID_DIVERGENCE_FRAGMENT)
        compiles("fabricate-fluid-zero.frag", FabricatePassLibrary.FLUID_ZERO_FRAGMENT)
        compiles("fabricate-fluid-jacobi.frag", FabricatePassLibrary.FLUID_JACOBI_FRAGMENT)
        compiles("fabricate-fluid-project.frag", FabricatePassLibrary.FLUID_PROJECT_FRAGMENT)
        compiles("fabricate-fluid-advect-dye.frag", FabricatePassLibrary.FLUID_ADVECT_DYE_FRAGMENT)
    }

    /**
     * The jump flood shaders, which read with `texelFetch` and index by `gl_FragCoord` — neither of
     * which any other fragment in this library does, so neither has ever been through shaderc here.
     */
    @Test
    fun jumpFloodFragmentsCompile() {
        compiles("fabricate-jfa-seed.frag", FabricatePassLibrary.JUMP_FLOOD_SEED_FRAGMENT)
        compiles("fabricate-jfa-flood.frag", FabricatePassLibrary.JUMP_FLOOD_FRAGMENT)
        compiles("fabricate-jfa-distance.frag", FabricatePassLibrary.JUMP_FLOOD_DISTANCE_FRAGMENT)
        compiles("fabricate-distance-bands.frag", FabricatePassLibrary.DISTANCE_BANDS_FRAGMENT)
    }

    /**
     * The summarised-quadtree shaders, and the three string-template seams they carry.
     *
     * Every one of these interpolates something from Kotlin into GLSL that no Kotlin compiler looks
     * at: a loop bound from a point count, a binary-search depth, a bit budget, and — in the value
     * leaf and the variance readout — *two different accumulations* selected by an enum. Both halves
     * of that switch are separate sources, so both are compiled, the same way
     * `fluidForceFragment`'s vorticity switch is.
     */
    @Test
    fun summaryQuadtreeFragmentsCompile() {
        compiles("fabricate-summary-value-seed-pooled.frag", FabricatePassLibrary.SUMMARY_VALUE_SEED_POOLED_FRAGMENT)
        compiles("fabricate-summary-value-seed-raw.frag", FabricatePassLibrary.SUMMARY_VALUE_SEED_RAW_FRAGMENT)
        compiles("fabricate-summary-moment-seed.frag", FabricatePassLibrary.SUMMARY_MOMENT_SEED_FRAGMENT)
        compiles("fabricate-summary-extent-seed.frag", FabricatePassLibrary.SUMMARY_EXTENT_SEED_FRAGMENT)
        compiles("fabricate-summary-mean-reduce.frag", FabricatePassLibrary.SUMMARY_MEAN_REDUCE_FRAGMENT)
        compiles("fabricate-summary-value-reduce.frag", FabricatePassLibrary.SUMMARY_VALUE_REDUCE_POOLED_FRAGMENT)
        compiles("fabricate-summary-extent-reduce.frag", FabricatePassLibrary.SUMMARY_EXTENT_REDUCE_FRAGMENT)
        compiles("fabricate-summary-range-reduce.frag", FabricatePassLibrary.SUMMARY_RANGE_REDUCE_FRAGMENT)
        compiles("fabricate-summary-readout.frag", FabricatePassLibrary.SUMMARY_READOUT_FRAGMENT)
        compiles("fabricate-summary-covariance.frag", FabricatePassLibrary.SUMMARY_COVARIANCE_FRAGMENT)
        FabricatePassLibrary.SummaryStatistics.entries.forEach { statistics ->
            compiles(
                "fabricate-summary-variance-$statistics.frag",
                FabricatePassLibrary.summaryVarianceFragment(statistics)
            )
        }
        compiles("fabricate-summary-ellipses.frag", FabricatePassLibrary.summaryEllipsesFragment(16))
    }

    /**
     * The point-quadtree shaders, which do things nothing else in this library does: unsigned bit
     * arithmetic, an O(N) gather loop, and a binary search with a data-dependent trip count.
     *
     * The bit shuffle is the one worth compiling on its own. `fieldSpreadBits` is five shifts and
     * five masks with no arithmetic a reader can check by eye, and it is interpolated into two
     * different shaders that both have to agree about it — a node's prefix and a point's code are
     * the same function of a cell coordinate, and if they were not, every node would bracket the
     * wrong range and every summary would be a plausible average of the wrong points.
     */
    @Test
    fun pointQuadtreeFragmentsCompile() {
        compiles("fabricate-morton-code.frag", FabricatePassLibrary.mortonCodeFragment(depth = 5, count = 256, width = 16))
        compiles("fabricate-point-rank.frag", FabricatePassLibrary.pointRankFragment(count = 256, width = 16))
        compiles("fabricate-point-gather.frag", FabricatePassLibrary.pointGatherFragment(count = 256, width = 16))
        compiles(
            "fabricate-point-nodes.frag",
            FabricatePassLibrary.pointNodesFragment(
                depth = 4,
                count = 256,
                width = 16,
                steps = FabricatePassLibrary.pointSearchSteps(256)
            )
        )
        listOf(true, false).forEach { pooled ->
            compiles(
                "fabricate-point-leaf-value-pooled-$pooled.frag",
                FabricatePassLibrary.pointLeafValueFragment(count = 256, width = 16, pooled = pooled)
            )
        }
        compiles("fabricate-point-leaf-moment.frag", FabricatePassLibrary.pointLeafMomentFragment(256, 16))
        compiles("fabricate-point-leaf-extent.frag", FabricatePassLibrary.pointLeafExtentFragment(256, 16))
    }

    /**
     * Every distinct fragment any [FabricatePassLibrary] chain builder can put in a plan compiles.
     *
     * The test above names four strings; this one asks the *builders* what they actually emit, so a
     * new library function that forgets to add its shader here still cannot ship a chain whose
     * shaders have never been through shaderc.
     */
    @Test
    fun everyShaderTheLibraryCanPlanCompiles() {
        val chains = listOf(
            FabricatePassLibrary.apertureChain(),
            FabricatePassLibrary.bloomPyramid(levels = 4),
            FabricatePassLibrary.downsamplePyramid(levels = 3),
            FabricatePassLibrary.separableBlur(),
            listOf(FabricatePassLibrary.brightPass(), FabricatePassLibrary.aperture()),
            FabricatePassLibrary.stableFluid(resolution = 64, iterations = 2),
            FabricatePassLibrary.stableFluid(prefix = "plain", resolution = 64, iterations = 2, vorticity = 0f),
            FabricatePassLibrary.jumpFloodDistance(resolution = 64),
            FabricatePassLibrary.jumpFloodDistance(prefix = "signed", resolution = 64, signed = true),
            listOf(FabricatePassLibrary.distanceBands(source = FabricatePassChain.CHAIN_INPUT)),
            FabricatePassLibrary.summaryPyramid(resolution = 32),
            FabricatePassLibrary.summaryPyramid(
                prefix = "raw",
                resolution = 32,
                statistics = FabricatePassLibrary.SummaryStatistics.RAW_MOMENTS
            ),
            FabricatePassLibrary.mortonPointQuadtree(points = FabricatePassChain.CHAIN_INPUT, pointGrid = 16, depth = 3),
            FabricatePassLibrary.mortonPointQuadtree(
                prefix = "raw-points",
                points = FabricatePassChain.CHAIN_INPUT,
                pointGrid = 16,
                depth = 3,
                statistics = FabricatePassLibrary.SummaryStatistics.RAW_MOMENTS
            ),
            listOf(
                FabricatePassLibrary.summaryEllipses(depth = 3),
                FabricatePassLibrary.summaryReadout(from = FabricatePassChain.CHAIN_INPUT),
                FabricatePassLibrary.summaryVariance(from = FabricatePassChain.CHAIN_INPUT),
                FabricatePassLibrary.summaryCovariance(depth = 3)
            )
        )
        val distinct = chains.flatten().map { it.fragment }.distinct()
        assertTrue(distinct.size >= 5, "expected at least five distinct library shaders, got ${distinct.size}")
        distinct.forEachIndexed { index, source -> compiles("fabricate-pass-library-$index.frag", source) }
    }
}
