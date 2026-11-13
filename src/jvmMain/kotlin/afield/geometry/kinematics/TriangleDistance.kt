package afield.geometry.kinematics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The double-precision triangle machinery under [ClearanceBody]: an axis-aligned box tree over a body's triangles
 * ([TriangleTree]) and the exact distance between two triangles ([TriangleQuery]).
 *
 * The tree's topology is built once in the body's own coordinates; for a moving body only its boxes are refitted
 * around the posed vertices, which keeps them tight under rotation.
 */

/** A median-split box tree over triangles; boxes live outside it, so one topology serves every pose. */
internal class TriangleTree(private val positions: DoubleArray, private val indices: IntArray, private val leafSize: Int = 4) {
    val order = IntArray(indices.size / 3) { it }
    private var left = IntArray(16)
    private var right = IntArray(16)
    var nodeCount = 0
        private set

    init {
        val n = order.size
        val c = DoubleArray(n * 3)
        for (t in 0 until n) for (k in 0..2) {
            c[t * 3 + k] = (positions[indices[t * 3] * 3 + k] + positions[indices[t * 3 + 1] * 3 + k] + positions[indices[t * 3 + 2] * 3 + k]) / 3
        }
        if (n > 0) build(0, n, c)
    }

    private fun newNode(): Int {
        if (nodeCount == left.size) { left = left.copyOf(left.size * 2); right = right.copyOf(right.size * 2) }
        return nodeCount++
    }

    private fun build(from: Int, to: Int, c: DoubleArray): Int {
        val node = newNode()
        val count = to - from
        if (count <= leafSize) { left[node] = -from - 1; right[node] = count; return node }
        val lo = DoubleArray(3) { Double.POSITIVE_INFINITY }; val hi = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        for (i in from until to) for (k in 0..2) { val x = c[order[i] * 3 + k]; lo[k] = min(lo[k], x); hi[k] = max(hi[k], x) }
        val axis = (0..2).maxByOrNull { hi[it] - lo[it] } ?: 0
        val slice = (from until to).map { order[it] }.sortedBy { c[it * 3 + axis] }
        for ((i, t) in slice.withIndex()) order[from + i] = t
        val mid = from + count / 2
        val l = build(from, mid, c)
        val r = build(mid, to, c)
        left[node] = l; right[node] = r
        return node
    }

    fun isLeaf(node: Int) = left[node] < 0
    fun first(node: Int) = -left[node] - 1
    fun count(node: Int) = right[node]
    fun left(node: Int) = left[node]
    fun right(node: Int) = right[node]

    /** The largest side of box [node] in [boxes]: which of two boxes to split first. */
    fun extent(boxes: DoubleArray, node: Int): Double =
        max(boxes[node * 6 + 3] - boxes[node * 6], max(boxes[node * 6 + 4] - boxes[node * 6 + 1], boxes[node * 6 + 5] - boxes[node * 6 + 2]))

    /** Recompute every node's box around the vertices [world] into [boxes] (six doubles per node). */
    fun refit(world: DoubleArray, boxes: DoubleArray) {
        // Children are always created after their parent, so a reverse sweep sees children first.
        for (node in nodeCount - 1 downTo 0) {
            val o = node * 6
            if (isLeaf(node)) {
                var x0 = Double.POSITIVE_INFINITY; var y0 = x0; var z0 = x0
                var x1 = Double.NEGATIVE_INFINITY; var y1 = x1; var z1 = x1
                for (i in first(node) until first(node) + count(node)) {
                    val t = order[i]
                    for (k in 0..2) {
                        val v = indices[t * 3 + k] * 3
                        x0 = min(x0, world[v]); x1 = max(x1, world[v])
                        y0 = min(y0, world[v + 1]); y1 = max(y1, world[v + 1])
                        z0 = min(z0, world[v + 2]); z1 = max(z1, world[v + 2])
                    }
                }
                boxes[o] = x0; boxes[o + 1] = y0; boxes[o + 2] = z0; boxes[o + 3] = x1; boxes[o + 4] = y1; boxes[o + 5] = z1
            } else {
                val a = left[node] * 6; val b = right[node] * 6
                for (k in 0..2) { boxes[o + k] = min(boxes[a + k], boxes[b + k]); boxes[o + 3 + k] = max(boxes[a + 3 + k], boxes[b + 3 + k]) }
            }
        }
    }

    /** Bounds of the tree at its own coordinates. */
    fun rootBounds(): DoubleArray = DoubleArray(nodeCount * 6).also { refit(positions, it) }.copyOf(6)

    /**
     * Is the point inside the closed surface whose posed vertices are [world]? By the parity of crossings along
     * three skew rays, majority vote, so a ray that grazes an edge is outvoted.
     */
    fun containsPoint(world: DoubleArray, x: Double, y: Double, z: Double): Boolean {
        var inside = 0
        for (d in RAYS) if (crossings(world, x, y, z, d[0], d[1], d[2]) % 2 == 1) inside++
        return inside >= 2
    }

    private fun crossings(world: DoubleArray, ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Int {
        var hits = 0
        for (t in 0 until indices.size / 3) {
            val a = indices[t * 3] * 3; val b = indices[t * 3 + 1] * 3; val c = indices[t * 3 + 2] * 3
            val e1x = world[b] - world[a]; val e1y = world[b + 1] - world[a + 1]; val e1z = world[b + 2] - world[a + 2]
            val e2x = world[c] - world[a]; val e2y = world[c + 1] - world[a + 1]; val e2z = world[c + 2] - world[a + 2]
            val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-300) continue
            val inv = 1.0 / det
            val tx = ox - world[a]; val ty = oy - world[a + 1]; val tz = oz - world[a + 2]
            val u = (tx * px + ty * py + tz * pz) * inv
            if (u < 0.0 || u > 1.0) continue
            val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
            val w = (dx * qx + dy * qy + dz * qz) * inv
            if (w < 0.0 || u + w > 1.0) continue
            if ((e2x * qx + e2y * qy + e2z * qz) * inv > 0.0) hits++
        }
        return hits
    }

    private companion object {
        val RAYS = arrayOf(
            doubleArrayOf(0.5773, 0.5774, 0.5775),
            doubleArrayOf(-0.2673, 0.8018, 0.5345),
            doubleArrayOf(0.7071, -0.1, -0.7),
        )
    }
}

/**
 * Exact closest points between two triangles, in doubles. Reusable scratch: not thread-safe, cheap to make.
 *
 * Two triangles that do not cross are nearest at a vertex of one against the face of the other, or at an edge of
 * one against an edge of the other; so the distance is the least of six point–triangle and nine segment–segment
 * distances, after a test for an edge of either piercing the other (which makes it 0).
 */
internal class TriangleQuery {
    val pointA = DoubleArray(3)
    val pointB = DoubleArray(3)
    private val p = DoubleArray(9)
    private val q = DoubleArray(9)
    private val c1 = DoubleArray(3)
    private val c2 = DoubleArray(3)

    /** Squared distance between triangle [s] of ([pa], [ia]) and triangle [t] of ([pb], [ib]); points in [pointA], [pointB]. */
    fun distance2(pa: DoubleArray, ia: IntArray, s: Int, pb: DoubleArray, ib: IntArray, t: Int): Double {
        for (k in 0..2) {
            val va = ia[s * 3 + k] * 3; val vb = ib[t * 3 + k] * 3
            p[k * 3] = pa[va]; p[k * 3 + 1] = pa[va + 1]; p[k * 3 + 2] = pa[va + 2]
            q[k * 3] = pb[vb]; q[k * 3 + 1] = pb[vb + 1]; q[k * 3 + 2] = pb[vb + 2]
        }
        return distance2(p, q)
    }

    /** Squared distance between the triangles [p] and [q], nine doubles each. */
    fun distance2(p: DoubleArray, q: DoubleArray): Double {
        // An edge of one through the face of the other: they cross.
        for (k in 0..2) {
            if (segmentHitsTriangle(p, k, (k + 1) % 3, q, pointA)) { pointA.copyInto(pointB); return 0.0 }
            if (segmentHitsTriangle(q, k, (k + 1) % 3, p, pointA)) { pointA.copyInto(pointB); return 0.0 }
        }
        var best = Double.POSITIVE_INFINITY
        for (i in 0..2) for (j in 0..2) {
            val d = segmentSegment(p, i, (i + 1) % 3, q, j, (j + 1) % 3, c1, c2)
            if (d < best) { best = d; c1.copyInto(pointA); c2.copyInto(pointB) }
        }
        for (k in 0..2) {
            val d = pointTriangle(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], q, c2)
            if (d < best) { best = d; pointA[0] = p[k * 3]; pointA[1] = p[k * 3 + 1]; pointA[2] = p[k * 3 + 2]; c2.copyInto(pointB) }
            val e = pointTriangle(q[k * 3], q[k * 3 + 1], q[k * 3 + 2], p, c1)
            if (e < best) { best = e; pointB[0] = q[k * 3]; pointB[1] = q[k * 3 + 1]; pointB[2] = q[k * 3 + 2]; c1.copyInto(pointA) }
        }
        return best
    }

    /** Does the segment from vertex [i] to vertex [j] of [s] cross the triangle [t]? If so, where, into [out]. */
    private fun segmentHitsTriangle(s: DoubleArray, i: Int, j: Int, t: DoubleArray, out: DoubleArray): Boolean {
        val ox = s[i * 3]; val oy = s[i * 3 + 1]; val oz = s[i * 3 + 2]
        val dx = s[j * 3] - ox; val dy = s[j * 3 + 1] - oy; val dz = s[j * 3 + 2] - oz
        val e1x = t[3] - t[0]; val e1y = t[4] - t[1]; val e1z = t[5] - t[2]
        val e2x = t[6] - t[0]; val e2y = t[7] - t[1]; val e2z = t[8] - t[2]
        val px = dy * e2z - dz * e2y; val py = dz * e2x - dx * e2z; val pz = dx * e2y - dy * e2x
        val det = e1x * px + e1y * py + e1z * pz
        // Parallel (or degenerate): a coplanar overlap is caught by the edge and vertex distances instead.
        val scale = (abs(dx) + abs(dy) + abs(dz)) * (abs(e1x) + abs(e1y) + abs(e1z)) * (abs(e2x) + abs(e2y) + abs(e2z))
        if (abs(det) <= 1e-14 * scale) return false
        val inv = 1.0 / det
        val tx = ox - t[0]; val ty = oy - t[1]; val tz = oz - t[2]
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < 0.0 || u > 1.0) return false
        val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
        val v = (dx * qx + dy * qy + dz * qz) * inv
        if (v < 0.0 || u + v > 1.0) return false
        val h = (e2x * qx + e2y * qy + e2z * qz) * inv
        if (h < 0.0 || h > 1.0) return false
        out[0] = ox + dx * h; out[1] = oy + dy * h; out[2] = oz + dz * h
        return true
    }

    companion object {
        /**
         * Squared distance between segments `a[i]a[j]` and `b[k]b[l]` (vertices of flat triples), closest points into
         * [ca] and [cb]. Ericson, *Real-Time Collision Detection*, 5.1.9.
         */
        fun segmentSegment(a: DoubleArray, i: Int, j: Int, b: DoubleArray, k: Int, l: Int, ca: DoubleArray, cb: DoubleArray): Double {
            val p1x = a[i * 3]; val p1y = a[i * 3 + 1]; val p1z = a[i * 3 + 2]
            val p2x = b[k * 3]; val p2y = b[k * 3 + 1]; val p2z = b[k * 3 + 2]
            val d1x = a[j * 3] - p1x; val d1y = a[j * 3 + 1] - p1y; val d1z = a[j * 3 + 2] - p1z
            val d2x = b[l * 3] - p2x; val d2y = b[l * 3 + 1] - p2y; val d2z = b[l * 3 + 2] - p2z
            val rx = p1x - p2x; val ry = p1y - p2y; val rz = p1z - p2z
            val aa = d1x * d1x + d1y * d1y + d1z * d1z
            val ee = d2x * d2x + d2y * d2y + d2z * d2z
            val ff = d2x * rx + d2y * ry + d2z * rz
            var s: Double; var t: Double
            val eps = 1e-300
            if (aa <= eps && ee <= eps) {
                s = 0.0; t = 0.0
            } else if (aa <= eps) {
                s = 0.0; t = (ff / ee).coerceIn(0.0, 1.0)
            } else {
                val cc = d1x * rx + d1y * ry + d1z * rz
                if (ee <= eps) {
                    t = 0.0; s = (-cc / aa).coerceIn(0.0, 1.0)
                } else {
                    val bb = d1x * d2x + d1y * d2y + d1z * d2z
                    val denom = aa * ee - bb * bb
                    s = if (denom > 1e-14 * aa * ee) ((bb * ff - cc * ee) / denom).coerceIn(0.0, 1.0) else 0.0
                    t = (bb * s + ff) / ee
                    if (t < 0.0) { t = 0.0; s = (-cc / aa).coerceIn(0.0, 1.0) }
                    else if (t > 1.0) { t = 1.0; s = ((bb - cc) / aa).coerceIn(0.0, 1.0) }
                }
            }
            ca[0] = p1x + d1x * s; ca[1] = p1y + d1y * s; ca[2] = p1z + d1z * s
            cb[0] = p2x + d2x * t; cb[1] = p2y + d2y * t; cb[2] = p2z + d2z * t
            val x = ca[0] - cb[0]; val y = ca[1] - cb[1]; val z = ca[2] - cb[2]
            return x * x + y * y + z * z
        }

        /** Squared distance from the point to triangle [t] (nine doubles), closest point into [out]. Ericson 5.1.5. */
        fun pointTriangle(px: Double, py: Double, pz: Double, t: DoubleArray, out: DoubleArray): Double {
            val ax = t[0]; val ay = t[1]; val az = t[2]
            val abx = t[3] - ax; val aby = t[4] - ay; val abz = t[5] - az
            val acx = t[6] - ax; val acy = t[7] - ay; val acz = t[8] - az
            val apx = px - ax; val apy = py - ay; val apz = pz - az
            val d1 = abx * apx + aby * apy + abz * apz
            val d2 = acx * apx + acy * apy + acz * apz
            fun set(x: Double, y: Double, z: Double): Double {
                out[0] = x; out[1] = y; out[2] = z
                val dx = px - x; val dy = py - y; val dz = pz - z
                return dx * dx + dy * dy + dz * dz
            }
            if (d1 <= 0.0 && d2 <= 0.0) return set(ax, ay, az)
            val bpx = px - t[3]; val bpy = py - t[4]; val bpz = pz - t[5]
            val d3 = abx * bpx + aby * bpy + abz * bpz
            val d4 = acx * bpx + acy * bpy + acz * bpz
            if (d3 >= 0.0 && d4 <= d3) return set(t[3], t[4], t[5])
            val vc = d1 * d4 - d3 * d2
            if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) {
                val v = d1 / (d1 - d3)
                return set(ax + abx * v, ay + aby * v, az + abz * v)
            }
            val cpx = px - t[6]; val cpy = py - t[7]; val cpz = pz - t[8]
            val d5 = abx * cpx + aby * cpy + abz * cpz
            val d6 = acx * cpx + acy * cpy + acz * cpz
            if (d6 >= 0.0 && d5 <= d6) return set(t[6], t[7], t[8])
            val vb = d5 * d2 - d1 * d6
            if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) {
                val w = d2 / (d2 - d6)
                return set(ax + acx * w, ay + acy * w, az + acz * w)
            }
            val va = d3 * d6 - d5 * d4
            if (va <= 0.0 && d4 - d3 >= 0.0 && d5 - d6 >= 0.0) {
                val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
                return set(t[3] + (t[6] - t[3]) * w, t[4] + (t[7] - t[4]) * w, t[5] + (t[8] - t[5]) * w)
            }
            val denom = va + vb + vc
            if (abs(denom) < 1e-300) return set(ax, ay, az)
            val v = vb / denom; val w = vc / denom
            return set(ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w)
        }
    }
}
