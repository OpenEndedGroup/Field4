package afield.four.synth

import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

private const val PRETTY_MAX_DEPTH = 2
private const val PRETTY_MAX_FIELDS = 6
private const val PRETTY_MAX_ITEMS = 4
private const val PRETTY_MAX_STRING = 48

internal fun synthPrettyObject(
    instance: Any,
    vararg fields: Pair<String, Any?>,
    maxDepth: Int = PRETTY_MAX_DEPTH
): String {
    val seen = IdentityHashMap<Any, Unit>()
    return synthPrettyObject(instance, fields.toList(), seen, 0, maxDepth)
}

internal fun synthPrettyValue(
    value: Any?,
    maxDepth: Int = PRETTY_MAX_DEPTH
): String {
    val seen = IdentityHashMap<Any, Unit>()
    return synthPrettyValue(value, seen, 0, maxDepth)
}

private fun synthPrettyObject(
    instance: Any,
    explicitFields: List<Pair<String, Any?>>,
    seen: IdentityHashMap<Any, Unit>,
    depth: Int,
    maxDepth: Int
): String {
    val className = instance::class.simpleName ?: instance.javaClass.simpleName.ifBlank { "Object" }
    if (seen.containsKey(instance)) return "$className(...)"
    if (depth >= maxDepth) return className
    seen[instance] = Unit
    val fields = if (explicitFields.isNotEmpty()) explicitFields else discoverPrettyFields(instance)
    if (fields.isEmpty()) return className
    val rendered = fields.take(PRETTY_MAX_FIELDS).joinToString(", ") { (name, value) ->
        "$name=${synthPrettyValue(value, seen, depth + 1, maxDepth)}"
    }
    val suffix = if (fields.size > PRETTY_MAX_FIELDS) ", ..." else ""
    return "$className($rendered$suffix)"
}

private fun discoverPrettyFields(instance: Any): List<Pair<String, Any?>> {
    if (instance is LiveGraphInspectable) {
        return instance.liveGraphChildren().mapNotNull { edge ->
            runCatching { edge.name to edge.getter() }.getOrNull()
        }
    }

    return instance::class.memberProperties
        .asSequence()
        .filter { it.visibility == KVisibility.PUBLIC }
        .filter { it.parameters.size == 1 }
        .mapNotNull { property ->
            runCatching {
                property.isAccessible = true
                property.name to property.getter.call(instance)
            }.getOrNull()
        }
        .toList()
}

private fun synthPrettyValue(
    value: Any?,
    seen: IdentityHashMap<Any, Unit>,
    depth: Int,
    maxDepth: Int
): String = when (value) {
    null -> "null"
    is String -> "\"${value.truncateForPrettyPrint(PRETTY_MAX_STRING)}\""
    is CharSequence -> "\"${value.toString().truncateForPrettyPrint(PRETTY_MAX_STRING)}\""
    is Number, is Boolean, is Char -> value.toString()
    is Enum<*> -> value.name
    is Path -> value.toString()
    is FloatArray -> primitiveArraySummary("FloatArray", value.size, value.take(4))
    is DoubleArray -> primitiveArraySummary("DoubleArray", value.size, value.take(4))
    is IntArray -> primitiveArraySummary("IntArray", value.size, value.take(4))
    is LongArray -> primitiveArraySummary("LongArray", value.size, value.take(4))
    is ShortArray -> primitiveArraySummary("ShortArray", value.size, value.take(4))
    is ByteArray -> "ByteArray(${value.size})"
    is BooleanArray -> primitiveArraySummary("BooleanArray", value.size, value.take(4))
    is CharArray -> primitiveArraySummary("CharArray", value.size, value.take(4))
    is Iterable<*> -> iterableSummary(value, seen, depth, maxDepth)
    is Map<*, *> -> mapSummary(value, seen, depth, maxDepth)
    is Function<*> -> "<lambda>"
    else -> synthPrettyObject(value, emptyList(), seen, depth, maxDepth)
}

private fun iterableSummary(
    values: Iterable<*>,
    seen: IdentityHashMap<Any, Unit>,
    depth: Int,
    maxDepth: Int
): String {
    val items = values.take(PRETTY_MAX_ITEMS)
        .joinToString(", ") { synthPrettyValue(it, seen, depth + 1, maxDepth) }
    val iterator = values.iterator()
    var count = 0
    while (iterator.hasNext() && count <= PRETTY_MAX_ITEMS) {
        iterator.next()
        count++
    }
    val suffix = if (count > PRETTY_MAX_ITEMS) ", ..." else ""
    return "[$items$suffix]"
}

private fun mapSummary(
    values: Map<*, *>,
    seen: IdentityHashMap<Any, Unit>,
    depth: Int,
    maxDepth: Int
): String {
    val entries = values.entries.take(PRETTY_MAX_ITEMS).joinToString(", ") { (key, itemValue) ->
        "${synthPrettyValue(key, seen, depth + 1, maxDepth)}=${synthPrettyValue(itemValue, seen, depth + 1, maxDepth)}"
    }
    val suffix = if (values.size > PRETTY_MAX_ITEMS) ", ..." else ""
    return "{$entries$suffix}"
}

private fun primitiveArraySummary(type: String, size: Int, preview: List<Any>): String {
    val body = preview.joinToString(", ")
    val suffix = if (size > preview.size) ", ..." else ""
    return if (size == 0) "$type(0)" else "$type($size)[$body$suffix]"
}

private fun String.truncateForPrettyPrint(limit: Int): String =
    if (length <= limit) this else take(limit - 3) + "..."
