package afield.stdlib

/**
 * Tiny string layer over the graph-copy API.
 *
 * This is deliberately not a second implementation of graph rewriting. It
 * parses forgiving "selector => action" rules, then delegates to the same
 * [Functions.Fn.rewriteGraph] machinery used by Kotlin callers. The optional
 * [FnRewriteOptions.kotlinNumberContext] is where KotlinInterface2 can be
 * threaded in for expressions such as `${'$'}{value * 1.1 + depth}` without
 * making this file depend directly on the live-code compiler. The selector side
 * is deliberately more structural than magical: `saw.frequency`, `role=amplitude`,
 * `parent=fbm param=gain`, and slash-separated path selectors with single-node
 * and recursive wildcards match the copied node with its occurrence path.
 */
data class FnRewriteNumberContext(
    val expression: String,
    val current: Double,
    val graph: FnGraphCopyContext
) {
    val value: Double get() = current
    val it: Double get() = current
    val op: String get() = graph.op
    val role: String? get() = graph.role
    val parameter: String? get() = graph.role
    val parentOp: String? get() = graph.parentOp
    val depth: Int get() = graph.depth
    val path: List<Int> get() = graph.path
    val ancestors: List<FnGraphPathElement> get() = graph.ancestors
    val occurrence: List<FnGraphPathElement> get() = graph.occurrence
    val name: String? get() = graph.name
    val domain: Functions.Domain get() = graph.domain
    val numbers: Map<String, Double> get() = graph.node.numbers
    val strings: Map<String, String> get() = graph.node.strings
    val booleans: Map<String, Boolean> get() = graph.node.booleans
    val longs: Map<String, Long> get() = graph.node.longs

    fun number(key: String): Double? = numbers[key]
    fun string(key: String): String? = strings[key]
    fun boolean(key: String): Boolean? = booleans[key]
    fun long(key: String): Long? = longs[key]
}

data class FnRewriteOptions(
    val kotlinNumber: ((expression: String, current: Double, context: FnGraphCopyContext) -> Double?)? = null,
    val preserveSharing: Boolean = false,
    val kotlinNumberContext: ((FnRewriteNumberContext) -> Double?)? = null
)

private data class FnDslRewriteRule(
    val selector: String,
    val actions: List<String>
)

fun Functions.Fn.rewrite(
    spec: String,
    options: FnRewriteOptions = FnRewriteOptions()
): Functions.Fn {
    val rules = parseFnDslRewriteRules(spec)
    if (rules.isEmpty()) return deepCopy(preserveSharing = options.preserveSharing)

    return rewriteGraph(preserveSharing = options.preserveSharing) { context, copy ->
        var current = copy
        for (rule in rules) {
            if (!rule.matches(context)) continue
            for (action in rule.actions) {
                current = applyRewriteAction(current, context, action, options)
            }
        }
        current
    }
}

fun Functions.Fn.rewrite(
    spec: String,
    preserveSharing: Boolean
): Functions.Fn = rewrite(spec, FnRewriteOptions(preserveSharing = preserveSharing))

private fun parseFnDslRewriteRules(spec: String): List<FnDslRewriteRule> =
    splitRewriteText(spec, setOf('\n', ';'))
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        .mapNotNull { line ->
            val parts = line.split("=>", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val actions = splitRewriteText(parts[1], setOf('|', ','))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (actions.isEmpty()) null else FnDslRewriteRule(parts[0].trim(), actions)
        }

/**
 * Split rewrite text on top-level separators only.
 *
 * Kotlin expressions are allowed on the RHS (`@{...}`), and those snippets
 * routinely contain commas, pipes, braces, strings, or semicolons. This small
 * scanner is not a Kotlin parser; it simply keeps obvious nested/comment/string
 * regions intact so the forgiving DSL does not sabotage the expressive part.
 */
private fun splitRewriteText(text: String, separators: Set<Char>): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0
    var inLineComment = false
    var inSingleQuote = false
    var inDoubleQuote = false
    var inTripleQuote = false
    var escaped = false

    fun emit() {
        parts += current.toString()
        current.clear()
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]
        val next = text.getOrNull(i + 1)
        val next2 = text.getOrNull(i + 2)

        if (inLineComment) {
            if (c == '\n') inLineComment = false
            current.append(c)
            i++
            continue
        }
        if (inTripleQuote) {
            current.append(c)
            if (c == '"' && next == '"' && next2 == '"') {
                current.append("\"\"")
                inTripleQuote = false
                i += 3
            } else {
                i++
            }
            continue
        }
        if (inSingleQuote || inDoubleQuote) {
            current.append(c)
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (inSingleQuote && c == '\'') {
                inSingleQuote = false
            } else if (inDoubleQuote && c == '"') {
                inDoubleQuote = false
            }
            i++
            continue
        }

        if (c == '/' && next == '/') {
            inLineComment = true
            current.append("//")
            i += 2
            continue
        }
        if (c == '"' && next == '"' && next2 == '"') {
            inTripleQuote = true
            current.append("\"\"\"")
            i += 3
            continue
        }
        when (c) {
            '\'' -> inSingleQuote = true
            '"' -> inDoubleQuote = true
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
        }

        if (
            c in separators &&
            parenDepth == 0 &&
            bracketDepth == 0 &&
            braceDepth == 0
        ) {
            emit()
        } else {
            current.append(c)
        }
        i++
    }

    emit()
    return parts
}

private fun FnDslRewriteRule.matches(context: FnGraphCopyContext): Boolean {
    val selector = selector.trim()
    if (selector == "*" || selector.equals("any", ignoreCase = true)) return true
    return splitSelectorTerms(selector)
        .filter { it.isNotEmpty() }
        .all { token ->
            if (looksLikePathSelector(token)) pathSelectorMatches(token, context)
            else tokenMatches(token, context)
        }
}

private fun tokenMatches(token: String, context: FnGraphCopyContext): Boolean {
    if ("[" in token && pathSegmentMatches(token, context.occurrence.last(), context.depth)) return true

    val lower = token.lowercase()
    if ("." in lower) {
        val parts = lower.split(".").filter { it.isNotEmpty() }
        if (parts.size == 2) {
            val left = parts[0]
            val right = parts[1]
            val parentMatches = context.parentOp?.equals(left, ignoreCase = true) == true ||
                context.op.equals(left, ignoreCase = true) ||
                context.node.tags.any { it.equals(left, ignoreCase = true) }
            val roleMatches = context.role?.equals(right, ignoreCase = true) == true ||
                context.op.equals(right, ignoreCase = true)
            return parentMatches && roleMatches
        }
    }
    if (lower == context.op.lowercase()) return true
    if (context.role?.equals(lower, ignoreCase = true) == true) return true
    if (lower == "number") return "number" in context.node.tags
    if (lower == "const") return context.op.equals("const", ignoreCase = true)
    if (lower == "param") return context.op.equals("param", ignoreCase = true)
    if (lower == "opaque") return "opaque" in context.node.tags

    val parts = token.split("=", limit = 2)
    if (parts.size != 2) return false
    val key = parts[0].trim().lowercase()
    val value = parts[1].trim().trim('"', '\'')
    return when (key) {
        "op" -> context.op.equals(value, ignoreCase = true)
        "name" -> context.name?.equals(value, ignoreCase = true) == true
        "role", "edge", "param", "parameter" -> context.role?.equals(value, ignoreCase = true) == true
        "parent", "under" -> context.parentOp?.equals(value, ignoreCase = true) == true
        "tag" -> context.node.tags.any { it.equals(value, ignoreCase = true) }
        "depth" -> context.depth == value.toIntOrNull()
        else -> false
    }
}

private fun splitSelectorTerms(selector: String): List<String> {
    val terms = mutableListOf<String>()
    val current = StringBuilder()
    var bracketDepth = 0
    var parenDepth = 0
    var braceDepth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaped = false

    fun emit() {
        val text = current.toString().trim()
        if (text.isNotEmpty()) terms += text
        current.clear()
    }

    for (c in selector) {
        if (inSingleQuote || inDoubleQuote) {
            current.append(c)
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (inSingleQuote && c == '\'') inSingleQuote = false
            else if (inDoubleQuote && c == '"') inDoubleQuote = false
            continue
        }

        when (c) {
            '\'' -> inSingleQuote = true
            '"' -> inDoubleQuote = true
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '{' -> braceDepth++
            '}' -> if (braceDepth > 0) braceDepth--
        }

        if ((c == '&' || c.isWhitespace()) && bracketDepth == 0 && parenDepth == 0 && braceDepth == 0) {
            emit()
        } else {
            current.append(c)
        }
    }

    emit()
    return terms
}

private fun looksLikePathSelector(selector: String): Boolean =
    '/' in selector || selector == "**"

private fun pathSelectorMatches(selector: String, context: FnGraphCopyContext): Boolean {
    val trimmed = selector.trim()
    val anchored = trimmed.startsWith("/")
    val body = trimmed.trim('/')
    val segments = splitPathSegments(body).filter { it.isNotBlank() }
    if (segments.isEmpty()) return false

    val occurrence = context.occurrence

    fun match(pathIndex: Int, segmentIndex: Int): Boolean {
        if (segmentIndex == segments.size) return pathIndex == occurrence.size
        val segment = segments[segmentIndex]

        if (segment == "**") {
            if (segmentIndex == segments.lastIndex) return true
            for (nextIndex in pathIndex..occurrence.size) {
                if (match(nextIndex, segmentIndex + 1)) return true
            }
            return false
        }

        if (pathIndex >= occurrence.size) return false
        return pathSegmentMatches(segment, occurrence[pathIndex], pathIndex) &&
            match(pathIndex + 1, segmentIndex + 1)
    }

    if (anchored) return match(0, 0)
    return occurrence.indices.any { start -> match(start, 0) }
}

private fun splitPathSegments(selector: String): List<String> {
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var bracketDepth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaped = false

    fun emit() {
        segments += current.toString().trim()
        current.clear()
    }

    for (c in selector) {
        if (inSingleQuote || inDoubleQuote) {
            current.append(c)
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (inSingleQuote && c == '\'') inSingleQuote = false
            else if (inDoubleQuote && c == '"') inDoubleQuote = false
            continue
        }

        when (c) {
            '\'' -> inSingleQuote = true
            '"' -> inDoubleQuote = true
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
        }

        if (c == '/' && bracketDepth == 0) emit()
        else current.append(c)
    }

    emit()
    return segments
}

private fun pathSegmentMatches(segment: String, element: FnGraphPathElement, depth: Int): Boolean {
    val trimmed = segment.trim()
    if (trimmed == "*" || trimmed.equals("any", ignoreCase = true)) return true
    if (trimmed == "**") return true

    val predicateRanges = Regex("""\[[^\]]*]""").findAll(trimmed).toList()
    val base = trimmed.substringBefore("[").trim()
    val baseMatches = base.isEmpty() ||
        base == "*" ||
        elementTokenMatches(base, element, depth)
    if (!baseMatches) return false

    return predicateRanges.all { match ->
        val predicate = match.value.removePrefix("[").removeSuffix("]").trim()
        predicate.isNotEmpty() && elementPredicateMatches(predicate, element, depth)
    }
}

private fun elementTokenMatches(token: String, element: FnGraphPathElement, depth: Int): Boolean {
    val lower = token.lowercase()
    if (lower == element.op.lowercase()) return true
    if (element.role?.equals(lower, ignoreCase = true) == true) return true
    if (lower == "number") return "number" in element.node.tags
    if (lower == "const") return element.op.equals("const", ignoreCase = true)
    if (lower == "param") return element.op.equals("param", ignoreCase = true)
    if (lower == "opaque") return "opaque" in element.node.tags
    if (element.node.tags.any { it.equals(lower, ignoreCase = true) }) return true

    val parts = token.split("=", limit = 2)
    if (parts.size == 2) return elementPredicateMatches(token, element, depth)
    return false
}

private fun elementPredicateMatches(predicate: String, element: FnGraphPathElement, depth: Int): Boolean {
    val op = listOf(">=", "<=", "!=", "=", ">", "<").firstOrNull { it in predicate } ?: return elementTokenMatches(predicate, element, depth)
    val parts = predicate.split(op, limit = 2)
    if (parts.size != 2) return false
    val key = parts[0].trim().lowercase()
    val expected = parts[1].trim().trim('"', '\'')

    if (key == "tag") return element.node.tags.any { it.equals(expected, ignoreCase = true) }

    val actual = elementValue(key, element, depth) ?: return false
    val expectedNumber = expected.toDoubleOrNull()
    val actualNumber = when (actual) {
        is Number -> actual.toDouble()
        is Boolean -> if (actual) 1.0 else 0.0
        is String -> actual.toDoubleOrNull()
        else -> null
    }

    return if (actualNumber != null && expectedNumber != null) {
        when (op) {
            ">=" -> actualNumber >= expectedNumber
            "<=" -> actualNumber <= expectedNumber
            "!=" -> actualNumber != expectedNumber
            "=" -> actualNumber == expectedNumber
            ">" -> actualNumber > expectedNumber
            "<" -> actualNumber < expectedNumber
            else -> false
        }
    } else {
        val actualText = actual.toString()
        when (op) {
            "!=" -> !actualText.equals(expected, ignoreCase = true)
            "=" -> actualText.equals(expected, ignoreCase = true)
            else -> false
        }
    }
}

private fun elementValue(key: String, element: FnGraphPathElement, depth: Int): Any? =
    when (key) {
        "op" -> element.op
        "role", "edge", "param", "parameter" -> element.role
        "name" -> element.name
        "depth" -> depth
        "index" -> element.index
        "value" -> element.node.numbers["value"]
        else -> element.node.numbers[key]
            ?: element.node.strings[key]
            ?: element.node.booleans[key]
            ?: element.node.longs[key]
    }

private fun applyRewriteAction(
    copy: Functions.Fn,
    context: FnGraphCopyContext,
    action: String,
    options: FnRewriteOptions
): Functions.Fn {
    val trimmed = action.trim()
    val lower = trimmed.lowercase()

    if (lower == "drop" || lower == "prune" || lower == "zero") {
        return Functions.zero(copy.domain)
    }

    if (lower.startsWith("op=") || lower.startsWith("replace=")) {
        val target = trimmed.substringAfter('=').trim().trim('"', '\'')
        return copy.rebuildAsOp(target) ?: copy
    }

    if (lower.startsWith("name=")) {
        return copy.withName(trimmed.substringAfter('=').trim().trim('"', '\''))
    }

    if (lower.startsWith("domain=")) {
        val value = trimmed.substringAfter('=').trim()
        parseDomain(value)?.let { return copy.withDomain(it) }
        if (value.equals("all", ignoreCase = true)) return copy.withDomain(Functions.Domain.ALL)
        if (value.equals("default", ignoreCase = true)) return copy.withDomain(Functions.Domain.DEFAULT)
        return copy
    }

    val currentValue = rewriteNumericValue(copy) ?: context.node.numbers["value"]
    if (currentValue != null) {
        val changed = applyNumberAction(trimmed, currentValue, context, options)
        if (changed != null && changed != currentValue) return rewriteNumericFn(changed, copy, context)
    }

    return copy
}

private fun rewriteNumericFn(value: Double, copy: Functions.Fn, context: FnGraphCopyContext): Functions.Fn {
    if ("parameter" in context.node.tags || context.node.op == "param") return parameterLiteral(value)
    return Functions.c(value, copy.domain).withName(copy.name)
}

private fun parameterLiteral(value: Number): Functions.Fn {
    val v = value.toDouble()
    return Functions.Fn(
        Functions.Domain.ALL,
        "param",
        FnGraphNode(
            op = "param",
            numbers = mapOf("value" to v),
            tags = setOf("number", "parameter"),
            rebuild = { parameterLiteral(v) }
        )
    ) { v }
}

private fun rewriteNumericValue(fn: Functions.Fn): Double? {
    val node = fn.graphNode()
    node.numbers["value"]?.let { return it }
    if (node.op != "name" && node.op != "over") return null
    return node.child("source")?.let(::rewriteNumericValue)
}

private fun applyNumberAction(
    action: String,
    current: Double,
    context: FnGraphCopyContext,
    options: FnRewriteOptions
): Double? {
    val match = Regex("""^\s*(?:value\s*)?(\*=|/=|\+=|-=|=)\s*(.+?)\s*$""").matchEntire(action)
        ?: return null
    val op = match.groupValues[1]
    val rhs = match.groupValues[2]
    val value = parseNumberExpression(rhs, current, context, options) ?: return null

    return when (op) {
        "*=" -> current * value
        "/=" -> if (value == 0.0) current else current / value
        "+=" -> current + value
        "-=" -> current - value
        "=" -> value
        else -> null
    }
}

private fun parseNumberExpression(
    expression: String,
    current: Double,
    context: FnGraphCopyContext,
    options: FnRewriteOptions
): Double? {
    val trimmed = expression.trim()
    if (trimmed.equals("it", ignoreCase = true) || trimmed.equals("value", ignoreCase = true)) return current
    trimmed.toDoubleOrNull()?.let { return it }
    val kotlinExpr = when {
        trimmed.startsWith("@{") && trimmed.endsWith("}") -> trimmed.removeSurrounding("@{", "}").trim()
        trimmed.startsWith("\${") && trimmed.endsWith("}") -> trimmed.removeSurrounding("\${", "}").trim()
        else -> trimmed
    }
    val numberContext = FnRewriteNumberContext(kotlinExpr, current, context)
    return options.kotlinNumberContext?.invoke(numberContext)
        ?: options.kotlinNumber?.invoke(kotlinExpr, current, context)
}

private fun parseDomain(value: String): Functions.Domain? {
    val trimmed = value.trim().removePrefix("(").removeSuffix(")")
    val parts = trimmed.split("..", limit = 2)
    if (parts.size != 2) return null
    val a = parts[0].trim().toDoubleOrNull() ?: return null
    val b = parts[1].trim().toDoubleOrNull() ?: return null
    return Functions.domain(a..b)
}
