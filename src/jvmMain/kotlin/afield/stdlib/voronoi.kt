@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import kotlin.collections.first
import kotlin.collections.last
import kotlin.math.*
import afield.stdlib.linalg.Vector3f as Vec3

/**
 * Practical 3D Voronoi and approximate 3D multiplicatively weighted Voronoi
 * ("CircularDirichlet") for Kotlin + Field linalg.
 *
 * - [voronoi3d] builds convex polyhedral cells by intersecting pairwise bisector half-spaces. Six
 *   clip-box planes are added to **every** cell, not only to the unbounded ones, so a cell always
 *   comes back as a bounded polyhedron; [VoronoiCell3.bounded] is how you tell whether the answer is
 *   the site's own cell or an artefact of the box. The box is the sites' bounding box grown on every
 *   side by `clipPadding * scale + 1`, where `scale` is the cloud's own diagonal (floored at 1).
 * - [CircularDirichlet3d.build] extracts approximate support patches on bisector spheres / planes by
 *   clipping a sampled surface mesh with the weighted score predicate.
 *
 * The weighted contourer is renderer-oriented: it prefers stable, wound
 * boundary loops over exact symbolic patch topology.
 *
 * # What the unweighted clipper guarantees, and what it does not
 *
 * Which face each corner belongs to is decided **combinatorially** — a corner records the planes that
 * produced it, and a face's polygon is exactly the corners recording that plane — rather than by a
 * distance test against a tolerance. See the `collectCorners` KDoc in [Voronoi3d] for why that matters: with a
 * tolerance scaled off the point cloud, faces near a near-cospherical site cluster claimed corners
 * that were not on them, and the cells did not close (a T-junction, and a hairline crack in any mesh
 * built from the faces).
 *
 * It is still a `Float` clipper and the repair is not total. On the sweep that ratcheted it, **4 of
 * 465 bounded cells** and **12 of 447 clipped cells** still fail the strict closure check — every
 * edge walked by exactly two faces, in opposite directions. Those counts are pinned with `<=` in
 * `VoronoiFaceTopologyTest`, so they can improve but cannot regress silently. Cell volumes tile the
 * clip box to 1e-4 relative and a cubic lattice of sites gives exact unit cubes.
 */

// -----------------------------------------------------------------------------
// Unweighted 3D Voronoi
// -----------------------------------------------------------------------------

/** An axis-aligned box; by convention `min` is componentwise `<=` `max`. The clipper's own window. */
data class Aabb3(
    val min: Vec3,
    val max: Vec3,
)

/**
 * The plane `normal · x = d`.
 *
 * [normal] is **not** normalized — for a bisector it is the raw difference between two sites — so a
 * signed distance from this plane is `(dot(normal, x) - d) / length(normal)`.
 */
data class Plane3(
    val normal: Vec3,
    val d: Float,
)

/**
 * One face of a [VoronoiCell3]: the plane it lies in, and the cell's corners that lie on it.
 *
 * @param neighborSite the site across this face, or `null` when this is a clip-box face rather than a
 *   real bisector.
 * @param polygon wound counter-clockwise as seen from the `+plane.normal` side — i.e. its signed area
 *   along [Plane3.normal] is non-negative. Always at least 3 corners; a face with fewer, or with a
 *   numerically zero area, is dropped rather than emitted degenerate.
 * @param truncated `true` if any of this face's corners touches the clip box, so the face is cut by
 *   the window rather than closed by the sites. Uses a deliberately looser tolerance than the cell's
 *   combinatorics, because "does this come near the box" is a question about the box.
 */
data class VoronoiFace(
    val neighborSite: Int?,
    val plane: Plane3,
    val polygon: List<Vec3>,
    val isClip: Boolean,
    val truncated: Boolean,
)

/**
 * The convex polyhedron of points nearer to [site] than to any other site, intersected with the
 * lattice's clip box.
 *
 * @param bounded `true` only if no face is a clip face and no face is [VoronoiFace.truncated] — that
 *   is, the cell is closed by its neighbours alone and the clip box did not participate. An unbounded
 *   cell's faces are real geometry, but its volume is an artefact of the box.
 */
data class VoronoiCell3(
    val siteIndex: Int,
    val site: Vec3,
    val faces: List<VoronoiFace>,
    val bounded: Boolean,
)

/**
 * One cell per input site, in input order, plus the box they were all clipped against.
 *
 * A Voronoi tessellation partitions its clip box exactly, which gives a one-line total check on a
 * result: the cell volumes must sum to the box's own volume. Measured, they do to 1e-4 relative.
 */
data class VoronoiLattice(
    val cells: List<VoronoiCell3>,
    val clipBounds: Aabb3,
) {
    operator fun get(siteIndex: Int): VoronoiCell3 = cells[siteIndex]
}

/**
 * The 3D Voronoi diagram of [l], with the default tolerance and a generous clip box.
 *
 * `Voronoi3d.build(l, tolerance, clipPadding)` is the same thing with the knobs exposed. The box is
 * the sites' bounding box grown by `clipPadding * scale + 1` on every side, `scale` being the cloud's
 * own diagonal, so the default `clipPadding = 8` puts the box well clear of the sites and every
 * interior cell is decided by its neighbours alone. Shrink it if you want the box to do work.
 */
fun voronoi3d(l: List<Vec3>): VoronoiLattice = Voronoi3d.build(l)

/** `Float`'s relative precision, `2^-23`. The unit every error bound in the clipper is expressed in. */
private const val FLOAT_ULP = 1.1920929e-7f

object Voronoi3d {
    private data class PlaneSpec(
        val plane: Plane3,
        val neighborSite: Int?,
        val isClip: Boolean,
    )

    fun build(
        points: List<Vec3>,
        tolerance: Float = 1e-4f,
        clipPadding: Float = 8f,
    ): VoronoiLattice {
        require(tolerance > 0f) { "tolerance must be > 0" }
        if (points.isEmpty()) {
            val zero = Vec3(0f, 0f, 0f)
            return VoronoiLattice(emptyList(), Aabb3(v3(zero), v3(zero)))
        }

        val scale = geometryScale(points)
        val eps = tolerance * max(1f, scale)
        val clip = buildClipBox(points, scale * clipPadding + 1f)

        // Each bisector's offset is `0.5 * (|pj|^2 - |pi|^2)`, and the clip planes' offsets are the box's
        // own coordinates, so the *absolute* rounding in a plane offset is set by the largest of those
        // magnitudes. It survives into every corner solved from those planes, and it is the error term that
        // dominates when the offsets themselves cancel to zero — sites on a sphere, where every bisector
        // passes exactly through the centre. Computed once here rather than guessed at per cell.
        var offsetMagnitude = max(abs(clip.min.x), abs(clip.max.x))
        offsetMagnitude = max(offsetMagnitude, max(abs(clip.min.y), abs(clip.max.y)))
        offsetMagnitude = max(offsetMagnitude, max(abs(clip.min.z), abs(clip.max.z)))
        for (p in points) offsetMagnitude = max(offsetMagnitude, lengthSquared(p))
        val offsetNoise = FLOAT_ULP * offsetMagnitude

        val cells = ArrayList<VoronoiCell3>(points.size)
        for (i in points.indices) {
            cells += buildCell(
                siteIndex = i,
                points = points,
                clip = clip,
                eps = eps,
                offsetNoise = offsetNoise,
            )
        }

        return VoronoiLattice(cells = cells, clipBounds = clip)
    }

    private fun buildCell(
        siteIndex: Int,
        points: List<Vec3>,
        clip: Aabb3,
        eps: Float,
        offsetNoise: Float,
    ): VoronoiCell3 {
        val site = points[siteIndex]
        val planes = ArrayList<PlaneSpec>(points.size + 6)

        for (j in points.indices) {
            if (j == siteIndex) continue

            val delta = sub(points[j], site)
            if (lengthSquared(delta) <= eps * eps) continue

            val d = 0.5f * (dot(points[j], points[j]) - dot(site, site))
            planes += PlaneSpec(
                plane = Plane3(normal = delta, d = d),
                neighborSite = j,
                isClip = false,
            )
        }

        addClipPlanes(planes, clip)

        val corners = collectCorners(planes, eps, offsetNoise)
        val faces = buildFaces(corners, planes, eps)

        val bounded = faces.none { it.isClip || it.truncated }

        return VoronoiCell3(
            siteIndex = siteIndex,
            site = v3(site),
            faces = faces,
            bounded = bounded,
        )
    }

    private fun addClipPlanes(planes: MutableList<PlaneSpec>, clip: Aabb3) {
        planes += PlaneSpec(Plane3(Vec3( 1f, 0f, 0f), clip.max.x), null, true)
        planes += PlaneSpec(Plane3(Vec3(-1f, 0f, 0f), -clip.min.x), null, true)
        planes += PlaneSpec(Plane3(Vec3(0f,  1f, 0f), clip.max.y), null, true)
        planes += PlaneSpec(Plane3(Vec3(0f, -1f, 0f), -clip.min.y), null, true)
        planes += PlaneSpec(Plane3(Vec3(0f, 0f,  1f), clip.max.z), null, true)
        planes += PlaneSpec(Plane3(Vec3(0f, 0f, -1f), -clip.min.z), null, true)
    }

    /**
     * A corner of the cell, the indices of the planes it lies on, and how well its position is known.
     *
     * The plane set is the whole point: a face's polygon is the corners that record *that* plane, and
     * nothing else. See [collectCorners]. [radius] is the corner's own uncertainty — see
     * [conditionRadius] — and doubles as the distance within which another candidate is *this* corner
     * rather than a different one.
     */
    private class Corner(var p: Vec3, var radius: Float, val planes: MutableSet<Int>)

    /**
     * How far a triple-plane intersection solved by Cramer's rule can be from the corner it is trying to
     * compute. [bulk] is the sum of the three numerator terms' magnitudes, [numerator] the magnitude of
     * their sum, [crossBulk] the sum of the three cross products' magnitudes, [offsetNoise] the absolute
     * rounding in a plane's offset, and [det] the determinant.
     *
     * `Float` carries about `2^-23` of relative precision and Cramer's rule spends it in three places.
     * The three numerator terms cancel against one another, so the surviving numerator can be far smaller
     * than the terms that made it. The result is divided by a determinant that goes to zero as the three
     * normals approach coplanar — which is what a cluster of near-cospherical sites produces. And the
     * plane offsets are not exact to begin with, which is the term that dominates when they cancel to
     * nothing: sites *on* a sphere give bisectors whose offsets are all zero in exact arithmetic and
     * `±6e-8` in `Float`, and the corner they all pass through comes out 1e-6 away from where it should
     * be — measured, on 24 cospherical sites.
     *
     * Deriving the merge radius from the arithmetic's own conditioning, rather than from the point
     * cloud's scale, is what lets one rule merge those and *not* merge a real 4.5e-3-long edge between two
     * well-conditioned corners, whose radii come out near 1e-6. The `48` and `2` are slack over the strict
     * first-order bounds, and the `2` is not arbitrary: swept over 0 / 2 / 8 / 32, the number of cells whose
     * faces fail to close over a 912-cell sweep goes 12 / 12 / 52 / 167, and `0` loses the cospherical case
     * entirely — so `2` is the widest value that costs nothing.
     */
    private fun conditionRadius(
        bulk: Float,
        numerator: Float,
        crossBulk: Float,
        offsetNoise: Float,
        det: Float,
    ): Float = (48f * FLOAT_ULP * (bulk + numerator) + 2f * crossBulk * offsetNoise) / max(1e-20f, det)

    /**
     * Every corner of the cell — every point where three or more of the bounding planes meet inside all
     * the others — each carrying the set of planes it lies on.
     *
     * # Why the plane set, and not a distance test
     *
     * The corners themselves are easy: intersect every triple of planes, keep the points inside every
     * half-space. Deciding *which face each corner belongs to* is the hard part, and doing it by asking
     * "is this corner within some tolerance of that plane?" is what used to leave cells with
     * T-junctions.
     *
     * Where four or more bisectors nearly meet — five sites near-cospherical, which is common: a third
     * of the interior cells of a 72-site random lattice have such a corner — the exact intersection is
     * not one corner but a tight cluster of real ones joined by short real edges. An absolute tolerance
     * (it was `4 * eps`, scaled off the whole point cloud) is wider than that cluster, so *every* face
     * passing near the cluster claimed *every* corner in it. The measured symptom, on a 72-site lattice
     * at seed 31, cell 10: a 4.5e-3-long edge listed by four faces and the two long edges radiating from
     * its ends listed by one each. One edge on one side of the seam, two on the other — a T-junction, a
     * hairline crack in any mesh built from the faces, and no weld tolerance repairs it because the
     * defect is a face claiming a corner that is not on it.
     *
     * The plane triple that produced a corner is exactly the incidence information the distance test was
     * trying to recover, so this keeps it instead. A corner is then on a face if and only if that face's
     * plane helped produce it — or passes through it to within the corner's *own* uncertainty
     * ([conditionRadius]), which is the second pass below. That pass is what handles *exactly* symmetric
     * input: over a cubic lattice of sites seven bisectors really do pass through each cell corner, and
     * only some of those triples are well-conditioned enough to survive [intersectPlanes].
     *
     * Every corner is then a genuine vertex of the half-space intersection and every recorded incidence
     * is genuine, so each pair of planes contributes a genuine edge and the faces close — with the small
     * real edges of a degenerate cluster present rather than smeared away.
     */
    private fun collectCorners(
        planes: List<PlaneSpec>,
        eps: Float,
        offsetNoise: Float,
    ): List<Corner> {
        val corners = ArrayList<Corner>()
        val lengths = FloatArray(planes.size) { sqrt(lengthSquared(planes[it].plane.normal)) }
        val radiusOut = FloatArray(1)

        for (a in 0 until planes.size - 2) {
            for (b in a + 1 until planes.size - 1) {
                for (c in b + 1 until planes.size) {
                    val p = intersectPlanes(
                        planes[a].plane, planes[b].plane, planes[c].plane, eps, offsetNoise, radiusOut,
                    ) ?: continue

                    // Floor the uncertainty at the noise in evaluating [side] itself, which is what the
                    // inside test below is about to measure with.
                    val radius = max(radiusOut[0], 8f * FLOAT_ULP * (1f + sqrt(lengthSquared(p))))
                    // A candidate is a corner if it is inside every half-space to within *its own*
                    // uncertainty. The old absolute `eps` here was the second half of the T-junction: at
                    // 1e-4 of the whole point cloud's diagonal it is the same size as a degenerate corner
                    // cluster, so it admitted points genuinely outside the cell, and a face built from a
                    // point that is not a corner of the cell cannot agree with its neighbour.
                    if (!insideAllWithin(p, planes, radius, lengths)) continue

                    var hit: Corner? = null
                    for (existing in corners) {
                        val reach = max(radius, existing.radius)
                        if (distanceSquared(p, existing.p) <= reach * reach) {
                            hit = existing
                            break
                        }
                    }
                    if (hit == null) {
                        corners += Corner(v3(p), radius, mutableSetOf(a, b, c))
                    } else {
                        // Keep the best-conditioned estimate of where the corner is, but keep the widest
                        // radius, because the corner really is that uncertain.
                        if (radius < hit.radius) hit.p = v3(p)
                        hit.radius = max(hit.radius, radius)
                        hit.planes += a
                        hit.planes += b
                        hit.planes += c
                    }
                }
            }
        }

        // A plane can pass through a corner without any triple containing it having survived — exactly
        // symmetric input, or a triple [intersectPlanes] rejected. Within the corner's own uncertainty
        // that is not a judgement call.
        for (corner in corners) {
            for (i in planes.indices) {
                if (i in corner.planes) continue
                if (abs(side(planes[i].plane, corner.p)) <= corner.radius * lengths[i]) corner.planes += i
            }
        }

        return corners
    }

    private fun buildFaces(
        corners: List<Corner>,
        planes: List<PlaneSpec>,
        eps: Float,
    ): List<VoronoiFace> {
        val faces = ArrayList<VoronoiFace>()
        // The `truncated` flag keeps its own, looser tolerance: "does this face come near the clip box"
        // is a question about the clip box, not about the cell's combinatorics.
        val clipTol = 4f * eps

        for (index in planes.indices) {
            val corner = ArrayList<Vec3>()
            var areaFloor = 0f
            for (c in corners) {
                if (index in c.planes) {
                    corner += v3(c.p)
                    areaFloor = max(areaFloor, c.radius)
                }
            }

            val spec = planes[index]
            // No dedupe pass: [collectCorners] emits one corner per corner, by construction.
            if (corner.size < 3) continue

            val polygon = sortPolygonOnPlane(corner, spec.plane.normal)
            if (polygon.size < 3) continue

            // Only a *numerically* zero area is culled — a plane that touches the cell along a line
            // rather than over a patch, where the corners are collinear to within their own uncertainty.
            // A small-but-real face has to be kept: dropping one is exactly how a cell gets a hole.
            val signedArea = signedAreaAlong(polygon, normalize(spec.plane.normal))
            if (abs(signedArea) <= areaFloor * areaFloor) continue

            val truncated = !spec.isClip && polygon.any { touchesAnyClip(it, planes, clipTol) }

            faces += VoronoiFace(
                neighborSite = spec.neighborSite,
                plane = Plane3(v3(spec.plane.normal), spec.plane.d),
                polygon = polygon,
                isClip = spec.isClip,
                truncated = truncated,
            )
        }

        faces.sortWith(compareBy<VoronoiFace>({ it.isClip }, { it.neighborSite ?: Int.MAX_VALUE }))
        return faces
    }

    private fun touchesAnyClip(p: Vec3, planes: List<PlaneSpec>, eps: Float): Boolean {
        for (spec in planes) {
            if (!spec.isClip) continue
            if (abs(side(spec.plane, p)) <= eps) return true
        }
        return false
    }

    /**
     * Is [p], a point known to within [radius], inside every half-space?
     *
     * [side] is measured with an un-normalized normal, so the per-plane allowance is scaled by that
     * normal's length ([lengths]) to make the comparison a distance.
     */
    private fun insideAllWithin(p: Vec3, planes: List<PlaneSpec>, radius: Float, lengths: FloatArray): Boolean {
        for (i in planes.indices) {
            if (side(planes[i].plane, p) > radius * lengths[i]) return false
        }
        return true
    }

    private fun side(plane: Plane3, p: Vec3): Float = dot(plane.normal, p) - plane.d

    /**
     * Where three planes meet, or `null` if they are too close to parallel to say.
     *
     * If [radiusOut] is given, `radiusOut[0]` receives a bound on how far the answer can be from the true
     * intersection, given that each plane's own offset is known only to [offsetNoise] — see
     * [conditionRadius] for why that matters and how it is used.
     */
    private fun intersectPlanes(
        a: Plane3,
        b: Plane3,
        c: Plane3,
        eps: Float,
        offsetNoise: Float,
        radiusOut: FloatArray?,
    ): Vec3? {
        val bc = cross(b.normal, c.normal)
        val ca = cross(c.normal, a.normal)
        val ab = cross(a.normal, b.normal)

        val det = dot(a.normal, bc)
        if (abs(det) <= eps) return null

        val ta = mul(bc, a.d)
        val tb = mul(ca, b.d)
        val tc = mul(ab, c.d)
        val num = add(add(ta, tb), tc)
        val p = mul(num, 1f / det)

        if (radiusOut != null) {
            // Cramer's rule loses precision two ways: the three numerator terms cancel against each other,
            // and the result is then divided by a determinant that may be tiny. Bounding it by the *sum of
            // the term magnitudes* over the determinant catches both — the ratio is 1 when nothing cancels
            // and grows exactly as far as the cancellation goes.
            val bulk = sqrt(lengthSquared(ta)) + sqrt(lengthSquared(tb)) + sqrt(lengthSquared(tc))
            val crossBulk = sqrt(lengthSquared(bc)) + sqrt(lengthSquared(ca)) + sqrt(lengthSquared(ab))
            radiusOut[0] = conditionRadius(bulk, sqrt(lengthSquared(num)), crossBulk, offsetNoise, abs(det))
        }
        return p
    }

    private fun sortPolygonOnPlane(points: List<Vec3>, normal: Vec3): List<Vec3> {
        val n = normalize(normal)
        val center = average(points)
        val u = orthogonalUnit(n)
        val v = normalize(cross(n, u))

        val sorted = points.sortedBy { p ->
            val r = sub(p, center)
            kotlin.math.atan2(dot(r, v).toDouble(), dot(r, u).toDouble())
        }

        return if (signedAreaAlong(sorted, n) >= 0f) sorted else sorted.reversed()
    }

    private fun signedAreaAlong(polygon: List<Vec3>, normal: Vec3): Float {
        if (polygon.size < 3) return 0f

        var accum = Vec3(0f, 0f, 0f)
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            accum = add(accum, cross(a, b))
        }
        return 0.5f * dot(accum, normal)
    }
}

// -----------------------------------------------------------------------------
// Approximate 3D multiplicatively weighted Voronoi (CircularDirichlet analogue)
// -----------------------------------------------------------------------------

data class WeightedSite3(
    val position: Vec3,
    val weight: Float,
)

sealed interface SupportSurface3 {
    val samplePoint: Vec3
}

data class SupportSphere3(
    val center: Vec3,
    val radius: Float,
) : SupportSurface3 {
    override val samplePoint: Vec3 = add(center, Vec3(radius, 0f, 0f))
}

data class SupportPlane3(
    val normal: Vec3,
    val d: Float, // plane: normal · x = d
) : SupportSurface3 {
    override val samplePoint: Vec3 = mul(normal, d / max(1e-20f, dot(normal, normal)))
}

sealed interface CircularDirichletPatch3 {
    val siteIndex: Int
    val neighborSite: Int
    val support: SupportSurface3
    val truncated: Boolean
}

data class SpherePatch3(
    override val siteIndex: Int,
    override val neighborSite: Int,
    override val support: SupportSphere3,
    val boundary: List<Vec3>, // empty => full sphere
    override val truncated: Boolean,
) : CircularDirichletPatch3

data class PlanePatch3(
    override val siteIndex: Int,
    override val neighborSite: Int,
    override val support: SupportPlane3,
    val polygon: List<Vec3>,
    override val truncated: Boolean,
) : CircularDirichletPatch3

data class CircularDirichletCell3(
    val siteIndex: Int,
    val patches: List<CircularDirichletPatch3>,
)

data class CircularDirichletDiagram3(
    val cells: List<CircularDirichletCell3>,
    val clipBounds: Aabb3,
) {
    operator fun get(siteIndex: Int): CircularDirichletCell3 = cells[siteIndex]
}

object CircularDirichlet3d {
    private data class ScalarVertex(
        val point: Vec3,
        val value: Float,
    )

    private data class Triangle(
        val a: Int,
        val b: Int,
        val c: Int,
    )

    private data class SurfaceMesh(
        val vertices: List<Vec3>,
        val triangles: List<Triangle>,
    )

    private data class PlaneSampleMesh(
        val mesh: SurfaceMesh,
        val origin: Vec3,
        val u: Vec3,
        val v: Vec3,
        val extent: Float,
    )

    private data class Segment(
        val a: Vec3,
        val b: Vec3,
    )

    fun build(
        sites: List<WeightedSite3>,
        tolerance: Float = 1e-4f,
        clipPadding: Float = 8f,
        sphereSubdivisions: Int = 3,
        planeSteps: Int = 56,
    ): CircularDirichletDiagram3 {
        require(tolerance > 0f) { "tolerance must be > 0" }
        require(sphereSubdivisions >= 0) { "sphereSubdivisions must be >= 0" }
        require(planeSteps >= 2) { "planeSteps must be >= 2" }

        sites.forEachIndexed { i, s ->
            require(s.weight > 0f) { "All weights must be > 0, but site $i had weight=${s.weight}" }
        }

        if (sites.isEmpty()) {
            val zero = Vec3(0f, 0f, 0f)
            return CircularDirichletDiagram3(emptyList(), Aabb3(v3(zero), v3(zero)))
        }

        val positions = sites.map { it.position }
        val scale = geometryScale(positions)
        val geoTol = tolerance * max(1f, scale)
        val minWeight = sites.minOf { it.weight }
        val scoreTol = tolerance * (1f + sq(scale / minWeight))
        val clip = buildClipBox(positions, scale * clipPadding + 1f)

        val bisectors = Array(sites.size) { arrayOfNulls<SupportSurface3>(sites.size) }
        for (i in sites.indices) {
            for (j in i + 1 until sites.size) {
                val surface = bisectorOf(sites[i], sites[j], tolerance, geoTol)
                bisectors[i][j] = surface
                bisectors[j][i] = surface
            }
        }

        val sphereUnitMesh = buildUnitOctasphere(subdivisions = sphereSubdivisions)
        val cells = ArrayList<CircularDirichletCell3>(sites.size)

        for (i in sites.indices) {
            val patches = ArrayList<CircularDirichletPatch3>()

            for (j in sites.indices) {
                if (i == j) continue
                when (val support = bisectors[i][j]) {
                    is SupportSphere3 -> patches += contourSpherePatches(
                        siteIndex = i,
                        neighborSite = j,
                        support = support,
                        sites = sites,
                        scoreTol = scoreTol,
                        geoTol = geoTol,
                        unitMesh = sphereUnitMesh,
                        clip = clip,
                    )
                    is SupportPlane3 -> patches += contourPlanePatches(
                        siteIndex = i,
                        neighborSite = j,
                        support = support,
                        sites = sites,
                        scoreTol = scoreTol,
                        geoTol = geoTol,
                        planeSteps = planeSteps,
                        clip = clip,
                    )
                    null -> Unit
                }
            }

            patches.sortWith(compareBy<CircularDirichletPatch3>({ it.neighborSite }, { patchSortKey(it) }))
            cells += CircularDirichletCell3(siteIndex = i, patches = patches)
        }

        return CircularDirichletDiagram3(cells = cells, clipBounds = clip)
    }

    private fun patchSortKey(patch: CircularDirichletPatch3): Float {
        return when (patch) {
            is SpherePatch3 -> patch.support.radius
            is PlanePatch3 -> 0f
        }
    }

    private fun contourSpherePatches(
        siteIndex: Int,
        neighborSite: Int,
        support: SupportSphere3,
        sites: List<WeightedSite3>,
        scoreTol: Float,
        geoTol: Float,
        unitMesh: SurfaceMesh,
        clip: Aabb3,
    ): List<SpherePatch3> {
        val worldMesh = transformUnitSphereMesh(unitMesh, support.center, support.radius)
        val scalars = FloatArray(worldMesh.vertices.size)
        var allInside = true
        var anyInside = false

        for (idx in worldMesh.vertices.indices) {
            val p = worldMesh.vertices[idx]
            val value = patchScalar(p, siteIndex, neighborSite, sites, scoreTol)
            scalars[idx] = value
            if (value >= 0f) anyInside = true else allInside = false
        }

        if (!anyInside) return emptyList()
        if (allInside) {
            return listOf(
                SpherePatch3(
                    siteIndex = siteIndex,
                    neighborSite = neighborSite,
                    support = SupportSphere3(v3(support.center), support.radius),
                    boundary = emptyList(),
                    truncated = false,
                )
            )
        }

        val loops = extractBoundaryLoops(
            mesh = worldMesh,
            scalars = scalars,
            geoTol = geoTol,
            projector = { p -> projectToSphere(p, support.center, support.radius) },
        )

        val out = ArrayList<SpherePatch3>()
        for (loop in loops) {
            if (loop.size < 3) continue
            val wound = orderLoopOnSphere(loop, support.center)
            val truncated = wound.any { !pointInAabb(it, clip, geoTol) }
            out += SpherePatch3(
                siteIndex = siteIndex,
                neighborSite = neighborSite,
                support = SupportSphere3(v3(support.center), support.radius),
                boundary = wound,
                truncated = truncated,
            )
        }

        return out
    }

    private fun contourPlanePatches(
        siteIndex: Int,
        neighborSite: Int,
        support: SupportPlane3,
        sites: List<WeightedSite3>,
        scoreTol: Float,
        geoTol: Float,
        planeSteps: Int,
        clip: Aabb3,
    ): List<PlanePatch3> {
        val planeMesh = buildPlaneSampleMesh(support, clip, planeSteps)
        val mesh = planeMesh.mesh
        val scalars = FloatArray(mesh.vertices.size)
        var anyInside = false

        for (idx in mesh.vertices.indices) {
            val value = patchScalar(mesh.vertices[idx], siteIndex, neighborSite, sites, scoreTol)
            scalars[idx] = value
            if (value >= 0f) anyInside = true
        }

        if (!anyInside) return emptyList()

        val loops = extractBoundaryLoops(
            mesh = mesh,
            scalars = scalars,
            geoTol = geoTol,
            projector = { p -> projectToPlane(p, support) },
        )

        val out = ArrayList<PlanePatch3>()
        for (loop in loops) {
            if (loop.size < 3) continue
            val wound = orderLoopOnPlane(loop, support.normal)
            val truncated = wound.any { nearPlaneMeshBoundary(it, planeMesh, geoTol * 8f) }
            out += PlanePatch3(
                siteIndex = siteIndex,
                neighborSite = neighborSite,
                support = SupportPlane3(v3(support.normal), support.d),
                polygon = wound,
                truncated = truncated,
            )
        }

        return out
    }

    private fun extractBoundaryLoops(
        mesh: SurfaceMesh,
        scalars: FloatArray,
        geoTol: Float,
        projector: (Vec3) -> Vec3,
    ): List<List<Vec3>> {
        data class Key(val x: Int, val y: Int, val z: Int)
        data class OrientedEdge(val a: Vec3, val b: Vec3)

        val eps = max(1e-6f, geoTol)
        val scale = 1f / eps

        fun keyOf(p: Vec3): Key = Key(
            round(p.x * scale).toInt(),
            round(p.y * scale).toInt(),
            round(p.z * scale).toInt(),
        )

        fun edgeKey(a: Vec3, b: Vec3): Pair<Key, Key> {
            val ka = keyOf(a)
            val kb = keyOf(b)
            return if (ka.hashCode() <= kb.hashCode()) ka to kb else kb to ka
        }

        val counts = HashMap<Pair<Key, Key>, Int>()
        val exemplar = HashMap<Pair<Key, Key>, OrientedEdge>()

        for (tri in mesh.triangles) {
            val poly = clipTriangleAgainstZero(
                ScalarVertex(mesh.vertices[tri.a], scalars[tri.a]),
                ScalarVertex(mesh.vertices[tri.b], scalars[tri.b]),
                ScalarVertex(mesh.vertices[tri.c], scalars[tri.c]),
                projector,
            )

            if (poly.size < 3) continue

            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                if (distanceSquared(a, b) <= eps * eps) continue
                val key = edgeKey(a, b)
                counts[key] = (counts[key] ?: 0) + 1
                exemplar.putIfAbsent(key, OrientedEdge(a, b))
            }
        }

        val boundarySegments = ArrayList<Segment>()
        for ((key, count) in counts) {
            if (count == 1) {
                val e = exemplar[key]!!
                boundarySegments += Segment(e.a, e.b)
            }
        }

        return stitchSegments(boundarySegments, eps)
    }

    private fun clipTriangleAgainstZero(
        a: ScalarVertex,
        b: ScalarVertex,
        c: ScalarVertex,
        projector: (Vec3) -> Vec3,
    ): List<Vec3> {
        var poly = listOf(a, b, c)
        val out = ArrayList<ScalarVertex>(4)

        fun clipEdge(input: List<ScalarVertex>): List<ScalarVertex> {
            out.clear()
            if (input.isEmpty()) return emptyList()

            for (i in input.indices) {
                val p = input[i]
                val q = input[(i + 1) % input.size]
                val pIn = p.value >= 0f
                val qIn = q.value >= 0f

                when {
                    pIn && qIn -> out += q
                    pIn && !qIn -> out += ScalarVertex(
                        point = projector(interpolateIso(p.point, p.value, q.point, q.value)),
                        value = 0f,
                    )
                    !pIn && qIn -> {
                        out += ScalarVertex(
                            point = projector(interpolateIso(p.point, p.value, q.point, q.value)),
                            value = 0f,
                        )
                        out += q
                    }
                    else -> Unit
                }
            }

            return out.toList()
        }

        poly = clipEdge(poly)
        return poly.map { v3(it.point) }
    }

    private fun interpolateIso(a: Vec3, sa: Float, b: Vec3, sb: Float): Vec3 {
        val denom = sa - sb
        val t = if (abs(denom) <= 1e-20f) 0.5f else (sa / denom).coerceIn(0f, 1f)
        return lerp(a, b, t)
    }

    private fun stitchSegments(segments: List<Segment>, eps: Float): List<List<Vec3>> {
        if (segments.isEmpty()) return emptyList()

        data class Key(val x: Int, val y: Int, val z: Int)

        fun keyOf(p: Vec3): Key {
            val s = 1f / max(1e-6f, eps)
            return Key(
                round(p.x * s).toInt(),
                round(p.y * s).toInt(),
                round(p.z * s).toInt(),
            )
        }

        val canonical = HashMap<Key, Vec3>()
        val adjacency = HashMap<Key, MutableList<Key>>()

        fun addDirected(a: Vec3, b: Vec3) {
            val ka = keyOf(a)
            val kb = keyOf(b)
            canonical.putIfAbsent(ka, v3(a))
            canonical.putIfAbsent(kb, v3(b))
            adjacency.getOrPut(ka) { ArrayList() } += kb
            adjacency.getOrPut(kb) { ArrayList() } += ka
        }

        for (seg in segments) {
            if (distanceSquared(seg.a, seg.b) <= eps * eps) continue
            addDirected(seg.a, seg.b)
        }

        val visited = HashSet<Pair<Key, Key>>()
        val loops = ArrayList<List<Vec3>>()

        for ((start, nexts) in adjacency) {
            for (next in nexts) {
                val edge = unorderedPair(start, next)
                if (!visited.add(edge)) continue

                val loop = ArrayList<Vec3>()
                var prev = start
                var curr = next
                loop += v3(canonical[start]!!)
                loop += v3(canonical[next]!!)

                while (true) {
                    val neighbors = adjacency[curr].orEmpty()
                    val candidate = neighbors.firstOrNull { unorderedPair(curr, it) !in visited && it != prev }
                        ?: neighbors.firstOrNull { unorderedPair(curr, it) !in visited }

                    if (candidate == null) break
                    val e = unorderedPair(curr, candidate)
                    if (!visited.add(e)) break

                    prev = curr
                    curr = candidate

                    if (curr == start) break
                    loop += v3(canonical[curr]!!)
                }

                if (loop.size >= 3) {
                    val cleaned = simplifyClosedLoop(loop, eps)
                    if (cleaned.size >= 3) loops += cleaned
                }
            }
        }

        return dedupeLoops(loops, eps)
    }

    private fun <T> unorderedPair(a: T, b: T): Pair<T, T> {
        val ha = a?.hashCode() ?: 0
        val hb = b?.hashCode() ?: 0
        return if (ha <= hb) a to b else b to a
    }

    private fun dedupeLoops(loops: List<List<Vec3>>, eps: Float): List<List<Vec3>> {
        val out = ArrayList<List<Vec3>>()
        val seen = ArrayList<List<Vec3>>()

        fun centroid(loop: List<Vec3>): Vec3 = average(loop)

        for (loop in loops) {
            val c = centroid(loop)
            val duplicate = seen.any { other -> distanceSquared(c, centroid(other)) <= eps * eps && abs(loopAreaEstimate(loop) - loopAreaEstimate(other)) <= eps * eps }
            if (!duplicate) {
                seen += loop
                out += loop
            }
        }

        return out
    }

    private fun simplifyClosedLoop(loop: List<Vec3>, eps: Float): List<Vec3> {
        if (loop.isEmpty()) return emptyList()
        val out = ArrayList<Vec3>()

        for (p in loop) {
            if (out.isEmpty() || distanceSquared(out.last(), p) > eps * eps) out += v3(p)
        }

        if (out.size >= 2 && distanceSquared(out.first(), out.last()) <= eps * eps) {
            out.removeAt(out.lastIndex)
        }

        if (out.size < 3) return out

        val simplified = ArrayList<Vec3>()
        for (i in out.indices) {
            val a = out[(i - 1 + out.size) % out.size]
            val b = out[i]
            val c = out[(i + 1) % out.size]
            val ab = sub(b, a)
            val bc = sub(c, b)
            if (lengthSquared(cross(ab, bc)) > eps * eps * max(lengthSquared(ab), lengthSquared(bc), 1f)) {
                simplified += b
            }
        }

        return if (simplified.size >= 3) simplified else out
    }

    private fun orderLoopOnSphere(loop: List<Vec3>, center: Vec3): List<Vec3> {
        if (loop.size < 3) return loop

        var mean = Vec3(0f, 0f, 0f)
        for (p in loop) mean = add(mean, normalize(sub(p, center)))
        val normal = normalize(mean)
        val u = orthogonalUnit(normal)
        val v = normalize(cross(normal, u))
        val center3 = average(loop)

        val sorted = loop.sortedBy { p ->
            val d = sub(p, center3)
            kotlin.math.atan2(dot(d, v).toDouble(), dot(d, u).toDouble())
        }

        return if (signedAreaAlong(sorted, normal) >= 0f) sorted else sorted.reversed()
    }

    private fun orderLoopOnPlane(loop: List<Vec3>, planeNormal: Vec3): List<Vec3> {
        if (loop.size < 3) return loop
        val n = normalize(planeNormal)
        val center = average(loop)
        val u = orthogonalUnit(n)
        val v = normalize(cross(n, u))

        val sorted = loop.sortedBy { p ->
            val d = sub(p, center)
            kotlin.math.atan2(dot(d, v).toDouble(), dot(d, u).toDouble())
        }

        return if (signedAreaAlong(sorted, n) >= 0f) sorted else sorted.reversed()
    }

    private fun nearPlaneMeshBoundary(p: Vec3, mesh: PlaneSampleMesh, eps: Float): Boolean {
        val d = sub(p, mesh.origin)
        val du = dot(d, mesh.u)
        val dv = dot(d, mesh.v)
        return abs(abs(du) - mesh.extent) <= eps || abs(abs(dv) - mesh.extent) <= eps
    }

    private fun transformUnitSphereMesh(mesh: SurfaceMesh, center: Vec3, radius: Float): SurfaceMesh {
        val verts = ArrayList<Vec3>(mesh.vertices.size)
        for (v in mesh.vertices) verts += add(center, mul(v, radius))
        return SurfaceMesh(vertices = verts, triangles = mesh.triangles)
    }

    private fun buildUnitOctasphere(subdivisions: Int): SurfaceMesh {
        val vertices = ArrayList<Vec3>()
        vertices += normalize(Vec3( 1f, 0f, 0f))
        vertices += normalize(Vec3(-1f, 0f, 0f))
        vertices += normalize(Vec3(0f,  1f, 0f))
        vertices += normalize(Vec3(0f, -1f, 0f))
        vertices += normalize(Vec3(0f, 0f,  1f))
        vertices += normalize(Vec3(0f, 0f, -1f))

        var triangles = listOf(
            Triangle(4, 0, 2),
            Triangle(4, 2, 1),
            Triangle(4, 1, 3),
            Triangle(4, 3, 0),
            Triangle(5, 2, 0),
            Triangle(5, 1, 2),
            Triangle(5, 3, 1),
            Triangle(5, 0, 3),
        ).map { orientSphereTriangle(it, vertices) }

        repeat(subdivisions) {
            val next = ArrayList<Triangle>(triangles.size * 4)
            val midpointCache = HashMap<Long, Int>()

            fun midpointIndex(i: Int, j: Int): Int {
                val a = min(i, j)
                val b = max(i, j)
                val key = (a.toLong() shl 32) xor b.toLong()
                return midpointCache.getOrPut(key) {
                    val m = normalize(mul(add(vertices[a], vertices[b]), 0.5f))
                    vertices += m
                    vertices.lastIndex
                }
            }

            for (tri in triangles) {
                val ab = midpointIndex(tri.a, tri.b)
                val bc = midpointIndex(tri.b, tri.c)
                val ca = midpointIndex(tri.c, tri.a)

                next += orientSphereTriangle(Triangle(tri.a, ab, ca), vertices)
                next += orientSphereTriangle(Triangle(tri.b, bc, ab), vertices)
                next += orientSphereTriangle(Triangle(tri.c, ca, bc), vertices)
                next += orientSphereTriangle(Triangle(ab, bc, ca), vertices)
            }

            triangles = next
        }

        return SurfaceMesh(vertices = vertices.map(::v3), triangles = triangles)
    }

    private fun orientSphereTriangle(tri: Triangle, vertices: List<Vec3>): Triangle {
        val a = vertices[tri.a]
        val b = vertices[tri.b]
        val c = vertices[tri.c]
        val normal = cross(sub(b, a), sub(c, a))
        val centroid = mul(add(add(a, b), c), 1f / 3f)
        return if (dot(normal, centroid) >= 0f) tri else Triangle(tri.a, tri.c, tri.b)
    }

    private fun buildPlaneSampleMesh(
        plane: SupportPlane3,
        clip: Aabb3,
        steps: Int,
    ): PlaneSampleMesh {
        val n = normalize(plane.normal)
        val u = orthogonalUnit(n)
        val v = normalize(cross(n, u))
        val p0 = mul(plane.normal, plane.d / max(1e-20f, dot(plane.normal, plane.normal)))

        var extent = 1f
        val corners = clipCorners(clip)
        for (c in corners) {
            val projected = projectToPlane(c, plane)
            val d = sub(projected, p0)
            extent = max(extent, abs(dot(d, u)))
            extent = max(extent, abs(dot(d, v)))
        }
        extent *= 1.25f

        val vertices = ArrayList<Vec3>((steps + 1) * (steps + 1))
        val triangles = ArrayList<Triangle>(steps * steps * 2)

        fun idx(ix: Int, iy: Int): Int = iy * (steps + 1) + ix

        for (iy in 0..steps) {
            val fy = iy.toFloat() / steps.toFloat()
            val sv = lerp(-extent, extent, fy)
            for (ix in 0..steps) {
                val fx = ix.toFloat() / steps.toFloat()
                val su = lerp(-extent, extent, fx)
                val p = add(add(p0, mul(u, su)), mul(v, sv))
                vertices += p
            }
        }

        for (iy in 0 until steps) {
            for (ix in 0 until steps) {
                val a = idx(ix, iy)
                val b = idx(ix + 1, iy)
                val c = idx(ix + 1, iy + 1)
                val d = idx(ix, iy + 1)

                triangles += orientPlaneTriangle(Triangle(a, b, c), vertices, n)
                triangles += orientPlaneTriangle(Triangle(a, c, d), vertices, n)
            }
        }

        return PlaneSampleMesh(
            mesh = SurfaceMesh(vertices = vertices, triangles = triangles),
            origin = p0,
            u = u,
            v = v,
            extent = extent,
        )
    }

    private fun orientPlaneTriangle(tri: Triangle, vertices: List<Vec3>, normal: Vec3): Triangle {
        val a = vertices[tri.a]
        val b = vertices[tri.b]
        val c = vertices[tri.c]
        return if (dot(cross(sub(b, a), sub(c, a)), normal) >= 0f) tri else Triangle(tri.a, tri.c, tri.b)
    }

    private fun clipCorners(clip: Aabb3): List<Vec3> {
        val xs = floatArrayOf(clip.min.x, clip.max.x)
        val ys = floatArrayOf(clip.min.y, clip.max.y)
        val zs = floatArrayOf(clip.min.z, clip.max.z)
        val out = ArrayList<Vec3>(8)
        for (x in xs) for (y in ys) for (z in zs) out += Vec3(x, y, z)
        return out
    }

    private fun bisectorOf(
        a: WeightedSite3,
        b: WeightedSite3,
        tol: Float,
        geoTol: Float,
    ): SupportSurface3? {
        val invWa2 = 1f / (a.weight * a.weight)
        val invWb2 = 1f / (b.weight * b.weight)

        val A = invWa2 - invWb2
        val B = sub(mul(b.position, 2f * invWb2), mul(a.position, 2f * invWa2))
        val C = dot(a.position, a.position) * invWa2 - dot(b.position, b.position) * invWb2

        val sameWeight = abs(A) <= tol * max(1f, max(abs(invWa2), abs(invWb2)))

        if (sameWeight) {
            val n2 = dot(B, B)
            if (n2 <= geoTol * geoTol) return null
            return SupportPlane3(normal = B, d = -C)
        }

        if (abs(A) <= 1e-12f) return null

        val center = mul(B, -1f / (2f * A))
        val r2 = dot(center, center) - C / A
        if (r2 <= geoTol * geoTol) return null
        if (r2 < 0f && abs(r2) > 16f * geoTol * geoTol) return null

        return SupportSphere3(center = center, radius = sqrt(max(0f, r2)))
    }

    private fun patchScalar(
        point: Vec3,
        siteIndex: Int,
        neighborSite: Int,
        sites: List<WeightedSite3>,
        scoreTol: Float,
    ): Float {
        val base = weightedScore(point, sites[siteIndex])
        var minDelta = Float.POSITIVE_INFINITY

        for (k in sites.indices) {
            if (k == siteIndex || k == neighborSite) continue
            val s = weightedScore(point, sites[k])
            val delta = s - base
            if (delta < minDelta) minDelta = delta
        }

        return if (minDelta.isFinite()) minDelta - scoreTol else 1f
    }

    private fun weightedScore(point: Vec3, site: WeightedSite3): Float {
        val dx = point.x - site.position.x
        val dy = point.y - site.position.y
        val dz = point.z - site.position.z
        return (dx * dx + dy * dy + dz * dz) / (site.weight * site.weight)
    }
}

// -----------------------------------------------------------------------------
// Shared helpers
// -----------------------------------------------------------------------------

private fun buildClipBox(points: List<Vec3>, pad: Float): Aabb3 {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY

    for (p in points) {
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
        if (p.z > maxZ) maxZ = p.z
    }

    return Aabb3(
        min = Vec3(minX - pad, minY - pad, minZ - pad),
        max = Vec3(maxX + pad, maxY + pad, maxZ + pad),
    )
}

private fun pointInAabb(p: Vec3, box: Aabb3, eps: Float = 1e-6f): Boolean {
    return p.x >= box.min.x - eps && p.x <= box.max.x + eps &&
            p.y >= box.min.y - eps && p.y <= box.max.y + eps &&
            p.z >= box.min.z - eps && p.z <= box.max.z + eps
}

private fun geometryScale(points: List<Vec3>): Float {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY

    for (p in points) {
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
        if (p.z > maxZ) maxZ = p.z
    }

    val dx = maxX - minX
    val dy = maxY - minY
    val dz = maxZ - minZ
    return max(1f, sqrt(dx * dx + dy * dy + dz * dz))
}

private fun uniquePoints(points: List<Vec3>, eps: Float): List<Vec3> {
    val out = ArrayList<Vec3>(points.size)
    for (p in points) addUnique(out, p, eps)
    return out
}

private fun addUnique(points: MutableList<Vec3>, p: Vec3, eps: Float) {
    val eps2 = eps * eps
    for (q in points) {
        if (distanceSquared(p, q) <= eps2) return
    }
    points += v3(p)
}

private fun average(points: List<Vec3>): Vec3 {
    var sx = 0f
    var sy = 0f
    var sz = 0f
    for (p in points) {
        sx += p.x
        sy += p.y
        sz += p.z
    }
    val inv = 1f / points.size.toFloat()
    return Vec3(sx * inv, sy * inv, sz * inv)
}

private fun signedAreaAlong(polygon: List<Vec3>, normal: Vec3): Float {
    if (polygon.size < 3) return 0f
    var accum = Vec3(0f, 0f, 0f)
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[(i + 1) % polygon.size]
        accum = add(accum, cross(a, b))
    }
    return 0.5f * dot(accum, normal)
}

private fun loopAreaEstimate(loop: List<Vec3>): Float {
    if (loop.size < 3) return 0f
    val normal = normalize(cross(sub(loop[1], loop[0]), sub(loop[2], loop[0])))
    return abs(signedAreaAlong(loop, normal))
}

private fun orthogonalUnit(n: Vec3): Vec3 {
    val ax = abs(n.x)
    val ay = abs(n.y)
    val az = abs(n.z)

    val seed = when {
        ax <= ay && ax <= az -> Vec3(1f, 0f, 0f)
        ay <= az -> Vec3(0f, 1f, 0f)
        else -> Vec3(0f, 0f, 1f)
    }

    return normalize(cross(seed, n))
}

private fun projectToSphere(p: Vec3, center: Vec3, radius: Float): Vec3 {
    val d = sub(p, center)
    return add(center, mul(normalize(d), radius))
}

private fun projectToPlane(p: Vec3, plane: SupportPlane3): Vec3 {
    val n2 = dot(plane.normal, plane.normal)
    if (n2 <= 1e-20f) return v3(p)
    val t = (dot(plane.normal, p) - plane.d) / n2
    return sub(p, mul(plane.normal, t))
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
private fun lerp(a: Vec3, b: Vec3, t: Float): Vec3 = Vec3(
    lerp(a.x, b.x, t),
    lerp(a.y, b.y, t),
    lerp(a.z, b.z, t),
)

private fun sq(v: Float): Float = v * v

private fun v3(v: Vec3): Vec3 = Vec3(v.x, v.y, v.z)

private fun dot(a: Vec3, b: Vec3): Float = a.x * b.x + a.y * b.y + a.z * b.z
private fun cross(a: Vec3, b: Vec3): Vec3 = Vec3(
    a.y * b.z - a.z * b.y,
    a.z * b.x - a.x * b.z,
    a.x * b.y - a.y * b.x,
)

private fun add(a: Vec3, b: Vec3): Vec3 = Vec3(a.x + b.x, a.y + b.y, a.z + b.z)
private fun sub(a: Vec3, b: Vec3): Vec3 = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
private fun mul(v: Vec3, s: Float): Vec3 = Vec3(v.x * s, v.y * s, v.z * s)
private fun lengthSquared(v: Vec3): Float = dot(v, v)
private fun distanceSquared(a: Vec3, b: Vec3): Float = lengthSquared(sub(a, b))
private fun normalize(v: Vec3): Vec3 {
    val len2 = lengthSquared(v)
    if (len2 <= 1e-20f) return Vec3(1f, 0f, 0f)
    val inv = 1f / sqrt(len2)
    return mul(v, inv)
}

private fun max(a: Float, b: Float, c: Float): Float = max(a, max(b, c))
