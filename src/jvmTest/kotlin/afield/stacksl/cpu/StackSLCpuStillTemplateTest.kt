package afield.stacksl.cpu

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Is [StackSLCpu.stillTemplate] still the template the GPU consumed?
 *
 * `StackSLCpu.stillTemplate` is a duplicate of the private `fragmentTemplate` inside
 * `StackSLGalleryHarness`. It has to be a duplicate: that file imports `afield.four.Renderer` and
 * most of the graphics stack, and a CPU backend that needed a graphics context to define its own
 * host shader would be missing the point. So the copy is pinned by comparison against the
 * **committed workspace files** — which are neither copy, but the text a real GPU was handed.
 *
 * 29 of the 31 gallery entries carry a byte-identical still template; the other two are the
 * feedback template. That is checked too, because "they all match each other" is the fact that
 * makes one comparison stand for all of them.
 */
class StackSLCpuStillTemplateTest {

    private val workspace = File(System.getProperty("user.dir"), StackSLCpuGallery.WORKSPACE_DIR)

    @Test
    fun `the built-in still template is byte-identical to the committed one`() {
        val committed = File(workspace, "definitions_fragment_.glsl").readText()
        assertEquals(committed.trim(), StackSLCpu.stillTemplate(1.75f).trim())
    }

    @Test
    fun `every non-feedback gallery entry shares that one template`() {
        val templates = workspace.listFiles { f: File -> f.name.endsWith("_fragment_.glsl") }!!
            .groupBy { it.readText().trim() }
        assertEquals(2, templates.size, "expected exactly two distinct templates (still and feedback)")
        val still = StackSLCpu.stillTemplate(1.75f).trim()
        val sharing = templates[still] ?: error("no committed template matches the built-in still template")
        assertEquals(29, sharing.size, "expected 29 entries on the still template")
        val feedback = templates.keys.single { it != still }
        assertTrue("sampler2D source" in feedback, "the other template should be the feedback one")
    }

    /**
     * The time really is baked in as four decimal places, which is what makes the byte comparison
     * above possible at all: a `Float.toString` would write `1.75` and the committed file says
     * `1.7500`.
     */
    @Test
    fun `time is formatted to four decimal places`() {
        assertTrue("vec4(uv, gallery_hash12(uv), 1.7500)" in StackSLCpu.stillTemplate(1.75f))
        assertTrue("vec4(uv, gallery_hash12(uv), 0.0000)" in StackSLCpu.stillTemplate(0f))
    }

    /** And the convenience path renders something rather than a black square. */
    @Test
    fun `renderStill produces a non-constant image`() {
        val raster = StackSLCpu.renderStill("uv saturate", dim = 32, parallel = false)
        assertEquals(32, raster.width)
        // u grows left to right; v grows bottom to top, so the top row carries the large v.
        // See StackSLCpu.FRAG_UV_ORIGIN_IS_TOP_LEFT.
        assertEquals(0.015625f, raster.at(0, 0, 0), 0f)
        assertEquals(0.984375f, raster.at(0, 0, 1), 0f)
        assertEquals(0.015625f, raster.at(31, 31, 1), 0f)
        assertTrue(raster.toArgb().toSet().size > 100, "expected a gradient, got a near-constant image")
    }
}
