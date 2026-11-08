package afield.simulation.automata

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shipped growing-NCA rules, run on the CPU: each grows its target from one seed and grows it back
 * after a third of it is erased. The numbers are mean squared RGBA error against the training target;
 * "empty" is the error of a blank grid, so a ratio well under 1 means the picture is there.
 */
class GrowingNcaTest {
    private fun emptyLoss(target: FloatArray) = target.sumOf { (it * it).toDouble() } / target.size

    @Test
    fun flowerGrowsAndRegrows() {
        for (name in GrowingNca.pretrainedNames) {
            val target = GrowingNca.pretrainedTarget(name)
            val nca = GrowingNca.pretrained(name, random = Random(1))
            val empty = emptyLoss(target)
            nca.step(300)
            val grown = nca.loss(target)
            nca.damage(32.0, 26.0, 10.0)
            val damaged = nca.loss(target)
            nca.step(300)
            val regrown = nca.loss(target)
            println("$name: empty $empty grown $grown damaged $damaged regrown $regrown")
            assertTrue(grown < 0.25 * empty, "$name did not grow: $grown vs empty $empty")
            assertTrue(damaged > 1.5 * grown, "$name: the damage did not register ($damaged vs $grown)")
            assertTrue(regrown < 0.25 * empty, "$name did not regrow: $regrown vs empty $empty")
        }
    }

    @Test
    fun emptySpaceStaysEmpty() {
        val nca = GrowingNca.pretrained("flower")
        nca.state.fill(0f)
        nca.step(5)
        assertTrue(nca.state.all { it == 0f }, "a grid with no live cell must not come alive")
    }

    @Test
    fun computeShaderUsesTheSharedNetwork() {
        val src = GrowingNca.pretrained("flower").computeShader()
        assertTrue("void ncaNet(ncaNetWeights nw, float x[48], out float y[16])" in src, src.take(400))
        assertTrue("layout(local_size_x = 8, local_size_y = 8)" in src)
    }

    /**
     * The demo transcript carries the compute shader as text (a box's shaders are compiled from its
     * properties before its code runs), so hold that text to [GrowingNca.computeShader]. On a
     * mismatch the current shader is written to `build/growing_nca_compute.glsl` to paste in.
     */
    @Test
    fun transcriptComputeShaderIsCurrent() {
        val src = GrowingNca.pretrained("flower").computeShader()
        val yaml = File("dev_docs/agents/transcripts/growing_nca.yaml")
        if (!yaml.exists()) return
        val indented = src.lines().joinToString("\n") { if (it.isEmpty()) it else "          $it" }.trimEnd()
        if (indented !in yaml.readText()) {
            File("build").mkdirs()
            File("build/growing_nca_compute.glsl").writeText(src)
            error("growing_nca.yaml's compute shader differs from GrowingNca.computeShader(); see build/growing_nca_compute.glsl")
        }
    }
}
