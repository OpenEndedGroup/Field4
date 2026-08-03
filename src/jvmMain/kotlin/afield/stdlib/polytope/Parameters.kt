@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

/**
 * Naming a subset of elements, and attaching a number to each one.
 *
 * This is the small algebra that turns a Conway operator from a whole-object rewrite into something
 * applied *there, by that much*. [ElementSelector] says which elements take part;
 * [ElementDoubleParameter] says how much, for each of them independently. The selective `kis`,
 * `truncate` and `gyro` overloads take both, evaluate them once per candidate element, and record
 * what they resolved in a [TransformDeployment] — so a non-uniform operation stays inspectable and
 * replayable after the fact rather than being a number you typed once.
 *
 * ## How a selector is evaluated, and against which rank
 *
 * Both interfaces are single-method predicates over an [ElementSelectionContext]: the object, one
 * element, and that element's lineage when there is one. They are called once per candidate element,
 * in `topology.elements(rank)` order, with no caching and no batching — so keep them cheap and free
 * of side effects.
 *
 * The **operator chooses the rank, not the selector.** `kis(selector, …)` offers the selector only
 * `elements(2)`, `truncate(selector, …)` only `elements(0)`, and `gyro` takes no selector at all and
 * evaluates its parameter over every edge. [ElementSelectors.rank] therefore filters *within* the
 * operator's rank rather than redirecting it: `kis(ElementSelectors.rank(0))` matches nothing and
 * kis-es nothing, legally and silently.
 *
 * ```kotlin
 * // Raise an apex over the pentagons only, by an amount painted onto each face beforehand.
 * val result = solid.kis(ElementSelectors.faceDegree(5), ElementParameters.attribute("spike"))
 * ```
 *
 * ## Lineage is not always there
 *
 * [ElementSelectors.provenanceRole] is the one selector that needs transform history.
 * [PolyhedralObject.selectElements] builds a context without lineage, so on a bare object every
 * provenance test is false — and a *negated* provenance test is therefore vacuously true, which is
 * the standing trap in this file. Ask a [PolytopeTransformResult] when a role matters.
 */

/** Everything a selector or parameter field may inspect without coupling topology to rendering. */
data class ElementSelectionContext(
    val polyhedron: PolyhedralObject,
    val element: Element,
    /** Null on a bare [PolyhedralObject]; a [PolytopeTransformResult] supplies it. */
    val lineage: ElementLineage? = null,
) {
    val topology: PolytopeTopology get() = polyhedron.topology

    /** This element's own attributes, copied on every read; empty — never null — when it has none. */
    val attributes: Map<String, Any?> get() = polyhedron.attributes[element.id]

    /**
     * Local valence, defined per rank: incident edges for a vertex, boundary length for a face, and
     * the sum of the up and down incidences for anything else.
     *
     * Recomputed on every read, and it throws for a rank-2 element with no stored ordered boundary
     * rather than reporting the raw edge incidence count.
     */
    val degree: Int
        get() = when (element.rank) {
            0 -> topology.incidentUp(element.id).size
            2 -> topology.faceCycle(element.id).size
            else -> topology.incidentUp(element.id).size + topology.incidentDown(element.id).size
        }
}

/**
 * A predicate over one element in its context. Pure, called once per candidate, never memoised.
 *
 * Written as a `fun interface` so a lambda is a selector: `ElementSelector { it.degree == 5 }`.
 * [ElementSelectors] supplies the stock ones and they compose with [and], [or] and `!`.
 */
fun interface ElementSelector {
    fun matches(context: ElementSelectionContext): Boolean
}

/** Both predicates, short-circuiting on the left — so put the cheap test first. */
infix fun ElementSelector.and(other: ElementSelector): ElementSelector = ElementSelector {
    matches(it) && other.matches(it)
}

/**
 * Either predicate, short-circuiting on the left.
 *
 * [and] and [or] are infix functions of equal precedence and so associate left to right:
 * `a and b or c` is `(a and b) or c`. Parenthesise when you meant otherwise.
 */
infix fun ElementSelector.or(other: ElementSelector): ElementSelector = ElementSelector {
    matches(it) || other.matches(it)
}

/**
 * Logical negation, so `!selector` reads as it should.
 *
 * Negating a selector that cannot be evaluated in the current context inverts *that*, not what you
 * meant: `!ElementSelectors.provenanceRole("face-apex")` on a bare [PolyhedralObject] negates a
 * predicate that matched nothing and so matches everything.
 */
operator fun ElementSelector.not(): ElementSelector = ElementSelector { !matches(it) }

/**
 * The stock selectors.
 *
 * None of these chooses its own domain — see the file overview: the operator offers one rank and
 * these filter within it. [PolyhedralObject.selectElements] is the exception and walks every rank,
 * the single whole-polytope element at the top included, unless you pass a rank.
 */
object ElementSelectors {
    val all = ElementSelector { true }

    /** Elements of exactly this rank: 0 vertices, 1 edges, 2 faces, `topology.rank` the whole body. */
    fun rank(rank: Int) = ElementSelector { it.element.rank == rank }

    /**
     * Exactly these elements, by identity. [ElementId] values are globally scoped, so no rank test
     * is needed or applied; the set is snapshotted here, not re-read per element.
     */
    fun ids(ids: Collection<ElementId>) = ids.mapTo(linkedSetOf()) { it.value }.let { values ->
        ElementSelector { it.element.id.value in values }
    }

    fun id(id: ElementId) = ids(listOf(id))

    /**
     * Faces with exactly [degree] corners — "the pentagons", in one call. Rejects a degree below 3
     * at construction, and reads the stored ordered boundary, so it throws on a rank-2 element that
     * has none.
     */
    fun faceDegree(degree: Int): ElementSelector {
        require(degree >= 3) { "face degree must be at least 3" }
        return ElementSelector { it.element.rank == 2 && it.topology.faceCycle(it.element.id).size == degree }
    }

    /** Vertices with exactly [degree] incident edges. Rejects a degree below 2 at construction. */
    fun vertexDegree(degree: Int): ElementSelector {
        require(degree >= 2) { "vertex degree must be at least 2" }
        return ElementSelector { it.element.rank == 0 && it.topology.incidentUp(it.element.id).size == degree }
    }

    /**
     * Elements carrying [name] with structurally equal [value].
     *
     * The key must be *present*, so `attributeEquals(name, null)` matches an attribute explicitly set
     * to null and not an element that never had one. Attribute values are Field's ordinary untyped
     * property values, so this is `==` on `Any?` and a `1` will not match a `1.0`.
     */
    fun attributeEquals(name: String, value: Any?): ElementSelector = ElementSelector {
        name in it.attributes && it.attributes[name] == value
    }

    /**
     * Match the most recent transform role, or any ancestor role when [recursive] is true.
     *
     * The roles are the fixed vocabulary the primitives emit — `face-center`, `face-apex`,
     * `original-vertex`, `edge-midpoint`, `directed-edge-point` and so on — which is the only way to
     * say "the apexes kis just raised" without naming indices. Matches nothing where there is no
     * lineage, and nothing on a seed element, whose lineage is [ElementLineage.Source]; see the file
     * overview for why negating it there is a trap.
     */
    fun provenanceRole(role: String, recursive: Boolean = false): ElementSelector = ElementSelector { context ->
        val pending = ArrayDeque<ElementLineage>()
        context.lineage?.let(pending::addLast)
        var found = false
        while (pending.isNotEmpty() && !found) {
            when (val lineage = pending.removeFirst()) {
                is ElementLineage.Source -> Unit
                is ElementLineage.Derived -> {
                    found = lineage.role == role
                    if (!found && recursive) lineage.parents.forEach(pending::addLast)
                }
            }
        }
        found
    }
}

/**
 * A scalar field over elements: one finite [Double] per element, evaluated where the operator asks.
 *
 * The selective transforms evaluate this once per *selected source* element and then validate the
 * result themselves — `kis` demands a positive height, `truncate` and `gyro` a fraction strictly
 * inside `(0, 0.5)` — naming the offending element in the failure. Returning a value out of range is
 * therefore an error at deployment time, not a clamp.
 */
fun interface ElementDoubleParameter {
    fun value(context: ElementSelectionContext): Double
}

/** The stock parameter fields: one number everywhere, a number read off each element, or one of two. */
object ElementParameters {
    /** The same value for every element, checked finite once here rather than per element. */
    fun constant(value: Double): ElementDoubleParameter {
        require(value.isFinite()) { "parameter value must be finite" }
        return ElementDoubleParameter { value }
    }

    /**
     * Read the number off each element's own attributes — how a painted-on scalar field becomes a
     * deployed parameter.
     *
     * Any [Number] is accepted and widened, so an `Int` attribute works. An element missing the
     * attribute takes [default], or fails naming that element when there is no default; a present
     * but non-numeric or non-finite value always fails. The failure is thrown while the operator is
     * resolving its parameters, so a single unpainted face aborts the whole deployment.
     */
    fun attribute(name: String, default: Double? = null): ElementDoubleParameter = ElementDoubleParameter { context ->
        val value = context.attributes[name]
        when (value) {
            is Number -> value.toDouble()
            null -> default ?: throw IllegalArgumentException(
                "element ${context.element.id.value} has no numeric '$name' attribute",
            )
            else -> throw IllegalArgumentException(
                "element ${context.element.id.value} attribute '$name' is not numeric: $value",
            )
        }.also { require(it.isFinite()) { "element ${context.element.id.value} parameter '$name' is not finite" } }
    }

    /**
     * Two-valued field: [selected] where [selector] matches, [otherwise] elsewhere.
     *
     * Useful because selection and amount are separate axes — the operator's own selector decides
     * *whether* an element takes part, and this decides *how much* using a different test.
     */
    fun selected(
        selector: ElementSelector,
        selected: Double,
        otherwise: Double,
    ): ElementDoubleParameter {
        require(selected.isFinite() && otherwise.isFinite()) { "parameter values must be finite" }
        return ElementDoubleParameter { if (selector.matches(it)) selected else otherwise }
    }
}

/**
 * Evaluate [selector] over this object's elements, for inspecting a selection before deploying it.
 *
 * Walks every rank in ascending order — including the single whole-polytope element at the top —
 * unless [rank] narrows it. The contexts carry no lineage, so provenance selectors match nothing
 * here; use the [PolytopeTransformResult] overload when that matters.
 */
fun PolyhedralObject.selectElements(
    selector: ElementSelector,
    rank: Int? = null,
): List<Element> = topology.elementsByRank.asSequence().flatten()
    .filter { rank == null || it.rank == rank }
    .filter { selector.matches(ElementSelectionContext(this, it)) }
    .toList()

/**
 * As the [PolyhedralObject] overload, but each context carries the element's lineage — so this is
 * the receiver on which [ElementSelectors.provenanceRole] can actually match.
 */
fun PolytopeTransformResult.selectElements(
    selector: ElementSelector,
    rank: Int? = null,
): List<Element> = polyhedron.topology.elementsByRank.asSequence().flatten()
    .filter { rank == null || it.rank == rank }
    .filter { selector.matches(ElementSelectionContext(polyhedron, it, lineage(it.id))) }
    .toList()

internal fun PolytopeTransformResult.selectionContext(element: Element): ElementSelectionContext =
    ElementSelectionContext(polyhedron, element, lineage(element.id))
