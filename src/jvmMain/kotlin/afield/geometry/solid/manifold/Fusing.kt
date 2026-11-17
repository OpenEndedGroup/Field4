@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.solid.manifold

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Joining the pieces of a solid that touch face to face, which a union leaves apart.
 *
 * Manifold keeps two pieces that only touch, with no volume in common, as separate components of one
 * solid. A union of cells that share faces, each computed on its own, can therefore come back as
 * several pieces with zero-width cracks between them: two faces back to back, with material on both
 * sides. [fused] fills those cracks, so the result is one piece wherever the material is continuous.
 */

/**
 * This solid with every crack between pieces that touch face to face filled, so they become one.
 *
 * A crack is where two faces lie back to back on one plane, facing opposite ways, closer together
 * than a billionth of the solid's size, with the solid's material behind each of them. Each region
 * where two such faces overlap is filled by a slab reaching 1e-7 of the solid's size into the
 * material on either side, which stops exactly where the overlap does. The solid
 * does not otherwise change: no face moves, and pieces that touch only along an edge or at a point
 * stay apart, since there is nothing between them to fill.
 *
 * ```kotlin
 * val left = Solid.cube(1.0, 0.1, 1.0)
 * val right = Solid.cube(1.0, 0.1, 1.0).translated(1.0 + 1e-12, 0.0, 0.0)
 * Solid.union(left, right).decompose().size          // 2: they only touch
 * Solid.union(left, right).fused().decompose().size  // 1
 * ```
 *
 * [insetFaces] fuses first, so the walls of a crack are not taken for faces to move.
 */
fun Solid.fused(): Solid {
    if (isEmpty) return translated(0.0, 0.0, 0.0)
    val mesh = toPlanarMesh()
    val p = mesh.positions
    val tri = mesh.indices
    val triangles = mesh.triangleCount
    var extent = 0.0
    for (axis in 0 until 3) {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (v in 0 until mesh.vertexCount) { lo = minOf(lo, p[v * 3 + axis]); hi = maxOf(hi, p[v * 3 + axis]) }
        extent = max(extent, hi - lo)
    }
    val touching = 1e-9 * extent
    val half = 1e-7 * extent
    val sameNormal = 1.0 - 1e-9

    // Each triangle's unit normal and offset, and its bounds along x for a sweep.
    val normal = DoubleArray(triangles * 3)
    val offset = DoubleArray(triangles)
    val minX = DoubleArray(triangles)
    val maxX = DoubleArray(triangles)
    val usable = BooleanArray(triangles)
    for (t in 0 until triangles) {
        val a = tri[t * 3] * 3; val b = tri[t * 3 + 1] * 3; val c = tri[t * 3 + 2] * 3
        val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
        val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length == 0.0) continue
        usable[t] = true
        normal[t * 3] = nx / length; normal[t * 3 + 1] = ny / length; normal[t * 3 + 2] = nz / length
        offset[t] = (nx * p[a] + ny * p[a + 1] + nz * p[a + 2]) / length
        minX[t] = minOf(p[a], p[b], p[c]); maxX[t] = maxOf(p[a], p[b], p[c])
    }
    val order = (0 until triangles).filter { usable[it] }.sortedBy { minX[it] }

    // A slab over each overlap, as thick on one side of the plane as the other. Thinner than about
    // 1e-7 of its own size, Manifold's hull takes it for flat; a sliver too thin for that is left.
    val slabs = ArrayList<Solid>()
    for ((i, t) in order.withIndex()) {
        for (j in i + 1 until order.size) {
            val u = order[j]
            if (minX[u] > maxX[t] + touching) break
            val cosine = normal[t * 3] * normal[u * 3] + normal[t * 3 + 1] * normal[u * 3 + 1] + normal[t * 3 + 2] * normal[u * 3 + 2]
            if (cosine > -sameNormal || abs(offset[t] + offset[u]) > touching) continue
            val overlap = overlapOnPlane(p, tri, normal, t, u) ?: continue
            val area = polygonArea(overlap)
            // the overlap must have an area worth filling, not just a shared edge
            if (area <= touching * touching) continue
            val corner = tri[t * 3] * 3
            val e = basis(normal, t)
            val points = DoubleArray(overlap.size * 6)
            for ((k, q) in overlap.withIndex()) {
                for (axis in 0 until 3) {
                    val onPlane = p[corner + axis] + q.first * e[axis] + q.second * e[3 + axis]
                    points[k * 6 + axis] = onPlane + half * normal[t * 3 + axis]
                    points[k * 6 + 3 + axis] = onPlane - half * normal[t * 3 + axis]
                }
            }
            val slab = Solid.hullOfPointsD(points)
            if (slab.isEmpty || slab.volume < area * half) slab.close() else slabs += slab
        }
    }
    if (slabs.isEmpty()) return translated(0.0, 0.0, 0.0)
    val result = Solid.union([this] + slabs)
    slabs.forEach { it.close() }
    return result
}

/** Two unit vectors spanning triangle [t]'s plane, as `[e1x, e1y, e1z, e2x, e2y, e2z]`, with e1 x e2 along its normal. */
private fun basis(normal: DoubleArray, t: Int): DoubleArray {
    val nx = normal[t * 3]; val ny = normal[t * 3 + 1]; val nz = normal[t * 3 + 2]
    // the axis least along the normal, crossed with it
    val (ax, ay, az) = if (abs(nx) <= abs(ny) && abs(nx) <= abs(nz)) Triple(1.0, 0.0, 0.0)
    else if (abs(ny) <= abs(nz)) Triple(0.0, 1.0, 0.0) else Triple(0.0, 0.0, 1.0)
    var e1x = ay * nz - az * ny; var e1y = az * nx - ax * nz; var e1z = ax * ny - ay * nx
    val length = sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
    e1x /= length; e1y /= length; e1z /= length
    val e2x = ny * e1z - nz * e1y; val e2y = nz * e1x - nx * e1z; val e2z = nx * e1y - ny * e1x
    return doubleArrayOf(e1x, e1y, e1z, e2x, e2y, e2z)
}

/**
 * Where triangles [t] and [u] overlap, as a convex polygon in coordinates on [t]'s plane about [t]'s
 * first corner, counter-clockwise; `null` when they do not.
 */
private fun overlapOnPlane(p: DoubleArray, tri: IntArray, normal: DoubleArray, t: Int, u: Int): List<Pair<Double, Double>>? {
    val e = basis(normal, t)
    val origin = tri[t * 3] * 3
    fun project(triangle: Int): List<Pair<Double, Double>> {
        val corners = (0 until 3).map { k ->
            val v = tri[triangle * 3 + k] * 3
            val dx = p[v] - p[origin]; val dy = p[v + 1] - p[origin + 1]; val dz = p[v + 2] - p[origin + 2]
            (dx * e[0] + dy * e[1] + dz * e[2]) to (dx * e[3] + dy * e[4] + dz * e[5])
        }
        return if (polygonArea(corners) < 0) corners.reversed() else corners
    }
    var polygon = project(t)
    val clip = project(u)
    for (k in clip.indices) {
        val (ax, ay) = clip[k]
        val (bx, by) = clip[(k + 1) % clip.size]
        fun side(q: Pair<Double, Double>) = (bx - ax) * (q.second - ay) - (by - ay) * (q.first - ax)
        val next = ArrayList<Pair<Double, Double>>(polygon.size + 1)
        for (m in polygon.indices) {
            val q = polygon[m]
            val r = polygon[(m + 1) % polygon.size]
            val sq = side(q); val sr = side(r)
            if (sq >= 0) next += q
            if ((sq >= 0) != (sr >= 0)) {
                val f = sq / (sq - sr)
                next += (q.first + f * (r.first - q.first)) to (q.second + f * (r.second - q.second))
            }
        }
        if (next.size < 3) return null
        polygon = next
    }
    return polygon
}

/** Signed area of a polygon, positive when counter-clockwise. */
private fun polygonArea(polygon: List<Pair<Double, Double>>): Double {
    var twice = 0.0
    for (k in polygon.indices) {
        val (ax, ay) = polygon[k]
        val (bx, by) = polygon[(k + 1) % polygon.size]
        twice += ax * by - bx * ay
    }
    return twice / 2
}
