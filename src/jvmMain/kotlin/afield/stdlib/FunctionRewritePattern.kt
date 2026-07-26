@file:afield.CompletionCategory("Function")

package afield.stdlib

import kotlin.math.abs

/**
 * Structural pattern → template rewriting: the term-rewriting surface on top of
 * the graph copier.
 *
 * The string DSL in [Functions.Fn.rewrite] is *selector => action*: it edits one
 * matched node in place. This is the algebraic sibling — *pattern => template* —
 * where both sides are graph shapes with metavariables. It is what a future
 * optimizer or evolving-function system rewrites with:
 *
 * ```
 * add(?x, 0)       => ?x            // additive identity
 * multiply(?x, 1)  => ?x            // multiplicative identity
 * multiply(?x, 0)  => 0            // annihilation
 * multiply(?a, ?b) => multiply(?b, ?a)   // commute
 * sin              => cos           // transmute the matched node, keeping its children
 * ```
 *
 * A metavariable `?x` binds a whole subtree; repeated names must bind structurally
 * equal subtrees (compared via [graphSignature]). A bare number matches a numeric
 * node (const/param) with that value. A bare op name matches by op (respecting the
 * [OpRegistry] alias table); `op(a, b, …)` additionally requires the leading child
 * subgraphs to match, leaving any trailing children free.
 *
 * [fnRule] compiles a spec into a plain [FnGraphRule], so pattern rules inherit the
 * existing traced, bounded, cycle-detecting machinery ([Functions.Fn.rewriteWithRules],
 * [Functions.Fn.rewriteUntilStable]) for free. For an RHS the string form cannot
 * express, use the programmatic overload with a Kotlin builder over the bindings.
 */
sealed interface FnPattern {
    /** `?name` — binds (or, if already bound, must structurally re-match) a subtree. */
    data class Var(val name: String) : FnPattern

    /** `_` — matches anything without binding. */
    data object Wild : FnPattern

    /** A numeric literal — matches a `numbers["value"]` node (const/param). */
    data class Num(val value: Double) : FnPattern

    /** `op` (children unconstrained) or `op(child, …)` (leading children must match). */
    data class Op(val op: String, val children: List<FnPattern>, val constrained: Boolean) : FnPattern

    companion object {
        fun parse(text: String): FnPattern = FnPatternParser(text).parseWhole()
    }
}

/** The result of matching a pattern: metavariable bindings plus the matched node. */
class FnPatternMatch internal constructor(
    val matched: Functions.Fn,
    private val bindings: Map<String, Functions.Fn>
) {
    /** The subtree bound to `?name`. */
    fun sub(name: String): Functions.Fn =
        bindings[name] ?: error("Pattern metavariable ?$name is not bound")

    /** The numeric value of `?name`, if it bound a numeric node. */
    fun value(name: String): Double? = bindings[name]?.graphNode()?.numbers?.get("value")

    fun has(name: String): Boolean = name in bindings
}

/** Compile `"lhs => rhs"` into a traced [FnGraphRule] usable with the existing rewrite runners. */
fun fnRule(spec: String): FnGraphRule {
    val (lhsText, rhsText) = splitRuleArrow(spec)
    val lhs = FnPattern.parse(lhsText)
    val rhs = FnPattern.parse(rhsText)
    return FnGraphRule(
        name = spec.trim(),
        matches = { context -> matchFnPattern(lhs, context.original, HashMap()) },
        replace = { _, copy ->
            val bindings = HashMap<String, Functions.Fn>()
            if (matchFnPattern(lhs, copy, bindings)) buildFnTemplate(rhs, bindings, copy) else copy
        }
    )
}

/**
 * Compile a pattern LHS with a Kotlin builder RHS. This is the fully general
 * surface: the pattern captures structure, the builder writes the replacement in
 * ordinary Kotlin over the whole [Functions] stdlib.
 */
fun fnRule(name: String, lhs: String, build: Functions.(FnPatternMatch) -> Functions.Fn): FnGraphRule {
    val pattern = FnPattern.parse(lhs)
    return FnGraphRule(
        name = name,
        matches = { context -> matchFnPattern(pattern, context.original, HashMap()) },
        replace = { _, copy ->
            val bindings = HashMap<String, Functions.Fn>()
            if (matchFnPattern(pattern, copy, bindings)) with(Functions) { build(FnPatternMatch(copy, bindings)) } else copy
        }
    )
}

/** Convenience: rewrite to a fixed point with a set of pattern rules. */
fun Functions.Fn.rewritePatterns(
    vararg specs: String,
    maxPasses: Int = 16,
    preserveSharing: Boolean = false
): FnGraphRewriteRun = rewriteUntilStable(specs.map(::fnRule), maxPasses, preserveSharing)

// ---- matching ----

private fun matchFnPattern(
    pattern: FnPattern,
    fn: Functions.Fn,
    bindings: MutableMap<String, Functions.Fn>
): Boolean = when (pattern) {
    is FnPattern.Wild -> true

    is FnPattern.Var -> {
        val existing = bindings[pattern.name]
        if (existing == null) {
            bindings[pattern.name] = fn
            true
        } else {
            existing.graphSignature() == fn.graphSignature()
        }
    }

    is FnPattern.Num -> {
        val value = fn.graphNode().numbers["value"]
        value != null && abs(value - pattern.value) < 1e-12
    }

    is FnPattern.Op -> {
        val node = fn.graphNode()
        if (!opNamesMatch(pattern.op, node.op)) {
            false
        } else if (!pattern.constrained) {
            true
        } else {
            pattern.children.withIndex().all { (index, childPattern) ->
                val child = node.children.getOrNull(index)?.fn
                child != null && matchFnPattern(childPattern, child, bindings)
            }
        }
    }
}

private fun opNamesMatch(patternOp: String, nodeOp: String): Boolean {
    if (patternOp.equals(nodeOp, ignoreCase = true)) return true
    val a = OpRegistry.canonical(patternOp) ?: patternOp
    val b = OpRegistry.canonical(nodeOp) ?: nodeOp
    return a.equals(b, ignoreCase = true)
}

// ---- template building ----

private val ALGEBRA_OPS = setOf("add", "subtract", "multiply", "divide", "+", "-", "*", "/")

private fun buildFnTemplate(
    template: FnPattern,
    bindings: Map<String, Functions.Fn>,
    matched: Functions.Fn
): Functions.Fn = when (template) {
    is FnPattern.Wild -> matched

    is FnPattern.Var ->
        bindings[template.name] ?: error("Template references unbound metavariable ?${template.name}")

    is FnPattern.Num -> Functions.c(template.value, matched.domain)

    is FnPattern.Op -> when {
        // bare op (no child list): transmute the matched node, preserving its children.
        !template.constrained -> matched.rebuildAsOp(template.op) ?: matched

        // algebra ops build directly from two child templates.
        OpRegistry.canonical(template.op)?.lowercase() in ALGEBRA_OPS || template.op.lowercase() in ALGEBRA_OPS -> {
            require(template.children.size == 2) { "Algebra op `${template.op}` needs two operands in a template" }
            val left = buildFnTemplate(template.children[0], bindings, matched)
            val right = buildFnTemplate(template.children[1], bindings, matched)
            val canonical = OpRegistry.canonical(template.op)?.lowercase() ?: template.op.lowercase()
            with(Functions) {
                when (canonical) {
                    "add", "+" -> left + right
                    "subtract", "-" -> left - right
                    "multiply", "*" -> left * right
                    "divide", "/" -> left / right
                    else -> error("unreachable")
                }
            }
        }

        else -> error(
            "Template op `${template.op}(...)` with explicit children is limited to algebra " +
                "(add/subtract/multiply/divide) in the string DSL. Use a bare op to transmute the " +
                "matched node, or the programmatic fnRule(name, lhs) { ... } builder for full control."
        )
    }
}

// ---- parsing ----

private fun splitRuleArrow(spec: String): Pair<String, String> {
    val trimmed = spec.trim().removePrefix("#fnrewrite").trim()
    val arrows = listOf("=>", "->", "→")
    var index = -1
    var length = 0
    for (arrow in arrows) {
        val at = trimmed.indexOf(arrow)
        if (at >= 0 && (index < 0 || at < index)) {
            index = at
            length = arrow.length
        }
    }
    require(index >= 0) { "Pattern rule needs `lhs => rhs`: `$spec`" }
    val lhs = trimmed.substring(0, index).trim()
    val rhs = trimmed.substring(index + length).trim()
    require(lhs.isNotEmpty()) { "Pattern rule has an empty left-hand side: `$spec`" }
    require(rhs.isNotEmpty()) { "Pattern rule has an empty right-hand side: `$spec`" }
    return lhs to rhs
}

private class FnPatternParser(text: String) {
    private val s = text.trim()
    private var i = 0

    fun parseWhole(): FnPattern {
        val term = parseTerm()
        skipWs()
        require(i >= s.length) { "Unexpected trailing input in pattern `$s` at $i" }
        return term
    }

    private fun parseTerm(): FnPattern {
        skipWs()
        require(i < s.length) { "Empty pattern term in `$s`" }
        val c = s[i]
        if (c == '?') {
            i++
            val name = readIdent()
            require(name.isNotEmpty()) { "Metavariable needs a name in `$s`" }
            return FnPattern.Var(name)
        }
        if (c == '_' && !isIdentPart(s.getOrNull(i + 1))) {
            i++
            return FnPattern.Wild
        }
        if (c == '-' || c == '+' || c == '.' || c.isDigit()) {
            readNumber()?.let { return FnPattern.Num(it) }
        }
        val op = readIdentOrSymbol()
        require(op.isNotEmpty()) { "Expected an op name or metavariable in `$s` at $i" }
        skipWs()
        if (i < s.length && s[i] == '(') {
            i++ // consume (
            val children = mutableListOf<FnPattern>()
            skipWs()
            if (i < s.length && s[i] != ')') {
                while (true) {
                    children += parseTerm()
                    skipWs()
                    if (i < s.length && s[i] == ',') {
                        i++
                        continue
                    }
                    break
                }
            }
            skipWs()
            require(i < s.length && s[i] == ')') { "Unbalanced parentheses in pattern `$s`" }
            i++ // consume )
            return FnPattern.Op(op, children, constrained = true)
        }
        return FnPattern.Op(op, emptyList(), constrained = false)
    }

    private fun readNumber(): Double? {
        val start = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        var digits = false
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) {
            digits = true
            i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && s[i].isDigit()) i++
        }
        if (!digits) {
            i = start
            return null
        }
        val token = s.substring(start, i)
        val parsed = token.toDoubleOrNull()
        if (parsed == null) {
            i = start
            return null
        }
        return parsed
    }

    private fun readIdent(): String {
        val start = i
        while (i < s.length && isIdentPart(s[i])) i++
        return s.substring(start, i)
    }

    private fun readIdentOrSymbol(): String {
        skipWs()
        if (i < s.length && s[i] in "+-*/") {
            return s[i].toString().also { i++ }
        }
        return readIdent()
    }

    private fun isIdentPart(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }
}
