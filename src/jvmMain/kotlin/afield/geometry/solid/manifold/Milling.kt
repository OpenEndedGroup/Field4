@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.solid.manifold

import afield.math.linalg.Vec3
import kotlin.math.sqrt

/**
 * What a part looks like after milling: the material a cutting tool cannot remove, as a [Solid].
 *
 * [ballMilled] previews a ball-end mill of a given radius, either from any direction or from one or
 * more fixed setups. Every concave edge comes back filleted, every pocket corner rounded, and every
 * slot narrower than the ball filled in, while convex edges stay sharp. [closed] is the general
 * operation underneath, the morphological closing of a part by any tool shape.
 */

/**
 * The morphological **closing** of this solid by [tool]: everything [tool] cannot reach without
 * passing through this solid.
 *
 * [tool] is the space the cutter occupies when its reference point sits at the origin. A position is
 * allowed when the tool placed there does not overlap this solid, and the result is everything that no
 * allowed position covers. That makes it the part as a machine would leave it, if the cutter could
 * reach every allowed position:
 *
 * ```kotlin
 * val ball = Solid.sphere(0.5, segments = 24)
 * val milled = part.closed(ball)          // concave edges filleted by 0.5, convex edges untouched
 * ```
 *
 * It is `this ⊕ (−tool)` followed by Manifold's Minkowski difference with [tool]. The result always
 * contains this solid. Minkowski sums of a non-convex part are expensive: they cost roughly one
 * convex hull per boundary triangle. A tool with fewer vertices, set with `segments` on [Solid.sphere],
 * is the main lever on speed. [ballMilled] is the convenience for the usual case.
 */
fun Solid.closed(tool: Solid): Solid {
    val reflected = tool.scaled(-1.0, -1.0, -1.0)
    val forbidden = minkowskiSum(reflected)
    reflected.close()
    val closing = forbidden.minkowskiDifference(tool)
    forbidden.close()
    val reach = tool.bounds.size.let { maxOf(it.x, it.y, it.z).toDouble() } / 2.0
    val result = withAdded(closing, thinnest = 0.01 * reach)
    closing.close()
    return result
}

/**
 * This solid united with whatever of [added] lies outside it, leaving out pieces thinner than [thinnest].
 *
 * The closing contains the part mathematically, but the Minkowski sum and difference re-derive every
 * face, and a re-derived face lands a few ε away from the original. Two kinds of piece come out of
 * that. A sloping face leaves flat, zero-thickness patches, which draw as stray specks. A shallow
 * concave crease, such as the one between two facets of a polygonal hole, leaves a hairline wedge
 * whose sides sit ε clear of the part's faces, so uniting it back opens a tunnel at every crease. A
 * 48-sided hole gains dozens of handles that way. Neither is anything a mill would leave that a viewer
 * could see. Uniting the part with only the substantial pieces keeps the part's own faces exactly
 * where they were. A piece counts as substantial when its volume over its surface area, a measure of
 * thickness, exceeds [thinnest]; at one percent of the tool's radius, that drops the fill of concave
 * creases shallower than about 16°, which is under a five-hundredth of the radius deep.
 */
internal fun Solid.withAdded(added: Solid, thinnest: Double): Solid {
    val residue = added - this
    val pieces = residue.decompose()
    residue.close()
    val kept = pieces.filter { it.volume > thinnest * it.surfaceArea }
    val result = Solid.union(listOf(this) + kept)
    pieces.forEach { it.close() }
    return result
}

/**
 * This part as a ball-end mill of [radius] would leave it: concave edges filleted, pocket corners
 * rounded, slots narrower than `2 * radius` filled in, convex edges kept sharp.
 *
 * ```kotlin
 * val part = Solid.cube(4.0, 1.0, 4.0) - Solid.cube(2.0, 0.5, 2.0).translated(1.0, 0.5, 1.0)
 *
 * val anyDirection = part.ballMilled(0.2)                            // the tool may come from anywhere
 * val fromTop = part.ballMilled(0.2, from = listOf(vec(0, 1, 0)))  // one 3-axis setup, spindle up +y
 * val flipped = part.ballMilled(0.2, from = listOf(vec(0, 1, 0), vec(0, -1, 0)))  // then turned over
 *
 * val fillets = fromTop - part        // just the material the mill leaves that the design does not have
 * ```
 *
 * With [from] empty, the ball may approach from any direction. That is the plain [closed] by a
 * sphere, an idealised five-axis machine. It assumes every pocket the ball fits into is also
 * reachable, so a sealed internal void stays open.
 *
 * Each vector in [from] is one **3-axis setup**: the tool axis, pointing from the part towards the
 * spindle. A setup models the shank as a cylinder as wide as the ball, running up and out of the part,
 * so anything the part overhangs, as seen from that side, stays solid. Each setup is computed as a
 * sphere closing of the part plus its shadow below it along that axis. That is exactly the closing by
 * ball-and-shank, and much cheaper. Material
 * any setup can remove is removed, so the result is the intersection of the setups. It is then
 * clipped to [stock], which defaults to this part's axis-aligned bounds. A single setup therefore
 * leaves the stock underneath the part, as a real one does until the part is turned over.
 *
 * The ball is Manifold's [Solid.sphere] with [segments] around, a polyhedron inscribed in the true
 * sphere. Fillets come out faceted and very slightly tighter than [radius], by at most
 * `radius * (1 - cos(π / segments))`, about 2% at the default of 16. Raising [segments] costs time
 * steeply: the Minkowski steps cost roughly one convex hull per boundary triangle. On a small stepped
 * plate that was about 2 s at 16 segments and 9 s at 32.
 *
 * @param radius the ball's radius, in the part's units.
 * @param from the tool axes of the 3-axis setups; empty for a ball that may approach from any direction.
 * @param segments segments around the ball's equator.
 * @param stock the blank the part is cut from, used only with [from]; defaults to the part's bounds.
 */
fun Solid.ballMilled(
    radius: Double,
    from: List<Vec3> = emptyList(),
    segments: Int = 16,
    stock: Solid? = null,
): Solid {
    require(radius.isFinite() && radius > 0.0) { "a ball mill needs a positive radius, got $radius" }
    require(segments >= 4) { "a ball needs at least 4 segments, got $segments" }
    if (isEmpty) return translated(0.0, 0.0, 0.0)
    val ball = Solid.sphere(radius, segments)
    try {
        if (from.isEmpty()) return closed(ball)

        // The part's bounds in double precision: [bounds] narrows to Float, which would leave a
        // stock face a fraction of a micron proud of the part.
        val positions = toPlanarMesh().positions
        val low = DoubleArray(3) { axis -> (axis until positions.size step 3).minOf { positions[it] } }
        val high = DoubleArray(3) { axis -> (axis until positions.size step 3).maxOf { positions[it] } }
        val blank = stock ?: Solid.cube(high[0] - low[0], high[1] - low[1], high[2] - low[2])
            .translated(low[0], low[1], low[2])
        // Long enough that the far end of the shadow, and anything the closing does there, lies
        // beyond the part by more than the ball's diameter on any axis.
        val reach = sqrt((0..2).sumOf { (high[it] - low[it]) * (high[it] - low[it]) }) + 4.0 * radius
        val setups = from.map { axis ->
            val shadowed = withShadow(axis, reach)
            val closing = shadowed.closed(ball)
            shadowed.close()
            closing
        }
        val removedByNone = if (setups.size == 1) setups[0] else Solid.batchBoolean(setups, BooleanOp.INTERSECTION)
        val reachable = removedByNone and blank
        val result = withAdded(reachable, thinnest = 0.01 * radius)
        reachable.close()
        if (removedByNone !== setups[0]) removedByNone.close()
        setups.forEach { it.close() }
        if (stock == null) blank.close()
        return result
    } finally {
        ball.close()
    }
}

/**
 * This solid together with everything beneath it along [axis], out to [reach]: what a tool coming
 * down `-axis` sees as solid.
 *
 * Built as the union of the solid with one prism per downward-facing boundary triangle, each swept
 * [reach] along `-axis`. A ray going down from any point of the solid leaves through one of those
 * triangles, so the prisms cover the whole shadow. Coordinates stay in double precision.
 */
internal fun Solid.withShadow(axis: Vec3, reach: Double): Solid {
    val ax = axis.x.toDouble()
    val ay = axis.y.toDouble()
    val az = axis.z.toDouble()
    val length = sqrt(ax * ax + ay * ay + az * az)
    require(length > 0.0 && length.isFinite()) { "a tool axis must be a non-zero vector, got $axis" }
    val dx = -ax / length * reach
    val dy = -ay / length * reach
    val dz = -az / length * reach
    val mesh = toPlanarMesh()
    val p = mesh.positions
    val prisms = ArrayList<Solid>()
    for (t in 0 until mesh.triangleCount) {
        val a = mesh.indices[t * 3] * 3
        val b = mesh.indices[t * 3 + 1] * 3
        val c = mesh.indices[t * 3 + 2] * 3
        val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
        val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        // Outward normal against the sweep direction: positive means the triangle faces down the axis.
        val facing = nx * dx + ny * dy + nz * dz
        val area2 = sqrt(nx * nx + ny * ny + nz * nz)
        if (area2 == 0.0 || facing <= 1e-12 * area2 * reach) continue
        val corners = DoubleArray(18)
        for ((k, v) in intArrayOf(a, b, c).withIndex()) {
            corners[k * 3] = p[v]; corners[k * 3 + 1] = p[v + 1]; corners[k * 3 + 2] = p[v + 2]
            corners[9 + k * 3] = p[v] + dx; corners[9 + k * 3 + 1] = p[v + 1] + dy; corners[9 + k * 3 + 2] = p[v + 2] + dz
        }
        prisms += Solid.hullOfPointsD(corners)
    }
    val result = Solid.union(listOf(this) + prisms)
    prisms.forEach { it.close() }
    return result
}
