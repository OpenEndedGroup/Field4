@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.solid.manifold

import afield.math.linalg.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Moving some faces of a solid inward and leaving the rest where they are: a clearance between parts
 * that meet, without thinning them.
 *
 * [insetFaces] backs every face off by a distance, except the faces parallel to planes you name. Edges
 * and corners stay sharp, and the faces you keep stay exactly where they were, growing or shrinking
 * to meet the faces that moved.
 */

/**
 * This solid with every face moved [distance] inward along its own normal, except the faces parallel
 * to one of the planes whose normals are in [except].
 *
 * Two panels that meet along their edges can be given a gap that way without touching their
 * thickness: keep the faces parallel to the panel, and move the rest.
 *
 * ```kotlin
 * val up = vec(0, 1, 0)
 * val panel = Solid.cube(4.0, 0.1, 3.0)
 * val clear = panel.insetFaces(0.002, except = [up])   // 3.996 x 0.1 x 2.996
 * ```
 *
 * A face counts as parallel to a plane when its normal is within [toleranceDegrees] of that plane's
 * normal, in either direction, so a panel's top and bottom are kept by the one normal. The result is
 * what moving the planes would give: each moved face's plane shifts inward by [distance], each kept
 * face's plane stays put, and every vertex goes to where its planes now meet. A convex edge between
 * two moved faces stays sharp, a concave one does too, and a kept face simply extends or shrinks to
 * meet the faces beside it. Two parts that touched along moved faces end up `2 * distance` apart.
 *
 * [distance] has to be smaller than the part's smallest feature across a moved face, or faces would
 * pass through each other; the same is true of any inward offset. A vertex where more than three
 * planes meet and stop meeting at one point once moved, the apex of a pyramid say, stays on every
 * kept plane and goes as near as it can to the moved ones.
 *
 * @param distance how far to move each face, in the part's units; positive moves it inward.
 * @param except normals of the planes whose parallel faces stay where they are.
 * @param toleranceDegrees how far a face's normal may be from one of [except] and still count as parallel.
 */
fun Solid.insetFaces(distance: Double, except: List<Vec3>, toleranceDegrees: Double = 1.0): Solid {
    require(distance.isFinite() && distance >= 0.0) { "an inset needs a non-negative distance, got $distance" }
    require(toleranceDegrees in 0.0..90.0) { "the tolerance is an angle in degrees, got $toleranceDegrees" }
    val keep = except.map { n ->
        val x = n.x.toDouble(); val y = n.y.toDouble(); val z = n.z.toDouble()
        val length = sqrt(x * x + y * y + z * z)
        require(length > 0.0 && length.isFinite()) { "a plane normal must be a non-zero vector, got $n" }
        doubleArrayOf(x / length, y / length, z / length)
    }
    val parallel = cos(toleranceDegrees * PI / 180.0)
    return insetFaces(distance) { normal ->
        keep.none { k -> abs(k[0] * normal[0] + k[1] * normal[1] + k[2] * normal[2]) >= parallel }
    }
}

/**
 * This solid with the faces [move] picks moved [distance] inward along their normals; the others stay.
 *
 * [move] is asked once per plane of the solid, with that plane's outward unit normal as `[x, y, z]`.
 * Everything else is as in the overload that takes a list of normals to keep.
 */
fun Solid.insetFaces(distance: Double, move: (DoubleArray) -> Boolean): Solid = insetFaces(distance, 1e-7, move)

internal fun Solid.insetFaces(distance: Double, liftFraction: Double, move: (DoubleArray) -> Boolean): Solid {
    require(distance.isFinite() && distance >= 0.0) { "an inset needs a non-negative distance, got $distance" }
    if (isEmpty || distance == 0.0) return translated(0.0, 0.0, 0.0)
    val mesh = toPlanarMesh()
    val p = mesh.positions
    val tri = mesh.indices
    val triangles = mesh.triangleCount
    val vertices = mesh.vertexCount

    var extent = 0.0
    for (axis in 0 until 3) {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (v in 0 until vertices) { lo = minOf(lo, p[v * 3 + axis]); hi = maxOf(hi, p[v * 3 + axis]) }
        extent = max(extent, hi - lo)
    }

    // Each face group's plane, from its triangles' area-weighted normal, then merged with any other
    // group on the same plane: Boolean results keep coplanar patches from different inputs apart, and
    // parts unioned from separately computed pieces can put "the same" plane a hair apart. Planes
    // closer than a thousandth of the inset are treated as one.
    val planes = ArrayList<DoubleArray>()          // [nx, ny, nz, c] with n . x = c
    val planeOfTriangle = IntArray(triangles) { -1 }
    val sameNormal = cos(1e-5)
    val sameOffset = max(1e-9 * extent, 1e-3 * distance)
    for (face in mesh.faces) {
        var nx = 0.0; var ny = 0.0; var nz = 0.0; var cx = 0.0; var cy = 0.0; var cz = 0.0; var area = 0.0
        for (t in face.triangles) {
            val a = tri[t * 3] * 3; val b = tri[t * 3 + 1] * 3; val c = tri[t * 3 + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val tx = uy * vz - uz * vy; val ty = uz * vx - ux * vz; val tz = ux * vy - uy * vx
            val w = sqrt(tx * tx + ty * ty + tz * tz)
            nx += tx; ny += ty; nz += tz; area += w
            cx += w * (p[a] + p[b] + p[c]) / 3; cy += w * (p[a + 1] + p[b + 1] + p[c + 1]) / 3; cz += w * (p[a + 2] + p[b + 2] + p[c + 2]) / 3
        }
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length == 0.0 || area == 0.0) continue
        nx /= length; ny /= length; nz /= length
        val offset = (nx * cx + ny * cy + nz * cz) / area
        var id = planes.indexOfFirst { q -> q[0] * nx + q[1] * ny + q[2] * nz > sameNormal && abs(q[3] - offset) < sameOffset }
        if (id < 0) { id = planes.size; planes += doubleArrayOf(nx, ny, nz, offset) }
        for (t in face.triangles) planeOfTriangle[t] = id
    }
    val moved = BooleanArray(planes.size) { move(planes[it].copyOfRange(0, 3)) }
    if (moved.none { it }) return translated(0.0, 0.0, 0.0)

    // Where each vertex goes: the least displacement that puts it on every one of its planes after
    // the moved ones shift. With three independent planes that is their new meeting point; with two
    // it slides only across the edge; with one it moves straight along the normal; with more that do
    // not meet again, it is a least-squares compromise in which the kept planes outweigh the moved
    // ones a million to one, so a kept face never drifts off its plane to make the others fit. All
    // of these are the pseudo-inverse of the weighted N^T N.
    val incident = Array(vertices) { HashSet<Int>(4) }
    for (t in 0 until triangles) {
        val plane = planeOfTriangle[t]
        if (plane < 0) continue
        for (k in 0 until 3) incident[tri[t * 3 + k]] += plane
    }
    val shifted = DoubleArray(vertices * 3)
    for (v in 0 until vertices) {
        val m = DoubleArray(9)
        val rhs = DoubleArray(3)
        for (id in incident[v]) {
            val q = planes[id]
            val residual = (if (moved[id]) -distance else 0.0)
            val weight = if (moved[id]) 1.0 else KEPT_PLANE_WEIGHT
            for (i in 0 until 3) {
                rhs[i] += weight * q[i] * residual
                for (j in 0 until 3) m[i * 3 + j] += weight * q[i] * q[j]
            }
        }
        val delta = pseudoSolve3(m, rhs)
        for (i in 0 until 3) shifted[v * 3 + i] = p[v * 3 + i] + delta[i]
    }

    // What leaves: for each triangle of a moved face, the hull of where it was and where it went.
    // No face of that hull may coincide with a face of the solid, or the cut leaves a zero-thickness
    // skin there, so corners are pushed a hair outward. An old corner goes out across all of its
    // planes, which always lands outside the solid. A new corner goes out across a kept plane only
    // where it slides over that plane's existing face, at a convex edge. At a concave edge it slides
    // past the face's end into material, the kept face grows to meet it, and a push would leave a
    // hairline strip of the moved face behind.
    val lift = liftFraction * extent
    val trianglesAt = Array(vertices) { ArrayList<Int>(6) }
    for (t in 0 until triangles) for (k in 0 until 3) trianglesAt[tri[t * 3 + k]] += t
    val outward = DoubleArray(vertices * 3)
    val alongKept = DoubleArray(vertices * 3)
    for (v in 0 until vertices) for (id in incident[v]) {
        for (i in 0 until 3) outward[v * 3 + i] += planes[id][i] * lift
        if (moved[id]) continue
        val probe = DoubleArray(3) { i -> p[v * 3 + i] + 1e-3 * (shifted[v * 3 + i] - p[v * 3 + i]) }
        val over = trianglesAt[v].any { t -> planeOfTriangle[t] == id && insideTriangle(p, tri, t, probe) }
        if (over) for (i in 0 until 3) alongKept[v * 3 + i] += planes[id][i] * lift
    }
    val pieces = ArrayList<Solid>()
    for (t in 0 until triangles) {
        val plane = planeOfTriangle[t]
        if (plane < 0 || !moved[plane]) continue
        val corners = DoubleArray(18)
        for (k in 0 until 3) {
            val v = tri[t * 3 + k]
            for (i in 0 until 3) {
                corners[k * 3 + i] = p[v * 3 + i] + outward[v * 3 + i]
                corners[9 + k * 3 + i] = shifted[v * 3 + i] + alongKept[v * 3 + i]
            }
        }
        val piece = Solid.hullOfPointsD(corners)
        if (piece.isEmpty) piece.close() else pieces += piece
    }
    if (pieces.isEmpty()) return translated(0.0, 0.0, 0.0)
    val removed = Solid.union(pieces)
    pieces.forEach { it.close() }
    val cut = this - removed
    removed.close()
    // Where two faces of the solid touch (two pieces of one part, face to face) or nearly coincide,
    // the cut can leave a zero-thickness film behind, always as a piece of its own. No piece of the
    // part is thinner than a millionth of its size, so those are dropped and the rest kept whole.
    val fragments = cut.decompose()
    val kept = fragments.filter { it.volume > 1e-6 * extent * it.surfaceArea }
    if (kept.size == fragments.size) {
        fragments.forEach { it.close() }
        return cut
    }
    cut.close()
    val result = Solid.union(kept)
    fragments.forEach { it.close() }
    return result
}

/** How much more a kept plane counts than a moved one where a vertex cannot satisfy them all. */
private const val KEPT_PLANE_WEIGHT = 1e6

/** Whether [q], which lies in triangle [t]'s plane, falls inside it (edges included). */
private fun insideTriangle(p: DoubleArray, tri: IntArray, t: Int, q: DoubleArray): Boolean {
    val a = tri[t * 3] * 3; val b = tri[t * 3 + 1] * 3; val c = tri[t * 3 + 2] * 3
    fun cross(o: Int, u: Int): DoubleArray {
        val ux = p[u] - p[o]; val uy = p[u + 1] - p[o + 1]; val uz = p[u + 2] - p[o + 2]
        val vx = q[0] - p[o]; val vy = q[1] - p[o + 1]; val vz = q[2] - p[o + 2]
        return doubleArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
    }
    val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
    val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
    val n = doubleArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
    val scale = n[0] * n[0] + n[1] * n[1] + n[2] * n[2]
    if (scale == 0.0) return false
    for ((o, u) in [a to b, b to c, c to a]) {
        val w = cross(o, u)
        if (w[0] * n[0] + w[1] * n[1] + w[2] * n[2] < -1e-12 * scale) return false
    }
    return true
}

/**
 * The minimum-norm least-squares solution of the symmetric positive semi-definite 3x3 system [m] x = [b].
 *
 * Diagonalised by Jacobi rotations, then inverted only along the eigenvectors whose eigenvalue is not
 * negligible, so a rank-one or rank-two [m] (a vertex on one or two planes) moves nowhere along the
 * directions those planes leave free.
 */
private fun pseudoSolve3(m: DoubleArray, b: DoubleArray): DoubleArray {
    val a = m.copyOf()
    val vectors = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    repeat(50) {
        var p = 0; var q = 1
        var largest = abs(a[1])
        if (abs(a[2]) > largest) { p = 0; q = 2; largest = abs(a[2]) }
        if (abs(a[5]) > largest) { p = 1; q = 2; largest = abs(a[5]) }
        if (largest < 1e-300) return@repeat
        val app = a[p * 3 + p]; val aqq = a[q * 3 + q]; val apq = a[p * 3 + q]
        val theta = (aqq - app) / (2 * apq)
        val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
        val c = 1 / sqrt(t * t + 1); val s = t * c
        for (k in 0 until 3) {
            val akp = a[k * 3 + p]; val akq = a[k * 3 + q]
            a[k * 3 + p] = c * akp - s * akq; a[k * 3 + q] = s * akp + c * akq
        }
        for (k in 0 until 3) {
            val apk = a[p * 3 + k]; val aqk = a[q * 3 + k]
            a[p * 3 + k] = c * apk - s * aqk; a[q * 3 + k] = s * apk + c * aqk
        }
        for (k in 0 until 3) {
            val vkp = vectors[k * 3 + p]; val vkq = vectors[k * 3 + q]
            vectors[k * 3 + p] = c * vkp - s * vkq; vectors[k * 3 + q] = s * vkp + c * vkq
        }
    }
    val top = maxOf(a[0], a[4], a[8])
    val x = DoubleArray(3)
    if (top <= 0.0) return x
    for (e in 0 until 3) {
        val lambda = a[e * 3 + e]
        if (lambda <= 1e-9 * top) continue
        val along = (vectors[e] * b[0] + vectors[3 + e] * b[1] + vectors[6 + e] * b[2]) / lambda
        for (i in 0 until 3) x[i] += along * vectors[i * 3 + e]
    }
    return x
}
