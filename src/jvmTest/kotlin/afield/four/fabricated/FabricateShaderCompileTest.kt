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
            listOf(FabricatePassLibrary.distanceBands(source = FabricatePassChain.CHAIN_INPUT))
        )
        val distinct = chains.flatten().map { it.fragment }.distinct()
        assertTrue(distinct.size >= 5, "expected at least five distinct library shaders, got ${distinct.size}")
        distinct.forEachIndexed { index, source -> compiles("fabricate-pass-library-$index.frag", source) }
    }
}
