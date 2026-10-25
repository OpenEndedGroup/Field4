@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spatial

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.linalg.Vec2
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/*
The central-path fan as a map from the (θ, log μ) plane into a polygon.

For an objective direction c(θ) = (cos θ, sin θ) and barrier weight μ, the barrier maximiser
x(θ, μ) solves  c(θ) = μ Σ wᵢ nᵢ / sᵢ(x). Write g(x) = Σ wᵢ nᵢ / sᵢ(x): the gradient of the
(negated) barrier, a diffeomorphism from the polygon's interior onto the whole plane. Then
g(x) = c(θ)/μ, so (θ, 1/μ) are polar coordinates of g(x) and the fan is the polar grid pulled
back through g⁻¹: one-to-one onto the interior minus the weighted analytic centre (g = 0).

Differentiating the optimality condition with M = μ Σ wᵢ nᵢnᵢᵀ / sᵢ² (the negated Hessian):
  ∂x/∂θ     =  M⁻¹ c'(θ)
  ∂x/∂log μ = −M⁻¹ c(θ)
and det[∂x/∂θ, ∂x/∂log μ] = det(M⁻¹) · det[c', −c] = 1 / det M > 0. The map never folds; it only
pinches, at both ends of the log μ axis.
*/

/**
 * One point of an [LpFanMap]: where `(theta, logMu)` lands in the polygon, and the map's Jacobian
 * there.
 *
 * @property theta the objective direction's angle, radians.
 * @property logMu the natural logarithm of the barrier weight μ.
 * @property x the barrier maximiser's x.
 * @property y the barrier maximiser's y.
 * @property dxdTheta `∂x/∂θ`, how the point moves as the direction turns: `M⁻¹ c'(θ)`.
 * @property dxdLogMu `∂x/∂log μ`, how it moves as μ grows: `−M⁻¹ c(θ)`. Its negative points
 *   along the central path toward the optimal vertex.
 * @property newtonSteps the Newton steps this point took from its starting guess.
 */
class LpFanPoint(
    val theta: Double,
    val logMu: Double,
    val x: Double,
    val y: Double,
    val dxdTheta: Vec2,
    val dxdLogMu: Vec2,
    val newtonSteps: Int,
) {
    /** The point, for drawing. */
    val position: Vec2 get() = vec(x, y)

    /**
     * `det[∂x/∂θ, ∂x/∂log μ]`: the area in the polygon that a unit of (θ, log μ) area covers here.
     * Always positive, and equal to `1 / det M`; it falls toward 0 near the analytic centre (large
     * μ) and near the vertices (small μ), where many parameter values crowd into little room.
     */
    val jacobianDeterminant: Double
        get() = dxdTheta.x.toDouble() * dxdLogMu.y - dxdTheta.y.toDouble() * dxdLogMu.x

    override fun toString(): String = "LpFanPoint(θ=%.4f, log μ=%.3f → (%.5f, %.5f), det J=%.3e)".format(theta, logMu, x, y, jacobianDeterminant)
}

/**
 * The fan of central paths as a smooth map `x(θ, log μ)` from a parameter plane into [polygon]:
 * θ is the objective direction's angle and `log μ` the barrier weight's logarithm. Each value is
 * the barrier maximiser, found by damped Newton from a starting guess; give it a nearby point
 * ([at] with `near`, or let [grid] and [warp] chain their own) and it takes one or two steps.
 *
 * Every [LpFanPoint] carries the Jacobian, computed exactly by implicit differentiation of the
 * optimality condition `c(θ) = μ Σ wᵢ nᵢ / sᵢ` rather than by differences. Its determinant is
 * `1 / det M` with `M = μ Σ wᵢ nᵢnᵢᵀ / sᵢ²`, so it is positive everywhere: the map is one-to-one
 * onto the interior minus the weighted analytic centre and never folds. It does pinch. As μ grows
 * the points close in on the analytic centre (θ there is a polar angle, as at the origin of polar
 * coordinates); as μ falls, every θ in a vertex's normal cone runs into that one vertex, and the
 * determinant goes to zero there. The few angles near an edge's outward normal go the other way:
 * a ring of small μ crosses the whole edge inside a window of angles about μ wide, so in that
 * window the map is steep in θ, and a fixed angular step draws the crossing as one long chord. A
 * grid drawn through the map shows all of this as cells shrinking to slivers at the centre and
 * the vertices and stretching along the edges, and no amount of resolution removes it.
 *
 * Uses: [grid] for the curvilinear coordinate lines (θ lines are central paths, μ lines closed
 * rings round the centre), [warp] to carry any [FLine] drawn in the parameter plane into the
 * polygon, and [at] for particles moved along `∂x/∂log μ` or shaded by [LpFanPoint.jacobianDeterminant].
 *
 * @property polygon the polygon the map lands in.
 * @property weights each edge's barrier weight, as for [centralPath].
 */
class LpFanMap(val polygon: LpPolygon, weights: DoubleArray? = null) {
    val weights: DoubleArray = polygon.checkedWeights(weights)

    /**
     * The point for direction angle [theta] and barrier weight `exp(logMu)`, with Newton started at
     * [start] (strictly inside; the polygon's centre by default).
     */
    fun at(theta: Double, logMu: Double, start: Vec2 = polygon.center): LpFanPoint =
        solve(theta, logMu, start.x.toDouble(), start.y.toDouble())

    /** The point for [theta] and [logMu], with Newton started from a neighbouring point [near]. */
    fun at(theta: Double, logMu: Double, near: LpFanPoint): LpFanPoint = solve(theta, logMu, near.x, near.y)

    private fun decrement(theta: Double, logMu: Double, p: LpFanPoint): Double =
        barrierDecrement(polygon.normalX, polygon.normalY, polygon.bound, weights, cos(theta), sin(theta), exp(logMu), p.x, p.y)

    private fun solve(theta: Double, logMu: Double, x0: Double, y0: Double): LpFanPoint {
        require(theta.isFinite() && logMu.isFinite()) { "LpFanMap needs a finite angle and log μ, got $theta and $logMu" }
        require(polygon.containsStrictly(x0, y0)) { "LpFanMap: Newton must start strictly inside the polygon; ($x0, $y0) is not" }
        val cx = cos(theta)
        val cy = sin(theta)
        val mu = exp(logMu)
        val p = barrierNewton(polygon.normalX, polygon.normalY, polygon.bound, weights, cx, cy, mu, x0, y0)
        var mxx = 0.0
        var mxy = 0.0
        var myy = 0.0
        for (i in 0 until polygon.edgeCount) {
            val s = polygon.slack(i, p[0], p[1])
            val q = mu * weights[i] / (s * s)
            mxx += q * polygon.normalX[i] * polygon.normalX[i]
            mxy += q * polygon.normalX[i] * polygon.normalY[i]
            myy += q * polygon.normalY[i] * polygon.normalY[i]
        }
        val det = mxx * myy - mxy * mxy
        // M⁻¹ v for v = c' = (−sin, cos) and v = −c.
        val tx = (myy * -cy - mxy * cx) / det
        val ty = (mxx * cx - mxy * -cy) / det
        val lx = (myy * -cx - mxy * -cy) / det
        val ly = (mxx * -cy - mxy * -cx) / det
        return LpFanPoint(theta, logMu, p[0], p[1], vec(tx, ty), vec(lx, ly), p[2].toInt())
    }

    /**
     * The map sampled on a [thetas] × [logMus] grid: θ in equal steps round the whole circle from
     * [startAngle], `log μ` in equal steps from [logMuHigh] down to [logMuLow]. Each point starts
     * Newton from whichever of its two solved neighbours (the previous angle at this μ, or this
     * angle at the previous μ) Newton judges closer, which takes a little over half the steps cold
     * starts would ([LpFanGrid.newtonSteps]).
     */
    fun grid(
        thetas: Int = 96,
        logMus: Int = 48,
        logMuHigh: Double = ln(100.0),
        logMuLow: Double = ln(1e-3),
        startAngle: Double = 0.0,
    ): LpFanGrid {
        require(thetas >= 3) { "LpFanMap.grid needs at least 3 angles, got $thetas" }
        require(logMus >= 2) { "LpFanMap.grid needs at least 2 values of log μ, got $logMus" }
        require(logMuHigh > logMuLow) { "LpFanMap.grid needs logMuHigh > logMuLow, got $logMuHigh and $logMuLow" }
        val angle = DoubleArray(thetas) { startAngle + 2.0 * PI * it / thetas }
        val level = DoubleArray(logMus) { logMuHigh + (logMuLow - logMuHigh) * it / (logMus - 1) }
        val rows = ArrayList<List<LpFanPoint>>(thetas)
        for (i in 0 until thetas) {
            val previous = rows.lastOrNull()
            val row = ArrayList<LpFanPoint>(logMus)
            for (j in 0 until logMus) {
                val a = previous?.get(j)
                val b = row.lastOrNull()
                val near = if (a != null && b != null) {
                    if (decrement(angle[i], level[j], a) < decrement(angle[i], level[j], b)) a else b
                } else a ?: b
                row += if (near != null) at(angle[i], level[j], near) else at(angle[i], level[j])
            }
            rows += row
        }
        return LpFanGrid(angle, level, rows)
    }

    /**
     * Carry [line], drawn in the parameter plane with x as θ (radians) and y as `log μ`, into the
     * polygon. Each segment is cut into [stepsPerSegment] pieces before mapping (cubic segments
     * are evaluated along their curve), since a straight segment in the parameter plane is a curve
     * in the polygon; subpaths stay separate. Newton for each point starts from the last. Colour
     * and thickness are copied from [line].
     */
    fun warp(line: FLine, stepsPerSegment: Int = 16): FLine {
        require(stepsPerSegment >= 1) { "LpFanMap.warp needs at least one step per segment, got $stepsPerSegment" }
        val out = FLine()
        var last: LpFanPoint? = null
        var cursorX = 0.0
        var cursorY = 0.0
        var subpathX = 0.0
        var subpathY = 0.0
        fun emit(u: Double, v: Double, move: Boolean) {
            val p = last?.let { at(u, v, it) } ?: at(u, v)
            last = p
            if (move) out.moveTo(p.x, p.y) else out.lineTo(p.x, p.y)
        }
        fun segment(x1: Double, y1: Double) {
            for (k in 1..stepsPerSegment) {
                val t = k.toDouble() / stepsPerSegment
                emit(cursorX + (x1 - cursorX) * t, cursorY + (y1 - cursorY) * t, false)
            }
            cursorX = x1
            cursorY = y1
        }
        for (node in line.nodes) {
            when (node) {
                is FLine.MoveTo -> {
                    cursorX = node.to.x.toDouble(); cursorY = node.to.y.toDouble()
                    subpathX = cursorX; subpathY = cursorY
                    emit(cursorX, cursorY, true)
                }
                is FLine.LineTo -> segment(node.to.x.toDouble(), node.to.y.toDouble())
                is FLine.CubicTo -> {
                    val ax = cursorX; val ay = cursorY
                    for (k in 1..stepsPerSegment) {
                        val t = k.toDouble() / stepsPerSegment
                        val s = 1.0 - t
                        val u = s * s * s * ax + 3 * s * s * t * node.c1.x + 3 * s * t * t * node.c2.x + t * t * t * node.to.x
                        val v = s * s * s * ay + 3 * s * s * t * node.c1.y + 3 * s * t * t * node.c2.y + t * t * t * node.to.y
                        emit(u, v, false)
                    }
                    cursorX = node.to.x.toDouble(); cursorY = node.to.y.toDouble()
                }
                is FLine.Close -> segment(subpathX, subpathY)
            }
        }
        out.color = Vec4(line.color)
        out.thickness = line.thickness ?: 1.0
        return out
    }
}

/**
 * The fan map sampled on a grid, from [LpFanMap.grid]. `points[i][j]` is angle `thetas[i]` and
 * level `logMus[j]` (largest μ first).
 */
class LpFanGrid(val thetas: DoubleArray, val logMus: DoubleArray, val points: List<List<LpFanPoint>>) {
    /** Newton steps the whole grid took. */
    val newtonSteps: Int get() = points.sumOf { row -> row.sumOf { it.newtonSteps } }

    /** The smallest Jacobian determinant on the grid; it is at the small-μ or large-μ edge. */
    val minDeterminant: Double get() = points.minOf { row -> row.minOf { it.jacobianDeterminant } }

    /**
     * The θ lines, every [every]th angle: each is a central path from the large-μ end to the small,
     * coloured round a hue circle as [toFLines] colours a fan.
     */
    fun thetaLines(every: Int = 1, alpha: Double = 0.85, thickness: Double = 1.0): List<FLine> =
        thetas.indices.filter { it % every == 0 }.map { i ->
            val h = 2.0 * PI * i / thetas.size
            polyline(points[i].map { it.position }, false, vec(0.6 + 0.4 * cos(h), 0.6 + 0.4 * cos(h - 2.1), 0.6 + 0.4 * cos(h + 2.1), alpha), thickness)
        }

    /** The μ lines, every [every]th level: closed rings round the analytic centre. */
    fun muLines(every: Int = 1, color: Vec4 = vec(1.0, 1.0, 1.0, 0.45), thickness: Double = 0.8): List<FLine> =
        logMus.indices.filter { it % every == 0 }.map { j -> polyline(points.map { it[j].position }, true, color, thickness) }

    private fun polyline(ps: List<Vec2>, closed: Boolean, color: Vec4, thickness: Double): FLine = FLine().also { f ->
        ps.forEachIndexed { k, p -> if (k == 0) f.moveTo(p.x.toDouble(), p.y.toDouble()) else f.lineTo(p.x.toDouble(), p.y.toDouble()) }
        if (closed) f.lineTo(ps[0].x.toDouble(), ps[0].y.toDouble())
        f.color = Vec4(color)
        f.thickness = thickness
    }
}

/** This polygon's central-path fan as a map `x(θ, log μ)`; see [LpFanMap]. */
fun LpPolygon.fanMap(weights: DoubleArray? = null): LpFanMap = LpFanMap(this, weights)
