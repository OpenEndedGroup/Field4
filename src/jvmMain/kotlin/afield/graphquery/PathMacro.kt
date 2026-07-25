package afield.graphquery

import afield.RunsAt
import afield.macroRegistration
import afield.macros.Insert
import afield.macros.MacroRegistry
import afield.macros.MacroRegistry.callRewrite
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty0

/**
 * A *compile-checked* path expression — the string-free counterpart to [graphPaths].
 *
 * `graphPaths("osc/filter/cutoff")` is convenient but unverified: a typo, or a rename three
 * months later, produces an empty result at runtime and nothing tells you why. That is the
 * usual price of a reflective query layer.
 *
 * `graphLens` avoids paying it. It is a source macro: before the box is compiled, the call
 *
 * ```kotlin
 * val cutoff = graphLens(rig, "carrier/freq")
 * ```
 *
 * is rewritten into an ordinary bound property reference
 *
 * ```kotlin
 * afield.graphquery.boundLens(rig.carrier::freq, "carrier/freq")
 * ```
 *
 * and then handed to `kotlinc` like any other code. Three things fall out of that:
 *
 *  - **A bad path is a compile error.** `graphLens(rig, "carrier/frequency")` fails with
 *    Kotlin's own "unresolved reference: frequency", positioned in your editor, because by
 *    the time the compiler sees it there is no path — only a property access.
 *  - **The element type is inferred**, so `cutoff.get()` is a `Float`, not an `Any?` you
 *    have to cast. No reified type argument, no `as`.
 *  - **Writability is checked too**, via overload resolution: a `var` resolves to the
 *    [KMutableProperty0] overload and yields a writable lens, a `val` resolves to the
 *    [KProperty0] one and yields a read-only lens whose `set` does not exist to be called.
 *
 * There is no reflection over the path at runtime and no traversal at all — this is the
 * fastest of the three resolution strategies, below even the literal-prefix fast path.
 *
 * The trade is deliberate: the guarantee only holds for paths made of Kotlin identifiers.
 * Wildcards, map keys and list indices cannot become property accesses, so rather than
 * silently degrading to an unchecked runtime lookup, the macro refuses to expand and points
 * at [graphPaths] / [graphPath]. If `graphLens` compiles, it was checked.
 */

/**
 * One statically-resolved slot. Carries no reflection: the accessors are the property
 * reference the compiler already resolved.
 */
class StaticLens<T> internal constructor(
    /** The original path text, kept for diagnostics and display. */
    val path: String,
    private val getter: () -> T,
    private val setter: ((T) -> Unit)?
) {
    val writable: Boolean get() = setter != null

    fun get(): T = getter()

    fun set(value: T) {
        val write = setter ?: error("Path '$path' is not writable")
        write(value)
    }

    fun update(transform: (T) -> T) {
        set(transform(get()))
    }

    override fun toString(): String = "StaticLens($path)"
}

/**
 * Writable overload — selected by overload resolution when the referenced property is a
 * `var`. Being more specific than the [KProperty0] overload, it always wins for a `var`.
 */
fun <T> boundLens(property: KMutableProperty0<T>, path: String): StaticLens<T> =
    StaticLens(path, { property.get() }, { value -> property.set(value) })

/** Read-only overload — the only applicable one when the property is a `val`. */
fun <T> boundLens(property: KProperty0<T>, path: String): StaticLens<T> =
    StaticLens(path, { property.get() }, null)

@RunsAt(macroRegistration)
fun registerGraphQueryMacros() {

    // graphLens(receiver, "a/b/c")  ->  boundLens(receiver.a.b::c, "a/b/c")
    callRewrite["graphLens"] = { _, _, args ->
        expandGraphLens(args)
    }
}

private const val USAGE = "graphLens expects a receiver and a literal path, " +
        "e.g. graphLens(rig, \"carrier/freq\")"

internal fun expandGraphLens(args: List<String>): Insert {
    // The identifier-macro pass hands over the raw source of the call's argument list,
    // optionally preceded by a type-argument group. There is nothing to infer from a type
    // argument here, so reject it rather than ignore it.
    val parts = args.filter { it.isNotBlank() }
    val argText = parts.lastOrNull()?.trim() ?: throw MacroRegistry.MacroExpansionProblem(USAGE)
    if (parts.size > 1 || !argText.startsWith("(") || !argText.endsWith(")")) {
        throw MacroRegistry.MacroExpansionProblem(USAGE)
    }

    val arguments = splitTopLevelArguments(argText.substring(1, argText.length - 1))
    if (arguments.size != 2) throw MacroRegistry.MacroExpansionProblem(USAGE)

    val receiver = arguments[0].trim()
    if (receiver.isEmpty()) throw MacroRegistry.MacroExpansionProblem(USAGE)

    val pathText = parseSimpleStringLiteral(arguments[1].trim())
        ?: throw MacroRegistry.MacroExpansionProblem(
            "graphLens needs a plain string literal for the path (no interpolation, no " +
                    "computed expression) so that it can be checked at compile time. " +
                    "For a path built at runtime use graphPath(...)."
        )

    val segments = pathText.split("/").map { it.trim() }.filter { it.isNotEmpty() }
    if (segments.isEmpty()) {
        throw MacroRegistry.MacroExpansionProblem("graphLens was given an empty path")
    }

    val offending = segments.firstOrNull { !isKotlinIdentifier(it) }
    if (offending != null) {
        throw MacroRegistry.MacroExpansionProblem(
            "graphLens cannot check the segment '$offending' in \"$pathText\": only Kotlin " +
                    "property names can become a compile-time property access. Wildcards, map " +
                    "keys and list indices need the reflective layer — use " +
                    "graphPaths(\"$pathText\") or graphPath(\"$pathText\") instead."
        )
    }

    // rig + [carrier, freq] -> rig.carrier::freq
    val chain = (listOf(receiver) + segments.dropLast(1)).joinToString(".")
    val reference = "$chain::${segments.last()}"

    // Emit the normalized path, so `lens.path` is canonical regardless of how the caller
    // spaced or doubled the separators.
    val canonicalPath = segments.joinToString("/")

    return Insert(here = "afield.graphquery.boundLens($reference, ${kotlinStringLiteral(canonicalPath)})")
}

/** Split an argument list on top-level commas, respecting nesting and string literals. */
internal fun splitTopLevelArguments(text: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false

    text.forEach { c ->
        when {
            inString -> {
                current.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            }
            c == '"' -> {
                inString = true
                current.append(c)
            }
            c == '(' || c == '[' || c == '{' || c == '<' -> {
                depth++
                current.append(c)
            }
            c == ')' || c == ']' || c == '}' || c == '>' -> {
                depth--
                current.append(c)
            }
            c == ',' && depth == 0 -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(c)
        }
    }
    if (current.isNotBlank()) parts += current.toString()
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Read a plain `"..."` literal, or null when the text is anything else — an interpolated
 * template, a concatenation, a variable. Only a literal can be checked, so only a literal
 * is accepted.
 */
internal fun parseSimpleStringLiteral(text: String): String? {
    if (text.length < 2 || !text.startsWith("\"") || !text.endsWith("\"")) return null
    val body = text.substring(1, text.length - 1)
    if (body.contains("\"")) return null
    // `$` would be interpolation, which cannot be resolved at expansion time.
    if (body.contains("$")) return null
    return body
}

internal fun isKotlinIdentifier(text: String): Boolean =
    text.isNotEmpty() &&
            (text[0].isLetter() || text[0] == '_') &&
            text.all { it.isLetterOrDigit() || it == '_' }

private fun kotlinStringLiteral(text: String): String = buildString(text.length + 2) {
    append('"')
    text.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '$' -> append("\\$")
            else -> append(c)
        }
    }
    append('"')
}
