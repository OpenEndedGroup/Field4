package afield.graphics.volume

import java.io.File
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Trains the in-scatter network on a small budget and checks that it beats the fitted
 * single-scatter-plus-ambient approximation against the path-traced reference, on a camera the
 * training never saw. Writes the comparison to `build/neural_clouds/`.
 */
class NeuralCloudsTest {
    private val scene = CloudScene()
    private val trainCameras = listOf(
        CloudScene.Camera(doubleArrayOf(0.0, 1.2, 7.5), doubleArrayOf(0.0, 0.95, 0.0)),
        CloudScene.Camera(doubleArrayOf(-5.5, 2.4, 4.5), doubleArrayOf(0.0, 0.9, 0.0)),
        CloudScene.Camera(doubleArrayOf(4.0, 0.6, -6.0), doubleArrayOf(0.0, 1.0, 0.0)),
    )
    private val heldOut = CloudScene.Camera(doubleArrayOf(4.6, 0.35, 3.6), doubleArrayOf(0.0, 1.05, 0.0), 40.0)

    @Test
    fun estimatorIsFiniteAndPositive() {
        val random = SplittableRandom(3)
        val y = DoubleArray(3)
        var sum = 0.0
        repeat(200) {
            scene.multipleScatter(doubleArrayOf(0.1, 1.0, 0.2), doubleArrayOf(0.0, 0.0, 1.0), random, into = y)
            assertTrue(y.all { it.isFinite() && it >= 0.0 })
            sum += y.sum()
        }
        assertTrue(sum > 0.0, "deep inside a lit cloud the in-scatter should not be zero")
    }

    @Test
    fun networkBeatsAmbientOnAHeldOutView() {
        val samples = NeuralCloud.samples(scene, trainCameras, count = 12000, pathsPerSample = 8)
        val net = NeuralCloud.train(samples, steps = 8000)
        val ambient = NeuralCloud.ambient(samples)
        val w = 200; val h = 125
        val none = CloudScene.InScatter { _, _, _, _, into -> into.fill(0.0) }
        val reference = scene.render(heldOut, w, h, scene.reference(48))
        val approx = scene.render(heldOut, w, h, ambient)
        val neural = scene.render(heldOut, w, h, net.inScatter)
        val single = scene.render(heldOut, w, h, none)
        val mask = approxSkyMask(w, h)
        val eNet = cloudError(neural, reference, mask)
        val eAmb = cloudError(approx, reference, mask)
        val eSingle = cloudError(single, reference, mask)
        val dir = File("build/neural_clouds").apply { mkdirs() }
        writeCloudComparison(File(dir, "held_out.png"), reference, listOf(single, approx, neural))
        File(dir, "held_out.txt").writeText(
            "samples ${samples.size}, final loss ${net.trainingLoss.takeLast(200).average()}\n" +
                "single-scatter only: $eSingle\nfitted ambient: $eAmb\nnetwork: $eNet\n",
        )
        File(dir, "network.glsl").writeText(net.glslNetwork())
        println("NEURAL_CLOUDS single=$eSingle ambient=$eAmb net=$eNet")
        assertTrue(eNet.rmse < eAmb.rmse, "network $eNet should beat ambient $eAmb")
    }

    /** Pixels whose ray crosses the cloud's box count as cloud; others are bare sky. */
    private fun approxSkyMask(w: Int, h: Int): CloudScene.Image {
        val mask = CloudScene.Image(w, h)
        // a mask image equal to the reference except where the ray hits cloud: built by a render with
        // an absurd in-scatter, so any pixel touched by cloud differs from it
        val huge = CloudScene.InScatter { _, _, _, _, into -> into.fill(1e3) }
        scene.render(heldOut, w, h, huge).rgb.copyInto(mask.rgb)
        return mask
    }
}
