package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

    val size: Int get() = coordinates.size
    val vertexCoordinates: List<DoubleArray> get() = coordinates.map { it.copyOf() }

    operator fun get(index: Int): DoubleArray = coordinates[index].copyOf()

    internal fun raw(index: Int): DoubleArray = coordinates[index]
}

/**
 * A rank-4 abstract polytope with an optional four-dimensional realization.
 *
 * This exists to exercise the incidence core at a rank the Conway surface operators cannot reach.
 * Every rank-3 assumption stays out of it: the topology has four proper ranks (vertices, edges,
 * polygons, cells) plus the whole-polytope element, an edge belongs to three or more polygons
 * rather than exactly two, and there is deliberately no `triangulate()`. Viewing one is a
 * [Projection4] above realization, not a reason to flatten the topology.
 */
data class Polytope4(
    val topology: PolytopeTopology,
    val realization: Realization4? = null,
) {
    init {
        require(topology.rank == 4) { "Polytope4 requires a rank-4 topology, got rank ${topology.rank}" }
        require(realization == null || realization.size == topology.elements(0).size) {
            "realization has ${realization?.size} coordinates for ${topology.elements(0).size} topology vertices"
        }
    }

    val vertices: List<Element> get() = topology.elements(0)
    val edges: List<Element> get() = topology.elements(1)
    val polygons: List<Element> get() = topology.elements(2)
    val cells: List<Element> get() = topology.elements(3)

    /** The abstract dual. Vertices become cells and polygons become edges. */
    fun dual(): Polytope4 = Polytope4(topology.dual())
}

/** Compact inspection value; the counts and the Euler relation are what matter at a prompt. */
data class Polytope4Summary(
    val vertices: Int,
    val edges: Int,
    val polygons: Int,
    val cells: Int,
    val polygonDegrees: Map<Int, Int>,
    val cellPolygonCounts: Map<Int, Int>,
    /** `V - E + F - C`, which is zero for every convex 4-polytope. */
    val eulerCharacteristic: Int,
    val valid: Boolean,
    val realized: Boolean,
)

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

/** The regular 5-cell (4-simplex): 5 vertices, 10 edges, 10 triangles, 5 tetrahedral cells. */
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

/** The tesseract (8-cell): 16 vertices, 32 edges, 24 squares, 8 cubic cells. */
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

/** The 16-cell (4-orthoplex): 8 vertices, 24 edges, 32 triangles, 16 tetrahedral cells. */
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
 * no three-dimensional analogue, which makes it the honest test that the core is rank-general and
 * not a polyhedron library wearing a hat.
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
 * This is deliberately a separate concept from realization. A [Polytope4]'s coordinates are its
 * geometry; a projection is a camera, and swapping cameras must not be mistaken for changing the
 * object.
 */
fun interface Projection4 {
    fun project(point: DoubleArray): Vec3
}

/** Drop one axis. Honest, cheap, and usually unreadable — every cell folds onto every other. */
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
 */
data class PerspectiveProjection4(val eye: Double = 3.0, val axis: Int = 3) : Projection4 {
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
 */
data class RotatedProjection4(
    val a: Int,
    val b: Int,
    val angle: Double,
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

/** Project every vertex, in `topology.elements(0)` order. */
fun Polytope4.project(projection: Projection4): List<Vec3> {
    val realization = realization ?: throw IllegalStateException("projection requires a 4D realization")
    return (0 until realization.size).map { projection.project(realization.raw(it)) }
}
