package afield.examples.four

import afield.four.Renderer
import afield.four.physics.ShaderBoids
import afield.four.util.GpuNeighbourGrid
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Vector2f
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.shader-boids.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.ShaderBoidsSmoke
tags: [smoke, four, gpu, compute, boids, neighbours, particles]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "shader boids tests PASSED"
*/
/**
 * That GPU boids computes the *same force* as a CPU brute force over the same law, per particle.
 *
 * This is the reference implementation the pilot's learned interaction will eventually be
 * measured against, so what matters is that it is right, not that it looks like a flock. Looking
 * like a flock is nearly free: almost any attraction-plus-repulsion rule with a speed limit
 * produces something a viewer will accept, which is exactly why "it flocks" is not evidence.
 *
 * ### Held still on purpose
 *
 * Every acceleration check runs with `dt = 0`. Boids is chaotic — two implementations that agree
 * to seven digits will visibly diverge within a few hundred steps — so comparing trajectories
 * measures nothing but Lyapunov time. With `dt = 0` the configuration is fixed, the acceleration
 * is a pure function of it, and the comparison is exact up to summation order.
 *
 * The integration law is then checked from a single readback, because each struct carries the
 * velocity the step integrated from alongside the force and the result — so the check does not
 * depend on knowing how many frames ran, which it cannot.
 *
 * ### What it asserts
 *
 * - **Acceleration matches brute force, per particle**, for a uniform flock and for a clustered
 *   one where neighbourhoods are hundreds strong.
 * - **The speed limit binds.** Without it a cohesive flock accelerates without bound, and a
 *   `maxSpeed` that is silently not applied is invisible for the first few seconds.
 * - **Velocity follows the law**, checked from one readback: each struct carries the velocity
 *   the step integrated from, the force it computed, and the velocity it produced.
 * - **Turning the rule off zeroes the force.** All three weights at zero must give exactly zero
 *   acceleration — the cheap check that nothing else is contributing.
 * - **A trained Deep Sets interaction agrees with its own CPU reference**, per particle, in the
 *   same scene — the tier-2 rule running on the GPU, graded the same way the hand-written one is.
 * - **The equivariant form agrees too, and is equivariant on the GPU.** Parity as above, plus the
 *   property it exists for, asserted against the shader rather than against the Kotlin: a flock
 *   rotated bodily about the origin must produce a bodily rotated force field.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.shader-boids.smoke
 * ```
 */
object ShaderBoidsSmoke {

    private const val CELLS = 24
    private const val DOMAIN = 2.0f
    private const val ORIGIN = -1.0f
    private const val COUNT = 3000
    private const val S = ShaderBoids.STRIDE_FLOATS

    /**
     * Allowed relative disagreement between GPU and CPU acceleration.
     *
     * Both compute the same finite sum over the same set — the sets are proven equal by
     * `four.neighbour-grid.smoke` — but in different orders: the GPU walks cell by cell in sorted
     * order, the reference walks in index order. Over a few hundred fp32 terms that is worth
     * several parts in a million. A traversal bug is not: it changes the set, and shows up here
     * as a gross error rather than a marginal one.
     */
    private const val TOLERANCE = 1e-3

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("Shader Boids Smoke")
        val renderer = Renderer(window)

        try {
            var failures = 0
            failures += checkAcceleration(renderer, window, "uniform", spread = 1.8f)
            failures += checkAcceleration(renderer, window, "clustered", spread = 0.25f)
            failures += checkIntegration(renderer, window)
            failures += checkQuiescent(renderer, window)
            failures += checkLearned(renderer, window)
            failures += checkEquivariant(renderer, window)
            failures += checkDrawInstalls(renderer, window)

            if (failures > 0) {
                println("shader boids tests FAILED ($failures)")
                exitProcess(1)
            }
            println("shader boids tests PASSED")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }

    private fun build(renderer: Renderer, tag: String, configure: afield.four.physics.BoidSettings.() -> Unit) =
        ShaderBoids(
            grid = GpuNeighbourGrid(
                capacity = COUNT,
                cellsPerSide = CELLS,
                domainMin = Vector2f(ORIGIN, ORIGIN),
                domainSize = DOMAIN,
                renderer = renderer,
                name = "boidGrid$tag",
            ),
            count = COUNT,
            renderer = renderer,
        ) {
            name = "boids$tag"
            radius = DOMAIN / CELLS
            configure()
        }

    private fun run(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
        renderer.windowContext().computeFences.forEach { it.fenceWait() }
    }

    private fun read(boids: ShaderBoids): FloatArray {
        val out = FloatArray(COUNT * S)
        boids.boids.get().mapAndRead { get(out) }
        return out
    }

    /** The headline: force against brute force, with nothing moving. */
    private fun checkAcceleration(renderer: Renderer, window: Window, label: String, spread: Float): Int {
        println("--- $label: $COUNT boids, radius one cell of ${DOMAIN / CELLS}")
        val boids = build(renderer, label.take(3)) { dt = 0f }
        boids.reset(Vector2f(-spread / 2, -spread / 2), spread, speed = 0.2f, seed = 21)
        boids.install(renderer.renderList.others)
        run(renderer, window, 4)

        val state = read(boids)
        var worst = 0.0
        var worstAt = -1
        var magnitude = 0.0
        var interacting = 0
        for (i in 0 until COUNT) {
            val (ax, ay) = boids.referenceAcceleration(state, i)
            val gx = state[i * S + 4]
            val gy = state[i * S + 5]
            val scale = maxOf(hypot(ax.toDouble(), ay.toDouble()), 1e-4)
            val error = hypot((gx - ax).toDouble(), (gy - ay).toDouble()) / scale
            if (hypot(ax.toDouble(), ay.toDouble()) > 1e-6) interacting++
            magnitude = maxOf(magnitude, hypot(ax.toDouble(), ay.toDouble()))
            if (error > worst) { worst = error; worstAt = i }
        }
        println("  worst relative ${"%.3g".format(worst)} at $worstAt; " +
            "$interacting of $COUNT have neighbours; largest |a| ${"%.3f".format(magnitude)}")

        var failures = 0
        if (worst >= TOLERANCE) {
            println("  FAIL: worst relative $worst exceeds $TOLERANCE"); failures++
        }
        // A rule that computed nothing would agree with a reference that computed nothing.
        if (interacting < COUNT / 2) {
            println("  FAIL: only $interacting boids had any neighbours; the test data is too sparse")
            failures++
        }
        // With dt = 0 the state must be exactly as seeded.
        val moved = (0 until COUNT).count { abs(state[it * S + 2]) < 1e-12 && abs(state[it * S + 3]) < 1e-12 }
        if (moved > 0) { println("  FAIL: $moved boids lost their velocity at dt = 0"); failures++ }

        boids.removeFrom(renderer.renderList.others)
        boids.grid.cleanup()
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }

    /**
     * The integration law, graded from a single readback.
     *
     * `previousVelocity`, `accel` and `velocity` in one struct are the input, the force and the
     * output of the *same* step, so `v = clampSpeed(previousVelocity + accel*dt)` can be checked
     * exactly without knowing how many frames ran. The earlier version of this test predicted one
     * step from a readback taken before it and failed by exactly one step's worth of velocity,
     * because `dt` is read when the push buffer is written and a frame already in flight had
     * picked up the new value.
     */
    private fun checkIntegration(renderer: Renderer, window: Window): Int {
        println("--- integration and the speed limit")
        val step = 1f / 60f
        val boids = build(renderer, "Int") { dt = step; maxSpeed = 0.05f; cohesion = 40f }
        boids.reset(Vector2f(-0.4f, -0.4f), 0.8f, speed = 0.2f, seed = 22)
        boids.install(renderer.renderList.others)
        run(renderer, window, 30)
        val state = read(boids)

        var worst = 0.0
        var over = 0
        var clamped = 0
        for (i in 0 until COUNT) {
            val pvx = state[i * S + 6]
            val pvy = state[i * S + 7]
            var vx = pvx + state[i * S + 4] * step
            var vy = pvy + state[i * S + 5] * step
            val speed = hypot(vx.toDouble(), vy.toDouble())
            if (speed > 0.05) { vx = (vx * 0.05 / speed).toFloat(); vy = (vy * 0.05 / speed).toFloat(); clamped++ }
            worst = maxOf(worst, hypot((state[i * S + 2] - vx).toDouble(), (state[i * S + 3] - vy).toDouble()))
            if (hypot(state[i * S + 2].toDouble(), state[i * S + 3].toDouble()) > 0.05 * 1.001) over++
        }
        println("  worst velocity disagreement ${"%.3g".format(worst)}; " +
            "$clamped of $COUNT hit the 0.05 ceiling; $over exceed it")

        boids.removeFrom(renderer.renderList.others)
        boids.grid.cleanup()
        var failures = 0
        if (worst >= 1e-5) { println("  FAIL: velocity does not follow previousVelocity + accel*dt"); failures++ }
        if (over > 0) { println("  FAIL: the speed limit is not binding"); failures++ }
        // A ceiling nothing reaches is a ceiling that proves nothing.
        if (clamped < COUNT / 10) { println("  FAIL: only $clamped boids reached the ceiling"); failures++ }
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }

    /**
     * The learned rule on the GPU, against its own CPU evaluation, per particle.
     *
     * Note what this does and does not claim. It is a *parity* check — that the shader evaluates
     * the network the trainer produced — and not a claim that the learned rule equals boids;
     * `DeepSetsInteractionTest` measures that separately and puts it at a relative error of about
     * a tenth. Conflating the two would let a faithful shader running a bad network look like a
     * pass, and a wrong shader running a good one look like a modelling problem.
     *
     * The reference needs the same neighbour sets the GPU used, so it recomputes them by brute
     * force at the same radius — which is legitimate precisely because
     * `four.neighbour-grid.smoke` has already shown those sets to be identical.
     */
    private fun checkLearned(renderer: Renderer, window: Window): Int {
        println("--- learned interaction")
        val radius = DOMAIN / CELLS
        val minSeparation = radius * 0.05f
        val velocityScale = 0.4f
        val random = kotlin.random.Random(5)

        // A short run: this asserts the shader matches the network, so the network only has to be
        // a real one, not a good one. Quality is DeepSetsInteractionTest's job.
        val weights = afield.stdlib.trainDeepSets(
            radius = radius,
            velocityScale = velocityScale,
            accelScale = 20f,
            sumScale = 8f,
            minSeparation = minSeparation,
            options = afield.stdlib.DeepSetsOptions(steps = 400, batch = 8, seed = 5),
        ) { List(8) { syntheticExample(random, radius, velocityScale, minSeparation) } }
        println("  trained ${weights.parameterCount} parameters")

        val boids = ShaderBoids(
            grid = GpuNeighbourGrid(
                capacity = COUNT, cellsPerSide = CELLS,
                domainMin = Vector2f(ORIGIN, ORIGIN), domainSize = DOMAIN,
                renderer = renderer, name = "boidGridLrn",
            ),
            count = COUNT,
            renderer = renderer,
            learned = weights,
        ) { name = "boidsLrn"; this.radius = radius; dt = 0f }
        boids.reset(Vector2f(-0.5f, -0.5f), 1.0f, speed = 0.2f, seed = 25)
        boids.install(renderer.renderList.others)
        run(renderer, window, 4)
        val state = read(boids)

        var worst = 0.0
        var magnitude = 0.0
        var interacting = 0
        for (i in 0 until COUNT) {
            val px = state[i * S]; val py = state[i * S + 1]
            val vx = state[i * S + 2]; val vy = state[i * S + 3]
            val neighbours = ArrayList<FloatArray>()
            for (j in 0 until COUNT) {
                if (j == i) continue
                val dx = state[j * S] - px
                val dy = state[j * S + 1] - py
                val d = hypot(dx.toDouble(), dy.toDouble())
                if (d > radius || d <= 0.0) continue
                neighbours += floatArrayOf(dx, dy, state[j * S + 2] - vx, state[j * S + 3] - vy)
            }
            if (neighbours.isEmpty()) continue
            interacting++
            val (ax, ay) = weights.evaluate(vx, vy, neighbours)
            val scale = maxOf(hypot(ax.toDouble(), ay.toDouble()), 1e-4)
            magnitude = maxOf(magnitude, hypot(ax.toDouble(), ay.toDouble()))
            worst = maxOf(worst, hypot((state[i * S + 4] - ax).toDouble(), (state[i * S + 5] - ay).toDouble()) / scale)
        }
        println("  GPU vs CPU learned rule: worst relative ${"%.3g".format(worst)} over $interacting boids; " +
            "largest |a| ${"%.3f".format(magnitude)}")

        boids.removeFrom(renderer.renderList.others)
        boids.grid.cleanup()
        var failures = 0
        if (worst >= TOLERANCE) { println("  FAIL: worst relative $worst exceeds $TOLERANCE"); failures++ }
        if (interacting < COUNT / 2) { println("  FAIL: only $interacting boids had neighbours"); failures++ }
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }

    /** A random neighbourhood with a boids target — enough to train a real, if brief, network. */
    private fun syntheticExample(
        random: kotlin.random.Random, radius: Float, velocityScale: Float, minSeparation: Float,
    ): afield.stdlib.InteractionExample {
        val v = floatArrayOf(
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
            (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
        )
        val neighbours = List(1 + random.nextInt(10)) {
            val r = radius * kotlin.math.sqrt(random.nextDouble()).toFloat()
            val a = random.nextDouble() * 2 * Math.PI
            floatArrayOf(
                (r * kotlin.math.cos(a)).toFloat(), (r * kotlin.math.sin(a)).toFloat(),
                (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
                (random.nextDouble() * 2 - 1).toFloat() * velocityScale,
            )
        }
        var sx = 0f; var sy = 0f; var dvx = 0f; var dvy = 0f; var dx = 0f; var dy = 0f
        for (n in neighbours) {
            val d = hypot(n[0].toDouble(), n[1].toDouble()).toFloat()
            val soft = maxOf(d, minSeparation)
            sx -= n[0] / (soft * soft); sy -= n[1] / (soft * soft)
            dvx += n[2]; dvy += n[3]; dx += n[0]; dy += n[1]
        }
        val n = neighbours.size.toFloat()
        return afield.stdlib.InteractionExample(
            v, neighbours,
            floatArrayOf(0.02f * sx + 1.2f * dvx / n + 0.8f * dx / n, 0.02f * sy + 1.2f * dvy / n + 0.8f * dy / n),
        )
    }

    /**
     * The equivariant rule on the GPU: parity with its CPU reference, and the symmetry itself.
     *
     * The parity half is the same check the plain learned rule gets. The second half is the one
     * worth having, because equivariance is the reason this form exists and a shader is perfectly
     * capable of breaking it — one component read where a length was meant and the property is
     * gone, while every parity number stays green. So: take the settled flock, rotate every
     * position and velocity bodily by an arbitrary angle, run it again, and require the resulting
     * accelerations to be the rotation of the originals.
     */
    private fun checkEquivariant(renderer: Renderer, window: Window): Int {
        println("--- equivariant interaction")
        val radius = DOMAIN / CELLS
        val minSeparation = radius * 0.05f
        val velocityScale = 0.4f
        val random = kotlin.random.Random(5)

        val weights = afield.stdlib.trainEquivariant(
            radius = radius, velocityScale = velocityScale, accelScale = 20f,
            sumScale = 8f, minSeparation = minSeparation,
            options = afield.stdlib.DeepSetsOptions(
                steps = 400, batch = 8, seed = 5, messageWidth = 4, psiHidden = intArrayOf(8, 8)),
        ) { List(8) { syntheticExample(random, radius, velocityScale, minSeparation) } }
        println("  trained ${weights.parameterCount} parameters, ${weights.channels} channels")

        fun build(tag: String) = ShaderBoids(
            grid = GpuNeighbourGrid(
                capacity = COUNT, cellsPerSide = CELLS,
                domainMin = Vector2f(ORIGIN, ORIGIN), domainSize = DOMAIN,
                renderer = renderer, name = "boidGridEq$tag",
            ),
            count = COUNT, renderer = renderer, equivariant = weights,
        ) { name = "boidsEq$tag"; this.radius = radius; dt = 0f }

        val plain = build("A")
        plain.reset(Vector2f(-0.5f, -0.5f), 1.0f, speed = 0.2f, seed = 26)
        plain.install(renderer.renderList.others)
        run(renderer, window, 4)
        val state = read(plain)

        // 1. Parity against the CPU reference.
        var worst = 0.0
        var interacting = 0
        for (i in 0 until COUNT) {
            val px = state[i * S]; val py = state[i * S + 1]
            val vx = state[i * S + 2]; val vy = state[i * S + 3]
            val neighbours = ArrayList<FloatArray>()
            for (j in 0 until COUNT) {
                if (j == i) continue
                val dx = state[j * S] - px
                val dy = state[j * S + 1] - py
                val d = hypot(dx.toDouble(), dy.toDouble())
                if (d > radius || d <= 0.0) continue
                neighbours += floatArrayOf(dx, dy, state[j * S + 2] - vx, state[j * S + 3] - vy)
            }
            if (neighbours.isEmpty()) continue
            interacting++
            val (ax, ay) = weights.evaluate(vx, vy, neighbours)
            val scale = maxOf(hypot(ax.toDouble(), ay.toDouble()), 1e-4)
            worst = maxOf(worst, hypot((state[i * S + 4] - ax).toDouble(), (state[i * S + 5] - ay).toDouble()) / scale)
        }
        println("  GPU vs CPU: worst relative ${"%.3g".format(worst)} over $interacting boids")
        plain.removeFrom(renderer.renderList.others)
        plain.grid.cleanup()

        // 2. The symmetry, on the GPU. Same flock, rotated bodily.
        val turn = 0.7
        val c = kotlin.math.cos(turn).toFloat()
        val sn = kotlin.math.sin(turn).toFloat()
        val rotated = build("B")
        rotated.boids.get(write = true).mapAndWrite {
            for (i in 0 until COUNT) {
                put(c * state[i * S] - sn * state[i * S + 1]); put(sn * state[i * S] + c * state[i * S + 1])
                put(c * state[i * S + 2] - sn * state[i * S + 3]); put(sn * state[i * S + 2] + c * state[i * S + 3])
                put(0f); put(0f); put(0f); put(0f)
            }
        }
        rotated.install(renderer.renderList.others)
        run(renderer, window, 4)
        val after = read(rotated)

        var worstTurn = 0.0
        var magnitude = 0.0
        for (i in 0 until COUNT) {
            val ex = c * state[i * S + 4] - sn * state[i * S + 5]
            val ey = sn * state[i * S + 4] + c * state[i * S + 5]
            val scale = maxOf(hypot(ex.toDouble(), ey.toDouble()), 1e-4)
            worstTurn = maxOf(worstTurn, hypot((after[i * S + 4] - ex).toDouble(), (after[i * S + 5] - ey).toDouble()) / scale)
            magnitude = maxOf(magnitude, hypot(ex.toDouble(), ey.toDouble()))
        }
        println("  rotated by ${"%.2f".format(turn)} rad: worst relative ${"%.3g".format(worstTurn)}, largest |a| ${"%.3f".format(magnitude)}")
        rotated.removeFrom(renderer.renderList.others)
        rotated.grid.cleanup()

        var failures = 0
        if (worst >= TOLERANCE) { println("  FAIL: parity $worst exceeds $TOLERANCE"); failures++ }
        // Looser than parity: the grid re-hashes rotated positions, so a boid on a cell boundary
        // can pick up a neighbour exactly at the radius that the unrotated run rounded out. The
        // plain form misses this by 18%, so there is no risk of it passing by accident.
        if (worstTurn >= 0.05) { println("  FAIL: equivariance $worstTurn exceeds 0.05"); failures++ }
        if (interacting < COUNT / 2) { println("  FAIL: only $interacting boids had neighbours"); failures++ }
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }

    /**
     * The draw half of the public recipe actually installs, and survives a frame.
     *
     * `installDrawInto` is what `neural_particles.md` tells a reader to call, and until this case
     * existed nothing in CI ran it: every other case here installs the *rule* and reads the buffer
     * back, which leaves the vertex and fragment shaders, the `Mesh` sizing and the eight push
     * bindings compiled but never executed. A shader that fails to compile, or a binding naming a
     * push constant the shader does not declare, would reach a reader before it reached a test.
     *
     * Deliberately not a picture: this asserts the module is registered and that a frame renders
     * with it in the list. What the flock *looks* like is the docs capture's job.
     */
    private fun checkDrawInstalls(renderer: Renderer, window: Window): Int {
        println("--- installDrawInto")
        val boids = build(renderer, "Drw") {}
        boids.reset(Vector2f(-0.5f, -0.5f), 1.0f, speed = 0.2f, seed = 25)
        boids.install(renderer.renderList.others)
        boids.installDrawInto(renderer.renderList)

        val key = "${boids.settings.name}-draw"
        val registered = renderer.renderList.modules.containsKey(key)
        println("  '$key' in the render list: $registered")

        // A frame with the draw in the list: this is what executes the shaders rather than merely
        // building them. A compile failure surfaces here and nowhere earlier.
        run(renderer, window, 4)

        var failures = 0
        if (!registered) { println("  FAIL: installDrawInto registered no draw under '$key'"); failures++ }

        boids.removeFrom(renderer.renderList.others)
        renderer.renderList.modules.remove(key)
        boids.grid.cleanup()
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }

    /** All three weights off must give exactly zero, not merely something small. */
    private fun checkQuiescent(renderer: Renderer, window: Window): Int {
        println("--- weights at zero")
        val boids = build(renderer, "Zer") { dt = 0f; separation = 0f; alignment = 0f; cohesion = 0f }
        boids.reset(Vector2f(-0.5f, -0.5f), 1.0f, speed = 0.2f, seed = 24)
        boids.install(renderer.renderList.others)
        run(renderer, window, 4)
        val state = read(boids)
        val nonZero = (0 until COUNT).count { state[it * S + 4] != 0f || state[it * S + 5] != 0f }
        println("  $nonZero of $COUNT have a non-zero acceleration")
        boids.removeFrom(renderer.renderList.others)
        boids.grid.cleanup()
        if (nonZero > 0) { println("  FAIL: something is contributing force that should not be"); return 1 }
        println("  ok")
        return 0
    }
}
