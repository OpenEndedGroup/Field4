package afield.stdlib

import java.util.IdentityHashMap

/** A named, programmatic rewrite rule over one occurrence in an [Functions.Fn] graph. */
data class FnGraphRule(
    val name: String,
    val matches: (FnGraphCopyContext) -> Boolean,
    val replace: (context: FnGraphCopyContext, copy: Functions.Fn) -> Functions.Fn
)

/** Whether one rewrite pass uses the first matching rule or chains every matching rule. */
enum class FnGraphRuleMode {
    FIRST_MATCH,
    ALL_MATCHES
}

/** Why a bounded graph-rewrite run stopped. */
enum class FnGraphRewriteStop {
    ONE_PASS,
    STABLE,
    CYCLE,
    MAX_PASSES
}

/** One effective rule application. Paths are child indices from the graph root. */
data class FnGraphRewriteEvent(
    val pass: Int,
    val rule: String,
    val path: List<Int>,
    val role: String?,
    val beforeOp: String,
    val afterOp: String,
    val beforeName: String?,
    val afterName: String?
)

/** Result and provenance from [Functions.Fn.rewriteWithRules] or [Functions.Fn.rewriteUntilStable]. */
data class FnGraphRewriteRun(
    val function: Functions.Fn,
    val events: List<FnGraphRewriteEvent>,
    val passes: Int,
    val stop: FnGraphRewriteStop
) {
    val stable: Boolean get() = stop == FnGraphRewriteStop.STABLE
    val changed: Boolean get() = events.isNotEmpty()
}

/** A matched graph occurrence. Shared nodes may appear more than once through different [context] paths. */
data class FnGraphMatch(
    val function: Functions.Fn,
    val context: FnGraphCopyContext
)

/** Small structural measurements suitable for diagnostics and optimizer cost functions. */
data class FnGraphMetrics(
    val uniqueNodes: Int,
    val occurrences: Int,
    val edges: Int,
    val maxDepth: Int,
    val opaqueNodes: Int,
    val rebuildableNodes: Int,
    val cost: Double
)

/**
 * Finds occurrence-sensitive matches without copying or evaluating the function.
 *
 * Unlike [Functions.Fn.graphSnapshot], this deliberately visits a shared node once
 * per incoming path. That makes ancestry and edge-role queries useful for editing.
 */
fun Functions.Fn.graphMatches(
    maxDepth: Int = 64,
    predicate: (FnGraphCopyContext) -> Boolean
): List<FnGraphMatch> {
    require(maxDepth >= 0) { "maxDepth must be non-negative" }
    val matches = mutableListOf<FnGraphMatch>()

    fun visit(
        fn: Functions.Fn,
        path: List<Int>,
        role: String?,
        parent: FnGraphNode?,
        ancestors: List<FnGraphPathElement>
    ) {
        val node = fn.graphNode()
        val context = FnGraphCopyContext(fn, node, path, path.size, role, parent, ancestors)
        if (predicate(context)) matches += FnGraphMatch(fn, context)
        if (path.size >= maxDepth) return

        val current = FnGraphPathElement(node, role, path.lastOrNull() ?: -1, fn.name)
        node.children.forEachIndexed { index, edge ->
            visit(edge.fn, path + index, edge.role, node, ancestors + current)
        }
    }

    visit(this, emptyList(), null, null, emptyList())
    return matches
}

/** Returns the first occurrence-sensitive match in depth-first order. */
fun Functions.Fn.firstGraphMatch(
    maxDepth: Int = 64,
    predicate: (FnGraphCopyContext) -> Boolean
): FnGraphMatch? = graphMatches(maxDepth, predicate).firstOrNull()

/**
 * Measures both unique DAG nodes and path-sensitive occurrences.
 *
 * [nodeCost] is charged once per unique node. It can encode a future execution,
 * allocation, visual-complexity, or aesthetic cost without changing the rewriter.
 */
fun Functions.Fn.graphMetrics(
    maxDepth: Int = 64,
    nodeCost: (FnGraphNode) -> Double = { 1.0 }
): FnGraphMetrics {
    require(maxDepth >= 0) { "maxDepth must be non-negative" }
    val unique = IdentityHashMap<Functions.Fn, Boolean>()
    var occurrences = 0
    var edges = 0
    var deepest = 0
    var opaque = 0
    var rebuildable = 0
    var cost = 0.0

    fun visit(fn: Functions.Fn, depth: Int) {
        occurrences++
        deepest = maxOf(deepest, depth)
        val node = fn.graphNode()
        if (unique.put(fn, true) == null) {
            if (node.rebuild == null) opaque++ else rebuildable++
            cost += nodeCost(node)
        }
        if (depth >= maxDepth) return
        edges += node.children.size
        node.children.forEach { visit(it.fn, depth + 1) }
    }

    visit(this, 0)
    return FnGraphMetrics(unique.size, occurrences, edges, deepest, opaque, rebuildable, cost)
}

/**
 * A deterministic structural signature for convergence checks and regression tests.
 * Evaluator closures and rebuild lambdas are intentionally excluded.
 */
fun Functions.Fn.graphSignature(maxDepth: Int = 64): String {
    require(maxDepth >= 0) { "maxDepth must be non-negative" }
    val ids = IdentityHashMap<Functions.Fn, Int>()
    val out = StringBuilder()

    fun appendMap(values: Map<String, *>): String = values.entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" }

    fun visit(fn: Functions.Fn, depth: Int) {
        ids[fn]?.let {
            out.append('@').append(it)
            return
        }
        val id = ids.size
        ids[fn] = id
        val node = fn.graphNode()
        out.append('(').append(id).append(':').append(node.op)
            .append("|name=").append(fn.name)
            .append("|domain=").append(fn.domain.min).append("..").append(fn.domain.max)
            .append("|n=").append(appendMap(node.numbers))
            .append("|s=").append(appendMap(node.strings))
            .append("|b=").append(appendMap(node.booleans))
            .append("|l=").append(appendMap(node.longs))
            .append("|t=").append(node.tags.sorted().joinToString(","))
        if (depth < maxDepth) {
            node.children.forEach { edge ->
                out.append('|').append(edge.role).append('=')
                visit(edge.fn, depth + 1)
            }
        } else if (node.children.isNotEmpty()) {
            out.append("|...")
        }
        out.append(')')
    }

    visit(this, 0)
    return out.toString()
}

/** Applies one bottom-up pass of named rules and records every effective edit. */
fun Functions.Fn.rewriteWithRules(
    rules: List<FnGraphRule>,
    preserveSharing: Boolean = false,
    mode: FnGraphRuleMode = FnGraphRuleMode.FIRST_MATCH
): FnGraphRewriteRun = rewriteRulePass(rules, preserveSharing, mode, pass = 1).let { (function, events) ->
    FnGraphRewriteRun(
        function = function,
        events = events,
        passes = 1,
        stop = if (events.isEmpty()) FnGraphRewriteStop.STABLE else FnGraphRewriteStop.ONE_PASS
    )
}

/** Vararg convenience for a single bottom-up rule pass. */
fun Functions.Fn.rewriteWithRules(
    vararg rules: FnGraphRule,
    preserveSharing: Boolean = false,
    mode: FnGraphRuleMode = FnGraphRuleMode.FIRST_MATCH
): FnGraphRewriteRun = rewriteWithRules(rules.toList(), preserveSharing, mode)

/**
 * Repeats bottom-up rule passes until structure is stable, cycles, or reaches [maxPasses].
 * This is intentionally bounded: experimental rewrite systems should fail visibly,
 * not hide a non-terminating rule set inside an animation loop.
 */
fun Functions.Fn.rewriteUntilStable(
    rules: List<FnGraphRule>,
    maxPasses: Int = 16,
    preserveSharing: Boolean = false,
    mode: FnGraphRuleMode = FnGraphRuleMode.FIRST_MATCH
): FnGraphRewriteRun {
    require(maxPasses > 0) { "maxPasses must be positive" }
    var current = this
    val events = mutableListOf<FnGraphRewriteEvent>()
    val seen = linkedSetOf(current.graphSignature())

    for (pass in 1..maxPasses) {
        val (next, passEvents) = current.rewriteRulePass(rules, preserveSharing, mode, pass)
        events += passEvents
        val signature = next.graphSignature()
        if (signature == current.graphSignature()) {
            return FnGraphRewriteRun(next, events, pass, FnGraphRewriteStop.STABLE)
        }
        if (!seen.add(signature)) {
            return FnGraphRewriteRun(next, events, pass, FnGraphRewriteStop.CYCLE)
        }
        current = next
    }
    return FnGraphRewriteRun(current, events, maxPasses, FnGraphRewriteStop.MAX_PASSES)
}

/** Vararg convenience for bounded fixed-point rewriting. */
fun Functions.Fn.rewriteUntilStable(
    vararg rules: FnGraphRule,
    maxPasses: Int = 16,
    preserveSharing: Boolean = false,
    mode: FnGraphRuleMode = FnGraphRuleMode.FIRST_MATCH
): FnGraphRewriteRun = rewriteUntilStable(rules.toList(), maxPasses, preserveSharing, mode)

private fun Functions.Fn.rewriteRulePass(
    rules: List<FnGraphRule>,
    preserveSharing: Boolean,
    mode: FnGraphRuleMode,
    pass: Int
): Pair<Functions.Fn, List<FnGraphRewriteEvent>> {
    val events = mutableListOf<FnGraphRewriteEvent>()
    val rewritten = rewriteGraph(preserveSharing) { context, copy ->
        var current = copy
        for (rule in rules) {
            if (!rule.matches(context)) continue
            val before = current
            val beforeSignature = before.graphSignature()
            val after = rule.replace(context, before)
            current = after
            if (after.graphSignature() != beforeSignature) {
                events += FnGraphRewriteEvent(
                    pass = pass,
                    rule = rule.name,
                    path = context.path,
                    role = context.role,
                    beforeOp = before.graphNode().op,
                    afterOp = after.graphNode().op,
                    beforeName = before.name,
                    afterName = after.name
                )
            }
            if (mode == FnGraphRuleMode.FIRST_MATCH) break
        }
        current
    }
    return rewritten to events
}
