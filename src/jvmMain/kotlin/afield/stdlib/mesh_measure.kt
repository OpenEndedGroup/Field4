@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Asking a [TriMesh] questions: how big is it, where is its middle, how curved is it — and, through
 * [MeshBvh], what does this ray hit, what is the nearest point on the surface, and is this point
 * inside.
 *
 * The measurements ([TriMesh.surfaceArea], [TriMesh.signedVolume], [TriMesh.centroid]) are exact for
 * the polyhedron, computed in double precision, and cheap. [TriMesh.signedVolume] is the one to keep
 * in mind: it is the divergence-theorem tetrahedron sum, so it is exact for any closed mesh, it is
 * **positive for outward winding and negative for inward**, and comparing it against an analytic
 * answer (`4/3 π` for a marched unit sphere) is the single most informative test a geometry pipeline
 * can have.
 *
 * The curvatures are the standard discrete operators — angle deficit for Gaussian, the
 * cotangent-Laplacian's magnitude for mean, both divided by the mixed Voronoi area. They satisfy
 * Gauss-Bonnet (`Σ K_v A_v = 2π χ`) to float precision on a closed mesh, which is how they are tested.
 *
 * [MeshBvh] exists because the useful queries are all `O(n)` without it and `O(log n)` with it. It is
 * a plain median-split bounding-volume hierarchy over triangles, built once, then:
 *
 * ```kotlin
 * val bvh = mesh.bvh()
 * bvh.raycast(vec(0, 0, -5), vec(0, 0, 1))?.let { println("hit at ${it.point}") }
 * bvh.closestPoint(vec(2, 0, 0))                 // nearest surface point + distance
 * bvh.contains(vec(0, 0, 0))                     // inside test, by parity of crossings
 * bvh.signedDistance(vec(0.5f, 0, 0))            // negative inside
 * ```
 */

// ==============================================================================================
// Measurement
// ==============================================================================================

/** Total area of every triangle, in double precision. */
fun TriMesh.surfaceArea(): Double {
    var sum = 0.0
    for (t in 0 until triangleCount) sum += triangleArea(t)
    return sum
}

/**
 * Volume enclosed, as the signed sum of the tetrahedra from the origin to each triangle.
 *
 * Exact for any **closed** mesh, and independent of where the origin sits (the out-of-surface parts
 * cancel). On an open mesh it is meaningless — the number depends on the origin — so check
 * [TriMesh.isWatertight] first if you did not build the mesh yourself.
 *
 * Positive for the outward (counter-clockwise-from-outside) winding this family uses, negative for a
 * mesh that is inside-out — which makes it the cheapest orientation check there is, and what
 * [TriMesh.fixWinding] uses.
 */
fun TriMesh.signedVolume(): Double {
    var sum = 0.0
    for (t in 0 until triangleCount) {
        val a = ia(t) * 3; val b = ib(t) * 3; val c = ic(t) * 3
        val ax = positions[a].toDouble(); val ay = positions[a + 1].toDouble(); val az = positions[a + 2].toDouble()
        val bx = positions[b].toDouble(); val by = positions[b + 1].toDouble(); val bz = positions[b + 2].toDouble()
        val cx = positions[c].toDouble(); val cy = positions[c + 1].toDouble(); val cz = positions[c + 2].toDouble()
        sum += (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6.0
    }
    return sum
}

/** [signedVolume]'s magnitude — the volume whichever way the mesh is wound. */
fun TriMesh.volume(): Double = abs(signedVolume())

/**
 * The **area-weighted centroid of the surface** — where a hollow shell of this shape would balance.
 *
 * Not the same as the centroid of the enclosed solid ([solidCentroid]) unless the shape is
 * symmetrical, and not the same as the mean of the vertices (which is biased toward wherever the mesh
 * happens to be finely triangulated).
 */
fun TriMesh.centroid(): Vec3 {
    var sx = 0.0; var sy = 0.0; var sz = 0.0; var total = 0.0
    for (t in 0 until triangleCount) {
        val area = triangleArea(t)
        if (area <= 0.0) continue
        val (a, b, c) = triangle(t)
        sx += area * (a.x + b.x + c.x) / 3.0
        sy += area * (a.y + b.y + c.y) / 3.0
        sz += area * (a.z + b.z + c.z) / 3.0
        total += area
    }
    if (total <= 0.0) return Vec3(0f, 0f, 0f)
    return Vec3((sx / total).toFloat(), (sy / total).toFloat(), (sz / total).toFloat())
}

/**
 * The centre of mass of the **enclosed solid**, assuming uniform density — the volume-weighted sum of
 * the same tetrahedra [signedVolume] uses.
 *
 * Only meaningful for a closed mesh. Returns the origin for a mesh with zero enclosed volume.
 */
fun TriMesh.solidCentroid(): Vec3 {
    var sx = 0.0; var sy = 0.0; var sz = 0.0; var total = 0.0
    for (t in 0 until triangleCount) {
        val (a, b, c) = triangle(t)
        val vol = (
            a.x.toDouble() * (b.y.toDouble() * c.z - b.z.toDouble() * c.y) -
                a.y.toDouble() * (b.x.toDouble() * c.z - b.z.toDouble() * c.x) +
                a.z.toDouble() * (b.x.toDouble() * c.y - b.y.toDouble() * c.x)
            ) / 6.0
        // A tetrahedron's centroid is the mean of its four corners, one of which is the origin.
        sx += vol * (a.x + b.x + c.x) / 4.0
        sy += vol * (a.y + b.y + c.y) / 4.0
        sz += vol * (a.z + b.z + c.z) / 4.0
        total += vol
    }
    if (abs(total) < 1e-18) return Vec3(0f, 0f, 0f)
    return Vec3((sx / total).toFloat(), (sy / total).toFloat(), (sz / total).toFloat())
}

/**
 * Per-vertex **mixed Voronoi area** — the area each vertex "owns", the denominator both curvatures
 * need.
 *
 * Meyer et al.'s construction: the true Voronoi cell area in a non-obtuse triangle, and a
 * barycentric fallback in an obtuse one (where the circumcentre falls outside and the Voronoi area
 * would go negative). Sums to the mesh's total [surfaceArea].
 */
fun TriMesh.mixedVertexAreas(): DoubleArray {
    val out = DoubleArray(vertexCount)
    for (t in 0 until triangleCount) {
        val i = ia(t); val j = ib(t); val k = ic(t)
        val pi = position(i); val pj = position(j); val pk = position(k)
        val area = triangleArea(t)
        if (area <= 0.0) continue
        fun cot(at: Vec3, b: Vec3, c: Vec3): Double {
            val ux = (b.x - at.x).toDouble(); val uy = (b.y - at.y).toDouble(); val uz = (b.z - at.z).toDouble()
            val vx = (c.x - at.x).toDouble(); val vy = (c.y - at.y).toDouble(); val vz = (c.z - at.z).toDouble()
            val dot = ux * vx + uy * vy + uz * vz
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            val len = sqrt(cx * cx + cy * cy + cz * cz)
            return if (len < 1e-18) 0.0 else dot / len
        }
        val cotI = cot(pi, pj, pk); val cotJ = cot(pj, pk, pi); val cotK = cot(pk, pi, pj)
        val obtuse = cotI < 0.0 || cotJ < 0.0 || cotK < 0.0
        if (!obtuse) {
            fun len2(a: Vec3, b: Vec3): Double {
                val dx = (a.x - b.x).toDouble(); val dy = (a.y - b.y).toDouble(); val dz = (a.z - b.z).toDouble()
                return dx * dx + dy * dy + dz * dz
            }
            out[i] += (len2(pi, pj) * cotK + len2(pi, pk) * cotJ) / 8.0
            out[j] += (len2(pj, pk) * cotI + len2(pj, pi) * cotK) / 8.0
            out[k] += (len2(pk, pi) * cotJ + len2(pk, pj) * cotI) / 8.0
        } else {
            // The obtuse corner takes half, the other two a quarter each.
            val obtuseVertex = when {
                cotI < 0.0 -> i
                cotJ < 0.0 -> j
                else -> k
            }
            for (v in intArrayOf(i, j, k)) out[v] += if (v == obtuseVertex) area / 2.0 else area / 4.0
        }
    }
    return out
}

/**
 * Discrete Gaussian curvature per vertex: the angle deficit `2π - Σθ` divided by the vertex's mixed
 * area.
 *
 * Exact in the sense that matters — `Σ K_v A_v = 2π χ` (Gauss-Bonnet) holds to float precision on a
 * closed mesh, which means a sphere sums to `4π`, a torus to `0`, and a bug in the sum shows up
 * immediately. `1 / r²` everywhere on a sphere of radius `r`.
 *
 * Boundary vertices get `π - Σθ` instead, the open-surface form.
 */
fun TriMesh.gaussianCurvature(): DoubleArray {
    val angles = DoubleArray(vertexCount)
    for (t in 0 until triangleCount) {
        val i = ia(t); val j = ib(t); val k = ic(t)
        val pi = position(i); val pj = position(j); val pk = position(k)
        fun angleAt(at: Vec3, b: Vec3, c: Vec3): Double {
            val u = Vec3(b).sub(at); val v = Vec3(c).sub(at)
            val lu = u.length(); val lv = v.length()
            if (lu < 1e-18f || lv < 1e-18f) return 0.0
            return acos((u.dot(v) / (lu * lv)).toDouble().coerceIn(-1.0, 1.0))
        }
        angles[i] += angleAt(pi, pj, pk)
        angles[j] += angleAt(pj, pk, pi)
        angles[k] += angleAt(pk, pi, pj)
    }
    val areas = mixedVertexAreas()
    val boundary = topology().boundaryVertices
    return DoubleArray(vertexCount) { v ->
        val turn = if (v in boundary) PI else 2.0 * PI
        if (areas[v] <= 1e-18) 0.0 else (turn - angles[v]) / areas[v]
    }
}

/**
 * Discrete mean curvature per vertex, signed against the surface normal: half the magnitude of the
 * cotangent Laplacian, divided by the mixed area.
 *
 * `1 / r` everywhere on a sphere of radius `r` (positive for an outward-wound sphere, which is the
 * convention that makes a convex surface positive). `0` on a plane. Boundary vertices are reported as
 * `0` rather than as the garbage the one-sided operator would give.
 */
fun TriMesh.meanCurvature(): DoubleArray {
    val topo = topology()
    val areas = mixedVertexAreas()
    val withNormals = if (hasNormals) this else withVertexNormals()
    val lx = DoubleArray(vertexCount); val ly = DoubleArray(vertexCount); val lz = DoubleArray(vertexCount)
    for (e in 0 until topo.edgeCount) {
        val (a, b) = topo.edges[e]
        var cotSum = 0.0
        for (t in topo.edgeTriangles[e]) {
            val c = run {
                val ta = ia(t); val tb = ib(t); val tc = ic(t)
                when {
                    ta != a && ta != b -> ta
                    tb != a && tb != b -> tb
                    else -> tc
                }
            }
            val pa = position(a); val pb = position(b); val pc = position(c)
            val ux = (pa.x - pc.x).toDouble(); val uy = (pa.y - pc.y).toDouble(); val uz = (pa.z - pc.z).toDouble()
            val vx = (pb.x - pc.x).toDouble(); val vy = (pb.y - pc.y).toDouble(); val vz = (pb.z - pc.z).toDouble()
            val dot = ux * vx + uy * vy + uz * vz
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            val len = sqrt(cx * cx + cy * cy + cz * cz)
            if (len > 1e-18) cotSum += dot / len
        }
        val dx = (x(b) - x(a)).toDouble(); val dy = (y(b) - y(a)).toDouble(); val dz = (z(b) - z(a)).toDouble()
        lx[a] += cotSum * dx; ly[a] += cotSum * dy; lz[a] += cotSum * dz
        lx[b] -= cotSum * dx; ly[b] -= cotSum * dy; lz[b] -= cotSum * dz
    }
    val boundary = topo.boundaryVertices
    return DoubleArray(vertexCount) { v ->
        if (v in boundary || areas[v] <= 1e-18) return@DoubleArray 0.0
        // K_H = |L| / (4A); the sign comes from whether L points along or against the normal.
        val mag = sqrt(lx[v] * lx[v] + ly[v] * ly[v] + lz[v] * lz[v]) / (4.0 * areas[v])
        val nrm = withNormals.normal(v) ?: return@DoubleArray mag
        val along = lx[v] * nrm.x + ly[v] * nrm.y + lz[v] * nrm.z
        if (along <= 0.0) mag else -mag
    }
}

// ==============================================================================================
// BVH and queries
// ==============================================================================================

/** What a ray hit: the triangle, the distance along the ray, and the point and normal there. */
class RayHit(
    /** Index of the triangle that was hit. */
    val triangle: Int,
    /** Distance along the (unit-length) ray direction. */
    val distance: Double,
    /** The intersection point. */
    val point: Vec3,
    /** The hit triangle's geometric normal — *not* flipped toward the ray. */
    val normal: Vec3,
    /** Barycentric coordinates of the hit within the triangle, as `(u, v)` on corners `b` and `c`. */
    val u: Double,
    val v: Double,
) {
    override fun toString(): String = "RayHit(tri=$triangle, t=%.6f, at=$point)".format(distance)
}

/** The nearest point on a mesh to a query point. */
class SurfacePoint(
    /** Index of the triangle the nearest point lies on. */
    val triangle: Int,
    /** The nearest point itself. */
    val point: Vec3,
    /** Distance from the query point to [point]. */
    val distance: Double,
) {
    override fun toString(): String = "SurfacePoint(tri=$triangle, at=$point, d=%.6f)".format(distance)
}

/**
 * A bounding-volume hierarchy over a mesh's triangles: build once, query many times.
 *
 * Median split along the widest axis of each node's centroid spread, leaves of at most
 * [leafSize] triangles. That is the simple construction rather than the good one (no surface-area
 * heuristic, no spatial splits), which is the right trade here — build time stays linear-ish and the
 * queries below all go from `O(n)` to `O(log n)`, which is the whole point.
 *
 * The mesh is captured by reference. Building a new [TriMesh] does not invalidate an old BVH (nothing
 * mutates), but a BVH is only valid for the mesh it was built from.
 */
class MeshBvh(val mesh: TriMesh, leafSize: Int = 8) {

    // Flat node arrays: 6 bounds floats, then either (left, right) or (-firstTriangle-1, count).
    private val nodeMin: FloatArray
    private val nodeMax: FloatArray
    private val nodeLeft: IntArray
    private val nodeRight: IntArray
    private val order: IntArray
    private var nodeCount = 0

    init {
        val n = mesh.triangleCount
        order = IntArray(n) { it }
        val maxNodes = max(1, 2 * (n / max(1, leafSize) + 1) * 2 + 1)
        nodeMin = FloatArray(maxNodes * 3)
        nodeMax = FloatArray(maxNodes * 3)
        nodeLeft = IntArray(maxNodes)
        nodeRight = IntArray(maxNodes)
        if (n > 0) {
            val cx = FloatArray(n); val cy = FloatArray(n); val cz = FloatArray(n)
            for (t in 0 until n) {
                val (a, b, c) = mesh.triangle(t)
                cx[t] = (a.x + b.x + c.x) / 3f; cy[t] = (a.y + b.y + c.y) / 3f; cz[t] = (a.z + b.z + c.z) / 3f
            }
            build(0, n, cx, cy, cz, leafSize)
        }
    }

    /** Recursively build the node covering `order[from until to]`; returns the node index. */
    private fun build(from: Int, to: Int, cx: FloatArray, cy: FloatArray, cz: FloatArray, leafSize: Int): Int {
        val node = nodeCount++
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in from until to) {
            val t = order[i]
            for (k in 0..2) {
                val v = mesh.indices[t * 3 + k]
                minX = min(minX, mesh.x(v)); maxX = max(maxX, mesh.x(v))
                minY = min(minY, mesh.y(v)); maxY = max(maxY, mesh.y(v))
                minZ = min(minZ, mesh.z(v)); maxZ = max(maxZ, mesh.z(v))
            }
        }
        nodeMin[node * 3] = minX; nodeMin[node * 3 + 1] = minY; nodeMin[node * 3 + 2] = minZ
        nodeMax[node * 3] = maxX; nodeMax[node * 3 + 1] = maxY; nodeMax[node * 3 + 2] = maxZ
        val count = to - from
        if (count <= leafSize) {
            nodeLeft[node] = -from - 1
            nodeRight[node] = count
            return node
        }
        // Split on the axis whose centroid spread is widest, at the median.
        var spreadMin = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var spreadMax = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in from until to) {
            val t = order[i]
            val c = floatArrayOf(cx[t], cy[t], cz[t])
            for (a in 0..2) { spreadMin[a] = min(spreadMin[a], c[a]); spreadMax[a] = max(spreadMax[a], c[a]) }
        }
        val axis = (0..2).maxByOrNull { spreadMax[it] - spreadMin[it] } ?: 0
        val slice = order.copyOfRange(from, to).sortedBy { t -> when (axis) { 0 -> cx[t]; 1 -> cy[t]; else -> cz[t] } }
        slice.forEachIndexed { i, t -> order[from + i] = t }
        val mid = from + count / 2
        nodeLeft[node] = build(from, mid, cx, cy, cz, leafSize)
        nodeRight[node] = build(mid, to, cx, cy, cz, leafSize)
        return node
    }

    private fun isLeaf(node: Int) = nodeLeft[node] < 0

    /** Slab test: the ray's entry distance into this node's box, or `Double.MAX_VALUE` for a miss. */
    private fun boxEntry(node: Int, ox: Double, oy: Double, oz: Double, idx: Double, idy: Double, idz: Double, tMax: Double): Double {
        var t0 = 0.0
        var t1 = tMax
        for (a in 0..2) {
            val o = when (a) { 0 -> ox; 1 -> oy; else -> oz }
            val inv = when (a) { 0 -> idx; 1 -> idy; else -> idz }
            val lo = (nodeMin[node * 3 + a] - o) * inv
            val hi = (nodeMax[node * 3 + a] - o) * inv
            val near = min(lo, hi); val far = max(lo, hi)
            if (near > t0) t0 = near
            if (far < t1) t1 = far
            if (t0 > t1) return Double.MAX_VALUE
        }
        return t0
    }

    /**
     * The nearest triangle [direction] from [origin], or `null`.
     *
     * Möller-Trumbore, double precision, watertight-ish: a ray that passes exactly through a shared
     * edge hits one of the two triangles rather than neither. Back faces are included by default
     * because "did my ray leave the solid" needs them; pass `cullBackFaces = true` for the
     * front-faces-only behaviour a renderer wants.
     *
     * @param direction need not be unit length; [RayHit.distance] is measured in its units.
     * @param maxDistance ignore hits beyond this.
     */
    fun raycast(
        origin: Vec3,
        direction: Vec3,
        maxDistance: Double = Double.MAX_VALUE,
        cullBackFaces: Boolean = false,
        minDistance: Double = 1e-9,
    ): RayHit? {
        if (nodeCount == 0) return null
        val ox = origin.x.toDouble(); val oy = origin.y.toDouble(); val oz = origin.z.toDouble()
        val dx = direction.x.toDouble(); val dy = direction.y.toDouble(); val dz = direction.z.toDouble()
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) return null
        val idx = if (dx != 0.0) 1.0 / dx else Double.MAX_VALUE
        val idy = if (dy != 0.0) 1.0 / dy else Double.MAX_VALUE
        val idz = if (dz != 0.0) 1.0 / dz else Double.MAX_VALUE

        var best = maxDistance
        var bestTri = -1
        var bestU = 0.0; var bestV = 0.0
        val stack = IntArray(64)
        var sp = 0
        stack[sp++] = 0
        while (sp > 0) {
            val node = stack[--sp]
            if (boxEntry(node, ox, oy, oz, idx, idy, idz, best) > best) continue
            if (isLeaf(node)) {
                val from = -nodeLeft[node] - 1
                for (i in from until from + nodeRight[node]) {
                    val t = order[i]
                    val a = mesh.ia(t) * 3; val b = mesh.ib(t) * 3; val c = mesh.ic(t) * 3
                    val p = mesh.positions
                    val e1x = (p[b] - p[a]).toDouble(); val e1y = (p[b + 1] - p[a + 1]).toDouble(); val e1z = (p[b + 2] - p[a + 2]).toDouble()
                    val e2x = (p[c] - p[a]).toDouble(); val e2y = (p[c + 1] - p[a + 1]).toDouble(); val e2z = (p[c + 2] - p[a + 2]).toDouble()
                    val px = dy * e2z - dz * e2y
                    val py = dz * e2x - dx * e2z
                    val pz = dx * e2y - dy * e2x
                    val det = e1x * px + e1y * py + e1z * pz
                    if (cullBackFaces) { if (det <= 1e-18) continue } else if (abs(det) < 1e-18) continue
                    val invDet = 1.0 / det
                    val tx = ox - p[a]; val ty = oy - p[a + 1]; val tz = oz - p[a + 2]
                    val u = (tx * px + ty * py + tz * pz) * invDet
                    if (u < -1e-9 || u > 1.0 + 1e-9) continue
                    val qx = ty * e1z - tz * e1y
                    val qy = tz * e1x - tx * e1z
                    val qz = tx * e1y - ty * e1x
                    val v = (dx * qx + dy * qy + dz * qz) * invDet
                    if (v < -1e-9 || u + v > 1.0 + 1e-9) continue
                    val hit = (e2x * qx + e2y * qy + e2z * qz) * invDet
                    if (hit < minDistance || hit >= best) continue
                    best = hit; bestTri = t; bestU = u; bestV = v
                }
            } else {
                stack[sp++] = nodeLeft[node]
                stack[sp++] = nodeRight[node]
            }
        }
        if (bestTri < 0) return null
        return RayHit(
            bestTri, best,
            Vec3((ox + dx * best).toFloat(), (oy + dy * best).toFloat(), (oz + dz * best).toFloat()),
            mesh.faceNormal(bestTri), bestU, bestV,
        )
    }

    /** Every triangle the ray meets, nearest first — what a "cut this solid with a line" query wants. */
    fun raycastAll(origin: Vec3, direction: Vec3, maxDistance: Double = Double.MAX_VALUE): List<RayHit> {
        val hits = mutableListOf<RayHit>()
        var from = 1e-9
        var guard = mesh.triangleCount + 8
        while (guard-- > 0) {
            val hit = raycast(origin, direction, maxDistance, cullBackFaces = false, minDistance = from) ?: break
            hits.add(hit)
            from = hit.distance + 1e-7
            if (from >= maxDistance) break
        }
        return hits
    }

    /** Squared distance from a point to this node's box; `0` inside it. */
    private fun boxDistance2(node: Int, x: Double, y: Double, z: Double): Double {
        var d = 0.0
        for (a in 0..2) {
            val p = when (a) { 0 -> x; 1 -> y; else -> z }
            val lo = nodeMin[node * 3 + a].toDouble(); val hi = nodeMax[node * 3 + a].toDouble()
            val excess = when {
                p < lo -> lo - p
                p > hi -> p - hi
                else -> 0.0
            }
            d += excess * excess
        }
        return d
    }

    /** The nearest point of the mesh's surface to [query], or `null` for an empty mesh. */
    fun closestPoint(query: Vec3): SurfacePoint? {
        if (nodeCount == 0) return null
        val qx = query.x.toDouble(); val qy = query.y.toDouble(); val qz = query.z.toDouble()
        var best2 = Double.MAX_VALUE
        var bestTri = -1
        var bx = 0.0; var by = 0.0; var bz = 0.0
        val stack = IntArray(64)
        var sp = 0
        stack[sp++] = 0
        while (sp > 0) {
            val node = stack[--sp]
            if (boxDistance2(node, qx, qy, qz) > best2) continue
            if (isLeaf(node)) {
                val from = -nodeLeft[node] - 1
                for (i in from until from + nodeRight[node]) {
                    val t = order[i]
                    val c = closestOnTriangle(mesh, t, qx, qy, qz)
                    val dx = c[0] - qx; val dy = c[1] - qy; val dz = c[2] - qz
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 < best2) { best2 = d2; bestTri = t; bx = c[0]; by = c[1]; bz = c[2] }
                }
            } else {
                // Descend into the nearer child first so the pruning bound tightens sooner.
                val l = nodeLeft[node]; val r = nodeRight[node]
                if (boxDistance2(l, qx, qy, qz) <= boxDistance2(r, qx, qy, qz)) {
                    stack[sp++] = r; stack[sp++] = l
                } else {
                    stack[sp++] = l; stack[sp++] = r
                }
            }
        }
        if (bestTri < 0) return null
        return SurfacePoint(bestTri, Vec3(bx.toFloat(), by.toFloat(), bz.toFloat()), sqrt(best2))
    }

    /**
     * Is [query] inside the mesh?
     *
     * By ray parity: cast in a fixed but arbitrary direction and count crossings. Correct for any
     * closed mesh, however it is triangulated, and unbothered by concavity. Meaningless for an open
     * one — check [TriMesh.isWatertight] if you did not build the mesh.
     *
     * A ray that grazes an edge could be counted twice; the direction is a deliberately "ugly"
     * vector so that is vanishingly unlikely, and a second probe along a different direction
     * arbitrates when the two disagree.
     */
    fun contains(query: Vec3): Boolean {
        if (nodeCount == 0) return false
        val a = parityInside(query, Vec3(0.5773f, 0.5774f, 0.5775f))
        val b = parityInside(query, Vec3(-0.2673f, 0.8018f, 0.5345f))
        if (a == b) return a
        // The two probes disagree, so one of them grazed something. A third breaks the tie.
        return parityInside(query, Vec3(0.7071f, -0.1f, -0.7f))
    }

    private fun parityInside(query: Vec3, direction: Vec3): Boolean =
        raycastAll(query, direction).size % 2 == 1

    /**
     * Distance to the surface, negative inside — the mesh as a [ScalarField3D].
     *
     * Exact (it is the true closest-point distance, not a grid approximation), and `O(log n)`, so it
     * is usable as a field: `mesh.bvh().asSdf()` can be marched, blended with an analytic SDF, or
     * offset to shell a scanned mesh.
     */
    fun signedDistance(query: Vec3): Double {
        val near = closestPoint(query) ?: return Double.MAX_VALUE
        return if (contains(query)) -near.distance else near.distance
    }

    /** This mesh as a signed-distance [ScalarField3D], for composing with the `sdf…` combinators. */
    fun asSdf(): ScalarField3D = { p -> signedDistance(p).toFloat() }

    /** Bounds of the whole hierarchy — the mesh's AABB, for free. */
    fun bounds(): AxisAlignedBoundingBox3D =
        if (nodeCount == 0) {
            AxisAlignedBoundingBox3D(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))
        } else {
            AxisAlignedBoundingBox3D(
                Vec3(nodeMin[0], nodeMin[1], nodeMin[2]),
                Vec3(nodeMax[0], nodeMax[1], nodeMax[2]),
            )
        }

    override fun toString(): String = "MeshBvh($nodeCount nodes over ${mesh.triangleCount} triangles)"
}

/** Closest point on triangle [t] to `(x, y, z)`, by the standard region test. Returns `[x, y, z]`. */
private fun closestOnTriangle(mesh: TriMesh, t: Int, x: Double, y: Double, z: Double): DoubleArray {
    val p = mesh.positions
    val a = mesh.ia(t) * 3; val b = mesh.ib(t) * 3; val c = mesh.ic(t) * 3
    val ax = p[a].toDouble(); val ay = p[a + 1].toDouble(); val az = p[a + 2].toDouble()
    val abx = (p[b] - p[a]).toDouble(); val aby = (p[b + 1] - p[a + 1]).toDouble(); val abz = (p[b + 2] - p[a + 2]).toDouble()
    val acx = (p[c] - p[a]).toDouble(); val acy = (p[c + 1] - p[a + 1]).toDouble(); val acz = (p[c + 2] - p[a + 2]).toDouble()
    val apx = x - ax; val apy = y - ay; val apz = z - az
    val d1 = abx * apx + aby * apy + abz * apz
    val d2 = acx * apx + acy * apy + acz * apz
    if (d1 <= 0.0 && d2 <= 0.0) return doubleArrayOf(ax, ay, az)
    val bpx = x - (ax + abx); val bpy = y - (ay + aby); val bpz = z - (az + abz)
    val d3 = abx * bpx + aby * bpy + abz * bpz
    val d4 = acx * bpx + acy * bpy + acz * bpz
    if (d3 >= 0.0 && d4 <= d3) return doubleArrayOf(ax + abx, ay + aby, az + abz)
    val vc = d1 * d4 - d3 * d2
    if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) {
        val v = if (abs(d1 - d3) < 1e-18) 0.0 else d1 / (d1 - d3)
        return doubleArrayOf(ax + abx * v, ay + aby * v, az + abz * v)
    }
    val cpx = x - (ax + acx); val cpy = y - (ay + acy); val cpz = z - (az + acz)
    val d5 = abx * cpx + aby * cpy + abz * cpz
    val d6 = acx * cpx + acy * cpy + acz * cpz
    if (d6 >= 0.0 && d5 <= d6) return doubleArrayOf(ax + acx, ay + acy, az + acz)
    val vb = d5 * d2 - d1 * d6
    if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) {
        val w = if (abs(d2 - d6) < 1e-18) 0.0 else d2 / (d2 - d6)
        return doubleArrayOf(ax + acx * w, ay + acy * w, az + acz * w)
    }
    val va = d3 * d6 - d5 * d4
    if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) {
        val denom = (d4 - d3) + (d5 - d6)
        val w = if (abs(denom) < 1e-18) 0.0 else (d4 - d3) / denom
        return doubleArrayOf(
            ax + abx + (acx - abx) * w,
            ay + aby + (acy - aby) * w,
            az + abz + (acz - abz) * w,
        )
    }
    val denom = va + vb + vc
    if (abs(denom) < 1e-18) return doubleArrayOf(ax, ay, az)
    val v = vb / denom
    val w = vc / denom
    return doubleArrayOf(ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w)
}

/** Build a [MeshBvh] over this mesh. Hold on to it — building is the expensive part. */
fun TriMesh.bvh(leafSize: Int = 8): MeshBvh = MeshBvh(this, leafSize)

/** One-shot ray query. Builds a BVH, so use [bvh] directly for more than a handful of rays. */
fun TriMesh.raycast(origin: Vec3, direction: Vec3): RayHit? = bvh().raycast(origin, direction)

/** One-shot nearest-surface-point query. Builds a BVH — see [bvh]. */
fun TriMesh.closestPoint(query: Vec3): SurfacePoint? = bvh().closestPoint(query)

/** One-shot inside test. Builds a BVH — see [bvh]. */
fun TriMesh.contains(query: Vec3): Boolean = bvh().contains(query)

/**
 * How far apart two meshes are, sampled: the largest distance from a vertex of [other] to the
 * surface of this mesh (a one-sided, vertex-sampled Hausdorff distance).
 *
 * Vertex-sampled rather than exact, and one-sided rather than symmetric — both stated because it
 * matters: this **under**-estimates the true Hausdorff distance, so it is a valid *lower* bound and
 * a valid regression test ("decimation stayed within ε") but not a proof of closeness. [maxDeviation]
 * runs it both ways, which is the number you usually want.
 */
fun TriMesh.deviationFrom(other: TriMesh): Double {
    if (triangleCount == 0 || other.vertexCount == 0) return 0.0
    val tree = bvh()
    var worst = 0.0
    for (v in 0 until other.vertexCount) {
        val d = tree.closestPoint(other.position(v))?.distance ?: continue
        if (d > worst) worst = d
    }
    return worst
}

/** The larger of the two one-sided [deviationFrom] distances — symmetric, still vertex-sampled. */
fun maxDeviation(a: TriMesh, b: TriMesh): Double = max(a.deviationFrom(b), b.deviationFrom(a))

/**
 * The solid angle-free, purely combinatorial check that this mesh's vertices all lie on or inside
 * [hullCandidate] — used by the convex-hull tests, and occasionally useful on its own.
 *
 * @param tolerance how far outside a vertex may be and still pass.
 */
fun TriMesh.isContainedIn(hullCandidate: TriMesh, tolerance: Double = 1e-4): Boolean {
    val tree = hullCandidate.bvh()
    for (v in 0 until vertexCount) {
        val p = position(v)
        if (tree.contains(p)) continue
        val d = tree.closestPoint(p)?.distance ?: return false
        if (d > tolerance) return false
    }
    return true
}

/**
 * Uniform-ish points on this mesh's surface, chosen by area so a finely triangulated region is not
 * over-sampled — the sampler a Monte-Carlo volume check or a point-cloud export wants.
 *
 * @param count how many points.
 * @param seed deterministic.
 */
fun TriMesh.samplePoints(count: Int, seed: Long = 1L): List<Vec3> {
    require(count >= 0) { "count must be >= 0, got $count" }
    if (triangleCount == 0 || count == 0) return emptyList()
    val cumulative = DoubleArray(triangleCount)
    var running = 0.0
    for (t in 0 until triangleCount) { running += triangleArea(t); cumulative[t] = running }
    if (running <= 0.0) return emptyList()
    val random = kotlin.random.Random(seed)
    return List(count) {
        val target = random.nextDouble() * running
        var lo = 0; var hi = triangleCount - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] < target) lo = mid + 1 else hi = mid
        }
        val (a, b, c) = triangle(lo)
        // Reflecting the pair back inside the unit triangle makes it uniform over the triangle
        // rather than bunched toward one corner.
        var u = random.nextDouble().toFloat()
        var v = random.nextDouble().toFloat()
        if (u + v > 1f) { u = 1f - u; v = 1f - v }
        Vec3(
            a.x + (b.x - a.x) * u + (c.x - a.x) * v,
            a.y + (b.y - a.y) * u + (c.y - a.y) * v,
            a.z + (b.z - a.z) * u + (c.z - a.z) * v,
        )
    }
}

/**
 * The convex hull of a point cloud, as a [TriMesh] — incremental construction, no dependencies.
 *
 * Starts from a non-degenerate tetrahedron of four extreme points, then adds the remaining points
 * one at a time: every face the new point can "see" (is in front of) is deleted, and the resulting
 * horizon loop is re-cones to the point. That is the standard incremental hull; the interesting part
 * is that the horizon is found combinatorially from the deleted face set, so no edge bookkeeping is
 * needed and the result is watertight by construction.
 *
 * Degenerate input (fewer than 4 points, or all points coplanar) returns an empty mesh rather than
 * throwing, because "these points have no volume" is an answer.
 *
 * @param tolerance how far in front of a face a point must be to count as outside it.
 */
fun convexHullMesh(points: List<Vec3>, tolerance: Double = 1e-7): TriMesh {
    if (points.size < 4) return TriMesh.empty
    val px = DoubleArray(points.size) { points[it].x.toDouble() }
    val py = DoubleArray(points.size) { points[it].y.toDouble() }
    val pz = DoubleArray(points.size) { points[it].z.toDouble() }

    // Seed: two extreme points on the widest axis, then the farthest from that line, then the
    // farthest from that plane. Any of these failing means the cloud has no volume.
    val spread = (0..2).map { a ->
        val v = when (a) { 0 -> px; 1 -> py; else -> pz }
        (v.maxOrNull()!! - v.minOrNull()!!) to a
    }
    val axis = spread.maxByOrNull { it.first }!!.second
    val key = when (axis) { 0 -> px; 1 -> py; else -> pz }
    val i0 = key.indices.minByOrNull { key[it] }!!
    val i1 = key.indices.maxByOrNull { key[it] }!!
    if (i0 == i1) return TriMesh.empty
    fun cross(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double) =
        doubleArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
    var i2 = -1; var bestArea = 0.0
    for (i in points.indices) {
        if (i == i0 || i == i1) continue
        val c = cross(
            px[i1] - px[i0], py[i1] - py[i0], pz[i1] - pz[i0],
            px[i] - px[i0], py[i] - py[i0], pz[i] - pz[i0],
        )
        val area = sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2])
        if (area > bestArea) { bestArea = area; i2 = i }
    }
    if (i2 < 0 || bestArea <= tolerance) return TriMesh.empty
    val nrm = cross(
        px[i1] - px[i0], py[i1] - py[i0], pz[i1] - pz[i0],
        px[i2] - px[i0], py[i2] - py[i0], pz[i2] - pz[i0],
    )
    var i3 = -1; var bestOff = 0.0
    for (i in points.indices) {
        if (i == i0 || i == i1 || i == i2) continue
        val d = nrm[0] * (px[i] - px[i0]) + nrm[1] * (py[i] - py[i0]) + nrm[2] * (pz[i] - pz[i0])
        if (abs(d) > abs(bestOff)) { bestOff = d; i3 = i }
    }
    if (i3 < 0 || abs(bestOff) <= tolerance) return TriMesh.empty

    // Faces as vertex triples, wound outward.
    val faces = ArrayList<IntArray>()
    fun addTetrahedron(a: Int, b: Int, c: Int, d: Int) {
        // Orient each of the four faces away from the fourth point.
        for (f in listOf(intArrayOf(a, b, c), intArrayOf(a, b, d), intArrayOf(a, c, d), intArrayOf(b, c, d))) {
            val other = intArrayOf(a, b, c, d).first { it !in f.toList() }
            val n = cross(
                px[f[1]] - px[f[0]], py[f[1]] - py[f[0]], pz[f[1]] - pz[f[0]],
                px[f[2]] - px[f[0]], py[f[2]] - py[f[0]], pz[f[2]] - pz[f[0]],
            )
            val side = n[0] * (px[other] - px[f[0]]) + n[1] * (py[other] - py[f[0]]) + n[2] * (pz[other] - pz[f[0]])
            faces.add(if (side > 0) intArrayOf(f[0], f[2], f[1]) else intArrayOf(f[0], f[1], f[2]))
        }
    }
    addTetrahedron(i0, i1, i2, i3)

    fun outside(face: IntArray, i: Int): Double {
        val n = cross(
            px[face[1]] - px[face[0]], py[face[1]] - py[face[0]], pz[face[1]] - pz[face[0]],
            px[face[2]] - px[face[0]], py[face[2]] - py[face[0]], pz[face[2]] - pz[face[0]],
        )
        val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        if (len < 1e-300) return 0.0
        return (n[0] * (px[i] - px[face[0]]) + n[1] * (py[i] - py[face[0]]) + n[2] * (pz[i] - pz[face[0]])) / len
    }

    // Any point strictly inside the seed tetrahedron stays inside every later hull, so its centroid
    // is a permanent interior reference for orienting newly created faces outward.
    val insideX = (px[i0] + px[i1] + px[i2] + px[i3]) / 4.0
    val insideY = (py[i0] + py[i1] + py[i2] + py[i3]) / 4.0
    val insideZ = (pz[i0] + pz[i1] + pz[i2] + pz[i3]) / 4.0
    fun facesAwayFromInterior(face: IntArray): IntArray {
        val n = cross(
            px[face[1]] - px[face[0]], py[face[1]] - py[face[0]], pz[face[1]] - pz[face[0]],
            px[face[2]] - px[face[0]], py[face[2]] - py[face[0]], pz[face[2]] - pz[face[0]],
        )
        val side = n[0] * (insideX - px[face[0]]) + n[1] * (insideY - py[face[0]]) + n[2] * (insideZ - pz[face[0]])
        return if (side > 0.0) intArrayOf(face[0], face[2], face[1]) else face
    }

    val onHull = BooleanArray(points.size)
    for (i in intArrayOf(i0, i1, i2, i3)) onHull[i] = true
    for (i in points.indices) {
        if (onHull[i]) continue
        val visible = faces.filter { outside(it, i) > tolerance }
        if (visible.isEmpty()) continue
        // Horizon = the edges of the visible set that are not shared by two visible faces.
        val edgeCount = HashMap<Long, Int>()
        val stride = points.size + 1L
        fun edgeKey(a: Int, b: Int) = min(a, b).toLong() * stride + max(a, b)
        for (f in visible) {
            for (k in 0..2) edgeCount.merge(edgeKey(f[k], f[(k + 1) % 3]), 1, Int::plus)
        }
        val horizon = ArrayList<IntArray>()
        for (f in visible) {
            for (k in 0..2) {
                val a = f[k]; val b = f[(k + 1) % 3]
                if (edgeCount[edgeKey(a, b)] == 1) horizon.add(intArrayOf(a, b))
            }
        }
        faces.removeAll(visible.toSet())
        // Cone the horizon back to the new point, each new face oriented away from the interior so
        // the `outside` test above keeps working on later points.
        for ((a, b) in horizon.map { it[0] to it[1] }) faces.add(facesAwayFromInterior(intArrayOf(a, b, i)))
        onHull[i] = true
    }

    val used = faces.flatMap { it.toList() }.distinct().sorted()
    val remap = HashMap<Int, Int>()
    val hull = triMesh {
        for (v in used) remap[v] = vertex(px[v].toFloat(), py[v].toFloat(), pz[v].toFloat())
        for (f in faces) triangle(remap[f[0]]!!, remap[f[1]]!!, remap[f[2]]!!)
    }
    return hull.removeDegenerateTriangles().fixWinding()
}

/** The convex hull of this mesh's own vertices — see [convexHullMesh]. */
fun TriMesh.convexHull(): TriMesh = convexHullMesh(vertices())

/**
 * The angle between two vectors in degrees, `0..180`, or `0` if either has no length.
 *
 * A crease angle, a normal deviation and a "did my transform rotate this the way I meant" check are
 * all this number, and writing `acos(dot / (len * len))` by hand is where the sign and the clamp go
 * wrong.
 */
fun angleBetweenDegrees(a: Vec3, b: Vec3): Double {
    val la = a.length(); val lb = b.length()
    if (la < 1e-20f || lb < 1e-20f) return 0.0
    return acos((a.dot(b) / (la * lb)).toDouble().coerceIn(-1.0, 1.0)) * 180.0 / PI
}

