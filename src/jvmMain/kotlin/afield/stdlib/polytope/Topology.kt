@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

/**
 * The abstract incidence complex — the authoritative value in `afield.stdlib.polytope`, and the one
 * layer that knows nothing about coordinates.
 *
 * A [PolytopeTopology] is a set of ranked [Element]s with identity, incidences between *adjacent*
 * ranks only, and an explicitly ordered vertex boundary per rank-2 face. Nothing in this file holds a
 * position, an `FLine`, a triangle, a normal or a renderer; a `Realization3` sits above it and can
 * be replaced or thrown away without disturbing the combinatorics. That separation is what lets a
 * Conway operator discard every coordinate, make new ones, and still answer "which face is this,
 * and what was it before".
 *
 * Ordered cycles are stored rather than derived because unoriented incidence does not choose a
 * traversal: four edges in a ring are equally consistent with two different quadrilaterals. A cycle
 * *can* be recovered from incidence where a boundary happens to be a single ring —
 * [PolytopeTopology.dual] does that for its own output — but that is a construction step, not a
 * property of the poset.
 *
 * ## Ranks, and the element that is the whole thing
 *
 * Ranks are zero-based: 0 vertices, 1 edges, 2 polygons, 3 cells. A topology's
 * [PolytopeTopology.rank] is its *top* rank, and by convention the element or elements there stand
 * for the whole object rather than for a geometric feature. So a polyhedron is rank 3 with one
 * rank-3 element meaning "the solid", and its proper ranks are `0 until rank`. This is why
 * [PolytopeTopology.eulerCharacteristic] sums the proper ranks only: it returns 2 for a closed
 * polyhedral surface and 0 for a convex 4-polytope, which are the numbers a reader is checking
 * against.
 *
 * ## Validation returns values, not exceptions
 *
 * [PolytopeTopologyBuilder] records what it is told and checks almost nothing. Structural
 * complaints are [ValidationIssue] *values* returned by [PolytopeTopology.validate], so a tool or a
 * test can see everything wrong with a malformed input at once, rather than the first thing. Call
 * [ValidationResult.throwIfInvalid] — or [PolytopeTopologyBuilder.buildValidated] — where an
 * exception is what you actually wanted.
 *
 * ```kotlin
 * val b = PolytopeTopologyBuilder(rank = 3)
 * val v = List(3) { b.addElement(0) }
 * val f = b.addElement(2)
 * b.setFaceCycle(f, v)
 * b.build().validate().issues.forEach { println(it.message) }   // three edges never recorded
 * ```
 *
 * ## Faces with holes
 *
 * A face's boundary is an outer cycle plus zero or more *inner* cycles — see [FaceBoundary].
 * [faceCycle] returns the outer ring, which is what the twenty-odd callers of it in this package
 * mean. A hole belongs to its face rather than being a rank-2 element in its own right, so it
 * carries no attributes and no picking id, and casts no vote in [eulerCharacteristic];
 * [cellularEulerCharacteristic] is the reading that subtracts one per hole.
 * `dev_docs/POLYTOPE_ALGEBRA.md` weighs that shape against the alternatives.
 *
 * **Winding is a convention here, not a check.** Outer rings run counter-clockwise about the face
 * normal and inner rings run clockwise. There are no coordinates at this layer, so that cannot be
 * verified here — and nothing downstream depends on it either, because
 * `afield.graphics.PlanarTessellation` normalises each ring's winding and decides which ring is the
 * outer one geometrically. What [validate] does check is combinatorial: rings vertex-disjoint, each
 * ring closed over the face's own edges, every incident edge used by exactly one ring, and one
 * boundary component per ring.
 *
 * A holed face is a surface *with boundary* — its hole edges bound one face, not two — so
 * `validate(checkManifold = true)` rejects it, correctly. [dual] and the Conway operators require a
 * closed manifold and therefore refuse it explicitly, rather than dropping the hole silently.
 *
 * ## What is absent
 *
 * No orientation of the complex as a whole, and no assumption that it is a closed manifold:
 * `validate(checkManifold = true)` is an opt-in stricter pass, and a 4-polytope legitimately fails
 * it because an edge there bounds three or more polygons. No incidence between non-adjacent ranks.
 * And no general incidence-poset isomorphism — [isCombinatoriallyIsomorphicTo] is a rank-3 helper
 * and says so.
 */

/**
 * A globally unique element identifier within one [PolytopeTopology].
 *
 * Ids are drawn from a single counter shared by every rank, so an id does not carry its rank and
 * ordering two ids tells you nothing about which is a vertex. Ask [PolytopeTopology.element].
 */
@JvmInline
value class ElementId(val value: Int)

/**
 * An element of the incidence poset: an identity and the rank it lives at, and nothing else.
 *
 * Ranks are zero-based — 0 vertex, 1 edge, 2 polygon, 3 cell — and an element at a topology's top
 * rank is the whole polytope rather than a feature of it.
 */
data class Element(
    val id: ElementId,
    val rank: Int,
)

/**
 * One structured problem found while validating a topology, reported as a value rather than thrown.
 *
 * Every issue [PolytopeTopology.validate] returns makes [ValidationResult.isValid] false; there is
 * no warning tier. What differs between them is reachability: [NonManifoldEdge] is produced only
 * when `checkManifold` is set, because non-manifold incidence is legal at this layer, and
 * [NonReciprocalIncidence] cannot be produced at all through the public builder.
 *
 * [message] is for a human. The implementing class is the machine-readable half — the validation
 * summary groups issues by class name — so branch on the type, not on the text.
 */
sealed interface ValidationIssue {
    val message: String
}

/** An incidence endpoint or a face-cycle owner names an id that is not in this topology. */
data class InvalidElementReference(val context: String, val reference: ElementId) : ValidationIssue {
    override val message = "$context references missing element ${reference.value}"
}

/**
 * An incidence spans non-adjacent ranks. Such an incidence is also omitted from the up/down maps
 * entirely, so the topology behaves as though it had never been recorded.
 */
data class IncorrectRankTransition(
    val lower: ElementId,
    val lowerRank: Int,
    val higher: ElementId,
    val higherRank: Int,
) : ValidationIssue {
    override val message =
        "incidence ${lower.value} (rank $lowerRank) -> ${higher.value} (rank $higherRank) is not between adjacent ranks"
}

/** The same ordered pair was recorded twice; the second call added nothing. */
data class DuplicateIncidence(val lower: ElementId, val higher: ElementId) : ValidationIssue {
    override val message = "incidence ${lower.value} -> ${higher.value} appears more than once"
}

/**
 * The up and down views of one incidence disagree. Unreachable through [PolytopeTopologyBuilder],
 * which derives both from a single list — a guard against a future construction path, not a
 * diagnosis of anything a caller can write today.
 */
data class NonReciprocalIncidence(val lower: ElementId, val higher: ElementId) : ValidationIssue {
    override val message = "incidence ${lower.value} -> ${higher.value} is not reciprocal"
}

/** An edge with other than two distinct vertices: a self-loop, a hyperedge, or one never joined up. */
data class EdgeEndpointCount(val edge: ElementId, val count: Int) : ValidationIssue {
    override val message = "edge ${edge.value} has $count distinct endpoint vertices; expected exactly 2"
}

/** A face with fewer than three distinct edges, or a cycle with fewer than three vertices. */
data class FaceTooSmall(val face: ElementId, val edgeCount: Int, val vertexCount: Int) : ValidationIssue {
    override val message =
        "face ${face.value} has $edgeCount distinct edges and $vertexCount boundary vertices; expected at least 3 of each"
}

/** A rank-2 element nobody called [PolytopeTopologyBuilder.setFaceCycle] for. */
data class MissingFaceCycle(val face: ElementId) : ValidationIssue {
    override val message = "rank-2 face ${face.value} has no ordered boundary cycle"
}

/** A cycle was set on an element that is not a polygon — usually the wrong id, or an off-by-one rank. */
data class FaceCycleOwnerWrongRank(val element: ElementId, val actualRank: Int) : ValidationIssue {
    override val message =
        "ordered face cycle belongs to element ${element.value} of rank $actualRank; expected rank 2"
}

/** A cycle names something that is not a vertex of this topology; a null [actualRank] means missing. */
data class FaceCycleWrongElement(val face: ElementId, val element: ElementId, val actualRank: Int?) : ValidationIssue {
    override val message = when (actualRank) {
        null -> "face ${face.value} cycle references missing element ${element.value}"
        else -> "face ${face.value} cycle references element ${element.value} of rank $actualRank; expected a vertex"
    }
}

/** A cycle visits the same vertex twice — a pinched or doubled-back boundary, not a simple polygon. */
data class DuplicateFaceCycleVertex(val face: ElementId, val vertex: ElementId) : ValidationIssue {
    override val message = "face ${face.value} cycle repeats vertex ${vertex.value}"
}

/**
 * Two of a face's boundary rings meet at a vertex, or one inner ring visits a vertex twice. A hole
 * that touches its outer ring is not a hole: the face is pinched there and its interior is not a
 * disk with a disk removed. Split it into two faces instead.
 */
data class FaceRingsShareVertex(val face: ElementId, val vertex: ElementId) : ValidationIssue {
    override val message = "face ${face.value} boundary rings repeat vertex ${vertex.value}"
}

/** An inner boundary ring with fewer than three vertices; [hole] indexes [PolytopeTopology.faceHoles]. */
data class FaceHoleTooSmall(val face: ElementId, val hole: Int, val vertexCount: Int) : ValidationIssue {
    override val message = "face ${face.value} hole $hole has $vertexCount vertices; expected at least 3"
}

/** Inner rings were recorded for a face that has no outer boundary to subtract them from. */
data class FaceHolesWithoutOuterBoundary(val face: ElementId, val holeCount: Int) : ValidationIssue {
    override val message =
        "face ${face.value} has $holeCount inner boundary ring(s) but no outer cycle"
}

/** The ordered boundary and the recorded edge incidences disagree about which vertices are on the face. */
data class FaceCycleVertexNotIncident(val face: ElementId, val vertex: ElementId) : ValidationIssue {
    override val message = "face ${face.value} cycle vertex ${vertex.value} is absent from the face's edge incidences"
}

/** Two consecutive cycle vertices with no edge of this face joining them; usually a cycle out of order. */
data class FaceCycleEdgeMismatch(val face: ElementId, val a: ElementId, val b: ElementId) : ValidationIssue {
    override val message =
        "face ${face.value} boundary step ${a.value} -> ${b.value} has no matching edge incident to that face"
}

/** The face claims an edge its own ordered boundary never traverses — an extra or stale incidence. */
data class FaceIncidenceAbsentFromCycle(val face: ElementId, val edge: ElementId) : ValidationIssue {
    override val message = "face ${face.value} incidence edge ${edge.value} does not occur in its ordered cycle"
}

/**
 * The face's edges do not form the closed rings — every vertex of degree two, one component per
 * recorded ring — that its boundary claims: two separate polygons recorded as a single face, a
 * figure-of-eight, a boundary with a gap in it, or a hole ring whose edges are not there.
 *
 * [expectedRings] is `1 + holes`, so it is 1 for every face that has no hole and the message reads
 * as it always did.
 */
data class DisconnectedFaceBoundary(val face: ElementId, val expectedRings: Int = 1) : ValidationIssue {
    override val message = when (expectedRings) {
        1 -> "face ${face.value} incident edges do not form one connected degree-2 boundary"
        else -> "face ${face.value} incident edges do not form $expectedRings connected degree-2 boundary rings"
    }
}

/**
 * An edge with other than exactly two faces. Produced only under `validate(checkManifold = true)`;
 * every edge of a 4-polytope reports this, which is the point of the flag being opt-in.
 */
data class NonManifoldEdge(val edge: ElementId, val faceCount: Int) : ValidationIssue {
    override val message = "edge ${edge.value} belongs to $faceCount faces; a closed manifold surface requires exactly 2"
}

/**
 * An immutable report, not a control-flow exception: everything one [PolytopeTopology.validate] pass
 * found, in the order it found it.
 *
 * [PolytopeTopology.validate] deduplicates before constructing this, so a fault two checks both
 * notice is listed once. The list is copied on the way in, and empty is the only valid state.
 */
class ValidationResult(issues: List<ValidationIssue>) {
    val issues: List<ValidationIssue> = issues.toList()
    val isValid: Boolean get() = issues.isEmpty()

    /**
     * Throw [TopologyValidationException] carrying every issue, or return without complaint. The bridge from
     * the report style back to ordinary Kotlin failure, used by
     * [PolytopeTopologyBuilder.buildValidated] and by each Field adapter before it assumes a
     * well-formed input.
     */
    fun throwIfInvalid() {
        if (!isValid) throw TopologyValidationException(this)
    }
}

/**
 * The report turned into a throw. Its message is every [ValidationIssue.message] as a bulleted list,
 * and [result] is kept, so a caller that catches this can branch on the issue classes instead of
 * parsing that text.
 */
class TopologyValidationException(val result: ValidationResult) : IllegalArgumentException(
    result.issues.joinToString(prefix = "invalid polytope topology:\n", separator = "\n") { "- ${it.message}" }
)

private data class Incidence(val lower: ElementId, val higher: ElementId)

/**
 * One rank-2 face's complete oriented boundary: an outer cycle and the inner cycles cut out of it.
 *
 * Every array is raw [ElementId] values in traversal order, and every accessor returns a fresh copy —
 * this is a value, and a caller that mutates what it is handed changes nothing here.
 *
 * By convention [outer] runs counter-clockwise about the face normal and each ring of [holes] runs
 * clockwise. The topology has no coordinates and so cannot enforce that; see the note on
 * [PolytopeTopology] for why nothing downstream depends on it either.
 *
 * A face with no holes is the ordinary case and stays cheap: [holes] is empty, [rings] is one ring,
 * and [PolytopeTopology.faceCycle] returns exactly what it always returned.
 */
class FaceBoundary internal constructor(outer: IntArray, holes: List<IntArray>) {
    private val storedOuter = outer.copyOf()
    private val storedHoles = holes.map { it.copyOf() }

    /** The outward boundary, counter-clockwise about the face normal by convention. */
    val outer: IntArray get() = storedOuter.copyOf()

    /** The inner boundaries, in the order they were recorded; empty for an ordinary face. */
    val holes: List<IntArray> get() = storedHoles.map { it.copyOf() }

    /** How many inner rings this face has. Zero for every face of every seed in this package. */
    val holeCount: Int get() = storedHoles.size

    /** [outer] first, then each ring of [holes] — the order a tessellator wants them in. */
    val rings: List<IntArray> get() = buildList {
        add(storedOuter.copyOf())
        storedHoles.forEach { add(it.copyOf()) }
    }

    override fun toString(): String =
        "FaceBoundary(outer=${storedOuter.size} vertices, holes=${storedHoles.map { it.size }})"
}

/**
 * Immutable, rank-aware incidence topology.
 *
 * Only incidences between adjacent ranks are represented. For an ordinary polyhedron rank 3 is
 * the single whole-polyhedron element. Ordered rank-2 boundaries are stored explicitly as vertex
 * cycles because unoriented incidence alone does not choose a polygon traversal.
 *
 * Public array accessors return copies; callers cannot mutate the stored incidence or cycles.
 *
 * The constructor is internal: make one with [PolytopeTopologyBuilder], or take one from a seed such
 * as [cube] or from a Conway expression. Construction is also the only moment anything is derived —
 * the adjacent-rank up and down maps are computed once here, and an incidence whose ranks are *not*
 * adjacent is left out of them rather than repaired. That is why [validate], and not
 * construction, is where you find out about it.
 */
class PolytopeTopology internal constructor(
    /** The top rank, whose elements are the whole polytope; the proper ranks are `0 until rank`. */
    val rank: Int,
    elementsByRank: List<List<Element>>,
    incidences: List<Pair<ElementId, ElementId>>,
    faceCycles: Map<ElementId, IntArray>,
    faceHoles: Map<ElementId, List<IntArray>> = emptyMap(),
) {
    /**
     * Every element, indexed by rank: an outer list of length `rank + 1`, each inner list in the
     * order the builder allocated them. Unmodifiable at both levels, and the ordering is the one
     * every parallel array in this package — realization coordinates included — is aligned to.
     */
    val elementsByRank: List<List<Element>> = java.util.Collections.unmodifiableList(
        elementsByRank.map { java.util.Collections.unmodifiableList(it.toList()) },
    )

    private val elementById = this.elementsByRank.flatten().associateBy { it.id }
    private val rawIncidences = incidences.map { Incidence(it.first, it.second) }
    private val storedFaceCycles = faceCycles.mapValues { (_, cycle) -> cycle.copyOf() }
    private val storedFaceHoles = faceHoles
        .filterValues { it.isNotEmpty() }
        .mapValues { (_, rings) -> rings.map { it.copyOf() } }
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

    /**
     * The elements at one rank, or an empty list for a rank outside `0..rank`. Total, so a sweep
     * over ranks or a speculative query about a rank this object may not have needs no bounds
     * check — and an empty result never distinguishes "no such rank" from "nothing there".
     */
    fun elements(rank: Int): List<Element> = elementsByRank.getOrElse(rank) { emptyList() }

    /** The rank-2 elements, `elements(2)`: what [faceCycle], `faceNormal` and `faceCentroids` are asked about. */
    val faces: List<Element> get() = elements(2)

    /**
     * Raw vertex id value → its index in `elements(0)`, which is also its index into
     * `Realization3.vertexCoordinates`. Keyed by the raw value because that is what [faceCycle] hands
     * back, so `faceCycle(f).map { coordinates[vertexMap.getValue(it)] }` is a face's realized ring.
     */
    val vertexMap: Map<Int, Int> by lazy {
        elements(0).withIndex().associate { (index, vertex) -> vertex.id.value to index }
    }

    /** The element with this id, or null if it belongs to some other topology. The way to learn an id's rank. */
    fun element(id: ElementId): Element? = elementById[id]

    /** IDs incident one rank above [element], in stable construction order. */
    fun incidentUp(element: ElementId): IntArray = up[element]?.copyOf() ?: intArrayOf()

    /** IDs incident one rank below [element], in stable construction order. */
    fun incidentDown(element: ElementId): IntArray = down[element]?.copyOf() ?: intArrayOf()

    /**
     * The explicitly stored **outer** ordered vertex boundary of a rank-2 face, as a fresh array of
     * raw [ElementId] values in traversal order.
     *
     * This is the outline, holes or no holes — which is what every caller of it in this package
     * means, and why adding holes did not have to change any of them. [faceHoles] is the other half;
     * [faceBoundary] is both at once.
     *
     * Throws when the element has no cycle — which a legally built, merely un-validated topology can
     * be, and which [dual] output routinely is where a dual boundary was not one ring. Use
     * [faceCycleOrNull] whenever you have not validated first.
     */
    fun faceCycle(face: ElementId): IntArray = storedFaceCycles[face]?.copyOf()
        ?: throw IllegalArgumentException("element ${face.value} has no ordered face cycle")

    /** [faceCycle] without the throw: null for any element that has no stored boundary. */
    fun faceCycleOrNull(face: ElementId): IntArray? = storedFaceCycles[face]?.copyOf()

    /**
     * The inner boundary rings cut out of [face], in the order they were recorded, or an empty list.
     *
     * Total by design and cheap for the ordinary case: a face with no holes stores nothing and this
     * allocates nothing.
     */
    fun faceHoles(face: ElementId): List<IntArray> =
        storedFaceHoles[face]?.map { it.copyOf() } ?: emptyList()

    /** Whether [face] has any inner boundary ring. The one predicate the render path branches on. */
    fun faceHasHoles(face: ElementId): Boolean = storedFaceHoles.containsKey(face)

    /**
     * Every ring of [face]'s boundary, outer first — the form a tessellator wants. One entry for an
     * ordinary face, so a caller that wants to handle both cases uniformly can.
     */
    fun faceRings(face: ElementId): List<IntArray> = buildList {
        add(faceCycle(face))
        storedFaceHoles[face]?.forEach { add(it.copyOf()) }
    }

    /** [face]'s complete oriented boundary as one value. Throws exactly when [faceCycle] would. */
    fun faceBoundary(face: ElementId): FaceBoundary =
        FaceBoundary(faceCycle(face), storedFaceHoles[face].orEmpty())

    /** [faceBoundary] without the throw: null for any element that has no stored outer cycle. */
    fun faceBoundaryOrNull(face: ElementId): FaceBoundary? =
        faceCycleOrNull(face)?.let { FaceBoundary(it, storedFaceHoles[face].orEmpty()) }

    /**
     * All vertices of [face]'s **outer** boundary, in order. Throws if the cycle names an id this
     * topology does not hold — a state [validate] would have reported as [FaceCycleWrongElement].
     * Hole vertices are not included; ask [faceHoles] for those.
     */
    fun verticesOfFace(face: ElementId): List<Element> = faceCycle(face).map { id ->
        elementById[ElementId(id)] ?: throw IllegalStateException("face ${face.value} references missing vertex $id")
    }

    /** Inner rings summed over every face — zero for everything this package constructs by default. */
    fun totalFaceHoleCount(): Int = storedFaceHoles.values.sumOf { it.size }

    /**
     * Alternating count of all proper ranks; the whole-polytope rank is excluded. So it
     * is 2 for a closed polyhedral surface and 0 for a convex 4-polytope, and either number is a
     * cheap check that a transform did not lose or duplicate elements.
     *
     * This counts *elements*, and it kept that meaning when holes arrived: it does not know that a
     * holed face is not a disk. [cellularEulerCharacteristic] is the number that does.
     */
    fun eulerCharacteristic(): Int = (0 until rank).sumOf { r ->
        if (r % 2 == 0) elements(r).size else -elements(r).size
    }

    /**
     * [eulerCharacteristic] corrected for holes: a face with `h` inner rings is a disk with `h` disks
     * removed and contributes `1 - h`, not 1.
     *
     * Identical to [eulerCharacteristic] whenever nothing has holes, which is every seed and every
     * Conway output. For a cube with one square window it is 1, not 2 — the correct χ of a sphere with
     * a disk removed.
     */
    fun cellularEulerCharacteristic(): Int = eulerCharacteristic() - totalFaceHoleCount()

    /**
     * Validate basic incidence and explicit polygon boundaries. Set [checkManifold] to additionally
     * require every edge to have exactly two incident faces. Non-manifold structures remain legal
     * at the core level.
     *
     * Nothing is cached: each call re-walks every incidence and every face, deduplicating the issues
     * at the end. That is fine once per construction and expensive in a per-frame loop — and note
     * that a topology summary validates twice, once plainly and once for the manifold verdict.
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
        for (face in storedFaceCycles.keys + storedFaceHoles.keys) {
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
        val holes = storedFaceHoles[face].orEmpty()
        if (cycle == null) {
            issues += MissingFaceCycle(face)
            if (holes.isNotEmpty()) issues += FaceHolesWithoutOuterBoundary(face, holes.size)
            if (faceEdges.size < 3) issues += FaceTooSmall(face, faceEdges.size, 0)
            return
        }

        val cycleIds = cycle.map(::ElementId)
        if (cycleIds.size < 3 || faceEdges.size < 3) issues += FaceTooSmall(face, faceEdges.size, cycleIds.size)
        holes.forEachIndexed { at, ring -> if (ring.size < 3) issues += FaceHoleTooSmall(face, at, ring.size) }

        // The outer ring first, then each hole. Everything below is per-ring, and for a face with no
        // holes `rings` is one entry and the walk is exactly the one that was here before.
        val rings = buildList {
            add(cycleIds)
            holes.forEach { add(it.map(::ElementId)) }
        }

        val seenVertices = mutableSetOf<ElementId>()
        rings.forEachIndexed { ringIndex, ring ->
            for (vertex in ring) {
                val element = elementById[vertex]
                if (element?.rank != 0) issues += FaceCycleWrongElement(face, vertex, element?.rank)
                if (!seenVertices.add(vertex)) {
                    if (ringIndex == 0) issues += DuplicateFaceCycleVertex(face, vertex)
                    else issues += FaceRingsShareVertex(face, vertex)
                }
            }
        }

        val endpointPairs = mutableMapOf<Set<ElementId>, MutableList<ElementId>>()
        val incidentVertices = mutableSetOf<ElementId>()
        for (edge in faceEdges) {
            val endpoints = (down[edge] ?: intArrayOf()).distinct().map(::ElementId)
            incidentVertices += endpoints
            if (endpoints.size == 2) endpointPairs.getOrPut(endpoints.toSet()) { mutableListOf() }.add(edge)
        }
        for (ring in rings) for (vertex in ring) {
            if (elementById[vertex]?.rank == 0 && vertex !in incidentVertices) {
                issues += FaceCycleVertexNotIncident(face, vertex)
            }
        }

        val usedEdges = mutableSetOf<ElementId>()
        for (ring in rings) if (ring.size >= 2) for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val candidates = endpointPairs[setOf(a, b)].orEmpty()
            if (candidates.isEmpty()) issues += FaceCycleEdgeMismatch(face, a, b)
            else usedEdges += candidates.first()
        }
        for (edge in faceEdges) if (edge !in usedEdges) issues += FaceIncidenceAbsentFromCycle(face, edge)

        if (!formsBoundaryRings(faceEdges, rings.size)) issues += DisconnectedFaceBoundary(face, rings.size)
    }

    /**
     * Every vertex of degree two, and exactly [expected] connected components — one per recorded
     * boundary ring. With `expected = 1` this is the single-ring test it grew out of.
     */
    private fun formsBoundaryRings(edges: List<ElementId>, expected: Int): Boolean {
        if (expected < 1 || edges.size < 3 * expected) return false
        val neighbors = mutableMapOf<ElementId, MutableSet<ElementId>>()
        for (edge in edges) {
            val endpoints = (down[edge] ?: intArrayOf()).distinct().map(::ElementId)
            if (endpoints.size != 2) return false
            neighbors.getOrPut(endpoints[0]) { mutableSetOf() }.add(endpoints[1])
            neighbors.getOrPut(endpoints[1]) { mutableSetOf() }.add(endpoints[0])
        }
        if (neighbors.isEmpty() || neighbors.values.any { it.size != 2 }) return false
        val reached = mutableSetOf<ElementId>()
        var components = 0
        for (seed in neighbors.keys) {
            if (seed in reached) continue
            components++
            if (components > expected) return false
            val pending = ArrayDeque<ElementId>()
            pending += seed
            while (pending.isNotEmpty()) {
                val at = pending.removeFirst()
                if (reached.add(at)) neighbors[at].orEmpty().forEach { pending += it }
            }
        }
        return components == expected
    }

    /**
     * Abstract dual. Proper ranks are reversed while the whole-polytope rank remains the whole.
     * For rank 3 this is exactly vertices <-> faces and edges <-> edges.
     *
     * Combinatorics only — there are no coordinates here to dualize, so `PolyhedralObject.dual` is
     * the call that also applies a realization policy. Boundary cycles for the dual's polygons are
     * *recovered* from incidence, and only where a boundary is a single ring; anywhere it is not, the
     * dual face is left without a cycle and [validate] will say so even though the primal passed.
     * Requires rank at least 1.
     *
     * A face with holes is refused outright. The dual of a face is a vertex, and a hole in that face
     * is a boundary of the surface rather than a cell of it — there is nothing for it to become, and
     * dropping it silently would make `dd` claim to be an involution on an object it had changed.
     */
    fun dual(): PolytopeTopology {
        require(rank >= 1) { "rank-0 topology has no represented proper-rank dual" }
        val holed = storedFaceHoles.keys.firstOrNull()
        if (holed != null) throw IllegalArgumentException(
            "face ${holed.value} has ${storedFaceHoles.getValue(holed).size} hole(s); " +
                "dualization is defined here for closed manifolds only",
        )
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

/**
 * Mutable assembly surface whose [build] result is immutable — and the only way to make a
 * [PolytopeTopology].
 *
 * It records; it does not check. The one eager check is [addElement]'s rank bound. Missing
 * references, non-adjacent ranks, duplicate incidences and malformed cycles are all
 * [ValidationIssue] values from [PolytopeTopology.validate] afterwards, which is what lets a caller
 * assemble in whatever order is convenient — every element first, then every incidence, then the
 * cycles — rather than in an order the checks would allow.
 *
 * [build] does not consume the builder: you may build, look at the result, add more, and build
 * again. [PolytopeTopology.dual] depends on exactly that, building once to find the dual's face
 * boundaries and again to keep them.
 */
class PolytopeTopologyBuilder(
    /** The top rank of the topology being assembled; [addElement] accepts `0..rank` inclusive. */
    val rank: Int,
) {
    private val elements = MutableList(rank + 1) { mutableListOf<Element>() }
    private val incidences = mutableListOf<Pair<ElementId, ElementId>>()
    private val faceCycles = mutableMapOf<ElementId, IntArray>()
    private val faceHoles = mutableMapOf<ElementId, MutableList<IntArray>>()
    private var nextId = 0

    init {
        require(rank >= 0) { "rank must be non-negative, got $rank" }
    }

    /**
     * Allocate a fresh element at one rank and return its new id. Ids come from a single counter
     * shared by all ranks, so they are unique but carry no rank information.
     *
     * Throws immediately for a rank outside `0..rank` — the builder's only eager check, because an
     * out-of-range rank has nowhere to be stored and so could not be reported later.
     */
    fun addElement(rank: Int): ElementId {
        require(rank in 0..this.rank) { "element rank $rank is outside topology ranks 0..${this.rank}" }
        val id = ElementId(nextId++)
        elements[rank] += Element(id, rank)
        return id
    }

    /**
     * Record an incidence. Reference and rank errors are reported by [validate] after [build],
     * so a tool or a test can inspect all malformed input at once.
     */
    fun addIncidence(lower: ElementId, higher: ElementId): PolytopeTopologyBuilder = apply {
        incidences += lower to higher
    }

    /**
     * Give a rank-2 face the ordered vertex boundary that unoriented incidence cannot supply. The
     * array is copied, and a face has at most one cycle, so a second call replaces the first.
     *
     * This traversal order is what polygons are drawn, wound and triangulated in. Whether the ids are
     * really vertices, really incident to the face, or really form a ring is
     * [PolytopeTopology.validate]'s business, not this call's.
     */
    fun setFaceCycle(face: ElementId, vertices: IntArray): PolytopeTopologyBuilder = apply {
        faceCycles[face] = vertices.copyOf()
    }

    /** The same, spelt in [ElementId]s — the form construction code normally has to hand. */
    fun setFaceCycle(face: ElementId, vertices: List<ElementId>): PolytopeTopologyBuilder =
        setFaceCycle(face, vertices.map { it.value }.toIntArray())

    /**
     * Cut one more hole out of a rank-2 face: an ordered inner boundary, clockwise about the face
     * normal by convention, wound opposite to the cycle [setFaceCycle] recorded.
     *
     * Rings accumulate in call order, and the array is copied. The hole's edges must be recorded as
     * incidences of this same face just as the outer ring's are — a hole is part of the face's
     * boundary, not a separate thing near it — and [PolytopeTopology.validate] is where you find out
     * if they are not.
     */
    fun addFaceHole(face: ElementId, vertices: IntArray): PolytopeTopologyBuilder = apply {
        faceHoles.getOrPut(face) { mutableListOf() }.add(vertices.copyOf())
    }

    /** The same, spelt in [ElementId]s. */
    fun addFaceHole(face: ElementId, vertices: List<ElementId>): PolytopeTopologyBuilder =
        addFaceHole(face, vertices.map { it.value }.toIntArray())

    /** Replace every inner ring of [face] at once; an empty list removes them all. */
    fun setFaceHoles(face: ElementId, rings: List<IntArray>): PolytopeTopologyBuilder = apply {
        if (rings.isEmpty()) faceHoles.remove(face)
        else faceHoles[face] = rings.mapTo(mutableListOf()) { it.copyOf() }
    }

    /**
     * Materialise an immutable [PolytopeTopology] from whatever has been recorded so far. Always
     * succeeds: a malformed topology is a buildable one, and inspecting it is how you learn what is
     * wrong. The builder stays usable afterwards.
     */
    fun build(): PolytopeTopology = PolytopeTopology(rank, elements, incidences, faceCycles, faceHoles)

    /**
     * [build] followed by [ValidationResult.throwIfInvalid], so a bad assembly fails here rather than
     * at the first adapter that assumed a well-formed input. Set [checkManifold] to also demand
     * exactly two faces per edge — true of every rank-3 seed in this package, and of no 4-polytope.
     */
    fun buildValidated(checkManifold: Boolean = false): PolytopeTopology = build().also {
        it.validate(checkManifold).throwIfInvalid()
    }
}

/**
 * Small rank-3 combinatorial isomorphism helper. It tries vertex bijections, then compares edge
 * endpoint pairs and face cycles up to rotation/reversal. Its scope is rank 3, and it is not a
 * general incidence-poset isomorphism solver.
 *
 * Because cycles are compared up to *reversal* the test is blind to orientation: a solid and its
 * mirror image compare equal. It is not blind to holes — a face's inner rings are part of what is
 * compared, so a windowed cube is not isomorphic to a cube. Anything not rank 3 on both sides, and
 * any pair whose element counts differ, answers `false` rather than complaining — but a face with no
 * stored cycle throws, through [PolytopeTopology.faceCycle], so validate before asking.
 *
 * The search is pruned by a per-vertex signature — edge degree, plus the sorted sizes of the faces
 * around that vertex — which is enough to make the Platonic and Conway solids it was written for
 * quick. It remains backtracking over bijections, and a large, highly symmetric pair is where that
 * shows.
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

    // A face is its outer ring plus its holes, so a windowed face can never compare equal to a solid
    // one. Holes are sorted because their recorded order is a construction detail, not structure.
    fun faceMultiset(topology: PolytopeTopology, map: Map<ElementId, ElementId>?): Map<List<List<Int>>, Int> =
        topology.elements(2).map { face ->
            val mapped = { ring: IntArray -> canonicalCycle(ring.map { id -> map?.get(ElementId(id))?.value ?: id }) }
            listOf(mapped(topology.faceCycle(face.id))) +
                topology.faceHoles(face.id).map(mapped).sortedWith(compareBy({ it.size }, { it.joinToString(",") }))
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
