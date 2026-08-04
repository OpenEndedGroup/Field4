@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

import afield.stdlib.LeastSquaresOptions
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Symmetry as a combinatorial fact, and as something a solver can be asked for.
 *
 * The library never computes a symmetry *group*. It computes a partition — [combinatorialOrbits] —
 * of the elements into classes that no automorphism of the topology can tell apart, by iterated
 * incidence-colour refinement over the ranked incidence structure. Coordinates play no part, so the
 * classes of a lumpy embedding and of its canonical form are identical: they are a property of the
 * combinatorics.
 *
 * Two things are built on that. [SymmetryOrbitConstraint] turns the partition into residuals — same
 * edge length within an edge class, same vertex radius within a vertex class, same face-plane distance
 * within a face class — so a realization run can be *asked* to look symmetric, and
 * [RelaxationPlan.Companion.symmetricCanonical] is the canonical conditions plus that family.
 * [symmetryDeviation] is the readout in the other direction: how equal the geometry actually is inside
 * each class, as a relative spread.
 *
 * ## The caveat that runs through all of it
 *
 * Colour refinement is not a complete isomorphism invariant, so a class is a *union* of true
 * automorphism orbits and may be strictly coarser than them. Constraining a class is therefore at
 * least as strong as constraining an orbit — and on a topology where it is strictly stronger, the
 * constraint asks for an equality no embedding can deliver. Nothing here detects that case; the
 * `symmetry-orbit` family simply reports a real residual and the reader is expected to look. The
 * long-term fix named in `dev_docs/POLYTOPE_ALGEBRA.md` is Wythoff construction, which would supply
 * actual group orbits.
 *
 * These are all soft constraints. Nothing in this file projects onto a symmetry group's fixed
 * subspace, so nothing here can manufacture symmetry the topology does not have.
 */

/**
 * A partition of the elements into classes that combinatorially indistinguishable elements share.
 *
 * **What this is.** Iterated incidence-colour refinement (the one-dimensional Weisfeiler-Leman
 * relabelling) over the ranked incidence structure: an element's colour is refined by the multiset
 * of colours immediately above and below it, until the partition stops changing.
 *
 * **What it guarantees.** Any automorphism of the topology preserves colours, so every true
 * automorphism orbit lies inside exactly one class: the classes are *unions* of orbits. Constraining
 * a class to share a measurement is therefore at least as strong as constraining an orbit to. For
 * the Platonic seeds and their Conway images the two coincide, which is why this is a useful
 * symmetry specification rather than an approximation to one.
 *
 * **What it does not guarantee.** Colour refinement is not a complete isomorphism invariant. On a
 * topology where a class is strictly coarser than the orbits it contains, [SymmetryOrbitConstraint]
 * asks for an equality the object cannot satisfy; the solver will then report a real residual for
 * the `symmetry-orbit` family rather than silently distorting the result. Read the report.
 *
 * Classes are ordered by their smallest [ElementId] and each class is sorted, so the value is
 * deterministic and prints usefully.
 */
data class CombinatorialOrbits(
    /** Rank-0 classes, each sorted, the classes ordered by their smallest member. */
    val vertexClasses: List<List<ElementId>>,
    val edgeClasses: List<List<ElementId>>,
    /** Rank-2 classes. Ranks above 2 are not refined and are absent from this value entirely. */
    val faceClasses: List<List<ElementId>>,
) {
    val vertexClassCount: Int get() = vertexClasses.size
    val edgeClassCount: Int get() = edgeClasses.size
    val faceClassCount: Int get() = faceClasses.size

    /** True when every rank has exactly one class: the hallmark of a fully transitive solid. */
    val isFullyTransitive: Boolean
        get() = vertexClassCount == 1 && edgeClassCount == 1 && faceClassCount == 1
}

/** Compact inspection value; the class *sizes* are the interesting fact at a live-coding prompt. */
data class CombinatorialOrbitsSummary(
    /** Sizes only, in class order. Which elements are in which class is not recoverable from here. */
    val vertexClassSizes: List<Int>,
    val edgeClassSizes: List<Int>,
    val faceClassSizes: List<Int>,
    val fullyTransitive: Boolean,
)

/**
 * Class sizes per rank — a cube reports `[8]`, `[12]`, `[6]`, one class each, because it is fully
 * transitive.
 *
 * Reading this line is the fastest way to see what a Conway expression produced: a single size at
 * every rank means a fully transitive solid, and two vertex classes mean the operator split the
 * vertices into kinds. Sizes only, so two genuinely different partitions of the same shape print
 * identically.
 */
fun CombinatorialOrbits.summary() = CombinatorialOrbitsSummary(
    vertexClasses.map { it.size },
    edgeClasses.map { it.size },
    faceClasses.map { it.size },
    isFullyTransitive,
)

/**
 * Refine the elements of ranks 0..2 into combinatorial classes. See [CombinatorialOrbits] for
 * exactly what the classes do and do not promise.
 *
 * Ranks above 2 take no part: on a rank-3 object the whole-body element is not classified, and its
 * incidence contributes the same unknown colour to every face, which is harmless because it is
 * uniform. The refinement stops the round the partition stops getting finer, which on the shapes this
 * vocabulary makes is a handful of rounds and not the `elements + 2` worst case — but the answer
 * depends only on the topology, so cache it rather than calling it per frame.
 */
fun PolytopeTopology.combinatorialOrbits(): CombinatorialOrbits = refineCombinatorialOrbits().orbits

/**
 * [combinatorialOrbits] together with the number of refinement rounds it actually ran, so a test can
 * assert the early stop really fires. Pass `stopWhenStable = false` to spend the whole `elements + 2`
 * budget, which is only useful for showing that stopping early costs nothing.
 */
internal data class CombinatorialOrbitsRefinement(val orbits: CombinatorialOrbits, val rounds: Int)

internal fun PolytopeTopology.refineCombinatorialOrbits(
    stopWhenStable: Boolean = true,
): CombinatorialOrbitsRefinement {
    val ranks = 0..minOf(2, rank)
    val elements = ranks.flatMap { elements(it) }
    // Seed on rank and local degree: two elements of different rank or valence can never be
    // exchanged by an automorphism, so this only saves refinement rounds, it does not change the
    // fixed point.
    var colour = elements.associate { element ->
        element.id to listOf(element.rank, incidentDown(element.id).size, incidentUp(element.id).size).toString()
    }

    var classes = colour.values.distinct().size
    var rounds = 0
    // Each round's colour string carries the previous round's colour as its prefix, so the partition
    // can only get finer. A round that does not add a class has therefore reached the fixed point and
    // every later round would just renumber it: stop.
    for (round in 1..elements.size + 2) {
        val next = elements.associate { element ->
            val below = incidentDown(element.id).map { colour[ElementId(it)] ?: "?" }.sorted()
            val above = incidentUp(element.id).map { colour[ElementId(it)] ?: "?" }.sorted()
            element.id to "${colour.getValue(element.id)}|$below|$above"
        }
        // Renumber to keep the colour strings from growing exponentially with the round count.
        val canonical = next.values.distinct().sorted().withIndex().associate { (at, value) -> value to at.toString() }
        val renumbered = next.mapValues { (_, value) -> canonical.getValue(value) }
        val count = renumbered.values.distinct().size
        colour = renumbered
        rounds = round
        if (count == classes && stopWhenStable) break
        classes = count
    }

    fun classesOf(rank: Int): List<List<ElementId>> = elements(rank)
        .groupBy { colour.getValue(it.id) }
        .values
        .map { group -> group.map { it.id }.sortedBy { it.value } }
        .sortedBy { it.first().value }

    return CombinatorialOrbitsRefinement(CombinatorialOrbits(classesOf(0), classesOf(1), classesOf(2)), rounds)
}

/**
 * This object's combinatorial classes, which are a fact about its topology alone.
 *
 * The coordinates are not consulted, so a badly distorted embedding and its canonical form report the
 * same classes. That is the whole point: the classes say what the object *should* look like, and
 * [symmetryDeviation] says how far this embedding is from it.
 */
fun PolyhedralObject.combinatorialOrbits(): CombinatorialOrbits = topology.combinatorialOrbits()

/**
 * Require combinatorially equivalent elements to be geometrically equal: same edge length within an
 * edge class, same distance from the centroid within a vertex class, same face-plane distance from
 * the centroid within a face class.
 *
 * This is the explicit "symmetry-orbit equality" family. It is a *soft* constraint like every other
 * member of the vocabulary — it does not project onto a symmetry group's fixed subspace, so it
 * cannot manufacture symmetry the topology does not have, and its report says how much symmetry the
 * final embedding actually achieved.
 *
 * Build one with [PolyhedralObject.symmetryOrbitConstraint] rather than by hand; the class indices
 * must be in `topology.elements(rank)` order.
 *
 * `vertexCount + edgeCount + faceCount` residuals, each a deviation from its own class's mean and so a
 * length in coordinate units; a class with one member contributes exactly zero. The face measurement
 * is the signed distance from the *vertex* centroid to the face's plane, not to the tangency centre.
 *
 * The class lists are checked against the topology inside the residual evaluation rather than at
 * construction, so a constraint built for a different object constructs happily and fails on the
 * solver's first residual call. It also has no Ceres equivalent — a plan containing this family cannot
 * be handed to [PolyhedralObject.relaxWithCeres].
 */
data class SymmetryOrbitConstraint(
    override val weight: Double = 1.0,
    /** One non-negative class index per vertex, in `topology.elements(0)` order. Gaps are harmless. */
    val vertexClass: List<Int>,
    val edgeClass: List<Int>,
    val faceClass: List<Int>,
) : RealizationConstraint {
    override val name get() = "symmetry-orbit"

    init {
        require(weight.isFinite() && weight >= 0.0) { "symmetry-orbit weight must be finite and non-negative" }
    }

    override fun residualCount(problem: RealizationProblem) =
        problem.vertexCount + problem.edgeCount + problem.faceCount

    override fun residuals(problem: RealizationProblem, x: DoubleArray, into: DoubleArray, offset: Int) {
        require(vertexClass.size == problem.vertexCount && edgeClass.size == problem.edgeCount) {
            "symmetry-orbit classes were built for a different topology"
        }
        require(faceClass.size == problem.faceCount) {
            "symmetry-orbit classes were built for a different topology"
        }
        val centre = centroidOf(x)

        val radii = DoubleArray(problem.vertexCount) { distanceToPoint(x, it, centre) }
        writeClassDeviation(radii, vertexClass, into, offset)

        val lengths = DoubleArray(problem.edgeCount) { distanceBetween(x, problem.edgeA(it), problem.edgeB(it)) }
        writeClassDeviation(lengths, edgeClass, into, offset + problem.vertexCount)

        val inradii = DoubleArray(problem.faceCount) { face ->
            val plane = facePlane(x, problem.faceCycles[face])
            val scale = max(plane.normalLength, 1e-10)
            ((plane.cx - centre[0]) * plane.nx + (plane.cy - centre[1]) * plane.ny + (plane.cz - centre[2]) * plane.nz) / scale
        }
        writeClassDeviation(inradii, faceClass, into, offset + problem.vertexCount + problem.edgeCount)
    }

    override fun withWeight(weight: Double) = copy(weight = weight)

    /** Deviation of each measurement from its own class mean; a singleton class contributes zero. */
    private fun writeClassDeviation(values: DoubleArray, classes: List<Int>, into: DoubleArray, offset: Int) {
        if (values.isEmpty()) return
        val classCount = (classes.maxOrNull() ?: -1) + 1
        val sums = DoubleArray(classCount)
        val counts = IntArray(classCount)
        for (i in values.indices) {
            sums[classes[i]] += values[i]
            counts[classes[i]]++
        }
        for (i in values.indices) {
            val mean = sums[classes[i]] / counts[classes[i]]
            into[offset + i] = weight * (values[i] - mean)
        }
    }
}

/**
 * The [SymmetryOrbitConstraint] for this object's own combinatorial classes.
 *
 * Runs the colour refinement on every call and bakes the resulting element order into the constraint,
 * so the value is tied to this topology and cannot be reused across a transform.
 */
fun PolyhedralObject.symmetryOrbitConstraint(weight: Double = 1.0): SymmetryOrbitConstraint {
    val orbits = combinatorialOrbits()
    fun indices(rank: Int, classes: List<List<ElementId>>): List<Int> {
        val lookup = mutableMapOf<Int, Int>()
        classes.forEachIndexed { at, group -> group.forEach { lookup[it.value] = at } }
        return topology.elements(rank).map { lookup.getValue(it.id.value) }
    }
    return SymmetryOrbitConstraint(
        weight,
        indices(0, orbits.vertexClasses),
        indices(1, orbits.edgeClasses),
        indices(2, orbits.faceClasses),
    )
}

/**
 * The canonical conditions *plus* combinatorial-orbit equality, for when the answer must look
 * symmetric and not merely satisfy the midsphere conditions.
 *
 * Reach for [PolyhedralObject.canonicalize] first: it is the specialised algorithm and it costs
 * O(iterations × elements). This plan runs a finite-difference Levenberg-Marquardt solve over
 * `3 × vertexCount` parameters, which is the right tool for tens of vertices and the wrong one for
 * hundreds.
 *
 * The plan captures [polyhedron]'s classes at the moment it is built, so it belongs to that topology
 * and no other. Because the symmetry family has no native counterpart, this is also the one stock plan
 * that cannot be handed to [PolyhedralObject.relaxWithCeres].
 */
fun RelaxationPlan.Companion.symmetricCanonical(
    polyhedron: PolyhedralObject,
    symmetryWeight: Double = 2.0,
    planarityWeight: Double = 2.0,
    tangencyWeight: Double = 1.0,
    tangencyCentroidWeight: Double = 4.0,
    radius: Double = 1.0,
    leastSquares: LeastSquaresOptions = LeastSquaresOptions(maxIterations = 128),
): RelaxationPlan = RelaxationPlan(
    listOf(
        FacePlanarityConstraint(planarityWeight),
        EdgeTangencyConstraint(tangencyWeight, radius),
        TangencyCentroidConstraint(tangencyCentroidWeight),
        polyhedron.symmetryOrbitConstraint(symmetryWeight),
    ),
    leastSquares,
    RigidGauge.CANONICAL,
)

/**
 * How equal the geometry actually is inside each combinatorial class. Zero spread everywhere is the
 * numeric statement of "this embedding respects the topology's symmetry".
 */
data class SymmetryDeviation(
    /** Worst `(max - min) / |mean|` of vertex-to-centroid distance within any one vertex class. */
    val vertexRadiusSpread: Double,
    val edgeLengthSpread: Double,
    /** The same, for the signed distance from the vertex centroid to each face's plane. */
    val faceDistanceSpread: Double,
    val orbits: CombinatorialOrbitsSummary,
)

/**
 * Largest within-class relative spread of each measured quantity.
 *
 * Dimensionless, so it reads as a fraction: `1e-8` is a canonical-quality solid, `3e-1` is a lump. It
 * takes the *worst* class at each rank, and skips any class with fewer than two members or a mean
 * within `1e-12` of zero — so `0.0` can mean "perfectly equal" or "there was nothing here to compare",
 * and [SymmetryDeviation.orbits] is what tells the two apart.
 *
 * Requires a realization and a topology that validates, via [RealizationProblem.of].
 */
fun PolyhedralObject.symmetryDeviation(): SymmetryDeviation {
    val problem = RealizationProblem.of(this)
    val x = problem.initialCoordinates
    val constraint = symmetryOrbitConstraint()
    val centre = centroidOf(x)

    fun spread(values: DoubleArray, classes: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val classCount = (classes.maxOrNull() ?: -1) + 1
        var worst = 0.0
        for (group in 0 until classCount) {
            val members = values.indices.filter { classes[it] == group }.map { values[it] }
            if (members.size < 2) continue
            val mean = members.average()
            if (kotlin.math.abs(mean) <= 1e-12) continue
            worst = max(worst, (members.max() - members.min()) / kotlin.math.abs(mean))
        }
        return worst
    }

    val radii = DoubleArray(problem.vertexCount) { distanceToPoint(x, it, centre) }
    val lengths = DoubleArray(problem.edgeCount) { distanceBetween(x, problem.edgeA(it), problem.edgeB(it)) }
    val distances = DoubleArray(problem.faceCount) { face ->
        val plane = facePlane(x, problem.faceCycles[face])
        val scale = max(plane.normalLength, 1e-10)
        ((plane.cx - centre[0]) * plane.nx + (plane.cy - centre[1]) * plane.ny + (plane.cz - centre[2]) * plane.nz) / scale
    }
    return SymmetryDeviation(
        spread(radii, constraint.vertexClass),
        spread(lengths, constraint.edgeClass),
        spread(distances, constraint.faceClass),
        combinatorialOrbits().summary(),
    )
}

/** Root-mean-square of a class's deviation from its own mean; used by tests and reports. */
internal fun rmsDeviation(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}
