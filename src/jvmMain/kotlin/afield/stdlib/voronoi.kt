@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import kotlin.collections.first
import kotlin.collections.last
import kotlin.math.*
import org.joml.Vector3f as Vec3

/**
 * Practical 3D Voronoi and approximate 3D multiplicatively weighted Voronoi
 * ("CircularDirichlet") for Kotlin + JOML.
 *
 * - voronoi3d(...) builds convex polyhedral cells by intersecting pairwise
 *   bisector half-spaces, then closing unbounded cells against a large clip box.
 * - CircularDirichlet3d.build(...) extracts approximate support patches on
 *   bisector spheres / planes by clipping a sampled surface mesh with the
 *   weighted score predicate.
 *
 * The weighted contourer is renderer-oriented: it prefers stable, wound
 * boundary loops over exact symbolic patch topology.
 */

// -----------------------------------------------------------------------------
// Unweighted 3D Voronoi
// -----------------------------------------------------------------------------

data class Aabb3(
    val min: Vec3,
    val max: Vec3,
)

data class Plane3(
    val normal: Vec3, // plane: normal · x = d
    val d: Float,
)

data class VoronoiFace(
    val neighborSite: Int?,
    val plane: Plane3,
    val polygon: List<Vec3>,   // wound CCW when viewed along plane.normal
    val isClip: Boolean,
    val truncated: Boolean,
)

data class VoronoiCell3(
    val siteIndex: Int,
    val site: Vec3,
    val faces: List<VoronoiFace>,
    val bounded: Boolean,
)

data class VoronoiLattice(
    val cells: List<VoronoiCell3>,
    val clipBounds: Aabb3,
) {
    operator fun get(siteIndex: Int): VoronoiCell3 = cells[siteIndex]
}

fun voronoi3d(l: List<Vec3>): VoronoiLattice = Voronoi3d.build(l)

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

        val cells = ArrayList<VoronoiCell3>(points.size)
        for (i in points.indices) {
            cells += buildCell(
                siteIndex = i,
                points = points,
                clip = clip,
                eps = eps,
            )
        }

        return VoronoiLattice(cells = cells, clipBounds = clip)
    }

    private fun buildCell(
        siteIndex: Int,
        points: List<Vec3>,
        clip: Aabb3,
        eps: Float,
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

        val vertices = collectVertices(planes, eps)
        val faces = buildFaces(vertices, planes, eps)

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

    private fun collectVertices(
        planes: List<PlaneSpec>,
        eps: Float,
    ): List<Vec3> {
        val vertices = ArrayList<Vec3>()

        for (a in 0 until planes.size - 2) {
            for (b in a + 1 until planes.size - 1) {
                for (c in b + 1 until planes.size) {
                    val p = intersectPlanes(planes[a].plane, planes[b].plane, planes[c].plane, eps) ?: continue
                    if (!insideAll(p, planes, eps)) continue
                    addUnique(vertices, p, eps)
                }
            }
        }

        return vertices
    }

    private fun buildFaces(
        vertices: List<Vec3>,
        planes: List<PlaneSpec>,
        eps: Float,
    ): List<VoronoiFace> {
        val faces = ArrayList<VoronoiFace>()
        val planeTol = 4f * eps

        for (spec in planes) {
            val raw = ArrayList<Vec3>()
            for (v in vertices) {
                if (abs(side(spec.plane, v)) <= planeTol) raw += v3(v)
            }

            val unique = uniquePoints(raw, eps)
            if (unique.size < 3) continue

            val polygon = sortPolygonOnPlane(unique, spec.plane.normal)
            if (polygon.size < 3) continue

            val signedArea = signedAreaAlong(polygon, normalize(spec.plane.normal))
            if (abs(signedArea) <= eps * eps) continue

            val truncated = !spec.isClip && polygon.any { touchesAnyClip(it, planes, planeTol) }

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

    private fun insideAll(p: Vec3, planes: List<PlaneSpec>, eps: Float): Boolean {
        for (spec in planes) {
            if (side(spec.plane, p) > eps) return false
        }
        return true
    }

    private fun side(plane: Plane3, p: Vec3): Float = dot(plane.normal, p) - plane.d

    private fun intersectPlanes(a: Plane3, b: Plane3, c: Plane3, eps: Float): Vec3? {
        val bc = cross(b.normal, c.normal)
        val ca = cross(c.normal, a.normal)
        val ab = cross(a.normal, b.normal)

        val det = dot(a.normal, bc)
        if (abs(det) <= eps) return null

        val num = add(add(mul(bc, a.d), mul(ca, b.d)), mul(ab, c.d))
        return mul(num, 1f / det)
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
