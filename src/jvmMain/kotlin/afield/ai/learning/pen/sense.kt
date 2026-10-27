/**
 * What a sensing pen sees of the ink around it: the Kotlin half of `python/fieldpen/sense.py`,
 * which has the canonical definition. `PenModelTest` holds the two together.
 *
 * At each point the pen sees every point drawn before it except its own last [recent], plus any
 * ink added from outside with [addInk]. Around the pen, [sectors] equal wedges of direction (sector
 * `k` covers `atan2(dy, dx)` in `[-pi + k * 2pi / sectors, -pi + (k + 1) * 2pi / sectors)`) each
 * report `1 - d / radius` for their nearest visible point within [radius], or 0. Two more values
 * follow, the way to the middle of all the visible ink, `tanh((cx - x) / CENTRE_SCALE)` and the same
 * for y: what is close, then where the mass of the drawing is. Units are model units throughout.
 */

package afield.ai.learning.pen

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.tanh

/**
 * The ink a pen has drawn or been shown, kept in a grid of cells [radius] wide so that a look
 * around the pen touches only the nine cells near it.
 */
class PenSense(val sectors: Int, val radius: Double, val recent: Int) {
    private var xs = DoubleArray(256)
    private var ys = DoubleArray(256)
    private var count = 0
    private var visible = 0
    private val grid = HashMap<Long, IntArrayList>()
    private var sumX = 0.0
    private var sumY = 0.0
    private var seen = 0

    /** How many numbers [features] writes: the sectors, then the two pointing at the middle of the ink. */
    val size get() = sectors + 2

    /** Record the point the pen has just reached, and make the points [recent] back visible. */
    fun add(x: Double, y: Double) {
        append(x, y)
        while (visible <= count - 1 - recent) {
            // ink added from outside is already in the grid and the middle
            if (visible !in external) {
                cellOf(xs[visible], ys[visible]).add(visible)
                see(xs[visible], ys[visible])
            }
            visible++
        }
    }

    private fun see(x: Double, y: Double) {
        sumX += x; sumY += y; seen++
    }

    /** Add ink the pen did not draw, visible at once: lines around it it should sense. */
    fun addInk(x: Double, y: Double) {
        // stored like any other point but entered in the grid now, so `recent` never hides it
        // entered in the grid and the middle now, and skipped when `visible` reaches it, so it
        // counts once however many of the pen's own points are still hidden
        append(x, y)
        cellOf(x, y).add(count - 1)
        see(x, y)
        external.add(count - 1)
    }

    private val external = HashSet<Int>()

    /** Write what the pen sees from ([x], [y]) into [out] from [offset], [size] values. */
    fun features(x: Double, y: Double, out: FloatArray, offset: Int) {
        for (k in 0 until size) out[offset + k] = 0f
        if (seen > 0) {
            out[offset + sectors] = tanh((sumX / seen - x) / CENTRE_SCALE).toFloat()
            out[offset + sectors + 1] = tanh((sumY / seen - y) / CENTRE_SCALE).toFloat()
        }
        val gx = cell(x)
        val gy = cell(y)
        for (cx in gx - 1..gx + 1) for (cy in gy - 1..gy + 1) {
            val list = grid[key(cx, cy)] ?: continue
            for (n in 0 until list.size) {
                val j = list[n]
                val dx = xs[j] - x
                val dy = ys[j] - y
                val d = hypot(dx, dy)
                if (d < radius) {
                    val k = sectorOf(dx, dy, sectors)
                    val v = (1.0 - d / radius).toFloat()
                    if (v > out[offset + k]) out[offset + k] = v
                }
            }
        }
    }

    /** An independent copy, for a forked pen. */
    fun copy(): PenSense {
        val other = PenSense(sectors, radius, recent)
        other.xs = xs.copyOf(); other.ys = ys.copyOf(); other.count = count; other.visible = visible
        other.sumX = sumX; other.sumY = sumY; other.seen = seen
        other.external.addAll(external)
        for ((k, v) in grid) other.grid[k] = v.copy()
        return other
    }

    /** Every point the pen has drawn or been shown, in model units: for drawing what it senses. */
    fun points(): List<Pair<Double, Double>> = (0 until count).map { xs[it] to ys[it] }

    private fun append(x: Double, y: Double) {
        if (count == xs.size) { xs = xs.copyOf(count * 2); ys = ys.copyOf(count * 2) }
        xs[count] = x; ys[count] = y; count++
    }

    private fun cell(v: Double) = floor(v / radius).toInt()
    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
    private fun cellOf(x: Double, y: Double) = grid.getOrPut(key(cell(x), cell(y))) { IntArrayList() }

    companion object {
        /** Model units over which the pull towards the middle of the ink saturates (`fieldpen.sense`). */
        const val CENTRE_SCALE = 10.0

        /** The wedge a direction falls in, exactly as `fieldpen.sense.sector_of` computes it. */
        fun sectorOf(dx: Double, dy: Double, sectors: Int): Int =
            floor((atan2(dy, dx) + PI) / (2 * PI) * sectors).toInt().coerceIn(0, sectors - 1)
    }
}

internal class IntArrayList {
    private var a = IntArray(8)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun copy() = IntArrayList().also { it.a = a.copyOf(); it.size = size }
}
