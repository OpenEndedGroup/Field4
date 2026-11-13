@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.kinematics

import afield.geometry.mesh.TriMesh
import afield.geometry.solid.manifold.Solid
import afield.geometry.solid.manifold.SolidPlanarMesh
import afield.math.linalg.Matrix4dc
import afield.math.linalg.Vector3d
import afield.math.linalg.Vector3dc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A rigid part or an obstacle, as double-precision triangles with a bounding-volume tree: the input to
 * [sweptClearance] and [ClearanceBody.distanceTo].
 *
 * Build one from a Manifold [Solid] (exact: Manifold's doubles, not the float [TriMesh] export), from a [TriMesh],
 * from raw triangles, or as a [box]. Building the tree is the expensive part, so build obstacles once and reuse
 * them across every part and motion you check against them.
 *
 * Distances are between **surfaces**, with one exception that makes them gaps rather than surface distances: when
 * both bodies are closed and one lies wholly inside the other, the gap is 0. Two bodies that cross are at gap 0 too;
 * how deep they cross is not measured.
 */
class ClearanceBody private constructor(
    /** A label for reports: the obstacle a [ClearanceStep] names. */
    val name: String,
    internal val positions: DoubleArray,
    internal val indices: IntArray,
) {
    internal val tree = TriangleTree(positions, indices)

    /** Whether every edge is shared by exactly two triangles, so inside and outside mean something. */
    val isClosed: Boolean = closedSurface(positions, indices)

    /** Number of triangles. */
    val triangleCount: Int get() = indices.size / 3

    /** Number of vertices. */
    val vertexCount: Int get() = positions.size / 3

    /** The axis-aligned bounds, as `[minX, minY, minZ, maxX, maxY, maxZ]`. */
    val bounds: DoubleArray get() = tree.rootBounds()

    /** The vertices as flat `x, y, z` triples, in the body's own coordinates (a copy). */
    fun vertices(): DoubleArray = positions.copyOf()

    /** The same triangles under another name. */
    fun named(name: String): ClearanceBody = ClearanceBody(name, positions, indices)

    /**
     * The gap between this body at [pose] and [other] at [otherPose], with the nearest points.
     *
     * Poses map each body's own coordinates to world; the identity leaves a body where it was built.
     */
    fun distanceTo(other: ClearanceBody, pose: Matrix4dc? = null, otherPose: Matrix4dc? = null): Contact {
        val a = PosedBody(this); a.place(pose)
        val b = PosedBody(other); b.place(otherPose)
        return bodyDistance(a, b, Double.POSITIVE_INFINITY, 0)
            ?: error("no distance between empty bodies $name and ${other.name}")
    }

    override fun toString(): String = "ClearanceBody($name, $triangleCount triangles${if (isClosed) ", closed" else ", open"})"

    companion object {
        /** A body from a Manifold [Solid], in its full double precision. */
        fun of(solid: Solid, name: String = "solid"): ClearanceBody = of(solid.toPlanarMesh(), name)

        /** A body from a [SolidPlanarMesh] (Manifold's double-precision export). */
        fun of(mesh: SolidPlanarMesh, name: String = "solid"): ClearanceBody = ofTriangles(mesh.positions, mesh.indices, name)

        /** A body from a Field [TriMesh]; its float positions are widened exactly. */
        fun of(mesh: TriMesh, name: String = "mesh"): ClearanceBody =
            ofTriangles(DoubleArray(mesh.positions.size) { mesh.positions[it].toDouble() }, mesh.indices, name)

        /** A body from flat `x, y, z` positions and triangle indices (three per triangle). Both arrays are copied. */
        fun ofTriangles(positions: DoubleArray, indices: IntArray, name: String = "mesh"): ClearanceBody {
            require(positions.size % 3 == 0) { "positions must be xyz triples" }
            require(indices.size % 3 == 0) { "indices must be triangle triples" }
            require(indices.isNotEmpty()) { "a clearance body needs at least one triangle" }
            require(positions.all { it.isFinite() }) { "positions must be finite" }
            require(indices.all { it in 0 until positions.size / 3 }) { "index outside the vertex array" }
            return ClearanceBody(name, positions.copyOf(), indices.copyOf())
        }

        /** An axis-aligned box between two corners, as 12 triangles. */
        fun box(min: Vector3dc, max: Vector3dc, name: String = "box"): ClearanceBody {
            val x0 = min(min.x(), max.x()); val x1 = max(min.x(), max.x())
            val y0 = min(min.y(), max.y()); val y1 = max(min.y(), max.y())
            val z0 = min(min.z(), max.z()); val z1 = max(min.z(), max.z())
            val p = doubleArrayOf(
                x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0,
                x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
            )
            val t = intArrayOf(
                0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7,
                0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5,
                2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7,
            )
            return ClearanceBody(name, p, t)
        }

        /**
         * A vertical prism: the convex polygon [outline] (x, y, counter-clockwise or clockwise) extruded from [z0] to
         * [z1]. The common shape of a plan obstacle.
         */
        fun prism(outline: List<Pair<Double, Double>>, z0: Double, z1: Double, name: String = "prism"): ClearanceBody {
            require(outline.size >= 3) { "a prism needs at least three outline points" }
            val n = outline.size
            val p = DoubleArray(n * 6)
            for ((i, xy) in outline.withIndex()) {
                p[i * 3] = xy.first; p[i * 3 + 1] = xy.second; p[i * 3 + 2] = min(z0, z1)
                p[(n + i) * 3] = xy.first; p[(n + i) * 3 + 1] = xy.second; p[(n + i) * 3 + 2] = max(z0, z1)
            }
            val t = ArrayList<Int>()
            for (i in 1 until n - 1) { t += listOf(0, i + 1, i); t += listOf(n, n + i, n + i + 1) }
            for (i in 0 until n) { val j = (i + 1) % n; t += listOf(i, j, n + j); t += listOf(i, n + j, n + i) }
            return ClearanceBody(name, p, t.toIntArray())
        }

        private fun closedSurface(positions: DoubleArray, indices: IntArray): Boolean {
            // Weld by exact coordinates first: a mesh may duplicate vertices along creases.
            val ids = HashMap<Triple<Double, Double, Double>, Int>()
            val weld = IntArray(positions.size / 3) { v ->
                ids.getOrPut(Triple(positions[v * 3] + 0.0, positions[v * 3 + 1] + 0.0, positions[v * 3 + 2] + 0.0)) { ids.size }
            }
            val edges = HashMap<Long, Int>()
            for (t in 0 until indices.size / 3) for (k in 0..2) {
                val a = weld[indices[t * 3 + k]]; val b = weld[indices[t * 3 + (k + 1) % 3]]
                if (a == b) continue
                val key = (min(a, b).toLong() shl 32) or max(a, b).toLong()
                edges[key] = (edges[key] ?: 0) + 1
            }
            return edges.isNotEmpty() && edges.values.all { it == 2 }
        }
    }
}

/**
 * The nearest approach between two bodies: the [gap], the points on each that realise it, and which obstacle.
 *
 * [onPart] and [onObstacle] are in world coordinates. When the bodies cross or one contains the other, [gap] is 0,
 * [touching] is true and both points are one point where they meet (for containment, a vertex of the inner body).
 */
class Contact(
    /** The smallest distance between the two, 0 when they touch, cross or nest. */
    val gap: Double,
    /** The obstacle's [ClearanceBody.name]. */
    val obstacle: String,
    /** The obstacle's position in the list it was found in (0 for [ClearanceBody.distanceTo]). */
    val obstacleIndex: Int,
    /** The nearest point on the moving part, world coordinates. */
    val onPart: Vector3dc,
    /** The nearest point on the obstacle, world coordinates. */
    val onObstacle: Vector3dc,
    /** True when the two meet: crossing surfaces, or one wholly inside the other. */
    val touching: Boolean,
) {
    /** The gap, the obstacle and the two points, for `println`. */
    override fun toString(): String = "Contact(%.6g to $obstacle, part $onPart, obstacle $onObstacle${if (touching) ", touching" else ""})".format(gap)
}

/** A body's vertices carried to a pose, with its tree's boxes refitted around them. */
internal class PosedBody(val body: ClearanceBody) {
    val world = DoubleArray(body.positions.size)
    val boxes = DoubleArray(body.tree.nodeCount * 6)
    var identity = true

    fun place(pose: Matrix4dc?) {
        val p = body.positions
        identity = pose == null
        if (pose == null) {
            p.copyInto(world)
        } else {
            val m00 = pose.m00(); val m01 = pose.m01(); val m02 = pose.m02()
            val m10 = pose.m10(); val m11 = pose.m11(); val m12 = pose.m12()
            val m20 = pose.m20(); val m21 = pose.m21(); val m22 = pose.m22()
            val m30 = pose.m30(); val m31 = pose.m31(); val m32 = pose.m32()
            var i = 0
            while (i < p.size) {
                val x = p[i]; val y = p[i + 1]; val z = p[i + 2]
                world[i] = m00 * x + m10 * y + m20 * z + m30
                world[i + 1] = m01 * x + m11 * y + m21 * z + m31
                world[i + 2] = m02 * x + m12 * y + m22 * z + m32
                i += 3
            }
        }
        body.tree.refit(world, boxes)
    }

    fun minX() = boxes[0]; fun minY() = boxes[1]; fun minZ() = boxes[2]
    fun maxX() = boxes[3]; fun maxY() = boxes[4]; fun maxZ() = boxes[5]
}

/** Squared distance between box [i] of [a] and box [j] of [b] (flat min/max sextets). */
internal fun boxDistance2(a: DoubleArray, i: Int, b: DoubleArray, j: Int): Double {
    var d = 0.0
    for (k in 0..2) {
        val gap = max(a[i * 6 + k] - b[j * 6 + 3 + k], b[j * 6 + k] - a[i * 6 + 3 + k])
        if (gap > 0) d += gap * gap
    }
    return d
}

/**
 * The nearest approach between the posed bodies [a] (the part) and [b] (an obstacle), or `null` when nothing is
 * closer than [bound]. Crossing or nesting closed bodies give gap 0.
 */
internal fun bodyDistance(a: PosedBody, b: PosedBody, bound: Double, index: Int): Contact? {
    val ta = a.body.tree; val tb = b.body.tree
    val q = TriangleQuery()
    var best2 = if (bound.isInfinite()) Double.POSITIVE_INFINITY else bound * bound
    var found = false
    val pa = DoubleArray(3); val pb = DoubleArray(3)
    var stackA = IntArray(64); var stackB = IntArray(64)
    var sp = 0
    stackA[sp] = 0; stackB[sp] = 0; sp++
    while (sp > 0) {
        sp--
        val na = stackA[sp]; val nb = stackB[sp]
        if (boxDistance2(a.boxes, na, b.boxes, nb) >= best2) continue
        val leafA = ta.isLeaf(na); val leafB = tb.isLeaf(nb)
        if (leafA && leafB) {
            for (i in ta.first(na) until ta.first(na) + ta.count(na)) {
                val s = ta.order[i]
                for (j in tb.first(nb) until tb.first(nb) + tb.count(nb)) {
                    val t = tb.order[j]
                    val d2 = q.distance2(a.world, a.body.indices, s, b.world, b.body.indices, t)
                    if (d2 < best2) {
                        best2 = d2; found = true
                        q.pointA.copyInto(pa); q.pointB.copyInto(pb)
                        if (d2 == 0.0) return Contact(0.0, b.body.name, index, v(pa), v(pb), true)
                    }
                }
            }
            continue
        }
        if (sp + 2 > stackA.size) { stackA = stackA.copyOf(stackA.size * 2); stackB = stackB.copyOf(stackB.size * 2) }
        // Split the bigger box (or whichever is not a leaf), pushing the nearer child last so it is visited first.
        val splitA = !leafA && (leafB || ta.extent(a.boxes, na) >= tb.extent(b.boxes, nb))
        if (splitA) {
            val l = ta.left(na); val r = ta.right(na)
            val nearL = boxDistance2(a.boxes, l, b.boxes, nb) <= boxDistance2(a.boxes, r, b.boxes, nb)
            stackA[sp] = if (nearL) r else l; stackB[sp] = nb; sp++
            stackA[sp] = if (nearL) l else r; stackB[sp] = nb; sp++
        } else {
            val l = tb.left(nb); val r = tb.right(nb)
            val nearL = boxDistance2(a.boxes, na, b.boxes, l) <= boxDistance2(a.boxes, na, b.boxes, r)
            stackA[sp] = na; stackB[sp] = if (nearL) r else l; sp++
            stackA[sp] = na; stackB[sp] = if (nearL) l else r; sp++
        }
    }
    // Nesting: no surfaces meet, but one closed body may hold the other (and then its box holds the other's box).
    if (a.body.isClosed && b.body.isClosed) {
        if (boxWithin(a.boxes, b.boxes) && b.body.tree.containsPoint(b.world, a.world[0], a.world[1], a.world[2])) {
            val p = Vector3d(a.world[0], a.world[1], a.world[2])
            return Contact(0.0, b.body.name, index, p, Vector3d(p), true)
        }
        if (boxWithin(b.boxes, a.boxes) && a.body.tree.containsPoint(a.world, b.world[0], b.world[1], b.world[2])) {
            val p = Vector3d(b.world[0], b.world[1], b.world[2])
            return Contact(0.0, b.body.name, index, Vector3d(p), p, true)
        }
    }
    if (!found) return null
    return Contact(sqrt(best2), b.body.name, index, v(pa), v(pb), false)
}

/** Is root box [inner] inside root box [outer]? */
private fun boxWithin(inner: DoubleArray, outer: DoubleArray): Boolean =
    inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] >= outer[2] && inner[3] <= outer[3] && inner[4] <= outer[4] && inner[5] <= outer[5]

private fun v(p: DoubleArray) = Vector3d(p[0], p[1], p[2])
