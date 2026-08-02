package afield.stdlib.polytope

import afield.stdlib.Vec3

enum class ConwayOperator(val symbol: Char, val displayName: String, val primitive: Boolean) {
    DUAL('d', "dual", true),
    AMBO('a', "ambo", true),
    KIS('k', "kis", true),
    TRUNCATE('t', "truncate", true),
    GYRO('g', "gyro", true),
    JOIN('j', "join", false),
    NEEDLE('n', "needle", false),
    ZIP('z', "zip", false),
    ORTHO('o', "ortho", false),
    EXPAND('e', "expand", false),
    SNUB('s', "snub", false),
    META('m', "meta", false),
    BEVEL('b', "bevel", false),
}

/** Primitive operations in execution order (seed outward), not written prefix order. */
val ConwayOperator.primitiveExpansion: List<ConwayOperator>
    get() = when (this) {
        ConwayOperator.DUAL,
        ConwayOperator.AMBO,
        ConwayOperator.KIS,
        ConwayOperator.TRUNCATE,
        ConwayOperator.GYRO,
        -> listOf(this)
        ConwayOperator.JOIN -> listOf(ConwayOperator.AMBO, ConwayOperator.DUAL) // j = da
        ConwayOperator.NEEDLE -> listOf(ConwayOperator.DUAL, ConwayOperator.KIS) // n = kd
        ConwayOperator.ZIP -> listOf(ConwayOperator.KIS, ConwayOperator.DUAL) // z = dk
        ConwayOperator.ORTHO -> listOf(ConwayOperator.AMBO, ConwayOperator.DUAL, ConwayOperator.AMBO, ConwayOperator.DUAL) // o = jj
        ConwayOperator.EXPAND -> listOf(ConwayOperator.AMBO, ConwayOperator.AMBO) // e = aa
        ConwayOperator.SNUB -> listOf(ConwayOperator.DUAL, ConwayOperator.GYRO, ConwayOperator.DUAL) // s = dgd
        ConwayOperator.META -> listOf(ConwayOperator.AMBO, ConwayOperator.DUAL, ConwayOperator.KIS) // m = kj
        ConwayOperator.BEVEL -> listOf(ConwayOperator.AMBO, ConwayOperator.TRUNCATE) // b = ta
    }

data class ElementRef(val id: ElementId, val rank: Int)

/** Direct explanation of how one output element was made from elements of the previous topology. */
data class ImmediateElementProvenance(
    val role: String,
    val sources: List<ElementRef>,
    val attributeSources: List<ElementRef> = sources,
)

sealed interface ElementLineage {
    data class Source(val element: ElementRef) : ElementLineage

    data class Derived(
        val operation: ConwayOperator,
        val role: String,
        val parents: List<ElementLineage>,
    ) : ElementLineage
}

fun ElementLineage.sourceElements(): Set<ElementRef> = when (this) {
    is ElementLineage.Source -> setOf(element)
    is ElementLineage.Derived -> parents.flatMapTo(linkedSetOf()) { it.sourceElements() }
}

class TopologyTransform internal constructor(
    val operation: ConwayOperator,
    val topology: PolytopeTopology,
    immediateProvenance: Map<ElementId, ImmediateElementProvenance>,
) {
    private val storedProvenance = immediateProvenance.toMap()

    fun provenance(element: ElementId): ImmediateElementProvenance = storedProvenance[element]
        ?: throw IllegalArgumentException("element ${element.value} is not in this transform output")
}

/** Replay/debug record for how an operator was deployed over its source object. */
class TransformDeployment internal constructor(
    val operation: ConwayOperator,
    selectedSources: Set<ElementRef>?,
    scalarParameters: Map<ElementRef, Map<String, Double>>,
) {
    private val storedSelectedSources = selectedSources?.toSet()
    private val storedScalarParameters = scalarParameters.mapValues { it.value.toMap() }

    /** `null` means the operator's complete natural domain; a set records an explicit selection. */
    val selectedSources: Set<ElementRef>? get() = storedSelectedSources?.toSet()
    val scalarParameters: Map<ElementRef, Map<String, Double>>
        get() = storedScalarParameters.mapValues { it.value.toMap() }

    companion object {
        internal fun global(operation: ConwayOperator) = TransformDeployment(operation, null, emptyMap())

        internal fun selected(
            operation: ConwayOperator,
            sources: Collection<ElementRef>,
            parameterName: String,
            values: Map<ElementId, Double>,
        ) = TransformDeployment(
            operation,
            sources.toSet(),
            sources.associateWith { source -> mapOf(parameterName to values.getValue(source.id)) },
        )
    }
}

fun interface RealizationPolicy3 {
    fun realize(source: PolyhedralObject, transform: TopologyTransform): Realization3?
}

fun interface AttributePropagationPolicy {
    fun attributesFor(
        source: PolyhedralObject,
        output: Element,
        provenance: ImmediateElementProvenance,
    ): Map<String, Any?>
}

/** Preserve only attributes present with equal values on every selected source element. */
object PreserveCommonAttributes : AttributePropagationPolicy {
    override fun attributesFor(
        source: PolyhedralObject,
        output: Element,
        provenance: ImmediateElementProvenance,
    ): Map<String, Any?> {
        val sources = provenance.attributeSources
        if (sources.isEmpty()) return emptyMap()
        val maps = sources.map { source.attributes[it.id] }
        if (maps.size == 1) return maps.single()
        val commonKeys = maps.map { it.keys }.reduce { common, keys -> common intersect keys }
        return commonKeys.mapNotNull { key ->
            val value = maps.first()[key]
            if (maps.all { it[key] == value }) key to value else null
        }.toMap()
    }
}

class PolytopeTransformResult internal constructor(
    val polyhedron: PolyhedralObject,
    lineage: Map<ElementId, ElementLineage>,
    /** Fully expanded primitive operations, useful for exact provenance and replay. */
    val history: List<ConwayOperator>,
    /** Operations requested by the caller, retaining named Conway composites. */
    val notationHistory: List<ConwayOperator> = history,
    /** Exact source selections and resolved scalar values for primitive replay and inspection. */
    val deployments: List<TransformDeployment> = emptyList(),
) {
    internal val storedLineage = lineage.toMap()

    fun lineage(element: ElementId): ElementLineage = storedLineage[element]
        ?: throw IllegalArgumentException("element ${element.value} is not in this transform result")

    fun sourceElements(element: ElementId): Set<ElementRef> = lineage(element).sourceElements()

    companion object {
        fun seed(polyhedron: PolyhedralObject): PolytopeTransformResult {
            val lineage = polyhedron.topology.elementsByRank.flatten().associate { element ->
                element.id to ElementLineage.Source(ElementRef(element.id, element.rank))
            }
            return PolytopeTransformResult(polyhedron, lineage, emptyList(), emptyList(), emptyList())
        }
    }
}

private sealed interface TransformVertexKey {
    data class FromElement(val element: ElementId) : TransformVertexKey
    data class FromVertexEdge(val vertex: ElementId, val edge: ElementId) : TransformVertexKey
}

private data class VertexSpec(
    val key: TransformVertexKey,
    val provenance: ImmediateElementProvenance,
)

private data class FaceSpec(
    val cycle: List<TransformVertexKey>,
    val provenance: ImmediateElementProvenance,
)

private data class LocalEdge(val a: Int, val b: Int) {
    companion object {
        fun of(a: Int, b: Int) = if (a < b) LocalEdge(a, b) else LocalEdge(b, a)
    }
}

private fun Element.asRef() = ElementRef(id, rank)

private fun buildTransform(
    source: PolytopeTopology,
    operation: ConwayOperator,
    vertices: List<VertexSpec>,
    faces: List<FaceSpec>,
): TopologyTransform {
    val builder = PolytopeTopologyBuilder(rank = 3)
    val provenance = mutableMapOf<ElementId, ImmediateElementProvenance>()
    val vertexIds = vertices.map { spec ->
        builder.addElement(0).also { provenance[it] = spec.provenance }
    }
    val vertexIndex = vertices.mapIndexed { index, spec -> spec.key to index }.toMap()
    require(vertexIndex.size == vertices.size) { "$operation produced duplicate abstract vertices" }

    val localFaceCycles = faces.map { face -> face.cycle.map { vertexIndex.getValue(it) } }
    val edgeFaces = linkedMapOf<LocalEdge, MutableList<Int>>()
    for ((faceIndex, cycle) in localFaceCycles.withIndex()) for (i in cycle.indices) {
        edgeFaces.getOrPut(LocalEdge.of(cycle[i], cycle[(i + 1) % cycle.size])) { mutableListOf() } += faceIndex
    }
    val edgeIds = linkedMapOf<LocalEdge, ElementId>()
    for ((edge, adjacentFaces) in edgeFaces) {
        val id = builder.addElement(1)
        edgeIds[edge] = id
        builder.addIncidence(vertexIds[edge.a], id)
        builder.addIncidence(vertexIds[edge.b], id)
        val sources = linkedSetOf<ElementRef>()
        sources += vertices[edge.a].provenance.sources
        sources += vertices[edge.b].provenance.sources
        adjacentFaces.forEach { sources += faces[it].provenance.sources }
        provenance[id] = ImmediateElementProvenance("boundary-edge", sources.toList())
    }

    val faceIds = faces.mapIndexed { faceIndex, face ->
        val id = builder.addElement(2)
        val cycle = localFaceCycles[faceIndex]
        for (i in cycle.indices) {
            builder.addIncidence(edgeIds.getValue(LocalEdge.of(cycle[i], cycle[(i + 1) % cycle.size])), id)
        }
        builder.setFaceCycle(id, cycle.map { vertexIds[it] })
        provenance[id] = face.provenance
        id
    }
    val whole = builder.addElement(3)
    faceIds.forEach { builder.addIncidence(it, whole) }
    val sourceTops = source.elements(3).map(Element::asRef)
    provenance[whole] = ImmediateElementProvenance("whole", sourceTops)

    return TopologyTransform(
        operation,
        builder.buildValidated(checkManifold = true),
        provenance,
    )
}

private class SurfaceContext(val topology: PolytopeTopology) {
    private val edgeByEndpoints = topology.elements(1).associate { edge ->
        topology.incidentDown(edge.id).toSet() to edge.id
    }

    init {
        require(topology.rank == 3) { "Conway surface transforms currently require rank 3, got ${topology.rank}" }
        topology.validate(checkManifold = true).throwIfInvalid()
    }

    fun edge(a: ElementId, b: ElementId): ElementId = edgeByEndpoints.getValue(setOf(a.value, b.value))

    fun boundaryEdges(face: ElementId): List<ElementId> {
        val cycle = topology.faceCycle(face).map(::ElementId)
        return cycle.indices.map { i -> edge(cycle[i], cycle[(i + 1) % cycle.size]) }
    }

    /** Incident edges ordered so their realized midpoint polygon has outward winding. */
    fun outwardEdgeRing(vertex: ElementId): List<ElementId> {
        val successor = mutableMapOf<ElementId, ElementId>()
        for (faceValue in incidentFaces(vertex)) {
            val cycle = topology.faceCycle(faceValue).map(::ElementId)
            val at = cycle.indexOf(vertex)
            require(at >= 0) { "face ${faceValue.value} incidence does not contain vertex ${vertex.value}" }
            val previous = cycle[(at - 1 + cycle.size) % cycle.size]
            val next = cycle[(at + 1) % cycle.size]
            val previousEdge = edge(previous, vertex)
            val nextEdge = edge(vertex, next)
            require(successor.put(nextEdge, previousEdge) == null) {
                "vertex ${vertex.value} does not have an oriented manifold fan"
            }
        }
        val start = successor.keys.minBy { it.value }
        val ring = mutableListOf(start)
        while (true) {
            val next = successor[ring.last()]
                ?: throw IllegalArgumentException("vertex ${vertex.value} has a disconnected oriented fan")
            if (next == start) break
            require(next !in ring) { "vertex ${vertex.value} has a self-intersecting oriented fan" }
            ring += next
        }
        require(ring.size == successor.size) { "vertex ${vertex.value} has a disconnected oriented fan" }
        return ring
    }

    fun faceBetweenAtVertex(a: ElementId, b: ElementId, vertex: ElementId): ElementId {
        val aFaces = topology.incidentUp(a).map(::ElementId).toSet()
        val bFaces = topology.incidentUp(b).map(::ElementId).toSet()
        return (aFaces intersect bFaces).singleOrNull { face -> vertex.value in topology.faceCycle(face) }
            ?: throw IllegalArgumentException(
                "edges ${a.value} and ${b.value} do not bound one face at vertex ${vertex.value}",
            )
    }

    private fun incidentFaces(vertex: ElementId): Set<ElementId> =
        topology.incidentUp(vertex).flatMapTo(linkedSetOf()) { edgeValue ->
            topology.incidentUp(ElementId(edgeValue)).map(::ElementId)
        }
}

fun PolytopeTopology.transform(operation: ConwayOperator): TopologyTransform {
    require(operation.primitive) {
        "${operation.displayName} is a named composite; transform a PolyhedralObject or expand ${operation.symbol} to ${operation.primitiveExpansion.joinToString("") { it.symbol.toString() }}"
    }
    val surface = SurfaceContext(this)
    return when (operation) {
        ConwayOperator.DUAL -> transformDual(surface)
        ConwayOperator.AMBO -> transformAmbo(surface)
        ConwayOperator.KIS -> transformKis(surface, elements(2).mapTo(linkedSetOf()) { it.id })
        ConwayOperator.TRUNCATE -> transformTruncate(surface, elements(0).mapTo(linkedSetOf()) { it.id })
        ConwayOperator.GYRO -> transformGyro(surface)
        else -> error("composite operator reached primitive topology transform: $operation")
    }
}

private fun PolytopeTopology.transformDual(surface: SurfaceContext): TopologyTransform {
    val vertices = elements(2).map { face ->
        VertexSpec(TransformVertexKey.FromElement(face.id), ImmediateElementProvenance("face-center", listOf(face.asRef())))
    }
    val faces = elements(0).map { vertex ->
        val ring = surface.outwardEdgeRing(vertex.id)
        val faceCycle = ring.indices.map { i ->
            val face = surface.faceBetweenAtVertex(ring[i], ring[(i + 1) % ring.size], vertex.id)
            TransformVertexKey.FromElement(face)
        }
        FaceSpec(faceCycle, ImmediateElementProvenance("vertex-face", listOf(vertex.asRef())))
    }
    return buildTransform(this, ConwayOperator.DUAL, vertices, faces)
}

private fun PolytopeTopology.transformAmbo(surface: SurfaceContext): TopologyTransform {
    val vertices = elements(1).map { edge ->
        VertexSpec(TransformVertexKey.FromElement(edge.id), ImmediateElementProvenance("edge-midpoint", listOf(edge.asRef())))
    }
    val faces = mutableListOf<FaceSpec>()
    for (face in elements(2)) {
        faces += FaceSpec(
            surface.boundaryEdges(face.id).map { TransformVertexKey.FromElement(it) },
            ImmediateElementProvenance("original-face", listOf(face.asRef())),
        )
    }
    for (vertex in elements(0)) {
        faces += FaceSpec(
            surface.outwardEdgeRing(vertex.id).map { TransformVertexKey.FromElement(it) },
            ImmediateElementProvenance("vertex-face", listOf(vertex.asRef())),
        )
    }
    return buildTransform(this, ConwayOperator.AMBO, vertices, faces)
}

fun PolytopeTopology.kis(selectedFaces: Collection<ElementId>): TopologyTransform {
    val selected = validateSelection(selectedFaces, expectedRank = 2, label = "kis faces")
    return transformKis(SurfaceContext(this), selected)
}

private fun PolytopeTopology.transformKis(
    surface: SurfaceContext,
    selectedFaces: Set<ElementId>,
): TopologyTransform {
    val vertices = buildList {
        elements(0).forEach { vertex ->
            add(VertexSpec(TransformVertexKey.FromElement(vertex.id), ImmediateElementProvenance("original-vertex", listOf(vertex.asRef()))))
        }
        elements(2).filter { it.id in selectedFaces }.forEach { face ->
            add(VertexSpec(TransformVertexKey.FromElement(face.id), ImmediateElementProvenance("face-apex", listOf(face.asRef()))))
        }
    }
    val faces = mutableListOf<FaceSpec>()
    for (face in elements(2)) {
        val cycle = faceCycle(face.id).map(::ElementId)
        if (face.id !in selectedFaces) {
            faces += FaceSpec(
                cycle.map { TransformVertexKey.FromElement(it) },
                ImmediateElementProvenance("original-face", listOf(face.asRef())),
            )
            continue
        }
        val edges = surface.boundaryEdges(face.id)
        for (i in cycle.indices) {
            val edge = element(edges[i]) ?: error("missing edge ${edges[i].value}")
            faces += FaceSpec(
                listOf(
                    TransformVertexKey.FromElement(cycle[i]),
                    TransformVertexKey.FromElement(cycle[(i + 1) % cycle.size]),
                    TransformVertexKey.FromElement(face.id),
                ),
                ImmediateElementProvenance(
                    "kis-triangle",
                    listOf(face.asRef(), edge.asRef()),
                    attributeSources = listOf(face.asRef()),
                ),
            )
        }
    }
    return buildTransform(this, ConwayOperator.KIS, vertices, faces)
}

fun PolytopeTopology.truncate(selectedVertices: Collection<ElementId>): TopologyTransform {
    val selected = validateSelection(selectedVertices, expectedRank = 0, label = "truncate vertices")
    return transformTruncate(SurfaceContext(this), selected)
}

private fun PolytopeTopology.transformTruncate(
    surface: SurfaceContext,
    selectedVertices: Set<ElementId>,
): TopologyTransform {
    val vertices = buildList {
        elements(0).filter { it.id !in selectedVertices }.forEach { vertex ->
            add(VertexSpec(TransformVertexKey.FromElement(vertex.id), ImmediateElementProvenance("original-vertex", listOf(vertex.asRef()))))
        }
        elements(1).forEach { edge ->
            incidentDown(edge.id).map(::ElementId).filter { it in selectedVertices }.forEach { vertexId ->
                val vertex = element(vertexId) ?: error("missing vertex ${vertexId.value}")
                add(
                    VertexSpec(
                        TransformVertexKey.FromVertexEdge(vertexId, edge.id),
                        ImmediateElementProvenance(
                            "vertex-edge-point",
                            listOf(vertex.asRef(), edge.asRef()),
                            attributeSources = listOf(vertex.asRef()),
                        ),
                    ),
                )
            }
        }
    }
    val faces = mutableListOf<FaceSpec>()
    for (face in elements(2)) {
        val cycle = faceCycle(face.id).map(::ElementId)
        val edgeCycle = surface.boundaryEdges(face.id)
        val outputCycle = buildList {
            for (i in cycle.indices) {
                val vertex = cycle[i]
                if (vertex in selectedVertices) {
                    val previousEdge = edgeCycle[(i - 1 + edgeCycle.size) % edgeCycle.size]
                    val nextEdge = edgeCycle[i]
                    add(TransformVertexKey.FromVertexEdge(vertex, previousEdge))
                    add(TransformVertexKey.FromVertexEdge(vertex, nextEdge))
                } else {
                    add(TransformVertexKey.FromElement(vertex))
                }
            }
        }
        val selectedOnFace = cycle.count { it in selectedVertices }
        faces += FaceSpec(
            outputCycle,
            ImmediateElementProvenance(
                when (selectedOnFace) {
                    0 -> "original-face"
                    cycle.size -> "truncated-face"
                    else -> "partially-truncated-face"
                },
                listOf(face.asRef()),
            ),
        )
    }
    for (vertex in elements(0).filter { it.id in selectedVertices }) {
        faces += FaceSpec(
            surface.outwardEdgeRing(vertex.id).map { edge -> TransformVertexKey.FromVertexEdge(vertex.id, edge) },
            ImmediateElementProvenance("vertex-face", listOf(vertex.asRef())),
        )
    }
    return buildTransform(this, ConwayOperator.TRUNCATE, vertices, faces)
}

private fun PolytopeTopology.validateSelection(
    selected: Collection<ElementId>,
    expectedRank: Int,
    label: String,
): Set<ElementId> = selected.toCollection(linkedSetOf()).also { ids ->
    for (id in ids) {
        val element = element(id) ?: throw IllegalArgumentException("$label contains unknown element ${id.value}")
        require(element.rank == expectedRank) {
            "$label contains rank-${element.rank} element ${id.value}; expected rank $expectedRank"
        }
    }
}

/**
 * Conway gyro. Each original edge receives two directed one-third points; every oriented source
 * half-edge becomes one pentagon. This is the combinatorial operation, independent of geometry.
 */
private fun PolytopeTopology.transformGyro(surface: SurfaceContext): TopologyTransform {
    val vertices = buildList {
        elements(0).forEach { vertex ->
            add(VertexSpec(TransformVertexKey.FromElement(vertex.id), ImmediateElementProvenance("original-vertex", listOf(vertex.asRef()))))
        }
        elements(2).forEach { face ->
            add(VertexSpec(TransformVertexKey.FromElement(face.id), ImmediateElementProvenance("face-center", listOf(face.asRef()))))
        }
        elements(1).forEach { edge ->
            incidentDown(edge.id).map(::ElementId).forEach { vertexId ->
                val vertex = element(vertexId) ?: error("missing vertex ${vertexId.value}")
                add(
                    VertexSpec(
                        TransformVertexKey.FromVertexEdge(vertexId, edge.id),
                        ImmediateElementProvenance("directed-edge-point", listOf(vertex.asRef(), edge.asRef())),
                    ),
                )
            }
        }
    }
    val faces = mutableListOf<FaceSpec>()
    for (face in elements(2)) {
        val cycle = faceCycle(face.id).map(::ElementId)
        val edges = surface.boundaryEdges(face.id)
        for (i in cycle.indices) {
            val u = cycle[i]
            val v = cycle[(i + 1) % cycle.size]
            val nextEdge = edges[(i + 1) % edges.size]
            val edge = element(edges[i]) ?: error("missing edge ${edges[i].value}")
            faces += FaceSpec(
                listOf(
                    TransformVertexKey.FromVertexEdge(u, edges[i]),
                    TransformVertexKey.FromVertexEdge(v, edges[i]),
                    TransformVertexKey.FromElement(v),
                    TransformVertexKey.FromVertexEdge(v, nextEdge),
                    TransformVertexKey.FromElement(face.id),
                ),
                ImmediateElementProvenance(
                    "gyro-pentagon",
                    listOf(face.asRef(), edge.asRef()),
                    attributeSources = listOf(face.asRef()),
                ),
            )
        }
    }
    return buildTransform(this, ConwayOperator.GYRO, vertices, faces)
}

object DualCentroidRealization : RealizationPolicy3 {
    override fun realize(source: PolyhedralObject, transform: TopologyTransform): Realization3? {
        if (source.realization == null) return null
        return realizeVertices(transform) { provenance -> source.centroid(provenance.sources.single().id) }
    }
}

object AmboMidpointRealization : RealizationPolicy3 {
    override fun realize(source: PolyhedralObject, transform: TopologyTransform): Realization3? {
        if (source.realization == null) return null
        return realizeVertices(transform) { provenance -> source.edgePoint(provenance.sources.single().id, 0.5f) }
    }
}

class KisFaceNormalRealization private constructor(
    val heightScale: Double,
    private val perFaceHeightScale: Map<ElementId, Double>,
) : RealizationPolicy3 {
    constructor(heightScale: Double = 0.5) : this(heightScale, emptyMap())

    init {
        require(heightScale.isFinite() && heightScale > 0.0) { "kis heightScale must be finite and positive" }
        require(perFaceHeightScale.values.all { it.isFinite() && it > 0.0 }) {
            "all kis height scales must be finite and positive"
        }
    }

    override fun realize(source: PolyhedralObject, transform: TopologyTransform): Realization3? {
        if (source.realization == null) return null
        return realizeVertices(transform) { provenance ->
            val sourceElement = provenance.sources.single()
            when (provenance.role) {
                "original-vertex" -> source.vertexCoordinate(sourceElement.id)
                "face-apex" -> {
                    val localHeightScale = perFaceHeightScale[sourceElement.id] ?: heightScale
                    val center = source.centroid(sourceElement.id)
                    val cycle = source.topology.faceCycle(sourceElement.id).map(::ElementId)
                    var meanEdgeLength = 0f
                    for (i in cycle.indices) {
                        meanEdgeLength += source.vertexCoordinate(cycle[i]).distance(
                            source.vertexCoordinate(cycle[(i + 1) % cycle.size]),
                        )
                    }
                    meanEdgeLength /= cycle.size
                    center.add(source.faceNormal(sourceElement.id).mul((meanEdgeLength * localHeightScale).toFloat()))
                }
                else -> error("unexpected kis vertex role ${provenance.role}")
            }
        }
    }

    companion object {
        internal fun varying(heightScaleByFace: Map<ElementId, Double>) =
            KisFaceNormalRealization(0.5, heightScaleByFace.toMap())
    }
}

class TruncateAlongEdgesRealization private constructor(
    val fraction: Double,
    private val perVertexFraction: Map<ElementId, Double>,
) : RealizationPolicy3 {
    constructor(fraction: Double = 1.0 / 3.0) : this(fraction, emptyMap())

    init {
        require(fraction.isFinite() && fraction > 0.0 && fraction < 0.5) {
            "truncate fraction must be finite and strictly between 0 and 0.5"
        }
        require(perVertexFraction.values.all { it.isFinite() && it > 0.0 && it < 0.5 }) {
            "all truncate fractions must be finite and strictly between 0 and 0.5"
        }
    }

    override fun realize(source: PolyhedralObject, transform: TopologyTransform): Realization3? {
        if (source.realization == null) return null
        return realizeVertices(transform) { provenance ->
            when (provenance.role) {
                "original-vertex" -> source.vertexCoordinate(provenance.sources.single().id)
                "vertex-edge-point" -> {
                    val vertex = provenance.sources.single { it.rank == 0 }.id
                    val edge = provenance.sources.single { it.rank == 1 }.id
                    val endpoints = source.topology.incidentDown(edge).map(::ElementId)
                    val other = endpoints.single { it != vertex }
                    val localFraction = perVertexFraction[vertex] ?: fraction
                    val a = source.vertexCoordinate(vertex)
                    val b = source.vertexCoordinate(other)
                    Vec3(a).mul((1.0 - localFraction).toFloat()).add(Vec3(b).mul(localFraction.toFloat()))
                }
                else -> error("unexpected truncate vertex role ${provenance.role}")
            }
        }
    }

    companion object {
        internal fun varying(fractionByVertex: Map<ElementId, Double>) =
            TruncateAlongEdgesRealization(1.0 / 3.0, fractionByVertex.toMap())
    }
}

class GyroSubdivisionRealization private constructor(
    val edgeFraction: Double,
    private val perEdgeFraction: Map<ElementId, Double>,
) : RealizationPolicy3 {
    constructor(edgeFraction: Double = 1.0 / 3.0) : this(edgeFraction, emptyMap())

    init {
        require(edgeFraction.isFinite() && edgeFraction > 0.0 && edgeFraction < 0.5) {
            "gyro edgeFraction must be finite and strictly between 0 and 0.5"
        }
        require(perEdgeFraction.values.all { it.isFinite() && it > 0.0 && it < 0.5 }) {
            "all gyro edge fractions must be finite and strictly between 0 and 0.5"
        }
    }

    override fun realize(source: PolyhedralObject, transform: TopologyTransform): Realization3? {
        if (source.realization == null) return null
        return realizeVertices(transform) { provenance ->
            when (provenance.role) {
                "original-vertex" -> source.vertexCoordinate(provenance.sources.single().id)
                "face-center" -> source.centroid(provenance.sources.single().id)
                "directed-edge-point" -> {
                    val vertex = provenance.sources.single { it.rank == 0 }.id
                    val edge = provenance.sources.single { it.rank == 1 }.id
                    val endpoints = source.topology.incidentDown(edge).map(::ElementId)
                    val other = endpoints.single { it != vertex }
                    val a = source.vertexCoordinate(vertex)
                    val b = source.vertexCoordinate(other)
                    val localFraction = perEdgeFraction[edge] ?: edgeFraction
                    Vec3(a).mul((1.0 - localFraction).toFloat()).add(Vec3(b).mul(localFraction.toFloat()))
                }
                else -> error("unexpected gyro vertex role ${provenance.role}")
            }
        }
    }

    companion object {
        internal fun varying(fractionByEdge: Map<ElementId, Double>) =
            GyroSubdivisionRealization(1.0 / 3.0, fractionByEdge.toMap())
    }
}

private fun realizeVertices(
    transform: TopologyTransform,
    coordinate: (ImmediateElementProvenance) -> Vec3,
): Realization3 = Realization3(transform.topology.elements(0).map { coordinate(transform.provenance(it.id)) })

private fun PolyhedralObject.vertexCoordinate(vertex: ElementId): Vec3 {
    val realization = realization ?: throw IllegalStateException("realization policy requires source coordinates")
    return realization[realizationIndex(vertex)]
}

private fun PolyhedralObject.centroid(element: ElementId): Vec3 {
    val source = topology.element(element) ?: throw IllegalArgumentException("missing source element ${element.value}")
    val vertices = when (source.rank) {
        0 -> listOf(element)
        1 -> topology.incidentDown(element).map(::ElementId)
        2 -> topology.faceCycle(element).map(::ElementId)
        else -> throw IllegalArgumentException("cannot calculate centroid for rank ${source.rank}")
    }
    val center = Vec3()
    vertices.forEach { center.add(vertexCoordinate(it)) }
    return center.mul(1f / vertices.size)
}

private fun PolyhedralObject.edgePoint(edge: ElementId, fraction: Float): Vec3 {
    val endpoints = topology.incidentDown(edge).map(::ElementId)
    require(endpoints.size == 2) { "edge ${edge.value} does not have two endpoints" }
    val a = vertexCoordinate(endpoints[0])
    val b = vertexCoordinate(endpoints[1])
    return Vec3(a).mul(1f - fraction).add(Vec3(b).mul(fraction))
}

private fun defaultRealizationPolicy(operation: ConwayOperator): RealizationPolicy3 = when (operation) {
    ConwayOperator.DUAL -> DualCentroidRealization
    ConwayOperator.AMBO -> AmboMidpointRealization
    ConwayOperator.KIS -> KisFaceNormalRealization()
    ConwayOperator.TRUNCATE -> TruncateAlongEdgesRealization()
    ConwayOperator.GYRO -> GyroSubdivisionRealization()
    else -> error("${operation.displayName} is a composite and has no single-step realization policy")
}

fun PolyhedralObject.transform(
    operation: ConwayOperator,
    realizationPolicy: RealizationPolicy3? = null,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = PolytopeTransformResult.seed(this).transform(operation, realizationPolicy, attributePolicy)

fun PolytopeTransformResult.transform(
    operation: ConwayOperator,
    realizationPolicy: RealizationPolicy3? = null,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult {
    if (!operation.primitive) {
        require(realizationPolicy == null) {
            "a single realization policy cannot realize composite ${operation.displayName}; configure its primitive steps instead"
        }
        var result = this
        for (primitive in operation.primitiveExpansion) {
            result = result.transformPrimitive(primitive, defaultRealizationPolicy(primitive), attributePolicy, recordNotation = false)
        }
        return PolytopeTransformResult(
            result.polyhedron,
            result.storedLineage,
            result.history,
            notationHistory + operation,
            result.deployments,
        )
    }
    return transformPrimitive(operation, realizationPolicy ?: defaultRealizationPolicy(operation), attributePolicy, recordNotation = true)
}

private fun PolytopeTransformResult.transformPrimitive(
    operation: ConwayOperator,
    realizationPolicy: RealizationPolicy3,
    attributePolicy: AttributePropagationPolicy,
    recordNotation: Boolean,
): PolytopeTransformResult {
    val transformed = polyhedron.topology.transform(operation)
    return applyTransform(transformed, operation, realizationPolicy, attributePolicy, recordNotation)
}

private fun PolytopeTransformResult.applyTransform(
    transformed: TopologyTransform,
    operation: ConwayOperator,
    realizationPolicy: RealizationPolicy3,
    attributePolicy: AttributePropagationPolicy,
    recordNotation: Boolean,
    deployment: TransformDeployment = TransformDeployment.global(operation),
): PolytopeTransformResult {
    val realization = realizationPolicy.realize(polyhedron, transformed)
    var attributes = PolytopeAttributes.EMPTY
    for (element in transformed.topology.elementsByRank.flatten()) {
        attributes = attributes.with(
            element.id,
            attributePolicy.attributesFor(polyhedron, element, transformed.provenance(element.id)),
        )
    }
    val output = PolyhedralObject(transformed.topology, realization, attributes)
    val outputLineage = transformed.topology.elementsByRank.flatten().associate { element ->
        val immediate = transformed.provenance(element.id)
        val parents = immediate.sources.map { source ->
            runCatching { lineage(source.id) }.getOrElse { ElementLineage.Source(source) }
        }
        element.id to ElementLineage.Derived(operation, immediate.role, parents)
    }
    return PolytopeTransformResult(
        output,
        outputLineage,
        history + operation,
        if (recordNotation) notationHistory + operation else notationHistory,
        deployments + deployment,
    )
}

fun PolyhedralObject.dual(
    realizationPolicy: RealizationPolicy3 = DualCentroidRealization,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.DUAL, realizationPolicy, attributePolicy)

fun PolyhedralObject.ambo(
    realizationPolicy: RealizationPolicy3 = AmboMidpointRealization,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.AMBO, realizationPolicy, attributePolicy)

fun PolyhedralObject.kis(
    realizationPolicy: RealizationPolicy3 = KisFaceNormalRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.KIS, realizationPolicy, attributePolicy)

fun PolyhedralObject.truncate(
    realizationPolicy: RealizationPolicy3 = TruncateAlongEdgesRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.TRUNCATE, realizationPolicy, attributePolicy)

fun PolytopeTransformResult.dual(
    realizationPolicy: RealizationPolicy3 = DualCentroidRealization,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.DUAL, realizationPolicy, attributePolicy)

fun PolytopeTransformResult.ambo(
    realizationPolicy: RealizationPolicy3 = AmboMidpointRealization,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.AMBO, realizationPolicy, attributePolicy)

fun PolytopeTransformResult.kis(
    realizationPolicy: RealizationPolicy3 = KisFaceNormalRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.KIS, realizationPolicy, attributePolicy)

fun PolytopeTransformResult.truncate(
    realizationPolicy: RealizationPolicy3 = TruncateAlongEdgesRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.TRUNCATE, realizationPolicy, attributePolicy)

/** Kis only selected source faces, with a height scale independently evaluated on each face. */
fun PolyhedralObject.kis(
    selector: ElementSelector,
    heightScale: ElementDoubleParameter = ElementParameters.constant(0.5),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = PolytopeTransformResult.seed(this).kis(selector, heightScale, attributePolicy)

fun PolytopeTransformResult.kis(
    selector: ElementSelector,
    heightScale: ElementDoubleParameter = ElementParameters.constant(0.5),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult {
    val faces = polyhedron.topology.elements(2).filter { selector.matches(selectionContext(it)) }
    val heights = faces.associate { face ->
        face.id to heightScale.value(selectionContext(face)).also {
            require(it.isFinite() && it > 0.0) {
                "kis height for face ${face.id.value} must be finite and positive, got $it"
            }
        }
    }
    val transformed = polyhedron.topology.kis(faces.map { it.id })
    return applyTransform(
        transformed,
        ConwayOperator.KIS,
        KisFaceNormalRealization.varying(heights),
        attributePolicy,
        recordNotation = true,
        deployment = TransformDeployment.selected(
            ConwayOperator.KIS,
            faces.map { ElementRef(it.id, it.rank) },
            "heightScale",
            heights,
        ),
    )
}

/** Truncate only selected source vertices, with an independently evaluated cut fraction. */
fun PolyhedralObject.truncate(
    selector: ElementSelector,
    fraction: ElementDoubleParameter = ElementParameters.constant(1.0 / 3.0),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = PolytopeTransformResult.seed(this).truncate(selector, fraction, attributePolicy)

fun PolytopeTransformResult.truncate(
    selector: ElementSelector,
    fraction: ElementDoubleParameter = ElementParameters.constant(1.0 / 3.0),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult {
    val vertices = polyhedron.topology.elements(0).filter { selector.matches(selectionContext(it)) }
    val fractions = vertices.associate { vertex ->
        vertex.id to fraction.value(selectionContext(vertex)).also {
            require(it.isFinite() && it > 0.0 && it < 0.5) {
                "truncate fraction for vertex ${vertex.id.value} must be finite and in (0, 0.5), got $it"
            }
        }
    }
    val transformed = polyhedron.topology.truncate(vertices.map { it.id })
    return applyTransform(
        transformed,
        ConwayOperator.TRUNCATE,
        TruncateAlongEdgesRealization.varying(fractions),
        attributePolicy,
        recordNotation = true,
        deployment = TransformDeployment.selected(
            ConwayOperator.TRUNCATE,
            vertices.map { ElementRef(it.id, it.rank) },
            "fraction",
            fractions,
        ),
    )
}

/** Apply gyro everywhere while varying its directed edge-point fraction by source edge. */
fun PolyhedralObject.gyro(
    edgeFraction: ElementDoubleParameter,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = PolytopeTransformResult.seed(this).gyro(edgeFraction, attributePolicy)

fun PolytopeTransformResult.gyro(
    edgeFraction: ElementDoubleParameter,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult {
    val fractions = polyhedron.topology.elements(1).associate { edge ->
        edge.id to edgeFraction.value(selectionContext(edge)).also {
            require(it.isFinite() && it > 0.0 && it < 0.5) {
                "gyro fraction for edge ${edge.id.value} must be finite and in (0, 0.5), got $it"
            }
        }
    }
    val transformed = polyhedron.topology.transform(ConwayOperator.GYRO)
    return applyTransform(
        transformed,
        ConwayOperator.GYRO,
        GyroSubdivisionRealization.varying(fractions),
        attributePolicy,
        recordNotation = true,
        deployment = TransformDeployment.selected(
            ConwayOperator.GYRO,
            polyhedron.topology.elements(1).map { ElementRef(it.id, it.rank) },
            "edgeFraction",
            fractions,
        ),
    )
}

fun PolyhedralObject.gyro(
    realizationPolicy: RealizationPolicy3 = GyroSubdivisionRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.GYRO, realizationPolicy, attributePolicy)

fun PolytopeTransformResult.gyro(
    realizationPolicy: RealizationPolicy3 = GyroSubdivisionRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.GYRO, realizationPolicy, attributePolicy)

fun PolyhedralObject.join(attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes) =
    transform(ConwayOperator.JOIN, attributePolicy = attributePolicy)

fun PolyhedralObject.expand(attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes) =
    transform(ConwayOperator.EXPAND, attributePolicy = attributePolicy)

fun PolyhedralObject.snub(attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes) =
    transform(ConwayOperator.SNUB, attributePolicy = attributePolicy)

fun PolyhedralObject.meta(attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes) =
    transform(ConwayOperator.META, attributePolicy = attributePolicy)

fun PolyhedralObject.bevel(attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes) =
    transform(ConwayOperator.BEVEL, attributePolicy = attributePolicy)
