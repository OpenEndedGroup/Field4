package afield.graphics

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How close two rings have to be before [PlanarTessellation] treats them as lying in the same plane.
 *
 * Both numbers are dimensionless, and neither is an absolute epsilon — that is deliberate, because
 * an `FLine` may be authored in screen pixels, in millimetres, or in unit cube coordinates, and the
 * same drawing must cluster the same way in all three.
 *
 * @param sineOfAngle compared against the sine of the angle between two rings' unit Newell normals,
 * `|n_a x n_b|`. It is a slope rather than radians: the default `1e-3` is about `0.057` degrees.
 * Antiparallel normals count as parallel — coplanar is not the same as co-oriented, and one plane is
 * perfectly entitled to hold two polygons facing opposite ways.
 * @param offsetFraction compared against the distance from a point to the candidate plane, divided
 * by the diagonal of the bounding box of *every* ring handed to [PlanarTessellation.tessellate]. So
 * the tolerance scales with the drawing, and scaling the whole input never changes the clustering.
 */
data class Coplanarity(
    val sineOfAngle: Float = 1e-3f,
    val offsetFraction: Float = 1e-3f,
) {
    init {
        require(sineOfAngle >= 0f && offsetFraction >= 0f) {
            "coplanarity tolerances cannot be negative: $sineOfAngle, $offsetFraction"
        }
    }

    companion object {
        val DEFAULT = Coplanarity()

        /**
         * Every ring in one plane, whatever its geometry says — which is what `FLine.tessellate` did
         * before per-plane grouping landed, and therefore the counterfactual that
         * `TessellationRingRobustnessTest` measures the fix against rather than arguing it.
         */
        val SINGLE_PLANE = Coplanarity(sineOfAngle = Float.MAX_VALUE, offsetFraction = Float.MAX_VALUE)
    }
}

/**
 * The one triangulator for filled, arbitrarily oriented, hole-carrying ring sets.
 *
 * Rings in, triangles out, where a triangle is three references back into the rings that were handed
 * in — so a caller can map them onto whatever its own vertices happen to be: packed `xyz` floats
 * (`FLine.tessellate`), or indices into a `Realization3` (`EarcutFaceTriangulator`). Field had two
 * implementations of this before, and they disagreed about the handedness of the projected frame: one
 * projected a y-dominant face onto the mirror of the right-handed pair and flipped every returned
 * triangle back afterwards. That composition is correct, holes included — the claim that a mirrored
 * frame inverts [FloatEarcut]'s outer-versus-hole decision was wrong, and `FloatEarcutOuterRingTest`
 * measures why. The reason for having one implementation is that a convention needing every caller to
 * remember a compensating flip is a convention callers get wrong. See the "convention note" in
 * `dev_docs/fline_fill_orientation.md`.
 *
 * The work, in order:
 *
 *  1. a Newell normal per ring;
 *  2. **cluster the rings into planes** — see [Coplanarity], and the notes on the decisions below;
 *  3. per plane, one orientation-preserving projected frame, chosen from that plane's strongest ring;
 *  4. per plane, ring nesting — bounding box, signed area, point-in-polygon — measured in that
 *     plane's own frame;
 *  5. per filled group (an even-depth ring plus its odd-depth children), the hemisphere read from
 *     that group's own outer ring;
 *  6. [FloatEarcut], with hole indices.
 *
 * ### Why the frame decides the facing
 *
 * [FloatEarcut] **normalises the winding of every ring it is handed** (`FloatEarcut.kt:705` walks the
 * ring forwards or backwards depending on the sign of its signed area), so the facing of every
 * triangle that comes back is decided by the projected frame and never by the winding the caller
 * authored. Which ring is the *outer* one is decided earlier, and separately, by **array position**
 * (`FloatEarcut.kt:19`, `:21`, `:484`) — the winding never enters that. So the frame is the whole of
 * the facing question and none of the nesting question. That makes "which two coordinates do I keep?"
 * a statement about the surface normal, not an implementation detail. Two properties make the frame
 * agree with the polygon:
 *
 *  * the kept pair is the right-handed cycle about the dropped axis: `x -> (y, z)`, `y -> (z, x)`,
 *    `z -> (x, y)`. `(x, z)` for a y-dominant face is the mirror of that, and mirrors the facing of
 *    every wall-like face;
 *  * the pair is swapped when the normal points down the *negative* half of the dominant axis, or
 *    the far side of a face comes back inside out.
 *
 * Together these make tessellation commute with rigid rotation, which
 * `TessellationOrientationOracleTest` pins.
 *
 * ### Why nesting is scoped to a plane
 *
 * "Ring A is a hole in ring B" is measured on projected coordinates, so it only means something when
 * A and B are flattened in the *same* frame — and it is only *true* when they are in the same
 * surface. Two rings in different planes can perfectly well contain one another in projection while
 * being nowhere near each other in space; subtracting one from the other then is not a hole, it is a
 * coincidence of the axis that happened to be picked. So a ring is only ever classified against
 * rings it is coplanar with, and a "hole" ring whose plane disagrees with its outer ring becomes an
 * outer ring in its own right, and fills.
 *
 * ### Degenerate rings have no plane
 *
 * A ring whose Newell normal is zero — collinear, all-identical, a bowtie — cannot vote on a plane
 * and is not allowed to seed one. Those rings are assigned *after* every other ring, by the distance
 * test alone, to the first plane that contains them; one that matches nothing gets a plane to itself,
 * where it fills nothing. This is exactly what the single-axis code did by a different route (its
 * `projectionAxis` kept its `(x, y)` default when no ring had a normal), so an all-degenerate input
 * behaves as it always has.
 *
 * ### Ambiguity resolves to the first match, in ring order
 *
 * Planes are grown greedily: each ring joins the first existing plane it matches or seeds a new one,
 * and a plane's representative is the ring that seeded it — never a running average of its members.
 * So a chain of pairwise-near-coplanar rings does not transitively collapse into one plane, and the
 * result does not drift with input size. It *does* depend on ring order, to the extent that earlier
 * rings seed; that is the price of not doing a global fit, and it is stable and predictable.
 */
object PlanarTessellation {

    /** A reference to one point of one input ring: `rings[ring][vertex]`. */
    data class Vertex(val ring: Int, val vertex: Int)

    /**
     * One filled polygon: an outer ring, the coplanar rings subtracted from it, and its triangles.
     *
     * A [Polygon] always carries at least one triangle — a group that tessellates to nothing is not
     * reported, so a caller never has to check.
     */
    class Polygon internal constructor(
        /** Index into the input rings of the ring being filled. */
        val outer: Int,
        /** Indices of the rings punched out of [outer]. All coplanar with it. */
        val holes: List<Int>,
        /** Three [Vertex] references per triangle, wound so that they face the way [normal] points. */
        val triangles: List<Vertex>,
        /** [outer]'s Newell normal, unnormalised: its length is twice the ring's area. */
        val normal: Vec3,
    ) {
        val triangleCount: Int get() = triangles.size / 3

        override fun toString(): String =
            "Polygon(outer=$outer, holes=$holes, triangles=$triangleCount)"
    }

    /**
     * Newell's normal of a ring, unnormalised — its length is twice the ring's planar area, which is
     * what makes it usable both as a direction and as a measure of how much to trust that direction.
     */
    fun newellNormal(ring: List<Vec3>): Vec3 {
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (i in ring.indices) {
            val p = ring[i]
            val q = ring[(i + 1) % ring.size]
            nx += (p.y - q.y) * (p.z + q.z)
            ny += (p.z - q.z) * (p.x + q.x)
            nz += (p.x - q.x) * (p.y + q.y)
        }
        return Vec3(nx, ny, nz)
    }

    /**
     * Triangulate [rings] — each an ordered, closed, already-flattened ring, with no repeated final
     * point — grouping them into planes and holes as described on [PlanarTessellation].
     *
     * Rings may be wound either way and given in any order. Results are ordered by outer-ring index,
     * so the output does not depend on the order the planes happened to be discovered in.
     */
    fun tessellate(
        rings: List<List<Vec3>>,
        coplanarity: Coplanarity = Coplanarity.DEFAULT,
    ): List<Polygon> {
        if (rings.isEmpty()) return emptyList()
        val normals = rings.map { newellNormal(it) }
        val out = ArrayList<Polygon>()
        for (plane in cluster(rings, normals, coplanarity)) {
            fill(rings, normals, plane, out)
        }
        out.sortBy { it.outer }
        return out
    }

    // ------------------------------------------------------------------------------------ planes

    /** A plane as a unit normal and a signed offset along it. */
    private class Plane(val nx: Float, val ny: Float, val nz: Float, val offset: Float) {
        fun distanceTo(p: Vec3): Float = abs(nx * p.x + ny * p.y + nz * p.z - offset)

        /** `|n x m|` for unit `n` and `m`: the sine of the angle, unsigned so antiparallel is 0. */
        fun sineOfAngleTo(mx: Float, my: Float, mz: Float): Float {
            val cx = ny * mz - nz * my
            val cy = nz * mx - nx * mz
            val cz = nx * my - ny * mx
            return sqrt(cx * cx + cy * cy + cz * cz)
        }
    }

    /** One plane's worth of rings. [plane] is null for the rings that have no plane at all. */
    private class Group(val plane: Plane?, val members: MutableList<Int>)

    private fun cluster(
        rings: List<List<Vec3>>,
        normals: List<Vec3>,
        coplanarity: Coplanarity,
    ): List<Group> {
        // The distance tolerance is a fraction of the whole input's diagonal, so that clustering is
        // invariant under a uniform scale of the drawing.
        val tolerance = coplanarity.offsetFraction * diagonalOf(rings)
        val groups = ArrayList<Group>()
        val withoutANormal = ArrayList<Int>()

        for (i in rings.indices) {
            val n = normals[i]
            val length = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
            if (!length.isFinite() || length <= 0f) {
                withoutANormal.add(i)
                continue
            }
            val ux = n.x / length
            val uy = n.y / length
            val uz = n.z / length
            val existing = groups.firstOrNull { group ->
                val plane = group.plane ?: return@firstOrNull false
                plane.sineOfAngleTo(ux, uy, uz) <= coplanarity.sineOfAngle &&
                        rings[i].all { plane.distanceTo(it) <= tolerance }
            }
            if (existing != null) {
                existing.members.add(i)
            } else {
                val ring = rings[i]
                var cx = 0f
                var cy = 0f
                var cz = 0f
                ring.forEach { cx += it.x; cy += it.y; cz += it.z }
                val inverse = 1f / ring.size
                val offset = ux * cx * inverse + uy * cy * inverse + uz * cz * inverse
                groups.add(Group(Plane(ux, uy, uz, offset), mutableListOf(i)))
            }
        }

        // A ring with no normal cannot seed a plane, and is only placed once every ring that *can*
        // has had its say — otherwise a collinear ring listed first would decide the frame for the
        // real geometry that follows it.
        for (i in withoutANormal) {
            val existing = groups.firstOrNull { group ->
                val plane = group.plane ?: return@firstOrNull false
                rings[i].all { plane.distanceTo(it) <= tolerance }
            }
            if (existing != null) existing.members.add(i) else groups.add(Group(null, mutableListOf(i)))
        }

        return groups
    }

    private fun diagonalOf(rings: List<List<Vec3>>): Float {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        rings.forEach { ring ->
            ring.forEach { p ->
                minX = min(minX, p.x); maxX = max(maxX, p.x)
                minY = min(minY, p.y); maxY = max(maxY, p.y)
                minZ = min(minZ, p.z); maxZ = max(maxZ, p.z)
            }
        }
        if (minX > maxX) return 0f
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ---------------------------------------------------------------------------- one plane's fill

    private class RingInfo(
        val ring: Int,
        val points: List<Vec3>,
        val area: Float,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
    )

    private fun fill(
        rings: List<List<Vec3>>,
        normals: List<Vec3>,
        group: Group,
        out: MutableList<Polygon>,
    ) {
        val members = group.members

        // The axis, and the fallback hemisphere, come from this plane's strongest ring. For a plane
        // that holds only real geometry every member agrees about the axis anyway — they are parallel
        // — so this matters for the degenerate members that were attached by distance alone.
        var axis = 2
        var strongest = 0f
        var defaultFlipped = false
        for (m in members) {
            val n = normals[m]
            val lengthSquared = n.x * n.x + n.y * n.y + n.z * n.z
            if (lengthSquared > strongest) {
                strongest = lengthSquared
                axis = when {
                    abs(n.x) >= abs(n.y) && abs(n.x) >= abs(n.z) -> 0
                    abs(n.y) >= abs(n.z) -> 1
                    else -> 2
                }
                defaultFlipped = componentOf(n, axis) < 0f
            }
        }

        /** The first of the right-handed pair kept about [axis]. */
        fun projectedX(p: Vec3): Float = when (axis) {
            0 -> p.y
            1 -> p.z
            else -> p.x
        }

        /** The second of the right-handed pair kept about [axis]. */
        fun projectedY(p: Vec3): Float = when (axis) {
            0 -> p.z
            1 -> p.x
            else -> p.y
        }

        fun area2D(points: List<Vec3>): Float {
            var sum = 0f
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                sum += (projectedX(a) * projectedY(b)) - (projectedX(b) * projectedY(a))
            }
            return 0.5f * sum
        }

        fun pointInPolygon(points: List<Vec3>, p: Vec3): Boolean {
            var inside = false
            var j = points.size - 1
            for (i in points.indices) {
                val xi = projectedX(points[i])
                val yi = projectedY(points[i])
                val xj = projectedX(points[j])
                val yj = projectedY(points[j])
                val px = projectedX(p)
                val py = projectedY(p)
                val intersect = ((yi > py) != (yj > py)) &&
                        (px < (xj - xi) * (py - yi) / ((yj - yi) + 1e-12f) + xi)
                if (intersect) inside = !inside
                j = i
            }
            return inside
        }

        // Bounding boxes, signed areas and containment all run in the *unflipped* frame. A mirror
        // does not change what is inside what, so the nesting comes out the same either way, and
        // keeping one frame here keeps the grouping stable however the groups end up facing.
        val infos = members.map { m ->
            val points = rings[m]
            var minX = projectedX(points[0])
            var minY = projectedY(points[0])
            var maxX = minX
            var maxY = minY
            for (i in 1 until points.size) {
                val p = points[i]
                minX = min(minX, projectedX(p)); maxX = max(maxX, projectedX(p))
                minY = min(minY, projectedY(p)); maxY = max(maxY, projectedY(p))
            }
            RingInfo(m, points, area2D(points), minX, minY, maxX, maxY)
        }

        val parent = IntArray(infos.size) { -1 }
        for (i in infos.indices) {
            val test = infos[i].points[0]
            val own = abs(infos[i].area)
            var best = -1
            var bestArea = Float.POSITIVE_INFINITY
            for (j in infos.indices) {
                if (i == j) continue
                val b = infos[j]
                val x = projectedX(test)
                val y = projectedY(test)
                if (x < b.minX || x > b.maxX || y < b.minY || y > b.maxY) continue
                if (!pointInPolygon(b.points, test)) continue
                val area = abs(b.area)
                // A container is *strictly* larger than what it contains. Geometrically that is free —
                // a simple ring cannot enclose one of equal or greater area — and it is what makes the
                // relation a forest: `parent` strictly increases area, so it cannot cycle.
                //
                // Without it, two overlapping rings of equal projected area can each be the other's
                // parent, because a parent is chosen by containment of a single point and the two
                // choices are made independently. The depth walk below then never terminates. Two
                // overlapping squares in one filled FLine is an ordinary drawing, and it hung the
                // renderer — and, identically, the browser tab, because `threejs_viewer.html` copies
                // this loop. Found 2026-08-04 by the differential corpus;
                // `PlanarTessellationParentCycleTest` is the reproduction.
                if (area <= own) continue
                if (area < bestArea) {
                    bestArea = area
                    best = j
                }
            }
            parent[i] = best
        }

        val depth = IntArray(infos.size)
        for (i in infos.indices) {
            var d = 0
            var p = parent[i]
            // `d <= infos.size` cannot bind, given the strictly-increasing-area invariant above. It is
            // here because the failure it would catch is a hang rather than a wrong answer, and a
            // wrong depth is the better of those two.
            while (p != -1 && d <= infos.size) {
                d++
                p = parent[p]
            }
            depth[i] = d
        }

        for (i in infos.indices) {
            if (depth[i] % 2 != 0) continue
            val holes = infos.indices.filter { parent[it] == i && depth[it] % 2 == 1 }

            // This group is its own polygon, so it faces its own way: read the hemisphere from its
            // outer ring rather than from whichever ring in the plane was biggest. Two disjoint
            // subpaths wound against each other are two polygons back to back, and a single decision
            // for both gets one of them inside out.
            val outerNormal = normals[infos[i].ring]
            val hemisphere = componentOf(outerNormal, axis)
            val flipped = if (hemisphere == 0f) defaultFlipped else hemisphere < 0f

            val refs = ArrayList<Vertex>()
            val projected = ArrayList<Float>()
            val holeStarts = ArrayList<Int>()
            (listOf(i) + holes).forEachIndexed { at, local ->
                if (at > 0) holeStarts.add(refs.size)
                val info = infos[local]
                info.points.forEachIndexed { vertex, p ->
                    refs.add(Vertex(info.ring, vertex))
                    projected.add(if (flipped) projectedY(p) else projectedX(p))
                    projected.add(if (flipped) projectedX(p) else projectedY(p))
                }
            }
            if (refs.size < 3) continue

            val triangles = FloatEarcut.earcut(
                projected.toFloatArray(),
                if (holeStarts.isEmpty()) null else holeStarts.toIntArray(),
                2,
            )
            if (triangles.isEmpty()) continue
            out.add(
                Polygon(
                    outer = infos[i].ring,
                    holes = holes.map { infos[it].ring },
                    triangles = triangles.map { refs[it] },
                    normal = Vec3(outerNormal),
                )
            )
        }
    }

    private fun componentOf(n: Vec3, axis: Int): Float = when (axis) {
        0 -> n.x
        1 -> n.y
        else -> n.z
    }
}
