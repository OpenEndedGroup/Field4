package afield.lang.solidsl.study

import afield.lang.solidsl.cuboid
import afield.lang.solidsl.cylinder
import afield.lang.solidsl.minus
import afield.lang.solidsl.sphere
import afield.lang.stacksl.cpu.parseGlslShader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolidStudyTest {
    private fun covered(scene: SolidScene, view: SolidView): Double {
        val r = SolidCpuTracer.render(scene, 96, 72, view)
        var n = 0
        // The background is dark grey-blue with a slight vignette; the solid is anything clearly not it.
        val corner = r.rgba.copyOfRange(0, 3)
        for (p in 0 until r.width * r.height) if ((0 until 3).any { kotlin.math.abs(r.rgba[p * 4 + it] - corner[it]) > 0.02f }) n++
        return n.toDouble() / (r.width * r.height)
    }

    /** A sphere at the default distance fills a plausible part of the frame, and a close-up more of it. */
    @Test
    fun cpuTracerFramesTheSolid() {
        val scene = SolidScene(sphere(1.0))
        val far = covered(scene, SolidView())
        val near = covered(scene, SolidView(distance = 1.3))
        assertTrue(far in 0.08..0.3, "coverage at 2.2 bounding radii was $far")
        assertTrue(near > far, "a close-up covers more: $near vs $far")
    }

    /** A section through a hollow block shows its inside: the cut face is lit where a bore would be dark. */
    @Test
    fun sectionCutsOnTheCameraSide() {
        val block = SolidScene(cuboid(2.0) - cylinder(3.0, 0.5))
        val whole = SolidCpuTracer.render(block, 64, 48, SolidView(yawDegrees = 0.0, pitchDegrees = 0.0))
        val cut = SolidCpuTracer.render(block, 64, 48, SolidView(yawDegrees = 0.0, pitchDegrees = 0.0, section = "y"))
        var differs = 0
        for (i in whole.rgba.indices) if (kotlin.math.abs(whole.rgba[i] - cut.rgba[i]) > 0.02f) differs++
        assertTrue(differs > 0, "a section changes the picture")
    }

    /** The fragment, with the default shading and with a scene's own, is GLSL the CPU parser reads. */
    @Test
    fun fragmentParses() {
        parseGlslShader(SolidStudyShader.fragment(SolidScene(sphere(1.0))))
        val own = sphere(1.0).shaded("vec3 shade(vec3 p, vec3 n, vec3 rd, float t) { return vec3(0.5) * occlusion(p, n) * softShadow(p, n); }")
        parseGlslShader(SolidStudyShader.fragment(own))
    }

    /** The starter compiles to a sphere. */
    @Test
    fun starterCompiles() {
        val scene = SolidStudies.compile(SolidStudies.STARTER)
        assertEquals(sphere(1.0), scene.solid)
        assertTrue(scene.shade.isBlank())
    }
}
