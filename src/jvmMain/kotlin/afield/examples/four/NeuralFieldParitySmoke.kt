package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.defaultBuffer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.Parameters
import afield.four.modules.ShaderKind
import afield.four.modules.pointer
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.FlowMatchOptions
import afield.stdlib.MlpActivation
import afield.stdlib.MlpAgreement
import afield.stdlib.MlpWeights
import afield.stdlib.VectorMLP
import afield.stdlib.flowMatch
import afield.stdlib.glsl
import afield.stdlib.preprocess
import afield.stdlib.vec
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.neural-field-parity.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.NeuralFieldParitySmoke
tags: [smoke, four, gpu, compute, neural, mlp, flow-matching]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "neural field parity tests PASSED"
*/
/**
 * Step one of the neural-particle pilot: that a network trained on the CPU is the *same network*
 * when a compute shader evaluates it.
 *
 * Everything downstream — a velocity field driving a buffer of particles, a learned interaction
 * summed over neighbours — is built on `MlpWeights.glsl` emitting an unrolled forward pass that
 * agrees with the weights Smile trained. That agreement is the one thing in the pilot that can be
 * wrong without looking wrong: a transposed layer, an off-by-one bias offset or a leaky slope of
 * 0.2 instead of 0.01 all produce a shader that compiles, runs at full speed, and generates a
 * smooth, plausible, entirely fictional field. There is no picture of a particle system that shows
 * you this.
 *
 * ### Why this needs a GPU and `MlpWeightsTest` does not
 *
 * `MlpWeightsTest` already checks that the exported weights reproduce Smile's forward pass, and it
 * checks the *text* of the emitted GLSL against the same offset arithmetic that generated it. What
 * it cannot check is whether that text compiles, whether the driver indexes a `float[]` inside a
 * `buffer_reference` block the way std430 says it should, and whether a `float x[11]` function
 * parameter survives a real glslang. Those are properties of the toolchain, and the only instrument
 * that reads them is the toolchain.
 *
 * ### What it asserts
 *
 * - **The GPU agrees with `MlpWeights.evaluate`**, fp32 against fp32, over a grid of inputs. The
 *   tolerance is [PARITY_TOLERANCE], which is dot-product reassociation and FMA contraction and
 *   nothing else. It is deliberately not loose enough to absorb the fp64-to-fp32 gap — that gap is
 *   `MlpWeightsTest`'s business, measured there against Smile, and folding the two comparisons
 *   into one would give a tolerance wide enough to hide a transcription error.
 * - **A transposed network fails that tolerance by orders of magnitude.** Measured in the same
 *   run, against the same readback, and reported. Without it the first assertion is unfalsifiable:
 *   a tolerance is only meaningful next to the size of the error it is supposed to catch, and
 *   quoting that size from a comment written on a good day is not a measurement.
 * - **Every output was written.** The output buffer is prefilled with a sentinel no network can
 *   produce, and its survival anywhere is a failure. A shader whose dispatch size is wrong, or
 *   whose buffer never bound, otherwise reports perfect agreement on the samples it did reach.
 * - **Three shapes, not one.** A trained flow-matching field (the real case, 11 inputs and a
 *   couple of thousand parameters), a single-hidden-layer net, and a net whose layers are all
 *   different widths. Square layers are where a transposition hides, and equal widths are where an
 *   offset error cancels.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.neural-field-parity.smoke
 * ```
 *
 * Needs a GPU and a window: `afield.four` has no headless mode, so a compute-only dispatch still
 * needs a `Window` for the device to exist.
 */
object NeuralFieldParitySmoke {

    /**
     * The largest relative disagreement between the GPU and [MlpWeights.evaluate] that is not a
     * bug.
     *
     * Both sides are fp32 and accumulate in the same order, so the only freedom left is the
     * hardware's: contracting a multiply and an add into an FMA, and reassociating a dot product
     * across lanes. Measured on this machine (M1 Max, MoltenVK), the three cases come in at
     * 5.8e-06, 3.5e-07 and 7.4e-06 — the largest being the deepest network, whose output is a sum
     * of five tanh terms and therefore small enough that a fixed absolute error reads as a
     * larger relative one.
     *
     * Set an order of magnitude above that rather than just above it. The instinct is to pin the
     * tolerance to the observed worst, and it is wrong here: the *discrimination* this test needs
     * does not come from the tolerance being tight — a wrong network misses by a factor of 1e7,
     * so anywhere in a six-decade range would catch it — and a tolerance with 36% headroom buys
     * no sensitivity while guaranteeing a flake on the first driver that fuses differently.
     */
    private const val PARITY_TOLERANCE = 1e-4

    /**
     * A value the output buffer is prefilled with, and which no correct run can leave behind.
     *
     * Not NaN: a NaN sentinel propagates into the agreement statistic as a NaN, and `NaN > x` is
     * false, so a comparison that silently skipped every sample would pass. A large finite number
     * fails loudly instead.
     */
    private const val UNWRITTEN = -98765.0f

    /** Inputs per dispatch. A multiple of the local size, so the shader needs no bounds check. */
    private const val SAMPLES = 4096

    /** Compute local size, matched by the `layout(local_size_x = ...)` in the shader. */
    private const val LOCAL_SIZE = 64

    /** One network to check, with the inputs to check it at. */
    private class Case(val name: String, val weights: MlpWeights, val samples: Array<FloatArray>)

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("Neural Field Parity Smoke")
        val renderer = Renderer(window)

        try {
            var failures = 0
            for (case in cases()) {
                println("--- ${case.name}: ${case.weights}")
                failures += check(renderer, window, case)
            }

            if (failures > 0) {
                println("neural field parity tests FAILED ($failures)")
                exitProcess(1)
            }
            println("neural field parity tests PASSED")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }

    /**
     * The three networks, and the inputs each is evaluated at.
     *
     * The trained one is the case that matters and the other two are the emitter's edge cases, so
     * all three run rather than one standing in for the others.
     */
    private fun cases(): List<Case> {
        val random = Random(20260919)

        // A real trained velocity field. Its inputs are a normalised 2D position followed by raw t
        // and four sin/cos octaves of it, so the samples are drawn the way the field will actually
        // be read: positions over the region it was trained on, times over 0..1.
        val ring = (0 until 256).map { vec(cos(it * 0.0245), sin(it * 0.0245), 0.0) }
        val field = flowMatch(ring, options = FlowMatchOptions(steps = 1500, seed = 11))
        val trained = field.network
        val trainedSamples = Array(SAMPLES) {
            val x = FloatArray(trained.inputs)
            x[0] = (random.nextDouble() * 4 - 2).toFloat()
            x[1] = (random.nextDouble() * 4 - 2).toFloat()
            val t = random.nextDouble()
            x[2] = t.toFloat()
            for (k in 0 until 4) {
                val omega = Math.PI * (1 shl k)
                x[3 + 2 * k] = sin(omega * t).toFloat()
                x[4 + 2 * k] = cos(omega * t).toFloat()
            }
            x
        }

        // One hidden layer: the emitter's shortest path through its layer loop, and the only shape
        // where the first weight-bearing layer is also the last.
        smile.math.MathEx.setSeed(3)
        val shallow = VectorMLP(inputs = 4, outputs = 3, hidden = intArrayOf(9), activation = "leaky")
            .exportWeights()

        // Every layer a different width, none of them square, and a tanh hidden activation. An
        // offset error that happens to cancel on equal widths cannot cancel here.
        smile.math.MathEx.setSeed(4)
        val ragged = VectorMLP(inputs = 6, outputs = 2, hidden = intArrayOf(13, 7, 5), activation = "tanh")
            .exportWeights()

        return listOf(
            Case("trained flow-matching field", trained, trainedSamples),
            Case("single hidden layer, leaky", shallow, randomSamples(shallow, random)),
            Case("ragged widths, tanh", ragged, randomSamples(ragged, random)),
        )
    }

    private fun randomSamples(weights: MlpWeights, random: Random) =
        Array(SAMPLES) { FloatArray(weights.inputs) { (random.nextDouble() * 4 - 2).toFloat() } }

    /** Runs one case on the GPU and reports; returns 1 if it failed, 0 if it passed. */
    private fun check(renderer: Renderer, window: Window, case: Case): Int {
        val weights = case.weights
        val readback = dispatch(renderer, window, weights, case.samples)

        val unwritten = readback.count { it == UNWRITTEN }
        if (unwritten > 0) {
            println("  FAIL: $unwritten of ${readback.size} outputs were never written by the shader")
            return 1
        }

        // The correct comparison, and the falsification of it, off the same readback.
        val agreement = compare(weights, case.samples, readback)
        val transposedAgreement = compare(transposed(weights), case.samples, readback)

        println("  GPU vs MlpWeights.evaluate: $agreement")
        println("  GPU vs a transposed network: $transposedAgreement")

        if (agreement.worstRelative >= PARITY_TOLERANCE) {
            println("  FAIL: worst relative ${agreement.worstRelative} exceeds $PARITY_TOLERANCE")
            println("        at input ${agreement.worstAt.joinToString(", ")}")
            return 1
        }
        // The tolerance is only worth stating if a wrong network would miss it. Insisting on a
        // thousandfold margin rather than merely "larger" keeps this from passing on a network so
        // nearly symmetric that its transpose is almost itself.
        if (transposedAgreement.worstRelative <= agreement.worstRelative * 1000) {
            println(
                "  FAIL: a transposed network is not distinguishable — " +
                    "${transposedAgreement.worstRelative} against ${agreement.worstRelative}"
            )
            return 1
        }
        println("  ok (transposed is ${(transposedAgreement.worstRelative / agreement.worstRelative).toInt()}x worse)")
        return 0
    }

    /** The worst disagreement between [weights] evaluated on the CPU and a flat GPU [readback]. */
    private fun compare(weights: MlpWeights, samples: Array<FloatArray>, readback: FloatArray): MlpAgreement {
        var worst = MlpAgreement.nothing
        val actual = FloatArray(weights.outputs)
        for (s in samples.indices) {
            for (o in 0 until weights.outputs) actual[o] = readback[s * weights.outputs + o]
            worst = MlpAgreement.accumulate(worst, samples[s], weights.evaluate(samples[s]), actual)
        }
        return worst
    }

    /**
     * The same network with every layer's weight matrix transposed where it can be — the wrong
     * network this test's tolerance has to be able to see.
     *
     * Only square layers can actually be transposed in place, so a network with none is left
     * alone in those layers and differs only where it can; that is still enough, because a network
     * that agreed with its own partial transpose would be one whose weights were symmetric, and
     * the run reports the ratio so a degenerate case shows up as a small one rather than as a pass.
     */
    private fun transposed(weights: MlpWeights): MlpWeights {
        val flat = weights.flat.copyOf()
        for (layer in 0 until weights.layerCount) {
            val n = weights.layerWidths[layer]
            val p = weights.layerInputs[layer]
            if (n != p) {
                // Not square: reverse each row instead, which is an equally wrong network of the
                // right shape. The point is a wrong network, not specifically a transposed one.
                val w = weights.weightOffset(layer)
                for (o in 0 until n) for (i in 0 until p / 2) {
                    val a = w + o * p + i
                    val b = w + o * p + (p - 1 - i)
                    val tmp = flat[a]; flat[a] = flat[b]; flat[b] = tmp
                }
            } else {
                val w = weights.weightOffset(layer)
                for (o in 0 until n) for (i in 0 until o) {
                    val a = w + o * p + i
                    val b = w + i * p + o
                    val tmp = flat[a]; flat[a] = flat[b]; flat[b] = tmp
                }
            }
        }
        return MlpWeights(weights.inputs, weights.outputs, weights.hidden, weights.activation, flat)
    }

    /**
     * Builds a compute module around [weights]'s generated GLSL, dispatches it once over
     * [samples], and returns the output buffer.
     *
     * A fresh module per case: the shader's array widths are compiled in, so two networks of
     * different shapes are two shaders however much machinery they share.
     */
    private fun dispatch(
        renderer: Renderer,
        window: Window,
        weights: MlpWeights,
        samples: Array<FloatArray>,
    ): FloatArray {
        val box = Box("neural-field-parity").apply { name = "neural-field-parity" }
        val source = preprocess(box, shaderFor(weights), ShaderKind.COMPUTE_SHADER)

        val inputBuffer = buffer(renderer, "neuralParityIn", samples.size * weights.inputs)
        val outputBuffer = buffer(renderer, "neuralParityOut", samples.size * weights.outputs)
        val weightBuffer = buffer(renderer, "neuralParityWeights", weights.parameterCount)

        inputBuffer.get(write = true).mapAndWrite { for (s in samples) put(s) }
        weightBuffer.get(write = true).mapAndWrite { put(weights.flat) }
        outputBuffer.get(write = true).mapAndWrite {
            repeat(samples.size * weights.outputs) { put(UNWRITTEN) }
        }

        val module = ComputeModule(
            Parameters(renderer = renderer, compute = source, name = "neural field parity")
        ).pointer()
        val mesh = ComputeMesh(renderer, module).apply {
            x = samples.size / LOCAL_SIZE
            y = 1
            z = 1
            push.bind("samplesIn") { inputBuffer }
            push.bind("samplesOut") { outputBuffer }
            push.bind("weights") { weightBuffer }
        }
        module += "dispatch" to mesh
        renderer += "neuralFieldParity" to module

        // Two frames, not one: the first is where the descriptor set and the buffer uploads are
        // established, and reading after a single render has read an unwritten buffer before.
        repeat(2) {
            window.pollEvents()
            renderer.render()
        }
        renderer.windowContext().computeFences.forEach { it.fenceWait() }

        val out = FloatArray(samples.size * weights.outputs)
        outputBuffer.get().mapAndRead { get(out) }

        // Each case is its own module; leaving the previous one in the compute list would make
        // every later case dispatch it too, into a buffer nothing reads.
        renderer.computeList.modules.remove("neuralFieldParity")
        return out
    }

    /** A host-visible, host-cached storage buffer of [floats] floats. */
    private fun buffer(renderer: Renderer, name: String, floats: Int): FrameBufferGroup =
        defaultBuffer(name = name) {
            size = floats.floats
            // Host-cached, per the rule in `field_probes.md`: a buffer the CPU reads back must ask
            // for HOST_CACHED or it lands in write-combined memory, where reads are pathological.
            streamReads()
        }.toBuffer(renderer)

    /**
     * The compute shader for [weights]: the generated forward pass, plus the loop that feeds it
     * one row of the input buffer per invocation.
     *
     * The generated block goes immediately after `#version` because it carries the
     * `#extension GL_EXT_buffer_reference` directive. [SAMPLES] is a multiple of [LOCAL_SIZE] so
     * there is no bounds check to get wrong — one fewer thing that can silently skip work and
     * report perfect agreement over what it did do.
     */
    private fun shaderFor(weights: MlpWeights): String = buildString {
        append("#version 450\n")
        append(weights.glsl("neuralField"))
        append(
            """

            layout(std430, buffer_reference) buffer SampleRows { float v[]; };

            layout(push_constant) uniform dat {
                SampleRows samplesIn;
                SampleRows samplesOut;
                neuralFieldWeights weights;
            } pc;

            layout(local_size_x = $LOCAL_SIZE) in;

            void main() {
                uint idx = gl_GlobalInvocationID.x;
                float x[${weights.inputs}];
                for (int i = 0; i < ${weights.inputs}; ++i) x[i] = pc.samplesIn.v[idx * ${weights.inputs}u + uint(i)];
                float y[${weights.outputs}];
                neuralField(pc.weights, x, y);
                for (int o = 0; o < ${weights.outputs}; ++o) pc.samplesOut.v[idx * ${weights.outputs}u + uint(o)] = y[o];
            }
            """.trimIndent()
        )
        append("\n")
    }
}
