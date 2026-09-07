@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

/**
 * Untyped per-element data, carried alongside the topology and propagated through transforms.
 *
 * An attribute is how a caller writes something onto one vertex, edge or face and gets it back later:
 * a per-face height for a non-uniform `kis`, a colour that survives into the FLine and triangle
 * output, a tag a selector can test with [ElementSelectors.attributeEquals]. What crosses a Conway
 * operator is decided by an [AttributePropagationPolicy] rather than by each operator, and the
 * default — [PreserveCommonAttributes] — keeps only the entries every source element agreed on.
 *
 * Values are typed `Any?`: these are Field's ordinary property values, so a box can put an
 * `Int`, a `Vec3` or a lambda in one. Two consequences follow: equality is
 * structural `==` on `Any?`, so `1` and `1.0` are different attributes, and nothing here validates a
 * value against a schema. The two extension functions [PolyhedralObject.withAttribute] and
 * [PolyhedralObject.withAttributes] are the intended entry points, because unlike
 * [PolytopeAttributes] itself they check that the element exists.
 */

/**
 * Immutable element-keyed attributes. Attribute values follow Field's ordinary property model and
 * are therefore typed as [Any]; maps are copied, while mutable values remain owned by the caller.
 */
class PolytopeAttributes private constructor(
    values: Map<ElementId, Map<String, Any?>>,
) {
    private val stored = values.mapValues { (_, attributes) -> attributes.toMap() }

    /**
     * The elements that have an entry, as a fresh set. An element appears as soon as any name has
     * been set on it — including a name set to null — and there is no way to remove it again.
     */
    val elementIds: Set<ElementId> get() = stored.keys.toSet()

    /**
     * This element's attributes, as a fresh copy.
     *
     * An element with no entry returns an empty map rather than null, so a read cannot distinguish
     * "no attributes" from "not part of this topology at all".
     */
    operator fun get(element: ElementId): Map<String, Any?> = stored[element].orEmpty().toMap()

    /**
     * One more name on one element, as a new value. A null [value] is stored, not treated as a
     * removal, and it makes [element] appear in [elementIds].
     */
    fun with(element: ElementId, name: String, value: Any?): PolytopeAttributes {
        require(name.isNotBlank()) { "attribute name must not be blank" }
        val next = stored.toMutableMap()
        next[element] = stored[element].orEmpty() + (name to value)
        return PolytopeAttributes(next)
    }

    /**
     * Merge a map over one element's existing attributes; later names win.
     *
     * An empty [attributes] returns this value untouched and creates no entry — which is why an
     * element that has only ever been given an empty map does not count towards
     * [PolyhedralObjectSummary.attributedElements].
     */
    fun with(element: ElementId, attributes: Map<String, Any?>): PolytopeAttributes {
        if (attributes.isEmpty()) return this
        require(attributes.keys.none { it.isBlank() }) { "attribute names must not be blank" }
        val next = stored.toMutableMap()
        next[element] = stored[element].orEmpty() + attributes
        return PolytopeAttributes(next)
    }

    companion object {
        val EMPTY = PolytopeAttributes(emptyMap())

        /**
         * Wrap an existing map. Element ids are *not* checked against any topology here; that check
         * belongs to [PolyhedralObject]'s own constructor and to the `withAttribute` extensions.
         */
        fun of(values: Map<ElementId, Map<String, Any?>>): PolytopeAttributes =
            if (values.isEmpty()) EMPTY else PolytopeAttributes(values)
    }
}

/**
 * Write one attribute onto one element, returning a new object — the ordinary way to paint a scalar
 * field or a tag onto a solid before deploying a selective transform.
 *
 * Rejects an element that is not in this topology, so a stale [ElementId] from before a transform
 * fails here rather than silently attaching data nothing will ever read.
 */
fun PolyhedralObject.withAttribute(element: ElementId, name: String, value: Any?): PolyhedralObject {
    require(topology.element(element) != null) { "element ${element.value} is not in this topology" }
    return copy(attributes = attributes.with(element, name, value))
}

/**
 * [withAttribute] for several names at once; existing names not mentioned in [values] survive.
 *
 * An empty [values] still validates the element and still allocates a new object, but the attributes
 * are the same value.
 */
fun PolyhedralObject.withAttributes(element: ElementId, values: Map<String, Any?>): PolyhedralObject {
    require(topology.element(element) != null) { "element ${element.value} is not in this topology" }
    return copy(attributes = attributes.with(element, values))
}
