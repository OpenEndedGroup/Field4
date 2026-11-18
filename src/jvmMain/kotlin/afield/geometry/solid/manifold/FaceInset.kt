@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.solid.manifold

import afield.math.linalg.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Moving some faces of a solid inward and leaving the rest where they are: a clearance between parts
 * that meet, without thinning them.
 *
 * [insetFaces] backs every face off by a distance, except the faces parallel to planes you name, or
 * only those lying in them. Edges and corners stay sharp, and the faces you keep stay exactly where
 * they were, growing or shrinking to meet the faces that moved.
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
 * planes meet and stop meeting at one point once moved opens into an edge: move one side of a
 * pyramid, or two opposite sides, and its apex becomes a short ridge, exactly where the planes now
 * meet. That holds where the solid around the vertex is a convex corner or a pit; at a saddle, or
 * where the inset is large against the faces around the vertex, it stays on every kept plane and
 * goes as near as it can to the moved ones.
 *
 * Pieces of the solid that touch face to face are [fused] first. A union of cells that share faces
 * can leave them as separate pieces with a zero-width crack between, and the walls of a crack are not
 * faces of the part.
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
    return insetFaces(distance) { plane ->
        keep.none { k -> abs(k[0] * plane[0] + k[1] * plane[1] + k[2] * plane[2]) >= parallel }
    }
}

/**
 * A plane given by a `point` on it and its `normal`, for [insetFaces]'s `exceptOn`. Which way the
 * normal points does not matter there: a face on the plane is kept whichever side its material is on.
 */
data class FacePlane(val point: Vec3, val normal: Vec3)

/**
 * This solid with every face moved [distance] inward, except the faces lying on one of the planes in
 * [exceptOn].
 *
 * The overload taking `except` keeps every face *parallel* to a plane; this one keeps only the faces
 * *in* it. Faces parallel to a plane but somewhere else, a step part-way through a panel or a panel's
 * other side, move like any other face.
 *
 * ```kotlin
 * val panel = Solid.cube(4.0, 0.1, 3.0)
 * val top = FacePlane(vec(0, 0.1, 0), vec(0, 1, 0))
 * val clear = panel.insetFaces(0.002, exceptOn = [top])   // 3.996 x 0.098 x 2.996, still up to y = 0.1
 * ```
 *
 * A face is on a plane when its normal is within [toleranceDegrees] of the plane's, either way round,
 * and the face's plane passes within [toleranceDistance] of [FacePlane.point] along that normal. The
 * default, a tenth of [distance], absorbs the rounding a Boolean leaves in a face's position, while
 * keeping apart faces that are as far from each other as the gap being cut. Everything else is as in
 * the overload that takes normals.
 *
 * @param distance how far to move each face, in the part's units; positive moves it inward.
 * @param exceptOn the planes whose faces stay where they are.
 * @param toleranceDegrees how far a face's normal may be from a plane's and still count as on it.
 * @param toleranceDistance how far a face's plane may be from [FacePlane.point] and still count as on it.
 */
fun Solid.insetFaces(
    distance: Double,
    exceptOn: List<FacePlane>,
    toleranceDegrees: Double = 1.0,
    toleranceDistance: Double = 0.1 * distance,
): Solid {
    require(distance.isFinite() && distance >= 0.0) { "an inset needs a non-negative distance, got $distance" }
    require(toleranceDegrees in 0.0..90.0) { "the tolerance is an angle in degrees, got $toleranceDegrees" }
    require(toleranceDistance.isFinite() && toleranceDistance >= 0.0) { "the distance tolerance must be non-negative, got $toleranceDistance" }
    val keep = exceptOn.map { (point, n) ->
        val x = n.x.toDouble(); val y = n.y.toDouble(); val z = n.z.toDouble()
        val length = sqrt(x * x + y * y + z * z)
        require(length > 0.0 && length.isFinite()) { "a plane normal must be a non-zero vector, got $n" }
        val offset = (x * point.x + y * point.y + z * point.z) / length
        require(offset.isFinite()) { "a plane's point must be finite, got $point" }
        doubleArrayOf(x / length, y / length, z / length, offset)
    }
    val parallel = cos(toleranceDegrees * PI / 180.0)
    return insetFaces(distance) { plane ->
        keep.none { k ->
            val cosine = k[0] * plane[0] + k[1] * plane[1] + k[2] * plane[2]
            // The face's plane is n . x = c; with n flipped against k, the same plane is -n . x = -c.
            abs(cosine) >= parallel && abs(if (cosine > 0) plane[3] - k[3] else plane[3] + k[3]) <= toleranceDistance
        }
    }
}

/**
 * This solid with the faces [move] picks moved [distance] inward along their normals; the others stay.
 *
 * [move] is asked once per plane of the solid, with that plane as `[x, y, z, c]`: its outward unit
 * normal, and its offset along it, so the plane is the points where `x * px + y * py + z * pz = c`.
 * Everything else is as in the overload that takes a list of normals to keep.
 */
fun Solid.insetFaces(distance: Double, move: (DoubleArray) -> Boolean): Solid = insetFaces(distance, 1e-7, move)

internal fun Solid.insetFaces(distance: Double, liftFraction: Double, move: (DoubleArray) -> Boolean): Solid {
    require(distance.isFinite() && distance >= 0.0) { "an inset needs a non-negative distance, got $distance" }
    if (isEmpty || distance == 0.0) return translated(0.0, 0.0, 0.0)
    // The walls of a crack between pieces that touch face to face are not faces of the part: fused,
    // they are gone, and do not move or pull their corners two ways.
    val whole = fused()
    try {
        return whole.insetFused(distance, liftFraction, move)
    } finally {
        whole.close()
    }
}

private fun Solid.insetFused(distance: Double, liftFraction: Double, move: (DoubleArray) -> Boolean): Solid {
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
    val moved = BooleanArray(planes.size) { move(planes[it].copyOf()) }
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
    fun displacement(ids: Collection<Int>): DoubleArray {
        val m = DoubleArray(9)
        val rhs = DoubleArray(3)
        for (id in ids) {
            val q = planes[id]
            val residual = (if (moved[id]) -distance else 0.0)
            val weight = if (moved[id]) 1.0 else KEPT_PLANE_WEIGHT
            for (i in 0 until 3) {
                rhs[i] += weight * q[i] * residual
                for (j in 0 until 3) m[i * 3 + j] += weight * q[i] * q[j]
            }
        }
        return pseudoSolve3(m, rhs)
    }
    val shifted = DoubleArray(vertices * 3)
    for (v in 0 until vertices) {
        val delta = displacement(incident[v])
        for (i in 0 until 3) shifted[v * 3 + i] = p[v * 3 + i] + delta[i]
    }

    // Where the compromise would be wrong, the corner is cut exactly instead (see exactCorners), and
    // the triangles there are trimmed back to where their own corners are exact.
    val exact = exactCorners(p, tri, planes, moved, incident, shifted, distance, extent)

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

    // A point on edge (v, a) at an exact corner v's cut distance from it, with where it goes and its
    // pushes, worked out as for a vertex from the one or two planes along that edge.
    val edgeTriangles = HashMap<Long, ArrayList<Int>>()
    if (exact.isNotEmpty()) for (t in 0 until triangles) for (k in 0 until 3) {
        val a = tri[t * 3 + k]; val b = tri[t * 3 + (k + 1) % 3]
        edgeTriangles.getOrPut(min(a, b).toLong() * vertices + max(a, b)) { ArrayList(2) } += t
    }
    class EdgePoint(val at: DoubleArray, val goes: DoubleArray)
    val edgePoints = HashMap<Long, EdgePoint>()
    fun edgePoint(v: Int, a: Int): EdgePoint = edgePoints.getOrPut(v.toLong() * vertices + a) {
        val cut = exact.getValue(v).cut
        val length = sqrt((0 until 3).sumOf { i -> (p[a * 3 + i] - p[v * 3 + i]).let { it * it } })
        val point = DoubleArray(3) { i -> p[v * 3 + i] + cut / length * (p[a * 3 + i] - p[v * 3 + i]) }
        val along = edgeTriangles[min(v, a).toLong() * vertices + max(v, a)].orEmpty()
        val ids = along.map { planeOfTriangle[it] }.filter { it >= 0 }.toSet()
        val delta = displacement(ids)
        val goes = DoubleArray(3) { i -> point[i] + delta[i] }
        val at = point.copyOf()
        for (id in ids) {
            for (i in 0 until 3) at[i] += planes[id][i] * lift
            if (moved[id]) continue
            val probe = DoubleArray(3) { i -> point[i] + 1e-3 * delta[i] }
            if (along.any { t -> planeOfTriangle[t] == id && insideTriangle(p, tri, t, probe) }) for (i in 0 until 3) goes[i] += planes[id][i] * lift
        }
        EdgePoint(at, goes)
    }

    val pieces = ArrayList<Solid>()
    fun prism(points: List<Pair<DoubleArray, DoubleArray>>) {
        val corners = DoubleArray(points.size * 6)
        for ((k, point) in points.withIndex()) for (i in 0 until 3) {
            corners[k * 3 + i] = point.first[i]
            corners[points.size * 3 + k * 3 + i] = point.second[i]
        }
        val piece = Solid.hullOfPointsD(corners)
        if (piece.isEmpty) piece.close() else pieces += piece
    }
    for (t in 0 until triangles) {
        val plane = planeOfTriangle[t]
        if (plane < 0 || !moved[plane]) continue
        val ring = ArrayList<Pair<DoubleArray, DoubleArray>>(6)
        for (k in 0 until 3) {
            val v = tri[t * 3 + k]
            if (v in exact) {
                // The corner itself is left to the exact cut; the rest of the triangle runs from the
                // points where the cut crosses its two edges.
                for (other in [tri[t * 3 + (k + 2) % 3], tri[t * 3 + (k + 1) % 3]]) edgePoint(v, other).let { ring += it.at to it.goes }
            } else {
                ring += DoubleArray(3) { i -> p[v * 3 + i] + outward[v * 3 + i] } to DoubleArray(3) { i -> shifted[v * 3 + i] + alongKept[v * 3 + i] }
            }
        }
        if (ring.size == 3) prism(ring) else for (k in 1 until ring.size - 1) prism([ring[0], ring[k], ring[k + 1]])
    }
    pieces += exact.values.map { it.removal }
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

/** A corner cut exactly: how far along its edges the triangles there are trimmed back, and what leaves around it. */
private class ExactCorner(val cut: Double, val removal: Solid)

/**
 * The vertices where more than three planes meet and stop meeting at one point once moved, the apex
 * of a pyramid with some of its sides moved say, and for each the exact material to remove around
 * it. A least-squares compromise there would leave material on some moved planes and cut into
 * others; the exact answer opens the vertex into an edge.
 *
 * Inside a cube about the vertex that reaches no face not touching the vertex, the solid is the cone
 * of its own planes. Where that cone is convex, what remains is the cube inside every plane in its
 * new place, so what leaves is the cube beyond any moved plane; where it is the complement of a
 * convex cone, a pit, the pit grows to the planes' new places. A vertex of any other kind (a saddle),
 * or one whose new corners would not fall well inside the cube because the inset is large against the
 * faces around it, keeps the compromise.
 */
private fun Solid.exactCorners(
    p: DoubleArray, tri: IntArray, planes: List<DoubleArray>, moved: BooleanArray,
    incident: Array<HashSet<Int>>, shifted: DoubleArray, distance: Double, extent: Double,
): Map<Int, ExactCorner> {
    val corners = HashMap<Int, ExactCorner>()
    val missed = 1e-6 * distance + 1e-12 * extent
    fun offsetNow(id: Int) = planes[id][3] - if (moved[id]) distance else 0.0
    for (v in incident.indices) {
        val ids = incident[v].toList()
        if (ids.size < 4) continue
        val off = ids.maxOf { id ->
            val q = planes[id]
            abs((0 until 3).sumOf { i -> q[i] * shifted[v * 3 + i] } - offsetNow(id))
        }
        if (off <= missed) continue

        var clear = Double.MAX_VALUE
        val at = DoubleArray(3) { p[v * 3 + it] }
        for (t in 0 until tri.size / 3) {
            if (tri[t * 3] == v || tri[t * 3 + 1] == v || tri[t * 3 + 2] == v) continue
            clear = min(clear, distanceToTriangle(at, p, tri, t))
        }
        val half = 0.9 * (clear - distance) / sqrt(3.0)
        if (half <= 0.0 || clear == Double.MAX_VALUE) continue

        val cube = Solid.cube(2 * half, center = true).translated(at[0], at[1], at[2])
        val local = this and cube
        val tolerance = 1e-6 * 8 * half * half * half
        fun cut(keepInside: Boolean, offset: (Int) -> Double, of: List<Int> = ids): Solid {
            var s = cube.translated(0.0, 0.0, 0.0)
            for (id in of) {
                val q = planes[id]
                val sign = if (keepInside) -1.0 else 1.0
                s = s.trimByPlane(sign * q[0], sign * q[1], sign * q[2], sign * offset(id)).also { s.close() }
            }
            return s
        }
        fun same(a: Solid, b: Solid): Boolean = (a and b).use { both -> abs(a.volume - both.volume) < tolerance && abs(b.volume - both.volume) < tolerance }
        val cone = cut(true, { planes[it][3] })
        val pit = cut(false, { planes[it][3] })
        val convex = same(local, cone)
        val concave = !convex && (local and pit).use { it.volume < tolerance } && abs(local.volume + pit.volume - cube.volume) < tolerance
        cone.close(); pit.close(); local.close()

        // The new corners: where three planes in their new places meet, on the material's boundary.
        var reach = 0.0
        var found = false
        for (i in ids.indices) for (j in i + 1 until ids.size) for (k in j + 1 until ids.size) {
            val x = meet(planes[ids[i]], planes[ids[j]], planes[ids[k]], offsetNow(ids[i]), offsetNow(ids[j]), offsetNow(ids[k])) ?: continue
            val inside = ids.all { id -> val s = (0 until 3).sumOf { c -> planes[id][c] * x[c] } - offsetNow(id); if (convex) s <= missed else s >= -missed }
            if (!inside) continue
            found = true
            reach = max(reach, (0 until 3).maxOf { c -> abs(x[c] - at[c]) })
        }
        if (!(convex || concave) || !found || reach > 0.5 * half) { cube.close(); continue }

        val removal = if (convex) {
            val beyond = ids.filter { moved[it] }.map { id -> cut(false, { offsetNow(it) }, [id]) }
            Solid.union(beyond).also { beyond.forEach(Solid::close) }
        } else {
            cut(false, { offsetNow(it) })
        }
        cube.close()
        corners[v] = ExactCorner(half / 3, removal)
    }
    return corners
}

/** Where the planes `a . x = ca`, `b . x = cb` and `c . x = cc` meet, or null if they do not meet at one point. */
private fun meet(a: DoubleArray, b: DoubleArray, c: DoubleArray, ca: Double, cb: Double, cc: Double): DoubleArray? {
    val bc = doubleArrayOf(b[1] * c[2] - b[2] * c[1], b[2] * c[0] - b[0] * c[2], b[0] * c[1] - b[1] * c[0])
    val ca2 = doubleArrayOf(c[1] * a[2] - c[2] * a[1], c[2] * a[0] - c[0] * a[2], c[0] * a[1] - c[1] * a[0])
    val ab = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    val det = a[0] * bc[0] + a[1] * bc[1] + a[2] * bc[2]
    if (abs(det) < 1e-9) return null
    return DoubleArray(3) { i -> (ca * bc[i] + cb * ca2[i] + cc * ab[i]) / det }
}

/** The distance from [q] to triangle [t]. */
private fun distanceToTriangle(q: DoubleArray, p: DoubleArray, tri: IntArray, t: Int): Double {
    val a = DoubleArray(3) { p[tri[t * 3] * 3 + it] }
    val b = DoubleArray(3) { p[tri[t * 3 + 1] * 3 + it] }
    val c = DoubleArray(3) { p[tri[t * 3 + 2] * 3 + it] }
    fun dot(u: DoubleArray, w: DoubleArray) = u[0] * w[0] + u[1] * w[1] + u[2] * w[2]
    fun sub(u: DoubleArray, w: DoubleArray) = DoubleArray(3) { u[it] - w[it] }
    fun at(u: DoubleArray, w: DoubleArray, s: Double) = DoubleArray(3) { u[it] + s * w[it] }
    // Ericson, Real-Time Collision Detection, 5.1.5: the closest point by Voronoi region.
    val ab = sub(b, a); val ac = sub(c, a); val ap = sub(q, a)
    val d1 = dot(ab, ap); val d2 = dot(ac, ap)
    val closest: DoubleArray = run {
        if (d1 <= 0 && d2 <= 0) return@run a
        val bp = sub(q, b); val d3 = dot(ab, bp); val d4 = dot(ac, bp)
        if (d3 >= 0 && d4 <= d3) return@run b
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0 && d1 >= 0 && d3 <= 0) return@run at(a, ab, d1 / (d1 - d3))
        val cp = sub(q, c); val d5 = dot(ab, cp); val d6 = dot(ac, cp)
        if (d6 >= 0 && d5 <= d6) return@run c
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0 && d2 >= 0 && d6 <= 0) return@run at(a, ac, d2 / (d2 - d6))
        val va = d3 * d6 - d5 * d4
        if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) return@run at(b, sub(c, b), (d4 - d3) / ((d4 - d3) + (d5 - d6)))
        val denom = 1.0 / (va + vb + vc)
        DoubleArray(3) { a[it] + ab[it] * vb * denom + ac[it] * vc * denom }
    }
    return sqrt(sub(q, closest).let { dot(it, it) })
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
