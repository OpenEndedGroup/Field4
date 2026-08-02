package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.sqrt

/**
 * Immutable ownership boundary around Field's mutable [Vec3]. Coordinates are indexed in exactly
 * the same order as `topology.elements(0)`. Public reads return copies.
 */
class Realization3(vertexCoordinates: List<Vec3>) {
    private val coordinates = vertexCoordinates.map(::Vec3)

    val size: Int get() = coordinates.size
    val vertexCoordinates: List<Vec3> get() = coordinates.map(::Vec3)

    operator fun get(index: Int): Vec3 = Vec3(coordinates[index])

    internal fun x(index: Int): Float = coordinates[index].x
    internal fun y(index: Int): Float = coordinates[index].y
    internal fun z(index: Int): Float = coordinates[index].z
}

/** Abstract topology plus optional ordinary three-dimensional realization and element attributes. */
data class PolyhedralObject(
    val topology: PolytopeTopology,
    val realization: Realization3? = null,
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

internal fun realizedPolyhedron(coordinates: List<Vec3>, unorientedFaces: List<List<Int>>): PolyhedralObject {
    require(unorientedFaces.all { it.size >= 3 }) { "every face needs at least three vertices" }
    require(unorientedFaces.flatten().all { it in coordinates.indices }) { "face vertex index outside coordinate list" }
    val faces = orientOutward(coordinates, unorientedFaces)
    val builder = PolytopeTopologyBuilder(rank = 3)
    val vertices = coordinates.map { builder.addElement(0) }

    val edgeKeys = linkedSetOf<EdgeKey>()
    for (cycle in faces) for (i in cycle.indices) {
        edgeKeys += EdgeKey.of(cycle[i], cycle[(i + 1) % cycle.size])
    }
    val edges = linkedMapOf<EdgeKey, ElementId>()
    for (key in edgeKeys) {
        val edge = builder.addElement(1)
        edges[key] = edge
        builder.addIncidence(vertices[key.a], edge)
        builder.addIncidence(vertices[key.b], edge)
    }

    val faceElements = faces.map { cycle ->
        val face = builder.addElement(2)
        for (i in cycle.indices) {
            val key = EdgeKey.of(cycle[i], cycle[(i + 1) % cycle.size])
            builder.addIncidence(edges.getValue(key), face)
        }
        builder.setFaceCycle(face, cycle.map { vertices[it] })
        face
    }
    val whole = builder.addElement(3)
    faceElements.forEach { builder.addIncidence(it, whole) }

    val topology = builder.buildValidated(checkManifold = true)
    return PolyhedralObject(topology, Realization3(coordinates))
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

/** A cube of side length 2, centred at the origin. */
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

/** A regular icosahedron centred at the origin, with vertices on the unit sphere. */
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

/** A regular dodecahedron, constructed as the realized dual of the regular icosahedron. */
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
