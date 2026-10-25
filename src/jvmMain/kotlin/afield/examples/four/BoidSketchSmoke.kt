package afield.examples.four

import afield.graphics.render.ComputeModule
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.simulation.particles.BoidEdges
import afield.simulation.particles.BoidShaders
import afield.simulation.particles.BoidSketch
import afield.simulation.particles.BoidSketchSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.boid-sketch.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.BoidSketchSmoke
tags: [smoke, four, gpu, compute, boids, neighbours, madp]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "boid sketch tests PASSED"
*/
/**
 * That a [BoidSketch] step on the GPU is the boids page's `stepWorld`, per boid.
 *
 * The oracle is a transcription of the page's JavaScript (`gather`, every rule's `apply`,
 * `steerInto`, the integration with its speed clamp, edges and rocks) run over the same boids by
 * brute force rather than a grid, in doubles. The GPU walks a sorted grid, on the torus when the
 * edges wrap, feeds one neighbour list to every rule through the rule table, and works in floats,
 * so agreement says the table, the reach, the field of view and every rule do what the page's do.
 *
 * ### One step
 *
 * The first frame is paused, so the boids are read exactly as they were placed; then exactly one
 * step runs. `wander` and `eat` draw random numbers, so the graded flocks leave them out; a third
 * flock runs the hawks for a few hundred steps and checks that they eat and the eaten come back.
 *
 * ### What it asserts
 *
 * - **Position, velocity and the visible-flockmate count match the page's**, for a two-kind flock
 *   with every deterministic rule (separate, align, cohere, flee, chase, seek, orbit, flow over
 *   the page's value noise, avoidRocks, avoidEdges, cruise and a custom rule), once with walls and
 *   rocks and once on the torus.
 * - **Hawks eat**: starlings are caught, respawn, and every boid stays finite and in the window.
 * - **The inspector draws**: with `inspect` on, the step adds the inspected boid's strokes.
 */
object BoidSketchSmoke {

    private const val F = 16

    private val SHADER = """
        #version 450
        #define FLOW_FIELD
        #define CUSTOM_RULE
        #include "boids/flock"
        vec4 color(Boid b) { return hsl(deg(b.heading), 60.0, 60.0); }
        float flowAngle(vec2 uv, float t) { return noise(uv.x * 2.2, uv.y * 2.2, t * 0.0015 + 0.37) * TAU * 2.0; }
        float bestDist; vec2 bestAim; bool found;
        void customStart(int rule, Boid me) { bestDist = 1e9; found = false; }
        void customNeighbour(int rule, Boid me, Boid other, vec2 d, float dist) {
            vec2 ahead = vec2(cos(me.heading), sin(me.heading));
            if (dot(d, ahead) > 0.0 && dist < bestDist) { bestDist = dist; bestAim = d - vec2(cos(other.heading), sin(other.heading)) * 9.0; found = true; }
        }
        vec2 customForce(int rule, Boid me) { return found ? steer(me, bestAim) : vec2(0.0); }
    """.trimIndent()

    private fun spec(edges: BoidEdges, hawks: Boolean): BoidSketchSettings.() -> Unit = {
        this.edges = edges
        fps = 0.0
        paused = true
        if (edges == BoidEdges.BOUNCE) { rock(0.3, 0.35, 55); rock(0.7, 0.6, 70) }
        kind("starling") {
            count = 900
            maxSpeed = 3.0; minSpeed = 1.2; maxForce = 0.08; fov = 280.0; drag = 0.01
            if (hawks) {
                rules(separate(11, 1.8), align(30, 1.0), cohere(42, 0.9), flee("hawk", 90, 4.5), wander(0.1))
            } else {
                rules(
                    separate(11, 1.8), align(30, 1.0), cohere(42, 0.9), flee("hawk", 90, 4.5),
                    seek(0.3, 0.7, 0.2), orbit(0.6, 0.4, 120, 0.3, -1), flow(0.3), custom(46, 0.8),
                    cruise(2.0, 0.2), avoidRocks(1.5, 30), avoidEdges(40, 0.7),
                )
            }
        }
        kind("hawk") {
            count = if (hawks) 4 else 12
            maxSpeed = 3.7; minSpeed = 2.2; maxForce = 0.05
            if (hawks) rules(chase("starling", 260, 1.3), eat("starling", 9, 30), separate(90, 1.5, among = "same"), wander(0.5))
            else rules(chase("starling", 260, 1.3), separate(90, 1.5, among = "same"), cruise(3.0, 0.4))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("Boid Sketch Smoke", initialDimensions = afield.app.protocol.Rect(60.0, 60.0, 900.0, 700.0))
        val renderer = Renderer(window)
        try {
            var failures = 0
            failures += checkStep(renderer, window, BoidEdges.BOUNCE)
            failures += checkStep(renderer, window, BoidEdges.WRAP)
            failures += checkHawks(renderer, window)
            if (failures > 0) {
                println("boid sketch tests FAILED ($failures)")
                exitProcess(1)
            }
            println("boid sketch tests PASSED")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }

    private fun module(renderer: Renderer, name: String) = ComputeModule(
        parameters(renderer) {
            this.name = name
            compute = SHADER.replace("#include \"boids/flock\"", BoidShaders.source("flock"))
        }
    ).pointer()

    private fun run(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
        renderer.device.waitIdle()
    }

    private fun read(sketch: BoidSketch): FloatArray {
        val out = FloatArray(sketch.count * F)
        sketch.latest.get().mapAndRead { get(out, 0, out.size) }
        return out
    }

    private class B(
        var x: Double, var y: Double, var vx: Double, var vy: Double,
        var heading: Double, var speed: Double, var wander: Double, var cool: Double,
        val r: Double, var age: Double, val kind: Int, var n: Int,
    )

    private fun boids(state: FloatArray, count: Int) = List(count) { i ->
        val b = i * F
        B(state[b].toDouble(), state[b + 1].toDouble(), state[b + 2].toDouble(), state[b + 3].toDouble(),
            state[b + 4].toDouble(), state[b + 5].toDouble(), state[b + 6].toDouble(), state[b + 7].toDouble(),
            state[b + 8].toDouble(), state[b + 9].toDouble(),
            java.lang.Float.floatToRawIntBits(state[b + 10]), java.lang.Float.floatToRawIntBits(state[b + 11]))
    }

    // ---- the page's noise, for the flow rule ----

    private fun hash3(i: Int, j: Int, k: Int): Double {
        var h = i * 374761393 + j * 668265263 + k * 1440662683
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h.toLong() and 0xffffffffL) / 4294967296.0
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    private fun noise(x: Double, y: Double, z: Double): Double {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        val xf = smooth(x - floor(x)); val yf = smooth(y - floor(y)); val zf = smooth(z - floor(z))
        fun l(a: Double, b: Double, t: Double) = a + (b - a) * t
        return l(
            l(l(hash3(xi, yi, zi), hash3(xi + 1, yi, zi), xf), l(hash3(xi, yi + 1, zi), hash3(xi + 1, yi + 1, zi), xf), yf),
            l(l(hash3(xi, yi, zi + 1), hash3(xi + 1, yi, zi + 1), xf), l(hash3(xi, yi + 1, zi + 1), hash3(xi + 1, yi + 1, zi + 1), xf), yf),
            zf,
        )
    }

    // ---- the page's stepWorld, transcribed ----

    private fun oracle(sketch: BoidSketch, before: List<B>, w: Double, h: Double, t: Double): List<B> {
        val s = sketch.settings
        val tables = sketch.tables
        val wrap = s.edges == BoidEdges.WRAP
        val rocks = s.rocks.map { Triple(it.x * w, it.y * h, it.r) }
        fun wrapDelta(d: Double, span: Double) = if (!wrap) d else if (d > span / 2) d - span else if (d < -span / 2) d + span else d
        val accX = DoubleArray(before.size); val accY = DoubleArray(before.size)
        val after = before.map { B(it.x, it.y, it.vx, it.vy, it.heading, it.speed, it.wander, it.cool, it.r, it.age, it.kind, it.n) }
        for ((i, b) in after.withIndex()) {
            val kind = tables.kinds[b.kind]
            fun steerInto(dx: Double, dy: Double): DoubleArray? {
                val len = sqrt(dx * dx + dy * dy)
                if (len < 1e-9) return null
                var fx = dx / len * kind.maxSpeed - b.vx; var fy = dy / len * kind.maxSpeed - b.vy
                val f = hypot(fx, fy)
                if (f > kind.maxForce) { fx *= kind.maxForce / f; fy *= kind.maxForce / f }
                return doubleArrayOf(fx, fy)
            }
            val cosHalf = if (kind.fov >= 360) -2.0 else cos(kind.fov / 2 * PI / 180)
            data class N(val j: Int, val dx: Double, val dy: Double, val d2: Double, val seen: Boolean)
            val near = mutableListOf<N>()
            var same = 0
            if (tables.queryRadius[b.kind] > 0) {
                val hx = cos(b.heading); val hy = sin(b.heading)
                for ((j, o) in before.withIndex()) {
                    if (j == i) continue
                    val dx = wrapDelta(o.x - before[i].x, w); val dy = wrapDelta(o.y - before[i].y, h)
                    val d2 = dx * dx + dy * dy
                    if (d2 >= tables.reachSquared[b.kind][o.kind]) continue
                    val seen = dx * hx + dy * hy >= cosHalf * sqrt(d2)
                    if (o.kind == b.kind && seen) same++
                    near += N(j, dx, dy, d2, seen)
                }
            }
            b.n = same
            var ax = 0.0; var ay = 0.0
            for (rl in kind.rules) {
                val mask = tables.among(rl, b.kind)
                val accepted = near.filter { it.d2 <= rl.radius * rl.radius && (!rl.fov || it.seen) && (mask shr before[it.j].kind) and 1 == 1 }
                val f: DoubleArray? = when (rl.type) {
                    "separate" -> { var sx = 0.0; var sy = 0.0; accepted.forEach { val d2s = max(it.d2, 0.25); sx -= it.dx / d2s; sy -= it.dy / d2s }; steerInto(sx, sy) }
                    "align" -> if (accepted.isEmpty()) null else steerInto(accepted.sumOf { before[it.j].vx }, accepted.sumOf { before[it.j].vy })
                    "cohere" -> if (accepted.isEmpty()) null else steerInto(accepted.sumOf { it.dx } / accepted.size, accepted.sumOf { it.dy } / accepted.size)
                    "flee" -> if (accepted.isEmpty()) null else {
                        var sx = 0.0; var sy = 0.0
                        accepted.forEach { val d = sqrt(it.d2).let { v -> if (v == 0.0) 0.5 else v }; val wt = 1 - d / rl.radius; sx -= it.dx / d * wt; sy -= it.dy / d * wt }
                        steerInto(sx, sy)
                    }
                    "chase" -> if (b.cool > 0 || accepted.isEmpty()) null else {
                        val best = accepted.minBy { it.d2 }
                        val lead = sqrt(best.d2) / kind.maxSpeed * 0.5
                        steerInto(best.dx + before[best.j].vx * lead, best.dy + before[best.j].vy * lead)
                    }
                    "seek" -> steerInto(wrapDelta(0.3 * w - b.x, w), wrapDelta(0.7 * h - b.y, h))
                    "orbit" -> {
                        val rx = wrapDelta(b.x - 0.6 * w, w); val ry = wrapDelta(b.y - 0.4 * h, h)
                        val d = hypot(rx, ry).let { if (it == 0.0) 1.0 else it }
                        val ux = rx / d; val uy = ry / d
                        val pull = ((120 - d) / 120).coerceIn(-1.5, 1.5) * 1.6
                        steerInto(-uy * -1 + ux * pull, ux * -1 + uy * pull)
                    }
                    "flow" -> { val a = noise(b.x / w * 2.2, b.y / h * 2.2, t * 0.0015 + 0.37) * 2 * PI * 2.0; steerInto(cos(a), sin(a)) }
                    "custom" -> {
                        val hx = cos(b.heading); val hy = sin(b.heading)
                        val best = accepted.filter { it.dx * hx + it.dy * hy > 0 }.minByOrNull { sqrt(it.d2) }
                        if (best == null) null else {
                            val lead = before[best.j]
                            steerInto(best.dx - cos(lead.heading) * 9, best.dy - sin(lead.heading) * 9)
                        }
                    }
                    "cruise" -> {
                        var fx = cos(b.heading) * rl.params[0] - b.vx; var fy = sin(b.heading) * rl.params[0] - b.vy
                        val f = hypot(fx, fy)
                        if (f > kind.maxForce) { fx *= kind.maxForce / f; fy *= kind.maxForce / f }
                        doubleArrayOf(fx, fy)
                    }
                    "avoidRocks" -> {
                        var sx = 0.0; var sy = 0.0; var any = false
                        for ((rx, ry, rr) in rocks) {
                            val dx = b.x - rx; val dy = b.y - ry
                            val d = hypot(dx, dy).let { if (it == 0.0) 1.0 else it }
                            val gap = d - rr
                            if (gap > rl.params[0]) continue
                            val wt = 1 - max(0.0, gap) / rl.params[0]
                            val side = if (dx * b.vy - dy * b.vx > 0) 1.0 else -1.0
                            sx += (dx / d + -dy / d * side * 0.6) * wt; sy += (dy / d + dx / d * side * 0.6) * wt; any = true
                        }
                        if (any) steerInto(sx, sy) else null
                    }
                    "avoidEdges" -> {
                        val m = rl.params[0]; var sx = 0.0; var sy = 0.0
                        if (b.x < m) sx += 1 - b.x / m
                        if (b.x > w - m) sx -= 1 - (w - b.x) / m
                        if (b.y < m) sy += 1 - b.y / m
                        if (b.y > h - m) sy -= 1 - (h - b.y) / m
                        if (sx == 0.0 && sy == 0.0) null else steerInto(sx, sy)
                    }
                    else -> error("the graded flock has no ${rl.type}")
                }
                if (f != null && rl.weight != 0.0) { ax += f[0] * rl.weight; ay += f[1] * rl.weight }
            }
            accX[i] = ax; accY[i] = ay
        }
        for ((k, q) in after.withIndex()) {
            val kind = tables.kinds[q.kind]
            q.vx += accX[k]; q.vy += accY[k]
            if (kind.drag != 0.0) { q.vx *= 1 - kind.drag; q.vy *= 1 - kind.drag }
            var sp = hypot(q.vx, q.vy)
            if (sp > kind.maxSpeed) { q.vx *= kind.maxSpeed / sp; q.vy *= kind.maxSpeed / sp; sp = kind.maxSpeed }
            else if (sp < kind.minSpeed) {
                if (sp < 1e-6) { q.vx = cos(q.heading) * kind.minSpeed; q.vy = sin(q.heading) * kind.minSpeed }
                else { q.vx *= kind.minSpeed / sp; q.vy *= kind.minSpeed / sp }
                sp = kind.minSpeed
            }
            q.x += q.vx; q.y += q.vy
            if (wrap) {
                if (q.x < 0) q.x += w else if (q.x >= w) q.x -= w
                if (q.y < 0) q.y += h else if (q.y >= h) q.y -= h
            } else {
                if (q.x < 0) { q.x = -q.x; q.vx = abs(q.vx) } else if (q.x > w) { q.x = 2 * w - q.x; q.vx = -abs(q.vx) }
                if (q.y < 0) { q.y = -q.y; q.vy = abs(q.vy) } else if (q.y > h) { q.y = 2 * h - q.y; q.vy = -abs(q.vy) }
            }
            for ((rx, ry, rr) in rocks) {
                val dx = q.x - rx; val dy = q.y - ry; val d = hypot(dx, dy)
                if (d < rr && d > 1e-6) {
                    q.x = rx + dx / d * rr; q.y = ry + dy / d * rr
                    val vn = (q.vx * dx + q.vy * dy) / d
                    if (vn < 0) { q.vx -= vn * dx / d; q.vy -= vn * dy / d }
                }
            }
            if (sp > 0.05) q.heading = atan2(q.vy, q.vx)
            q.speed = sp
            q.age += 1
        }
        return after
    }

    private fun checkStep(renderer: Renderer, window: Window, edges: BoidEdges): Int {
        println("--- one step against the page's stepWorld, edges $edges")
        val sketch = BoidSketch(module(renderer, "boids-step-$edges"), renderer, spec(edges, hawks = false).let { base -> { base(); name = "smoke-$edges"; inspect = true } })
        run(renderer, window, 3)
        val before = boids(read(sketch), sketch.count)
        val w = sketch.size.x.toDouble(); val h = sketch.size.y.toDouble()
        sketch.settings.paused = false
        run(renderer, window, 1)
        sketch.settings.paused = true
        val gpu = boids(read(sketch), sketch.count)
        val strokes = IntArray(1).also { header -> sketch.strokes.get().mapAndRead_ints { get(header) } }[0]
        val want = oracle(sketch, before, w, h, 0.0)

        var worstPos = 0.0; var worstVel = 0.0; var nWrong = 0
        var flockmates = 0
        for (i in gpu.indices) {
            worstPos = max(worstPos, hypot(gpu[i].x - want[i].x, gpu[i].y - want[i].y).let { if (edges == BoidEdges.WRAP) minOf(it, hypot(abs(gpu[i].x - want[i].x) - w, abs(gpu[i].y - want[i].y)), hypot(abs(gpu[i].x - want[i].x), abs(gpu[i].y - want[i].y) - h)) else it })
            worstVel = max(worstVel, hypot(gpu[i].vx - want[i].vx, gpu[i].vy - want[i].vy))
            if (gpu[i].n != want[i].n) nWrong++
            flockmates += want[i].n
        }
        val stepped = gpu.count { it.age == 1.0 }
        if (nWrong > 0) {
            println("  gpu mean flockmates ${"%.2f".format(gpu.sumOf { it.n }.toDouble() / gpu.size)}")
            gpu.indices.filter { gpu[it].n != want[it].n }.take(4).forEach { i ->
                println("  #$i kind ${gpu[i].kind} at (${"%.1f".format(before[i].x)}, ${"%.1f".format(before[i].y)}) heading ${"%.2f".format(before[i].heading)}: gpu n ${gpu[i].n}, page n ${want[i].n}; gpu v (${"%.3f".format(gpu[i].vx)}, ${"%.3f".format(gpu[i].vy)}) page (${"%.3f".format(want[i].vx)}, ${"%.3f".format(want[i].vy)})")
            }
        }
        println("  ${gpu.size} boids in ${w.toInt()}x${h.toInt()}; $stepped stepped once; mean flockmates ${"%.1f".format(flockmates.toDouble() / gpu.size)}")
        println("  worst position ${"%.3g".format(worstPos)} px, velocity ${"%.3g".format(worstVel)} px/step; $nWrong flockmate counts differ; $strokes strokes")
        var failures = 0
        if (stepped != gpu.size) { println("  FAIL: $stepped of ${gpu.size} boids took exactly one step"); failures++ }
        if (worstPos > 2e-3) { println("  FAIL: positions differ from the page's"); failures++ }
        if (worstVel > 2e-3) { println("  FAIL: velocities differ from the page's"); failures++ }
        if (nWrong > 0) { println("  FAIL: a neighbour walk saw a different set from brute force"); failures++ }
        if (flockmates < gpu.size) { println("  FAIL: the flock is too sparse to test the rules"); failures++ }
        val rocks = sketch.settings.rocks.size * 2
        if (strokes <= rocks + 2) { println("  FAIL: inspect on, but the step drew no inspector"); failures++ }
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }

    private fun checkHawks(renderer: Renderer, window: Window): Int {
        println("--- hawks eat, and the eaten come back")
        val sketch = BoidSketch(module(renderer, "boids-hawks"), renderer, spec(BoidEdges.WRAP, hawks = true).let { base -> { base(); name = "smoke-hawks"; paused = false } })
        run(renderer, window, 400)
        val m = sketch.metrics()
        val state = boids(read(sketch), sketch.count)
        val w = sketch.size.x.toDouble(); val h = sketch.size.y.toDouble()
        val outside = state.count { !(it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.y >= 0 && it.x <= w && it.y <= h) }
        println("  after ${m.step} steps: caught ${m.caught}, polarization ${"%.3f".format(m.polarization)}, mean speed ${"%.2f".format(m.meanSpeed)}; $outside outside the window")
        var failures = 0
        if (m.caught <= 0) { println("  FAIL: the hawks caught nothing"); failures++ }
        if (outside > 0) { println("  FAIL: boids left the window or went non-finite"); failures++ }
        if (m.polarization !in 0.0..1.0 || m.boids != sketch.count) { println("  FAIL: the metrics are not a flock's"); failures++ }
        if (failures == 0) println("  ok")
        return if (failures > 0) 1 else 0
    }
}
