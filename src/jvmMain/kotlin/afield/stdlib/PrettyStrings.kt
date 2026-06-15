package afield.stdlib

private const val PRETTY_LIMIT = 4
private const val STRING_LIMIT = 48

internal fun prettySummary(name: String, vararg fields: Pair<String, Any?>): String {
    if (fields.isEmpty()) return name
    val body = fields.joinToString(", ") { (key, value) -> "$key=${prettyValue(value)}" }
    return "$name($body)"
}

internal fun prettyValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"${value.prettyText()}\""
    is CharSequence -> "\"${value.toString().prettyText()}\""
    is Number, is Boolean, is Char -> value.toString()
    is Enum<*> -> value.name
    is FloatArray -> prettyArray("FloatArray", value.size, value.take(PRETTY_LIMIT))
    is DoubleArray -> prettyArray("DoubleArray", value.size, value.take(PRETTY_LIMIT))
    is IntArray -> prettyArray("IntArray", value.size, value.take(PRETTY_LIMIT))
    is LongArray -> prettyArray("LongArray", value.size, value.take(PRETTY_LIMIT))
    is Iterable<*> -> {
        val items = value.take(PRETTY_LIMIT).joinToString(", ") { prettyValue(it) }
        val suffix = if (value.drop(PRETTY_LIMIT).any()) ", ..." else ""
        "[$items$suffix]"
    }
    is Map<*, *> -> {
        val items = value.entries.take(PRETTY_LIMIT).joinToString(", ") { (k, v) -> "${prettyValue(k)}=${prettyValue(v)}" }
        val suffix = if (value.size > PRETTY_LIMIT) ", ..." else ""
        "{$items$suffix}"
    }
    is Function<*> -> "<lambda>"
    else -> value.toString()
}

internal fun prettyNumber(value: Double, decimals: Int = 4): String =
    String.format(java.util.Locale.US, "%.${decimals}f", value).trimEnd('0').trimEnd('.')

internal fun prettyNumber(value: Float, decimals: Int = 3): String =
    String.format(java.util.Locale.US, "%.${decimals}f", value).trimEnd('0').trimEnd('.')

private fun prettyArray(type: String, size: Int, values: List<Any>): String {
    val preview = values.joinToString(", ")
    val suffix = if (size > values.size) ", ..." else ""
    return "$type($size)[$preview$suffix]"
}

private fun String.prettyText(limit: Int = STRING_LIMIT): String =
    if (length <= limit) this else take(limit - 3) + "..."
