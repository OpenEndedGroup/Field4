@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import afield.graphics.FLine
import afield.graphics.FloatEarcut
import afield.graphics.PlanarTessellation
import afield.stdlib.TriMesh
import afield.stdlib.TriMeshBuilder
import afield.stdlib.Vec3
import kotlin.math.sqrt

/**
 * The bridge from a polytope to the things Field can actually draw: [FLine]s and a [TriMesh].
 *
 * Three adapters, and the choice between them is about what you want to *do* with the result rather
 * than how it looks. [PolyhedralObject.toFaceFLines] gives one closed line per face, which is what
 * you want if faces are going to be styled, picked or filled independently. [PolyhedralObject.triangulate]
 * gives a flat-normal [TriMesh] with the provenance arrays needed to get from a triangle or a render
 * vertex back to the element it came from; [PolyhedralObject.toTriMesh] is the same thing when that
 * provenance can be thrown away. [Polytope4.toEdgeFLines] projects a 4-polytope to a wireframe.
 *
 * ## Attributes become FLine properties
 *
 * Every face line receives its face's attributes copied straight into `line.properties`, plus
 * [POLYTOPE_FACE_ID_PROPERTY] and [POLYTOPE_FACE_RANK_PROPERTY]. So an attribute named `color` set
 * on a face during a Conway chain arrives as Field's own `color` property with no translation step —
 * which is the point of [PolytopeAttributes] being typed as `Any`, and also means a badly-named
 * attribute can quietly overwrite a drawing property.
 *
 * ## What these will not do
 *
 * Every entry point requires a realization and validates the topology first, so an abstract polytope
 * throws rather than drawing nothing. Faces with holes are out of reach: the topology stores one
 * boundary cycle per face, and while [FloatEarcut] itself understands hole indices there is nowhere
 * to put them. A face with zero area throws [DegenerateFaceGeometryException] rather than emitting a
 * triangle with a garbage normal.
 */

/**
 * The [FLine] property every adapter here stamps with the source element's raw id.
 *
 * Read it back to go from a picked line to the element it came from. `toEdgeFLines` uses the same key
 * for an *edge*, not only a face, so pair it with [POLYTOPE_FACE_RANK_PROPERTY] before assuming a rank.
 */
const val POLYTOPE_FACE_ID_PROPERTY = "polytopeFaceId"
/**
 * The rank of the element a line came from: 2 for a face, 1 for a projected 4-polytope edge.
 */
const val POLYTOPE_FACE_RANK_PROPERTY = "polytopeFaceRank"

/**
 * A face whose 3D realization has no usable normal — zero area, or coordinates that went non-finite.
 *
 * Thrown by [PolyhedralObject.faceNormal] and therefore by triangulation. It almost always means an
 * upstream realization collapsed rather than that the topology is wrong, so check
 * `geometryDiagnostics` before suspecting the combinatorics.
 */
class DegenerateFaceGeometryException(val face: ElementId) : IllegalArgumentException(
    "face ${face.value} has zero area in its 3D realization"
)

/**
 * One closed, outward-wound [FLine] per polygonal face.
 *
 * Separate lines rather than one multi-contour line, so that callers can style, select or fill faces
 * independently; Field's ordinary `filled` and `stroked` properties are set in [configure]. Each
 * line's properties already carry the face's own attributes plus [POLYTOPE_FACE_ID_PROPERTY] and
 * [POLYTOPE_FACE_RANK_PROPERTY] by the time [configure] runs, so [configure] can read them and can
 * also overwrite them.
 *
 * Requires a realization and validates the topology first (without the manifold check), so a broken
 * complex throws rather than drawing a subset.
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
 * A [FaceTriangulator] that can subtract hole rings from the face it fills.
 *
 * Holes are not reachable from [PolyhedralObject.triangulate], because a `faceCycle` is a single
 * cycle and the topology has no notion of a hole in a face. This is the surface for callers that do
 * know their boundary rings — face-with-hole geometry assembled outside the polytope topology — and
 * it is what makes the hole support in [EarcutFaceTriangulator] usable and testable rather than
 * merely present.
 */
interface HoleAwareFaceTriangulator : FaceTriangulator {
    /**
     * [outer] and each ring of [holes] are ordered cycles of [Realization3] indices. Rings may be
     * wound either way. A hole ring that is not coplanar with [outer] is not treated as a hole — see
     * [afield.graphics.PlanarTessellation].
     */
    fun triangulate(outer: IntArray, holes: List<IntArray>, realization: Realization3): IntArray

    override fun triangulate(faceVertices: IntArray, realization: Realization3): IntArray =
        triangulate(faceVertices, emptyList(), realization)
}

/**
 * Field's Earcut tessellator over an arbitrarily oriented 3D polygon, with holes.
 *
 * This is a thin adapter over [afield.graphics.PlanarTessellation], which is also what
 * `FLine.tessellate` runs on. It used to be a second, independent implementation of the same idea,
 * and the two disagreed about the handedness of the projected frame: this one projected onto the
 * *mirror* of the right-handed pair for a y-dominant face and then flipped each returned triangle back
 * against the face's 3D normal.
 *
 * That is a correct composition — a mirror flips the facing of every triangle and nothing else, so
 * flipping them all back arrives at the same place — and it stayed correct with holes, which the
 * earlier version of this note denied. [FloatEarcut] decides which ring is the outer one from **array
 * position**, not from any area sign (`FloatEarcut.kt:19`, `:21`, `:484-492`), so a mirror cannot
 * invert the hole-versus-outer decision; `FloatEarcutOuterRingTest` measures that. The reason for
 * having one implementation is therefore the ordinary one: two conventions for the same question, one
 * of which needs every caller to remember a compensating flip, and a hole ring is one more thing for a
 * caller to get wrong. See `dev_docs/fline_fill_orientation.md`.
 */
object EarcutFaceTriangulator : HoleAwareFaceTriangulator {
    override fun triangulate(outer: IntArray, holes: List<IntArray>, realization: Realization3): IntArray {
        require(outer.size >= 3) { "Earcut triangulation needs at least three face vertices" }
        holes.forEachIndexed { at, hole ->
            require(hole.size >= 3) { "hole ring $at needs at least three vertices, had ${hole.size}" }
        }
        val cycles = ArrayList<IntArray>(1 + holes.size)
        cycles.add(outer)
        cycles.addAll(holes)
        val rings = cycles.map { cycle ->
            cycle.map { Vec3(realization.x(it), realization.y(it), realization.z(it)) }
        }

        // Kept in double precision, and only as a precondition: a zero-area face is a caller error
        // worth a message, not something to tessellate to nothing quietly.
        var nx = 0.0
        var ny = 0.0
        var nz = 0.0
        for (i in outer.indices) {
            val p = outer[i]
            val q = outer[(i + 1) % outer.size]
            nx += (realization.y(p) - realization.y(q)) * (realization.z(p) + realization.z(q))
            ny += (realization.z(p) - realization.z(q)) * (realization.x(p) + realization.x(q))
            nz += (realization.x(p) - realization.x(q)) * (realization.y(p) + realization.y(q))
        }
        val normalLengthSquared = nx * nx + ny * ny + nz * nz
        require(normalLengthSquared.isFinite() && normalLengthSquared > 1e-20) {
            "cannot triangulate a face with zero projected area"
        }

        val triangles = PlanarTessellation.tessellate(rings).flatMap { it.triangles }
        require(triangles.size % 3 == 0 && triangles.isNotEmpty()) {
            "Earcut did not produce complete triangles for a ${outer.size}-vertex face"
        }
        return IntArray(triangles.size) { at ->
            val vertex = triangles[at]
            cycles[vertex.ring][vertex.vertex]
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

    /**
     * Triangles in [mesh]; the length of [triangleFaceIds] and of [triangleAttributes].
     */
    val triangleCount: Int get() = mesh.triangleCount
    val triangleFaceIds: IntArray get() = storedTriangleFaceIds.copyOf()
    val vertexElementIds: IntArray get() = storedVertexElementIds.copyOf()
    val triangleAttributes: List<Map<String, Any?>> get() = storedTriangleAttributes.map { it.toMap() }
    val vertexAttributes: List<Map<String, Any?>> get() = storedVertexAttributes.map { it.toMap() }

    /**
     * The polygonal face a triangle was cut from. The inverse of triangulation, one triangle at a time.
     */
    fun sourceFace(triangle: Int): ElementId = ElementId(storedTriangleFaceIds[triangle])
    /**
     * The topology vertex a render vertex duplicates. Several render vertices share one of these whenever
     * a polyhedron is creased at that corner.
     */
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

/**
 * The [FLine] property `Polytope4.toCellEdgeFLines` stamps with the cell it was asked about.
 */
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
 * The edges of one cell of a [Polytope4], projected — the way to make one cell readable inside the
 * projected whole. [cell] must be a rank-3 element.
 *
 * This projects the *whole* polytope and then keeps the cell's edges, so it costs a full projection
 * per call and [configure] is invoked for every edge in the object, including the ones that are then
 * discarded. Filter inside [configure] on [POLYTOPE_FACE_ID_PROPERTY] if that matters, or call
 * [Polytope4.toEdgeFLines] once and partition the result yourself when highlighting several cells.
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
