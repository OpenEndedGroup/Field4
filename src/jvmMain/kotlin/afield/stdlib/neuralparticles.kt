@file:afield.CompletionCategory("Machine learning")

package afield.stdlib

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tanh

/**
 * Taking a small trained network to the GPU: [MlpWeights] is a flat, layer-major snapshot of a
 * multilayer perceptron's weights, and [MlpWeights.glsl] emits a GLSL function that evaluates the
 * same network out of a storage buffer.
 *
 * What that buys is a particle whose update rule was *trained* rather than written. `flowMatch`
 * (see `flowmatching.kt`) already fits a velocity field `v(x, t)` in a couple of seconds of pure
 * Kotlin; a compute shader stepping a buffer of particles can evaluate that field per particle per
 * frame, provided the two sides agree about what the network *is*. This file is that agreement,
 * and `afield.examples.four.NeuralFieldParitySmoke` measures that it holds on real hardware.
 *
 * Nothing here is particle-specific: a net that takes a position and returns a velocity, a net
 * that takes a neighbour offset and returns an interaction term, and a net that takes anything and
 * returns anything are all the same object. The particle system is what consumes it.
 *
 * #### Two comparisons, not one
 *
 * There are two distinct errors between "the network Smile trained" and "the network the GPU
 * runs", they have very different sizes, and conflating them makes the unavoidable one look like
 * a bug.
 *
 * - **Precision.** Smile evaluates in `Double`; a shader evaluates in `float`. Agreement is
 *   therefore limited to fp32 by construction — a few parts in a million, relative, and rather
 *   more for an output that is a cancelling sum. `MlpWeights.evaluate` is the fp32 reference *in
 *   Kotlin*, so this error can be measured on its own with no GPU present at all.
 * - **Transcription.** The order of the layers, the orientation of each weight matrix, the slope
 *   of the leaky ReLU, whether the output layer has an activation. Every one of these is either
 *   exactly right or grossly wrong, and a grossly wrong one still produces smooth, plausible,
 *   entirely fictional output.
 *
 * So the GPU is compared against `MlpWeights.evaluate`, fp32 against fp32, where the only
 * remaining freedom is the order the hardware chooses to accumulate a dot product in; and
 * `evaluate` is compared against Smile separately. A single comparison against `Double` would
 * have to be loose enough to absorb the precision gap, which is a tolerance wide enough to hide a
 * transposed matrix in a small network.
 *
 * #### The weight layout
 *
 * `MlpWeights.flat` runs layer by layer from the first hidden layer to the output layer. Each
 * layer contributes its weight matrix, row-major with one row per *output* neuron, then its bias
 * vector. So for a layer with `n` outputs over `p` inputs, the weight from input `i` to output `o`
 * is at `weightOffset(layer) + o * p + i`, and its bias is at `biasOffset(layer) + o`. The input
 * layer contributes nothing — it holds no weights.
 *
 * This is the layout a shader wants to read, and it is also the layout Smile happens to store.
 * The two are not relied on to coincide: `VectorMLP.exportWeights` in `flowmatching.kt` reads
 * element by element through `DenseMatrix.get(row, column)` rather than copying memory, so a
 * change of storage order upstream cannot silently transpose the network.
 */

/**
 * A flat, layer-major snapshot of a trained multilayer perceptron: the layer widths, the hidden
 * activation, and one `FloatArray` holding every weight and bias.
 *
 * `VectorMLP.exportWeights` in `flowmatching.kt` builds one from a Smile network.
 * `MlpWeights.glsl` turns one into a GLSL function that reads the same array out of a storage
 * buffer, and [evaluate] is the fp32 Kotlin reference that GLSL is held to. The layout of [flat]
 * is described in this file's overview above, and it is addressable rather than opaque:
 * [weightOffset] and [biasOffset] give the start of any layer's matrix and bias vector.
 *
 * The constructor checks the widths against `flat.size`, so an export that is truncated or built
 * for a different shape fails here rather than on the GPU, where the symptom is smooth, plausible
 * output from the wrong network.
 */
class MlpWeights(
    /** Width of the input vector. */
    val inputs: Int,
    /** Width of the output vector. */
    val outputs: Int,
    /** Hidden layer widths, in order. */
    val hidden: IntArray,
    /** The activation applied after every hidden layer. The output layer is always linear. */
    val activation: MlpActivation,
    /** Every weight and bias, laid out as described above. */
    val flat: FloatArray,
) {
    /** The width of each weight-bearing layer's output: the hidden widths, then [outputs]. */
    val layerWidths: IntArray = hidden + outputs

    /** The width of each weight-bearing layer's input: [inputs], then the hidden widths. */
    val layerInputs: IntArray = intArrayOf(inputs) + hidden

    /** How many weight-bearing layers there are — one more than the number of hidden layers. */
    val layerCount: Int get() = layerWidths.size

    init {
        require(inputs > 0) { "a network needs at least one input, got $inputs" }
        require(outputs > 0) { "a network needs at least one output, got $outputs" }
        require(hidden.all { it > 0 }) { "every hidden layer needs at least one neuron, got ${hidden.toList()}" }
        require(flat.size == floatCount(inputs, layerWidths)) {
            "expected ${floatCount(inputs, layerWidths)} weights for a " +
                "${inputs}->${hidden.joinToString("->")}->$outputs network, got ${flat.size}"
        }
    }

    /** Index into [flat] of the first weight of [layer], which is `layer`'s row 0, column 0. */
    fun weightOffset(layer: Int): Int {
        require(layer in 0 until layerCount) { "no layer $layer in a $layerCount-layer network" }
        var at = 0
        for (l in 0 until layer) at += layerWidths[l] * layerInputs[l] + layerWidths[l]
        return at
    }

    /** Index into [flat] of the first bias of [layer]. */
    fun biasOffset(layer: Int): Int = weightOffset(layer) + layerWidths[layer] * layerInputs[layer]

    /**
     * The network's output for [x], in `Float`, accumulated in exactly the order [glsl] accumulates
     * it — bias first, then one multiply-add per input in index order.
     *
     * This is the reference the GPU is held to. Matching the accumulation order does not make the
     * two bit-identical, because the hardware is free to contract a multiply and an add into an FMA
     * and to reassociate, but it removes the one source of disagreement that is under our control
     * and leaves a residual that is a property of the machine rather than of this code.
     *
     * Allocates two scratch rows per call; this is a reference implementation and a test oracle,
     * not the thing you evaluate a million times.
     */
    fun evaluate(x: FloatArray, into: FloatArray = FloatArray(outputs)): FloatArray {
        require(x.size == inputs) { "expected an input of width $inputs, got ${x.size}" }
        require(into.size == outputs) { "expected an output of width $outputs, got ${into.size}" }

        var source = x
        for (layer in 0 until layerCount) {
            val n = layerWidths[layer]
            val p = layerInputs[layer]
            val w = weightOffset(layer)
            val b = biasOffset(layer)
            val last = layer == layerCount - 1
            val destination = if (last) into else FloatArray(n)
            for (o in 0 until n) {
                var sum = flat[b + o]
                for (i in 0 until p) sum += flat[w + o * p + i] * source[i]
                destination[o] = if (last) sum else activation.apply(sum)
            }
            source = destination
        }
        return into
    }

    /** How many weights and biases this network is, which is `flat.size`. */
    val parameterCount: Int get() = flat.size

    override fun toString(): String =
        "MlpWeights($inputs->${hidden.joinToString("->")}->$outputs, $activation, $parameterCount parameters)"

    companion object {
        /** The number of floats a network of these widths needs. */
        fun floatCount(inputs: Int, layerWidths: IntArray): Int {
            var total = 0
            var below = inputs
            for (width in layerWidths) {
                total += below * width + width
                below = width
            }
            return total
        }
    }
}

/**
 * Two networks of the same shape, averaged weight by weight — `(1-t)·this + t·other`.
 *
 * Useful because the result is *not* a blend of the two behaviours in any simple sense: a network
 * is nonlinear in its weights, so the midpoint of two trained networks is a third function that
 * was never trained on anything, and which in general does something neither endpoint does. For
 * two velocity fields that is the difference between morphing a shape and finding a shape between
 * two shapes — see `public/docs/neural_particles.md`.
 *
 * **This only means something if the two networks share an initialisation.** Hidden units are
 * exchangeable: two runs from different random starts represent similar functions through
 * different permutations of the same machinery, and averaging those is averaging two unrelated
 * parameter vectors. Train both with the same `seed`. (Measured, that argument is weaker than it
 * sounds for two fields fitted to a shared source — see `WeightInterpolationTest` — but it is the
 * assumption to design against rather than one to rely on being forgiving.)
 */
fun MlpWeights.blendWith(other: MlpWeights, t: Float): MlpWeights {
    require(inputs == other.inputs && outputs == other.outputs && hidden.contentEquals(other.hidden)) {
        "only networks of the same shape can be blended: $this against $other"
    }
    require(activation == other.activation) {
        "these networks use different activations ($activation and ${other.activation}), so the " +
            "blend is not a network of either kind"
    }
    return MlpWeights(
        inputs, outputs, hidden, activation,
        FloatArray(flat.size) { flat[it] * (1 - t) + other.flat[it] * t },
    )
}

/**
 * [MlpWeights.blendWith] for a whole field, carrying the training frame and source with it.
 *
 * The blended field is evaluated in **this** field's frame, so the two must agree about what
 * their coordinates mean — otherwise `t = 1` would be the other network's weights read in this
 * one's coordinate system, which is a different field from the other one and the difference would
 * look exactly like an interesting result. That is checked rather than assumed.
 *
 * The practical consequence for a caller: train both fields from the *same* source cloud. That
 * gives them the same normalisation, and it is also the only way a particle has one start point
 * and two destinations, which is what makes a sweep over `t` a motion rather than a cut.
 */
fun FlowMatchExport.blendWith(other: FlowMatchExport, t: Float): FlowMatchExport {
    require(dimension == other.dimension && timeFeatures == other.timeFeatures) {
        "these fields are not the same kind: ${dimension}D/$timeFeatures against " +
            "${other.dimension}D/${other.timeFeatures}"
    }
    val centreGap = (0 until dimension).sumOf {
        val d = (center[it] - other.center[it]).toDouble(); d * d
    }
    require(kotlin.math.sqrt(centreGap) < 1e-3 * scale) {
        "these fields were normalised about different centres (${center.toList()} against " +
            "${other.center.toList()}), so blending their weights mixes two coordinate systems — " +
            "train both from the same source cloud"
    }
    require(kotlin.math.abs(scale - other.scale) < 1e-3 * scale) {
        "these fields were normalised at different scales ($scale against ${other.scale}), so " +
            "blending their weights mixes two coordinate systems — train both from the same " +
            "source cloud"
    }
    return FlowMatchExport(
        network.blendWith(other.network, t), dimension, timeFeatures, center, scale, source,
    )
}

/**
 * The hidden activations this file can evaluate on both sides of the CPU/GPU line.
 *
 * Smile names these with strings; [of] is the translation, and it is deliberately strict — an
 * unrecognised activation must fail at export rather than quietly become a linear layer.
 */
enum class MlpActivation(
    /** The name `smile.base.mlp.Layer.builder` knows this activation by. */
    val smileName: String,
) {
    /**
     * `max(x, 0.01 * x)`.
     *
     * The slope is 0.01 because that is what Smile's own `ActivationFunction.leaky()` uses and
     * what `Layer.builder(name, width, dropout, Double.NaN)` — the call `VectorMLP` makes — falls
     * back to when the slope argument is `NaN`. Read out of the bytecode rather than the
     * documentation, because it is a default and defaults are what change.
     */
    LEAKY("leaky"),

    /** `max(x, 0)`. */
    RELU("relu"),

    /** `tanh(x)`. */
    TANH("tanh"),

    /** `1 / (1 + exp(-x))`. */
    SIGMOID("sigmoid"),

    /** The identity — a network of these can only fit an affine function, whatever its depth. */
    LINEAR("linear");

    /** This activation applied to one value, in `Float`. */
    fun apply(v: Float): Float = when (this) {
        LEAKY -> max(v, LEAKY_SLOPE * v)
        RELU -> max(v, 0f)
        TANH -> tanh(v)
        SIGMOID -> 1f / (1f + exp(-v))
        LINEAR -> v
    }

    /**
     * A GLSL expression for this activation applied to [argument], which must already be
     * parenthesised or a single token.
     *
     * `LEAKY` is written as a `max` rather than a branch so that it is the same expression on both
     * sides: `max(v, 0.01 * v)` agrees with `v < 0 ? 0.01 * v : v` for every finite `v`, because
     * `0.01 * v` is the larger of the two exactly when `v` is negative.
     */
    internal fun glslExpression(argument: String): String = when (this) {
        LEAKY -> "max($argument, $LEAKY_SLOPE * $argument)"
        RELU -> "max($argument, 0.0)"
        TANH -> "tanh($argument)"
        SIGMOID -> "(1.0 / (1.0 + exp(-($argument))))"
        LINEAR -> argument
    }

    companion object {
        /** The negative-side slope of [LEAKY], matching Smile's default. */
        const val LEAKY_SLOPE = 0.01f

        /** The activation Smile calls [smileName], or a failure naming the ones that exist. */
        fun of(smileName: String): MlpActivation =
            entries.firstOrNull { it.smileName == smileName.lowercase() }
                ?: throw IllegalArgumentException(
                    "no GPU translation for the activation '$smileName'; " +
                        "this file knows ${entries.joinToString(", ") { it.smileName }}"
                )
    }
}

/**
 * GLSL that evaluates this network out of a storage buffer, as a `buffer_reference` type and a
 * function named [name].
 *
 * The emitted block declares `buffer <name>Weights { float w[]; }` and
 * `void <name>(<name>Weights nw, float x[inputs], out float y[outputs])`. Bind the buffer the way
 * `alife_dla.kt` binds its particle buffer — as a pointer in the push-constant block — and the
 * weights can be swapped between frames without recompiling anything, which is the whole reason
 * they live in a buffer rather than being baked in as literals.
 *
 * **Place the result immediately after `#version`**: it emits an `#extension` directive, and
 * glslang wants those before the first real declaration.
 *
 * The layer loop is unrolled and the dot products are not. Every offset and every width is a
 * literal, so the compiler sees a fixed-trip loop over a buffer with no dynamic indexing of
 * anything it cannot resolve; and the emitted source stays proportional to the *depth* of the
 * network rather than to its parameter count, which keeps a 5,000-parameter net readable.
 */
fun MlpWeights.glsl(name: String = "neuralField"): String {
    val out = StringBuilder()
    out.append("#extension GL_EXT_buffer_reference : require\n\n")
    out.append("// $this\n")
    out.append("// Generated by MlpWeights.glsl(); the weight layout is documented on MlpWeights.\n")
    out.append("layout(std430, buffer_reference) buffer ${name}Weights { float w[]; };\n\n")
    out.append("void $name(${name}Weights nw, float x[$inputs], out float y[$outputs]) {\n")

    for (layer in 0 until layerCount) {
        val n = layerWidths[layer]
        val p = layerInputs[layer]
        val w = weightOffset(layer)
        val b = biasOffset(layer)
        val last = layer == layerCount - 1
        val source = if (layer == 0) "x" else "h${layer - 1}"
        val destination = if (last) "y" else "h$layer"

        if (!last) out.append("    float $destination[$n];\n")
        out.append("    // layer $layer: $p -> $n${if (last) ", linear" else ", ${activation.smileName}"}\n")
        out.append("    for (int o = 0; o < $n; ++o) {\n")
        out.append("        float s = nw.w[$b + o];\n")
        out.append("        for (int i = 0; i < $p; ++i) s += nw.w[$w + o * $p + i] * $source[i];\n")
        out.append("        $destination[o] = ${if (last) "s" else activation.glslExpression("s")};\n")
        out.append("    }\n")
    }

    out.append("}\n")
    return out.toString()
}

/**
 * A trained velocity field packaged for a shader: the network, plus the three things around it
 * that decide what its numbers *mean*.
 *
 * Built by `FlowMatchField.export()`. [glsl] turns the whole thing into a `neuralVelocity`
 * function that takes a position in *your* coordinates and a time in `0..1`, and returns a
 * velocity in your coordinates — the frame conversion included, because the one guaranteed way to
 * get this wrong is to hand a shader the weights and remember the frame later.
 */
class FlowMatchExport(
    /** The trained network. Its inputs are [dimension] positions followed by the time encoding. */
    val network: MlpWeights,
    /** 2 or 3 — how many position components the network takes. */
    val dimension: Int,
    /** How many `sin`/`cos` octaves of `t` follow raw `t` in the input vector. */
    val timeFeatures: Int,
    /** The centre of the frame training happened in, in input coordinates. */
    val center: FloatArray,
    /** The isotropic scale of that frame. One number, not one per axis. */
    val scale: Float,
    /**
     * The cloud the field was trained to start from, interleaved by [dimension] and already in
     * the normalised frame — or null if it was trained from a standard Gaussian.
     *
     * A particle system has to seed from *this*, not from a Gaussian, whenever it is present. A
     * field trained with `mixtureSource` has a source made of one ellipse per component, and
     * starting its particles in a single blob instead asks it a question it was never shown: the
     * velocity it returns out there is extrapolation, and the cloud goes somewhere arbitrary that
     * still looks like a plausible flow.
     */
    val source: FloatArray? = null,
) {
    init {
        require(dimension == 2 || dimension == 3) { "a flow field is 2D or 3D, not ${dimension}D" }
        require(center.size == dimension) { "a ${dimension}D frame needs a ${dimension}D centre" }
        require(scale > 0f) { "the frame's scale must be positive, got $scale" }
        require(network.inputs == dimension + 1 + 2 * timeFeatures) {
            "a ${dimension}D field with $timeFeatures time features needs a " +
                "${dimension + 1 + 2 * timeFeatures}-wide input, the network takes ${network.inputs}"
        }
        require(network.outputs == dimension) {
            "a ${dimension}D field returns a ${dimension}D velocity, the network returns ${network.outputs}"
        }
        require(source == null || source.size % dimension == 0) {
            "a ${dimension}D source cloud needs a multiple of $dimension floats, got ${source!!.size}"
        }
    }

    /** How many points are in [source], or 0 if the field starts from a Gaussian. */
    val sourceCount: Int get() = (source?.size ?: 0) / dimension

    override fun toString(): String =
        "FlowMatchExport(${dimension}D, $timeFeatures time features, scale $scale, $network)"
}

/**
 * GLSL for the whole field: the network from [MlpWeights.glsl], the time encoding, and a
 * `neuralVelocity` that converts in and out of the training frame.
 *
 * Emits, for `name = "neuralField"`:
 *
 * - `buffer neuralFieldWeights { float w[]; }` — bind this to the weight buffer.
 * - `vecN neuralVelocity(neuralFieldWeights nw, vecN position, float t)`, in input coordinates.
 * - `vecN neuralVelocityNormalised(...)`, for a caller integrating in the training frame — which
 *   is the cheaper thing to do if you are only ever going to draw the result, since the
 *   conversion is then one multiply at the end rather than two per step.
 *
 * The time encoding is unrolled to match `flowmatching.kt`'s `encodeTime` exactly: raw `t` at
 * index `dimension`, then `sin(2^k·π·t)` and `cos(2^k·π·t)` in pairs. An off-by-one here is the
 * quietest possible bug — the field still varies smoothly with `t`, just not the way it was
 * trained to — so the sequence is generated from the same expression on both sides rather than
 * transcribed.
 *
 * **Place the result immediately after `#version`**, as with [MlpWeights.glsl]: it carries an
 * `#extension` directive.
 */
fun FlowMatchExport.glsl(name: String = "neuralField"): String {
    val vec = "vec$dimension"
    val out = StringBuilder()
    out.append(network.glsl(name))
    out.append("\n// $this\n")

    out.append("$vec ${name}VelocityNormalised(${name}Weights nw, $vec p, float t) {\n")
    out.append("    float xin[${network.inputs}];\n")
    for (d in 0 until dimension) out.append("    xin[$d] = p[$d];\n")
    out.append("    xin[$dimension] = t;\n")
    for (k in 0 until timeFeatures) {
        // The same `PI * (1 shl k)` the trainer used, evaluated here so the two cannot drift.
        val omega = Math.PI * (1 shl k)
        out.append("    xin[${dimension + 1 + 2 * k}] = sin($omega * t);\n")
        out.append("    xin[${dimension + 2 + 2 * k}] = cos($omega * t);\n")
    }
    out.append("    float yout[$dimension];\n")
    out.append("    $name(nw, xin, yout);\n")
    out.append("    return $vec(${(0 until dimension).joinToString(", ") { "yout[$it]" }});\n")
    out.append("}\n\n")

    val centre = "$vec(${center.joinToString(", ") { formatFloat(it) }})"
    out.append("const $vec ${name}Center = $centre;\n")
    out.append("const float ${name}Scale = ${formatFloat(scale)};\n\n")
    out.append("$vec ${name}Velocity(${name}Weights nw, $vec position, float t) {\n")
    out.append("    $vec p = (position - ${name}Center) / ${name}Scale;\n")
    out.append("    return ${name}VelocityNormalised(nw, p, t) * ${name}Scale;\n")
    out.append("}\n")
    return out.toString()
}

/** A float as GLSL source, always with a decimal point so it cannot be read as an int. */
private fun formatFloat(v: Float): String {
    val s = v.toString()
    return if ('.' in s || 'e' in s || 'E' in s || "Infinity" in s || "NaN" in s) s else "$s.0"
}

/**
 * The largest relative disagreement between two evaluations of the same network, and the input it
 * happened at — the statistic both halves of the parity test report.
 *
 * Relative to the *magnitude of the expected output* rather than componentwise, because a velocity
 * field's components pass through zero and a componentwise relative error is unbounded there for
 * reasons that have nothing to do with the thing being measured.
 */
data class MlpAgreement(
    /** The largest relative disagreement seen. */
    val worstRelative: Double,
    /** The largest absolute disagreement seen, in output units. */
    val worstAbsolute: Double,
    /** The input at which [worstRelative] occurred. */
    val worstAt: FloatArray,
    /** How many outputs were compared in total. */
    val comparisons: Int,
) {
    override fun toString(): String =
        "worst relative %.3g (absolute %.3g) over %d comparisons".format(worstRelative, worstAbsolute, comparisons)

    // Comparing two output vectors into a running worst, which is how both halves accumulate.
    companion object {
        /** The neutral element: nothing compared yet. */
        val nothing = MlpAgreement(0.0, 0.0, FloatArray(0), 0)

        /** [worst] widened to include the disagreement between [expected] and [actual] at [at]. */
        fun accumulate(
            worst: MlpAgreement,
            at: FloatArray,
            expected: FloatArray,
            actual: FloatArray,
        ): MlpAgreement {
            require(expected.size == actual.size) {
                "cannot compare a ${expected.size}-vector against a ${actual.size}-vector"
            }
            var magnitude = 0.0
            for (v in expected) magnitude += v.toDouble() * v
            val scale = max(kotlin.math.sqrt(magnitude), 1e-6)

            var absolute = 0.0
            for (i in expected.indices) absolute = max(absolute, abs(expected[i].toDouble() - actual[i]))
            val relative = absolute / scale

            return if (relative > worst.worstRelative) {
                MlpAgreement(relative, max(absolute, worst.worstAbsolute), at.copyOf(), worst.comparisons + expected.size)
            } else {
                MlpAgreement(
                    worst.worstRelative,
                    max(absolute, worst.worstAbsolute),
                    worst.worstAt,
                    worst.comparisons + expected.size
                )
            }
        }
    }
}
