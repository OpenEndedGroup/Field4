package afield.geometry.path

import afield.math.linalg.Quaternionf
import afield.math.linalg.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A curve drawn once in a bind pose, between two bind points, and posed each frame between two
 * live points. [pose] turns the curve so the direction from [bindFrom] to [bindTo] points from
 * the first live point to the second, the shortest rotation between those two directions. It
 * stretches the curve along that direction by exactly what it takes to reach the live points,
 * and moves [bindFrom] onto the first one.
 *
 * [across] sets how much of that stretch the curve takes at right angles to the bind direction.
 * The scale across is the stretch along raised to this power: 1 scales the curve evenly, 0 keeps
 * its width whatever its length, and something like 0.5 lets it thin a little as it lengthens and
 * fatten a little as it shortens.
 *
 * [lag] makes the posed curve follow the live points with a delay that varies along it. It is
 * given each curve point's place along the bind direction, 0 at [bindFrom] and 1 at [bindTo], and
 * returns a time in seconds: each point then closes that fraction of its distance to where the
 * pose puts it, the larger the time the more slowly. `{ s -> 0.3 * sin(PI * s) }` keeps the ends
 * pinned and lets the middle trail behind them. A lagging skin remembers where its points were,
 * so make one for each line you draw, inside the process that draws it.
 *
 * Curves in the XY plane posed between points in the XY plane turn about Z, so a flat curve
 * never flips out of its plane, even when the line turns right round.
 */
class SkinnedLine(
    val shape: FLine,
    val bindFrom: Vec3,
    val bindTo: Vec3,
    val across: Double = 1.0,
    val lag: (along: Double) -> Double = { 0.0 },
) {
    private val bindLength = bindFrom.distance(bindTo).toDouble()
    private val axis = Vec3(
        ((bindTo.x - bindFrom.x) / bindLength).toFloat(),
        ((bindTo.y - bindFrom.y) / bindLength).toFloat(),
        ((bindTo.z - bindFrom.z) / bindLength).toFloat(),
    )
    private val flat = isFlat(shape) && bindFrom.z == 0f && bindTo.z == 0f
    private var lags: DoubleArray? = null
    private var posed: Array<Vec3>? = null
    private var posedAt = 0.0

    init {
        require(bindLength > 0) { "The bind points must be apart" }
    }

    /**
     * The curve posed between [from] and [to] at time [now], in seconds, which [lag] measures
     * against. The result is a new FLine with the shape's style.
     */
    fun pose(from: Vec3, to: Vec3, now: Double): FLine {
        val dx = (to.x - from.x).toDouble()
        val dy = (to.y - from.y).toDouble()
        val dz = (to.z - from.z).toDouble()
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        val stretch = length / bindLength
        val width = stretch.pow(across)
        val turn = when {
            length == 0.0 -> Quaternionf()
            flat && dz == 0.0 -> {
                val angle = atan2(axis.x * dy - axis.y * dx, axis.x * dx + axis.y * dy)
                Quaternionf().rotateZ(angle.toFloat())
            }
            else -> Quaternionf().rotationTo(axis, Vec3(dx.toFloat(), dy.toFloat(), dz.toFloat()))
        }
        val targets = mutableListOf<Vec3>()
        val along = mutableListOf<Double>()
        val target = shape.transform { p ->
            val rx = (p.x - bindFrom.x).toDouble()
            val ry = (p.y - bindFrom.y).toDouble()
            val rz = (p.z - bindFrom.z).toDouble()
            val a = rx * axis.x + ry * axis.y + rz * axis.z
            along += (a / bindLength).coerceIn(0.0, 1.0)
            val scaled = Vec3(
                (a * stretch * axis.x + (rx - a * axis.x) * width).toFloat(),
                (a * stretch * axis.y + (ry - a * axis.y) * width).toFloat(),
                (a * stretch * axis.z + (rz - a * axis.z) * width).toFloat(),
            )
            turn.transform(scaled)
            Vec3(from.x + scaled.x, from.y + scaled.y, from.z + scaled.z).also { targets += it }
        }
        if (lags == null || lags!!.size != targets.size) lags = DoubleArray(targets.size) { lag(along[it]) }
        val times = lags!!
        if (times.all { it <= 0.0 }) return target
        val previous = posed
        val dt = now - posedAt
        posedAt = now
        if (previous == null || previous.size != targets.size || dt < 0) {
            posed = Array(targets.size) { Vec3(targets[it]) }
            return target
        }
        for (i in targets.indices) {
            val follow = if (times[i] <= 0.0) 1.0 else 1 - exp(-dt / times[i])
            val p = previous[i]
            val t = targets[i]
            p.set(
                (p.x + (t.x - p.x) * follow).toFloat(),
                (p.y + (t.y - p.y) * follow).toFloat(),
                (p.z + (t.z - p.z) * follow).toFloat(),
            )
        }
        var i = 0
        return shape.transform { Vec3(previous[i++]) }
    }

    private companion object {
        fun isFlat(shape: FLine) = shape.nodes.all { node -> node.storage.all { abs(it.z) == 0f } }
    }
}
