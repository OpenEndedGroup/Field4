package afield.examples.four

import afield.four.Renderer
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.FlowIntegrator
import afield.stdlib.FlowMatchOptions
import afield.stdlib.MlpWeights
import afield.stdlib.ShaderNeuralParticles
import afield.stdlib.Vec3
import afield.stdlib.flowMatch
import afield.stdlib.vec
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.neural-particles.smoke
runner: jvm-main
entrypoint: afield.examples.four.NeuralParticlesSmoke
tags: [smoke, four, gpu, compute, neural, particles, flow-matching]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "neural particles tests PASSED"
*/
/**
 * Step two of the neural-particle pilot: that a buffer of particles integrated on the GPU along a
 * trained field goes where the CPU says it should.
 *
 * `four.neural-field-parity.smoke` established that one forward pass agrees. This is the system
 * assertion on top of it — a whole trajectory, a hundred and fifty Euler steps of feedback, with
 * every step's error compounding into the next. A single evaluation can be right while the loop
 * around it is wrong: the wrong `dt`, a `t` that advances before the velocity is read rather than
 * after, a reset that fires a frame early. None of those change the picture into something that
 * looks broken. They change *where the shape ends up*, by a little.
 *
 * ### The assertion
 *
 * Read the particles' own `start` points back off the GPU, hand those same points to
 * [afield.stdlib.FlowMatchField.transport] with the same integrator and the same step count, and
 * compare the landings **point by point**. That is a far sharper oracle than any statistic of the
 * cloud: it asks "did particle *i* go where particle *i* was supposed to go", which a system that
 * produced a correct-looking shape by a wrong route would fail.
 *
 * Reading `start` back rather than regenerating it from the seed is deliberate — it means the
 * test cannot agree with itself about a source draw that never reached the GPU.
 *
 * Two supporting checks, because the sharp one has a blind spot at zero:
 *
 * - **The particles moved at all.** A compute pass that never ran leaves every particle on its
 *   start point, and "GPU agrees with CPU" would then be a claim about two numbers that are both
 *   the input.
 * - **They landed on the shape.** The target is a ring of known radius, so the landing radius has
 *   a closed form. This is the end-to-end check that the *field* is the thing being integrated
 *   and not, say, a buffer of zeroes read through a correctly-wired shader.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.neural-particles.smoke
 * ```
 *
 * Needs a GPU and an awake display.
 */
object NeuralParticlesSmoke {

    /** Radius of the target ring, in input coordinates. Arbitrary, and deliberately not 1. */
    private const val RING_RADIUS = 140.0

    /** Particles. Small: this is a correctness run, not a load test. */
    private const val PARTICLES = 4096

    /** Euler steps from `t = 0` to `t = 1`, and therefore frames to render. */
    private const val STEPS = 150

    /**
     * Allowed disagreement between a GPU landing and a CPU landing, relative to the ring radius.
     *
     * Both sides run Euler with the same step count from the same point, so they are computing
     * the same finite sum and would agree exactly in exact arithmetic. What is left is fp32
     * against fp64, carried through 150 steps of feedback.
     *
     * The interesting part is that this does **not** compound. The measured worst case is
     * 1.7e-06 of the ring radius — *smaller* than the 5.8e-06 relative error of a single forward
     * pass, after a hundred and fifty steps of it. A converging flow is contractive: every
     * trajectory is being pulled onto the same target, so a perturbation at step `k` is squeezed
     * out by the steps after it rather than amplified. Do not read that as a general licence —
     * it is a property of *this* field, and a field trained to do something divergent would
     * behave the other way — but it does mean an integrator error here shows up as a gross one
     * rather than as slow drift. Hence a tolerance with four orders of headroom: nothing lives in
     * the gap.
     */
    private const val LANDING_TOLERANCE = 0.02

    /** How many particles to grade individually. All of them, at this count. */
    private const val GRADED = PARTICLES

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("Neural Particles Smoke")
        val renderer = Renderer(window)

        try {
            // A ring, because its radius is a single number and therefore an oracle.
            val target = (0 until 900).map {
                val a = it * 2.0 * Math.PI / 900.0
                vec(RING_RADIUS * cos(a), RING_RADIUS * sin(a), 0.0)
            }
            val field = flowMatch(target, options = FlowMatchOptions(steps = 2500, seed = 19))
            val export = field.export()
            println("field: $export, final loss ${"%.4g".format(field.loss(200))}")

            val particles = ShaderNeuralParticles(export, renderer) {
                particleCount = PARTICLES
                stepsPerCycle = STEPS
                // Everything launches together, so that after exactly STEPS frames every particle
                // is at t = 1 and none has looped back to its start.
                phaseSpread = 0f
                randomSeed = 7L
            }
            particles.installInto()

            val starts = readParticles(particles).map { it.start }
            require(starts.size == PARTICLES)

            repeat(STEPS) {
                window.pollEvents()
                renderer.render()
            }
            renderer.windowContext().computeFences.forEach { it.fenceWait() }

            val landed = readParticles(particles)
            var failures = 0

            // 1. Did anything happen?
            val moved = landed.indices.count { hypot(landed[it].x - starts[it].x, landed[it].y - starts[it].y) > 1e-3 }
            println("moved: $moved of $PARTICLES")
            if (moved < PARTICLES * 0.99) {
                println("  FAIL: $moved of $PARTICLES particles moved; the compute pass did not run")
                failures++
            }

            // 2. Did they all reach t = 1, exactly once?
            val t = landed.map { it.t }
            println("t after $STEPS frames: min ${"%.4f".format(t.min())} max ${"%.4f".format(t.max())}")
            if (t.min() < 0.999 || t.max() > 1.001) {
                println("  FAIL: t should be 1.0 after $STEPS steps of 1/$STEPS; the clock is off")
                failures++
            }

            // 3. The sharp one: point-by-point against the CPU integrator.
            val cpu = field.transport(
                starts.take(GRADED).map { toWorld(export.center, export.scale, it.x, it.y) },
                from = 0.0,
                to = 1.0,
                steps = STEPS,
                integrator = FlowIntegrator.EULER,
            )
            var worst = 0.0
            var worstAt = -1
            for (i in 0 until GRADED) {
                val g = toWorld(export.center, export.scale, landed[i].x, landed[i].y)
                val d = hypot((g.x - cpu[i].x).toDouble(), (g.y - cpu[i].y).toDouble()) / RING_RADIUS
                if (d > worst) { worst = d; worstAt = i }
            }
            println("GPU vs CPU Euler, per particle: worst ${"%.3g".format(worst)} of the ring radius (particle $worstAt)")
            if (worst >= LANDING_TOLERANCE) {
                println("  FAIL: worst landing disagreement $worst exceeds $LANDING_TOLERANCE")
                failures++
            }

            // 4. Did they land on the shape at all?
            val radii = landed.map {
                val w = toWorld(export.center, export.scale, it.x, it.y)
                hypot(w.x.toDouble(), w.y.toDouble())
            }
            val meanRadius = radii.average()
            val spread = sqrt(radii.sumOf { (it - meanRadius) * (it - meanRadius) } / radii.size)
            println("landing radius: mean ${"%.1f".format(meanRadius)} (target $RING_RADIUS), spread ${"%.1f".format(spread)}")
            // Loose, and deliberately so: this grades the *training*, which is a 2500-step budget
            // chosen to keep the smoke quick. Its job is to catch a field that was never
            // integrated, not to hold flow matching to an accuracy `FlowMatchingTest` already owns.
            if (abs(meanRadius - RING_RADIUS) > RING_RADIUS * 0.15) {
                println("  FAIL: the cloud did not land on the ring")
                failures++
            }

            // 4. Swapping the weights really does change the field, without recompiling anything.
            //
            // `neural_particles.md` makes a specific claim about this — "retraining swaps a buffer
            // between frames and never recompiles a shader" — and `updateWeights` is the call it
            // tells a reader to make. Nothing ran it until now. The failure it guards against is
            // not subtle but it is silent: a weight upload that never reaches the GPU leaves a
            // system that still draws a perfectly good ring, just the ring it had before.
            //
            // Scaled by 0.5 rather than replaced by a second trained field, so the expected
            // outcome is arithmetic rather than another training run: halving every weight of a
            // leaky-ReLU network halves nothing predictably layer to layer, so this asserts only
            // that the landings *moved*, and by more than the integrator's own noise.
            val before = readParticles(particles).map { toWorld(export.center, export.scale, it.x, it.y) }
            val halved = MlpWeights(
                export.network.inputs, export.network.outputs, export.network.hidden,
                export.network.activation, FloatArray(export.network.flat.size) { export.network.flat[it] * 0.5f },
            )
            particles.updateWeights(halved)
            particles.reset()
            repeat(STEPS) { window.pollEvents(); renderer.render() }
            renderer.windowContext().computeFences.forEach { it.fenceWait() }
            val after = readParticles(particles).map { toWorld(export.center, export.scale, it.x, it.y) }

            val shifted = before.indices.sumOf {
                hypot((after[it].x - before[it].x).toDouble(), (after[it].y - before[it].y).toDouble())
            } / before.size
            println("  after halving the weights, landings moved by a mean of ${"%.3g".format(shifted)} (ring radius $RING_RADIUS)")
            if (shifted < RING_RADIUS * 0.05) {
                println("  FAIL: updateWeights did not reach the GPU — the field is unchanged")
                failures++
            }

            // And putting them back restores the original landings, which is the half of the
            // claim that says the buffer is the only state: if the swap had disturbed anything
            // else -- the module, the particle buffer, the source cloud -- this would not close.
            particles.updateWeights(export.network)
            particles.reset()
            repeat(STEPS) { window.pollEvents(); renderer.render() }
            renderer.windowContext().computeFences.forEach { it.fenceWait() }
            val restored = readParticles(particles).map { toWorld(export.center, export.scale, it.x, it.y) }
            val drift = before.indices.maxOf {
                hypot((restored[it].x - before[it].x).toDouble(), (restored[it].y - before[it].y).toDouble())
            }
            println("  restoring them returns the landings to within ${"%.3g".format(drift)}")
            if (drift > RING_RADIUS * 1e-3) {
                println("  FAIL: swapping the weights back did not restore the field")
                failures++
            }

            particles.removeFrom()

            if (failures > 0) {
                println("neural particles tests FAILED ($failures)")
                exitProcess(1)
            }
            println("neural particles tests PASSED")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }

    /** A particle's source point, in the training frame. */
    private class Start(val x: Float, val y: Float)

    /** One particle as the shader left it, in the training frame. */
    private class Particle(val x: Float, val y: Float, val start: Start, val t: Float)

    /** The particle buffer, decoded. */
    private fun readParticles(particles: ShaderNeuralParticles): List<Particle> {
        val out = ArrayList<Particle>(PARTICLES)
        particles.particleBuffer.get().mapAndRead {
            repeat(PARTICLES) {
                val px = get(); val py = get()
                val sx = get(); val sy = get()
                get(); get()                     // velocity, not graded
                val t = get()
                get()                            // pad
                out += Particle(px, py, Start(sx, sy), t)
            }
        }
        return out
    }

    /** Out of the training frame and back into input coordinates. */
    private fun toWorld(center: FloatArray, scale: Float, x: Float, y: Float): Vec3 =
        vec((center[0] + scale * x).toDouble(), (center[1] + scale * y).toDouble(), 0.0)
}
