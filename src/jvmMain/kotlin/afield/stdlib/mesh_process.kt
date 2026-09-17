@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Changing a mesh's *sampling* without changing the shape it stands for: normals, subdivision,
 * smoothing and decimation.
 *
 * These are the four operations that make the difference between a mesh you generated and a mesh you
 * can use. In rough order of how often you will reach for them:
 *
 *  - [TriMesh.withVertexNormals] / [TriMesh.flatShaded] / [TriMesh.shadedNormals] — shading.
 *    [TriMesh.shadedNormals] splits a vertex only where the faces meeting there genuinely
 *    disagree, so a cylinder comes out smooth around its side and hard at its rim, from one call.
 *  - [TriMesh.loopSubdivide] — four triangles for one, converging on the Loop limit surface. Refines
 *    *and* smooths, and preserves the Euler characteristic exactly.
 *  - [TriMesh.laplacianSmooth] / [TriMesh.taubinSmooth] — noise removal. Laplacian shrinks the shape
 *    (that is what a diffusion does); Taubin's λ/μ pair alternates a shrink with an expand and so
 *    does not, which is why it is the one to default to. Both offer cotangent weights, which are
 *    scale- and shape-independent where the uniform ones are not.
 *  - [TriMesh.decimate] — quadric error metric edge collapse down to a triangle budget. The dual of
 *    subdivision, and the one that has to work hardest: it maintains per-vertex quadrics, a
 *    cost-ordered queue with lazy invalidation, the link condition (so a collapse cannot make the
 *    mesh non-manifold) and a normal-flip veto (so it cannot turn the mesh inside out locally).
 *
 * ```kotlin
 * val fine = icosphereMesh(4)                       // 5120 triangles
 * val coarse = fine.decimate(400)                   // 400, and still a sphere
 * val smooth = coarse.taubinSmooth(iterations = 20) // volume preserved to within ~1%
 * ```
 *
 * Catmull-Clark is absent: it is a quad scheme, [TriMesh] is triangles-only, and
 * running it on a triangulated quad mesh gives a different (worse) surface than running it on the
 * quads. Loop is the triangle scheme, and it is the one here.
 */

// ==============================================================================================
// Normals
// ==============================================================================================

/**
 * Per-vertex normals from the incident triangles, each weighted by its area.
 *
 * Area weighting is the right default: it is what makes the normal field of a subdivided or marched
 * surface continuous, and it is insensitive to a fan of slivers on one side of a vertex distorting
 * the answer. Pass `areaWeighted = false` for the plain (angle-independent) sum, which is
 * occasionally what a stylised look wants.
 *
 * Shared vertices mean shared normals, so this **rounds off creases** — a cube gets rounded corners.
 * That is correct for a smooth surface and wrong for a faceted one; see [flatShaded] and
 * [shadedNormals].
 */
fun TriMesh.withVertexNormals(areaWeighted: Boolean = true): TriMesh {
    val n = FloatArray(vertexCount * 3)
    for (t in 0 until triangleCount) {
        val a = ia(t); val b = ib(t); val c = ic(t)
        val ax = x(a); val ay = y(a); val az = z(a)
        val ux = x(b) - ax; val uy = y(b) - ay; val uz = z(b) - az
        val vx = x(c) - ax; val vy = y(c) - ay; val vz = z(c) - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-20f) continue
        // The un-normalised cross product already has twice the triangle's area as its length, so
        // "area weighted" is simply "do not normalise first".
        if (!areaWeighted) { nx /= len; ny /= len; nz /= len }
        for (v in intArrayOf(a, b, c)) {
            n[v * 3] += nx; n[v * 3 + 1] += ny; n[v * 3 + 2] += nz
        }
    }
    for (v in 0 until vertexCount) {
        val nx = n[v * 3]; val ny = n[v * 3 + 1]; val nz = n[v * 3 + 2]
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l > 1e-20f) { n[v * 3] = nx / l; n[v * 3 + 1] = ny / l; n[v * 3 + 2] = nz / l }
    }
    return withNormals(n)
}

/**
 * One normal per **triangle**: every triangle gets its own three vertices, so every edge is hard.
 *
 * `3 * triangleCount` vertices and no sharing, which means the result is *not*
 * watertight in the topological sense — it is a render-ready mesh, not an analysis-ready one. Keep
 * the welded original for measurement.
 */
fun TriMesh.flatShaded(): TriMesh {
    val builder = TriMeshBuilder()
    for (t in 0 until triangleCount) {
        val fn = faceNormal(t)
        val corners = intArrayOf(ia(t), ib(t), ic(t))
        val fresh = IntArray(3) { k ->
            val v = corners[k]
            builder.vertex(x(v), y(v), z(v), normal = Vec3(fn), uv = uv(v), color = color(v))
        }
        builder.triangle(fresh[0], fresh[1], fresh[2])
    }
    return builder.build()
}

/**
 * Crease-aware normals: smooth across edges where the two faces are within [creaseAngleDegrees] of
 * each other, hard where they are not.
 *
 * This is the one to reach for on a generated primitive that ships with no normals. A cylinder comes
 * out smooth along its side and crisp at its rim; a box stays a box; an icosphere is untouched. Only
 * the vertices that actually straddle a crease are duplicated, so the cost over [withVertexNormals]
 * is proportional to how creased the mesh really is.
 *
 * Implemented by partitioning each vertex's triangle star into groups connected by *non-crease*
 * edges, then giving each group its own vertex carrying that group's area-weighted normal. Like
 * [flatShaded] this is render prep: the duplicated seam vertices mean the result is no longer
 * topologically closed.
 *
 * @param creaseAngleDegrees `0` behaves like [flatShaded], `180` like [withVertexNormals].
 */
fun TriMesh.shadedNormals(creaseAngleDegrees: Double = 35.0): TriMesh {
    if (triangleCount == 0) return this
    val cosLimit = cos(creaseAngleDegrees.coerceIn(0.0, 180.0) * PI / 180.0).toFloat()
    val topo = topology()
    val faceN = Array(triangleCount) { faceNormal(it) }
    val faceArea = DoubleArray(triangleCount) { triangleArea(it) }

    val builder = TriMeshBuilder()
    // corner (triangle, vertex) -> emitted vertex index
    val emitted = HashMap<Long, Int>()

    for (v in 0 until vertexCount) {
        val star = topo.vertexTriangles[v]
        if (star.isEmpty()) continue
        val local = HashMap<Int, Int>(star.size * 2)
        star.forEachIndexed { i, t -> local[t] = i }
        val group = IntArray(star.size) { -1 }
        var groups = 0
        for (s in star.indices) {
            if (group[s] >= 0) continue
            val g = groups++
            val stack = ArrayDeque<Int>()
            stack.addLast(s); group[s] = g
            while (stack.isNotEmpty()) {
                val si = stack.removeLast()
                val t = star[si]
                for (other in intArrayOf(ia(t), ib(t), ic(t))) {
                    if (other == v) continue
                    val e = topo.edgeOf(v, other)
                    if (e < 0) continue
                    for (nt in topo.edgeTriangles[e]) {
                        val ni = local[nt] ?: continue
                        if (group[ni] >= 0) continue
                        // Smooth across this edge only if the two faces nearly agree.
                        if (faceN[t].dot(faceN[nt]) < cosLimit) continue
                        group[ni] = g
                        stack.addLast(ni)
                    }
                }
            }
        }
        // One emitted vertex per group, carrying that group's area-weighted normal.
        val acc = Array(groups) { Vec3(0f, 0f, 0f) }
        for (s in star.indices) {
            val t = star[s]
            val a = faceArea[t].toFloat()
            acc[group[s]].add(faceN[t].x * a, faceN[t].y * a, faceN[t].z * a)
        }
        val index = IntArray(groups) { g ->
            val nrm = acc[g]
            if (nrm.length() < 1e-20f) nrm.set(0f, 1f, 0f) else nrm.normalize()
            builder.vertex(x(v), y(v), z(v), normal = Vec3(nrm), uv = uv(v), color = color(v))
        }
        for (s in star.indices) emitted[star[s].toLong() * (vertexCount + 1L) + v] = index[group[s]]
    }

    for (t in 0 until triangleCount) {
        val key = t.toLong() * (vertexCount + 1L)
        val a = emitted[key + ia(t)] ?: continue
        val b = emitted[key + ib(t)] ?: continue
        val c = emitted[key + ic(t)] ?: continue
        builder.triangle(a, b, c)
    }
    return builder.build()
}

// ==============================================================================================
// Loop subdivision
// ==============================================================================================

/**
 * Loop subdivision, [times] rounds: every triangle becomes four, and every old vertex moves toward
 * the average of its neighbours by the Loop weights.
 *
 * The classic C² (C¹ at extraordinary vertices) triangle scheme. Two properties worth relying on:
 * the Euler characteristic is **exactly** preserved, so a watertight input gives a watertight output
 * of the same genus; and the limit surface is inside the convex hull of the control mesh, so the
 * volume of a convex input shrinks slightly and monotonically toward its limit rather than wandering.
 *
 * Boundaries use the four-point boundary rules, so an open mesh keeps its border curve smooth
 * instead of pinching. Normals are recomputed; uvs and colours are dropped (interpolating an
 * attribute through subdivision needs its own rule per attribute, and guessing without saying so would be
 * worse than saying so).
 *
 * @param times rounds of subdivision. Each round multiplies the triangle count by 4, so 4 rounds is
 *   256x — check your budget.
 */
fun TriMesh.loopSubdivide(times: Int = 1): TriMesh {
    require(times >= 0) { "times must be >= 0, got $times" }
    var mesh = this
    repeat(times) { mesh = loopSubdivideOnce(mesh) }
    return if (times == 0) this else mesh.withVertexNormals()
}

private fun loopSubdivideOnce(mesh: TriMesh): TriMesh {
    if (mesh.triangleCount == 0) return mesh
    val topo = mesh.topology()
    val n = mesh.vertexCount
    val out = TriMeshBuilder()

    // ---- even (existing) vertices, repositioned
    for (v in 0 until n) {
        val ring = topo.neighbors(v)
        val boundaryRing = if (topo.boundaryVertices.contains(v)) {
            // The two neighbours reached by a boundary edge.
            ring.filter { w -> topo.edgeOf(v, w).let { it >= 0 && topo.edgeTriangles[it].size == 1 } }
        } else {
            emptyList()
        }
        if (boundaryRing.size == 2) {
            // Boundary rule: 3/4 self + 1/8 each neighbour along the border.
            val a = boundaryRing[0]; val b = boundaryRing[1]
            out.vertex(
                0.75f * mesh.x(v) + 0.125f * (mesh.x(a) + mesh.x(b)),
                0.75f * mesh.y(v) + 0.125f * (mesh.y(a) + mesh.y(b)),
                0.75f * mesh.z(v) + 0.125f * (mesh.z(a) + mesh.z(b)),
            )
        } else if (ring.isEmpty()) {
            out.vertex(mesh.x(v), mesh.y(v), mesh.z(v))
        } else {
            val k = ring.size
            // Loop's beta; the standard 3/16 special case for k = 3 falls out of this formula.
            val c = 0.375 + 0.25 * cos(2.0 * PI / k)
            val beta = ((0.625 - c * c) / k).toFloat()
            var sx = 0f; var sy = 0f; var sz = 0f
            for (w in ring) { sx += mesh.x(w); sy += mesh.y(w); sz += mesh.z(w) }
            val self = 1f - k * beta
            out.vertex(self * mesh.x(v) + beta * sx, self * mesh.y(v) + beta * sy, self * mesh.z(v) + beta * sz)
        }
    }

    // ---- odd (new edge) vertices
    val edgeVertex = IntArray(topo.edgeCount)
    for (e in 0 until topo.edgeCount) {
        val (a, b) = topo.edges[e]
        val faces = topo.edgeTriangles[e]
        if (faces.size == 2) {
            // 3/8 each endpoint, 1/8 each opposite corner.
            val c = opposite(mesh, faces[0], a, b)
            val d = opposite(mesh, faces[1], a, b)
            edgeVertex[e] = out.vertex(
                0.375f * (mesh.x(a) + mesh.x(b)) + 0.125f * (mesh.x(c) + mesh.x(d)),
                0.375f * (mesh.y(a) + mesh.y(b)) + 0.125f * (mesh.y(c) + mesh.y(d)),
                0.375f * (mesh.z(a) + mesh.z(b)) + 0.125f * (mesh.z(c) + mesh.z(d)),
            )
        } else {
            edgeVertex[e] = out.vertex(
                0.5f * (mesh.x(a) + mesh.x(b)),
                0.5f * (mesh.y(a) + mesh.y(b)),
                0.5f * (mesh.z(a) + mesh.z(b)),
            )
        }
    }

    // ---- retriangulate
    for (t in 0 until mesh.triangleCount) {
        val a = mesh.ia(t); val b = mesh.ib(t); val c = mesh.ic(t)
        val ab = edgeVertex[topo.edgeOf(a, b).takeIf { it >= 0 } ?: continue]
        val bc = edgeVertex[topo.edgeOf(b, c).takeIf { it >= 0 } ?: continue]
        val ca = edgeVertex[topo.edgeOf(c, a).takeIf { it >= 0 } ?: continue]
        out.triangle(a, ab, ca)
        out.triangle(b, bc, ab)
        out.triangle(c, ca, bc)
        out.triangle(ab, bc, ca)
    }
    return out.build()
}

/** The corner of triangle [t] that is neither [a] nor [b]. */
private fun opposite(mesh: TriMesh, t: Int, a: Int, b: Int): Int {
    val ta = mesh.ia(t); val tb = mesh.ib(t); val tc = mesh.ic(t)
    return when {
        ta != a && ta != b -> ta
        tb != a && tb != b -> tb
        else -> tc
    }
}

// ==============================================================================================
// Smoothing
// ==============================================================================================

/**
 * Laplacian smoothing: each vertex moved [lambda] of the way toward the weighted average of its
 * neighbours, [iterations] times.
 *
 * Removes noise, and **shrinks** — that is not a bug, it is what solving the heat equation on a
 * surface does, and a sphere smoothed for long enough becomes a point. [taubinSmooth] is the version
 * that does not shrink; reach for this one when you want the shrinkage (to inset a shell) or when you
 * are smoothing an open patch where it barely matters.
 *
 * @param lambda step size, sensible in `0 < λ <= 1`. Above ~1 the iteration oscillates.
 * @param cotangent use cotangent weights (`(cot α + cot β)/2` per edge) rather than uniform ones.
 *   Cotangent weights approximate the true Laplace-Beltrami operator, so the result does not depend
 *   on how the surface happens to be triangulated — worth it on an irregular mesh, pointless on a
 *   regular one. Negative weights from obtuse triangles are clamped to zero, which is the standard
 *   fix for the instability they otherwise cause.
 * @param preserveBoundary leave boundary vertices exactly where they are, so an open patch does not
 *   crawl inward from its edge.
 */
fun TriMesh.laplacianSmooth(
    iterations: Int = 1,
    lambda: Double = 0.5,
    cotangent: Boolean = false,
    preserveBoundary: Boolean = true,
): TriMesh {
    if (iterations <= 0 || triangleCount == 0) return this
    val topo = topology()
    var pos = positions.copyOf()
    repeat(iterations) { pos = smoothingPass(this, topo, pos, lambda.toFloat(), cotangent, preserveBoundary) }
    return TriMesh(pos, indices, null, uvs, colors).withVertexNormals()
}

/**
 * Taubin λ/μ smoothing: a shrinking pass at `+λ` followed by an inflating pass at `-μ`, [iterations]
 * times.
 *
 * The point of the pair is a low-pass filter with a *pass band*: with `μ` slightly more negative than
 * `λ` the transfer function is near 1 at low frequency and small at high frequency, so noise goes and
 * the shape stays. The default pair keeps the volume of a smoothed sphere to within about a percent
 * over tens of iterations, where plain [laplacianSmooth] would have visibly deflated it.
 *
 * @param lambda the shrinking step, `0 < λ < 1`.
 * @param mu the inflating step, which must be **negative and larger in magnitude** than λ.
 */
fun TriMesh.taubinSmooth(
    iterations: Int = 10,
    lambda: Double = 0.5,
    mu: Double = -0.53,
    cotangent: Boolean = false,
    preserveBoundary: Boolean = true,
): TriMesh {
    require(mu < 0.0) { "mu must be negative (it is the inflating pass), got $mu" }
    require(abs(mu) > lambda) { "|mu| must exceed lambda or the pair still shrinks; got lambda=$lambda mu=$mu" }
    if (iterations <= 0 || triangleCount == 0) return this
    val topo = topology()
    var pos = positions.copyOf()
    repeat(iterations) {
        pos = smoothingPass(this, topo, pos, lambda.toFloat(), cotangent, preserveBoundary)
        pos = smoothingPass(this, topo, pos, mu.toFloat(), cotangent, preserveBoundary)
    }
    return TriMesh(pos, indices, null, uvs, colors).withVertexNormals()
}

/** One `p += step * (weightedNeighbourAverage - p)` pass. Shared by both smoothers. */
private fun smoothingPass(
    mesh: TriMesh,
    topo: MeshTopology,
    pos: FloatArray,
    step: Float,
    cotangent: Boolean,
    preserveBoundary: Boolean,
): FloatArray {
    val n = mesh.vertexCount
    val out = pos.copyOf()
    val weights = if (cotangent) cotangentWeights(mesh, topo, pos) else null
    for (v in 0 until n) {
        if (preserveBoundary && v in topo.boundaryVertices) continue
        val ring = topo.neighbors(v)
        if (ring.isEmpty()) continue
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var total = 0.0
        for (w in ring) {
            val weight = if (weights == null) 1.0 else weights[topo.edgeOf(v, w)].coerceAtLeast(0.0)
            if (weight <= 0.0) continue
            sx += weight * pos[w * 3]; sy += weight * pos[w * 3 + 1]; sz += weight * pos[w * 3 + 2]
            total += weight
        }
        if (total <= 0.0) continue
        out[v * 3] = pos[v * 3] + step * ((sx / total).toFloat() - pos[v * 3])
        out[v * 3 + 1] = pos[v * 3 + 1] + step * ((sy / total).toFloat() - pos[v * 3 + 1])
        out[v * 3 + 2] = pos[v * 3 + 2] + step * ((sz / total).toFloat() - pos[v * 3 + 2])
    }
    return out
}

/**
 * `(cot α + cot β) / 2` per edge, where α and β are the angles opposite it in its two triangles —
 * the discrete Laplace-Beltrami weights.
 *
 * Indexed by [MeshTopology.edges] position. Boundary edges get the single available cotangent.
 */
private fun cotangentWeights(mesh: TriMesh, topo: MeshTopology, pos: FloatArray): DoubleArray {
    val w = DoubleArray(topo.edgeCount)
    fun cotAt(t: Int, a: Int, b: Int): Double {
        val c = opposite(mesh, t, a, b)
        val ux = (pos[a * 3] - pos[c * 3]).toDouble()
        val uy = (pos[a * 3 + 1] - pos[c * 3 + 1]).toDouble()
        val uz = (pos[a * 3 + 2] - pos[c * 3 + 2]).toDouble()
        val vx = (pos[b * 3] - pos[c * 3]).toDouble()
        val vy = (pos[b * 3 + 1] - pos[c * 3 + 1]).toDouble()
        val vz = (pos[b * 3 + 2] - pos[c * 3 + 2]).toDouble()
        val dot = ux * vx + uy * vy + uz * vz
        val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
        val crossLen = sqrt(cx * cx + cy * cy + cz * cz)
        return if (crossLen < 1e-18) 0.0 else dot / crossLen
    }
    for (e in 0 until topo.edgeCount) {
        val (a, b) = topo.edges[e]
        var sum = 0.0
        for (t in topo.edgeTriangles[e]) sum += cotAt(t, a, b)
        w[e] = sum / 2.0
    }
    return w
}

// ==============================================================================================
// Decimation
// ==============================================================================================

/**
 * The normalised shape measure below which [TriMesh.decimate] defers a collapse (see `shapeOf` in its
 * body). `0.25` corresponds to roughly a 10-degree minimum angle — loose enough not to fight the
 * error metric, tight enough to stop slivers.
 */
private const val SHAPE_VETO = 0.25

/**
 * How well-conditioned the summed quadric's 3x3 must be, relative to its own trace cubed, before
 * [TriMesh.decimate] trusts its minimum. A flat or cylindrical neighbourhood is *nearly* singular and
 * a permissive threshold accepts an "optimum" way down the null direction; measured on a 5120-triangle
 * sphere, `1e-10` let vertices drift 19% of the radius outward by 200 triangles.
 */
private const val QUADRIC_CONDITION = 1e-4

/**
 * How far from the collapsed edge's midpoint the quadric optimum may sit, as a multiple of the edge
 * length, before it is discarded in favour of an endpoint or the midpoint.
 */
private const val OPTIMUM_STRAY = 0.5

/**
 * Simplify to at most [targetTriangles] by quadric-error-metric edge collapse.
 *
 * Garland & Heckbert's algorithm: every vertex carries a 4x4 symmetric quadric summing the squared
 * distances to the planes of its (area-weighted) incident triangles; collapsing an edge adds the two
 * quadrics and places the survivor where that sum is minimised; edges are processed cheapest-first.
 * The result is the standard one — flat regions collapse away first, silhouettes and creases survive.
 *
 * Three guards, each of which matters:
 *
 *  - **Link condition.** A collapse is refused unless `u` and `v`'s common neighbours are exactly the
 *    corners opposite their shared triangles. Without it, collapsing across a shortcut edge silently
 *    creates a non-manifold vertex, and everything downstream (curvature, subdivision, another
 *    decimation pass) then misbehaves.
 *  - **Normal-flip veto.** A collapse is refused if it would rotate any surviving triangle's normal
 *    by more than 90°, which is what folds a mesh over on itself.
 *  - **Boundary planes.** Each boundary edge contributes a heavily-weighted virtual plane
 *    perpendicular to the surface, so an open mesh keeps its border curve instead of eroding it.
 *
 * Returns early — without reaching the target — when every remaining collapse is vetoed. That is
 * documented behaviour rather than a violated postcondition, so check [TriMesh.triangleCount] if the
 * exact budget matters. Normals are recomputed; uvs and colours are dropped.
 *
 * @param targetTriangles the budget. Values at or above the current count return the mesh unchanged.
 * @param maxError stop early if the cheapest available collapse costs more than this (in squared
 *   distance units). `null` means "spend whatever it takes to hit the target".
 */
fun TriMesh.decimate(targetTriangles: Int, maxError: Double? = null): TriMesh {
    require(targetTriangles >= 1) { "targetTriangles must be >= 1, got $targetTriangles" }
    if (triangleCount <= targetTriangles) return this

    val pos = DoubleArray(vertexCount * 3) { positions[it].toDouble() }
    val tri = indices.copyOf()
    val triAlive = BooleanArray(triangleCount) { true }
    val vertexAlive = BooleanArray(vertexCount) { true }
    val vertexTris = Array(vertexCount) { HashSet<Int>() }
    for (t in 0 until triangleCount) {
        vertexTris[tri[t * 3]].add(t); vertexTris[tri[t * 3 + 1]].add(t); vertexTris[tri[t * 3 + 2]].add(t)
    }
    val quadric = Array(vertexCount) { DoubleArray(10) }
    val version = IntArray(vertexCount)

    fun addPlaneQuadric(v: Int, a: Double, b: Double, c: Double, d: Double, weight: Double) {
        val q = quadric[v]
        q[0] += weight * a * a; q[1] += weight * a * b; q[2] += weight * a * c; q[3] += weight * a * d
        q[4] += weight * b * b; q[5] += weight * b * c; q[6] += weight * b * d
        q[7] += weight * c * c; q[8] += weight * c * d
        q[9] += weight * d * d
    }

    fun facePlane(t: Int): DoubleArray? {
        val a = tri[t * 3]; val b = tri[t * 3 + 1]; val c = tri[t * 3 + 2]
        val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
        val vx = pos[c * 3] - pos[a * 3]; val vy = pos[c * 3 + 1] - pos[a * 3 + 1]; val vz = pos[c * 3 + 2] - pos[a * 3 + 2]
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-18) return null
        val area = 0.5 * len
        nx /= len; ny /= len; nz /= len
        val d = -(nx * pos[a * 3] + ny * pos[a * 3 + 1] + nz * pos[a * 3 + 2])
        return doubleArrayOf(nx, ny, nz, d, area)
    }

    for (t in 0 until triangleCount) {
        val p = facePlane(t) ?: continue
        for (k in 0..2) addPlaneQuadric(tri[t * 3 + k], p[0], p[1], p[2], p[3], p[4])
    }

    // Boundary constraint planes: perpendicular to the surface along each border edge.
    val topo = topology()
    for (e in 0 until topo.edgeCount) {
        if (topo.edgeTriangles[e].size != 1) continue
        val (a, b) = topo.edges[e]
        val t = topo.edgeTriangles[e][0]
        val fp = facePlane(t) ?: continue
        val ex = pos[b * 3] - pos[a * 3]; val ey = pos[b * 3 + 1] - pos[a * 3 + 1]; val ez = pos[b * 3 + 2] - pos[a * 3 + 2]
        var nx = ey * fp[2] - ez * fp[1]
        var ny = ez * fp[0] - ex * fp[2]
        var nz = ex * fp[1] - ey * fp[0]
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-18) continue
        nx /= l; ny /= l; nz /= l
        val d = -(nx * pos[a * 3] + ny * pos[a * 3 + 1] + nz * pos[a * 3 + 2])
        // 1000x the face weight: heavy enough that the border is effectively pinned, finite so a
        // border vertex in a flat run can still slide along the border.
        val weight = 1000.0 * fp[4]
        addPlaneQuadric(a, nx, ny, nz, d, weight)
        addPlaneQuadric(b, nx, ny, nz, d, weight)
    }

    fun quadricError(q: DoubleArray, x: Double, y: Double, z: Double): Double =
        q[0] * x * x + 2 * q[1] * x * y + 2 * q[2] * x * z + 2 * q[3] * x +
            q[4] * y * y + 2 * q[5] * y * z + 2 * q[6] * y +
            q[7] * z * z + 2 * q[8] * z + q[9]

    /** `[cost, x, y, z]` for collapsing `(u, v)` — the quadric minimum, or the best of three fallbacks. */
    fun collapseTarget(u: Int, v: Int): DoubleArray {
        val q = DoubleArray(10) { quadric[u][it] + quadric[v][it] }
        // Solve A p = -b by Cramer's rule; A is the quadric's upper-left 3x3.
        val a11 = q[0]; val a12 = q[1]; val a13 = q[2]
        val a22 = q[4]; val a23 = q[5]; val a33 = q[7]
        val det = a11 * (a22 * a33 - a23 * a23) - a12 * (a12 * a33 - a23 * a13) + a13 * (a12 * a23 - a22 * a13)
        val scale = max(1e-12, abs(a11) + abs(a22) + abs(a33))
        val mx = (pos[u * 3] + pos[v * 3]) / 2
        val my = (pos[u * 3 + 1] + pos[v * 3 + 1]) / 2
        val mz = (pos[u * 3 + 2] + pos[v * 3 + 2]) / 2
        val edgeLength = sqrt(
            (pos[v * 3] - pos[u * 3]) * (pos[v * 3] - pos[u * 3]) +
                (pos[v * 3 + 1] - pos[u * 3 + 1]) * (pos[v * 3 + 1] - pos[u * 3 + 1]) +
                (pos[v * 3 + 2] - pos[u * 3 + 2]) * (pos[v * 3 + 2] - pos[u * 3 + 2]),
        )
        if (abs(det) > QUADRIC_CONDITION * scale * scale * scale) {
            val bx = -q[3]; val by = -q[6]; val bz = -q[8]
            val dx = bx * (a22 * a33 - a23 * a23) - a12 * (by * a33 - a23 * bz) + a13 * (by * a23 - a22 * bz)
            val dy = a11 * (by * a33 - a23 * bz) - bx * (a12 * a33 - a23 * a13) + a13 * (a12 * bz - by * a13)
            val dz = a11 * (a22 * bz - by * a23) - a12 * (a12 * bz - by * a13) + bx * (a12 * a23 - a22 * a13)
            val x = dx / det; val y = dy / det; val z = dz / det
            // A nearly-singular quadric — a flat or cylindrical neighbourhood — still passes the
            // determinant test but its "optimum" can be an arbitrary point far down the null direction,
            // where the error really is near zero and the *geometry* is ruined. Requiring the optimum to
            // stay within one edge length of the edge it replaces is what keeps a decimated sphere a
            // sphere; without it a 5120-to-200 pass wandered 12% of the radius off the surface.
            val strayed = sqrt((x - mx) * (x - mx) + (y - my) * (y - my) + (z - mz) * (z - mz)) > OPTIMUM_STRAY * edgeLength
            if (x.isFinite() && y.isFinite() && z.isFinite() && !strayed) {
                return doubleArrayOf(max(0.0, quadricError(q, x, y, z)), x, y, z)
            }
        }
        // Degenerate or rejected quadric optimum: take the best of the two ends and the midpoint.
        var best = doubleArrayOf(Double.MAX_VALUE, 0.0, 0.0, 0.0)
        for (cand in listOf(
            doubleArrayOf(pos[u * 3], pos[u * 3 + 1], pos[u * 3 + 2]),
            doubleArrayOf(pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]),
            doubleArrayOf(mx, my, mz),
        )) {
            val err = max(0.0, quadricError(q, cand[0], cand[1], cand[2]))
            if (err < best[0]) best = doubleArrayOf(err, cand[0], cand[1], cand[2])
        }
        return best
    }

    /**
     * One queued collapse. [deferred] marks a candidate that was already once rejected for producing
     * a badly-shaped triangle and re-queued at an inflated cost; a deferred candidate is accepted on
     * its second visit, which is what stops the quality veto from stalling the decimation short of its
     * budget.
     */
    class Candidate(
        val cost: Double,
        val u: Int, val v: Int,
        val vu: Int, val vv: Int,
        val x: Double, val y: Double, val z: Double,
        val deferred: Boolean = false,
    )
    val queue = PriorityQueue<Candidate>(max(16, indices.size)) { a, b -> a.cost.compareTo(b.cost) }

    fun push(u: Int, v: Int) {
        if (u == v || !vertexAlive[u] || !vertexAlive[v]) return
        val t = collapseTarget(u, v)
        queue.add(Candidate(t[0], u, v, version[u], version[v], t[1], t[2], t[3]))
    }

    /**
     * A triangle's normalised shape measure, `4 sqrt(3) A / (a^2 + b^2 + c^2)`: `1` for an equilateral
     * triangle, `0` for a degenerate one, and — unlike the minimum angle — computable without a
     * trigonometric function per triangle.
     */
    fun shapeOf(
        ax: Double, ay: Double, az: Double,
        bx: Double, by: Double, bz: Double,
        cx: Double, cy: Double, cz: Double,
    ): Double {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val twiceArea = sqrt(nx * nx + ny * ny + nz * nz)
        val sumSquares = (ux * ux + uy * uy + uz * uz) + (vx * vx + vy * vy + vz * vz) +
            ((cx - bx) * (cx - bx) + (cy - by) * (cy - by) + (cz - bz) * (cz - bz))
        if (sumSquares < 1e-30) return 0.0
        return 2.0 * sqrt(3.0) * twiceArea / sumSquares
    }

    fun neighborsOf(v: Int): Set<Int> {
        val out = HashSet<Int>()
        for (t in vertexTris[v]) {
            if (!triAlive[t]) continue
            for (k in 0..2) { val w = tri[t * 3 + k]; if (w != v) out.add(w) }
        }
        return out
    }

    for (t in 0 until triangleCount) {
        val a = tri[t * 3]; val b = tri[t * 3 + 1]; val c = tri[t * 3 + 2]
        if (a < b) push(a, b) else push(b, a)
        if (b < c) push(b, c) else push(c, b)
        if (c < a) push(c, a) else push(a, c)
    }

    var liveTriangles = triangleCount
    var guard = indices.size * 8 + 1024
    while (liveTriangles > targetTriangles && queue.isNotEmpty() && guard-- > 0) {
        val cand = queue.poll()
        val u = cand.u; val v = cand.v
        if (!vertexAlive[u] || !vertexAlive[v]) continue
        if (version[u] != cand.vu || version[v] != cand.vv) continue // stale: a fresher entry exists
        if (maxError != null && cand.cost > maxError) break

        val nu = neighborsOf(u); val nv = neighborsOf(v)
        if (v !in nu) continue // no longer an edge

        // Link condition: the shared neighbours must be exactly the opposite corners of the shared
        // triangles, or the collapse pinches the surface into a non-manifold vertex.
        val shared = nu.intersect(nv)
        val sharedTriangles = vertexTris[u].filter { triAlive[it] && v in intArrayOf(tri[it * 3], tri[it * 3 + 1], tri[it * 3 + 2]).toList() }
        val opposites = sharedTriangles.map { opposite(tri, it, u, v) }.toSet()
        if (shared != opposites) continue
        if (sharedTriangles.size > 2) continue

        // Two vetoes over every triangle that survives the collapse: it must not turn inside out, and
        // it must not become a sliver. The shape veto is what keeps a decimated sphere usable — on a
        // sphere every edge carries almost the same quadric error, so without it the queue order is
        // effectively arbitrary and the collapses cascade into long thin strips (measured: 3 degree
        // minimum angles and a 12%-of-radius deviation at 200 triangles).
        var flips = false
        var worstShape = 1.0
        for (t in (vertexTris[u] + vertexTris[v])) {
            if (!triAlive[t] || t in sharedTriangles) continue
            val a = tri[t * 3]; val b = tri[t * 3 + 1]; val c = tri[t * 3 + 2]
            fun px(i: Int) = if (i == u || i == v) cand.x else pos[i * 3]
            fun py(i: Int) = if (i == u || i == v) cand.y else pos[i * 3 + 1]
            fun pz(i: Int) = if (i == u || i == v) cand.z else pos[i * 3 + 2]
            val before = facePlane(t) ?: continue
            val ux = px(b) - px(a); val uy = py(b) - py(a); val uz = pz(b) - pz(a)
            val vx = px(c) - px(a); val vy = py(c) - py(a); val vz = pz(c) - pz(a)
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-18) { flips = true; break }
            if ((nx * before[0] + ny * before[1] + nz * before[2]) / l < 0.0) { flips = true; break }
            worstShape = min(
                worstShape,
                shapeOf(px(a), py(a), pz(a), px(b), py(b), pz(b), px(c), py(c), pz(c)),
            )
        }
        if (flips) continue
        if (!cand.deferred && worstShape < SHAPE_VETO) {
            // Send it to the back of the queue rather than dropping it: if nothing better is ever
            // available, its second visit is accepted and the budget is still met.
            queue.add(Candidate(cand.cost * 1e6 + 1e-9, u, v, cand.vu, cand.vv, cand.x, cand.y, cand.z, deferred = true))
            continue
        }

        // ---- commit
        pos[u * 3] = cand.x; pos[u * 3 + 1] = cand.y; pos[u * 3 + 2] = cand.z
        for (i in 0..9) quadric[u][i] += quadric[v][i]
        for (t in sharedTriangles) { triAlive[t] = false; liveTriangles-- }
        for (t in vertexTris[v].toList()) {
            if (!triAlive[t]) continue
            for (k in 0..2) if (tri[t * 3 + k] == v) tri[t * 3 + k] = u
            vertexTris[u].add(t)
        }
        vertexTris[v].clear()
        vertexAlive[v] = false
        // Only `u` and `v` changed — `u` moved and absorbed `v`'s quadric — so only queue entries that
        // mention them are stale, and the version stamps on those two are enough to retire them. Bumping
        // the *neighbours*' versions as well (which an earlier draft did) retires entries like `(w, z)`
        // that are still perfectly valid and are never re-pushed, so the queue drains and the decimation
        // is forced into whatever is left: measured, that gave a 200-triangle "sphere" with a 1.96-long
        // edge and a 27% radial deviation.
        version[u]++
        for (w in neighborsOf(u)) push(min(u, w), max(u, w))
    }

    // ---- rebuild
    val builder = TriMeshBuilder()
    val remap = IntArray(vertexCount) { -1 }
    for (t in 0 until triangleCount) {
        if (!triAlive[t]) continue
        val corners = IntArray(3) { k ->
            val v = tri[t * 3 + k]
            if (remap[v] < 0) remap[v] = builder.vertex(pos[v * 3].toFloat(), pos[v * 3 + 1].toFloat(), pos[v * 3 + 2].toFloat())
            remap[v]
        }
        if (corners[0] == corners[1] || corners[1] == corners[2] || corners[0] == corners[2]) continue
        builder.triangle(corners[0], corners[1], corners[2])
    }
    return builder.build().withVertexNormals()
}

/** The corner of triangle [t] in the flat index array [tri] that is neither [a] nor [b]. */
private fun opposite(tri: IntArray, t: Int, a: Int, b: Int): Int {
    val ta = tri[t * 3]; val tb = tri[t * 3 + 1]; val tc = tri[t * 3 + 2]
    return when {
        ta != a && ta != b -> ta
        tb != a && tb != b -> tb
        else -> tc
    }
}

// ==============================================================================================
// Quality
// ==============================================================================================

/**
 * How well-shaped a mesh's triangles are — the number to look at before and after a remesh, a
 * decimation or a marching pass.
 *
 * @param minAngleDegrees the smallest interior angle anywhere. 60° is a perfectly equilateral mesh;
 *   below about 5° the mesh is numerically unpleasant for anything solving on it.
 * @param meanAspectRatio mean of `circumradius / (2 * inradius)`, which is `1` for an equilateral
 *   triangle and grows without bound for a sliver.
 * @param worstAspectRatio the worst single triangle's ratio.
 * @param degenerateCount triangles with no area at all.
 */
data class MeshQuality(
    /** The smallest interior angle anywhere. 60 degrees is perfectly equilateral; below about 5 the mesh is numerically unpleasant. */
    val minAngleDegrees: Double,
    /** Mean over triangles of each triangle's own smallest angle — a better summary than the single worst one. */
    val meanMinAngleDegrees: Double,
    /** Mean of `circumradius / (2 * inradius)`: `1` for an equilateral triangle, unbounded for a sliver. */
    val meanAspectRatio: Double,
    /** The worst single triangle's aspect ratio. */
    val worstAspectRatio: Double,
    /** Triangles with no area at all. Legal — a UV sphere has none, a marched surface can. */
    val degenerateCount: Int,
) {
    /** All five numbers on one line, for `println`. */
    override fun toString(): String =
        "MeshQuality(minAngle=%.2f°, meanMinAngle=%.2f°, aspect mean=%.3f worst=%.3f, degenerate=%d)".format(
            minAngleDegrees, meanMinAngleDegrees, meanAspectRatio, worstAspectRatio, degenerateCount,
        )
}

/** Measure this mesh's triangle shapes — see [MeshQuality]. */
fun TriMesh.quality(): MeshQuality {
    if (triangleCount == 0) return MeshQuality(0.0, 0.0, 0.0, 0.0, 0)
    var minAngle = 180.0
    var angleSum = 0.0
    var aspectSum = 0.0
    var worstAspect = 0.0
    var degenerate = 0
    var counted = 0
    for (t in 0 until triangleCount) {
        val (pa, pb, pc) = triangle(t)
        val la = pb.distance(pc).toDouble()
        val lb = pc.distance(pa).toDouble()
        val lc = pa.distance(pb).toDouble()
        val area = triangleArea(t)
        if (area <= 1e-16 || la <= 0.0 || lb <= 0.0 || lc <= 0.0) { degenerate++; continue }
        counted++
        fun angle(opposite: Double, x: Double, y: Double): Double {
            val c = ((x * x + y * y - opposite * opposite) / (2 * x * y)).coerceIn(-1.0, 1.0)
            return acos(c) * 180.0 / PI
        }
        val smallest = minOf(angle(la, lb, lc), angle(lb, lc, la), angle(lc, la, lb))
        minAngle = min(minAngle, smallest)
        angleSum += smallest
        // circumradius / (2 * inradius) = abc / (8 * area^2 / s) ... written directly:
        val s = (la + lb + lc) / 2.0
        val inradius = area / s
        val circumradius = la * lb * lc / (4.0 * area)
        val aspect = circumradius / (2.0 * inradius)
        aspectSum += aspect
        worstAspect = max(worstAspect, aspect)
    }
    if (counted == 0) return MeshQuality(0.0, 0.0, 0.0, 0.0, degenerate)
    return MeshQuality(minAngle, angleSum / counted, aspectSum / counted, worstAspect, degenerate)
}
