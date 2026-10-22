package afield.graphics.shader

import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import afield.tools.docs.CitationGuard
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CPU half of the shadertoy host: the `{{ shadertoy.image }}` preamble a ShaderTrinket program is
 * pasted under, the channel flip and canvas passes, and the small decisions they rest on. The GPU half
 * (that a bound channel comes out the right way up, at its own resolution, in display values) was
 * checked by native capture; see `dev_docs/madp/shadertrinket.md`.
 */
class ShadertoyChannelsTest {

    private fun compiles(name: String, source: String, kind: ShaderKind = ShaderKind.FRAGMENT_SHADER) {
        val spirv = ShaderSPIRVUtils.compileShader("$name.glsl", source, kind)
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "$name produced no SPIR-V")
    }

    @Test
    fun `a ShaderToy Image pass compiles under the image preamble`() {
        // ShaderTrinket's "Flat Rings", verbatim: loops, int/float mixing, early assignment.
        val flatRings = """
            void mainImage(out vec4 fragColor, in vec2 fragCoord) {
              vec2 uv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
              float t = iTime * 0.7;
              vec3 col = vec3(0.015, 0.018, 0.018);
              for (int i = 0; i < 8; i++) {
                float fi = float(i);
                vec2 c = vec2(cos(t + fi * 1.35), sin(t * 0.8 + fi * 1.1)) * 0.42;
                if (1.0 - step(0.035, length(uv - c)) > 0.5) col = vec3(0.92, 0.96, 0.90);
              }
              fragColor = vec4(col + texture(iChannel0, uv).rgb * iChannelResolution[0].x, 1.0);
            }
        """.trimIndent()
        compiles("flat-rings", shadertoyImagePreamble + "\n" + flatRings)
    }

    @Test
    fun `every program the shadertrinket starting point ships compiles`() {
        // The presets are ShaderTrinket's own text, verbatim under the one template line, so this is
        // also the check that the preamble declares everything a ShaderToy Image pass expects.
        val yaml = File(CitationGuard.repoRoot(), "dev_docs/agents/transcripts/shadertrinket.yaml")
        @Suppress("UNCHECKED_CAST")
        val ops = (Yaml().load<Map<String, Any?>>(yaml.readText())["operations"] as List<Map<String, Any?>>)
        val fragments = ops.mapNotNull { it["create"] as? Map<*, *> }.mapNotNull { create ->
            ((create["properties"] as? Map<*, *>)?.get("fragment") as? String)?.let { create["box"] as String to it }
        }
        assertTrue(fragments.size >= 6, "expected the lead and its presets, found ${fragments.map { it.first }}")
        for ((box, fragment) in fragments) {
            val line = "{{ shadertoy.image }}"
            assertTrue(fragment.startsWith(line), "$box: fragment should start with $line")
            compiles(box, fragment.replace(line, shadertoyImagePreamble))
        }
    }

    @Test
    fun `the image main hands mainImage a y-up fragCoord and decodes display values`() {
        assertTrue("iResolution.y - gl_FragCoord.y" in shadertoyImagePreamble)
        assertTrue("shadertoyDisplayToLinear(color.rgb)" in shadertoyImagePreamble)
        assertTrue(shadertoyImagePreamble.startsWith(shadertoyFragmentPreamble))
    }

    @Test
    fun `the flip and canvas passes compile`() {
        compiles("fullscreen", ShadertoyChannel.FULLSCREEN_VERTEX, ShaderKind.VERTEX_SHADER)
        compiles("flip-encode", ShadertoyChannel.flipFragment(encode = true, height = 360))
        compiles("flip-raw", ShadertoyChannel.flipFragment(encode = false, height = 360))
        assertTrue("linearToDisplay(c.rgb)" in ShadertoyChannel.flipFragment(encode = true, height = 1))
        assertFalse("linearToDisplay(c.rgb)" in ShadertoyChannel.flipFragment(encode = false, height = 1))
    }

    @Test
    fun `only 8-bit UNORM sources are copied without re-encoding`() {
        assertTrue(isUnorm8(VK_FORMAT_R8G8B8A8_UNORM))
        assertFalse(isUnorm8(VK_FORMAT_B8G8R8A8_SRGB))
        assertFalse(isUnorm8(VK_FORMAT_R16G16B16A16_SFLOAT))
    }

    @Test
    fun `channel names map to indices`() {
        assertEquals(0, shadertoyChannelIndex("iChannel0"))
        assertEquals(3, shadertoyChannelIndex("iChannel3"))
        assertNull(shadertoyChannelIndex("iChannel4"))
        assertNull(shadertoyChannelIndex("iChannel10"))
        assertNull(shadertoyChannelIndex("remote"))
    }

    @Test
    fun `a size is read from any public width and height`() {
        class Stream(val width: Int, val height: Int)
        assertEquals(640 to 360, sampleableSize(Stream(640, 360)))
        assertEquals(512 to 512, sampleableSize(Any()))
        assertEquals(7 to 9, sampleableSize(Stream(0, 0), fallback = 7 to 9))
    }
}
