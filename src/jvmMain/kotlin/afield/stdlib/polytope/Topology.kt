package afield.stdlib.polytope

/** A globally unique element identifier within one [PolytopeTopology]. */
@JvmInline
value class ElementId(val value: Int)

/** An element of the incidence poset. Ranks are zero-based. */
data class Element(
    val id: ElementId,
    val rank: Int,
)

/** One structured problem found while building or validating a topology. */
sealed interface ValidationIssue {
    val message: String
}

data class InvalidElementReference(val context: String, val reference: ElementId) : ValidationIssue {
    override val message = "$context references missing element ${reference.value}"
}

data class IncorrectRankTransition(
    val lower: ElementId,
    val lowerRank: Int,
    val higher: ElementId,
    val higherRank: Int,
) : ValidationIssue {
    override val message =
        "incidence ${lower.value} (rank $lowerRank) -> ${higher.value} (rank $higherRank) is not between adjacent ranks"
}

data class DuplicateIncidence(val lower: ElementId, val higher: ElementId) : ValidationIssue {
    override val message = "incidence ${lower.value} -> ${higher.value} appears more than once"
}

data class NonReciprocalIncidence(val lower: ElementId, val higher: ElementId) : ValidationIssue {
    override val message = "incidence ${lower.value} -> ${higher.value} is not reciprocal"
}

data class EdgeEndpointCount(val edge: ElementId, val count: Int) : ValidationIssue {
    override val message = "edge ${edge.value} has $count distinct endpoint vertices; expected exactly 2"
}

data class FaceTooSmall(val face: ElementId, val edgeCount: Int, val vertexCount: Int) : ValidationIssue {
    override val message =
        "face ${face.value} has $edgeCount distinct edges and $vertexCount boundary vertices; expected at least 3 of each"
}

data class MissingFaceCycle(val face: ElementId) : ValidationIssue {
    override val message = "rank-2 face ${face.value} has no ordered boundary cycle"
}

data class FaceCycleOwnerWrongRank(val element: ElementId, val actualRank: Int) : ValidationIssue {
    override val message =
        "ordered face cycle belongs to element ${element.value} of rank $actualRank; expected rank 2"
}

data class FaceCycleWrongElement(val face: ElementId, val element: ElementId, val actualRank: Int?) : ValidationIssue {
    override val message = when (actualRank) {
        null -> "face ${face.value} cycle references missing element ${element.value}"
        else -> "face ${face.value} cycle references element ${element.value} of rank $actualRank; expected a vertex"
    }
}

data class DuplicateFaceCycleVertex(val face: ElementId, val vertex: ElementId) : ValidationIssue {
    override val message = "face ${face.value} cycle repeats vertex ${vertex.value}"
}

data class FaceCycleVertexNotIncident(val face: ElementId, val vertex: ElementId) : ValidationIssue {
    override val message = "face ${face.value} cycle vertex ${vertex.value} is absent from the face's edge incidences"
}

data class FaceCycleEdgeMismatch(val face: ElementId, val a: ElementId, val b: ElementId) : ValidationIssue {
    override val message =
        "face ${face.value} boundary step ${a.value} -> ${b.value} has no matching edge incident to that face"
}

data class FaceIncidenceAbsentFromCycle(val face: ElementId, val edge: ElementId) : ValidationIssue {
    override val message = "face ${face.value} incidence edge ${edge.value} does not occur in its ordered cycle"
}

data class DisconnectedFaceBoundary(val face: ElementId) : ValidationIssue {
    override val message = "face ${face.value} incident edges do not form one connected degree-2 boundary"
}

data class NonManifoldEdge(val edge: ElementId, val faceCount: Int) : ValidationIssue {
    override val message = "edge ${edge.value} belongs to $faceCount faces; a closed manifold surface requires exactly 2"
}

class ValidationResult(issues: List<ValidationIssue>) {
    val issues: List<ValidationIssue> = issues.toList()
    val isValid: Boolean get() = issues.isEmpty()

    fun throwIfInvalid() {
        if (!isValid) throw TopologyValidationException(this)
    }
}

class TopologyValidationException(val result: ValidationResult) : IllegalArgumentException(
    result.issues.joinToString(prefix = "invalid polytope topology:\n", separator = "\n") { "- ${it.message}" }
)

private data class Incidence(val lower: ElementId, val higher: ElementId)

/**
 * Immutable, rank-aware incidence topology.
 *
 * Only incidences between adjacent ranks are represented. For an ordinary polyhedron rank 3 is
 * the single whole-polyhedron element. Ordered rank-2 boundaries are stored explicitly as vertex
 * cycles because unoriented incidence alone does not choose a polygon traversal.
 *
 * Public array accessors return copies; callers cannot mutate the stored incidence or cycles.
 */
class PolytopeTopology internal constructor(
    val rank: Int,
    elementsByRank: List<List<Element>>,
    incidences: List<Pair<ElementId, ElementId>>,
    faceCycles: Map<ElementId, IntArray>,
) {
    val elementsByRank: List<List<Element>> = java.util.Collections.unmodifiableList(
        elementsByRank.map { java.util.Collections.unmodifiableList(it.toList()) },
    )

    private val elementById = this.elementsByRank.flatten().associateBy { it.id }
    private val rawIncidences = incidences.map { Incidence(it.first, it.second) }
    private val storedFaceCycles = faceCycles.mapValues { (_, cycle) -> cycle.copyOf() }
    private val up: Map<ElementId, IntArray>
    private val down: Map<ElementId, IntArray>

    init {
        require(rank >= 0) { "rank must be non-negative, got $rank" }
        val upMutable = elementById.keys.associateWith { mutableListOf<Int>() }
        val downMutable = elementById.keys.associateWith { mutableListOf<Int>() }
        for ((lower, higher) in rawIncidences) {
            val lowElement = elementById[lower]
            val highElement = elementById[higher]
            if (lowElement != null && highElement != null && highElement.rank == lowElement.rank + 1) {
                upMutable.getValue(lower).add(higher.value)
                downMutable.getValue(higher).add(lower.value)
            }
        }
        up = upMutable.mapValues { (_, ids) -> ids.toIntArray() }
        down = downMutable.mapValues { (_, ids) -> ids.toIntArray() }
    }

    fun elements(rank: Int): List<Element> = elementsByRank.getOrElse(rank) { emptyList() }

    fun element(id: ElementId): Element? = elementById[id]

    /** IDs incident one rank above [element], in stable construction order. */
    fun incidentUp(element: ElementId): IntArray = up[element]?.copyOf() ?: intArrayOf()

    /** IDs incident one rank below [element], in stable construction order. */
    fun incidentDown(element: ElementId): IntArray = down[element]?.copyOf() ?: intArrayOf()

    /** The explicitly stored ordered vertex boundary of a rank-2 face. */
    fun faceCycle(face: ElementId): IntArray = storedFaceCycles[face]?.copyOf()
        ?: throw IllegalArgumentException("element ${face.value} has no ordered face cycle")

    fun faceCycleOrNull(face: ElementId): IntArray? = storedFaceCycles[face]?.copyOf()

    /** All vertices of [face], in boundary order. */
    fun verticesOfFace(face: ElementId): List<Element> = faceCycle(face).map { id ->
        elementById[ElementId(id)] ?: throw IllegalStateException("face ${face.value} references missing vertex $id")
    }

    /** Alternating count of all proper ranks; the whole-polytope rank is deliberately excluded. */
    fun eulerCharacteristic(): Int = (0 until rank).sumOf { r ->
        if (r % 2 == 0) elements(r).size else -elements(r).size
    }

    /**
     * Validate basic incidence and explicit polygon boundaries. Set [checkManifold] to additionally
     * require every edge to have exactly two incident faces. Non-manifold structures remain legal
     * at the core level.
     */
    fun validate(checkManifold: Boolean = false): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        val seenIncidences = mutableSetOf<Pair<ElementId, ElementId>>()

        for ((lower, higher) in rawIncidences) {
            val low = elementById[lower]
            val high = elementById[higher]
            if (low == null) issues += InvalidElementReference("incidence lower endpoint", lower)
            if (high == null) issues += InvalidElementReference("incidence higher endpoint", higher)
            if (low != null && high != null && high.rank != low.rank + 1) {
                issues += IncorrectRankTransition(lower, low.rank, higher, high.rank)
            }
            if (!seenIncidences.add(lower to higher)) issues += DuplicateIncidence(lower, higher)
        }
        for (face in storedFaceCycles.keys) {
            val owner = elementById[face]
            if (owner == null) issues += InvalidElementReference("ordered face cycle owner", face)
            else if (owner.rank != 2) issues += FaceCycleOwnerWrongRank(face, owner.rank)
        }

        for ((lower, higherIds) in up) for (higherValue in higherIds) {
            val higher = ElementId(higherValue)
            if (down[higher]?.contains(lower.value) != true) issues += NonReciprocalIncidence(lower, higher)
        }
        for ((higher, lowerIds) in down) for (lowerValue in lowerIds) {
            val lower = ElementId(lowerValue)
            if (up[lower]?.contains(higher.value) != true) issues += NonReciprocalIncidence(lower, higher)
        }

        for (edge in elements(1)) {
            val endpoints = down.getValue(edge.id).distinct()
            if (endpoints.size != 2) issues += EdgeEndpointCount(edge.id, endpoints.size)
            if (checkManifold && rank >= 3) {
                val faces = up.getValue(edge.id).distinct().count { elementById[ElementId(it)]?.rank == 2 }
                if (faces != 2) issues += NonManifoldEdge(edge.id, faces)
            }
        }

        for (face in elements(2)) validateFace(face.id, issues)
        return ValidationResult(issues.distinct())
    }

    private fun validateFace(face: ElementId, issues: MutableList<ValidationIssue>) {
        val faceEdges = down.getValue(face).distinct().map(::ElementId)
        val cycle = storedFaceCycles[face]
        if (cycle == null) {
            issues += MissingFaceCycle(face)
            if (faceEdges.size < 3) issues += FaceTooSmall(face, faceEdges.size, 0)
            return
        }

        val cycleIds = cycle.map(::ElementId)
        if (cycleIds.size < 3 || faceEdges.size < 3) issues += FaceTooSmall(face, faceEdges.size, cycleIds.size)
        val seenVertices = mutableSetOf<ElementId>()
        for (vertex in cycleIds) {
            val element = elementById[vertex]
            if (element?.rank != 0) issues += FaceCycleWrongElement(face, vertex, element?.rank)
            if (!seenVertices.add(vertex)) issues += DuplicateFaceCycleVertex(face, vertex)
        }

        val endpointPairs = mutableMapOf<Set<ElementId>, MutableList<ElementId>>()
        val incidentVertices = mutableSetOf<ElementId>()
        for (edge in faceEdges) {
            val endpoints = (down[edge] ?: intArrayOf()).distinct().map(::ElementId)
            incidentVertices += endpoints
            if (endpoints.size == 2) endpointPairs.getOrPut(endpoints.toSet()) { mutableListOf() }.add(edge)
        }
        for (vertex in cycleIds) {
            if (elementById[vertex]?.rank == 0 && vertex !in incidentVertices) {
                issues += FaceCycleVertexNotIncident(face, vertex)
            }
        }

        val usedEdges = mutableSetOf<ElementId>()
        if (cycleIds.size >= 2) for (i in cycleIds.indices) {
            val a = cycleIds[i]
            val b = cycleIds[(i + 1) % cycleIds.size]
            val candidates = endpointPairs[setOf(a, b)].orEmpty()
            if (candidates.isEmpty()) issues += FaceCycleEdgeMismatch(face, a, b)
            else usedEdges += candidates.first()
        }
        for (edge in faceEdges) if (edge !in usedEdges) issues += FaceIncidenceAbsentFromCycle(face, edge)

        if (!formsSingleBoundary(faceEdges)) issues += DisconnectedFaceBoundary(face)
    }

    private fun formsSingleBoundary(edges: List<ElementId>): Boolean {
        if (edges.size < 3) return false
        val neighbors = mutableMapOf<ElementId, MutableSet<ElementId>>()
        for (edge in edges) {
            val endpoints = (down[edge] ?: intArrayOf()).distinct().map(::ElementId)
            if (endpoints.size != 2) return false
            neighbors.getOrPut(endpoints[0]) { mutableSetOf() }.add(endpoints[1])
            neighbors.getOrPut(endpoints[1]) { mutableSetOf() }.add(endpoints[0])
        }
        if (neighbors.values.any { it.size != 2 }) return false
        val reached = mutableSetOf<ElementId>()
        val pending = ArrayDeque<ElementId>()
        pending += neighbors.keys.firstOrNull() ?: return false
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (reached.add(at)) neighbors[at].orEmpty().forEach { pending += it }
        }
        return reached.size == neighbors.size
    }

    /**
     * Abstract dual. Proper ranks are reversed while the whole-polytope rank remains the whole.
     * For rank 3 this is exactly vertices <-> faces and edges <-> edges.
     */
    fun dual(): PolytopeTopology {
        require(rank >= 1) { "rank-0 topology has no represented proper-rank dual" }
        val properMax = rank - 1
        val builder = PolytopeTopologyBuilder(rank)
        val mapped = mutableMapOf<ElementId, ElementId>()

        for (newRank in 0..properMax) {
            val oldRank = properMax - newRank
            for (old in elements(oldRank)) mapped[old.id] = builder.addElement(newRank)
        }
        for (oldTop in elements(rank)) mapped[oldTop.id] = builder.addElement(rank)

        for ((lower, higher) in rawIncidences) {
            val low = elementById[lower] ?: continue
            val high = elementById[higher] ?: continue
            if (low.rank < rank && high.rank < rank && high.rank == low.rank + 1) {
                builder.addIncidence(mapped.getValue(higher), mapped.getValue(lower))
            }
        }
        for (newTop in elements(rank).map { mapped.getValue(it.id) }) {
            for (facet in elements(0).map { mapped.getValue(it.id) }) builder.addIncidence(facet, newTop)
        }

        val provisional = builder.build()
        for (face in provisional.elements(2)) {
            provisional.cycleFromIncidence(face.id)?.let { builder.setFaceCycle(face.id, it) }
        }
        return builder.build()
    }

    private fun cycleFromIncidence(face: ElementId): List<ElementId>? {
        val edges = (down[face] ?: intArrayOf()).map(::ElementId)
        val neighbors = mutableMapOf<ElementId, MutableSet<ElementId>>()
        for (edge in edges) {
            val vertices = (down[edge] ?: intArrayOf()).distinct().map(::ElementId)
            if (vertices.size != 2) return null
            neighbors.getOrPut(vertices[0]) { mutableSetOf() }.add(vertices[1])
            neighbors.getOrPut(vertices[1]) { mutableSetOf() }.add(vertices[0])
        }
        if (neighbors.isEmpty() || neighbors.values.any { it.size != 2 }) return null
        val start = neighbors.keys.minBy { it.value }
        val ring = mutableListOf(start)
        var previous: ElementId? = null
        var current = start
        while (true) {
            val next = neighbors.getValue(current).sortedBy { it.value }.firstOrNull { it != previous } ?: return null
            if (next == start) break
            if (next in ring) return null
            ring += next
            previous = current
            current = next
        }
        return ring.takeIf { it.size == neighbors.size }
    }
}

/** Mutable assembly surface whose [build] result is immutable. */
class PolytopeTopologyBuilder(val rank: Int) {
    private val elements = MutableList(rank + 1) { mutableListOf<Element>() }
    private val incidences = mutableListOf<Pair<ElementId, ElementId>>()
    private val faceCycles = mutableMapOf<ElementId, IntArray>()
    private var nextId = 0

    init {
        require(rank >= 0) { "rank must be non-negative, got $rank" }
    }

    fun addElement(rank: Int): ElementId {
        require(rank in 0..this.rank) { "element rank $rank is outside topology ranks 0..${this.rank}" }
        val id = ElementId(nextId++)
        elements[rank] += Element(id, rank)
        return id
    }

    /**
     * Record an incidence. Reference and rank errors are intentionally reported by [validate]
     * after [build], allowing tools and tests to inspect all malformed input at once.
     */
    fun addIncidence(lower: ElementId, higher: ElementId): PolytopeTopologyBuilder = apply {
        incidences += lower to higher
    }

    fun setFaceCycle(face: ElementId, vertices: IntArray): PolytopeTopologyBuilder = apply {
        faceCycles[face] = vertices.copyOf()
    }

    fun setFaceCycle(face: ElementId, vertices: List<ElementId>): PolytopeTopologyBuilder =
        setFaceCycle(face, vertices.map { it.value }.toIntArray())

    fun build(): PolytopeTopology = PolytopeTopology(rank, elements, incidences, faceCycles)

    fun buildValidated(checkManifold: Boolean = false): PolytopeTopology = build().also {
        it.validate(checkManifold).throwIfInvalid()
    }
}

/**
 * Small rank-3 combinatorial isomorphism helper. It tries vertex bijections, then compares edge
 * endpoint pairs and face cycles up to rotation/reversal. This is intentionally not a general
 * unrestricted incidence-poset graph-isomorphism solver.
 */
fun PolytopeTopology.isCombinatoriallyIsomorphicTo(other: PolytopeTopology): Boolean {
    if (rank != 3 || other.rank != 3) return false
    if ((0..3).any { elements(it).size != other.elements(it).size }) return false
    val sourceVertices = elements(0)
    val targetVertices = other.elements(0)

    fun signature(topology: PolytopeTopology, vertex: ElementId): Pair<Int, List<Int>> {
        val edgeIds = topology.incidentUp(vertex).map(::ElementId)
        val faceSizes = edgeIds.flatMap { topology.incidentUp(it).map(::ElementId) }
            .distinct()
            .map { topology.faceCycle(it).size }
            .sorted()
        return edgeIds.distinct().size to faceSizes
    }

    val sourceSignatures = sourceVertices.associate { it.id to signature(this, it.id) }
    val targetSignatures = targetVertices.associate { it.id to signature(other, it.id) }
    val mapping = mutableMapOf<ElementId, ElementId>()
    val used = mutableSetOf<ElementId>()

    fun edgeMultiset(topology: PolytopeTopology, map: Map<ElementId, ElementId>?): Map<Set<Int>, Int> =
        topology.elements(1).map { edge ->
            topology.incidentDown(edge.id).map { id -> map?.get(ElementId(id))?.value ?: id }.toSet()
        }.groupingBy { it }.eachCount()

    fun canonicalCycle(values: List<Int>): List<Int> {
        if (values.isEmpty()) return values
        val variants = mutableListOf<List<Int>>()
        for (base in listOf(values, values.reversed())) for (shift in base.indices) {
            variants += List(base.size) { base[(it + shift) % base.size] }
        }
        fun less(a: List<Int>, b: List<Int>): Boolean {
            for (i in a.indices) if (a[i] != b[i]) return a[i] < b[i]
            return false
        }
        return variants.reduce { best, candidate -> if (less(candidate, best)) candidate else best }
    }

    fun faceMultiset(topology: PolytopeTopology, map: Map<ElementId, ElementId>?): Map<List<Int>, Int> =
        topology.elements(2).map { face ->
            canonicalCycle(topology.faceCycle(face.id).map { id -> map?.get(ElementId(id))?.value ?: id })
        }.groupingBy { it }.eachCount()

    val targetEdges = edgeMultiset(other, null)
    val targetFaces = faceMultiset(other, null)

    fun search(index: Int): Boolean {
        if (index == sourceVertices.size) {
            return edgeMultiset(this, mapping) == targetEdges && faceMultiset(this, mapping) == targetFaces
        }
        val source = sourceVertices[index].id
        for (target in targetVertices) {
            if (target.id in used || sourceSignatures[source] != targetSignatures[target.id]) continue
            mapping[source] = target.id
            used += target.id
            if (search(index + 1)) return true
            used -= target.id
            mapping -= source
        }
        return false
    }
    return search(0)
}
