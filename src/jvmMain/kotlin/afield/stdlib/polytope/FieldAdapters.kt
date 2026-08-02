package afield.stdlib.polytope

import afield.graphics.FLine
import afield.graphics.FloatEarcut
import afield.stdlib.TriMesh
import afield.stdlib.TriMeshBuilder
import afield.stdlib.Vec3
import kotlin.math.sqrt
import kotlin.math.abs

const val POLYTOPE_FACE_ID_PROPERTY = "polytopeFaceId"
const val POLYTOPE_FACE_RANK_PROPERTY = "polytopeFaceRank"

class DegenerateFaceGeometryException(val face: ElementId) : IllegalArgumentException(
    "face ${face.value} has zero area in its 3D realization"
)

/**
 * One closed, outward-wound [FLine] per polygonal face. Separate lines are the primary adapter so
 * callers can style, select, or fill faces independently. Field's normal `filled` and `stroked`
 * properties can be set in [configure].
 */
fun PolyhedralObject.toFaceFLines(
    configure: (face: Element, line: FLine) -> Unit = { _, _ -> },
): List<FLine> {
    require(realization != null) { "FLine conversion requires a 3D realization" }
    topology.validate().throwIfInvalid()
    return topology.elements(2).map { face ->
        val line = FLine()
        topology.faceCycle(face.id).forEachIndexed { i, vertexValue ->
            val coordinate = realizationIndex(ElementId(vertexValue))
            if (i == 0) line.moveTo(realization.x(coordinate), realization.y(coordinate), realization.z(coordinate))
            else line.lineTo(realization.x(coordinate), realization.y(coordinate), realization.z(coordinate))
        }
        line.close().also {
            line.properties.putAll(attributes[face.id])
            line.properties[POLYTOPE_FACE_ID_PROPERTY] = face.id.value
            line.properties[POLYTOPE_FACE_RANK_PROPERTY] = face.rank
            configure(face, line)
        }
    }
}

/** Unit normal selected by the face's explicit winding. */
fun PolyhedralObject.faceNormal(face: ElementId): Vec3 {
    require(realization != null) { "normal calculation requires a 3D realization" }
    require(topology.element(face)?.rank == 2) { "element ${face.value} is not a rank-2 face" }
    val cycle = topology.faceCycle(face)
    var nx = 0.0
    var ny = 0.0
    var nz = 0.0
    for (i in cycle.indices) {
        val p = realizationIndex(ElementId(cycle[i]))
        val q = realizationIndex(ElementId(cycle[(i + 1) % cycle.size]))
        nx += (realization.y(p).toDouble() - realization.y(q)) * (realization.z(p).toDouble() + realization.z(q))
        ny += (realization.z(p).toDouble() - realization.z(q)) * (realization.x(p).toDouble() + realization.x(q))
        nz += (realization.x(p).toDouble() - realization.x(q)) * (realization.y(p).toDouble() + realization.y(q))
    }
    val length = sqrt(nx * nx + ny * ny + nz * nz)
    if (!length.isFinite() || length <= 1e-12) throw DegenerateFaceGeometryException(face)
    return Vec3((nx / length).toFloat(), (ny / length).toFloat(), (nz / length).toFloat())
}

/**
 * Polygon triangulation strategy. Input and output values are indices into [Realization3], not
 * global [ElementId] values or render-buffer offsets. Implementations must return consecutive
 * triples and may only reference vertices present in the supplied face.
 */
fun interface FaceTriangulator {
    fun triangulate(faceVertices: IntArray, realization: Realization3): IntArray
}

/** Deterministic convex-polygon fan. It is deliberately not a general polygon triangulator. */
object FanFaceTriangulator : FaceTriangulator {
    override fun triangulate(faceVertices: IntArray, realization: Realization3): IntArray {
        require(faceVertices.size >= 3) { "fan triangulation needs at least three face vertices" }
        return IntArray((faceVertices.size - 2) * 3).also { triangles ->
            var out = 0
            for (i in 1 until faceVertices.size - 1) {
                triangles[out++] = faceVertices[0]
                triangles[out++] = faceVertices[i]
                triangles[out++] = faceVertices[i + 1]
            }
        }
    }
}

/**
 * Field's existing Earcut tessellator, adapted to an arbitrarily oriented 3D polygon.
 *
 * The ordered face is projected by dropping its Newell normal's dominant axis, triangulated in
 * 2D, then every returned triangle is checked and, if necessary, flipped back to the face's 3D
 * winding. This handles simple concave faces. Face holes need a richer boundary model than the
 * milestone's single face cycle, although [FloatEarcut] itself already supports hole indices.
 */
object EarcutFaceTriangulator : FaceTriangulator {
    override fun triangulate(faceVertices: IntArray, realization: Realization3): IntArray {
        require(faceVertices.size >= 3) { "Earcut triangulation needs at least three face vertices" }
        var nx = 0.0
        var ny = 0.0
        var nz = 0.0
        for (i in faceVertices.indices) {
            val p = faceVertices[i]
            val q = faceVertices[(i + 1) % faceVertices.size]
            nx += (realization.y(p) - realization.y(q)) * (realization.z(p) + realization.z(q))
            ny += (realization.z(p) - realization.z(q)) * (realization.x(p) + realization.x(q))
            nz += (realization.x(p) - realization.x(q)) * (realization.y(p) + realization.y(q))
        }
        val normalLengthSquared = nx * nx + ny * ny + nz * nz
        require(normalLengthSquared.isFinite() && normalLengthSquared > 1e-20) {
            "cannot triangulate a face with zero projected area"
        }
        val dropAxis = when {
            abs(nx) >= abs(ny) && abs(nx) >= abs(nz) -> 0
            abs(ny) >= abs(nz) -> 1
            else -> 2
        }
        val projected = FloatArray(faceVertices.size * 2)
        faceVertices.forEachIndexed { local, vertex ->
            when (dropAxis) {
                0 -> {
                    projected[2 * local] = realization.y(vertex)
                    projected[2 * local + 1] = realization.z(vertex)
                }
                1 -> {
                    projected[2 * local] = realization.x(vertex)
                    projected[2 * local + 1] = realization.z(vertex)
                }
                else -> {
                    projected[2 * local] = realization.x(vertex)
                    projected[2 * local + 1] = realization.y(vertex)
                }
            }
        }
        val localTriangles = FloatEarcut.earcut(projected, dim = 2)
        require(localTriangles.size % 3 == 0 && localTriangles.isNotEmpty()) {
            "Earcut did not produce complete triangles for a ${faceVertices.size}-vertex face"
        }
        return IntArray(localTriangles.size).also { output ->
            for (i in localTriangles.indices step 3) {
                val a = faceVertices[localTriangles[i]]
                var b = faceVertices[localTriangles[i + 1]]
                var c = faceVertices[localTriangles[i + 2]]
                val abx = realization.x(b) - realization.x(a)
                val aby = realization.y(b) - realization.y(a)
                val abz = realization.z(b) - realization.z(a)
                val acx = realization.x(c) - realization.x(a)
                val acy = realization.y(c) - realization.y(a)
                val acz = realization.z(c) - realization.z(a)
                val winding =
                    (aby * acz - abz * acy) * nx +
                        (abz * acx - abx * acz) * ny +
                        (abx * acy - aby * acx) * nz
                if (winding < 0.0) b = c.also { c = b }
                output[i] = a
                output[i + 1] = b
                output[i + 2] = c
            }
        }
    }
}

/**
 * Field-native triangle geometry plus provenance lost by an ordinary triangle mesh.
 *
 * [mesh] duplicates polygon corners between source faces. That is intentional: Field's [TriMesh]
 * stores normals per vertex, and a creased polyhedron needs a different flat normal on each side
 * of a shared abstract vertex. [vertexElementIds] maps those render vertices back to topology
 * vertices; [triangleFaceIds] maps each triangle back to its polygonal source face.
 */
class TriangulatedGeometry internal constructor(
    val mesh: TriMesh,
    triangleFaceIds: IntArray,
    vertexElementIds: IntArray,
    triangleAttributes: List<Map<String, Any?>>,
    vertexAttributes: List<Map<String, Any?>>,
) {
    private val storedTriangleFaceIds = triangleFaceIds.copyOf()
    private val storedVertexElementIds = vertexElementIds.copyOf()
    private val storedTriangleAttributes = triangleAttributes.map { it.toMap() }
    private val storedVertexAttributes = vertexAttributes.map { it.toMap() }

    val triangleCount: Int get() = mesh.triangleCount
    val triangleFaceIds: IntArray get() = storedTriangleFaceIds.copyOf()
    val vertexElementIds: IntArray get() = storedVertexElementIds.copyOf()
    val triangleAttributes: List<Map<String, Any?>> get() = storedTriangleAttributes.map { it.toMap() }
    val vertexAttributes: List<Map<String, Any?>> get() = storedVertexAttributes.map { it.toMap() }

    fun sourceFace(triangle: Int): ElementId = ElementId(storedTriangleFaceIds[triangle])
    fun sourceVertex(renderVertex: Int): ElementId = ElementId(storedVertexElementIds[renderVertex])
}

/** Triangulate every ordered face and produce a flat-normal [TriMesh] with provenance. */
fun PolyhedralObject.triangulate(
    triangulator: FaceTriangulator = EarcutFaceTriangulator,
): TriangulatedGeometry {
    val realization = realization ?: throw IllegalStateException("triangulation requires a 3D realization")
    topology.validate().throwIfInvalid()
    val mesh = TriMeshBuilder()
    val triangleFaces = mutableListOf<Int>()
    val renderVertices = mutableListOf<Int>()
    val triangleAttributes = mutableListOf<Map<String, Any?>>()
    val vertexAttributes = mutableListOf<Map<String, Any?>>()
    val realizationIndex = topology.elements(0).mapIndexed { index, vertex -> vertex.id.value to index }.toMap()

    for (face in topology.elements(2)) {
        val elementCycle = topology.faceCycle(face.id)
        val cycle = elementCycle.map { realizationIndex.getValue(it) }.toIntArray()
        val normal = faceNormal(face.id)
        val localRenderIndex = mutableMapOf<Int, Int>()
        for (i in cycle.indices) {
            val coordinateIndex = cycle[i]
            val elementId = elementCycle[i]
            localRenderIndex[coordinateIndex] = mesh.vertex(
                realization.x(coordinateIndex),
                realization.y(coordinateIndex),
                realization.z(coordinateIndex),
                normal = normal,
            )
            renderVertices += elementId
            vertexAttributes += attributes[ElementId(elementId)]
        }

        val indices = triangulator.triangulate(cycle.copyOf(), realization)
        require(indices.size % 3 == 0) {
            "triangulator returned ${indices.size} indices for face ${face.id.value}; expected complete triples"
        }
        for (i in indices.indices step 3) {
            val a = localRenderIndex[indices[i]]
                ?: throw IllegalArgumentException("triangulator referenced vertex ${indices[i]} outside face ${face.id.value}")
            val b = localRenderIndex[indices[i + 1]]
                ?: throw IllegalArgumentException("triangulator referenced vertex ${indices[i + 1]} outside face ${face.id.value}")
            val c = localRenderIndex[indices[i + 2]]
                ?: throw IllegalArgumentException("triangulator referenced vertex ${indices[i + 2]} outside face ${face.id.value}")
            mesh.triangle(a, b, c)
            triangleFaces += face.id.value
            triangleAttributes += attributes[face.id]
        }
    }
    return TriangulatedGeometry(
        mesh.build(),
        triangleFaces.toIntArray(),
        renderVertices.toIntArray(),
        triangleAttributes,
        vertexAttributes,
    )
}

/** Convenience when only Field's renderable mesh is needed and provenance can be discarded. */
fun PolyhedralObject.toTriMesh(triangulator: FaceTriangulator = EarcutFaceTriangulator): TriMesh =
    triangulate(triangulator).mesh

const val POLYTOPE_CELL_ID_PROPERTY = "polytopeCellId"

/**
 * One open [FLine] per rank-1 edge of a [Polytope4], under a viewing [projection].
 *
 * Edges rather than filled polygons is the honest default for a 4-polytope: its polygons all live
 * on the boundary of *cells*, several cells meet at every polygon, and a projection puts them on
 * top of one another. A wireframe says what is there; a filled projection would imply a surface
 * that does not exist.
 */
fun Polytope4.toEdgeFLines(
    projection: Projection4,
    configure: (edge: Element, line: FLine) -> Unit = { _, _ -> },
): List<FLine> {
    require(realization != null) { "FLine conversion requires a 4D realization" }
    topology.validate().throwIfInvalid()
    val projected = project(projection)
    val index = topology.elements(0).mapIndexed { at, vertex -> vertex.id.value to at }.toMap()
    return topology.elements(1).map { edge ->
        val endpoints = topology.incidentDown(edge.id)
        require(endpoints.size == 2) { "edge ${edge.id.value} does not have exactly two endpoints" }
        val a = projected[index.getValue(endpoints[0])]
        val b = projected[index.getValue(endpoints[1])]
        FLine().moveTo(a.x, a.y, a.z).lineTo(b.x, b.y, b.z).also { line ->
            line.properties[POLYTOPE_FACE_ID_PROPERTY] = edge.id.value
            line.properties[POLYTOPE_FACE_RANK_PROPERTY] = edge.rank
            configure(edge, line)
        }
    }
}

/**
 * The edges of one cell of a [Polytope4], projected — the way to make a cell readable inside the
 * projected whole. [cell] must be a rank-3 element.
 */
fun Polytope4.toCellEdgeFLines(
    cell: ElementId,
    projection: Projection4,
    configure: (edge: Element, line: FLine) -> Unit = { _, _ -> },
): List<FLine> {
    require(topology.element(cell)?.rank == 3) { "element ${cell.value} is not a rank-3 cell" }
    val cellEdges = topology.incidentDown(cell)
        .flatMap { polygon -> topology.incidentDown(ElementId(polygon)).toList() }
        .toSet()
    return toEdgeFLines(projection) { edge, line ->
        line.properties[POLYTOPE_CELL_ID_PROPERTY] = cell.value
        configure(edge, line)
    }.filterIndexed { at, _ -> topology.elements(1)[at].id.value in cellEdges }
}
