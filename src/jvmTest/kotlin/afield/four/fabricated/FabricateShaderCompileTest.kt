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
}
