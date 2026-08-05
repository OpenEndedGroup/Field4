@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import afield.stdlib.Vec3

/**
 * The Conway operator algebra: thirteen named operations that rewrite a polyhedron's combinatorics,
 * each paired with a policy for giving the result coordinates.
 *
 * A transform happens in two independent halves, and keeping them apart is the whole point of this
 * file. [PolytopeTopology.transform] is pure combinatorics — it consumes an incidence complex and
 * produces a new one plus, for every output element, an [ImmediateElementProvenance] naming the
 * source elements it came from and the role it plays (`face-center`, `edge-midpoint`,
 * `kis-triangle`, `gyro-pentagon`, …). A [RealizationPolicy3] then reads those roles and computes
 * coordinates from the source's; an [AttributePropagationPolicy] does the same for attributes. Swap
 * the policy and you get a different solid over identical combinatorics.
 *
 * ## Five primitives, eight composites
 *
 * `d` dual, `a` ambo, `k` kis, `t` truncate and `g` gyro are implemented. `j n z o e s m b` are
 * defined as primitive *words* — `j = da`, `b = ta`, `s = dgd`, `o = jj`, `m = kj` — and run one
 * primitive at a time, so they cost as many topology rebuilds as their expansion is long.
 * [PolytopeTransformResult.history] is the expanded trace; `notationHistory` keeps the composite you
 * actually asked for.
 *
 * ## Beyond standard notation
 *
 * `k`, `t` and `g` also take an [ElementSelector] and an [ElementDoubleParameter], so one step can
 * kis only the pentagons, or truncate every vertex by a fraction that varies with its height. Conway
 * notation cannot say either thing; [TransformDeployment] records what was selected and which
 * scalars were resolved, so such a step is still exactly replayable.
 *
 * ## What this will not do
 *
 * - **Rank 3 and closed manifolds only.** Every step validates its input *and* its output with
 *   manifold checking on, so a non-manifold complex or an inconsistently oriented vertex fan throws
 *   rather than producing a plausible-looking bad mesh.
 * - **The composites are declared on [PolyhedralObject] only.** `cube().ambo()` returns a
 *   [PolytopeTransformResult], which has no `bevel()` — so `cube().ambo().bevel()` does not compile.
 *   Write `cube().ambo().transform(ConwayOperator.BEVEL)`, or let `conway("baC")` parse the whole
 *   expression. `needle`, `zip` and `ortho` have no extension function at all and are reachable only
 *   through [ConwayOperator] or Conway notation.
 * - **Nothing here produces a canonical shape.** The default policies are cheap local constructions —
 *   a centroid, a midpoint, a fraction along an edge — so a long chain drifts away from anything
 *   symmetric. [PolyhedralObject.canonicalize] is the repair.
 */

/**
 * The Conway operator vocabulary, each with the single letter Conway notation writes it as.
 *
 * [primitive] is the load-bearing flag: only the five primitive operators (`d`, `a`, `k`, `t`, `g`)
 * have an implementation in [PolytopeTopology.transform]. The other eight are *words* over those
 * five and are expanded by `primitiveExpansion` before anything runs, which is why they cost as
 * many topology rebuilds as their expansion is long. [displayName] is the lower-case English name
 * used in error messages, not a UI label.
 */
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

/**
 * A source element named by both id and rank, so provenance can be read without the topology in hand.
 */
data class ElementRef(val id: ElementId, val rank: Int)

/** Direct explanation of how one output element was made from elements of the previous topology. */
data class ImmediateElementProvenance(
    val role: String,
    val sources: List<ElementRef>,
    val attributeSources: List<ElementRef> = sources,
)

/**
 * The complete derivation of one output element, back to the seed elements it descends from.
 *
 * [ImmediateElementProvenance] records one step; this records the whole chain, so a vertex of `bkC`
 * still knows which cube face it came from. Nodes are shared between siblings but traversal is a
 * tree, so a shared ancestor is visited once per path — use `sourceElements` rather than walking it
 * by hand when all you want is the seed set.
 */
sealed interface ElementLineage {
    /**
     * A seed element: it was already in the object the chain started from.
     */
    data class Source(val element: ElementRef) : ElementLineage

    /**
     * One operator's output, naming the role the transform gave it (`face-center`, `kis-triangle`, …)
     * and the lineages it was built from.
     */
    data class Derived(
        val operation: ConwayOperator,
        val role: String,
        val parents: List<ElementLineage>,
    ) : ElementLineage
}

/**
 * Every seed element this lineage descends from, deduplicated, in order of first encounter.
 */
fun ElementLineage.sourceElements(): Set<ElementRef> = when (this) {
    is ElementLineage.Source -> setOf(element)
    is ElementLineage.Derived -> parents.flatMapTo(linkedSetOf()) { it.sourceElements() }
}

/**
 * One primitive operator's combinatorial output: the new topology, plus why each of its elements
 * exists.
 *
 * No coordinates and no attributes — this is the half of a Conway step that a [RealizationPolicy3]
 * and an [AttributePropagationPolicy] then read in order to produce the other half. The topology
 * has already been validated as a closed manifold by the time you hold one of these.
 */
class TopologyTransform internal constructor(
    val operation: ConwayOperator,
    val topology: PolytopeTopology,
    immediateProvenance: Map<ElementId, ImmediateElementProvenance>,
) {
    private val storedProvenance = immediateProvenance.toMap()

    /**
     * How [element] was made, in one step. Provenance is total over the output, so an unknown element
     * throws rather than returning null — a miss means the caller has an id from a different topology.
     */
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

/**
 * Coordinates for a transform's output, computed from the source object's coordinates and the
 * transform's provenance roles.
 *
 * Returning `null` means "no realization", and every built-in policy does exactly that when the
 * source is itself abstract — so a Conway chain over a topology with no coordinates stays abstract
 * instead of inventing some. A policy that meets a role it does not recognise should fail loudly;
 * the built-ins call `error`, because a silently misplaced vertex is far harder to notice than an
 * exception.
 */
fun interface RealizationPolicy3 {
    fun realize(source: PolyhedralObject, transform: TopologyTransform): Realization3?
}

/**
 * Which of a source element's attributes survive onto one output element.
 *
 * Called once per output element with that element's [ImmediateElementProvenance]; whatever map it
 * returns *is* the output element's attribute map, so an empty map drops everything.
 * [PreserveCommonAttributes] is the default and the conservative choice. Note that a policy is
 * handed `provenance.attributeSources`, which is not always the same list as `provenance.sources` —
 * kis triangles, for instance, are sourced from a face and an edge but inherit only the face's
 * attributes.
 */
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

/**
 * A polyhedron plus the record of how it was made: the object itself, per-element lineage, and
 * three histories.
 *
 * This — not [PolyhedralObject] — is what a Conway chain passes along, because each step needs the
 * previous step's lineage to keep provenance and attributes attached. [history] is the expanded
 * primitive trace, [notationHistory] is the operators the caller actually named (so `bC` reads back
 * as one `bevel` rather than `truncate` then `ambo`), and [deployments] records the element
 * selections and resolved scalar parameters needed to replay a step exactly.
 */
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

    /**
     * How [element] came to be, back to the seed. Throws for an element this result does not contain.
     */
    fun lineage(element: ElementId): ElementLineage = storedLineage[element]
        ?: throw IllegalArgumentException("element ${element.value} is not in this transform result")

    /**
     * The seed elements [element] descends from — its lineage flattened and deduplicated.
     */
    fun sourceElements(element: ElementId): Set<ElementRef> = lineage(element).sourceElements()

    companion object {
        /**
         * Start a chain from an existing object: every element becomes its own [ElementLineage.Source] and
         * all three histories start empty. Cheap; it copies no coordinates.
         */
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
        // Checked before validation, because a holed face fails the manifold pass as a fistful of
        // NonManifoldEdge issues and that is a diagnosis of the symptom rather than the cause.
        val holed = topology.elements(2).firstOrNull { topology.faceHasHoles(it.id) }
        if (holed != null) throw IllegalArgumentException(
            "face ${holed.id.value} has ${topology.faceHoles(holed.id).size} hole(s); Conway operators are " +
                "defined here for closed manifolds, and a holed face is a surface with boundary",
        )
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

/**
 * Run one primitive operator over this complex, producing new combinatorics and their provenance.
 *
 * Primitives only — a composite throws, naming the primitive word to use instead. The input must be
 * rank 3 and a closed manifold with a consistently oriented fan at every vertex, and the output is
 * validated the same way, so both a malformed input and an operator that produced a non-manifold
 * result surface as an exception rather than as a bad mesh.
 */
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

/**
 * Kis only the named faces: each grows an apex, every other face is copied unchanged.
 *
 * The selection must be rank-2 elements of this topology; anything else throws. Combinatorics only —
 * `PolytopeTransformResult.kis` is the version that also places the apexes.
 */
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

/**
 * Truncate only the named vertices: each is replaced by a face, its neighbours left alone.
 *
 * The selection must be rank-0 elements of this topology; anything else throws. A face with only
 * some of its corners selected is kept and grows the corresponding pairs of points, recorded with
 * the role `partially-truncated-face` — which is a shape standard Conway notation cannot describe.
 */
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

/**
 * Every dual vertex sits at the centroid of the face it replaces.
 *
 * Cheap, and exactly right for a face-transitive seed. For anything else the dual's faces are not
 * planar, because a dual face's vertices are the centroids of the faces around one original vertex
 * and those centroids need not be coplanar — and `dd` therefore returns a shrunken, distorted copy
 * rather than the original. Follow with [PolyhedralObject.canonicalize] when that matters.
 */
object DualCentroidRealization : RealizationPolicy3 {
    override fun realize(source: PolyhedralObject, transform: TopologyTransform): Realization3? {
        if (source.realization == null) return null
        return realizeVertices(transform) { provenance -> source.centroid(provenance.sources.single().id) }
    }
}

/**
 * Every ambo vertex sits at its source edge's midpoint — the rectification you would draw by hand.
 *
 * The faces inherited from original faces are planar whenever those were; the new vertex-faces are
 * planar only when the edge midpoints around a vertex happen to be coplanar, which for a Conway
 * output is usually not true.
 */
object AmboMidpointRealization : RealizationPolicy3 {
    override fun realize(source: PolyhedralObject, transform: TopologyTransform): Realization3? {
        if (source.realization == null) return null
        return realizeVertices(transform) { provenance -> source.edgePoint(provenance.sources.single().id, 0.5f) }
    }
}

/**
 * Raise each selected face's apex along the face normal, by a multiple of that face's own mean edge
 * length.
 *
 * [heightScale] is therefore relative rather than a distance: the same number gives visually
 * comparable spikes on a triangle and on a decagon, and on an object of any overall size. Must be
 * finite and positive — 0 would leave the apex in the face plane and produce degenerate triangles,
 * so it is rejected rather than tolerated.
 */
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

/**
 * Cut each selected vertex off at [fraction] of the way along every edge leaving it.
 *
 * Strictly between 0 and 0.5, and that bound is not conservative: at 0.5 the two cut points on a
 * shared edge coincide and the edge collapses. The default 1/3 is *not* the value that makes a
 * truncated Platonic exactly Archimedean — it is close enough to read as one, and
 * [PolyhedralObject.canonicalize] is how you get the exact solid.
 */
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

/**
 * Place gyro's two directed points on each edge at [edgeFraction] and one minus [edgeFraction]
 * along it.
 *
 * Strictly between 0 and 0.5 for the same reason as truncation: at 0.5 the pair coincides and the
 * pentagons degenerate. 1/3 is the default and the value gyro is conventionally drawn with.
 */
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

/**
 * Apply one Conway operator — primitive or composite — and start a chain from this object.
 *
 * Equivalent to seeding a [PolytopeTransformResult] and transforming that. Passing a
 * [realizationPolicy] for a *composite* throws: a composite runs as several primitive steps and one
 * policy cannot speak for all of them; configure the primitives instead.
 */
fun PolyhedralObject.transform(
    operation: ConwayOperator,
    realizationPolicy: RealizationPolicy3? = null,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = PolytopeTransformResult.seed(this).transform(operation, realizationPolicy, attributePolicy)

/**
 * Apply the next Conway operator to this chain.
 *
 * A composite is expanded into its primitive word and run one primitive at a time with each
 * primitive's default policy, then recorded in [notationHistory] as the single operator you asked
 * for while [history] keeps every step. Passing a [realizationPolicy] alongside a composite throws.
 * This is also the escape hatch for the composites that have no extension function of their own:
 * `transform(ConwayOperator.NEEDLE)`, `ZIP`, `ORTHO`, and any composite mid-chain.
 */
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

/**
 * Conway `d`: faces become vertices and vertices become faces, edges staying edges.
 *
 * An involution combinatorially — `dd` is the original complex — but not geometrically, because the
 * default [DualCentroidRealization] moves every vertex to a face centroid. See
 * [DualCentroidRealization] for what that costs.
 */
fun PolyhedralObject.dual(
    realizationPolicy: RealizationPolicy3 = DualCentroidRealization,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.DUAL, realizationPolicy, attributePolicy)

/**
 * Conway `a`: rectification. Each edge midpoint becomes a vertex, so the result has one face per
 * original face and one per original vertex, and every vertex has degree exactly 4.
 *
 * `aC` is the cuboctahedron; `aa` is the composite `e` (expand).
 */
fun PolyhedralObject.ambo(
    realizationPolicy: RealizationPolicy3 = AmboMidpointRealization,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.AMBO, realizationPolicy, attributePolicy)

/**
 * Conway `k`: raise a pyramid on every face. Original vertices stay exactly where they were; a face
 * of n sides becomes n triangles around a new apex, so the result is a triangulation.
 *
 * `kC` is the tetrakis hexahedron. The [ElementSelector] overload kises only some faces, which is
 * the direction this library extends furthest beyond standard notation.
 */
fun PolyhedralObject.kis(
    realizationPolicy: RealizationPolicy3 = KisFaceNormalRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.KIS, realizationPolicy, attributePolicy)

/**
 * Conway `t`: cut every vertex off and replace it with a face. A vertex of degree n becomes an
 * n-gon; a face of m sides becomes a 2m-gon.
 *
 * `tC` is an approximately regular truncated cube — canonicalize for the Archimedean one. The
 * [ElementSelector] overload truncates only some vertices, which standard notation cannot say.
 */
fun PolyhedralObject.truncate(
    realizationPolicy: RealizationPolicy3 = TruncateAlongEdgesRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.TRUNCATE, realizationPolicy, attributePolicy)

/**
 * `dual` in the middle of a chain: same operator, carrying lineage, history and deployments forward.
 */
fun PolytopeTransformResult.dual(
    realizationPolicy: RealizationPolicy3 = DualCentroidRealization,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.DUAL, realizationPolicy, attributePolicy)

/**
 * `ambo` in the middle of a chain: same operator, carrying lineage, history and deployments forward.
 */
fun PolytopeTransformResult.ambo(
    realizationPolicy: RealizationPolicy3 = AmboMidpointRealization,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.AMBO, realizationPolicy, attributePolicy)

/**
 * `kis` in the middle of a chain: same operator, carrying lineage, history and deployments forward.
 */
fun PolytopeTransformResult.kis(
    realizationPolicy: RealizationPolicy3 = KisFaceNormalRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.KIS, realizationPolicy, attributePolicy)

/**
 * `truncate` in the middle of a chain: same operator, carrying lineage, history and deployments
 * forward.
 */
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

/**
 * Kis only the faces the selector matches, mid-chain, with a height scale evaluated once per
 * selected face.
 *
 * Both the selection and the resolved heights are recorded in [deployments], so the step can be
 * replayed exactly. A height that is not finite and positive throws, naming the face.
 */
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

/**
 * Truncate only the vertices the selector matches, mid-chain, with a cut fraction evaluated once per
 * selected vertex.
 *
 * Both the selection and the resolved fractions are recorded in [deployments]. A fraction outside
 * (0, 0.5) throws, naming the vertex.
 */
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

/**
 * Gyro everywhere, mid-chain, with the directed edge-point fraction evaluated once per source edge.
 *
 * Unlike the selector overloads of kis and truncate this has no selection — gyro is applied to the
 * whole object and only its *parameter* varies, because a partially gyrated surface has no
 * well-defined combinatorics here.
 */
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

/**
 * Conway `g`: every oriented half-edge becomes a pentagon, so an n-gon face becomes n pentagons and
 * the result is chiral.
 *
 * `gD` gives the combinatorics of the pentagonal hexecontahedron. `s = dgd` (snub) is how the snub
 * solids are built from it.
 */
fun PolyhedralObject.gyro(
    realizationPolicy: RealizationPolicy3 = GyroSubdivisionRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.GYRO, realizationPolicy, attributePolicy)

/**
 * `gyro` in the middle of a chain: same operator, carrying lineage, history and deployments forward.
 */
fun PolytopeTransformResult.gyro(
    realizationPolicy: RealizationPolicy3 = GyroSubdivisionRealization(),
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = transform(ConwayOperator.GYRO, realizationPolicy, attributePolicy)

/**
 * Conway `j = da`: the rhombic form. Every original edge becomes one quadrilateral spanning its two
 * endpoints and the centres of its two faces; `jC` is the rhombic dodecahedron.
 *
 * Declared on [PolyhedralObject] only, like every composite here — mid-chain write
 * `transform(ConwayOperator.JOIN)`, or let `conway("jC")` parse the whole expression.
 */
fun PolyhedralObject.join(attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes) =
    transform(ConwayOperator.JOIN, attributePolicy = attributePolicy)

/**
 * Conway `e = aa`: pull every face away from the centre, filling the gaps with a quadrilateral per
 * original edge and an n-gon per original vertex. `eC` is the rhombicuboctahedron.
 *
 * Declared on [PolyhedralObject] only; mid-chain use `transform(ConwayOperator.EXPAND)`.
 */
fun PolyhedralObject.expand(attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes) =
    transform(ConwayOperator.EXPAND, attributePolicy = attributePolicy)

/**
 * Conway `s = dgd`: the snub. Original faces survive, separated by bands of triangles, and the
 * result is chiral. `sC` is the snub cube.
 *
 * Declared on [PolyhedralObject] only; mid-chain use `transform(ConwayOperator.SNUB)`.
 */
fun PolyhedralObject.snub(attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes) =
    transform(ConwayOperator.SNUB, attributePolicy = attributePolicy)

/**
 * Conway `m = kj`: triangulate completely by adding both a face centre and an edge midpoint, so
 * every output face is a triangle. `mC` has 48 of them.
 *
 * Declared on [PolyhedralObject] only; mid-chain use `transform(ConwayOperator.META)`.
 */
fun PolyhedralObject.meta(attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes) =
    transform(ConwayOperator.META, attributePolicy = attributePolicy)

/**
 * Conway `b = ta`: truncate the rectification, so each original face, vertex and edge becomes a face
 * and no two of the original faces touch. `bC` is the truncated cuboctahedron.
 *
 * Declared on [PolyhedralObject] only; mid-chain use `transform(ConwayOperator.BEVEL)`.
 */
fun PolyhedralObject.bevel(attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes) =
    transform(ConwayOperator.BEVEL, attributePolicy = attributePolicy)
