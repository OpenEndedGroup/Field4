package afield.examples.four

import afield.graphics.render.ComputeModule
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.vec
import afield.simulation.particles.ParticleShaders
import afield.simulation.particles.ParticleSketch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.particle-sketch.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.ParticleSketchSmoke
tags: [smoke, four, gpu, compute, particles, neighbours, madp]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "particle sketch tests PASSED"
*/
/**
 * That a [ParticleSketch] step on the GPU does what madp's JavaScript does, per particle.
 *
 * The oracle is a transcription of the demo's own functions (`flockParticle`, `clumpParticle`,
 * `springBetween`, `collidePair`, `springToTarget`, `oscillateParticle`, `integrateWorld`, ...)
 * over the same particles, in madp's own order: one sequential pass over each unordered pair that
 * writes *both* particles, then each particle's update against the snapshot, then the integration.
 * The GPU does none of that the same way — it visits each pair from both sides and applies one
 * half each, and walks a sorted grid instead of a hash map — so agreement is evidence that the
 * per-side form is the same physics, not a copy of the same code.
 *
 * ### One step, with the time step recovered from the particles
 *
 * A frame is paused while the paint lands, so the particles are read exactly as `spawn` made
 * them; then exactly one frame steps. The step's `dt` comes from the wall clock, and is read back
 * from the particles' `age`, which starts at 0 and goes up by `dt`. Everything a particle starts
 * with is a function of where it was painted, so the oracle can rebuild it.
 *
 * ### What it asserts
 *
 * - **Forces, pairs and integration match madp**, per particle: flock, clump (with a radius two
 *   cells wide, so the grid walk has to reach), springs between pairs, springTo, oscillate,
 *   attract, gravity, drag, the speed limits and bounce, compared on position, velocity and
 *   acceleration after one step.
 * - **Neighbour counts are exact**: `neighbors`, `flock` and `clump` report how many they saw, an
 *   integer the oracle counts by brute force. A grid walk that misses a cell cannot hide in a
 *   tolerance there.
 * - **`link` draws each pair once**, and `draw` adds its overlays: the stroke count equals the
 *   oracle's.
 * - **Collisions match madp** for isolated touching pairs, where madp's sequential pass and the
 *   GPU's simultaneous one must agree exactly.
 * - **Painting past capacity replaces the oldest**, and **reset empties the world**.
 *
 * ```
 * tools/run_field_tests.py --id four.particle-sketch.smoke
 * ```
 */
object ParticleSketchSmoke {

    private const val F = 24

    private val SPAWN = """
        void spawn(vec2 at) {
            p.pos = at;
            p.vel = vec2(sin(at.x * 0.37) * 60.0, cos(at.y * 0.23) * 60.0);
            p.radius = 2.0 + fract(at.x * 0.013) * 4.0;
            p.mass = 0.5 + fract(at.y * 0.017) * 2.0;
            p.seed = fract(at.x * 0.0071 + at.y * 0.0033) * 1000.0;
            p.group = int(mod(floor(at.x * 0.05), 3.0));
        }
    """.trimIndent()

    private val FORCES = """
        #version 450
        #include "particles/sketch"
        $SPAWN
        void eachPair(Particle other, Pair pair) {
            if (pair.dist < 40.0) spring(other, pair, 25.0, 3.0, 0.2);
            if (p.group == other.group && pair.dist < 20.0) link(other, rgba(120.0, 255.0, 210.0, 0.5), 1.0);
        }
        void update() {
            int a = flock(82.0, 2.2, 0.82, 0.52, 125.0);
            int b = clump(150.0, 8.0, 18.0, 40.0);
            int c = neighbors(30.0);
            springTo(vec2(width, height) * 0.5, 110.0, 1.8, 0.15);
            oscillate(vec2(cos(p.seed), sin(p.seed)), 24.0, 0.48, p.seed * 0.03);
            attract(vec2(100.0, 100.0), 5.0);
            drag(0.01);
            limitSpeed(150.0);
            bounce(0.9);
            p.data = vec4(float(a), float(b), float(c), 0.0);
        }
        void draw() {
            ring(vec2(50.0, 50.0), 20.0, rgba(255.0, 120.0, 120.0, 0.25), 2.0);
        }
    """.trimIndent()

    private val COLLISIONS = """
        #version 450
        #include "particles/sketch"
        $SPAWN
        void eachPair(Particle other, Pair pair) {
            collide(other, pair, 0.9, 0.5);
        }
        void update() {
        }
        void draw() {
        }
    """.trimIndent()

    private val PILE = """
        #version 450
        #include "particles/sketch"
        void spawn(vec2 at) {
            p.pos = at;
            p.vel = vec2(0.0);
            p.radius = 5.0;
            p.mass = 1.0;
        }
        void eachPair(Particle other, Pair pair) {
            collide(other, pair, 0.9);
        }
        void update() {
        }
        void draw() {
        }
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("Particle Sketch Smoke", initialDimensions = afield.app.protocol.Rect(60.0, 60.0, 900.0, 700.0))
        val renderer = Renderer(window)
        try {
            var failures = 0
            failures += checkForces(renderer, window)
            failures += checkCollisions(renderer, window)
            failures += checkPileSymmetry(renderer, window)
            failures += checkCapacityAndReset(renderer, window)
            if (failures > 0) {
                println("particle sketch tests FAILED ($failures)")
                exitProcess(1)
            }
            println("particle sketch tests PASSED")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }

    private fun module(renderer: Renderer, name: String, source: String) = ComputeModule(
        parameters(renderer) {
            this.name = name
            compute = source.replace("#include \"particles/sketch\"", ParticleShaders.source("sketch"))
        }
    ).pointer()

    private fun run(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
        // the sketch runs in the graphics command buffer, so wait for everything rather than for compute
        renderer.device.waitIdle()
    }

    private fun read(sketch: ParticleSketch): FloatArray {
        val out = FloatArray(sketch.capacity * F)
        sketch.latest.get().mapAndRead { get(out) }
        return out
    }

    /** One particle in the oracle, madp's fields. */
    private class P(
        var x: Double, var y: Double, var vx: Double, var vy: Double,
        var ax: Double = 0.0, var ay: Double = 0.0,
        val radius: Double, val mass: Double, val seed: Double, val group: Int, val id: Int,
    ) {
        var counts = DoubleArray(3)
        fun copy() = P(x, y, vx, vy, ax, ay, radius, mass, seed, group, id)
    }

    private fun spawned(state: FloatArray, count: Int) = List(count) { i ->
        val b = i * F
        P(
            x = state[b].toDouble(), y = state[b + 1].toDouble(),
            vx = state[b + 2].toDouble(), vy = state[b + 3].toDouble(),
            radius = state[b + 12].toDouble(), mass = state[b + 13].toDouble(), seed = state[b + 14].toDouble(),
            group = java.lang.Float.floatToRawIntBits(state[b + 16]), id = java.lang.Float.floatToRawIntBits(state[b + 17]),
        )
    }

    // ---- madp's functions, transcribed ----

    private fun applyForce(p: P, fx: Double, fy: Double) { p.ax += fx / p.mass; p.ay += fy / p.mass }

    private fun neighbours(all: List<P>, snapshot: List<P>, me: Int, r: Double) =
        snapshot.indices.filter { j ->
            j != me && (snapshot[j].x - all[me].x).let { dx -> (snapshot[j].y - all[me].y).let { dy -> dx * dx + dy * dy <= r * r } }
        }

    private fun forceToward(p: P, tx: Double, ty: Double, strength: Double) {
        val dx = tx - p.x; val dy = ty - p.y
        val len = sqrt(dx * dx + dy * dy).let { if (it == 0.0) 1.0 else it }
        applyForce(p, dx / len * strength, dy / len * strength)
    }

    private fun flock(all: List<P>, snapshot: List<P>, me: Int): Int {
        val p = all[me]
        val ns = neighbours(all, snapshot, me, 82.0)
        if (ns.isEmpty()) return 0
        var sepX = 0.0; var sepY = 0.0; var aliX = 0.0; var aliY = 0.0; var cohX = 0.0; var cohY = 0.0
        for (j in ns) {
            val n = snapshot[j]
            val dist = hypot(n.x - p.x, n.y - p.y).let { if (it == 0.0) 1.0 else it }
            sepX += (p.x - n.x) / (dist * dist); sepY += (p.y - n.y) / (dist * dist)
            aliX += n.vx; aliY += n.vy; cohX += n.x; cohY += n.y
        }
        aliX /= ns.size; aliY /= ns.size; cohX /= ns.size; cohY /= ns.size
        var fx = sepX * 2.2 + (aliX - p.vx) * 0.82 + (cohX - p.x) * 0.52 * 0.12
        var fy = sepY * 2.2 + (aliY - p.vy) * 0.82 + (cohY - p.y) * 0.52 * 0.12
        val len = hypot(fx, fy)
        if (len > 125.0) { fx = fx / len * 125.0; fy = fy / len * 125.0 }
        applyForce(p, fx, fy)
        return ns.size
    }

    private fun clump(all: List<P>, snapshot: List<P>, me: Int): Int {
        val p = all[me]
        val ns = neighbours(all, snapshot, me, 150.0)
        if (ns.isEmpty()) return 0
        var cx = 0.0; var cy = 0.0
        for (j in ns) {
            val n = snapshot[j]
            val dist = hypot(n.x - p.x, n.y - p.y)
            cx += n.x; cy += n.y
            if (dist < 18.0 && dist > 0) applyForce(p, (p.x - n.x) / dist * 40.0, (p.y - n.y) / dist * 40.0)
        }
        forceToward(p, cx / ns.size, cy / ns.size, 8.0)
        return ns.size
    }

    private fun springBetween(a: P, b: P, rest: Double, stiffness: Double, damping: Double) {
        val dx = b.x - a.x; val dy = b.y - a.y
        val dist = sqrt(dx * dx + dy * dy).let { if (it == 0.0) 0.0001 else it }
        val nx = dx / dist; val ny = dy / dist
        val relVel = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
        val force = (dist - rest) * stiffness + relVel * damping
        applyForce(a, nx * force, ny * force)
        applyForce(b, -nx * force, -ny * force)
    }

    private fun collidePair(a: P, b: P, restitution: Double, padding: Double) {
        val dx = b.x - a.x; val dy = b.y - a.y
        val dist = sqrt(dx * dx + dy * dy).let { if (it == 0.0) 0.0001 else it }
        val minDist = a.radius + b.radius + padding
        if (dist >= minDist) return
        val nx = dx / dist; val ny = dy / dist
        val overlap = minDist - dist
        val total = a.mass + b.mass
        a.x -= nx * overlap * (b.mass / total); a.y -= ny * overlap * (b.mass / total)
        b.x += nx * overlap * (a.mass / total); b.y += ny * overlap * (a.mass / total)
        val sepVel = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
        if (sepVel > 0) return
        val j = -(1 + restitution) * sepVel / (1 / a.mass + 1 / b.mass)
        a.vx -= j * nx / a.mass; a.vy -= j * ny / a.mass
        b.vx += j * nx / b.mass; b.vy += j * ny / b.mass
    }

    private fun limitSpeed(p: P, max: Double) {
        val speed = hypot(p.vx, p.vy)
        if (speed > max) { p.vx *= max / speed; p.vy *= max / speed }
    }

    private fun bounce(p: P, restitution: Double, width: Double, height: Double, dt: Double) {
        val nextX = p.x + p.vx * dt; val nextY = p.y + p.vy * dt
        if (nextX < p.radius) { p.x = p.radius; p.vx = abs(p.vx) * restitution }
        else if (nextX > width - p.radius) { p.x = width - p.radius; p.vx = -abs(p.vx) * restitution }
        if (nextY < p.radius) { p.y = p.radius; p.vy = abs(p.vy) * restitution }
        else if (nextY > height - p.radius) { p.y = height - p.radius; p.vy = -abs(p.vy) * restitution }
    }

    /** madp's `eachPairWithin`: every unordered pair once. */
    private fun eachPair(all: List<P>, radius: Double, fn: (P, P, Double) -> Unit) {
        for (i in all.indices) for (j in i + 1 until all.size) {
            val d = hypot(all[j].x - all[i].x, all[j].y - all[i].y)
            if (d <= radius) fn(all[i], all[j], d)
        }
    }

    private fun integrate(p: P, dt: Double, damping: Double, maxSpeed: Double) {
        p.vx += p.ax * dt; p.vy += p.ay * dt
        p.vx *= damping; p.vy *= damping
        limitSpeed(p, maxSpeed)
        p.x += p.vx * dt; p.y += p.vy * dt
    }

    // ---- the checks ----

    /** Paints [points] with the sketch paused, so they are read back exactly as spawn made them. */
    private fun paintPaused(renderer: Renderer, window: Window, sketch: ParticleSketch, points: List<Pair<Double, Double>>): FloatArray {
        sketch.settings.paused = true
        points.forEach { (x, y) -> sketch.paint(x, y) }
        run(renderer, window, 3)
        return read(sketch)
    }

    private fun checkForces(renderer: Renderer, window: Window): Int {
        println("--- forces, pairs and integration against madp's JavaScript")
        val sketch = ParticleSketch(module(renderer, "sketch-forces", FORCES), renderer) {
            name = "smoke-forces"
            capacity = 1024
            gravity = vec(0, 50)
            damping = 0.99
            maxSpeed = 160.0
            pairRadius = 40.0
            paintWithMouse = false
            fps = 0.0 // every rendered frame is a step, so one render is exactly one step
        }
        // a dense patch, so neighbourhoods are tens strong and the 150-pixel clump reaches past
        // the 3x3 block of 72-pixel cells
        val random = kotlin.random.Random(11)
        val points = List(700) {
            val a = random.nextDouble() * 2 * PI
            val r = sqrt(random.nextDouble()) * 170
            (420 + cos(a) * r) to (330 + sin(a) * r)
        }
        val before = paintPaused(renderer, window, sketch, points)
        val count = sketch.count
        val width = sketch.size.x.toDouble()
        val height = sketch.size.y.toDouble()

        sketch.settings.paused = false
        run(renderer, window, 1)
        sketch.settings.paused = true
        val after = read(sketch)
        val strokes = IntArray(1).also { header -> sketch.strokes.get().mapAndRead_ints { get(header) } }[0]

        var failures = 0
        if (count != points.size) { println("  FAIL: $count particles alive, painted ${points.size}"); failures++ }
        val dt = after[15].toDouble()
        val t = sketch.time.toFloat().toDouble()
        println("  $count particles in a ${width.toInt()}x${height.toInt()} world, one step of dt = ${"%.5f".format(dt)}, t = ${"%.4f".format(t)}")
        if (!(dt > 0.0 && dt <= 0.0331)) { println("  FAIL: the step's dt, read from age, is $dt"); return 1 }

        // madp's frame, in madp's order
        val live = spawned(before, count)
        val snapshot = live.map { it.copy() }
        live.forEach { it.ax = 0.0; it.ay = 50.0 }
        var links = 0
        eachPair(live, 40.0) { a, b, d ->
            if (d < 40.0) springBetween(a, b, 25.0, 3.0, 0.2)
            if (a.group == b.group && d < 20.0) links++
        }
        for (i in live.indices) {
            val p = live[i]
            p.counts[0] = flock(live, snapshot, i).toDouble()
            p.counts[1] = clump(live, snapshot, i).toDouble()
            p.counts[2] = neighbours(live, snapshot, i, 30.0).size.toDouble()
            val dx = width * 0.5 - p.x; val dy = height * 0.5 - p.y
            val dist = sqrt(dx * dx + dy * dy).let { if (it == 0.0) 0.0001 else it }
            val nx = dx / dist; val ny = dy / dist
            val f = (dist - 110.0) * 1.8 + (-p.vx * nx - p.vy * ny) * 0.15
            applyForce(p, nx * f, ny * f)
            val seed = p.seed.toFloat().toDouble()
            val wave = sin((t * 0.48 + seed * 0.03) * 2 * PI)
            val axis = hypot(cos(seed), sin(seed))
            applyForce(p, cos(seed) / axis * wave * 24.0, sin(seed) / axis * wave * 24.0)
            forceToward(p, 100.0, 100.0, 5.0)
            p.vx *= 0.99; p.vy *= 0.99
            limitSpeed(p, 150.0)
            bounce(p, 0.9, width, height, dt)
            integrate(p, dt, 0.99, 160.0)
        }

        var worstPos = 0.0; var worstVel = 0.0; var worstAcc = 0.0; var countMismatches = 0
        var largestAcc = 0.0
        val byId = (0 until count).associateBy { java.lang.Float.floatToRawIntBits(after[it * F + 17]) }
        for (p in live) {
            val i = byId[p.id] ?: run { println("  FAIL: particle ${p.id} is missing after the step"); failures++; null } ?: continue
            val b = i * F
            worstPos = max(worstPos, hypot(after[b] - p.x, after[b + 1] - p.y))
            worstVel = max(worstVel, hypot(after[b + 2] - p.vx, after[b + 3] - p.vy) / max(1.0, hypot(p.vx, p.vy)))
            worstAcc = max(worstAcc, hypot(after[b + 4] - p.ax, after[b + 5] - p.ay) / max(1.0, hypot(p.ax, p.ay)))
            largestAcc = max(largestAcc, hypot(p.ax, p.ay))
            for (k in 0..2) if (after[b + 20 + k].toDouble() != p.counts[k]) countMismatches++
        }
        val meanFlock = live.sumOf { it.counts[0] } / count
        val meanClump = live.sumOf { it.counts[1] } / count
        println("  worst: position ${"%.3g".format(worstPos)} px, velocity ${"%.3g".format(worstVel)} relative, " +
            "acceleration ${"%.3g".format(worstAcc)} relative (largest |a| ${"%.0f".format(largestAcc)})")
        println("  mean neighbours: flock ${"%.1f".format(meanFlock)}, clump ${"%.1f".format(meanClump)}; " +
            "$countMismatches neighbour counts differ; strokes $strokes, madp ${links + 1} (links $links + 1 ring)")

        if (worstAcc > 2e-3) { println("  FAIL: acceleration differs from madp's"); failures++ }
        if (worstVel > 2e-3) { println("  FAIL: velocity differs from madp's"); failures++ }
        if (worstPos > 5e-3) { println("  FAIL: position differs from madp's"); failures++ }
        if (countMismatches > 0) { println("  FAIL: a neighbour query saw a different set from brute force"); failures++ }
        if (strokes != links + 1) { println("  FAIL: link should draw each pair once, plus draw()'s ring"); failures++ }
        // the scene has to exercise what it claims to
        if (meanClump < 2 * meanFlock || meanFlock < 10) { println("  FAIL: neighbourhoods too sparse to test the reach"); failures++ }
        if (links < 50) { println("  FAIL: only $links links; the scene does not test link"); failures++ }
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }

    private fun checkCollisions(renderer: Renderer, window: Window): Int {
        println("--- collisions against madp's JavaScript, on isolated pairs")
        val sketch = ParticleSketch(module(renderer, "sketch-collide", COLLISIONS), renderer) {
            name = "smoke-collide"
            capacity = 1024
            damping = 1.0
            pairRadius = 20.0
            paintWithMouse = false
            fps = 0.0 // every rendered frame is a step, so one render is exactly one step
        }
        // pairs 9 pixels apart, every pair 60 pixels from the next, so each ball touches at most
        // its partner and madp's sequential pass and the GPU's simultaneous one must agree
        val points = (0 until 10).flatMap { row ->
            (0 until 12).flatMap { col ->
                val x = 60.0 + col * 60 + row * 1.7
                val y = 60.0 + row * 60
                listOf(x to y, (x + 7 + (col % 3)) to (y + 1.5 * (row % 2)))
            }
        }
        val before = paintPaused(renderer, window, sketch, points)
        val count = sketch.count
        sketch.settings.paused = false
        run(renderer, window, 1)
        sketch.settings.paused = true
        val after = read(sketch)
        val dt = after[15].toDouble()

        val live = spawned(before, count)
        eachPair(live, 20.0) { a, b, _ -> collidePair(a, b, 0.9, 0.5) }
        live.forEach { integrate(it, dt, 1.0, Double.POSITIVE_INFINITY) }

        var worst = 0.0
        var touching = 0
        val byId = (0 until count).associateBy { java.lang.Float.floatToRawIntBits(after[it * F + 17]) }
        for ((k, p) in live.withIndex()) {
            val i = byId[p.id] ?: continue
            worst = max(worst, hypot(after[i * F] - p.x, after[i * F + 1] - p.y))
            if (k % 2 == 0 && hypot(before[(k + 1) * F] - before[k * F].toDouble(), before[(k + 1) * F + 1] - before[k * F + 1].toDouble()) <
                live[k].radius + live[k + 1].radius + 0.5) touching++
        }
        println("  $count balls, $touching of ${count / 2} pairs touching; worst position ${"%.3g".format(worst)} px after dt ${"%.5f".format(dt)}")
        var failures = 0
        if (worst > 5e-3) { println("  FAIL: collision response differs from madp's"); failures++ }
        if (touching < count / 4) { println("  FAIL: too few pairs touch to test collide"); failures++ }
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }

    /**
     * A pile of identical overlapping balls, mirror-symmetric left to right and top to bottom,
     * has to stay symmetric through a step. The neighbour walk visits cells left to right and
     * bottom to top; when collide() moved p as it went, the pairs visited later saw p already
     * pushed away from the earlier ones, and a pile in bounce house drifted into one corner.
     */
    private fun checkPileSymmetry(renderer: Renderer, window: Window): Int {
        println("--- a mirror-symmetric pile stays symmetric through a step")
        val sketch = ParticleSketch(module(renderer, "sketch-pile", PILE), renderer) {
            name = "smoke-pile"
            capacity = 1024
            damping = 1.0
            pairRadius = 20.0
            paintWithMouse = false
            fps = 0.0
        }
        val cx = 450.0
        val cy = 350.0
        // a quarter of a jittered lattice 7 pixels apart (the balls are 10 across), mirrored four ways
        val random = kotlin.random.Random(3)
        val quarter = (0 until 8).flatMap { i -> (0 until 8).map { j -> (3.5 + i * 7 + random.nextDouble() * 2) to (3.5 + j * 7 + random.nextDouble() * 2) } }
        val points = quarter.flatMap { (dx, dy) -> listOf((cx + dx) to (cy + dy), (cx - dx) to (cy + dy), (cx + dx) to (cy - dy), (cx - dx) to (cy - dy)) }
        paintPaused(renderer, window, sketch, points)
        sketch.settings.paused = false
        run(renderer, window, 1)
        sketch.settings.paused = true
        val after = read(sketch)
        val count = sketch.count
        val byId = (0 until count).associateBy { java.lang.Float.floatToRawIntBits(after[it * F + 17]) }
        var worst = 0.0
        var moved = 0.0
        for (k in quarter.indices) {
            val at = (0..3).map { m -> byId.getValue(k * 4 + m + 1).let { i -> after[i * F].toDouble() to after[i * F + 1].toDouble() } }
            val (a, b, c, d) = at
            worst = maxOf(worst, abs((a.first - cx) + (b.first - cx)), abs((a.second - cy) - (b.second - cy)))
            worst = maxOf(worst, abs((a.first - cx) - (c.first - cx)), abs((a.second - cy) + (c.second - cy)))
            worst = maxOf(worst, abs((a.first - cx) + (d.first - cx)), abs((a.second - cy) + (d.second - cy)))
            moved = maxOf(moved, hypot(a.first - cx - quarter[k].first, a.second - cy - quarter[k].second))
        }
        println("  $count balls; largest push ${"%.3g".format(moved)} px; worst asymmetry ${"%.3g".format(worst)} px")
        var failures = 0
        if (moved < 0.5) { println("  FAIL: the pile hardly moved, so this tests nothing"); failures++ }
        if (worst > 1e-3) { println("  FAIL: the step broke the pile's mirror symmetry"); failures++ }
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }

    private fun checkCapacityAndReset(renderer: Renderer, window: Window): Int {
        println("--- painting past capacity, and reset")
        val sketch = ParticleSketch(module(renderer, "sketch-ring", COLLISIONS), renderer) {
            name = "smoke-ring"
            capacity = 64
            paintWithMouse = false
            fps = 0.0 // every rendered frame is a step, so one render is exactly one step
        }
        val state = paintPaused(renderer, window, sketch, List(100) { (100.0 + it * 3) to 200.0 })
        val ids = (0 until 64).map { java.lang.Float.floatToRawIntBits(state[it * F + 17]) }.toSet()
        var failures = 0
        if (sketch.count != 64) { println("  FAIL: ${sketch.count} alive, capacity 64"); failures++ }
        if (ids != (37..100).toSet()) { println("  FAIL: the newest 64 should be alive, have ${ids.sorted()}"); failures++ }
        sketch.reset()
        run(renderer, window, 2)
        if (sketch.count != 0 || !sketch.isEmpty) { println("  FAIL: reset left ${sketch.count}"); failures++ }
        println("  ids alive after painting 100 into 64: ${ids.minOrNull()}..${ids.maxOrNull()}")
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }
}
