/**
 * Guided sampling for a learned pen: the pen proposes several next points and a [PenGuide] picks
 * one. The pen keeps its hand (its tremor, its rhythm, its habits) while the guide decides where it
 * goes. [FollowGuide] keeps a pen a set distance from the ink beside it, which is hatching.
 * Tests: `PenHatchTest`.
 */

package afield.ai.learning.pen

import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/** Chooses among a pen's proposals for its next point; set one on [PenLine.guide]. */
interface PenGuide {
    /** How many proposals the pen makes each point. */
    val candidates: Int

    /**
     * How bad a proposal is, lower is better: a step from [from] to [to] in drawing units, after
     * which the pen lifts if [lift]. [penUp] says whether this step is a move rather than a mark.
     */
    fun score(from: Vec3, to: Vec3, lift: Boolean, penUp: Boolean): Double

    /** The pen drew the chosen point; a guide that tracks ink records it here. */
    fun drawn(at: Vec3, lift: Boolean) {}
}

/**
 * Keeps a pen [gap] drawing units from the ink beside it, without crossing any: hatching.
 *
 * The ink is whatever was given to [see] and everything the pen has drawn, except its own last
 * [recent] points. A proposal scores by how far its distance to that ink is from [gap], with a
 * heavy cost for coming closer than half the gap, for a step longer than [longest] (a jump that
 * happens to land at the right distance from some far-off ink is not following anything), and for
 * lifting the pen. With nothing in reach (more than [reach] away) only step length counts.
 */
class FollowGuide(
    val gap: Double = 4.0,
    override val candidates: Int = 12,
    val recent: Int = 10,
    val reach: Double = 30.0,
    val liftCost: Double = 2.0,
    val longest: Double = 6.0,
) : PenGuide {
    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val grid = HashMap<Long, MutableList<Int>>()
    private val pending = ArrayDeque<Int>()

    /** Ink the pen should keep its distance from, visible at once: the drawing being hatched. */
    fun see(lines: List<FLine>, spacing: Double = 2.0) {
        lines.forEach { l -> l.pieces().forEach { piece -> resampleForPen(piece, spacing).forEach { index(it.x.toDouble(), it.y.toDouble()) } } }
    }

    override fun drawn(at: Vec3, lift: Boolean) {
        xs += at.x.toDouble(); ys += at.y.toDouble()
        pending.addLast(xs.size - 1)
        while (pending.size > recent) enter(pending.removeFirst())
    }

    override fun score(from: Vec3, to: Vec3, lift: Boolean, penUp: Boolean): Double {
        val d = nearest(to.x.toDouble(), to.y.toDouble())
        var s = if (d > reach) 0.0 else ((d - gap) / gap).let { it * it }
        if (d < gap * 0.5) s += 50.0
        // a mark that passes over ink between its ends also crosses it
        if (!penUp) {
            val mx = (from.x + to.x) / 2.0
            val my = (from.y + to.y) / 2.0
            if (nearest(mx, my) < gap * 0.5) s += 50.0
        }
        val length = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
        if (length > longest) s += 50.0 + length
        if (lift) s += liftCost
        return s
    }

    private fun index(x: Double, y: Double) {
        xs += x; ys += y
        enter(xs.size - 1)
    }

    private fun enter(i: Int) {
        grid.getOrPut(key(cell(xs[i]), cell(ys[i]))) { ArrayList() } += i
    }

    private fun nearest(x: Double, y: Double): Double {
        val gx = cell(x)
        val gy = cell(y)
        var best = Double.POSITIVE_INFINITY
        for (cx in gx - 1..gx + 1) for (cy in gy - 1..gy + 1) {
            for (i in grid[key(cx, cy)] ?: continue) best = minOf(best, hypot(xs[i] - x, ys[i] - y))
        }
        return best
    }

    private val size = max(reach, 1.0)
    private fun cell(v: Double) = floor(v / size).toInt()
    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
}
