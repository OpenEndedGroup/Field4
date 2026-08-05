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
 * throws rather than drawing nothing. A face with zero area throws [DegenerateFaceGeometryException]
 * rather than emitting a triangle with a garbage normal.
 *
 * ## Faces with holes
 *
 * A face's inner rings (`topology.faceHoles`) travel the whole way through both adapters.
 * [PolyhedralObject.triangulate] hands them to a [HoleAwareFaceTriangulator] — the default
 * [EarcutFaceTriangulator] is one — and a face with holes given a triangulator that is *not*
 * hole-aware throws rather than filling the hole in. [PolyhedralObject.toFaceFLines] emits each hole
 * as a further closed contour on the same `FLine`, which is exactly what `FLine.tessellate` and
 * [PlanarTessellation] already understand, so a filled holed face fills with a hole in it and a
 * stroked one is drawn with its inner outline.
 *
 * A face with no holes takes the identical path it took before holes existed — same calls, same
 * order, same buffers. `PolytopeFaceHolesTest` asserts that mesh-for-mesh rather than asserting it
 * in a comment.
 *
 * [PolyhedralObject.faceNormal] reads the outer ring only: a hole does not contribute area, and its
 * opposite winding would cancel some of the outline's if it did.
 *
 * ## Flat, smooth, or creased
 *
 * [NormalPolicy] decides what normal each duplicated corner carries. The default is [NormalPolicy.Flat]
 * — one face normal per face, which is what a Conway solid wants, every edge of one being a real
 * crease. [NormalPolicy.Crease] averages the faces at a vertex that are within an angle of this one,
 * so a subdivided sphere reads as curved and a cube still reads as a cube. Nothing else about the
 * output changes: same vertices, same triangles, same provenance arrays.
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
 * A face's holes become further closed contours on that same line — one line is still one face, and
 * one hole is not a face. `FLine.tessellate` groups a line's contours into outer rings and holes
 * geometrically, so a filled holed face comes out with a hole in it and nothing extra to say.
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
        topology.faceRings(face.id).forEach { ring ->
            ring.forEachIndexed { i, vertexValue ->
                val coordinate = realizationIndex(ElementId(vertexValue))
                if (i == 0) line.moveTo(realization.x(coordinate), realization.y(coordinate), realization.z(coordinate))
                else line.lineTo(realization.x(coordinate), realization.y(coordinate), realization.z(coordinate))
            }
            line.close()
        }
        line.also {
            line.properties.putAll(attributes[face.id])
            line.properties[POLYTOPE_FACE_ID_PROPERTY] = face.id.value
            line.properties[POLYTOPE_FACE_RANK_PROPERTY] = face.rank
            configure(face, line)
        }
    }
}

/**
 * Unit normal selected by the face's explicit winding — of its **outer** ring. A hole encloses no
 * area of the face and is wound the other way, so including it would subtract from the outline's own
 * Newell sum for no reason.
 */
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
 * This is the interface [PolyhedralObject.triangulate] reaches for as soon as a face has holes, and
 * the reason a face's inner rings are not a private arrangement between a caller and Earcut. It is
 * also still usable directly, for face-with-hole geometry assembled outside a `PolytopeTopology`.
 *
 * A plain [FaceTriangulator] handed a holed face is an error rather than a fallback — filling a
 * window in is a wrong picture, not a degraded one.
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

/**
 * How a render vertex gets its normal — and therefore whether an edge of the solid reads as a crease
 * or as curvature.
 *
 * The corners are duplicated per face either way ([TriangulatedGeometry] says why), so this changes
 * only what is written into those duplicates, never the buffer layout, the triangle count or the
 * provenance arrays. `POLYTOPE_ALGEBRA.md` item 4 asked for exactly that: smooth and crease-aware
 * normals *while preserving abstract vertex provenance*.
 */
sealed interface NormalPolicy {
    /**
     * One flat face normal on every corner of that face. The default, and what a Platonic or Conway
     * solid wants — every edge of one is a real crease.
     */
    object Flat : NormalPolicy

    /**
     * Average the face normals meeting at a topology vertex, but only the ones within [creaseDegrees]
     * of *this* face's own normal; a sharper join keeps this face's flat normal on this corner. So
     * `180.0` is fully smooth and `0.0` is [Flat].
     *
     * This is the per-corner smoothing-group approximation rather than a walk of the vertex fan: each
     * corner asks which of its vertex's faces it is nearly parallel to, and averages those. It is
     * order-independent and needs no manifold fan, which matters here because a hole rim has no fan at
     * all — a hole's edges bound one face, so a rim corner averages over that one face and keeps the
     * flat normal, which is the right answer and comes out of the same rule rather than a special case.
     *
     * The average is unweighted. Area or angle weighting is a better approximation of curvature on an
     * irregular mesh and is not what this is for; say so rather than implying otherwise.
     */
    data class Crease(val creaseDegrees: Double = 30.0) : NormalPolicy {
        init {
            require(creaseDegrees.isFinite() && creaseDegrees >= 0.0 && creaseDegrees <= 180.0) {
                "crease angle must be a finite 0..180 degrees, got $creaseDegrees"
            }
        }

        /** Every face normal counts as parallel to itself, so a threshold of 0 still keeps the face's own. */
        internal val cosineThreshold: Double = kotlin.math.cos(Math.toRadians(creaseDegrees))
    }

    companion object {
        /** Fully smooth: every face at a vertex contributes, whatever the dihedral. */
        val Smooth: NormalPolicy = Crease(180.0)
    }
}

/**
 * Triangulate every ordered face and produce a [TriMesh] with provenance.
 *
 * A face with inner rings goes to [HoleAwareFaceTriangulator.triangulate] with its holes; a face
 * without takes the single-cycle call it always took, and its output is identical vertex for vertex
 * and index for index. Passing a [triangulator] that is not hole-aware is fine right up until a face
 * that has holes, which throws and names both the face and the triangulator.
 *
 * [normals] decides what goes on each duplicated corner and nothing else — with the default
 * [NormalPolicy.Flat] the result is byte-identical to what this function returned before the policy
 * existed, which `PolytopeVertexNormalsTest` measures rather than assumes.
 */
fun PolyhedralObject.triangulate(
    triangulator: FaceTriangulator = EarcutFaceTriangulator,
    normals: NormalPolicy = NormalPolicy.Flat,
): TriangulatedGeometry {
    val realization = realization ?: throw IllegalStateException("triangulation requires a 3D realization")
    topology.validate().throwIfInvalid()
    val mesh = TriMeshBuilder()
    val triangleFaces = mutableListOf<Int>()
    val renderVertices = mutableListOf<Int>()
    val triangleAttributes = mutableListOf<Map<String, Any?>>()
    val vertexAttributes = mutableListOf<Map<String, Any?>>()
    val realizationIndex = topology.elements(0).mapIndexed { index, vertex -> vertex.id.value to index }.toMap()

    // Face normals once, up front, because a smoothed corner needs its neighbours' before it can be
    // written. Flat asks for none of this and pays for none of it.
    val faceNormals: Map<ElementId, Vec3> = when (normals) {
        NormalPolicy.Flat -> emptyMap()
        is NormalPolicy.Crease -> topology.elements(2).associate { it.id to faceNormal(it.id) }
    }
    val facesAtVertex: Map<ElementId, List<ElementId>> = when (normals) {
        NormalPolicy.Flat -> emptyMap()
        is NormalPolicy.Crease -> topology.elements(0).associate { vertex ->
            vertex.id to topology.incidentUp(vertex.id)
                .flatMap { edge -> topology.incidentUp(ElementId(edge)).toList() }
                .distinct()
                .map(::ElementId)
                .filter { topology.element(it)?.rank == 2 }
        }
    }

    fun normalFor(face: ElementId, faceNormal: Vec3, vertex: ElementId): Vec3 = when (normals) {
        NormalPolicy.Flat -> faceNormal
        is NormalPolicy.Crease -> {
            val sum = Vec3(faceNormal)
            var blended = 0
            for (other in facesAtVertex[vertex].orEmpty()) {
                if (other == face) continue
                val candidate = faceNormals[other] ?: continue
                if (candidate.dot(faceNormal) >= normals.cosineThreshold) {
                    sum.add(candidate)
                    blended++
                }
            }
            when {
                // Nothing to smooth against — a crease all round, or a hole rim, or a zero threshold —
                // keeps the face normal *exactly*, rather than a renormalized copy of it that differs
                // in the last bit. That is what makes `Crease(0)` byte-identical to `Flat`.
                blended == 0 -> faceNormal
                // A fan that cancels itself out — two faces exactly back to back — has no average
                // worth having, so keep the corner's own flat normal rather than a zero vector.
                sum.lengthSquared() <= 1e-12f -> faceNormal
                else -> sum.normalize()
            }
        }
    }

    for (face in topology.elements(2)) {
        // One entry for an ordinary face, so the loop below is the one that was always here; the
        // outer ring is always rings[0], which is what makes the hole-free output byte-identical.
        val elementRings = topology.faceRings(face.id)
        val normal = faceNormals[face.id] ?: faceNormal(face.id)
        val localRenderIndex = mutableMapOf<Int, Int>()
        val coordinateRings = elementRings.map { elementCycle ->
            val cycle = elementCycle.map { realizationIndex.getValue(it) }.toIntArray()
            for (i in cycle.indices) {
                val coordinateIndex = cycle[i]
                val elementId = elementCycle[i]
                localRenderIndex[coordinateIndex] = mesh.vertex(
                    realization.x(coordinateIndex),
                    realization.y(coordinateIndex),
                    realization.z(coordinateIndex),
                    normal = normalFor(face.id, normal, ElementId(elementId)),
                )
                renderVertices += elementId
                vertexAttributes += attributes[ElementId(elementId)]
            }
            cycle
        }
        val cycle = coordinateRings.first()

        val indices = if (coordinateRings.size == 1) {
            triangulator.triangulate(cycle.copyOf(), realization)
        } else {
            val holeAware = triangulator as? HoleAwareFaceTriangulator
                ?: throw IllegalArgumentException(
                    "face ${face.id.value} has ${coordinateRings.size - 1} hole(s) and " +
                        "${triangulator.javaClass.simpleName} is not a HoleAwareFaceTriangulator",
                )
            holeAware.triangulate(
                cycle.copyOf(),
                coordinateRings.drop(1).map { it.copyOf() },
                realization,
            )
        }
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
fun PolyhedralObject.toTriMesh(
    triangulator: FaceTriangulator = EarcutFaceTriangulator,
    normals: NormalPolicy = NormalPolicy.Flat,
): TriMesh = triangulate(triangulator, normals).mesh

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
