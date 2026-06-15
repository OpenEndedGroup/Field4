package afield.four.util

import afield.Box
import afield.gather
import afield.logger.lprintln
import java.util.concurrent.ConcurrentHashMap

// A tiny, dynamic, Jinja-ish templating engine in *one file* using only Kotlin stdlib.
// Features:
// - {{ expr }} interpolation
// - {% if expr %} ... {% else %} ... {% endif %} with nesting
// - {% include "path" %} (requires include resolver)
// - Dynamic, runtime resolver chaining with dot-path fallback rules
// - Good runtime errors with line/col, line snippet, and a resolution trace
//
// The resolver contract is intentionally loose and runtime-driven:
//   resolve(key: String): Any?
// where Any? may be:
//   - null -> treated as "not resolved"
//   - String -> final value
//   - any other value -> final value via toString()
//   - Resolver -> used to resolve remaining suffix segments
//   - Iterable<*> containing Resolvers -> used as a new resolver chain
//
// Path strategy for "foo.bar.gux":
//   try "foo.bar.gux", then "foo.bar", then "foo", then "".
// If "foo" resolves to a Resolver, we then attempt "bar.gux", then "bar", then "" on that child.
//
// This gives you liveness and debuggability over type gymnastics.

//////////////////////////////
// Public API
//////////////////////////////

fun interface Resolver {
    fun resolve(key: String): Any?
}

typealias ResolverFn = (String) -> Any?
fun interface IncludeResolver {
    fun include(path: String, line: Int, col: Int): String
}

private val globalShaderTemplates = ConcurrentHashMap<String, Any>()

private val globalShaderTemplateResolver = Resolver { key -> globalShaderTemplates[key] }

fun registerGlobalShaderTemplate(name: String, template: Any) {
    globalShaderTemplates[name] = template
}

fun unregisterGlobalShaderTemplate(name: String): Any? =
    globalShaderTemplates.remove(name)

fun listGlobalShaderTemplateNames(): Set<String> =
    globalShaderTemplates.keys.toSortedSet()

fun getShaderTemplateResolverForBox(b : Box): List<Resolver> =
    listOf(
        MapResolver(b.gather<MutableMap<String, Any>>("shaderTemplate")),
        globalShaderTemplateResolver
    )

fun runTemplate(template: String, resolver: Resolver, includeResolver: IncludeResolver? = null): String =
    TemplateEngine().run(template, listOf(resolver), includeResolver)

fun runTemplate(template: String, resolver: ResolverFn, includeResolver: IncludeResolver? = null): String =
    TemplateEngine().run(template, listOf(FnResolver(resolver)), includeResolver)

/**
 * Most permissive entrypoint.
 * Allows passing:
 *  - Resolver
 *  - (String) -> Any?
 *  - Iterable<*> of resolvers
 *  - Map<String, Any?> (will be wrapped with MapResolver)
 */
fun runTemplate(template: String, resolver: Any?, includeResolver: IncludeResolver? = null): String =
    TemplateEngine().run(template, TemplateEngine.asResolverList(resolver), includeResolver)

//////////////////////////////
// Engine
//////////////////////////////

private class TemplateEngine {

    fun run(template: String, rootResolvers: List<Resolver>, includeResolver: IncludeResolver? = null): String {
        val lineIndex = LineIndex(template)
        val parser = Parser(template, lineIndex)
        val nodes = parser.parseTopLevel()
        val evaluator = Evaluator(template, lineIndex, rootResolvers, includeResolver)
        return evaluator.render(nodes, rootResolvers)
    }

    companion object {
        fun asResolverList(any: Any?): List<Resolver> = when (any) {
            null -> emptyList()
            is Resolver -> listOf(any)
            is Map<*, *> -> listOf(MapResolver(any.entries.associate { it.key.toString() to it.value }))
            is Iterable<*> -> any.mapNotNull { asSingleResolverOrNull(it) }
            else -> {
                // Try to treat it as a lambda-like resolver.
                val fn = any as? ResolverFn
                if (fn != null) listOf(FnResolver(fn))
                else throw TemplateException(
                    "Unsupported resolver root type: ${any::class.qualifiedName ?: any::class.simpleName}"
                )
            }
        }

        private fun asSingleResolverOrNull(any: Any?): Resolver? = when (any) {
            null -> null
            is Resolver -> any
            is Map<*, *> -> MapResolver(any.entries.associate { it.key.toString() to it.value })
            else -> {
                val fn = any as? ResolverFn
                if (fn != null) FnResolver(fn) else null
            }
        }
    }
}

//////////////////////////////
// Parsing
//////////////////////////////

private sealed interface Node
private data class TextNode(val text: String) : Node
private data class ExprNode(val expr: String, val loc: Location) : Node
private data class IncludeNode(val includeExpr: String, val loc: Location) : Node
private data class IfNode(
    val condition: String,
    val thenBranch: List<Node>,
    val elseBranch: List<Node>?,
    val loc: Location
) : Node

private class Parser(
    private val template: String,
    private val index: LineIndex
) {
    private var pos: Int = 0

    fun parseTopLevel(): List<Node> {
        val res = parseBlock(stopTags = emptySet())
        if (res.stopTag != null) {
            val loc = index.location(res.stopTagStartIndex ?: 0)
            throw TemplateException("Unexpected tag '${res.stopTag}' at ${loc.format()}.")
        }
        return res.nodes
    }

    private data class BlockResult(
        val nodes: List<Node>,
        val stopTag: String?,
        val stopTagStartIndex: Int?
    )

    private fun parseBlock(stopTags: Set<String>): BlockResult {
        val nodes = mutableListOf<Node>()

        while (pos < template.length) {
            val nextExpr = template.indexOf("{{", startIndex = pos)
            val nextTag = template.indexOf("{%", startIndex = pos)
            val next = minPositive(nextExpr, nextTag)

            if (next == -1) {
                if (pos < template.length) {
                    nodes += TextNode(template.substring(pos))
                }
                pos = template.length
                break
            }

            if (next > pos) {
                nodes += TextNode(template.substring(pos, next))
            }

            if (next == nextExpr) {
                val start = next
                val end = template.indexOf("}}", startIndex = start + 2)
                if (end == -1) {
                    val loc = index.location(start)
                    throw TemplateException("Unclosed '{{' at ${loc.format()}.")
                }
                val expr = template.substring(start + 2, end).trim()
                val loc = index.location(start)
                nodes += ExprNode(expr, loc)
                pos = end + 2
                continue
            }

            // Tag
            val start = next
            val end = template.indexOf("%}", startIndex = start + 2)
            if (end == -1) {
                val loc = index.location(start)
                throw TemplateException("Unclosed '{%' at ${loc.format()}.")
            }

            val raw = template.substring(start + 2, end).trim()
            val loc = index.location(start)

            // Stop tags for nested parsing
            if (raw in stopTags) {
                pos = end + 2
                return BlockResult(nodes, raw, start)
            }

            when {
                raw.startsWith("if ") -> {
                    val condition = raw.removePrefix("if ").trim()
                    pos = end + 2

                    val thenRes = parseBlock(stopTags = setOf("else", "endif"))
                    val thenNodes = thenRes.nodes

                    when (thenRes.stopTag) {
                        "else" -> {
                            val elseRes = parseBlock(stopTags = setOf("endif"))
                            if (elseRes.stopTag != "endif") {
                                throw TemplateException("Missing '{% endif %}' for if at ${loc.format()}.")
                            }
                            nodes += IfNode(condition, thenNodes, elseRes.nodes, loc)
                        }
                        "endif" -> {
                            nodes += IfNode(condition, thenNodes, null, loc)
                        }
                        null -> {
                            throw TemplateException("Missing '{% endif %}' for if at ${loc.format()}.")
                        }
                        else -> {
                            throw TemplateException("Unexpected tag '${thenRes.stopTag}' near ${loc.format()}.")
                        }
                    }
                }
                raw.startsWith("include ") -> {
                    val includeExpr = raw.removePrefix("include ").trim()
                    if (includeExpr.isBlank()) {
                        throw TemplateException("Missing include path at ${loc.format()}.")
                    }
                    nodes += IncludeNode(includeExpr, loc)
                }
                raw == "else" || raw == "endif" -> {
                    // If we see these here, they were not expected by any enclosing block.
                    throw TemplateException("Unexpected '{% $raw %}' at ${loc.format()}.")
                }
                raw.isBlank() -> {
                    throw TemplateException("Empty tag at ${loc.format()}.")
                }
                else -> {
                    throw TemplateException("Unknown tag '{% $raw %}' at ${loc.format()}.")
                }
            }

            pos = maxOf(pos, end + 2)
        }

        return BlockResult(nodes, stopTag = null, stopTagStartIndex = null)
    }

    private fun minPositive(a: Int, b: Int): Int = when {
        a == -1 && b == -1 -> -1
        a == -1 -> b
        b == -1 -> a
        else -> minOf(a, b)
    }
}

//////////////////////////////
// Evaluation
//////////////////////////////

private class Evaluator(
    private val template: String,
    private val index: LineIndex,
    private val rootResolvers: List<Resolver>,
    private val includeResolver: IncludeResolver?
) {

    fun render(nodes: List<Node>, currentResolvers: List<Resolver>): String {
        val sb = StringBuilder()
        for (n in nodes) {
            when (n) {
                is TextNode -> sb.append(n.text)
                is ExprNode -> sb.append(evalExprToString(n.expr, n.loc, currentResolvers))
                is IncludeNode -> sb.append(evalIncludeToString(n.includeExpr, n.loc, currentResolvers))
                is IfNode -> {
                    val truthy = evalCondition(n.condition, n.loc, currentResolvers)
                    val branch = if (truthy) n.thenBranch else (n.elseBranch ?: emptyList())
                    sb.append(render(branch, currentResolvers))
                }
            }
        }
        return sb.toString()
    }

    private fun evalExprToString(expr: String, loc: Location, resolvers: List<Resolver>): String {
        val trace = mutableListOf<String>()
        val value = resolveExpression(expr, resolvers, trace, depth = 0)
        if (value == null) {
            throw resolutionError(expr, loc, trace)
        }

        val raw = when (value) {
            is String -> value
            else -> value.toString()
        }

        return applyLineMagic(raw, loc.line)
    }

    private fun evalIncludeToString(expr: String, loc: Location, resolvers: List<Resolver>): String {
        val includePath = evaluateIncludePath(expr, loc, resolvers)
        val resolver = includeResolver
            ?: throw TemplateException("Include tag used without include resolver at ${loc.format()}.")
        return try {
            resolver.include(includePath, loc.line, loc.col)
        } catch (e: TemplateException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Unknown include error"
            throw TemplateException("Failed to include '$includePath' at ${loc.format()}: $msg")
        }
    }

    private fun evaluateIncludePath(expr: String, loc: Location, resolvers: List<Resolver>): String {
        val trimmed = expr.trim()
        if (trimmed.length >= 2) {
            if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                (trimmed.startsWith("'") && trimmed.endsWith("'"))
            ) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }

        val trace = mutableListOf<String>()
        val value = resolveExpression(trimmed, resolvers, trace, depth = 0)
            ?: throw resolutionError(trimmed, loc, trace)
        return if (value is String) value else value.toString()
    }


    private fun evalCondition(expr: String, loc: Location, resolvers: List<Resolver>): Boolean {
        val trimmed = expr.trim()
        val negated = trimmed.startsWith("not ") || trimmed.startsWith("!")
        val inner = when {
            trimmed.startsWith("not ") -> trimmed.removePrefix("not ").trim()
            trimmed.startsWith("!") -> trimmed.removePrefix("!").trim()
            else -> trimmed
        }

        val trace = mutableListOf<String>()
        val value = resolveExpression(inner, resolvers, trace, depth = 0)

        val truth = when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotEmpty()
            is Map<*, *> -> value.isNotEmpty()
            is Resolver -> true
            is Iterable<*> -> value.any()
            else -> true
        }

        return if (negated) !truth else truth
    }

    private fun applyLineMagic(s: String, line: Int): String {
        val rep = "#line $line"
        // handle both LF and CRLF just in case
        return s
            .replace("//#<line>\r\n", "$rep\r\n")
            .replace("//#<line>\n", "$rep\n")
    }

    private fun resolveExpression(
        expr: String,
        resolvers: List<Resolver>,
        trace: MutableList<String>,
        depth: Int
    ): Any? {
        if (depth > 50) {
            trace += "Resolver chain exceeded max depth (50)."
            return null
        }

        val raw = expr.trim()
        val segments = if (raw.isEmpty()) emptyList() else raw.split(".").map { it.trim() }

        return resolveSegments(segments, resolvers, trace, depth)
    }

    private fun resolveSegments(
        segments: List<String>,
        resolvers: List<Resolver>,
        trace: MutableList<String>,
        depth: Int
    ): Any? {
        val n = segments.size

        for (i in n downTo 0) {
            val prefix = if (i == 0) "" else segments.subList(0, i).joinToString(".")
            val suffix = segments.subList(i, n)

            var scalarCandidate: Any? = null

            for (resolver in resolvers) {
                val result = safeResolve(resolver, prefix, trace)
                if (result == null) continue

                when (result) {
                    is Resolver -> {
                        val childList = listOf(result)
                        val sub = resolveSegments(suffix, childList, trace, depth + 1)
                        if (sub != null) return sub
                        if (suffix.isEmpty()) {
                            val subBlank = resolveSegments(emptyList(), childList, trace, depth + 1)
                            if (subBlank != null) return subBlank
                        }
                    }

                    // NEW:
                    is Map<*, *> -> {
                        val child = MapResolver(result.entries.associate { it.key.toString() to it.value })
                        val childList = listOf(child)
                        val sub = resolveSegments(suffix, childList, trace, depth + 1)
                        if (sub != null) return sub
                        if (suffix.isEmpty()) {
                            val subBlank = resolveSegments(emptyList(), childList, trace, depth + 1)
                            if (subBlank != null) return subBlank
                        }
                        // If you want scalar-prefix fallback to consider the map itself as a value,
                        // don't set scalarCandidate here; typically you'd treat it as structural only.
                    }

                    is Iterable<*> -> {
                        val childResolvers = result.mapNotNull { it as? Resolver }
                        if (childResolvers.isNotEmpty()) {
                            val sub = resolveSegments(suffix, childResolvers, trace, depth + 1)
                            if (sub != null) return sub
                            if (suffix.isEmpty()) {
                                val subBlank = resolveSegments(emptyList(), childResolvers, trace, depth + 1)
                                if (subBlank != null) return subBlank
                            }
                        } else {
                            if (scalarCandidate == null) scalarCandidate = result
                        }
                    }

                    else -> {
                        if (scalarCandidate == null) scalarCandidate = result
                    }
                }

            }

            // NEW: after trying all resolvers at this prefix,
            // if we found a scalar candidate, use it.
            if (scalarCandidate != null) return scalarCandidate
        }

        return null
    }


    private fun safeResolve(resolver: Resolver, key: String, trace: MutableList<String>): Any? {
        return try {
            val out = resolver.resolve(key)
            trace += "${resolverName(resolver)}.resolve('$key') -> ${describeResult(out)}"
            out
        } catch (e: Exception) {
            trace += "${resolverName(resolver)}.resolve('$key') -> threw ${e::class.simpleName}: ${e.message}"
            throw e
        }
    }

    private fun describeResult(out: Any?): String = when (out) {
        null -> "null"
        is String -> "String(${short(out)})"
        is Resolver -> "Resolver(${resolverName(out)})"
        is Map<*, *> -> "Map(${out.size})"
        is Iterable<*> -> {
            val count = out.count { it is Resolver }
            if (count > 0) "List<Resolver>($count)" else "Iterable"
        }
        else -> "${out::class.simpleName}(${short(out.toString())})"
    }

    private fun short(s: String, max: Int = 60): String =
        if (s.length <= max) "\"$s\"" else "\"${s.take(max)}…\""

    private fun resolverName(r: Resolver): String =
        r::class.simpleName ?: r::class.qualifiedName ?: "AnonymousResolver"

    private fun resolutionError(expr: String, loc: Location, trace: List<String>): TemplateException {
        val lineText = index.lineText(loc.line)
        val caret = buildString {
            val safeCol = maxOf(1, loc.col)
            repeat(safeCol - 1) { append(' ') }
            append('^')
        }

        val attempts = trace.joinToString(separator = "\n") { "  - $it" }

        val msg = buildString {
            append("Unable to resolve expression '$expr' at ${loc.format()}.\n")
            append("Line ${loc.line}: $lineText\n")
            append("          $caret\n")
            append("Tried:\n")
            append(attempts)
        }
        return TemplateException(msg)
    }
}

//////////////////////////////
// Line/Location helpers
//////////////////////////////

private data class Location(val line: Int, val col: Int) {
    fun format(): String = "line $line, col $col"
}

private class LineIndex(private val text: String) {
    private val lineStarts: IntArray = buildLineStarts(text)

    fun location(index: Int): Location {
        val line = findLine(index)
        val lineStart = lineStarts[line - 1]
        val col = (index - lineStart) + 1
        return Location(line, col)
    }

    fun lineText(line: Int): String {
        if (line < 1) return ""
        val start = lineStarts.getOrNull(line - 1) ?: return ""
        val endExclusive = if (line < lineStarts.size) lineStarts[line] - 1 else text.length
        val raw = text.substring(start, endExclusive.coerceAtLeast(start))
        return raw.trimEnd('\r', '\n')
    }

    private fun findLine(index: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = lineStarts[mid]
            if (v <= index) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans + 1 // 1-based
    }

    private fun buildLineStarts(s: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in s.indices) {
            if (s[i] == '\n') {
                val next = i + 1
                if (next < s.length) starts.add(next)
            }
        }
        return starts.toIntArray()
    }
}

//////////////////////////////
// Helpful resolver implementations
//////////////////////////////

private class FnResolver(private val fn: ResolverFn) : Resolver {
    override fun resolve(key: String): Any? = fn(key)
}

/**
 * Map-backed resolver.
 * - Looks up exact keys.
 * - If the stored value is a Map, it returns a MapResolver to enable hierarchical resolution.
 * - If the stored value is already a Resolver or Iterable of resolvers, returns it as-is.
 */
class MapResolver(private val map: Map<String, Any?>) : Resolver {
    override fun resolve(key: String): Any? {
        val v = map[key]
        return when (v) {
            is Map<*, *> -> MapResolver(v.entries.associate { it.key.toString() to it.value })
            else -> v
        }
    }
}

/**
 * Composite resolver that tries children in order.
 * This is useful if you want a simple "first non-null wins" behavior
 * without relying on the engine's "return a List<Resolver>" feature.
 */
class CompositeResolver(private val resolvers: List<Resolver>) : Resolver {
    override fun resolve(key: String): Any? {
        for (r in resolvers) {
            val v = r.resolve(key)
            if (v != null) return v
        }
        return null
    }
}

//////////////////////////////
// Errors
//////////////////////////////

class TemplateException(message: String) : RuntimeException(message)

//////////////////////////////
// Representative main()
//////////////////////////////

fun main() {
    // A nested map example. MapResolver will auto-wrap nested maps into child resolvers.
    val data = mapOf(
        "name" to "Marc",
        "isAdmin" to true,
        "foo" to mapOf(
            "bar" to mapOf(
                "gux" to "Hello from gux!"
            ),
            "bar2" to 123
        )
    )

    val defaults = mapOf(
        "name" to "Stranger",
        "global" to "This is a fallback value"
    )

    val base = MapResolver(data)
    val fallback = MapResolver(defaults)

    // A "facade" resolver that exposes a list of resolvers when asked for "".
    // This demonstrates your "blank-string" handoff trick.
    val facade = object : Resolver {
        override fun resolve(key: String): Any? {
            return if (key.isEmpty()) listOf(base, fallback) else null
        }
    }

    val template = """
Hello {{ name }}!

Direct leaf: {{ foo.bar.gux }}
Number leaf: {{ foo.bar2 }}


{% if isAdmin %}
Welcome, admin {{ name }}.
{% else %}
Welcome, user {{ name }}.
{% endif %}

{% if not foo.bar.missingThing %}
This shows because the condition is not truthy.
{% endif %}

Global fallback via facade: {{ global }}
""".trimIndent()

    try {
        val out = runTemplate(template, facade)
        lprintln("info,templating", out)
    } catch (e: TemplateException) {
        lprintln("error,templating", "Template error:\n${e.message}")
    }
}
