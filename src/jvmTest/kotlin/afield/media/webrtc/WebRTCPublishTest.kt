package afield.media.webrtc

import afield.graphics.shader.ShaderKind
import afield.graphics.shader.ShaderSPIRVUtils
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The CPU half of [webrtcPublish]: the copy pass converts between what its source holds and what the
 * sink stores, so a published texture looks the same as it does in the window, and every variant
 * compiles. The GPU half (a browser ShaderTrinket receiving a published shadertoy canvas the right way
 * up and in the same colours) was checked live; see `dev_docs/madp/shadertrinket.md`.
 */
class WebRTCPublishTest {

    private fun body(source: Int, target: Int) =
        webrtcCopyFragment(source, target, 640, 360).substringAfter("void main()")

    @Test
    fun `linear sources go straight into an sRGB sink`() {
        // A shadertoy canvas or an ordinary render target (_SRGB), or an HDR float target.
        assertTrue("color = vec4(c, 1.0)" in body(VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB))
        assertTrue("color = vec4(c, 1.0)" in body(VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_B8G8R8A8_SRGB))
    }

    @Test
    fun `display-value sources are decoded for an sRGB sink`() {
        // A UNORM texture already holds what should be seen; the sink would encode it a second time.
        assertTrue("displayToLinear(c)" in body(VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_SRGB))
    }

    @Test
    fun `a UNORM sink stores display values`() {
        assertTrue("linearToDisplay(c)" in body(VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_UNORM))
        assertTrue("color = vec4(c, 1.0)" in body(VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM))
    }

    @Test
    fun `every variant of the copy pass compiles`() {
        val formats = listOf(VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT)
        for (source in formats) for (target in formats) {
            val spirv = ShaderSPIRVUtils.compileShader("copy-$source-$target.frag", webrtcCopyFragment(source, target, 960, 540), ShaderKind.FRAGMENT_SHADER)
            assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "copy $source -> $target produced no SPIR-V")
        }
        val vertex = ShaderSPIRVUtils.compileShader("copy.vert", WEBRTC_COPY_VERTEX, ShaderKind.VERTEX_SHADER)
        assertTrue((vertex.bytecode()?.remaining() ?: 0) > 0)
    }
}
