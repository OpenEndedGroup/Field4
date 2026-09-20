@file:afield.CompletionCategory("Machine learning")

package afield.stdlib

import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A learned *interaction* — the tier-2 half of the neural-particle pilot, and the thing a flat
 * velocity field cannot be.
 *
 * `flowMatch` learns `v(x, t)`: where a particle should go given only where it is. That can carry
 * a cloud onto a shape, and it is completely blind to the other particles. An interaction is the
 * other kind of rule:
 *
 * ```
 * a_i = phi( v_i, SUM_j psi(x_j - x_i, v_j - v_i), MEAN_j psi(...) )
 * ```
 *
 * two small networks and a reduction over neighbours. This is the Deep Sets form, and it is the
 * right one here for a structural reason: the aggregation is permutation-invariant and takes any
 * number of neighbours, so one set of weights works for a particle with three neighbours and a
 * particle with three hundred. Classical boids is *one particular choice* of `psi` and `phi`, so
 * the family provably contains something worth finding.
 *
 * ### Why both a sum and a mean
 *
 * Reaching for one reduction is the obvious move and it cannot work. Reynolds separation is a
 * **sum** — being crowded by twenty neighbours pushes harder than being crowded by two — while
 * alignment and cohesion are **means**, since they are averages of neighbour velocity and
 * position. A mean-only aggregator cannot represent separation at any width, and a sum-only one
 * makes alignment scale with crowding. Feeding `phi` both costs `messageWidth` extra inputs and
 * removes the ceiling. [DeepSetsWeights.sumScale] keeps the summed branch in a sane range.
 *
 * ### The feature map is where the physics goes
 *
 * `psi` is not fed the raw offset. It is fed [interactionFeatures]: the offset and velocity
 * difference, plus the **unit direction scaled by a small radial basis**, `u*p` and `u*p^2`, where
 * `p` is a bounded proximity that rises as neighbours get close.
 *
 * This is the single thing that made tier 2 work, and it was found by ablation rather than
 * guessed. Fed raw Cartesian offsets, the model reproduces alignment and cohesion to a relative
 * error of **0.036** — both are plain means, and linear in the inputs — and reproduces separation
 * at **0.78**, which is barely better than predicting the average. Separation goes as `1/r` over
 * a four-hundred-fold range of `r`, and a `tanh` network cannot form the *product* of a direction
 * and a steep radial profile out of Cartesian components at any width worth paying for.
 *
 * A radial interaction is a direction times a profile — that is what "radial" means, for any such
 * law and not just this one — so giving the network `u` already multiplied by a couple of profile
 * terms is a statement about the *form* of two-body interactions rather than a transcription of
 * boids. What is left for it to learn is the profile's coefficients, the velocity dependence, and
 * how the summed and meaned branches combine, none of which is handed over.
 *
 * ### Trained by [afield.stdlib.ADTape], not by Smile
 *
 * Smile's MLP cannot express this: the gradient has to flow back through a sum over a
 * *variable-length* neighbour set, and `psi` is applied once per neighbour with shared weights.
 * `autodiff.kt` is reverse-mode over a tape, which handles it without knowing anything about
 * networks — the tape simply records whatever the forward pass did. Cost is one forward and one
 * backward sweep per batch whatever the neighbour counts are.
 *
 * ### The weights come out as [MlpWeights]
 *
 * Both halves are packaged in the same flat layout the flow-matching work already uses, so
 * `MlpWeights.glsl` emits them for the GPU unchanged and the parity machinery in
 * `four.neural-field-parity.smoke` applies to them without modification.
 */
class DeepSetsWeights(
    /** Applied per neighbour. Inputs `(dx, dy, dvx, dvy)` normalised; outputs [messageWidth]. */
    val psi: MlpWeights,
    /** Applied once. Inputs `(vx, vy, sum…, mean…)` normalised; outputs a normalised acceleration. */
    val phi: MlpWeights,
    /** How wide a single neighbour's message is. */
    val messageWidth: Int,
    /** Interaction radius, in world units. Offsets are divided by this. */
    val radius: Float,
    /** Velocity differences are divided by this. */
    val velocityScale: Float,
    /** The output is multiplied by this to get a world-units acceleration. */
    val accelScale: Float,
    /** The summed branch is divided by this, so a typical crowd lands near 1. */
    val sumScale: Float,
    /** Closest approach used by the proximity feature — the same floor the rule itself uses. */
    val minSeparation: Float,
) {
    init {
        require(psi.inputs == PSI_INPUTS) {
            "psi takes the $PSI_INPUTS features interactionFeatures() produces, not ${psi.inputs}"
        }
        require(psi.outputs == messageWidth) { "psi must emit $messageWidth, not ${psi.outputs}" }
        require(phi.inputs == 2 + 2 * messageWidth) {
            "phi takes velocity plus a summed and a meaned message (${2 + 2 * messageWidth}), " +
                "this one takes ${phi.inputs}"
        }
        require(phi.outputs == 2) { "phi must emit a 2D acceleration, not ${phi.outputs}" }
    }

    /** How many weights and biases in total. */
    val parameterCount: Int get() = psi.parameterCount + phi.parameterCount

    /**
     * The acceleration on a particle with velocity `(vx, vy)` given its neighbours, in fp32 and
     * in the same order a shader would accumulate it — the reference the GPU is held to.
     *
     * [neighbours] supplies `(dx, dy, dvx, dvy)` per neighbour, already in world units.
     */
    fun evaluate(vx: Float, vy: Float, neighbours: List<FloatArray>): Pair<Float, Float> {
        val sum = FloatArray(messageWidth)
        val out = FloatArray(messageWidth)
        for (n in neighbours) {
            psi.evaluate(interactionFeatures(n[0], n[1], n[2], n[3], radius, velocityScale, minSeparation), out)
            for (k in 0 until messageWidth) sum[k] += out[k]
        }
        val count = neighbours.size
        val input = FloatArray(phi.inputs)
        input[0] = vx / velocityScale
        input[1] = vy / velocityScale
        for (k in 0 until messageWidth) {
            input[2 + k] = sum[k] / sumScale
            input[2 + messageWidth + k] = if (count > 0) sum[k] / count else 0f
        }
        val a = phi.evaluate(input)
        return a[0] * accelScale to a[1] * accelScale
    }

    override fun toString(): String =
        "DeepSetsWeights(psi ${psi}, phi ${phi}, $parameterCount parameters)"

    companion object {
        /** How many features [interactionFeatures] produces, and therefore `psi`'s input width. */
        const val PSI_INPUTS = 8
    }
}

/**
 * The per-neighbour features `psi` reads — see the discussion on [DeepSetsWeights].
 *
 * `[dx/R, dy/R, dvx/V, dvy/V, ux*p, uy*p, ux*p^2, uy*p^2]`, where `u` is the unit direction to the
 * neighbour and `p = minSeparation / max(r, minSeparation)` is a proximity in `(0, 1]`.
 *
 * The last four are the ones that matter. A `1/r` law is exactly `u * p / minSeparation` for any
 * `r` past the floor, so it is *linear* in `ux*p` — which is what turns separation from the term
 * the network could not fit into the one it fits first.
 */
fun interactionFeatures(
    dx: Float,
    dy: Float,
    dvx: Float,
    dvy: Float,
    radius: Float,
    velocityScale: Float,
    minSeparation: Float,
    into: FloatArray = FloatArray(DeepSetsWeights.PSI_INPUTS),
): FloatArray {
    val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    val soft = maxOf(distance, minSeparation)
    // At distance zero the direction is undefined; zero is the only answer that does not invent a
    // force out of a rounding error.
    val ux = if (distance > 0f) dx / distance else 0f
    val uy = if (distance > 0f) dy / distance else 0f
    val p = minSeparation / soft
    into[0] = dx / radius
    into[1] = dy / radius
    into[2] = dvx / velocityScale
    into[3] = dvy / velocityScale
    into[4] = ux * p
    into[5] = uy * p
    into[6] = ux * p * p
    into[7] = uy * p * p
    return into
}

/** One training example: a neighbourhood and the acceleration the rule being imitated produces. */
class InteractionExample(
    /** The centre particle's velocity, in world units. */
    val velocity: FloatArray,
    /** `(dx, dy, dvx, dvy)` per neighbour, in world units. */
    val neighbours: List<FloatArray>,
    /** The target acceleration, in world units. */
    val target: FloatArray,
)

/** The knobs [trainDeepSets] takes. */
data class DeepSetsOptions(
    val steps: Int = 4000,
    val batch: Int = 16,
    val messageWidth: Int = 6,
    val psiHidden: IntArray = intArrayOf(16, 16),
    val phiHidden: IntArray = intArrayOf(16, 16),
    val learningRate: Double = 3e-3,
    /** Decayed linearly to this by the last step. Same reasoning as `FlowMatchOptions`. */
    val finalLearningRate: Double = 2e-4,
    val seed: Long = 1L,
    val onProgress: ((Int, Double) -> Unit)? = null,
    val progressEvery: Int = 200,
)

/**
 * Fits a [DeepSetsWeights] to whatever rule [examples] embodies, by reverse-mode autodiff.
 *
 * [examples] is called with a step index and must return a fresh batch; drawing new
 * neighbourhoods every step rather than looping over a fixed set is what stops the networks
 * memorising particular configurations, and costs nothing when the examples are generated from a
 * rule you already have.
 *
 * Plain MSE on the acceleration. Weighting each example by its own target magnitude was tried and
 * removed: it changes nothing, because the regime where it would matter — a settled flock, where
 * separation and cohesion cancel twentyfold — is a regime the *examples* do not contain. Sample
 * the neighbourhoods from a running flock instead; that is worth a factor of ten in absolute force
 * error where reweighting is worth nothing.
 *
 * Adam, because plain SGD on a sum-over-neighbours objective has wildly different gradient scales
 * between the `psi` weights (which see every neighbour) and the `phi` weights (which see one
 * vector), and a single learning rate suits neither.
 */
fun trainDeepSets(
    radius: Float,
    velocityScale: Float,
    accelScale: Float,
    sumScale: Float,
    minSeparation: Float,
    options: DeepSetsOptions = DeepSetsOptions(),
    examples: (Int) -> List<InteractionExample>,
): DeepSetsWeights {
    require(options.steps > 0) { "trainDeepSets needs at least one step" }
    require(options.batch > 0) { "trainDeepSets needs a batch of at least one" }

    val random = Random(options.seed)
    val psiShape = MlpShape(DeepSetsWeights.PSI_INPUTS, options.psiHidden, options.messageWidth)
    val phiShape = MlpShape(2 + 2 * options.messageWidth, options.phiHidden, 2)

    val psiParams = psiShape.initialise(random)
    val phiParams = phiShape.initialise(random)
    val adam = Adam(psiParams.size + phiParams.size)

    var lastLoss = 0.0
    for (step in 0 until options.steps) {
        val tape = ADTape()
        val psiVars = tape.variables(psiParams)
        val phiVars = tape.variables(phiParams)

        var loss = tape.constant(0.0)
        val batch = examples(step)
        for (example in batch) {
            val sum = MutableList(options.messageWidth) { tape.constant(0.0) }
            val features = FloatArray(DeepSetsWeights.PSI_INPUTS)
            for (n in example.neighbours) {
                // Features are data, not parameters, so they go on the tape as constants: no
                // gradient flows into them and they cost one node each.
                interactionFeatures(n[0], n[1], n[2], n[3], radius, velocityScale, minSeparation, features)
                val out = psiShape.forward(features.map { tape.constant(it.toDouble()) }, psiVars)
                for (k in 0 until options.messageWidth) sum[k] = sum[k] + out[k]
            }
            val count = example.neighbours.size
            val input = ArrayList<AD>(phiShape.inputs)
            input += tape.constant(example.velocity[0].toDouble() / velocityScale)
            input += tape.constant(example.velocity[1].toDouble() / velocityScale)
            for (k in 0 until options.messageWidth) input += sum[k] / sumScale.toDouble()
            for (k in 0 until options.messageWidth) {
                input += if (count > 0) sum[k] / count.toDouble() else tape.constant(0.0)
            }
            val predicted = phiShape.forward(input, phiVars)
            for (d in 0 until 2) {
                val residual = predicted[d] - (example.target[d].toDouble() / accelScale)
                loss = loss + residual.sq()
            }
        }
        loss = loss / (batch.size * 2).toDouble()
        lastLoss = loss.value

        val gradients = tape.backward(loss)
        val rate = options.learningRate +
            (options.finalLearningRate - options.learningRate) * step / (options.steps - 1).coerceAtLeast(1)
        adam.step(rate) { i ->
            if (i < psiParams.size) gradients[psiVars[i].index]
            else gradients[phiVars[i - psiParams.size].index]
        }.let { delta ->
            for (i in psiParams.indices) psiParams[i] -= delta[i]
            for (i in phiParams.indices) phiParams[i] -= delta[psiParams.size + i]
        }

        val onProgress = options.onProgress
        if (onProgress != null && (step + 1) % options.progressEvery == 0) onProgress(step + 1, lastLoss)
    }

    return DeepSetsWeights(
        psi = psiShape.toMlpWeights(psiParams),
        phi = phiShape.toMlpWeights(phiParams),
        messageWidth = options.messageWidth,
        radius = radius,
        velocityScale = velocityScale,
        accelScale = accelScale,
        sumScale = sumScale,
        minSeparation = minSeparation,
    )
}

/**
 * The layer shape of one small network, and the two things a trainer needs from it: a forward pass
 * over [AD] and a conversion into the flat [MlpWeights] layout a shader reads.
 *
 * `tanh` throughout the hidden layers, and a linear output. `tanh` rather than the `leaky` that
 * `flowMatch` prefers because this is a bounded regression onto a bounded target, and the argument
 * for `leaky` there — that a velocity field has to keep growing away from the data without bound —
 * does not apply to an interaction with a finite radius.
 */
private class MlpShape(val inputs: Int, val hidden: IntArray, val outputs: Int) {
    val widths: IntArray = hidden + outputs
    val inputWidths: IntArray = intArrayOf(inputs) + hidden
    val parameterCount: Int = MlpWeights.floatCount(inputs, widths)

    /** Xavier, so the initial activations neither saturate `tanh` nor vanish through the depth. */
    fun initialise(random: Random): DoubleArray {
        val p = DoubleArray(parameterCount)
        var at = 0
        for (layer in widths.indices) {
            val n = widths[layer]
            val below = inputWidths[layer]
            val limit = sqrt(6.0 / (below + n))
            for (o in 0 until n) for (i in 0 until below) p[at++] = (random.nextDouble() * 2 - 1) * limit
            for (o in 0 until n) p[at++] = 0.0
        }
        return p
    }

    fun forward(x: List<AD>, params: List<AD>): List<AD> {
        var source = x
        var at = 0
        for (layer in widths.indices) {
            val n = widths[layer]
            val below = inputWidths[layer]
            val weightBase = at
            val biasBase = at + n * below
            val destination = ArrayList<AD>(n)
            for (o in 0 until n) {
                var sum = params[biasBase + o]
                for (i in 0 until below) sum = sum + params[weightBase + o * below + i] * source[i]
                destination += if (layer == widths.size - 1) sum else tanh(sum)
            }
            at = biasBase + n
            source = destination
        }
        return source
    }

    fun toMlpWeights(params: DoubleArray) = MlpWeights(
        inputs = inputs,
        outputs = outputs,
        hidden = hidden.copyOf(),
        activation = MlpActivation.TANH,
        flat = FloatArray(params.size) { params[it].toFloat() },
    )
}

/** Adam, in the fifteen lines it actually is. */
private class Adam(size: Int, val beta1: Double = 0.9, val beta2: Double = 0.999, val epsilon: Double = 1e-8) {
    private val m = DoubleArray(size)
    private val v = DoubleArray(size)
    private val delta = DoubleArray(size)
    private var t = 0

    inline fun step(rate: Double, gradient: (Int) -> Double): DoubleArray {
        t++
        val correction1 = 1.0 - Math.pow(beta1, t.toDouble())
        val correction2 = 1.0 - Math.pow(beta2, t.toDouble())
        for (i in delta.indices) {
            val g = gradient(i)
            m[i] = beta1 * m[i] + (1 - beta1) * g
            v[i] = beta2 * v[i] + (1 - beta2) * g * g
            delta[i] = rate * (m[i] / correction1) / (sqrt(v[i] / correction2) + epsilon)
        }
        return delta
    }
}

/**
 * GLSL for the whole learned interaction: both networks, the feature map, and the combination.
 *
 * Emits, for `prefix = "learned"`:
 *
 * - `learnedPsiWeights` / `learnedPhiWeights` buffer-reference types and the two evaluation
 *   functions, straight from [MlpWeights.glsl] — the same emitter the flow-matching work uses, so
 *   there is one implementation of "an MLP as a shader" and one parity test covering it.
 * - `learnedFeatures(vec2 d, vec2 dv, out float f[8])`, which must agree with
 *   [interactionFeatures] exactly.
 * - `learnedCombine(...)`, the `phi` half: velocity, the summed message and the meaned message.
 *
 * The aggregation loop itself is *not* emitted, because it belongs to the caller's neighbour walk
 * — the consumer already has a loop over the 3x3 block, and the message sum has to accumulate
 * inside it.
 *
 * Place immediately after `#version`: this carries `#extension` directives.
 */
fun DeepSetsWeights.glsl(prefix: String = "learned"): String = buildString {
    // Captured before the builder starts, because inside `buildString` the implicit receiver is
    // the StringBuilder: writing `$this` in the template below silently interpolates everything
    // appended so far, which emits both networks a second time inside what looks like a comment
    // and fails as "block name cannot be redefined" forty lines later.
    val description = this@glsl.toString()
    append(psi.glsl("${prefix}Psi"))
    append("\n")
    append(phi.glsl("${prefix}Phi"))
    append("\n")
    append(
        """
        // $description
        const int ${prefix}MessageWidth = $messageWidth;
        const float ${prefix}Radius = ${glslFloat(radius)};
        const float ${prefix}VelocityScale = ${glslFloat(velocityScale)};
        const float ${prefix}AccelScale = ${glslFloat(accelScale)};
        const float ${prefix}SumScale = ${glslFloat(sumScale)};
        const float ${prefix}MinSeparation = ${glslFloat(minSeparation)};

        // Must agree with interactionFeatures() in deepsets.kt, term for term.
        void ${prefix}Features(vec2 d, vec2 dv, out float f[${DeepSetsWeights.PSI_INPUTS}]) {
            float distance = length(d);
            float soft = max(distance, ${prefix}MinSeparation);
            // Zero at zero distance: the direction is undefined there and any other answer
            // invents a force out of a rounding error.
            vec2 u = distance > 0.0 ? d / distance : vec2(0.0);
            float p = ${prefix}MinSeparation / soft;
            f[0] = d.x / ${prefix}Radius;
            f[1] = d.y / ${prefix}Radius;
            f[2] = dv.x / ${prefix}VelocityScale;
            f[3] = dv.y / ${prefix}VelocityScale;
            f[4] = u.x * p;
            f[5] = u.y * p;
            f[6] = u.x * p * p;
            f[7] = u.y * p * p;
        }

        vec2 ${prefix}Combine(
            ${prefix}PhiWeights pw, vec2 velocity, float messageSum[${prefix}MessageWidth], float n
        ) {
            float x[${phi.inputs}];
            x[0] = velocity.x / ${prefix}VelocityScale;
            x[1] = velocity.y / ${prefix}VelocityScale;
            for (int k = 0; k < ${prefix}MessageWidth; ++k) {
                x[2 + k] = messageSum[k] / ${prefix}SumScale;
                x[2 + ${prefix}MessageWidth + k] = n > 0.0 ? messageSum[k] / n : 0.0;
            }
            float y[2];
            ${prefix}Phi(pw, x, y);
            return vec2(y[0], y[1]) * ${prefix}AccelScale;
        }
        """.trimIndent()
    )
    append("\n")
}

/** A float as GLSL source, always with a decimal point so it cannot be read as an int. */
private fun glslFloat(v: Float): String {
    val s = v.toString()
    return if ('.' in s || 'e' in s || 'E' in s) s else "$s.0"
}
