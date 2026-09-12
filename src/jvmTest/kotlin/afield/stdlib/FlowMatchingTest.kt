package afield.stdlib

import afield.graphics.FLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import smile.util.function.TimeFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The claims in `flowmatching.kt` that arithmetic can check, plus the one it cannot — that the thing
 * learns a distribution — checked against shapes whose answer is known in closed form.
 *
 * Two oracles do most of the work here. A ring's radius is a single number, so "did the samples land
 * on the target" becomes an assertion about the mean and spread of one statistic rather than a
 * picture. And where a correspondence is known, [FlowMatchField.transport] can be graded point by
 * point — *this* point was supposed to end up *there* — which is far sharper than any statistic of
 * the cloud.
 *
 * Budgets are smaller than [FlowMatchOptions]'s defaults to keep the suite quick. Tolerances are set
 * from what a run near the edge of acceptable actually produces rather than from what a good one
 * does, so they fail on a regression and not on a slow day; the numbers quoted in comments are from
 * the measurements these tests were written against.
 */
class FlowMatchingTest {

    // ---- the network ------------------------------------------------------------------------

    @Test
    fun vectorMlpFitsAMultiOutputMapWhichSmilesOwnRegressionMlpCannotRepresent() {
        // Smile draws its initial weights from its own thread-local generator, so without this the
        // starting point -- and therefore the residual error -- varies from run to run.
        smile.math.MathEx.setSeed(4242)

        val steps = 4000
        val net = VectorMLP(inputs = 1, outputs = 2, hidden = intArrayOf(16, 16))
        // The decaying schedule, not the constant one: at a constant rate this fit stalls around
        // 0.2 and gets no better with more steps. See decayingTheLearningRateIsWhatMakesMoreStepsHelp.
        net.configure(TimeFunction.linear(1e-2, steps.toDouble(), 1e-4), momentum = 0.0, rho = 0.9)

        val random = Random(7)
        val batch = 32
        repeat(steps) {
            for (i in 0 until batch) {
                val x = random.nextDouble(-1.0, 1.0)
                net.accumulate(doubleArrayOf(x), doubleArrayOf(2.0 * x, -x))
            }
            net.step(batch)
        }

        // The ends of the training range are the worst case for a piecewise-linear fit, so they set
        // the tolerance; the interior is an order of magnitude better than this.
        for (x in listOf(-0.8, -0.3, 0.0, 0.4, 0.9)) {
            val predicted = net.predict(doubleArrayOf(x))
            assertEquals(2.0 * x, predicted[0], 0.05, "first output at x=$x")
            assertEquals(-x, predicted[1], 0.05, "second output at x=$x")
        }
    }

    @Test
    fun vectorMlpBuildsTheArchitectureItWasAskedFor() {
        val net = VectorMLP(inputs = 3, outputs = 2, hidden = intArrayOf(8, 5), activation = "tanh")
        // 3->8, 8->5, 5->2, each layer carrying a bias per neuron.
        assertEquals((3 * 8 + 8) + (8 * 5 + 5) + (5 * 2 + 2), net.parameterCount())

        // Smile's own description of the net it built -- the output head must be linear with squared
        // error, because a velocity is unbounded in both directions.
        val description = net.toString()
        assertTrue(description.startsWith("Input(3) -> TANH(8) -> TANH(5) -> LINEAR(2)"), description)
        assertTrue(description.contains("MEAN_SQUARED_ERROR"), description)
    }

    @Test
    fun vectorMlpRejectsShapesItCannotHold() {
        val net = VectorMLP(inputs = 2, outputs = 2, hidden = intArrayOf(8))
        assertFailsWith<IllegalArgumentException> { net.predict(doubleArrayOf(1.0)) }
        assertFailsWith<IllegalArgumentException> {
            net.accumulate(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0))
        }
        assertFailsWith<IllegalArgumentException> {
            VectorMLP(inputs = 2, outputs = 2, hidden = intArrayOf())
        }
    }

    // ---- generating a 2D shape --------------------------------------------------------------

    @Test
    fun aFieldTrainedOnARingGeneratesPointsOnThatRing() {
        val target = ring(radius = 200.0, count = 400, seed = 1, centre = Pair(500.0, 300.0))
        val field = flowMatch(target, options = quickOptions(seed = 11))

        assertEquals(2, field.dimension, "a flat ring is a 2D problem")

        val samples = field.sample(400, seed = 12)
        val radii = samples.map { hypot(it.x - 500.0, it.y - 300.0) }
        val meanRadius = radii.average()
        val spread = sqrt(radii.sumOf { (it - meanRadius) * (it - meanRadius) } / radii.size)

        // Measured: mean radius within a few of 200, radial spread around 12 at this budget.
        assertTrue(
            abs(meanRadius - 200.0) < 20.0,
            "samples should sit on the ring: mean radius $meanRadius, wanted 200 +/- 20",
        )
        assertTrue(spread < 25.0, "samples should not smear across the annulus: radius sd $spread")

        // A collapsed field would put every sample in one place, which the radius alone cannot see.
        val octants = samples.map { (((atan2(it.y - 300.0, it.x - 500.0) + 2 * PI) % (2 * PI)) / (PI / 4)).toInt() }
        assertEquals(8, octants.distinct().size, "the whole ring should be covered, not an arc of it")

        val chamfer = chamferDistance(samples, target)
        assertTrue(chamfer < 25.0, "chamfer to the target cloud was $chamfer")
    }

    /**
     * `leaky` beat `tanh` by about four times on radial spread when this was measured, which is why
     * it is the default. The assertion is loose because the point is the ordering, not the ratio.
     */
    @Test
    fun aSaturatingActivationFitsTheFarFieldWorseThanARectifyingOne() {
        val target = ring(radius = 100.0, count = 400, seed = 2)

        fun spreadWith(activation: String): Double {
            val field = flowMatch(target, options = quickOptions(seed = 12).copy(activation = activation))
            val radii = field.sample(300, seed = 13).map { hypot(it.x.toDouble(), it.y.toDouble()) }
            val mean = radii.average()
            return sqrt(radii.sumOf { (it - mean) * (it - mean) } / radii.size)
        }

        val leaky = spreadWith("leaky")
        val tanh = spreadWith("tanh")
        assertTrue(
            leaky < tanh,
            "leaky should hold the ring tighter than tanh: leaky $leaky against tanh $tanh",
        )
    }

    @Test
    fun samplesStayOnThePlaneTheTrainingDataCameFrom() {
        val target = ring(radius = 1.0, count = 200, seed = 3).map { vec(it.x, it.y, 42.0) }
        val field = flowMatch(target, options = quickOptions(seed = 13, steps = 300))

        assertEquals(2, field.dimension)
        for (p in field.sample(50, seed = 14)) {
            assertEquals(42.0f, p.z, 0.0f, "a 2D field must not invent a z")
        }
    }

    // ---- generating a 3D shape --------------------------------------------------------------

    @Test
    fun aFieldTrainedOnAHelixGeneratesPointsAlongThatHelix() {
        val line = FLine()
        for (i in 0..300) {
            val s = i / 300.0
            val angle = 4 * PI * s
            val point = vec(100.0 * cos(angle), 100.0 * sin(angle), 300.0 * s)
            if (i == 0) line.moveTo(point) else line.lineTo(point)
        }

        val target = flowSamples(line, 500, jitter = 2.0, seed = 4)
        val field = flowMatch(target, options = quickOptions(seed = 15, steps = 2500))

        assertEquals(3, field.dimension, "a helix varies in z, so it is a 3D problem")

        val samples = field.sample(500, seed = 16)
        // Every point of a radius-100 helix is 100 from the z axis, whatever its height.
        val axisDistances = samples.map { hypot(it.x.toDouble(), it.y.toDouble()) }
        assertTrue(
            abs(axisDistances.average() - 100.0) < 25.0,
            "samples should hug the helix's cylinder: mean axis distance ${axisDistances.average()}",
        )
        val chamfer = chamferDistance(samples, target)
        assertTrue(chamfer < 40.0, "chamfer to the target cloud was $chamfer")
    }

    // ---- interpolating between two shapes ----------------------------------------------------

    @Test
    fun transportHalfWayLandsBetweenTheTwoShapesItWasTrainedOn() {
        val inner = ring(radius = 50.0, count = 300, seed = 5)
        val outer = ring(radius = 200.0, count = 300, seed = 6)
        val field = flowMatch(to = outer, from = inner, options = quickOptions(seed = 17))

        assertTrue(field.hasSourceCloud, "a field given a from cloud should say so")

        fun meanRadius(points: List<Vec3>) = points.map { hypot(it.x.toDouble(), it.y.toDouble()) }.average()

        assertEquals(50.0, meanRadius(field.transport(inner, to = 0.0)), 1e-4, "t=0 must not move anything")
        val half = meanRadius(field.transport(inner, to = 0.5))
        val whole = meanRadius(field.transport(inner, to = 1.0))

        assertTrue(half > 60.0 && half < 190.0, "half way should be between the two radii, got $half")
        assertTrue(abs(whole - 200.0) < 40.0, "the whole way should approach radius 200, got $whole")
        assertTrue(half < whole, "half way should not have travelled further than the whole way")
    }

    @Test
    fun transportPreservesOrderSoAMorphCanBeDrawn() {
        val inner = ring(radius = 50.0, count = 64, seed = 7)
        val outer = ring(radius = 200.0, count = 64, seed = 8)
        val field = flowMatch(to = outer, from = inner, options = quickOptions(seed = 18, steps = 300))

        val moved = field.transport(inner, to = 0.3, steps = 8)
        assertEquals(inner.size, moved.size)

        val paths = field.pathsFrom(inner, to = 0.3, steps = 8)
        assertEquals(inner.size, paths.size, "one path per point, in order")
        for (i in inner.indices) {
            assertEquals(inner[i].x, paths[i].nodes.first().to.x, 1e-3f, "path $i should start at point $i")
            assertEquals(moved[i].x, paths[i].nodes.last().to.x, 1e-3f, "path $i should end where transport did")
            assertEquals(9, paths[i].nodes.size, "8 integration steps is 9 vertices")
        }
    }

    // ---- the paired case: an explicit correspondence ------------------------------------------

    /**
     * With `k` and `s` already known, point `i` of the canonical strand is the same material point as
     * point `i` of the recovered curve, and the flow can be graded as a *map* rather than as a cloud.
     * Measured mean landing error here was under 2% of the shape's extent.
     */
    @Test
    fun pairedCouplingLandsEachPointNearItsOwnTarget() {
        val n = 300
        val target = closedCurveBySampleOrder(n, radius = 100.0)
        val canonical = closedCurveBySampleOrder(n, radius = 30.0)

        val field = flowMatch(
            to = target,
            from = canonical,
            options = quickOptions(seed = 19).copy(coupling = FlowCoupling.PAIRED),
        )

        val landed = field.transport(canonical, steps = 64)
        val miss = landed.indices.sumOf { distance(landed[it], target[it]) } / n
        assertTrue(miss < 12.0, "each point should land near its own target, mean miss was $miss")

        // The loss floor is the giveaway: a known correspondence leaves almost nothing to average
        // over, so this sits near zero where an unpaired coupling sits near the data's variance.
        assertTrue(field.loss(200) < 0.1, "paired training should drive the loss near zero, got ${field.loss(200)}")
    }

    /**
     * §9 of the design, as an experiment: a flow cannot change topology, and asking it to is the
     * dominant error term rather than a technicality. Measured: about 2% of extent from a matched
     * circle against about 29% from a segment.
     */
    @Test
    fun aClosedTargetNeedsAClosedSourceBecauseAFlowCannotTearOrJoin() {
        val n = 300
        val target = closedCurveBySampleOrder(n, radius = 100.0)
        val matchedCircle = closedCurveBySampleOrder(n, radius = 30.0)
        val clashingSegment = List(n) {
            val s = it / (n - 1.0)
            vec(200.0 * (s - 0.5), -260.0, 0.0)
        }

        fun missFrom(source: List<Vec3>): Double {
            val field = flowMatch(
                to = target,
                from = source,
                options = quickOptions(seed = 20).copy(coupling = FlowCoupling.PAIRED),
            )
            val landed = field.transport(source, steps = 64)
            return landed.indices.sumOf { distance(landed[it], target[it]) } / n
        }

        val matched = missFrom(matchedCircle)
        val clashing = missFrom(clashingSegment)
        assertTrue(
            clashing > 3 * matched,
            "a topology clash should cost far more than a matched source: " +
                "segment $clashing against circle $matched",
        )
    }

    @Test
    fun pairedCouplingNeedsTwoCloudsOfEqualSize() {
        val a = ring(radius = 50.0, count = 32, seed = 9)
        val b = ring(radius = 100.0, count = 32, seed = 10)
        val paired = FlowMatchOptions(steps = 1, coupling = FlowCoupling.PAIRED)

        assertFailsWith<IllegalArgumentException> { flowMatch(b, options = paired) }
        assertFailsWith<IllegalArgumentException> {
            flowMatch(b, from = a.take(16), options = paired)
        }
        flowMatch(b, from = a, options = paired) // equal sizes, with a source: fine
    }

    // ---- reproducibility and argument checking -----------------------------------------------

    @Test
    fun trainingAndSamplingAreReproducibleFromTheirSeeds() {
        val target = ring(radius = 100.0, count = 128, seed = 11)

        val first = flowMatch(target, options = quickOptions(seed = 21, steps = 200))
        val second = flowMatch(target, options = quickOptions(seed = 21, steps = 200))
        assertTrue(
            first.losses.contentEquals(second.losses),
            "the same seed should train the same field, including its initial weights",
        )

        val a = first.sample(20, seed = 99)
        val b = first.sample(20, seed = 99)
        for (i in a.indices) assertEquals(a[i].x, b[i].x, 0.0f, "sample $i")

        val different = first.sample(20, seed = 100)
        assertTrue(a.indices.any { abs(a[it].x - different[it].x) > 1e-4f }, "a new seed should draw elsewhere")
    }

    @Test
    fun midpointIntegrationBeatsEulerAtTheSameStepCount() {
        val target = ring(radius = 100.0, count = 300, seed = 12)
        val field = flowMatch(target, options = quickOptions(seed = 22))

        // Many-step midpoint is the reference answer; both cheap integrators are judged against it.
        val reference = field.sample(200, steps = 256, integrator = FlowIntegrator.MIDPOINT, seed = 31)
        val euler = field.sample(200, steps = 4, integrator = FlowIntegrator.EULER, seed = 31)
        val midpoint = field.sample(200, steps = 4, integrator = FlowIntegrator.MIDPOINT, seed = 31)

        val eulerError = meanDistance(euler, reference)
        val midpointError = meanDistance(midpoint, reference)
        assertTrue(
            midpointError < eulerError,
            "midpoint ($midpointError) should track the reference better than Euler ($eulerError)",
        )
    }

    @Test
    fun dimensionCanBeForcedWhenInferenceWouldGuessWrong() {
        val flat = ring(radius = 100.0, count = 64, seed = 13)
        val quick = quickOptions(seed = 23, steps = 100)
        assertEquals(3, flowMatch(flat, options = quick.copy(dimension = 3)).dimension)
        assertEquals(2, flowMatch(flat, options = quick).dimension)
    }

    @Test
    fun flowMatchRejectsInputsItCannotTrainOn() {
        val target = ring(radius = 1.0, count = 8, seed = 14)
        assertFailsWith<IllegalArgumentException> { flowMatch(emptyList()) }
        assertFailsWith<IllegalArgumentException> { flowMatch(target, from = emptyList()) }
        assertFailsWith<IllegalArgumentException> { flowMatch(target, options = FlowMatchOptions(steps = 0)) }
        assertFailsWith<IllegalArgumentException> {
            flowMatch(target, options = FlowMatchOptions(steps = 1, jitter = -1.0))
        }
        assertFailsWith<IllegalArgumentException> {
            flowMatch(target, options = FlowMatchOptions(steps = 1, dimension = 4))
        }
        assertFailsWith<IllegalArgumentException> {
            flowMatch(target, options = FlowMatchOptions(steps = 1, timeFeatures = -1))
        }
        assertFailsWith<IllegalArgumentException> { chamferDistance(emptyList(), target) }
    }

    @Test
    fun chamferDistanceIsSymmetricAndZeroOnlyForCoincidentClouds() {
        val a = listOf(vec(0, 0, 0), vec(10, 0, 0))
        assertEquals(0.0, chamferDistance(a, listOf(vec(0, 0, 0), vec(10, 0, 0))), 1e-9)

        val c = listOf(vec(0, 3, 0), vec(10, 3, 0))
        assertEquals(6.0, chamferDistance(a, c), 1e-6, "each side contributes a mean of 3")
        assertEquals(chamferDistance(a, c), chamferDistance(c, a), 1e-9)
    }

    @Test
    fun flowSamplesJittersOffTheLineWithoutLeavingItsPlane() {
        val line = FLine().moveTo(vec(0, 0, 0)).lineTo(vec(100, 0, 0))

        val clean = flowSamples(line, 11)
        assertEquals(11, clean.size)
        assertEquals(0.0f, clean.last().y, 1e-4f)
        assertEquals(100.0f, clean.last().x, 1e-3f)

        val noisy = flowSamples(line, 200, jitter = 3.0, seed = 5)
        val offsets = noisy.map { it.y.toDouble() }
        val sd = sqrt(offsets.sumOf { it * it } / offsets.size)
        assertTrue(abs(sd - 3.0) < 1.0, "jitter should have the requested sd, got $sd")
        assertTrue(noisy.all { it.z == 0.0f }, "a flat line's samples must stay flat")

        assertFailsWith<IllegalArgumentException> { flowSamples(line, 1) }
        assertFailsWith<IllegalArgumentException> { flowSamples(line, 10, jitter = -1.0) }
    }

    /**
     * Minibatch OT drives the training loss far below independent coupling, because it removes most
     * of the path crossing the loss is averaging over. It does *not* follow that the generator is
     * better, which is why [FlowCoupling.MINIBATCH_OT] is documented the way it is and is not the
     * default — only the loss gap is asserted here, because that is the part that reproduces.
     */
    @Test
    fun minibatchOptimalTransportLowersTheLossItIsAveragingOver() {
        val target = ring(radius = 100.0, count = 200, seed = 15)
        val options = quickOptions(seed = 24, steps = 800)

        val independent = flowMatch(target, options = options.copy(coupling = FlowCoupling.INDEPENDENT))
        val optimal = flowMatch(target, options = options.copy(coupling = FlowCoupling.MINIBATCH_OT))

        assertTrue(
            optimal.loss(200) < 0.5 * independent.loss(200),
            "OT coupling should cut the loss substantially: " +
                "OT ${optimal.loss(200)} against independent ${independent.loss(200)}",
        )
    }

    /**
     * The learning-rate decay decides whether training converges at all. At a constant rate RMSProp
     * keeps taking same-sized steps near the answer, so the fit stops at a noise floor: on the paired
     * case, *more* steps at a constant rate moved the landing error further out, while more steps
     * with the decay brought it in.
     */
    @Test
    fun decayingTheLearningRateIsWhatMakesMoreStepsHelp() {
        val n = 200
        val target = List(n) {
            val s = it / (n - 1.0)
            vec(300.0 * (s - 0.5), 80.0 * sin(3 * PI * s), 0.0)
        }
        val source = List(n) { vec(300.0 * (it / (n - 1.0) - 0.5), -200.0, 0.0) }
        val paired = quickOptions(seed = 26, steps = 3000).copy(coupling = FlowCoupling.PAIRED)

        fun missWith(options: FlowMatchOptions): Double {
            val field = flowMatch(target, source, options = options)
            val landed = field.transport(source, steps = 64)
            return landed.indices.sumOf { distance(landed[it], target[it]) } / n
        }

        val decayed = missWith(paired)
        val constant = missWith(paired.copy(finalLearningRate = null))
        assertTrue(
            decayed < constant,
            "a decayed rate should land closer: decayed $decayed against constant $constant",
        )
    }

    // ---- what it costs -----------------------------------------------------------------------

    @Test
    fun trainingCostIsWorthReportingRatherThanAsserting() {
        val target = ring(radius = 100.0, count = 500, seed = 16)
        val options = FlowMatchOptions(steps = 1000, batch = 48, seed = 25)

        val started = System.nanoTime()
        val field = flowMatch(target, options = options)
        val trainMillis = (System.nanoTime() - started) / 1e6

        val sampleStarted = System.nanoTime()
        val samples = field.sample(1000, steps = 32)
        val sampleMillis = (System.nanoTime() - sampleStarted) / 1e6

        println(
            "flowMatch: %d steps x %d batch, %d parameters -> %.0f ms (%.2f ms/step); ".format(
                options.steps, options.batch, field.parameterCount, trainMillis, trainMillis / options.steps,
            ) + "1000 samples x 32 midpoint steps -> %.0f ms; final loss %.4f, chamfer %.2f".format(
                sampleMillis, field.loss(100), chamferDistance(samples, target),
            )
        )

        // Only a smoke bound, so a 100x slowdown is caught and ordinary machine noise is not.
        assertTrue(trainMillis < 120_000, "1000 training steps took ${trainMillis}ms")
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Small budgets so the suite stays quick; the defaults in [FlowMatchOptions] are larger. */
    private fun quickOptions(seed: Long, steps: Int = 1500) =
        FlowMatchOptions(steps = steps, batch = 48, hidden = intArrayOf(64, 64), seed = seed)

    /** [count] points scattered uniformly around a circle — a shape whose radius is one number. */
    private fun ring(
        radius: Double,
        count: Int,
        seed: Long,
        centre: Pair<Double, Double> = Pair(0.0, 0.0),
    ): List<Vec3> {
        val random = Random(seed)
        return List(count) {
            val angle = random.nextDouble(0.0, 2 * PI)
            vec(centre.first + radius * cos(angle), centre.second + radius * sin(angle), 0.0)
        }
    }

    /**
     * A circle whose points are in order of its own arc coordinate, so that point `i` of two such
     * lists at different radii are the same material point — which is what [FlowCoupling.PAIRED]
     * needs and what a recovered `s` coordinate would supply.
     */
    private fun closedCurveBySampleOrder(count: Int, radius: Double): List<Vec3> = List(count) {
        val s = it / (count - 1.0)
        vec(radius * cos(2 * PI * s), radius * sin(2 * PI * s), 0.0)
    }

    private fun distance(a: Vec3, b: Vec3): Double = sqrt(
        ((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)).toDouble()
    )

    private fun meanDistance(a: List<Vec3>, b: List<Vec3>): Double =
        a.indices.sumOf { distance(a[it], b[it]) } / a.size
}
