@file:afield.CompletionCategory("Machine learning")

package afield.stdlib

import afield.graphics.FLine
import afield.graphics.HungarianAssignment
import smile.base.mlp.Layer
import smile.base.mlp.LayerBuilder
import smile.base.mlp.MultilayerPerceptron
import smile.base.mlp.OutputFunction
import smile.util.function.TimeFunction
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Flow matching over 2D and 3D point clouds — [flowMatch] trains a velocity field that carries one
 * distribution onto another, and the field it returns can be sampled, integrated part-way, or drawn.
 *
 * The idea is small enough to state completely. Pick a point `x0` from where you are starting and a
 * point `x1` from where you want to end up, and declare that the straight line between them is the
 * path a particle should take: at time `t` it is at `xt = (1-t)·x0 + t·x1`, travelling at `x1 - x0`.
 * Now fit a network `v(x, t)` to that velocity by least squares over many random `(x0, x1, t)`
 * triples. No two straight lines agree where they cross, so the fit cannot reproduce any single one
 * of them; what it converges to is the *average* velocity through each point at each time, and
 * integrating that average from `t = 0` to `t = 1` transports the whole starting distribution onto
 * the whole target one. That is the entire trick, and it is why there is no adversary, no sampler in
 * the training loop, and nothing to diverge — the loss is a plain regression MSE.
 *
 * ```kotlin
 * val target = flowSamples(myLine, 600, jitter = 4.0)   // sampled FLine + noise
 * val field = flowMatch(target, options = FlowMatchOptions(steps = 6000))
 * val cloud = field.sample(600)                         // fresh points from the same shape
 * val threads = field.paths(80)                         // the trajectories, as FLines
 * ```
 *
 * With a `from` cloud supplied it becomes a morph instead of a generator, and then [FlowMatchField.transport]
 * is the interesting call: it runs the ODE only part-way, so `transport(a, to = 0.5)` is a genuine
 * half-way shape rather than a vertex-wise average of two point lists.
 *
 * ```kotlin
 * val morph = flowMatch(to = flowSamples(lineB, 600), from = flowSamples(lineA, 600))
 * val halfway = morph.transport(flowSamples(lineA, 600), to = 0.5)
 * ```
 *
 * ## Coordinates
 *
 * Training happens in a normalised frame — the target cloud (both clouds, for a morph) is centred
 * and isotropically scaled to roughly unit variance — because a Gaussian source has to be
 * commensurate with the data for any of this to work, and FLine coordinates are usually in the
 * hundreds. The scaling is a single factor rather than per-axis, so it cannot squash a shape's aspect
 * ratio. [FlowMatchField] converts at its boundary, so everything you pass in and get back is in the
 * coordinates you started with.
 *
 * 2D and 3D are distinguished by the data, not by a flag: a cloud whose `z` never varies is trained
 * as a 2D problem and its points come back on that same `z` plane. Pass [FlowMatchOptions.dimension]
 * to override. It matters — a 2D shape trained as 3D gets a Gaussian's worth of spread in `z` that
 * the field then has to learn to collapse.
 *
 * ## What this is not
 *
 * - **Not a big model.** The default is two hidden layers of 64 `leaky` units trained by RMSProp,
 *   which is right for a few thousand points in two or three dimensions and nothing like enough for
 *   images. Training is single-threaded and per-sample (Smile's MLP has no batched matmul), so cost
 *   scales with `steps × batch`; see the timings in `FlowMatchingTest`.
 * - **Not able to change topology.** Integrating a velocity field is a continuous invertible map, so
 *   it cannot tear a loop open or join two strands into one — a flow can only deform. With
 *   [FlowCoupling.PAIRED] this stops being an abstraction and starts being the dominant error term:
 *   asking a straight segment to become a closed ring makes the mean landing error fifteen times
 *   worse than asking a small circle to do it, because the two ends of the segment are pulled to the
 *   same place and their paths have to cross. Give a closed target a closed source.
 * - **Not exact.** The field is an average over crossing paths, so samples land *near* the target
 *   distribution, not on it. Sharp corners smooth off, and thin structure needs more `steps` and more
 *   `hidden` before it resolves. [FlowMatchField.loss] is the training MSE, which measures the velocity
 *   fit and not the quality of the samples — judge those by looking, or with [chamferDistance].
 * - **Not ordered.** [FlowMatchField.sample] returns an unordered cloud. Recovering an FLine from it is a
 *   separate problem; [FlowMatchField.transport] sidesteps it by moving points you already have, which
 *   keeps whatever order they came in.
 * - **Not conditional.** There is no class label or text prompt input — one field carries one
 *   distribution onto one other.
 */

// ---------------------------------------------------------------------------------------------
// The network
// ---------------------------------------------------------------------------------------------

/**
 * A multi-output regression MLP, which is the one thing Smile's own `smile.regression.MLP` will not
 * do — it fixes the output layer at a single neuron and `predict` returns a `Double`.
 *
 * Everything underneath already supports it: `OutputLayer.computeOutputGradient` takes a target
 * *vector* and squared-error cost is defined component-wise. So this subclasses
 * `MultilayerPerceptron` directly, builds an [outputs]-wide linear output layer, and exposes the
 * three protected operations a training loop needs — [predict], [accumulate] and [step].
 *
 * Not thread-safe for training. Smile keeps per-layer activations in thread locals, so [predict] from
 * several threads is fine, but [accumulate] writes gradient buffers that [step] then consumes.
 */
class VectorMLP(
    /** Width of the input vector. */
    val inputs: Int,
    /** Width of the predicted vector. */
    val outputs: Int,
    hidden: IntArray = intArrayOf(96, 96),
    /** The hidden activation, kept because [exportWeights] has to name it and Smile does not. */
    val activation: String = "tanh",
) : MultilayerPerceptron(*mlpLayers(inputs, hidden, outputs, activation)) {

    /** Runs the network forward, writing [outputs] values into [into], which is returned. */
    fun predict(x: DoubleArray, into: DoubleArray = DoubleArray(outputs)): DoubleArray {
        require(x.size == inputs) { "expected an input of width $inputs, got ${x.size}" }
        propagate(vector(x), false)
        val out = output.output()
        for (i in 0 until outputs) into[i] = out.get(i)
        return into
    }

    /**
     * Adds one sample's gradient to the batch, returning that sample's squared error.
     *
     * The error is the network's own forward pass read back before the weights move, so it costs
     * nothing. It is therefore the loss *before* the step this batch is about to take, which is the
     * quantity a batch mean should be averaging over.
     */
    fun accumulate(x: DoubleArray, y: DoubleArray): Double {
        require(x.size == inputs) { "expected an input of width $inputs, got ${x.size}" }
        require(y.size == outputs) { "expected a target of width $outputs, got ${y.size}" }
        propagate(vector(x), true)

        val out = output.output()
        var squaredError = 0.0
        for (i in 0 until outputs) {
            val residual = y[i] - out.get(i)
            squaredError += residual * residual
        }

        val desired = target.get()
        for (i in 0 until outputs) desired.set(i, y[i])
        backpropagate(false)

        return squaredError
    }

    /** Applies the accumulated gradient of a batch of [batchSize] samples and advances the clock. */
    fun step(batchSize: Int) {
        update(batchSize)
        t++
    }

    /** Configures RMSProp, momentum and learning rate together, the combination [flowMatch] uses. */
    fun configure(learningRate: Double, momentum: Double, rho: Double, epsilon: Double = 1e-7) =
        configure(TimeFunction.constant(learningRate), momentum, rho, epsilon)

    /**
     * As [configure], but with a learning-rate *schedule* — `TimeFunction.linear(from, steps, to)`
     * and friends, indexed by the [step] count.
     *
     * Worth the trouble: RMSProp normalises the gradient, so a constant rate takes steps of roughly
     * constant size however close to the answer it is, and the fit stops improving at a floor set by
     * that rate rather than by the network's capacity. Decaying the rate is what converts a
     * plausible-looking fit into an accurate one.
     */
    fun configure(learningRate: TimeFunction, momentum: Double, rho: Double, epsilon: Double = 1e-7) {
        setLearningRate(learningRate)
        if (momentum > 0.0) setMomentum(TimeFunction.constant(momentum))
        if (rho > 0.0) setRMSProp(rho, epsilon)
    }

    /** How many parameters — weights plus biases — the network is fitting. */
    fun parameterCount(): Int {
        var widthBelow = inputs
        var total = 0
        for (layer in layerWidths()) {
            total += widthBelow * layer + layer
            widthBelow = layer
        }
        return total
    }

    private fun layerWidths(): IntArray {
        // net[0] is the input layer, which holds no weights; the output layer lives outside net.
        val widths = IntArray(net.size) { net[it].getOutputSize() }
        return widths.copyOfRange(1, widths.size) + outputs
    }

    // -- taking the network somewhere else ------------------------------------------------------

    /**
     * A flat snapshot of every weight and bias, in the layout [MlpWeights] documents — which is
     * what [afield.stdlib.glsl] turns into a shader.
     *
     * This lives here, rather than as an extension somewhere nearer the GPU, because it is the
     * only code that needs to know how Smile lays a network out, and the two facts it depends on
     * are both invisible from outside: `net[0]` is the input layer and carries no weights, and
     * `Layer.propagate` computes `weight * input + bias` with no transpose, so `weight` is
     * `(outputs x inputs)` and `weight.get(o, i)` is the weight from input `i` to output `o`.
     *
     * Read element by element through the public accessors rather than copied out of
     * `DenseMatrix.memory()`. Slower, and irrelevantly so for a few thousand parameters, but a
     * change of storage order upstream then cannot silently transpose every layer — which is a
     * failure that leaves the network the right shape and the right size and entirely wrong.
     */
    fun exportWeights(): MlpWeights {
        val widths = layerWidths()
        val weightLayers = (1 until net.size).map { net[it] } + output
        check(weightLayers.size == widths.size) {
            "expected ${widths.size} weight-bearing layers, found ${weightLayers.size}"
        }

        val flat = FloatArray(MlpWeights.floatCount(inputs, widths))
        var at = 0
        var below = inputs
        for ((index, layer) in weightLayers.withIndex()) {
            val n = widths[index]
            val weight = layer.weight()
            val bias = layer.bias()
            check(weight.nrow() == n && weight.ncol() == below) {
                "layer $index should be ${n}x$below, Smile says ${weight.nrow()}x${weight.ncol()}"
            }
            for (o in 0 until n) for (i in 0 until below) flat[at++] = weight.get(o, i).toFloat()
            for (o in 0 until n) flat[at++] = bias.get(o).toFloat()
            below = n
        }
        check(at == flat.size) { "wrote $at of ${flat.size} weights" }

        return MlpWeights(
            inputs = inputs,
            outputs = outputs,
            hidden = widths.copyOfRange(0, widths.size - 1),
            activation = MlpActivation.of(activation),
            flat = flat,
        )
    }
}

/**
 * Builds the input / hidden / output layer stack Smile's `MultilayerPerceptron` constructor wants.
 *
 * Each builder needs the width of the layer below it, which is why this is a loop over builders
 * rather than a `map`. The output layer is linear with squared-error cost: a velocity is unbounded in
 * both directions, so neither a sigmoid nor a softmax head can represent one.
 */
private fun mlpLayers(inputs: Int, hidden: IntArray, outputs: Int, activation: String): Array<Layer> {
    require(inputs > 0) { "a network needs at least one input" }
    require(outputs > 0) { "a network needs at least one output" }
    require(hidden.isNotEmpty()) { "a network with no hidden layer can only fit a linear velocity field" }
    require(hidden.all { it > 0 }) { "every hidden layer needs at least one neuron, got ${hidden.toList()}" }

    val builders = ArrayList<LayerBuilder>(hidden.size + 2)
    builders += Layer.input(inputs)
    for (width in hidden) builders += Layer.builder(activation, width, 0.0, Double.NaN)
    builders += Layer.mse(outputs, OutputFunction.LINEAR)

    var widthBelow = 0
    return Array(builders.size) { index ->
        val builder = builders[index]
        builder.build(widthBelow).also { widthBelow = builder.neurons() }
    }
}

// ---------------------------------------------------------------------------------------------
// Training
// ---------------------------------------------------------------------------------------------

/** How [flowMatch] decides which `x0` travels to which `x1`. */
enum class FlowCoupling {
    /**
     * Pair a random source point with a random target point. The textbook choice, and correct: the
     * marginals are all the maths needs, so the field this converges to does transport one cloud onto
     * the other.
     */
    INDEPENDENT,

    /**
     * Pair them within each minibatch by solving the assignment that minimises total squared travel
     * distance. The marginals are unchanged, so the answer is still right, but far fewer straight
     * lines cross — and crossing lines are the entire reason the fitted velocity has to be an average
     * rather than the thing itself. The training loss consequently drops a long way, because the
     * quantity it is averaging over has much less spread.
     *
     * A much lower loss is *not* automatically a better generator, and on the unconditional ring in
     * `FlowMatchingTest` it is measurably worse. Minibatch OT is a biased approximation to the true
     * transport — it only sees `batch` points at a time — and the bias grows as the batch shrinks
     * relative to the cloud. Judge it on samples, not on loss.
     *
     * Costs an `O(batch³)` Hungarian solve per step — at the default batch that is small against the
     * network passes, but it grows quickly, so a batch of 512 is a different story.
     */
    MINIBATCH_OT,

    /**
     * Take the correspondence as given: the `i`-th source point travels to the `i`-th target point,
     * and the two clouds must therefore be the same size.
     *
     * This is the coupling to use when something upstream already knows what goes where — a curve
     * coordinate `s` shared between a canonical strand and a recovered one, so that `x0 = b(s) + B(s)r`
     * and `x1 = f(s) + A(s)r` are the *same* material point at both ends. Nothing is being averaged
     * then: the target velocity is a genuine function of `(x, t)` wherever the paths do not cross, the
     * loss floor is near zero rather than near the data's variance, and the field can be fitted far
     * more sharply than any of the unpaired couplings allow.
     *
     * The paths can still cross, and where they do this reverts to an average like the others — two
     * strands that swap places must pass through each other, and the field cannot represent that.
     */
    PAIRED,
}

/**
 * Which integrator [FlowMatchField] uses to follow the velocity field.
 */
enum class FlowIntegrator {
    /** One network evaluation per step. Cheap, and first-order accurate. */
    EULER,

    /**
     * Two evaluations per step, second-order accurate — the explicit midpoint rule. Usually the
     * better buy: half the steps at twice the cost lands closer, because the error falls as the
     * square.
     */
    MIDPOINT,
}

/**
 * The knobs [flowMatch] takes, bundled so that a call that only wants to change one of them does not
 * have to name the rest positionally.
 *
 * The defaults are tuned for a few hundred to a few thousand points in two or three dimensions, and
 * train in a couple of seconds. Reach for more [steps] first when the result is vague, and for wider
 * [hidden] only when a long run is still vague — an undertrained big network looks much like a
 * well-trained small one that has run out of capacity, and [flowMatch]'s returned
 * [FlowMatchField.losses] is how you tell them apart: a loss still falling at the end means more steps.
 */
data class FlowMatchOptions(
    /** Gradient steps to take. The dominant cost and the dominant quality lever. */
    val steps: Int = 3000,
    /** Samples per gradient step. */
    val batch: Int = 48,
    /** Hidden layer widths. */
    val hidden: IntArray = intArrayOf(64, 64),
    /**
     * Hidden activation: `leaky`, `relu`, `tanh`, `sigmoid` or `linear`.
     *
     * This is the single biggest quality lever here, well ahead of width or learning rate, and the
     * obvious choice is the wrong one: on the ring in `FlowMatchingTest`, `tanh` gives samples with
     * about four times the radial spread of `leaky` or `relu` at equal cost. A velocity field has to
     * keep growing away from the data — at `t` near 0 the right answer is roughly `-x`, without
     * bound — and a saturating activation cannot, so it bends the far field back on itself. `leaky`
     * and `relu` are within noise of each other; `leaky` is the default only because a dead unit in
     * a network this small is worth avoiding.
     */
    val activation: String = "leaky",
    /** RMSProp learning rate at the first step. */
    val learningRate: Double = 1e-2,
    /**
     * Learning rate at the last step, decayed linearly from [learningRate]; null to hold it constant.
     *
     * RMSProp rescales the gradient to roughly unit size, so a constant rate keeps taking same-sized
     * steps however close to the answer it already is, and the fit settles at a floor set by the rate
     * rather than by the network. This is the cheapest large improvement available here — measured
     * against a constant rate it took the ring's radial spread from 9.2 to 3.6 and a paired strand's
     * mean landing error from 3.1% of extent to 0.7%.
     *
     * It also decides whether training *converges at all*. At a constant rate, more steps made the
     * paired case measurably worse — it bounces around the answer rather than settling into it — so
     * a `null` here turns [steps] from a quality lever into a gamble.
     */
    val finalLearningRate: Double? = 3e-4,
    /**
     * Momentum, or 0 to disable. **Leave this at 0 unless [rho] is also 0.**
     *
     * Smile applies momentum on top of the RMSProp-normalised gradient *without* the `(1 - β)` factor
     * that Adam uses, so switching it on multiplies the effective step by about `1/(1 - momentum)` —
     * ten times, at the usual 0.9. Combined with [rho] and this file's default learning rate it does
     * not merely train worse, it diverges: the ring test reaches coordinates around 1e23. Momentum at
     * 0.9 with `rho = 0` is a reasonable plain-SGD configuration; the two together are not.
     */
    val momentum: Double = 0.0,
    /** RMSProp decay, or 0 to fall back to plain SGD. See [momentum] before changing either. */
    val rho: Double = 0.9,
    /**
     * How many `sin`/`cos` octaves of the time input the network sees, on top of raw `t`.
     *
     * A velocity field's dependence on `t` is often much sharper than its dependence on position —
     * near `t = 1` the paths have to fan out onto the target's fine structure — and a plain scalar `t`
     * resolves that badly. Octave `k` contributes `sin(2ᵏπt)` and `cos(2ᵏπt)`, so four octaves is
     * detail down to about an eighth of the trajectory. Zero means raw `t` alone, which measurably
     * costs accuracy; past about four the gain is lost in run-to-run noise.
     */
    val timeFeatures: Int = 4,
    /**
     * How `x0` is paired with `x1`.
     *
     * [FlowCoupling.INDEPENDENT] is the default because it is unbiased and the cheapest. If something
     * upstream already knows the correspondence, [FlowCoupling.PAIRED] is a different and much
     * better-posed problem — see its own documentation.
     */
    val coupling: FlowCoupling = FlowCoupling.INDEPENDENT,
    /**
     * Standard deviation of noise added to each drawn data point, in input coordinates.
     *
     * A finite point cloud is a sum of spikes, and a field fitted hard against spikes will reproduce
     * exactly those points and nothing between them. A little jitter — a fraction of the spacing
     * between neighbouring points — turns it into something continuous to aim at. Note that
     * [flowSamples] can also jitter, and that noise is baked into the cloud once; this one is fresh
     * every time a point is drawn, which is the more useful of the two during training.
     */
    val jitter: Double = 0.0,
    /** Seed for the training draws. `null` takes an unpredictable one. */
    val seed: Long? = null,
    /** Override the inferred 2-or-3 dimensionality. */
    val dimension: Int? = null,
    /** Called every [progressEvery] steps with the step index and the running mean loss. */
    val onProgress: ((Int, Double) -> Unit)? = null,
    /** How often [onProgress] fires. */
    val progressEvery: Int = 100,
) {
    // IntArray has identity equals, so the generated data-class equals/hashCode would be wrong.
    override fun equals(other: Any?): Boolean =
        other is FlowMatchOptions && toString() == other.toString()

    override fun hashCode(): Int = toString().hashCode()
}

/**
 * Trains a velocity field that carries [from] onto [to], and returns it.
 *
 * [to] is the distribution you want to end up able to produce. [from] is where the particles start:
 * leave it null for a standard Gaussian, which makes the result a *generator* — [FlowMatchField.sample]
 * draws fresh points shaped like [to]. Supply a cloud and the result is a *morph* between the two,
 * which is what [FlowMatchField.transport] exists for.
 *
 * Both clouds are taken as unordered — nothing pairs the `i`-th point of [from] with the `i`-th point
 * of [to], and it would be wrong to, since the whole business of [FlowCoupling] is deciding what
 * travels where. Point counts need not match.
 *
 * Training is synchronous and single-threaded; the default 3000 steps take a couple of seconds.
 * [FlowMatchOptions.onProgress] is the hook for a progress bar, and [FlowMatchField.losses] the record
 * afterwards.
 */
fun flowMatch(
    to: List<Vec3>,
    from: List<Vec3>? = null,
    options: FlowMatchOptions = FlowMatchOptions(),
): FlowMatchField {
    require(to.isNotEmpty()) { "flowMatch needs at least one target point" }
    require(from == null || from.isNotEmpty()) { "flowMatch was given an empty source cloud" }
    require(options.steps > 0) { "flowMatch needs at least one step, got ${options.steps}" }
    require(options.batch > 0) { "flowMatch needs a batch of at least one, got ${options.batch}" }
    require(options.timeFeatures >= 0) { "timeFeatures cannot be negative, got ${options.timeFeatures}" }
    require(options.jitter >= 0.0) { "jitter cannot be negative, got ${options.jitter}" }

    require(options.coupling != FlowCoupling.PAIRED || from != null) {
        "PAIRED coupling needs a from cloud to pair the target with"
    }
    require(options.coupling != FlowCoupling.PAIRED || from!!.size == to.size) {
        "PAIRED coupling pairs point i with point i, so the clouds must match in size: " +
            "${from!!.size} source points against ${to.size} target points"
    }

    val everyPoint = if (from == null) to else to + from
    val dimension = options.dimension ?: inferDimension(everyPoint)
    require(dimension == 2 || dimension == 3) { "flowMatch handles 2D and 3D data, not ${dimension}D" }

    val frame = FlowFrame.of(everyPoint, dimension)
    val targetPoints = frame.normalize(to)
    val sourcePoints = from?.let { frame.normalize(it) }

    // Smile draws its Xavier-uniform initial weights from its own generator, not ours, so a seed
    // that did not reach MathEx would leave training reproducible in its draws and random in its
    // starting point. MathEx's generator is a thread local, so this reseeds Smile for the calling
    // thread only -- but it does reseed it, and any other Smile call on this thread afterwards
    // inherits that.
    options.seed?.let { smile.math.MathEx.setSeed(it) }

    val net = VectorMLP(
        inputs = dimension + 1 + 2 * options.timeFeatures,
        outputs = dimension,
        hidden = options.hidden.copyOf(),
        activation = options.activation,
    )
    val schedule = options.finalLearningRate?.let {
        TimeFunction.linear(options.learningRate, options.steps.toDouble(), it)
    } ?: TimeFunction.constant(options.learningRate)
    net.configure(schedule, options.momentum, options.rho)

    val random = options.seed?.let { Random(it) } ?: Random(Random.nextLong())
    val jitter = options.jitter / frame.scale

    val input = DoubleArray(net.inputs)
    val velocity = DoubleArray(dimension)
    val x0 = Array(options.batch) { DoubleArray(dimension) }
    val x1 = Array(options.batch) { DoubleArray(dimension) }
    val losses = DoubleArray(options.steps)

    for (step in 0 until options.steps) {
        for (i in 0 until options.batch) {
            if (options.coupling == FlowCoupling.PAIRED) {
                // One index, used at both ends -- that is the whole of what "paired" means.
                val index = random.nextInt(targetPoints.size)
                drawIndexInto(x0[i], sourcePoints!!, index, random, jitter)
                drawIndexInto(x1[i], targetPoints, index, random, jitter)
            } else {
                drawInto(x0[i], sourcePoints, random, jitter)
                drawInto(x1[i], targetPoints, random, jitter)
            }
        }
        if (options.coupling == FlowCoupling.MINIBATCH_OT) coupleByAssignment(x0, x1)

        var squaredError = 0.0
        for (i in 0 until options.batch) {
            val t = random.nextDouble()
            for (d in 0 until dimension) {
                input[d] = (1.0 - t) * x0[i][d] + t * x1[i][d]
                velocity[d] = x1[i][d] - x0[i][d]
            }
            encodeTime(input, dimension, t, options.timeFeatures)
            squaredError += net.accumulate(input, velocity)
        }
        net.step(options.batch)

        losses[step] = squaredError / (options.batch * dimension)
        val onProgress = options.onProgress
        if (onProgress != null && (step + 1) % options.progressEvery == 0) {
            onProgress(step + 1, losses.meanOfLast(step, options.progressEvery))
        }
    }

    return FlowMatchField(
        dimension = dimension,
        net = net,
        timeFeatures = options.timeFeatures,
        frame = frame,
        sourcePoints = sourcePoints,
        losses = losses,
    )
}

/**
 * Trains a field that generates points shaped like [line] — the common case, spelled out.
 *
 * Equivalent to `flowMatch(flowSamples(line, samples, jitter))`, and see [flowSamples] for what that
 * cloud looks like. [jitter] defaults to a twentieth of the spacing between samples, which is enough
 * to keep the field aiming at something continuous without visibly thickening the line.
 */
fun flowMatch(
    line: FLine,
    samples: Int = 600,
    jitter: Double? = null,
    options: FlowMatchOptions = FlowMatchOptions(),
): FlowMatchField = flowMatch(flowSamples(line, samples, jitter ?: defaultJitter(line, samples)), null, options)

/**
 * Samples [count] points evenly along [line]'s arc length, displaced by Gaussian noise of standard
 * deviation [jitter].
 *
 * This is the "sampled FLine plus noise" a flow field is usually asked to learn. The noise is
 * isotropic and in the line's own coordinates, applied in the plane for a flat line and in all three
 * axes otherwise, so a 2D line stays 2D and can be trained as such.
 */
fun flowSamples(line: FLine, count: Int = 600, jitter: Double = 0.0, seed: Long? = null): List<Vec3> {
    require(count > 1) { "flowSamples needs at least two samples, got $count" }
    require(jitter >= 0.0) { "jitter cannot be negative, got $jitter" }

    val points = line.sampleByNumber(count)
    if (jitter == 0.0) return points

    val random = seed?.let { Random(it) } ?: Random(Random.nextLong())
    val flat = inferDimension(points) == 2
    return points.map {
        vec(
            it.x + jitter * random.nextGaussian(),
            it.y + jitter * random.nextGaussian(),
            if (flat) it.z.toDouble() else it.z + jitter * random.nextGaussian(),
        )
    }
}

/** A twentieth of the mean spacing between [samples] points along [line]. */
private fun defaultJitter(line: FLine, samples: Int): Double =
    0.05 * line.cursor().lengthD() / max(1, samples - 1)

// ---------------------------------------------------------------------------------------------
// The trained field
// ---------------------------------------------------------------------------------------------

/**
 * A source cloud and a target cloud of the same length, in correspondence — what
 * [FlowCoupling.PAIRED] wants, and what [mixtureSource] builds.
 */
class FlowPairing(
    /** Where the particles start. The `i`-th point travels to `to[i]`. */
    val from: List<Vec3>,
    /** Where they end up. */
    val to: List<Vec3>,
) {
    init {
        require(from.size == to.size) { "a pairing needs equal clouds, got ${from.size} and ${to.size}" }
    }

    /** How many pairs. */
    val size: Int get() = from.size
}

/**
 * Splits [lines] into their closed subpaths and samples each one in arc-length order, in
 * proportion to its length — the components [mixtureSource] wants, prepared the way that matters.
 *
 * Two details it exists to get right, both measured on the word "FIELD":
 *
 * - **Subpaths, not glyphs.** `TextLayout.toFLines()` returns one FLine per *glyph*, so a "D" is
 *   an outer contour and a counter in a single line — an annulus. Asking one ellipse to become an
 *   annulus is the same topology error as asking one blob to become a word, just one level down.
 *   Splitting at [FLine.pieces] boundaries takes the mean landing error from 12.4 to 5.0.
 * - **Proportional sampling.** Giving every subpath the same count puts as many points on a
 *   counter as on the letter around it, which both distorts the density the flow is fitted to and
 *   makes the counter conspicuously brighter when particles are drawn on it.
 *
 * [total] is shared out across subpaths; each gets at least [minimum] so a small counter is not
 * sampled into nothing.
 */
fun contourComponents(
    lines: List<FLine>,
    total: Int = 3500,
    minimum: Int = 24,
    jitter: Double = 0.0,
    seed: Long? = null,
): List<List<Vec3>> {
    require(lines.isNotEmpty()) { "contourComponents needs at least one line" }
    require(total > 0) { "total must be positive, got $total" }

    val subpaths = lines.flatMap { it.pieces() }.filter { it.nodes.size >= 2 }
    require(subpaths.isNotEmpty()) { "none of these lines has a drawable subpath" }

    val lengths = subpaths.map { piece ->
        val points = piece.sampleByNumber(64)
        var length = 0.0
        for (i in 1 until points.size) {
            length += hypot((points[i].x - points[i - 1].x).toDouble(), (points[i].y - points[i - 1].y).toDouble())
        }
        max(length, 1e-9)
    }
    val totalLength = lengths.sum()

    return subpaths.mapIndexed { index, piece ->
        val count = max(minimum, (total * lengths[index] / totalLength).roundToInt())
        flowSamples(piece, count, jitter, seed)
    }
}

/**
 * Builds a source cloud whose *topology matches the target's*: one closed curve per component,
 * in point-for-point correspondence with the component it has to become.
 *
 * This exists because of the hardest limit in this file. A flow is a continuous invertible map,
 * so it cannot tear one blob into several, and it cannot open a closed curve. Ask a standard
 * Gaussian to become the five disjoint outlines of the word "FIELD" and it cannot: measured, that
 * lands at a chamfer of 12.3 and forty thousand steps at 128x128 units does not improve it. The
 * loss barely moves across all of that, so the loss will not tell you either.
 *
 * Give each component a source of its own *shape*, and the problem becomes several copies of the
 * case that already works well — a circle becoming a closed curve, which lands within 0.3 units
 * on the ring in `FlowMatchingTest`. Each component gets an ellipse fitted to its own mean and
 * covariance, so a wide contour gets a wide ellipse at the right angle and nothing has far to
 * travel.
 *
 * **Components must be ordered along the contour**, which is what [flowSamples] returns — it
 * samples an FLine by arc length. The `i`-th source point is paired with the `i`-th contour
 * point, and that ordering is the correspondence. Handing this a shuffled cloud gives a pairing
 * with no structure at all, which is worse than not using it.
 *
 * ```kotlin
 * val contours = layoutText("FIELD", typeface("Helvetica", 260.0)).toFLines()
 * val pairing = mixtureSource(contours.map { flowSamples(it, 700) })
 * val field = flowMatch(pairing.to, pairing.from, FlowMatchOptions(coupling = FlowCoupling.PAIRED))
 * ```
 *
 * [shrink] scales each ellipse about its own centre; [jitter] thickens it from a curve into an
 * annulus, in units of the ellipse's own axes. [alignPhase] is described on its own below and
 * should be left on.
 */
fun mixtureSource(
    components: List<List<Vec3>>,
    shrink: Double = 1.0,
    jitter: Double = 0.0,
    alignPhase: Boolean = true,
    seed: Long? = null,
): FlowPairing {
    require(components.isNotEmpty()) { "mixtureSource needs at least one component" }
    require(components.all { it.size >= 3 }) { "every component needs at least three points" }
    require(shrink > 0.0) { "shrink must be positive, got $shrink" }
    require(jitter >= 0.0) { "jitter cannot be negative, got $jitter" }

    val random = seed?.let { Random(it) } ?: Random(Random.nextLong())
    val from = ArrayList<Vec3>()
    val to = ArrayList<Vec3>()

    for (component in components) {
        val n = component.size
        var cx = 0.0
        var cy = 0.0
        var cz = 0.0
        for (p in component) { cx += p.x; cy += p.y; cz += p.z }
        cx /= n; cy /= n; cz /= n

        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (p in component) {
            val dx = p.x - cx
            val dy = p.y - cy
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        sxx /= n; sxy /= n; syy /= n

        // Cholesky of the 2x2 covariance, floored so a component with no width — a straight
        // stroke, which a sampled outline can be — stays samplable instead of yielding NaN.
        val floor = 1e-6 * max(sxx + syy, 1e-12)
        val l00 = sqrt(max(sxx, floor))
        val l10 = sxy / l00
        val l11 = sqrt(max(syy - l10 * l10, floor))
        // sqrt(2) so a circular contour maps to a circle of its own radius: a circle of radius R
        // has covariance R^2/2 per axis, so the Cholesky factor is R/sqrt(2).
        val k = sqrt(2.0) * shrink

        fun ellipse(index: Int): Pair<Double, Double> {
            val a = 2.0 * PI * index / n
            val u = cos(a)
            val v = sin(a)
            return Pair(cx + k * l00 * u, cy + k * (l10 * u + l11 * v))
        }

        // Which point of the ellipse should become point 0 of the contour, and which way round.
        //
        // This is the step that makes the whole construction work, and leaving it out is not a
        // small loss. A contour's arc-length origin has nothing to do with angle zero on the
        // fitted ellipse, and a contour may wind either way, so the identity correspondence
        // carries an arbitrary rotation and possibly a reflection. Measured on the five contours
        // of "FIELD": aligning takes the training loss from 0.047 to 0.0047 and the mean landing
        // error from 58 units to 16.
        var bestOffset = 0
        var bestFlip = false
        if (alignPhase) {
            var best = Double.MAX_VALUE
            val stride = max(1, n / 64)
            for (flip in listOf(false, true)) {
                var offset = 0
                while (offset < n) {
                    var total = 0.0
                    var i = 0
                    while (i < n) {
                        val index = if (flip) (offset - i + 2 * n) % n else (offset + i) % n
                        val (ex, ey) = ellipse(index)
                        total += hypot(ex - component[i].x, ey - component[i].y)
                        i += stride
                    }
                    if (total < best) { best = total; bestOffset = offset; bestFlip = flip }
                    offset += stride
                }
            }
        }

        for (i in 0 until n) {
            val index = if (bestFlip) (bestOffset - i + 2 * n) % n else (bestOffset + i) % n
            val (ex, ey) = ellipse(index)
            from += vec(
                ex + jitter * k * l00 * random.nextGaussian(),
                ey + jitter * k * l11 * random.nextGaussian(),
                cz,
            )
            to += component[i]
        }
    }

    return FlowPairing(from, to)
}

/**
 * A trained velocity field, as returned by [flowMatch] — the thing you integrate.
 *
 * [sample] is the generator: draw from the source and follow the field all the way to `t = 1`.
 * [transport] is the same integration applied to points you supply and stopped wherever you like,
 * which is how a partial morph is spelled. [paths] and [pathsFrom] hand back the trajectories
 * themselves as FLines, which is both the nicest way to see what a field has learned and, on a morph,
 * the drawing you probably wanted.
 *
 * Nothing here retrains — the weights are fixed once [flowMatch] returns — but evaluation is *not*
 * thread-safe: the integrator reuses a handful of scratch arrays held on the field, so two threads
 * sampling the same [FlowMatchField] will corrupt each other's trajectories. Train once and sample from
 * one thread, or hand each thread its own field.
 */
class FlowMatchField internal constructor(
    /** 2 or 3 — whether `z` is part of the problem. */
    val dimension: Int,
    private val net: VectorMLP,
    private val timeFeatures: Int,
    private val frame: FlowFrame,
    private val sourcePoints: Array<DoubleArray>?,
    /** The mean squared velocity error of every training step, in order. */
    val losses: DoubleArray,
) {

    /** The training loss of the final step. */
    val loss: Double get() = losses.last()

    /** Mean training loss over the last [count] steps — steadier to read than [loss] alone. */
    fun loss(count: Int): Double = losses.meanOfLast(losses.size - 1, count)

    /** Whether this field was trained to morph from a supplied cloud rather than from a Gaussian. */
    val hasSourceCloud: Boolean get() = sourcePoints != null

    /** How many weights and biases the field is made of. */
    val parameterCount: Int get() = net.parameterCount()

    /**
     * The trained network itself, flattened for a shader — see [MlpWeights].
     *
     * Its inputs are the normalised position followed by the time encoding, in that order, which
     * is `dimension + 1 + 2 * timeFeatures` wide; its outputs are a velocity in the *normalised*
     * frame. This is the network and not the field: reproducing [velocity] also needs the time
     * encoding and the frame, which is what [export] bundles. Reach for this only when you want
     * the weights alone.
     */
    val network: MlpWeights get() = net.exportWeights()

    /**
     * Everything a shader needs to reproduce [velocity] — the network plus the three things around
     * it: how wide the position is, how `t` is encoded, and the frame the training happened in.
     *
     * The frame is the part that is easy to forget and impossible to see. Training normalises the
     * clouds to roughly unit variance, so the network's inputs and outputs are both in that
     * normalised space; a shader handed only the weights will integrate a field that is right in
     * shape and wrong in scale by whatever factor the data happened to need, which on FLine
     * coordinates is a factor of hundreds.
     */
    fun export(): FlowMatchExport = FlowMatchExport(
        network = net.exportWeights(),
        dimension = dimension,
        timeFeatures = timeFeatures,
        center = FloatArray(dimension) { frame.center[it].toFloat() },
        scale = frame.scale.toFloat(),
        source = sourcePoints?.let { points ->
            FloatArray(points.size * dimension) { points[it / dimension][it % dimension].toFloat() }
        },
    )

    /**
     * The velocity the field predicts at [at] and time [t], in input coordinates.
     *
     * [t] is not clamped: the field was only fitted on `0..1` and says something arbitrary outside it.
     */
    fun velocity(at: Vec3, t: Double): Vec3 {
        val x = frame.toNormalized(at, dimension)
        val v = evaluate(x, t)
        return vec(v[0] * frame.scale, v[1] * frame.scale, if (dimension == 3) v[2] * frame.scale else 0.0)
    }

    /**
     * Draws [count] points from the source and follows the field to `t = 1`.
     *
     * This is the generator. On a field trained without a `from` cloud the source is a Gaussian, so
     * every call produces genuinely new points; on a morph it draws from the source cloud instead,
     * which means the answer is a re-sampling of it and not new shape.
     *
     * [steps] is the integrator's step count, and the accuracy knob — see [FlowIntegrator]. Too few
     * and points fall short of the target or overshoot into places the shape does not go.
     */
    fun sample(
        count: Int,
        steps: Int = 32,
        integrator: FlowIntegrator = FlowIntegrator.MIDPOINT,
        seed: Long? = null,
    ): List<Vec3> {
        require(count >= 0) { "cannot draw $count samples" }
        val random = seed?.let { Random(it) } ?: Random(Random.nextLong())
        val x = DoubleArray(dimension)
        return List(count) {
            drawInto(x, sourcePoints, random, 0.0)
            integrate(x, 0.0, 1.0, steps, integrator)
            frame.toWorld(x, dimension)
        }
    }

    /**
     * Carries [points] along the field from time [from] to time [to].
     *
     * Order is preserved — the `i`-th result is the `i`-th input moved — which is what makes this the
     * call for a morph: `transport(sourceCloud, to = 0.5)` is the half-way shape, and it is a real
     * intermediate distribution rather than a vertex-wise blend of two clouds.
     *
     * [from] and [to] may run backwards (`from = 1.0, to = 0.0`) to invert the flow, which on a
     * generator maps data back toward the Gaussian.
     */
    fun transport(
        points: List<Vec3>,
        from: Double = 0.0,
        to: Double = 1.0,
        steps: Int = 32,
        integrator: FlowIntegrator = FlowIntegrator.MIDPOINT,
    ): List<Vec3> = points.map {
        val x = frame.toNormalized(it, dimension)
        integrate(x, from, to, steps, integrator)
        frame.toWorld(x, dimension)
    }

    /**
     * The trajectories of [count] fresh samples, one FLine each, from `t = 0` to `t = 1`.
     *
     * Each line has [steps] segments, so the vertices are the integrator's own steps — no extra cost
     * over [sample] beyond keeping them. A tangle of these is the quickest read on whether a field has
     * trained: they should sweep smoothly from the source onto the shape without knotting or stalling.
     */
    fun paths(
        count: Int,
        steps: Int = 32,
        integrator: FlowIntegrator = FlowIntegrator.MIDPOINT,
        seed: Long? = null,
    ): List<FLine> {
        require(count >= 0) { "cannot draw $count paths" }
        val random = seed?.let { Random(it) } ?: Random(Random.nextLong())
        val x = DoubleArray(dimension)
        return List(count) {
            drawInto(x, sourcePoints, random, 0.0)
            traceLine(x, 0.0, 1.0, steps, integrator)
        }
    }

    /** The trajectories of [points] specifically, from time [from] to time [to] — see [paths]. */
    fun pathsFrom(
        points: List<Vec3>,
        from: Double = 0.0,
        to: Double = 1.0,
        steps: Int = 32,
        integrator: FlowIntegrator = FlowIntegrator.MIDPOINT,
    ): List<FLine> = points.map { traceLine(frame.toNormalized(it, dimension), from, to, steps, integrator) }

    // -- internals, all in the normalised frame -------------------------------------------------

    private val input = DoubleArray(net.inputs)
    private val velocityA = DoubleArray(dimension)
    private val velocityB = DoubleArray(dimension)
    private val midpoint = DoubleArray(dimension)

    /** The field at normalised [x] and time [t], into a buffer that is overwritten on the next call. */
    private fun evaluate(x: DoubleArray, t: Double, into: DoubleArray = velocityA): DoubleArray {
        for (d in 0 until dimension) input[d] = x[d]
        encodeTime(input, dimension, t, timeFeatures)
        return net.predict(input, into)
    }

    /** Integrates normalised [x] in place from [from] to [to]. */
    private fun integrate(x: DoubleArray, from: Double, to: Double, steps: Int, integrator: FlowIntegrator) {
        require(steps > 0) { "integration needs at least one step, got $steps" }
        val h = (to - from) / steps
        var t = from
        for (step in 0 until steps) {
            when (integrator) {
                FlowIntegrator.EULER -> {
                    val v = evaluate(x, t, velocityA)
                    for (d in 0 until dimension) x[d] += h * v[d]
                }

                FlowIntegrator.MIDPOINT -> {
                    val v = evaluate(x, t, velocityA)
                    for (d in 0 until dimension) midpoint[d] = x[d] + 0.5 * h * v[d]
                    val vm = evaluate(midpoint, t + 0.5 * h, velocityB)
                    for (d in 0 until dimension) x[d] += h * vm[d]
                }
            }
            t = from + h * (step + 1)
        }
    }

    /** Integrates normalised [x], keeping every step as a vertex of the returned FLine. */
    private fun traceLine(x: DoubleArray, from: Double, to: Double, steps: Int, integrator: FlowIntegrator): FLine {
        val line = FLine().moveTo(frame.toWorld(x, dimension))
        val h = (to - from) / steps
        for (step in 0 until steps) {
            integrate(x, from + h * step, from + h * (step + 1), 1, integrator)
            line.lineTo(frame.toWorld(x, dimension))
        }
        return line
    }
}

// ---------------------------------------------------------------------------------------------
// Shared machinery
// ---------------------------------------------------------------------------------------------

/**
 * The centre-and-scale that maps input coordinates to the frame training happens in.
 *
 * [scale] is a single isotropic factor, not one per axis: a per-axis normalisation would stretch a
 * shape to fill the unit ball and hand back something with the wrong aspect ratio. A cloud with no
 * extent at all — every point identical — would give a scale of zero, so that degenerate case is
 * pinned to 1 and the field simply learns to sit still.
 */
internal class FlowFrame(val center: DoubleArray, val scale: Double, val planeZ: Float) {

    fun normalize(points: List<Vec3>): Array<DoubleArray> =
        Array(points.size) { toNormalized(points[it], center.size) }

    fun toNormalized(point: Vec3, dimension: Int): DoubleArray {
        val x = DoubleArray(dimension)
        x[0] = (point.x - center[0]) / scale
        x[1] = (point.y - center[1]) / scale
        if (dimension == 3) x[2] = (point.z - center[2]) / scale
        return x
    }

    fun toWorld(x: DoubleArray, dimension: Int): Vec3 = vec(
        center[0] + scale * x[0],
        center[1] + scale * x[1],
        if (dimension == 3) center[2] + scale * x[2] else planeZ.toDouble(),
    )

    companion object {
        fun of(points: List<Vec3>, dimension: Int): FlowFrame {
            val center = DoubleArray(dimension)
            for (p in points) {
                center[0] += p.x
                center[1] += p.y
                if (dimension == 3) center[2] += p.z
            }
            for (d in 0 until dimension) center[d] /= points.size

            var meanSquare = 0.0
            for (p in points) {
                val dx = p.x - center[0]
                val dy = p.y - center[1]
                meanSquare += dx * dx + dy * dy
                if (dimension == 3) {
                    val dz = p.z - center[2]
                    meanSquare += dz * dz
                }
            }
            meanSquare /= points.size * dimension

            val scale = sqrt(meanSquare).takeIf { it > 1e-9 } ?: 1.0
            return FlowFrame(center, scale, points.first().z)
        }
    }
}

/**
 * 2 if every point shares the same `z`, 3 otherwise.
 *
 * The tolerance is relative to the cloud's extent in `x` and `y`, so a line that is flat to within a
 * ten-thousandth of its own size reads as flat. Exact equality would be too strict — points that came
 * through a float matrix are flat to rounding, not to the bit.
 */
private fun inferDimension(points: List<Vec3>): Int {
    if (points.isEmpty()) return 3

    var minZ = Float.MAX_VALUE
    var maxZ = -Float.MAX_VALUE
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in points) {
        minZ = min(minZ, p.z); maxZ = max(maxZ, p.z)
        minX = min(minX, p.x); maxX = max(maxX, p.x)
        minY = min(minY, p.y); maxY = max(maxY, p.y)
    }

    val planarExtent = max(maxX - minX, maxY - minY)
    val tolerance = max(1e-6f, 1e-4f * planarExtent)
    return if (maxZ - minZ <= tolerance) 2 else 3
}

/**
 * Writes `t` and its Fourier features into [input] just past the [dimension] position components.
 *
 * Octave `k` is `sin(2ᵏπt)` and `cos(2ᵏπt)`: a half-cycle over the trajectory at `k = 0`, doubling
 * from there. See [FlowMatchOptions.timeFeatures] for why raw `t` alone is usually not enough.
 */
private fun encodeTime(input: DoubleArray, dimension: Int, t: Double, timeFeatures: Int) {
    input[dimension] = t
    for (k in 0 until timeFeatures) {
        val omega = PI * (1 shl k)
        input[dimension + 1 + 2 * k] = sin(omega * t)
        input[dimension + 2 + 2 * k] = cos(omega * t)
    }
}

/** Draws one point into [into] — uniformly from [cloud], or from a standard Gaussian if it is null. */
private fun drawInto(into: DoubleArray, cloud: Array<DoubleArray>?, random: Random, jitter: Double) {
    if (cloud == null) {
        for (d in into.indices) into[d] = random.nextGaussian()
        return
    }
    drawIndexInto(into, cloud, random.nextInt(cloud.size), random, jitter)
}

/** Copies `cloud[index]` into [into], jittered — the draw with the choice already made. */
private fun drawIndexInto(
    into: DoubleArray,
    cloud: Array<DoubleArray>,
    index: Int,
    random: Random,
    jitter: Double,
) {
    val picked = cloud[index]
    for (d in into.indices) into[d] = picked[d] + if (jitter > 0.0) jitter * random.nextGaussian() else 0.0
}

/**
 * Permutes [x1] so that the total squared travel distance to [x0] is as small as it can be.
 *
 * This is the minibatch optimal transport of [FlowCoupling.MINIBATCH_OT], and it is exactly an
 * assignment problem: rows are source points, columns targets, cost the squared distance. Squared
 * rather than plain distance because that is the cost whose optimal transport has straight
 * characteristic paths, which is the property being bought here.
 */
private fun coupleByAssignment(x0: Array<DoubleArray>, x1: Array<DoubleArray>) {
    val n = x0.size
    if (n < 2) return

    val cost = Array(n) { i ->
        DoubleArray(n) { j ->
            var squared = 0.0
            for (d in x0[i].indices) {
                val delta = x0[i][d] - x1[j][d]
                squared += delta * delta
            }
            squared
        }
    }

    val assignment = HungarianAssignment.solve(cost)
    val permuted = Array(n) { x1[assignment[it]] }
    for (i in 0 until n) x1[i] = permuted[i]
}

/** Mean of up to [count] entries ending at index [end] inclusive. */
private fun DoubleArray.meanOfLast(end: Int, count: Int): Double {
    val first = max(0, end - count + 1)
    var total = 0.0
    for (i in first..end) total += this[i]
    return total / (end - first + 1)
}

// ---------------------------------------------------------------------------------------------
// Measuring the result
// ---------------------------------------------------------------------------------------------

/**
 * Symmetric mean nearest-neighbour distance between two point clouds — how far apart two *shapes*
 * are, in input coordinates.
 *
 * The number is the mean over every point in [a] of its distance to the nearest point of [b], plus
 * the same with the clouds swapped. Both halves are needed: the one-sided version is happy for [a] to
 * cover a corner of [b] and ignore the rest.
 *
 * This is the practical way to grade a [FlowMatchField], because the training loss cannot — a velocity
 * fit can be excellent while the samples sit slightly off. It is a distance between *sample sets*,
 * though, not between distributions: it notices a shape in the wrong place and it does not notice a
 * shape whose points are bunched in the wrong proportions.
 *
 * Brute force, `O(|a|·|b|)`. Fine at the few-thousand points a flow field is trained on; use
 * [vec3NN] for anything bigger.
 */
fun chamferDistance(a: List<Vec3>, b: List<Vec3>): Double {
    require(a.isNotEmpty() && b.isNotEmpty()) { "chamferDistance needs two non-empty clouds" }
    return oneSidedChamfer(a, b) + oneSidedChamfer(b, a)
}

private fun oneSidedChamfer(from: List<Vec3>, to: List<Vec3>): Double {
    var total = 0.0
    for (p in from) {
        var nearest = Double.MAX_VALUE
        for (q in to) {
            val dx = (p.x - q.x).toDouble()
            val dy = (p.y - q.y).toDouble()
            val dz = (p.z - q.z).toDouble()
            val squared = dx * dx + dy * dy + dz * dz
            if (squared < nearest) nearest = squared
        }
        total += sqrt(nearest)
    }
    return total / from.size
}
