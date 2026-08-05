@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.sqrt

/**
 * The rank-3 value everything in this package passes around, and the five Platonic seeds to start
 * from.
 *
 * [PolyhedralObject] is the aggregate — a [PolytopeTopology], optional coordinates, optional
 * per-element attributes — and [Realization3] is the coordinate half, kept as a separate immutable
 * value so that a Conway operator can replace it wholesale without the topology noticing. The seeds
 * are [tetrahedron], [cube], [octahedron], [icosahedron] and [dodecahedron]; each returns a fully
 * realized, outward-wound, manifold-validated object, and each is the starting point of a Conway
 * expression such as `conway("tI")`.
 *
 * [cubeWithWindow] is the sixth and the odd one out: a cube whose top face has a hole in it, which
 * makes it a surface *with boundary* rather than a closed manifold. It exists so that the parts of
 * the library that handle holes have something honest to be tested and demonstrated against.
 *
 * ## Scale is not uniform across the seeds
 *
 * [tetrahedron], [octahedron], [icosahedron] and [dodecahedron] all put their vertices on the unit
 * sphere. [cube] does not: it is the ±1 cube, so its side is 2 and its circumradius `sqrt(3)`. If you
 * are comparing seeds, or mixing them in one scene, normalise or apply a gauge first rather than
 * assuming a common radius.
 *
 * ## Face winding is derived, not trusted
 *
 * Seed faces are declared as unoriented index cycles and then reversed where needed so that every
 * normal points away from the vertex centroid. Which means the literal lists below need only be
 * correct as *cycles* — a reader checking them does not also have to check their handedness.
 */

/**
 * Immutable ownership boundary around Field's mutable [Vec3]. Coordinates are indexed in exactly
 * the same order as `topology.elements(0)`. Public reads return copies.
 */
class Realization3(vertexCoordinates: List<Vec3>) {
    private val coordinates = vertexCoordinates.map(::Vec3)

    /** How many vertices are realized; equal to the topology's rank-0 element count. */
    val size: Int get() = coordinates.size

    /** Every coordinate as a fresh [Vec3] list, rebuilt on each read — a snapshot, not a view, and not free. */
    val vertexCoordinates: List<Vec3> get() = coordinates.map(::Vec3)

    /**
     * One vertex position, by index into `topology.elements(0)` — **not** by [ElementId]. A copy:
     * `Vec3` is mutable and Field's graphics code mutates it freely, so writing into the returned
     * vector changes nothing here. That is the whole reason this class exists.
     */
    operator fun get(index: Int): Vec3 = Vec3(coordinates[index])

    internal fun x(index: Int): Float = coordinates[index].x
    internal fun y(index: Int): Float = coordinates[index].y
    internal fun z(index: Int): Float = coordinates[index].z
}

/**
 * Abstract topology plus optional ordinary three-dimensional realization and element attributes.
 *
 * The two optional halves are what make it worth having: a topology-only object is legal and useful
 * (dualize it, then choose a realization policy), and attributes ride along through a whole Conway
 * chain under a propagation policy. The constructor checks only the two things it can: that a
 * realization has one coordinate per topology vertex, and that no attribute names an element outside
 * this topology.
 *
 * Note what it does *not* check — the rank. A rank-4 topology will construct one of these quite
 * happily. The rank-3 assumption belongs to the operators that actually need it: the Conway surface
 * transforms require rank 3, and the triangulator requires a realization, so that is where a
 * mismatched topology is caught rather than here.
 */
data class PolyhedralObject(
    /** The authoritative value; ordinarily rank 3, though nothing in this class insists on it. */
    val topology: PolytopeTopology,
    /** Coordinates, or null for a purely combinatorial object. Must have one entry per rank-0 element. */
    val realization: Realization3? = null,
    /** Per-element properties, keyed by [ElementId]; every key must be an element of [topology]. */
    val attributes: PolytopeAttributes = PolytopeAttributes.EMPTY,
) {
    init {
        require(realization == null || realization.size == topology.elements(0).size) {
            "realization has ${realization?.size} coordinates for ${topology.elements(0).size} topology vertices"
        }
        val topologyIds = topology.elementsByRank.flatten().mapTo(mutableSetOf()) { it.id }
        require(attributes.elementIds.all { it in topologyIds }) {
            "attributes reference elements outside this topology"
        }
    }
}

private data class EdgeKey(val a: Int, val b: Int) {
    companion object {
        fun of(a: Int, b: Int) = if (a < b) EdgeKey(a, b) else EdgeKey(b, a)
    }
}

internal fun realizedPolyhedron(
    coordinates: List<Vec3>,
    unorientedFaces: List<List<Int>>,
    /** Inner rings per face, keyed by index into [unorientedFaces]; wound opposite the outer ring. */
    unorientedHoles: Map<Int, List<List<Int>>> = emptyMap(),
): PolyhedralObject {
    require(unorientedFaces.all { it.size >= 3 }) { "every face needs at least three vertices" }
    require(unorientedFaces.flatten().all { it in coordinates.indices }) { "face vertex index outside coordinate list" }
    require(unorientedHoles.keys.all { it in unorientedFaces.indices }) { "hole names a face that is not there" }
    require(unorientedHoles.values.flatten().all { it.size >= 3 }) { "every hole ring needs at least three vertices" }
    val faces = orientOutward(coordinates, unorientedFaces)
    val holes = unorientedHoles.mapValues { (face, rings) ->
        // The convention is that an inner ring runs against its outer one. That is a statement about
        // geometry, so it is settled here, with coordinates in hand, rather than asked of the caller.
        val outward = newellNormal(coordinates, faces[face])
        rings.map { ring -> if (newellNormal(coordinates, ring).dot(outward) > 0f) ring.reversed() else ring }
    }
    val builder = PolytopeTopologyBuilder(rank = 3)
    val vertices = coordinates.map { builder.addElement(0) }

    fun ringsOf(face: Int): List<List<Int>> = listOf(faces[face]) + holes[face].orEmpty()

    val edgeKeys = linkedSetOf<EdgeKey>()
    for (face in faces.indices) for (cycle in ringsOf(face)) for (i in cycle.indices) {
        edgeKeys += EdgeKey.of(cycle[i], cycle[(i + 1) % cycle.size])
    }
    val edges = linkedMapOf<EdgeKey, ElementId>()
    for (key in edgeKeys) {
        val edge = builder.addElement(1)
        edges[key] = edge
        builder.addIncidence(vertices[key.a], edge)
        builder.addIncidence(vertices[key.b], edge)
    }

    val faceElements = faces.indices.map { at ->
        val face = builder.addElement(2)
        for (cycle in ringsOf(at)) for (i in cycle.indices) {
            val key = EdgeKey.of(cycle[i], cycle[(i + 1) % cycle.size])
            builder.addIncidence(edges.getValue(key), face)
        }
        builder.setFaceCycle(face, faces[at].map { vertices[it] })
        holes[at].orEmpty().forEach { ring -> builder.addFaceHole(face, ring.map { vertices[it] }) }
        face
    }
    val whole = builder.addElement(3)
    faceElements.forEach { builder.addIncidence(it, whole) }

    // A face with a hole is a surface with boundary — its inner edges bound one face, not two — so
    // the manifold pass is exactly the check that must not run on it.
    val topology = builder.buildValidated(checkManifold = unorientedHoles.isEmpty())
    return PolyhedralObject(topology, Realization3(coordinates))
}

private fun newellNormal(coordinates: List<Vec3>, cycle: List<Int>): Vec3 {
    val normal = Vec3()
    for (i in cycle.indices) {
        val p = coordinates[cycle[i]]
        val q = coordinates[cycle[(i + 1) % cycle.size]]
        normal.add(Vec3((p.y - q.y) * (p.z + q.z), (p.z - q.z) * (p.x + q.x), (p.x - q.x) * (p.y + q.y)))
    }
    return normal
}

private fun orientOutward(coordinates: List<Vec3>, faces: List<List<Int>>): List<List<Int>> {
    val center = Vec3()
    coordinates.forEach { center.add(it) }
    center.mul(1f / coordinates.size)
    return faces.map { cycle ->
        val a = coordinates[cycle[0]]
        val b = coordinates[cycle[1]]
        val c = coordinates[cycle[2]]
        val normal = Vec3(b).sub(a).cross(Vec3(c).sub(a))
        val faceCenter = Vec3()
        cycle.forEach { faceCenter.add(coordinates[it]) }
        faceCenter.mul(1f / cycle.size).sub(center)
        if (normal.dot(faceCenter) >= 0f) cycle else cycle.reversed()
    }
}

/** A regular tetrahedron centred at the origin, with vertices on the unit sphere. */
fun tetrahedron(): PolyhedralObject {
    val s = 1f / sqrt(3f)
    return realizedPolyhedron(
        listOf(
            Vec3(s, s, s),
            Vec3(s, -s, -s),
            Vec3(-s, s, -s),
            Vec3(-s, -s, s),
        ),
        listOf(
            listOf(0, 1, 2),
            listOf(0, 2, 3),
            listOf(0, 3, 1),
            listOf(1, 3, 2),
        ),
    )
}

/**
 * A cube of side length 2, centred at the origin — the eight sign combinations of `(±1, ±1, ±1)`.
 *
 * The one seed in this file whose vertices are *not* on the unit sphere: its circumradius is
 * `sqrt(3)`, about 1.73.
 */
fun cube(): PolyhedralObject = realizedPolyhedron(
    listOf(
        Vec3(-1f, -1f, -1f),
        Vec3(1f, -1f, -1f),
        Vec3(1f, 1f, -1f),
        Vec3(-1f, 1f, -1f),
        Vec3(-1f, -1f, 1f),
        Vec3(1f, -1f, 1f),
        Vec3(1f, 1f, 1f),
        Vec3(-1f, 1f, 1f),
    ),
    listOf(
        listOf(0, 1, 2, 3),
        listOf(4, 5, 6, 7),
        listOf(0, 4, 5, 1),
        listOf(1, 5, 6, 2),
        listOf(2, 6, 7, 3),
        listOf(3, 7, 4, 0),
    ),
)

/**
 * The ±1 [cube] with a square window cut through the middle of its `+z` face: the package's one seed
 * whose face boundaries are not all simple.
 *
 * Twelve vertices — the cube's eight, plus the window's four at `(±side, ±side, 1)` — six faces, and
 * one inner ring on the top face. Everything else about it is an ordinary cube.
 *
 * It is deliberately **not a closed manifold**, and that is the point of it rather than a defect: the
 * window's four edges bound one face each, so `validate(checkManifold = true)` rejects it and the
 * Conway operators refuse it, both with messages that say so. `validate()` on its own passes. Use it
 * to exercise the parts of the library that do handle a hole — [PolyhedralObject.triangulate],
 * [PolyhedralObject.toFaceFLines], `cellularEulerCharacteristic` (which is 1 here, not 2).
 *
 * @param side half the window's width, in the cube's own coordinates, so it must be inside `(0, 1)`.
 */
fun cubeWithWindow(side: Float = 0.5f): PolyhedralObject {
    require(side > 0f && side < 1f) { "window half-width must be inside (0, 1), got $side" }
    return realizedPolyhedron(
        listOf(
            Vec3(-1f, -1f, -1f),
            Vec3(1f, -1f, -1f),
            Vec3(1f, 1f, -1f),
            Vec3(-1f, 1f, -1f),
            Vec3(-1f, -1f, 1f),
            Vec3(1f, -1f, 1f),
            Vec3(1f, 1f, 1f),
            Vec3(-1f, 1f, 1f),
            Vec3(-side, -side, 1f),
            Vec3(side, -side, 1f),
            Vec3(side, side, 1f),
            Vec3(-side, side, 1f),
        ),
        listOf(
            listOf(0, 1, 2, 3),
            listOf(4, 5, 6, 7),
            listOf(0, 4, 5, 1),
            listOf(1, 5, 6, 2),
            listOf(2, 6, 7, 3),
            listOf(3, 7, 4, 0),
        ),
        mapOf(1 to listOf(listOf(8, 9, 10, 11))),
    )
}

/** A regular octahedron centred at the origin, with vertices on the unit sphere. */
fun octahedron(): PolyhedralObject = realizedPolyhedron(
    listOf(
        Vec3(1f, 0f, 0f),
        Vec3(-1f, 0f, 0f),
        Vec3(0f, 1f, 0f),
        Vec3(0f, -1f, 0f),
        Vec3(0f, 0f, 1f),
        Vec3(0f, 0f, -1f),
    ),
    listOf(
        listOf(0, 2, 4),
        listOf(2, 1, 4),
        listOf(1, 3, 4),
        listOf(3, 0, 4),
        listOf(2, 0, 5),
        listOf(1, 2, 5),
        listOf(3, 1, 5),
        listOf(0, 3, 5),
    ),
)

/**
 * A regular icosahedron centred at the origin, with vertices on the unit sphere.
 *
 * The twelve cyclic `(0, ±1, ±phi)` points, normalised. Unlike the other seeds its faces are not
 * written down but *found*, by testing all 220 vertex triples for a supporting plane; the search
 * insists on finding exactly 20 triangles and throws if it does not, so a change to the coordinates
 * cannot quietly produce a not-quite-icosahedron.
 */
fun icosahedron(): PolyhedralObject {
    val phi = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
    val raw = buildList {
        for (a in listOf(-1f, 1f)) for (b in listOf(-phi, phi)) add(Vec3(0f, a, b))
        for (a in listOf(-1f, 1f)) for (b in listOf(-phi, phi)) add(Vec3(a, b, 0f))
        for (a in listOf(-phi, phi)) for (b in listOf(-1f, 1f)) add(Vec3(a, 0f, b))
    }
    val coordinates = raw.map { Vec3(it).normalize() }
    return realizedPolyhedron(coordinates, convexHullTriangleFaces(coordinates))
}

/**
 * A regular dodecahedron, constructed as the realized dual of the regular icosahedron.
 *
 * No pentagon is written down anywhere. The twenty vertices are [icosahedron]'s face centroids, then
 * pushed back out onto the unit sphere — a centroid is inside it — and the twelve pentagonal cycles
 * come from walking the ordered edge ring around each icosahedron vertex. So this seed costs an
 * icosahedron plus a dualization, and it is a live check on the dual operator rather than a table.
 */
fun dodecahedron(): PolyhedralObject {
    val dual = icosahedron().dual().polyhedron
    val realization = dual.realization ?: error("realized icosahedron dual lost its coordinates")
    return dual.copy(realization = Realization3(realization.vertexCoordinates.map { Vec3(it).normalize() }))
}

private fun convexHullTriangleFaces(coordinates: List<Vec3>): List<List<Int>> {
    val faces = mutableListOf<List<Int>>()
    val epsilon = 1e-5f
    for (a in 0 until coordinates.size - 2) {
        for (b in a + 1 until coordinates.size - 1) {
            for (c in b + 1 until coordinates.size) {
                val normal = Vec3(coordinates[b]).sub(coordinates[a])
                    .cross(Vec3(coordinates[c]).sub(coordinates[a]))
                if (normal.lengthSquared() <= epsilon * epsilon) continue
                var positive = false
                var negative = false
                for (point in coordinates.indices) {
                    if (point == a || point == b || point == c) continue
                    val side = normal.dot(Vec3(coordinates[point]).sub(coordinates[a]))
                    if (side > epsilon) positive = true
                    if (side < -epsilon) negative = true
                    if (positive && negative) break
                }
                if (!(positive && negative)) faces += listOf(a, b, c)
            }
        }
    }
    require(faces.size == 20) { "icosahedron hull should have 20 triangular faces, got ${faces.size}" }
    return faces
}

internal fun PolyhedralObject.realizationIndex(vertex: ElementId): Int {
    val index = topology.elements(0).indexOfFirst { it.id == vertex }
    require(index >= 0) { "element ${vertex.value} is not a topology vertex" }
    return index
}
