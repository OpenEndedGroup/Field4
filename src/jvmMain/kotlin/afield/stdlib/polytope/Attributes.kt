package afield.stdlib.polytope

/**
 * Immutable element-keyed attributes. Attribute values follow Field's ordinary property model and
 * are therefore typed as [Any]; maps are copied, while mutable values remain owned by the caller.
 */
class PolytopeAttributes private constructor(
    values: Map<ElementId, Map<String, Any?>>,
) {
    private val stored = values.mapValues { (_, attributes) -> attributes.toMap() }

    val elementIds: Set<ElementId> get() = stored.keys.toSet()

    operator fun get(element: ElementId): Map<String, Any?> = stored[element].orEmpty().toMap()

    fun with(element: ElementId, name: String, value: Any?): PolytopeAttributes {
        require(name.isNotBlank()) { "attribute name must not be blank" }
        val next = stored.toMutableMap()
        next[element] = stored[element].orEmpty() + (name to value)
        return PolytopeAttributes(next)
    }

    fun with(element: ElementId, attributes: Map<String, Any?>): PolytopeAttributes {
        if (attributes.isEmpty()) return this
        require(attributes.keys.none { it.isBlank() }) { "attribute names must not be blank" }
        val next = stored.toMutableMap()
        next[element] = stored[element].orEmpty() + attributes
        return PolytopeAttributes(next)
    }

    companion object {
        val EMPTY = PolytopeAttributes(emptyMap())

        fun of(values: Map<ElementId, Map<String, Any?>>): PolytopeAttributes =
            if (values.isEmpty()) EMPTY else PolytopeAttributes(values)
    }
}

fun PolyhedralObject.withAttribute(element: ElementId, name: String, value: Any?): PolyhedralObject {
    require(topology.element(element) != null) { "element ${element.value} is not in this topology" }
    return copy(attributes = attributes.with(element, name, value))
}

fun PolyhedralObject.withAttributes(element: ElementId, values: Map<String, Any?>): PolyhedralObject {
    require(topology.element(element) != null) { "element ${element.value} is not in this topology" }
    return copy(attributes = attributes.with(element, values))
}
