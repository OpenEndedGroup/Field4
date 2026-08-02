package afield.stdlib.polytope

/** Everything a selector or parameter field may inspect without coupling topology to rendering. */
data class ElementSelectionContext(
    val polyhedron: PolyhedralObject,
    val element: Element,
    val lineage: ElementLineage? = null,
) {
    val topology: PolytopeTopology get() = polyhedron.topology
    val attributes: Map<String, Any?> get() = polyhedron.attributes[element.id]
    val degree: Int
        get() = when (element.rank) {
            0 -> topology.incidentUp(element.id).size
            2 -> topology.faceCycle(element.id).size
            else -> topology.incidentUp(element.id).size + topology.incidentDown(element.id).size
        }
}

fun interface ElementSelector {
    fun matches(context: ElementSelectionContext): Boolean
}

infix fun ElementSelector.and(other: ElementSelector): ElementSelector = ElementSelector {
    matches(it) && other.matches(it)
}

infix fun ElementSelector.or(other: ElementSelector): ElementSelector = ElementSelector {
    matches(it) || other.matches(it)
}

operator fun ElementSelector.not(): ElementSelector = ElementSelector { !matches(it) }

object ElementSelectors {
    val all = ElementSelector { true }

    fun rank(rank: Int) = ElementSelector { it.element.rank == rank }

    fun ids(ids: Collection<ElementId>) = ids.mapTo(linkedSetOf()) { it.value }.let { values ->
        ElementSelector { it.element.id.value in values }
    }

    fun id(id: ElementId) = ids(listOf(id))

    fun faceDegree(degree: Int): ElementSelector {
        require(degree >= 3) { "face degree must be at least 3" }
        return ElementSelector { it.element.rank == 2 && it.topology.faceCycle(it.element.id).size == degree }
    }

    fun vertexDegree(degree: Int): ElementSelector {
        require(degree >= 2) { "vertex degree must be at least 2" }
        return ElementSelector { it.element.rank == 0 && it.topology.incidentUp(it.element.id).size == degree }
    }

    fun attributeEquals(name: String, value: Any?): ElementSelector = ElementSelector {
        name in it.attributes && it.attributes[name] == value
    }

    /** Match the most recent transform role, or any ancestor role when [recursive] is true. */
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

fun interface ElementDoubleParameter {
    fun value(context: ElementSelectionContext): Double
}

object ElementParameters {
    fun constant(value: Double): ElementDoubleParameter {
        require(value.isFinite()) { "parameter value must be finite" }
        return ElementDoubleParameter { value }
    }

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

    fun selected(
        selector: ElementSelector,
        selected: Double,
        otherwise: Double,
    ): ElementDoubleParameter {
        require(selected.isFinite() && otherwise.isFinite()) { "parameter values must be finite" }
        return ElementDoubleParameter { if (selector.matches(it)) selected else otherwise }
    }
}

fun PolyhedralObject.selectElements(
    selector: ElementSelector,
    rank: Int? = null,
): List<Element> = topology.elementsByRank.asSequence().flatten()
    .filter { rank == null || it.rank == rank }
    .filter { selector.matches(ElementSelectionContext(this, it)) }
    .toList()

fun PolytopeTransformResult.selectElements(
    selector: ElementSelector,
    rank: Int? = null,
): List<Element> = polyhedron.topology.elementsByRank.asSequence().flatten()
    .filter { rank == null || it.rank == rank }
    .filter { selector.matches(ElementSelectionContext(polyhedron, it, lineage(it.id))) }
    .toList()

internal fun PolytopeTransformResult.selectionContext(element: Element): ElementSelectionContext =
    ElementSelectionContext(polyhedron, element, lineage(element.id))
