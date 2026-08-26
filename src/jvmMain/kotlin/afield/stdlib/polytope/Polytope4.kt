@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rank 4: the seeds, the coordinates and the viewing policy that keep the incidence core general
 * about being rank-general rather than a polyhedron library with a rank field.
 *
 * Four regular seeds — [fiveCell], [tesseract], [sixteenCell] and the self-dual [twentyFourCell] —
 * plus [Realization4] for coordinates, an abstract [Polytope4.dual], and [Projection4] for getting a
 * picture out. Everything a rank-3 reader would assume is wrong here: there are four
 * proper ranks rather than three, an edge bounds three or more polygons rather than exactly two (so
 * `validate(checkManifold = true)` *must reject* a tesseract, and does), and there is no
 * `triangulate()`, because a projected 4-polytope's polygons do not bound a surface and filling them
 * would claim they did.
 *
 * ## The face lattice is derived, not declared
 *
 * [convexPolytope4] is the one call the seeds are built from. You give it vertex coordinates and
 * outward facet normals; it works out cells, polygons, boundary orders and edges itself. So a wrong
 * derivation shows up as a wrong published count — 24 cells, 96 edges — rather than as a shape nobody
 * checked. The trade is in the name: the criteria it uses are true of convex polytopes and only of
 * convex polytopes.
 *
 * ## Coordinates, and why they are not `Vec3`
 *
 * Field's graphics vocabulary has no `Vec4` and should not grow one for this, so [Realization4]
 * stores plain `DoubleArray`s and every public read hands back a copy. That also means rank 4 is the
 * double-precision half of this package while `Realization3` is single-precision.
 *
 * ## Projection is a camera, not geometry
 *
 * ```kotlin
 * val t = tesseract()
 * val points = t.project(RotatedProjection4(0, 3, angle = 0.6))   // cube inside a cube, turning
 * ```
 *
 * A [Projection4] lives *above* realization: swapping one for another changes the picture and not
 * the object, and nothing in the topology moves when you do it.
 */

/**
 * Immutable ownership boundary around four-dimensional vertex coordinates.
 *
 * The rank-3 sibling [Realization3] wraps Field's mutable `Vec3`; there is no `Vec4` in Field's
 * graphics vocabulary and there should not be one just for this, so the storage is a plain array
 * and every public read returns a copy. Coordinates are indexed in exactly the same order as
 * `topology.elements(0)`.
 */
class Realization4(vertexCoordinates: List<DoubleArray>) {
    private val coordinates = vertexCoordinates.map {
        require(it.size == 4) { "a rank-4 realization needs four components per vertex, got ${it.size}" }
        require(it.all(Double::isFinite)) { "a rank-4 realization must be finite" }
        it.copyOf()
    }

    /** How many vertices are realized; equal to the topology's rank-0 element count. */
    val size: Int get() = coordinates.size

    /** Every coordinate, freshly copied on each read — so this is a snapshot, not a view, and not free. */
    val vertexCoordinates: List<DoubleArray> get() = coordinates.map { it.copyOf() }

    /**
     * The four components of one vertex, in `topology.elements(0)` order. A **copy**: writing into
     * the returned array changes nothing, which is the whole point of this class existing.
     */
    operator fun get(index: Int): DoubleArray = coordinates[index].copyOf()

    internal fun raw(index: Int): DoubleArray = coordinates[index]
}

/**
 * A rank-4 abstract polytope with an optional four-dimensional realization.
 *
 * This exists to exercise the incidence core at a rank the Conway surface operators cannot reach.
 * Every rank-3 assumption stays out of it: the topology has four proper ranks (vertices, edges,
 * polygons, cells) plus the whole-polytope element, an edge belongs to three or more polygons
 * rather than exactly two, and there is no `triangulate()`. Viewing one is a
 * [Projection4] above realization, not a reason to flatten the topology.
 */
data class Polytope4(
    /** Must be rank 4; the constructor rejects a rank-3 topology rather than treating it as degenerate. */
    val topology: PolytopeTopology,
    /** Optional coordinates, in `topology.elements(0)` order. Null means a purely combinatorial object. */
    val realization: Realization4? = null,
) {
    init {
        require(topology.rank == 4) { "Polytope4 requires a rank-4 topology, got rank ${topology.rank}" }
        require(realization == null || realization.size == topology.elements(0).size) {
            "realization has ${realization?.size} coordinates for ${topology.elements(0).size} topology vertices"
        }
    }

    /** Rank-0 elements, in the order every realization and projection result is indexed by. */
    val vertices: List<Element> get() = topology.elements(0)

    /** Rank-1 elements. Each belongs to three or more [polygons] here, never to exactly two. */
    val edges: List<Element> get() = topology.elements(1)

    /** Rank-2 elements: the flat polygonal faces, each with its own ordered boundary cycle. */
    val polygons: List<Element> get() = topology.elements(2)

    /** Rank-3 elements: the polyhedral facets. The rank a reader arriving from rank 3 does not expect. */
    val cells: List<Element> get() = topology.elements(3)

    /**
     * The abstract dual. Vertices become cells and polygons become edges.
     *
     * Combinatorics only: the result carries **no realization**, so [project] on it throws until
     * coordinates are supplied. Dualizing [twentyFourCell] is therefore a test of self-duality by
     * incidence, not a second picture of the same solid.
     */
    fun dual(): Polytope4 = Polytope4(topology.dual())
}

/** Compact inspection value; the counts and the Euler relation are what matter at a prompt. */
data class Polytope4Summary(
    val vertices: Int,
    val edges: Int,
    val polygons: Int,
    val cells: Int,
    /** Polygon side count to how many polygons have it: `{3=96}` for the 24-cell, all triangles. */
    val polygonDegrees: Map<Int, Int>,
    /** Polygons-per-cell to how many cells have that many: `{8=24}` for the 24-cell's octahedra. */
    val cellPolygonCounts: Map<Int, Int>,
    /** `V - E + F - C`, which is zero for every convex 4-polytope. */
    val eulerCharacteristic: Int,
    /** The *ordinary* validation verdict. Never the manifold one, which no 4-polytope can pass. */
    val valid: Boolean,
    /** Whether coordinates are attached at all — false after a [Polytope4.dual]. */
    val realized: Boolean,
)

/**
 * Reduce a [Polytope4] to the counts worth reading at a prompt, and the ones the tests assert.
 *
 * Runs a full [PolytopeTopology.validate] pass each time it is called, so it is an inspection call
 * rather than something to put in a loop. Polygons with no stored boundary cycle are simply absent
 * from [Polytope4Summary.polygonDegrees] instead of counted as zero-sided.
 */
fun Polytope4.summary(): Polytope4Summary = Polytope4Summary(
    vertices = vertices.size,
    edges = edges.size,
    polygons = polygons.size,
    cells = cells.size,
    polygonDegrees = polygons.mapNotNull { topology.faceCycleOrNull(it.id)?.size }.groupingBy { it }.eachCount().toSortedMap(),
    cellPolygonCounts = cells.map { topology.incidentDown(it.id).size }.groupingBy { it }.eachCount().toSortedMap(),
    eulerCharacteristic = topology.eulerCharacteristic(),
    valid = topology.validate().isValid,
    realized = realization != null,
)

// ==============================================================================================
// Seeds
// ==============================================================================================

/**
 * The regular 5-cell (4-simplex): 5 vertices, 10 edges, 10 triangles, 5 tetrahedral cells.
 *
 * A tetrahedron in the `w = -1/sqrt(5)` hyperplane plus an apex on the `w` axis; centred at the
 * origin, circumradius `4/sqrt(5)` (about 1.79), edge length `2*sqrt(2)`.
 */
fun fiveCell(): Polytope4 {
    val s = 1.0 / sqrt(5.0)
    val coordinates = listOf(
        doubleArrayOf(1.0, 1.0, 1.0, -s),
        doubleArrayOf(1.0, -1.0, -1.0, -s),
        doubleArrayOf(-1.0, 1.0, -1.0, -s),
        doubleArrayOf(-1.0, -1.0, 1.0, -s),
        doubleArrayOf(0.0, 0.0, 0.0, 4.0 * s),
    )
    // Each facet of a simplex is the one opposite a vertex, so the outward normals are the
    // negated vertices of the (origin-centred) simplex itself.
    return convexPolytope4(coordinates, coordinates.map { v -> DoubleArray(4) { -v[it] } })
}

/**
 * The tesseract (8-cell): 16 vertices, 32 edges, 24 squares, 8 cubic cells.
 *
 * All sixteen sign combinations of `(±1, ±1, ±1, ±1)`, so edge length 2 and circumradius 2. Under
 * [PerspectiveProjection4] this is the cube-inside-a-cube; under [OrthographicProjection4] its two
 * outermost cells land exactly on top of each other.
 */
fun tesseract(): Polytope4 {
    val coordinates = buildList {
        for (a in listOf(-1.0, 1.0)) for (b in listOf(-1.0, 1.0)) {
            for (c in listOf(-1.0, 1.0)) for (d in listOf(-1.0, 1.0)) add(doubleArrayOf(a, b, c, d))
        }
    }
    val normals = buildList {
        for (axis in 0..3) for (sign in listOf(-1.0, 1.0)) add(DoubleArray(4) { if (it == axis) sign else 0.0 })
    }
    return convexPolytope4(coordinates, normals)
}

/**
 * The 16-cell (4-orthoplex): 8 vertices, 24 edges, 32 triangles, 16 tetrahedral cells.
 *
 * The eight unit axis vectors, so it is inscribed in the unit sphere with edge length `sqrt(2)` — and
 * it is the dual of [tesseract], which is why the two seeds' vertices and facet normals are the same
 * two lists swapped over.
 */
fun sixteenCell(): Polytope4 {
    val coordinates = buildList {
        for (axis in 0..3) for (sign in listOf(1.0, -1.0)) add(DoubleArray(4) { if (it == axis) sign else 0.0 })
    }
    val normals = buildList {
        for (a in listOf(-1.0, 1.0)) for (b in listOf(-1.0, 1.0)) {
            for (c in listOf(-1.0, 1.0)) for (d in listOf(-1.0, 1.0)) add(doubleArrayOf(a, b, c, d))
        }
    }
    return convexPolytope4(coordinates, normals)
}

/**
 * The 24-cell: 24 vertices, 96 edges, 96 triangles, 24 octahedral cells. It is self-dual and has
 * no three-dimensional analogue, which makes it the real test that the core is rank-general and
 * not a polyhedron library wearing a hat.
 *
 * The 24 permutations of `(±1, ±1, 0, 0)`: circumradius and edge length are both `sqrt(2)`, so every
 * vertex is as far from the origin as it is from its neighbours. Its facet normals are the tesseract's
 * vertices together with the 16-cell's.
 */
fun twentyFourCell(): Polytope4 {
    val coordinates = buildList {
        for (i in 0..3) for (j in i + 1..3) for (si in listOf(-1.0, 1.0)) for (sj in listOf(-1.0, 1.0)) {
            add(DoubleArray(4) { if (it == i) si else if (it == j) sj else 0.0 })
        }
    }
    val normals = buildList {
        for (a in listOf(-1.0, 1.0)) for (b in listOf(-1.0, 1.0)) {
            for (c in listOf(-1.0, 1.0)) for (d in listOf(-1.0, 1.0)) add(doubleArrayOf(a, b, c, d))
        }
        for (axis in 0..3) for (sign in listOf(-1.0, 1.0)) add(DoubleArray(4) { if (it == axis) sign else 0.0 })
    }
    return convexPolytope4(coordinates, normals)
}

/**
 * Build a rank-4 incidence topology from vertex coordinates and outward facet normals.
 *
 * The whole face lattice is derived, not declared: each normal's supporting hyperplane picks out a
 * cell; every pair of cells whose shared vertices are affinely two-dimensional is a polygon (an
 * intersection of two facets of a convex 4-polytope is a face, and a two-dimensional one is exactly
 * a polygon); each polygon's boundary order comes from an angular sort in its own 2-plane; and the
 * edges are the polygons' consecutive pairs. A polygon is incident to a cell exactly when its
 * vertices are a subset of that cell's, which is the standard criterion for convex polytopes and
 * only for convex polytopes — hence the function's name.
 *
 * Nothing is silently skipped. A normal supporting fewer than four vertices, a coordinate list of
 * fewer than five, a normal or coordinate of the wrong length, no two-dimensional facet intersection
 * anywhere, or a polygon whose vertices turn out collinear all throw. Repeated normals are harmless:
 * cells are keyed by the vertex set they support, so the same hyperplane twice yields one cell. The
 * result is [PolytopeTopologyBuilder.buildValidated], so a derivation that produced a malformed
 * lattice fails here rather than downstream.
 *
 * [tolerance] is an *absolute* comparison against dot products of the normals you supply with the
 * coordinates you supply — it is therefore scale-sensitive at both ends, and the default assumes both
 * are of order one, as all four seeds here are. Every pair of cells is tested for a shared face, so
 * the work grows with the square of the facet count: this is a lattice derivation for a few dozen
 * facets, not a convex-hull algorithm for thousands.
 */
fun convexPolytope4(
    coordinates: List<DoubleArray>,
    facetNormals: List<DoubleArray>,
    tolerance: Double = 1e-9,
): Polytope4 {
    require(coordinates.size >= 5) { "a 4-polytope needs at least five vertices" }
    require(coordinates.all { it.size == 4 }) { "every coordinate needs four components" }

    val cellVertexSets = linkedSetOf<Set<Int>>()
    for (normal in facetNormals) {
        require(normal.size == 4) { "every facet normal needs four components" }
        val support = coordinates.maxOf { dot4(normal, it) }
        val onFacet = coordinates.indices.filterTo(sortedSetOf()) { dot4(normal, coordinates[it]) >= support - tolerance }
        require(onFacet.size >= 4) { "facet normal ${normal.toList()} supports only ${onFacet.size} vertices" }
        cellVertexSets += onFacet.toSet()
    }
    val cellSets = cellVertexSets.toList()

    val polygonSets = linkedSetOf<Set<Int>>()
    for (a in cellSets.indices) for (b in a + 1 until cellSets.size) {
        val shared = cellSets[a] intersect cellSets[b]
        if (shared.size >= 3 && affineDimension(coordinates, shared, tolerance) == 2) polygonSets += shared
    }
    require(polygonSets.isNotEmpty()) { "no two facets met in a two-dimensional face" }

    val polygonCycles = polygonSets.map { orderPolygon(coordinates, it) }
    val edgePairs = linkedSetOf<Pair<Int, Int>>()
    for (cycle in polygonCycles) for (i in cycle.indices) {
        val a = cycle[i]
        val b = cycle[(i + 1) % cycle.size]
        edgePairs += if (a < b) a to b else b to a
    }

    val builder = PolytopeTopologyBuilder(rank = 4)
    val vertexIds = coordinates.map { builder.addElement(0) }
    val edgeIds = edgePairs.associateWith { (a, b) ->
        builder.addElement(1).also {
            builder.addIncidence(vertexIds[a], it)
            builder.addIncidence(vertexIds[b], it)
        }
    }
    val polygonIds = polygonCycles.map { cycle ->
        val polygon = builder.addElement(2)
        for (i in cycle.indices) {
            val a = cycle[i]
            val b = cycle[(i + 1) % cycle.size]
            builder.addIncidence(edgeIds.getValue(if (a < b) a to b else b to a), polygon)
        }
        builder.setFaceCycle(polygon, cycle.map { vertexIds[it] })
        polygon
    }
    val cellIds = cellSets.map { cell ->
        val id = builder.addElement(3)
        polygonCycles.forEachIndexed { at, cycle ->
            if (cell.containsAll(cycle.toList())) builder.addIncidence(polygonIds[at], id)
        }
        id
    }
    val whole = builder.addElement(4)
    cellIds.forEach { builder.addIncidence(it, whole) }

    return Polytope4(builder.buildValidated(), Realization4(coordinates))
}

private fun dot4(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]

/** Dimension of the affine hull of the selected points, by Gram-Schmidt with a tolerance. */
private fun affineDimension(coordinates: List<DoubleArray>, selected: Collection<Int>, tolerance: Double): Int {
    val points = selected.map { coordinates[it] }
    if (points.size < 2) return 0
    val origin = points.first()
    val basis = mutableListOf<DoubleArray>()
    for (point in points.drop(1)) {
        val residual = DoubleArray(4) { point[it] - origin[it] }
        for (axis in basis) {
            val projection = dot4(residual, axis)
            for (i in 0..3) residual[i] -= projection * axis[i]
        }
        val length = sqrt(dot4(residual, residual))
        if (length > tolerance) basis += DoubleArray(4) { residual[it] / length }
    }
    return basis.size
}

/** Boundary order of a planar polygon in 4D: angular sort about its centroid in its own 2-plane. */
private fun orderPolygon(coordinates: List<DoubleArray>, selected: Collection<Int>): List<Int> {
    val members = selected.toList()
    val centre = DoubleArray(4)
    for (index in members) for (axis in 0..3) centre[axis] += coordinates[index][axis] / members.size

    fun offset(index: Int) = DoubleArray(4) { coordinates[index][it] - centre[it] }
    val first = offset(members.first())
    val firstLength = sqrt(dot4(first, first))
    require(firstLength > 1e-12) { "degenerate polygon: a vertex coincides with its own centroid" }
    val u = DoubleArray(4) { first[it] / firstLength }

    var w: DoubleArray? = null
    for (index in members.drop(1)) {
        val residual = offset(index)
        val projection = dot4(residual, u)
        for (i in 0..3) residual[i] -= projection * u[i]
        val length = sqrt(dot4(residual, residual))
        if (length > 1e-12) {
            w = DoubleArray(4) { residual[it] / length }
            break
        }
    }
    val second = requireNonNull(w) { "polygon vertices are collinear" }
    return members.sortedBy { index ->
        val offsetVector = offset(index)
        atan2(dot4(offsetVector, second), dot4(offsetVector, u))
    }
}

private inline fun <T : Any> requireNonNull(value: T?, message: () -> String): T =
    value ?: throw IllegalArgumentException(message())

// ==============================================================================================
// Projection: a viewing policy above realization
// ==============================================================================================

/**
 * How a four-dimensional point becomes a three-dimensional one for viewing.
 *
 * This is a separate concept from realization. A [Polytope4]'s coordinates are its
 * geometry; a projection is a camera, and swapping cameras must not be mistaken for changing the
 * object.
 */
fun interface Projection4 {
    /**
     * Map one four-component point to a viewing position. Called once per vertex, so it should be
     * cheap; it may throw to reject a point it cannot see, as [PerspectiveProjection4] does.
     */
    fun project(point: DoubleArray): Vec3
}

/**
 * Drop one axis. Cheap, exact, and usually unreadable — every cell folds onto every other.
 *
 * The three surviving axes become x, y and z in ascending index order, so `axis = 3` is the identity
 * on the first three components and `axis = 0` shifts the rest down.
 */
data class OrthographicProjection4(val axis: Int = 3) : Projection4 {
    init {
        require(axis in 0..3) { "projection axis must be 0..3" }
    }

    override fun project(point: DoubleArray): Vec3 {
        val kept = (0..3).filter { it != axis }
        return Vec3(point[kept[0]].toFloat(), point[kept[1]].toFloat(), point[kept[2]].toFloat())
    }
}

/**
 * Perspective from a viewpoint on one axis — the projection that makes a tesseract look like a cube
 * inside a cube and a 24-cell readable at all. [eye] must be outside the object on that axis; the
 * nearer it is, the more extreme the nesting.
 *
 * "Must" is enforced per point, at projection time rather than construction time: a vertex at or
 * behind the viewpoint throws, so one badly placed vertex fails the whole projection. The default 3.0
 * clears every seed in this file — the largest circumradius among them is the tesseract's 2 — with
 * room left for a [RotatedProjection4] in front of it.
 */
data class PerspectiveProjection4(
    /** Viewpoint distance along [axis]; smaller exaggerates the nesting, and must exceed the object's extent. */
    val eye: Double = 3.0,
    /** Which of the four coordinates is depth. The other three become x, y, z in ascending order. */
    val axis: Int = 3,
) : Projection4 {
    init {
        require(axis in 0..3) { "projection axis must be 0..3" }
        require(eye.isFinite() && eye > 0.0) { "the 4D viewpoint must be a positive finite distance" }
    }

    override fun project(point: DoubleArray): Vec3 {
        val depth = eye - point[axis]
        require(depth > 1e-6) { "vertex at ${point.toList()} is at or behind the 4D viewpoint $eye" }
        val scale = eye / depth
        val kept = (0..3).filter { it != axis }
        return Vec3(
            (point[kept[0]] * scale).toFloat(),
            (point[kept[1]] * scale).toFloat(),
            (point[kept[2]] * scale).toFloat(),
        )
    }
}

/**
 * Rotate in the `(a, b)` coordinate plane, then project. Rotations of 4-space are the reason a
 * projected 4-polytope reads as one object rather than a tangle: the inner cell turns itself
 * inside out as the angle advances.
 *
 * Only the two named coordinates move; the other two are untouched. Pick a plane that includes the
 * depth axis of [then] — `(0, 3)` against the default — and the rotation changes which cell is
 * nearest. Pick one that does not, such as `(0, 1)`, and you get an ordinary spin of the picture.
 */
data class RotatedProjection4(
    /** First axis of the rotation plane, `0..3`. */
    val a: Int,
    /** Second axis of the rotation plane, `0..3` and distinct from [a]. */
    val b: Int,
    /** Rotation in radians, applied before [then]. Animate this, not the object. */
    val angle: Double,
    /** What turns the rotated 4D point into a viewing position; nesting these composes rotations. */
    val then: Projection4 = PerspectiveProjection4(),
) : Projection4 {
    init {
        require(a in 0..3 && b in 0..3 && a != b) { "a rotation plane needs two distinct axes" }
        require(angle.isFinite()) { "rotation angle must be finite" }
    }

    override fun project(point: DoubleArray): Vec3 {
        val turned = point.copyOf()
        turned[a] = point[a] * cos(angle) - point[b] * sin(angle)
        turned[b] = point[a] * sin(angle) + point[b] * cos(angle)
        return then.project(turned)
    }
}

/**
 * Project every vertex, in `topology.elements(0)` order — so the result is index-parallel to
 * [Polytope4.vertices], and a face cycle's element ids have to be looked up in that list rather than
 * used as indices into this one.
 *
 * Throws if the polytope has no realization, which includes anything just returned by
 * [Polytope4.dual]; and propagates whatever the projection itself rejects.
 */
fun Polytope4.project(projection: Projection4): List<Vec3> {
    val realization = realization ?: throw IllegalStateException("projection requires a 4D realization")
    return (0 until realization.size).map { projection.project(realization.raw(it)) }
}
