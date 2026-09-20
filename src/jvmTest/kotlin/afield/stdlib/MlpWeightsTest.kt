package afield.stdlib

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The CPU half of the neural-particle parity check: that [MlpWeights] is the same network Smile
 * trained, and that [MlpWeights.evaluate] is a faithful fp32 reading of it.
 *
 * This is the half that can fail *silently on the GPU*. If `exportWeights` transposed a layer, or
 * put the biases in the wrong place, or got the leaky slope wrong, the shader would still compile,
 * still run at full speed, and still produce a smooth field — a wrong one. So the assertions here
 * are against Smile's own forward pass, which is the only thing in the building that knows what
 * the network means, and they are tight: [assertMatchesSmile] allows 2e-6 relative, which is fp32
 * rounding and nothing else. A transposed square layer fails it by a factor of about a million.
 *
 * `NeuralFieldParitySmoke` is the other half, and it compares the GPU against [MlpWeights.evaluate]
 * rather than against Smile, for the reason set out in `neuralparticles.kt`: one comparison would
 * have to be loose enough to absorb the fp64-to-fp32 gap, and that is loose enough to hide a
 * transcription error in a small network.
 */
class MlpWeightsTest {

    /**
     * fp32 against fp64 over 64 input vectors, as a relative error against the output's magnitude.
     *
     * Smile is the oracle in both directions here — [MlpWeights.evaluate] is being checked against
     * `VectorMLP.predict`, not the other way round.
     */
    private fun assertMatchesSmile(net: VectorMLP, tolerance: Double = 2e-6, samples: Int = 64) {
        val weights = net.exportWeights()
        val random = Random(20260919)
        var worst = MlpAgreement.nothing
        repeat(samples) {
            val x = FloatArray(net.inputs) { (random.nextDouble() * 4.0 - 2.0).toFloat() }
            val expected = FloatArray(net.outputs)
            net.predict(DoubleArray(x.size) { x[it].toDouble() }).forEachIndexed { i, v ->
                expected[i] = v.toFloat()
            }
            worst = MlpAgreement.accumulate(worst, x, expected, weights.evaluate(x))
        }
        assertTrue(worst.worstRelative < tolerance, "exported weights disagree with Smile: $worst")
        assertTrue(worst.comparisons == samples * net.outputs, "compared ${worst.comparisons} outputs")
    }

    @Test
    fun anUntrainedNetworkExportsToWeightsThatReproduceSmilesForwardPass() {
        // Untrained is the harder case, not the easier one: Xavier-uniform initial weights are
        // symmetric in a way a trained network is not, so a layer that came out transposed still
        // has plausible statistics. It is also the case where a bias error is most visible,
        // because the weights have not yet grown to dominate the biases.
        smile.math.MathEx.setSeed(20260919)
        assertMatchesSmile(VectorMLP(inputs = 5, outputs = 3, hidden = intArrayOf(7, 4), activation = "leaky"))
    }

    @Test
    fun aTrainedNetworkExportsToWeightsThatReproduceSmilesForwardPass() {
        // A trained network is the case that matters, and its weights are asymmetric and span a
        // wider range than an initialisation does, so it is the one whose fp32 error is real.
        smile.math.MathEx.setSeed(20260919)
        val net = VectorMLP(inputs = 3, outputs = 2, hidden = intArrayOf(16, 16), activation = "leaky")
        net.configure(1e-2, 0.0, 0.9)
        val random = Random(4)
        repeat(400) {
            repeat(16) {
                val x = DoubleArray(3) { random.nextDouble() * 2 - 1 }
                net.accumulate(x, doubleArrayOf(x[0] * x[1] - x[2], x[2] * x[2]))
            }
            net.step(16)
        }
        assertMatchesSmile(net)
    }

    @Test
    fun everyTranslatableActivationSurvivesTheRoundTrip() {
        // Each activation is a separate opportunity to translate one wrongly, and three of the
        // five agree with each other closely enough near zero that a mix-up is invisible on a
        // casual look: leaky, relu and linear are all the identity for positive inputs.
        for (activation in MlpActivation.entries) {
            smile.math.MathEx.setSeed(7)
            assertMatchesSmile(
                VectorMLP(inputs = 4, outputs = 2, hidden = intArrayOf(6, 6), activation = activation.smileName),
                // tanh and sigmoid saturate, so their outputs are small and their relative error
                // against a small magnitude is correspondingly larger. Still fp32, still nowhere
                // near a transcription error.
                tolerance = 1e-5,
            )
        }
    }

    @Test
    fun aSingleHiddenLayerAndAWideOutputBothExportCorrectly() {
        // The layer loop's edge cases: one weight-bearing hidden layer, and an output wider than
        // anything before it. Both are shapes the emitted GLSL indexes differently.
        smile.math.MathEx.setSeed(11)
        assertMatchesSmile(VectorMLP(inputs = 2, outputs = 9, hidden = intArrayOf(5), activation = "tanh"), 1e-5)
    }

    @Test
    fun theWeightLayoutIsTheOneTheShaderIsToldToExpect() {
        // The offsets are arithmetic, and the shader is generated from the same arithmetic, so
        // this pins the arithmetic itself rather than trusting the two to agree by construction.
        val weights = MlpWeights(
            inputs = 3,
            outputs = 2,
            hidden = intArrayOf(4),
            activation = MlpActivation.LINEAR,
            flat = FloatArray(3 * 4 + 4 + 4 * 2 + 2),
        )
        assertEquals(2, weights.layerCount)
        assertEquals(0, weights.weightOffset(0))
        assertEquals(12, weights.biasOffset(0))
        assertEquals(16, weights.weightOffset(1))
        assertEquals(24, weights.biasOffset(1))
        assertEquals(26, weights.parameterCount)
    }

    @Test
    fun aLinearNetworkEvaluatesToTheAffineMapItsWeightsSpellOut() {
        // One case computed by hand, so that a consistent-but-wrong convention shared by
        // `evaluate` and `exportWeights` cannot pass. Layer 0 is 2->2 with weights
        // [[1, 2], [3, 4]] and biases [10, 20]; layer 1 is 2->1 with weights [[1, -1]] and bias 0.
        val weights = MlpWeights(
            inputs = 2,
            outputs = 1,
            hidden = intArrayOf(2),
            activation = MlpActivation.LINEAR,
            flat = floatArrayOf(1f, 2f, 3f, 4f, 10f, 20f, 1f, -1f, 0f),
        )
        // h = [1*5 + 2*7 + 10, 3*5 + 4*7 + 20] = [29, 63]; y = 29 - 63 = -34.
        assertEquals(-34f, weights.evaluate(floatArrayOf(5f, 7f))[0], 1e-4f)
    }

    @Test
    fun theLeakySlopeIsSmilesOwnDefault() {
        // 0.01 is read out of `ActivationFunction.leaky()`'s bytecode. A network whose hidden
        // units are all negative is the one that can tell the difference, so drive it there.
        smile.math.MathEx.setSeed(3)
        val net = VectorMLP(inputs = 1, outputs = 1, hidden = intArrayOf(8), activation = "leaky")
        val weights = net.exportWeights()
        assertEquals(MlpActivation.LEAKY, weights.activation)
        var worst = 0.0
        for (step in -20..20) {
            val x = floatArrayOf(step * 0.5f)
            val expected = net.predict(doubleArrayOf(x[0].toDouble()))[0]
            worst = maxOf(worst, abs(expected - weights.evaluate(x)[0]))
        }
        assertTrue(worst < 1e-5, "leaky slope disagrees with Smile by $worst")
    }

    @Test
    fun anActivationWithNoGpuTranslationIsRefusedAtExportRatherThanDowngraded() {
        smile.math.MathEx.setSeed(1)
        // `linear` exists on both sides; the failure has to come from a name neither knows, and it
        // has to happen at export, because a shader that silently became linear would train, run,
        // and be wrong.
        assertFailsWith<IllegalArgumentException> { MlpActivation.of("gelu") }
    }

    @Test
    fun theEmittedGlslDeclaresTheRightWidthsAndOffsets() {
        // Not a compile check -- `NeuralFieldParitySmoke` is that, and it needs a GPU. This is the
        // cheap half: that the generator wrote the same numbers `weightOffset` reports.
        val weights = MlpWeights(
            inputs = 3,
            outputs = 2,
            hidden = intArrayOf(4),
            activation = MlpActivation.LEAKY,
            flat = FloatArray(26),
        )
        val source = weights.glsl("probe")
        assertTrue("layout(std430, buffer_reference) buffer probeWeights" in source, source)
        assertTrue("void probe(probeWeights nw, float x[3], out float y[2])" in source, source)
        assertTrue("float h0[4];" in source, source)
        assertTrue("s += nw.w[0 + o * 3 + i] * x[i];" in source, source)
        assertTrue("float s = nw.w[12 + o];" in source, source)
        assertTrue("s += nw.w[16 + o * 4 + i] * h0[i];" in source, source)
        assertTrue("float s = nw.w[24 + o];" in source, source)
        // The output layer is linear whatever the hidden activation is, and writing an activation
        // there would bound a velocity that has no business being bounded.
        assertTrue("y[o] = s;" in source, source)
        assertEquals(1, Regex("max\\(s, 0\\.01 \\* s\\)").findAll(source).count(), source)
    }

    @Test
    fun aFlowMatchFieldHandsBackANetworkOfTheShapeItsInputsImply() {
        // The one connection to the thing this is all for: a trained velocity field is a network
        // that a shader can be handed. Its input width is the contract -- position, then raw t,
        // then two features per octave.
        val target = (0 until 64).map { vec(kotlin.math.cos(it * 0.1), kotlin.math.sin(it * 0.1), 0.0) }
        val field = flowMatch(target, options = FlowMatchOptions(steps = 50, timeFeatures = 4, seed = 5))
        val network = field.network
        assertEquals(2 + 1 + 2 * 4, network.inputs)
        assertEquals(2, network.outputs)
        assertEquals(field.parameterCount, network.parameterCount)
        assertEquals(MlpActivation.LEAKY, network.activation)
    }
}
