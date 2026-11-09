package afield.simulation.automata

import afield.graphics.render.color
import afield.graphics.render.filled
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shipped growing-NCA rules, run on the CPU: each grows its target from one seed and grows it back
 * after a third of it is erased; the flower also grows turned when its sensors are. The numbers are mean squared RGBA error against the training target;
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

    /**
     * The article's Experiment 4: turning the Sobel pair by θ grows the flower turned by θ, with no
     * retraining. At 90° the turned filters are the upright ones relabelled, so the error matches the
     * upright flower's (2e-5); at 30° it is about 8e-4, against 1.3e-2 for an upright flower scored
     * against the turned target.
     */
    @Test
    fun turnedSensorsGrowATurnedFlower() {
        val target = GrowingNca.pretrainedTarget("flower")
        val upright = GrowingNca.pretrained("flower", random = Random(1))
        upright.step(300)
        for ((degrees, bound) in listOf(90 to 5e-5, 30 to 2e-3)) {
            val nca = GrowingNca.pretrained("flower", random = Random(1))
            nca.rotation = Math.toRadians(degrees.toDouble())
            nca.step(300)
            val turned = GrowingNca.rotateTarget(target, nca.rotation)
            val error = nca.loss(turned)
            val unturned = upright.loss(turned)
            println("$degrees degrees: error $error, upright flower against the turned target $unturned")
            assertTrue(error < bound, "$degrees degrees: error $error is not under $bound")
            assertTrue(error < 0.2 * unturned, "$degrees degrees: $error is no better than leaving the flower upright ($unturned)")
        }
    }

    /** Four quarter turns of a target about the seed's centre give the target back. */
    @Test
    fun rotateTargetQuarterTurnsAreExact() {
        val target = GrowingNca.pretrainedTarget("flower")
        var t = target
        repeat(4) { t = GrowingNca.rotateTarget(t, Math.PI / 2) }
        val worst = target.indices.maxOf { kotlin.math.abs(target[it] - t[it]) }
        assertTrue(worst < 1e-4f, "four quarter turns moved a value by $worst")
    }

    /**
     * Two cuts from the damage sweep on the page, with fixed seeds: the left half grows back where
     * it was (2e-5, the uncut error), and after a disc 24 cells across through the centre the flower
     * does not (about 1e-2: it regrows a few cells higher, or grows a second flower).
     */
    @Test
    fun leftHalfHealsCentreDiscDoesNot() {
        val target = GrowingNca.pretrainedTarget("flower")
        val left = GrowingNca.pretrained("flower", random = Random(1))
        left.step(300)
        left.damage { x, _ -> x < 32 }
        left.step(800)
        val centre = GrowingNca.pretrained("flower", random = Random(1))
        centre.step(300)
        centre.damage(32.0, 32.0, 12.0)
        centre.step(800)
        println("left half ${left.loss(target)}, centre disc ${centre.loss(target)}")
        assertTrue(left.loss(target) < 1e-4, "the left half did not grow back: ${left.loss(target)}")
        assertTrue(centre.loss(target) > 1e-3, "the centre disc healed in place after all: ${centre.loss(target)}")
        assertTrue(centre.liveCells() > 200, "the flower died after the centre cut")
    }

    /** A filled FLine rasterises to premultiplied RGBA: full alpha inside, the line's colour times alpha. */
    @Test
    fun targetFromFLinesIsPremultiplied() {
        val square = afield.geometry.path.FLine().apply {
            moveTo(10, 10); lineTo(30, 10); lineTo(30, 30); lineTo(10, 30); close()
        }
        square.filled = true
        square.color = afield.math.linalg.vec(1.0, 0.5, 0.0, 0.5)
        val t = GrowingNca.targetFromFLines(listOf(square), size = 40)
        val inside = (20 * 40 + 20) * 4
        assertTrue(kotlin.math.abs(t[inside + 3] - 0.5f) < 0.01f, "alpha ${t[inside + 3]}")
        assertTrue(kotlin.math.abs(t[inside] - 0.5f) < 0.01f && kotlin.math.abs(t[inside + 1] - 0.25f) < 0.01f, "rgb not premultiplied")
        assertTrue(t[(2 * 40 + 2) * 4 + 3] == 0f, "outside the square should be empty")
    }

    /**
     * The page's other target helpers: [GrowingNca.targetArgb] shows a target on white,
     * [GrowingNca.writeTarget] writes what `train_growing_nca.py --target-file` reads, and
     * [GrowingNca.strayAlpha] is zero for a grown flower and positive once something grows away from it.
     */
    @Test
    fun targetHelpersAndStrayAlpha() {
        val target = GrowingNca.pretrainedTarget("flower")
        val px = GrowingNca.targetArgb(target, 64, scale = 2)
        assertTrue(px.size == 128 * 128 && px[0] == 0xffffffff.toInt(), "the corner of the target should be white")
        val file = kotlin.io.path.createTempFile("nca_target", ".json").toFile()
        try {
            GrowingNca.writeTarget(file, target, size = 64, name = "flower")
            val o = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonObject
            assertTrue(o["size"]!!.jsonPrimitive.int == 64 && o["rgba"]!!.jsonArray.size == 64 * 64 * 4, "written target has the wrong shape")
        } finally {
            file.delete()
        }
        val nca = GrowingNca.pretrained("flower", random = Random(1))
        nca.step(300)
        assertTrue(nca.strayAlpha(target) == 0.0, "a grown flower has no stray alpha: ${nca.strayAlpha(target)}")
        for (i in 0 until 16) nca.state[(2 * 64 + 2) * GrowingNca.CHANNELS + i] = 1f
        assertTrue(nca.strayAlpha(target) >= 1.0, "a live cell in the corner should count as stray")
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
        // formatBoxCode reflows the GLSL, so compare with all whitespace removed
        fun squeeze(s: String) = s.filterNot { it.isWhitespace() }
        if (squeeze(src) !in squeeze(yaml.readText())) {
            File("build").mkdirs()
            File("build/growing_nca_compute.glsl").writeText(src)
            error("growing_nca.yaml's compute shader differs from GrowingNca.computeShader(); see build/growing_nca_compute.glsl")
        }
    }
}
