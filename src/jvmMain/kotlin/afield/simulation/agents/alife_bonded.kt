package afield.simulation.agents

import afield.graphics.render.ComputeModulePointer
import afield.graphics.render.computeMemoryBarrier
import afield.graphics.vulkan.Target
import afield.math.linalg.Rectanglef
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bonded-particle organisms: cells joined by springs, after Christian Heinemann's
 * [ALIEN](https://github.com/chrxh/alien) (version 5.0-beta was the reference here).
 *
 * In ALIEN a creature is not drawn on a grid. It is a set of particles ("cells") held together by
 * bonds, and some cells have a function: a muscle changes the length of its bonds, a sensor
 * reads its surroundings, a constructor builds new cells from a genome. This file is a small,
 * readable version of that idea with three of those parts:
 *
 * - **Bonds** are damped springs with a rest length. A bond whose strain passes its limit breaks,
 *   at both ends at once.
 * - **Muscles** are bonds whose rest length oscillates, `rest * (1 + amp * sin(omega * t + phase))`.
 * - **Energy** sits in every cell, flows along bonds toward the emptier end, and is spent by
 *   muscles. A muscle's stroke is scaled by the mean energy of its two cells, so a creature that
 *   runs out stops moving.
 *
 * The medium is a viscous fluid at rest, with [resistive-force-theory](https://en.wikipedia.org/wiki/Resistive_force_theory)
 * drag: moving sideways to a cell's bonds costs [BondedParams.dragNormal], moving along them
 * [BondedParams.dragTangent]. That anisotropy is what lets a bending wave push a body forward, as
 * it does for a nematode; with equal drags a [genome] worm goes nowhere.
 *
 * [BondedWorld.step] is written one cell at a time, reading only the previous state, so the same
 * function is a compute shader: the `compute` tab of the `bonded_organisms` transcript
 * (`dev_docs/agents/transcripts/bonded_organisms.yaml`) is that shader, cell for cell, and
 * [BondedWorld.cellFloats] / [BondedWorld.bondFloats] are its two buffers. Each bond is stored at
 * both of its ends with the same parameters, so no invocation writes to another cell, and both ends
 * of a bond reach the same break decision from the same numbers.
 */
class BondedParams {
    /** Time step. */
    var dt = 0.02f
    /** Spring stiffness of every bond. */
    var stiffness = 60f
    /** Damping of the relative velocity along a bond. */
    var bondDamping = 2f
    /** Drag on motion across a cell's bonds. */
    var dragNormal = 3f
    /** Drag on motion along a cell's bonds. Less than [dragNormal] is what makes swimming possible. */
    var dragTangent = 0.3f
    /** Muscle angular frequency, radians per unit time. */
    var omega = PI.toFloat()
    /** Any two cells closer than this push apart. */
    var repulsionRadius = 0.6f
    /** Strength of that push. */
    var repulsionStiffness = 40f
    /** Rate at which energy flows along a bond, per unit difference. */
    var energyDiffusion = 0.5f
    /** Energy a cell spends per unit time per unit of muscle amplitude it carries. */
    var muscleCost = 0.0f
    /** The energy at which a muscle reaches its full stroke. */
    var fullEnergy = 1f
    /** Half-width of the soft walled box cells are pushed back into; 0 for no walls. */
    var wallX = 0f
    /** Half-height of that box. */
    var wallY = 0f
    /** How hard a wall pushes back, per unit of penetration. */
    var wallStiffness = 20f
}

/**
 * The GPU world's step counter, shared by the boxes that step, draw and check it. [tick] is the
 * compute shader's `tick` push constant: it returns the step to dispatch, advancing once per
 * dispatch ([stepsPerFrame] dispatches a frame through [stepping]), or -1 once the world has
 * reached [freezeAt], which makes the shader write nothing.
 *
 * It advances on a change of frame rather than on every call because a push-constant lambda is
 * evaluated more than once per dispatch (twice, measured), so a lambda that counts its own calls
 * skips steps; with two calls per dispatch the ping-pong parity never changed and the world
 * stood still. It is a float, exact to 2^24 steps, so the frozen value can be negative.
 */
class BondedClock {
    /** Steps the GPU has been asked to take. */
    @Volatile var step = 0
    /** Stop stepping once [step] reaches this; -1 never stops. */
    @Volatile var freezeAt = -1
    /** Steps taken in each renderer frame by the [Target] that [stepping] returns. */
    @Volatile var stepsPerFrame = 1
    private var substep = 0
    private var lastFrame = Long.MIN_VALUE
    private var lastSubstep = -1
    private var current = -1f

    /** The push-constant value for the dispatch in renderer frame [frame] (and the current substep). */
    @Synchronized fun tick(frame: Long): Float {
        if (frame != lastFrame || substep != lastSubstep) {
            lastFrame = frame
            lastSubstep = substep
            current = if (freezeAt in 0..step) -1f else (step++).toFloat()
        }
        return current
    }

    /**
     * Dispatches [module] [stepsPerFrame] times a frame, with a compute barrier after each, so
     * each step reads what the one before it wrote. Register it in place of the module:
     * `renderer.computeList += "bondedStep" to clock.stepping(module)`.
     */
    fun stepping(module: ComputeModulePointer): Target {
        val bounds = Rectanglef(0f, 0f, 0f, 0f)
        return Target { command, _ ->
            repeat(stepsPerFrame.coerceAtLeast(1)) { k ->
                synchronized(this) { substep = k }
                module.render(command, bounds)
                computeMemoryBarrier(command, null as Long?)
            }
        }
    }

    /** Restarts counting from step 0. */
    @Synchronized fun restart() { step = 0; lastFrame = Long.MIN_VALUE; lastSubstep = -1; current = -1f }

    /** Which half of the cell buffer holds the latest positions: the one the last step wrote. */
    val latestHalf: Int get() = step and 1
}

/** One creature's cells: a contiguous index range in its [BondedWorld]. */
data class BondedOrganism(val genome: String, val first: Int, val count: Int) {
    /** The cell indices. */
    val cells: IntRange get() = first until first + count
}

/**
 * A fixed-capacity world of cells and bonds, in flat float arrays. Every cell has four bond
 * slots, enough for the ladder bodies [genome] builds.
 */
class BondedWorld(val capacity: Int, val params: BondedParams = BondedParams()) {
    /** Cells in use. */
    var count = 0
        private set
    /** Steps taken; simulated time is `steps * dt`. */
    var steps = 0
        private set

    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val energy = FloatArray(capacity)
    /** 0 stiff, 1 muscle, 2 weak, 3 free matter. Used for colour only. */
    val kind = FloatArray(capacity)
    /** Which organism a cell belongs to, or -1 for free matter. Used for colour only. */
    val owner = FloatArray(capacity)

    /** Bond slots, four per cell: partner index (-1 for empty), rest, muscle amplitude, phase, strain limit, drag-axis weight. */
    val partner = IntArray(capacity * 4) { -1 }
    val rest = FloatArray(capacity * 4)
    val amp = FloatArray(capacity * 4)
    val phase = FloatArray(capacity * 4)
    val limit = FloatArray(capacity * 4)
    /** How much a bond counts toward its cells' drag axis: 1 for the rails of a body, 0 for rungs and braces. */
    val axis = FloatArray(capacity * 4)

    val organisms = mutableListOf<BondedOrganism>()

    private val nx = FloatArray(capacity)
    private val ny = FloatArray(capacity)
    private val nvx = FloatArray(capacity)
    private val nvy = FloatArray(capacity)
    private val ne = FloatArray(capacity)

    /** Adds a cell and returns its index. */
    fun addCell(px: Float, py: Float, e: Float = 1f, kind: Int = 0, owner: Int = -1): Int {
        require(count < capacity) { "BondedWorld is full ($capacity cells)" }
        val i = count++
        x[i] = px; y[i] = py; vx[i] = 0f; vy[i] = 0f
        energy[i] = e; this.kind[i] = kind.toFloat(); this.owner[i] = owner.toFloat()
        return i
    }

    /** Joins two cells, writing the same bond into a free slot at each end. */
    fun bond(i: Int, j: Int, restLength: Float, amplitude: Float = 0f, phase: Float = 0f, strainLimit: Float = 10f,
             dragAxis: Float = 1f) {
        val si = freeSlot(i)
        val sj = freeSlot(j)
        for ((a, s, b) in listOf(Triple(i, si, j), Triple(j, sj, i))) {
            val k = a * 4 + s
            partner[k] = b; rest[k] = restLength; amp[k] = amplitude; this.phase[k] = phase; limit[k] = strainLimit; axis[k] = dragAxis
        }
    }

    private fun freeSlot(i: Int): Int {
        for (s in 0 until 4) if (partner[i * 4 + s] < 0) return s
        error("cell $i already has four bonds")
    }

    /** Live bonds, each counted once. */
    fun bondCount(): Int {
        var n = 0
        for (i in 0 until count) for (s in 0 until 4) if (partner[i * 4 + s] > i) n++
        return n
    }

    /** Builds an organism from a genome string at ([cx], [cy]) heading along [angle]. See [genome]. */
    fun grow(genomeText: String, cx: Float, cy: Float, angle: Float = 0f, waveStep: Float = 0.8f,
             amplitude: Float = 0.25f, energy: Float = 1f): BondedOrganism {
        val o = genome(this, genomeText, cx, cy, angle, waveStep, amplitude, energy, organisms.size)
        organisms += o
        return o
    }

    /** Scatters [n] free, unbonded cells uniformly in a box: loose matter for creatures to push through. */
    fun scatter(n: Int, x0: Float, y0: Float, x1: Float, y1: Float, seed: Long = 1L) {
        val r = java.util.Random(seed)
        repeat(n) { addCell(x0 + (x1 - x0) * r.nextFloat(), y0 + (y1 - y0) * r.nextFloat(), 0f, 3, -1) }
    }

    /** Mean position of an organism's cells. */
    fun centroid(o: BondedOrganism): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0
        for (i in o.cells) { sx += x[i]; sy += y[i] }
        return sx / o.count to sy / o.count
    }

    /** Total momentum (unit masses). */
    fun momentum(): Pair<Double, Double> {
        var px = 0.0; var py = 0.0
        for (i in 0 until count) { px += vx[i]; py += vy[i] }
        return px to py
    }

    /** Total energy held in cells. */
    fun totalEnergy(): Double = (0 until count).sumOf { energy[it].toDouble() }

    /** Advances one step. The body is the compute shader's `main`, one cell at a time. */
    fun step() {
        val p = params
        val t = steps * p.dt
        for (i in 0 until count) {
            var fx = 0f; var fy = 0f
            var mxx = 0f; var mxy = 0f; var myy = 0f
            var nb = 0f
            var de = 0f; var cost = 0f
            val xi = x[i]; val yi = y[i]; val vxi = vx[i]; val vyi = vy[i]; val ei = energy[i]
            for (s in 0 until 4) {
                val k = i * 4 + s
                val j = partner[k]
                if (j < 0) continue
                val dx = x[j] - xi; val dy = y[j] - yi
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
                val ux = dx / len; val uy = dy / len
                val scale = (0.5f * (ei + energy[j]) / p.fullEnergy).coerceIn(0f, 1f)
                val r = rest[k] * (1f + amp[k] * scale * sin(p.omega * t + phase[k]))
                if (abs(len - r) / r > limit[k]) { partner[k] = -1; continue }
                val rv = (vx[j] - vxi) * ux + (vy[j] - vyi) * uy
                val f = p.stiffness * (len - r) + p.bondDamping * rv
                fx += f * ux; fy += f * uy
                val wa = axis[k]
                mxx += wa * ux * ux; mxy += wa * ux * uy; myy += wa * uy * uy
                nb += wa
                de += p.energyDiffusion * (energy[j] - ei)
                cost += abs(amp[k]) * scale
            }
            val rr = p.repulsionRadius
            for (j in 0 until count) {
                if (j == i) continue
                val dx = x[j] - xi; val dy = y[j] - yi
                val d2 = dx * dx + dy * dy
                if (d2 >= rr * rr || d2 < 1e-12f) continue
                val len = sqrt(d2)
                val f = -p.repulsionStiffness * (rr - len)
                fx += f * dx / len; fy += f * dy / len
            }
            if (p.wallX > 0f) {
                fx -= p.wallStiffness * (maxOf(xi - p.wallX, 0f) + minOf(xi + p.wallX, 0f))
                fy -= p.wallStiffness * (maxOf(yi - p.wallY, 0f) + minOf(yi + p.wallY, 0f))
            }
            // resistive-force drag: -dragNormal * v + (dragNormal - dragTangent) * M v, M the
            // axis-weighted mean of u u^T over the cell's bonds
            val inv = if (nb > 0f) 1f / nb else 0f
            mxx *= inv; mxy *= inv; myy *= inv
            val g = p.dragNormal - p.dragTangent
            fx += -p.dragNormal * vxi + g * (mxx * vxi + mxy * vyi)
            fy += -p.dragNormal * vyi + g * (mxy * vxi + myy * vyi)
            val nvxi = vxi + fx * p.dt
            val nvyi = vyi + fy * p.dt
            nvx[i] = nvxi; nvy[i] = nvyi
            nx[i] = xi + nvxi * p.dt; ny[i] = yi + nvyi * p.dt
            ne[i] = (ei + (de - p.muscleCost * cost) * p.dt).coerceAtLeast(0f)
        }
        for (i in 0 until count) { x[i] = nx[i]; y[i] = ny[i]; vx[i] = nvx[i]; vy[i] = nvy[i]; energy[i] = ne[i] }
        steps++
    }

    /** Runs [n] steps. */
    fun run(n: Int) = repeat(n) { step() }

    /**
     * The cell buffer the shader reads and writes: two `vec4` per cell, `(x, y, vx, vy)` and
     * `(energy, kind, owner, 0)`, repeated twice because the shader ping-pongs between halves.
     */
    fun cellFloats(): FloatArray {
        val out = FloatArray(capacity * 8 * 2)
        for (h in 0 until 2) for (i in 0 until count) {
            val o = (h * capacity + i) * 8
            out[o] = x[i]; out[o + 1] = y[i]; out[o + 2] = vx[i]; out[o + 3] = vy[i]
            out[o + 4] = energy[i]; out[o + 5] = kind[i]; out[o + 6] = owner[i]
        }
        return out
    }

    /** The bond buffer: six `vec4` per cell, the four slots' partners, rests, amplitudes, phases, limits and drag-axis weights. */
    fun bondFloats(): FloatArray {
        val out = FloatArray(capacity * 24)
        for (i in 0 until count) for (s in 0 until 4) {
            val k = i * 4 + s; val o = i * 24 + s
            out[o] = partner[k].toFloat(); out[o + 4] = rest[k]; out[o + 8] = amp[k]
            out[o + 12] = phase[k]; out[o + 16] = limit[k]; out[o + 20] = axis[k]
        }
        return out
    }
}

/**
 * Builds a ladder-bodied organism from a genome string, one segment per character, into [world].
 *
 * Each segment is a pair of cells, one on each rail, joined by a rung; consecutive segments are
 * joined along both rails and by one diagonal, which keeps the ladder from shearing flat. What the
 * character says is what the two rail bonds *ahead* of that segment do:
 *
 * - `M` — **muscle**: the left rail oscillates with phase `k * waveStep`, the right rail half a cycle
 *   behind, so the segment bends one way and then the other. Successive `M`s with a non-zero
 *   [waveStep] make a travelling wave down the body.
 * - `L`, `R` — a muscle whose left rail is shorter (`L`) or longer (`R`) at rest, so the body
 *   curves and swims in a circle.
 * - `S` — **stiff**: plain springs.
 * - `W` — **weak**: plain springs with a strain limit of 0.15, the place the body tears.
 *
 * Any other character is skipped, so `"S MMMM MMMM S"` reads as written.
 */
fun genome(world: BondedWorld, text: String, cx: Float, cy: Float, angle: Float = 0f, waveStep: Float = 0.8f,
           amplitude: Float = 0.25f, energy: Float = 1f, owner: Int = 0): BondedOrganism {
    val genes = text.filter { it in "MLRSW" }
    require(genes.isNotEmpty()) { "genome '$text' has no M, L, R, S or W" }
    val n = genes.length
    val ca = cos(angle); val sa = sin(angle)
    val first = world.count
    for (k in 0 until n) {
        val along = k - (n - 1) / 2f
        val kind = when (genes[k]) { 'M', 'L', 'R' -> 1; 'W' -> 2; else -> 0 }
        for (side in listOf(-0.5f, 0.5f)) {
            world.addCell(cx + along * ca - side * sa, cy + along * sa + side * ca, energy, kind, owner)
        }
    }
    val diag = sqrt(2f)
    for (k in 0 until n) {
        val l = first + 2 * k; val r = l + 1
        world.bond(l, r, 1f, dragAxis = 0f)
        if (k + 1 < n) {
            val g = genes[k]
            val a = if (g in "MLR") amplitude else 0f
            val lim = if (g == 'W') 0.15f else 10f
            val bias = when (g) { 'L' -> -0.08f; 'R' -> 0.08f; else -> 0f }
            world.bond(l, l + 2, 1f + bias, a, k * waveStep, lim)
            world.bond(r, r + 2, 1f - bias, a, k * waveStep + PI.toFloat(), lim)
            world.bond(l, r + 2, diag, 0f, 0f, lim, dragAxis = 0f)
        }
    }
    return BondedOrganism(text, first, 2 * n)
}
