@file:afield.CompletionCategory("Function")

package afield.stdlib

import afield.graphics.FLine
import afield.graphics.FLineMap
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * The data model and the copy engine underneath Field's graph-rewritable notion of a function:
 * [FnGraphNode] / [FnGraphEdge] (an op name, role-tagged children, scalar metadata and an
 * optional rebuild recipe) plus [Functions.Fn.deepCopy] and [Functions.Fn.rewriteGraph], a
 * bottom-up copy that offers every occurrence to an [FnGraphEdit]. Every other file in this
 * family runs on that single traversal, as do the edit primitives declared here —
 * [Functions.Fn.mapGraphNumbers], [Functions.Fn.replaceOps], [Functions.Fn.pruneWhere],
 * [Functions.Fn.graftWhere], [Functions.Fn.duplicateWhere], [Functions.Fn.slideCopy]. The
 * read-only renderings live here too: [Functions.Fn.graphSnapshot], [Functions.Fn.graphText],
 * [Functions.Fn.graphSvg] and [Functions.Fn.graphFLineMap].
 *
 * Two constraints show up everywhere downstream. A node whose `rebuild` is null cannot be
 * reconstructed, so a copy keeps the original evaluator and drops the recipe. And
 * `preserveSharing` decides whether a shared subgraph stays shared (memoized by identity) or is
 * expanded into a tree — [Functions.Fn.deepCopy] defaults it to true, while the rewriting entry
 * points default it to false so each occurrence can be edited independently.
 */

/**
 * Structural metadata for [Functions.Fn].
 *
 * The evaluator closure remains the authoritative implementation of a function;
 * this graph is a best-effort recipe attached alongside it. Nodes that know how
 * to rebuild themselves can participate fully in deep-copy and rewrite passes.
 * Nodes that cannot rebuild safely are still inspectable, but copy as opaque
 * evaluators so we do not pretend to understand arbitrary user lambdas.
 */
data class FnGraphEdge(
    val role: String,
    val fn: Functions.Fn
)

/**
 * A node's recipe for putting itself back together: given the children in role order, return the equivalent [Functions.Fn].
 *
 * The presence of one is the difference between a node a rewrite can *change* and one it can only copy. Every op the
 * stdlib emits supplies one; a hand-written lambda wrapped as an `Fn` cannot, and copies as an opaque evaluator instead.
 */
typealias FnGraphRebuilder = (List<Functions.Fn>) -> Functions.Fn

/**
 * One node of the graph hanging off a [Functions.Fn]: which op it is, its role-tagged children, its scalar metadata, and
 * — if it can — how to rebuild itself.
 *
 * The structure a rewrite reads and writes. [op] is the name from `OpRegistry` and what rules match on; [label] is for
 * display only. Children are addressed **by role** (`"source"`, `"frequency"`, …) rather than by position, which is what
 * lets a transmutation move a frequency into a period slot. The four scalar maps carry the node's own parameters, and
 * [tags] carries classifications like `"number"` or `"opaque"` that the renderers and rules test.
 *
 * A null [rebuild] means this node cannot be reconstructed: it stays inspectable, but a copy keeps the original evaluator
 * and drops the recipe, so nothing downstream can edit *inside* it.
 */
data class FnGraphNode(
    val op: String,
    val label: String = op,
    val children: List<FnGraphEdge> = emptyList(),
    val numbers: Map<String, Double> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val booleans: Map<String, Boolean> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val tags: Set<String> = emptySet(),
    val rebuild: FnGraphRebuilder? = null
) {
    /** The child in this role, or null if there is none — the role-addressed accessor rules use. First match wins. */
    fun child(role: String): Functions.Fn? = children.firstOrNull { it.role == role }?.fn
}

/**
 * One step on the path from the root down to a node: the node, the role it was reached by, its index among its siblings,
 * and its `Fn`'s name if it had one.
 *
 * These make up [FnGraphCopyContext.occurrence], so a rewrite predicate can ask about *where* a node sits — "only the
 * frequency of a sine that is itself inside a sum" — rather than only what it is.
 */
data class FnGraphPathElement(
    val node: FnGraphNode,
    val role: String?,
    val index: Int,
    val name: String? = null
) {
    /** The op at this step of the path. */
    val op: String get() = node.op
}

/**
 * Everything a rewrite is told about the node it is currently being offered: the original `Fn`, its graph node, where it
 * sits in the tree, and its immediate parent and full ancestry.
 *
 * The argument to every [FnGraphEdit]. Because the copy is **bottom-up**, the children of this node have already been
 * copied (and possibly rewritten) by the time you see it, while the ancestors described here are the *originals*.
 *
 * The convenience accessors are what predicates are usually written against: [op] and [parentOp] for the shape,
 * [name] and [domain] from the original function, and [occurrence] for the whole path when position matters.
 */
data class FnGraphCopyContext(
    val original: Functions.Fn,
    val node: FnGraphNode,
    val path: List<Int>,
    val depth: Int,
    val role: String? = null,
    val parent: FnGraphNode? = null,
    val ancestors: List<FnGraphPathElement> = emptyList()
) {
    /** The op name of the node being offered — the first thing most predicates test. */
    val op: String get() = node.op

    /** The original function's name, if it had one. Null for the great majority of nodes. */
    val name: String? get() = original.name

    /** The original function's domain, which the replacement is usually expected to match. */
    val domain: Functions.Domain get() = original.domain

    /** The op of the immediate parent, or null at the root — "am I inside a sum?" in one property. */
    val parentOp: String? get() = parent?.op

    /**
     * The whole path from the root to this node inclusive, as [FnGraphPathElement]s — the addressable identity of *this
     * occurrence*, as opposed to this op in general.
     *
     * Rebuilt on each read, so bind it to a local rather than calling it repeatedly in a predicate.
     */
    val occurrence: List<FnGraphPathElement> get() =
        ancestors + FnGraphPathElement(node, role, path.lastOrNull() ?: -1, name)
}

/**
 * The rewrite hook: for each node of the graph, look at [FnGraphCopyContext] and either return a replacement or leave the
 * copy alone.
 *
 * A `fun interface`, so it is written as a lambda. Return the `copy` argument (or null, which means the same thing) to
 * keep the node as it is; return anything else to substitute it — including a subtree of a completely different shape.
 * The children of the node you are handed have **already** been visited, so replacing it discards whatever was rewritten
 * inside it.
 *
 * Called once per *occurrence* by default, since the rewriting entry points turn sharing off; with `preserveSharing = true`
 * a shared subgraph is visited once and the same replacement is reused everywhere it appeared.
 */
fun interface FnGraphEdit {
    /** Return a replacement for this node, or `copy`/null to keep it. */
    fun edit(context: FnGraphCopyContext, copy: Functions.Fn): Functions.Fn?
}

/**
 * One node in a flattened, read-only [FnGraphSnapshot]: an integer id and the node's op, label, name, domain, scalars and
 * tags, with no reference back to the live `Fn`.
 *
 * Plain data, so it survives being serialised, diffed or handed to a browser — which is the point of taking a snapshot
 * rather than walking the graph.
 */
data class FnGraphSnapshotNode(
    val id: Int,
    val op: String,
    val label: String,
    val name: String?,
    val domain: Functions.Domain,
    val numbers: Map<String, Double>,
    val strings: Map<String, String>,
    val booleans: Map<String, Boolean>,
    val longs: Map<String, Long>,
    val tags: Set<String>
)

/** A parent-to-child link in an [FnGraphSnapshot], by node id, carrying the role the child was addressed by. */
data class FnGraphSnapshotEdge(
    val from: Int,
    val to: Int,
    val role: String
)

/**
 * A flattened, `Fn`-free picture of a graph: a node list, an edge list and the root's id.
 *
 * A **graph, not a tree** — a subgraph reached twice appears once with two edges into it, because identity is what is
 * de-duplicated. That is the honest shape of an `Fn` built with shared sub-expressions, and it is why this is the right
 * thing to serialise or diff, where `graphText` and `graphSvg` re-expand and mark repeats instead.
 */
data class FnGraphSnapshot(
    val nodes: List<FnGraphSnapshotNode>,
    val edges: List<FnGraphSnapshotEdge>,
    val root: Int
)

/**
 * This function's graph node — or a synthetic `"opaque"` node, tagged `opaque`, when it has no graph at all.
 *
 * Never null, which is what lets every traversal in this family be written without a special case. An opaque node has no
 * children and no rebuild recipe, so a rewrite can replace it wholesale but cannot see inside it.
 */
fun Functions.Fn.graphNode(): FnGraphNode =
    graph ?: FnGraphNode(
        op = "opaque",
        label = name ?: "opaque",
        tags = setOf("opaque")
    )

/**
 * Rebuilds this function from its graph, bottom-up, offering every node to [edit] on the way — the single traversal every
 * other rewrite in this family is written in terms of.
 *
 * With the default no-op edit it is a genuine deep copy: nothing is shared with the original, so mutating one leaves the
 * other alone. Nodes whose graph carries no `rebuild` recipe are copied as **opaque evaluators wrapping the original**,
 * which means they still compute the same thing but their internals are gone from the copy.
 *
 * [preserveSharing] defaults to true here — a subgraph reached twice is copied once and the result reused, keeping the
 * copy's structure (and its evaluation cost) the same as the original's. Turn it off, as [rewriteGraph] does, when each
 * occurrence should be editable independently; the copy then expands into a tree and can be much larger.
 */
fun Functions.Fn.deepCopy(
    preserveSharing: Boolean = true,
    edit: FnGraphEdit = FnGraphEdit { _, copy -> copy }
): Functions.Fn {
    val memo = IdentityHashMap<Functions.Fn, Functions.Fn>()

    fun copyRec(
        fn: Functions.Fn,
        path: List<Int>,
        role: String?,
        parent: FnGraphNode?,
        ancestors: List<FnGraphPathElement>
    ): Functions.Fn {
        if (preserveSharing) memo[fn]?.let { return it }

        val node = fn.graphNode()
        val currentElement = FnGraphPathElement(node, role, path.lastOrNull() ?: -1, fn.name)
        val copiedChildren = node.children.mapIndexed { index, edge ->
            copyRec(edge.fn, path + index, edge.role, node, ancestors + currentElement)
        }

        val rebuilt = node.rebuild?.invoke(copiedChildren) ?: Functions.Fn(
            fn.domain,
            fn.name,
            evalFn = { x -> fn.eval(x) },
            graph = node.copy(
                children = node.children.zip(copiedChildren) { edge, child -> edge.copy(fn = child) },
                rebuild = null
            )
        )

        val context = FnGraphCopyContext(fn, node, path, path.size, role, parent, ancestors)
        val edited = edit.edit(context, rebuilt) ?: rebuilt
        if (preserveSharing) memo[fn] = edited
        return edited
    }

    return copyRec(this, emptyList(), null, null, emptyList())
}

/**
 * [deepCopy] under the name that says what you are doing, and with sharing **off** by default so each occurrence of a
 * repeated subgraph is offered to [edit] separately and can be changed independently.
 *
 * The general rewriting entry point, and what all the specific ones below call. Returns a new function; the original is
 * untouched.
 */
fun Functions.Fn.rewriteGraph(
    preserveSharing: Boolean = false,
    edit: FnGraphEdit
): Functions.Fn = deepCopy(preserveSharing = preserveSharing, edit = edit)

/**
 * Rewrites the *numbers* in the graph: every node carrying a scalar under the key `"value"` — which is how a constant is
 * represented — is replaced by a constant holding whatever [transform] returns.
 *
 * The "make everything 10% faster" pass. Only the key `"value"` is considered, so a node's other named parameters (a
 * frequency, a phase) are **not** visited by this; and a node whose transform returns the value unchanged is left exactly
 * as it was rather than rebuilt. The replacement is a plain constant, so any name is carried over but any structure the
 * node had is not.
 */
fun Functions.Fn.mapGraphNumbers(
    preserveSharing: Boolean = false,
    transform: (value: Double, context: FnGraphCopyContext, key: String) -> Double
): Functions.Fn = rewriteGraph(preserveSharing) { context, copy ->
    val key = context.node.numbers.keys.firstOrNull { it == "value" } ?: return@rewriteGraph copy
    val current = context.node.numbers[key] ?: return@rewriteGraph copy
    val next = transform(current, context, key)
    if (next == current) copy else Functions.c(next, copy.domain).withName(copy.name)
}

/**
 * Transmutes ops in place: every node whose op is a key of [replacements] is rebuilt as the corresponding op, reusing its
 * role-addressed children and its scalar parameters.
 *
 * "Make every sine a square" without rebuilding the expression by hand. The mapping is applied **once per node**, not
 * repeatedly, so `mapOf("sin" to "cos", "cos" to "sin")` really does swap them rather than looping.
 *
 * `OpRegistry` decides what is a viable transmutation, including the cross-family role bridging (frequency ↔ period). A
 * node the registry refuses is **left unchanged and silently**, so a typo'd target op is a no-op rather than an error —
 * compare the graph before and after if you need to know it took.
 */
fun Functions.Fn.replaceOps(
    replacements: Map<String, String>,
    preserveSharing: Boolean = false
): Functions.Fn = rewriteGraph(preserveSharing) { context, copy ->
    val target = replacements[context.op] ?: return@rewriteGraph copy
    copy.rebuildAsOp(target) ?: copy
}

/** [replaceOps] for a single op. Same quiet no-op if the transmutation is not viable. */
fun Functions.Fn.replaceOp(
    from: String,
    to: String,
    preserveSharing: Boolean = false
): Functions.Fn = replaceOps(mapOf(from to to), preserveSharing)

/**
 * Cuts every node matching [predicate] out of the graph, putting [replacement] in its place — zero, by default.
 *
 * Ablation: "what does this expression sound like without the noise term". Because the traversal is bottom-up and the
 * replacement discards the copied subtree, pruning a node removes **everything below it**, whether or not those
 * descendants also matched.
 *
 * The default replacement is `Functions.zero` over the pruned node's own domain, which is the identity for a sum but not
 * for a product — pass a `replacement` of one if you are ablating a factor.
 */
fun Functions.Fn.pruneWhere(
    preserveSharing: Boolean = false,
    replacement: (FnGraphCopyContext) -> Functions.Fn = { Functions.zero(it.domain) },
    predicate: (FnGraphCopyContext) -> Boolean
): Functions.Fn = rewriteGraph(preserveSharing) { context, copy ->
    if (predicate(context)) replacement(context) else copy
}

/**
 * Substitutes [graft] for every node matching [predicate] — transplanting one expression into another wherever it fits.
 *
 * Each match gets its **own copy** of the graft (a `deepCopy` with sharing preserved inside it), so the results are
 * independent and a later rewrite can change one without changing the others. As with [pruneWhere], the matched node's
 * whole subtree is discarded, and a graft whose domain differs from the node's is inserted as-is rather than adapted.
 */
fun Functions.Fn.graftWhere(
    graft: Functions.Fn,
    preserveSharing: Boolean = false,
    predicate: (FnGraphCopyContext) -> Boolean
): Functions.Fn = rewriteGraph(preserveSharing) { context, copy ->
    if (predicate(context)) graft.deepCopy(preserveSharing = true) else copy
}

/**
 * At every node matching [predicate], makes a second copy of that subtree, passes it through [duplicate], and combines the
 * two with [merge].
 *
 * The "echo this part of the expression" primitive: `duplicate` is where you offset, detune or delay the copy, and `merge`
 * is what puts the pair back together — a sum for a chorus, a max for a union. Both halves are fully independent
 * expressions, so a subsequent rewrite treats them separately.
 *
 * The duplicate is taken from the already-copied node, so it includes any rewriting that happened below it in this same
 * pass.
 */
fun Functions.Fn.duplicateWhere(
    preserveSharing: Boolean = false,
    duplicate: (Functions.Fn) -> Functions.Fn,
    merge: (original: Functions.Fn, duplicated: Functions.Fn) -> Functions.Fn,
    predicate: (FnGraphCopyContext) -> Boolean
): Functions.Fn = rewriteGraph(preserveSharing) { context, copy ->
    if (predicate(context)) merge(copy, duplicate(copy.deepCopy())) else copy
}

/**
 * This function plus a shifted copy of itself: `f + f.shift(amount)` — the one-line canon or echo.
 *
 * [amount] is a shift along the input, in the same units as the domain, so a positive value delays. The copy is a real
 * deep copy, so the two halves can be rewritten apart afterwards. Note that this **sums**, so the result's amplitude is
 * roughly doubled where the two overlap.
 */
fun Functions.Fn.slideCopy(amount: Number, preserveSharing: Boolean = true): Functions.Fn =
    with(Functions) { this@slideCopy + this@slideCopy.deepCopy(preserveSharing).shift(amount) }

/**
 * A flattened, serialisable [FnGraphSnapshot] of this graph — ids, ops, metadata and role-labelled edges, with no live
 * `Fn` references.
 *
 * De-duplicated **by identity**, so a shared subgraph appears once with several edges into it: the snapshot is the graph as
 * it really is, which is what makes it the right input for diffing two versions of an expression.
 *
 * [maxDepth] stops the descent, but the node at the limit is still emitted — just without its children — so a truncated
 * snapshot looks like a leaf rather than announcing itself. Depth is per path, not per node.
 */
fun Functions.Fn.graphSnapshot(maxDepth: Int = 32): FnGraphSnapshot {
    val ids = IdentityHashMap<Functions.Fn, Int>()
    val nodes = mutableListOf<FnGraphSnapshotNode>()
    val edges = mutableListOf<FnGraphSnapshotEdge>()

    fun visit(fn: Functions.Fn, depth: Int): Int {
        ids[fn]?.let { return it }
        val id = ids.size
        ids[fn] = id
        val node = fn.graphNode()
        nodes += FnGraphSnapshotNode(
            id = id,
            op = node.op,
            label = node.label,
            name = fn.name,
            domain = fn.domain,
            numbers = node.numbers,
            strings = node.strings,
            booleans = node.booleans,
            longs = node.longs,
            tags = node.tags
        )
        if (depth < maxDepth) {
            node.children.forEach { edge ->
                val to = visit(edge.fn, depth + 1)
                edges += FnGraphSnapshotEdge(id, to, edge.role)
            }
        }
        return id
    }

    val root = visit(this, 0)
    return FnGraphSnapshot(nodes, edges, root)
}

/**
 * The graph as indented text — one line per node with its op, name, domain and scalars — for printing into a box output.
 *
 * The quickest way to see what an expression is actually made of. Each node gets a `#n` id the first time it is seen, and a
 * repeat of a shared subgraph prints as `ref #n` rather than being expanded again, so you can tell sharing from
 * duplication at a glance. A path deeper than [maxDepth] ends in `...`.
 */
fun Functions.Fn.graphText(maxDepth: Int = 12): String {
    val seen = IdentityHashMap<Functions.Fn, Int>()
    var nextId = 0

    fun lineFor(fn: Functions.Fn): String {
        val node = fn.graphNode()
        val numberText = node.numbers.entries.joinToString(", ") { "${it.key}=${prettyNumber(it.value)}" }
        val nameText = fn.name?.let { " name=$it" } ?: ""
        val numberSuffix = if (numberText.isEmpty()) "" else " {$numberText}"
        return "${node.op}$nameText domain=${fn.domain}$numberSuffix"
    }

    fun walk(fn: Functions.Fn, depth: Int, prefix: String, out: StringBuilder) {
        val id = seen[fn]
        if (id != null) {
            out.append(prefix).append("ref #").append(id).append(" ").append(lineFor(fn)).append('\n')
            return
        }
        val assigned = nextId++
        seen[fn] = assigned
        out.append(prefix).append("#").append(assigned).append(" ").append(lineFor(fn)).append('\n')
        if (depth >= maxDepth) {
            out.append(prefix).append("  ...\n")
            return
        }
        fn.graphNode().children.forEachIndexed { index, edge ->
            out.append(prefix).append("  ").append(edge.role).append(":\n")
            walk(edge.fn, depth + 1, "$prefix    ", out)
            if (index == fn.graphNode().children.lastIndex) Unit
        }
    }

    return buildString { walk(this@graphText, 0, "", this) }
}

/**
 * The graph drawn as a dark-themed SVG: one card per node, laid out left-to-right by depth, with curved edges and — by
 * default — a **sparkline of what that sub-expression actually evaluates to** behind each card.
 *
 * The sparkline is the reason to prefer this over [graphText]: you see the shape produced at each point in the graph, not
 * just its name. Each node is sampled 56 times across its own domain, or the unit interval when that domain is unbounded;
 * constants and unevaluable nodes simply get no sparkline, and an exception during sampling is swallowed as a gap in the
 * line rather than propagated.
 *
 * A repeated subgraph is **expanded again** here (outlined more brightly to mark it), unlike [graphSnapshot], so this is a
 * tree view of a graph and a heavily shared expression can get wide. The returned SVG grows past [width] rather than
 * clipping. Set `sparklines = false` when the evaluation cost matters — it is one full evaluation per node per call.
 */
fun Functions.Fn.graphSvg(
    width: Int = 960,
    nodeWidth: Double = 168.0,
    nodeHeight: Double = 54.0,
    xGap: Double = 215.0,
    yGap: Double = 84.0,
    maxDepth: Int = 10,
    sparklines: Boolean = true
): String {
    data class Tree(
        val fn: Functions.Fn,
        val edgeRole: String?,
        val depth: Int,
        val children: List<Tree>,
        val repeated: Boolean = false,
        var x: Double = 0.0,
        var y: Double = 0.0
    )

    val active = IdentityHashMap<Functions.Fn, Boolean>()
    val encountered = IdentityHashMap<Functions.Fn, Boolean>()

    fun build(fn: Functions.Fn, role: String?, depth: Int): Tree {
        val repeated = encountered[fn] == true || active[fn] == true
        encountered[fn] = true
        if (depth >= maxDepth || repeated) return Tree(fn, role, depth, emptyList(), repeated)
        active[fn] = true
        val children = fn.graphNode().children.map { build(it.fn, it.role, depth + 1) }
        active.remove(fn)
        return Tree(fn, role, depth, children)
    }

    val root = build(this, null, 0)
    var leaf = 0
    fun layout(tree: Tree): Double {
        tree.x = 28.0 + tree.depth * xGap
        if (tree.children.isEmpty()) {
            tree.y = 28.0 + leaf++ * yGap
        } else {
            val ys = tree.children.map { layout(it) }
            tree.y = (ys.first() + ys.last()) * 0.5
        }
        return tree.y
    }
    layout(root)

    val all = mutableListOf<Tree>()
    fun collect(tree: Tree) {
        all += tree
        tree.children.forEach(::collect)
    }
    collect(root)
    val height = max(160, (all.maxOfOrNull { it.y } ?: 0.0).toInt() + 92)
    val totalWidth = max(width, (all.maxOfOrNull { it.x } ?: 0.0).toInt() + nodeWidth.toInt() + 40)

    fun esc(s: String): String = buildString(s.length) {
        s.forEach {
            append(
                when (it) {
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '&' -> "&amp;"
                    '"' -> "&quot;"
                    else -> it
                }
            )
        }
    }

    val fieldCyan = "#48c9ff"
    val fieldOrange = "#ffb85a"

    fun technicalName(tree: Tree): String? {
        val node = tree.fn.graphNode()
        if (node.op != "name") return null
        val name = tree.fn.name ?: node.label
        if (name in setOf("param", "const", "opaque")) return name
        val sourceOp = tree.children.singleOrNull { it.edgeRole == "source" }?.fn?.graphNode()?.op
        return name.takeIf { it == sourceOp || it.matches(Regex("[a-z][A-Za-z0-9_]*")) }
    }

    fun displayOp(tree: Tree): String {
        val node = tree.fn.graphNode()
        return technicalName(tree) ?: node.op
    }

    fun isTypeOp(op: String, node: FnGraphNode): Boolean =
        op in setOf("param", "const", "opaque") || "number" in node.tags

    fun humanName(tree: Tree): String? {
        val node = tree.fn.graphNode()
        if (node.op != "name" || technicalName(tree) != null) return null
        return tree.fn.name
            ?.takeIf { it.isNotBlank() }
    }

    fun tspan(text: String, fill: String, weight: String = "400", style: String? = null): String {
        val styleAttr = style?.let { """ font-style="$it"""" } ?: ""
        return """<tspan fill="$fill" font-weight="$weight"$styleAttr>${esc(text)}</tspan>"""
    }

    fun separatorTspan(): String =
        """<tspan fill="#686868" font-weight="400"> &#8212; </tspan>"""

    fun domainMarkup(domain: Functions.Domain): String {
        val text = when {
            domain._isEmpty -> "empty"
            domain == Functions.Domain.ALL -> null
            domain == Functions.Domain.DEFAULT -> "0.0 .. 1.0"
            domain.min == domain.max -> prettyNumber(domain.min)
            else -> "${prettyNumber(domain.min)} .. ${prettyNumber(domain.max)}"
        }
        return if (text == null) {
            """<tspan fill="#9d9d9d">&#8734;</tspan>"""
        } else {
            """<tspan fill="#9d9d9d">${esc(text)}</tspan>"""
        }
    }

    fun detailMarkup(fn: Functions.Fn): String {
        val node = fn.graphNode()
        if (node.numbers.isEmpty()) return domainMarkup(fn.domain)
        return node.numbers.entries.take(2).joinToString("""<tspan fill="#9d9d9d"> </tspan>""") { entry ->
            """<tspan fill="#9d9d9d">${esc(entry.key)}=</tspan><tspan fill="$fieldOrange">${esc(prettyNumber(entry.value))}</tspan>"""
        }
    }

    fun mainLabel(tree: Tree): String {
        val node = tree.fn.graphNode()
        val op = displayOp(tree)
        val human = humanName(tree)
        val head = when {
            human != null -> tspan("\"$human\"", "#dddddd", style = "italic")
            isTypeOp(op, node) -> tspan(op, "#a8a8a8")
            else -> tspan(op, fieldCyan, weight = "700")
        }
        val role = tree.edgeRole?.takeIf { it.isNotBlank() }
        return buildString {
            if (role != null) {
                append(tspan(role, "#dddddd", style = "italic"))
                append(separatorTspan())
                append(head)
            } else {
                append(head)
            }
        }
    }

    val fieldSpark = "#5bd6c0"

    // A miniature plot of the sub-expression rooted at this node, drawn as a faint
    // waveform behind the card's labels. We evaluate the node (operator + all of its
    // parameters) across its own domain — or the unit interval when the domain is
    // unbounded — so the card shows the shape produced *at that point* in the graph.
    // Constants carry no shape worth plotting, so they are skipped.
    fun sparklineMarkup(tree: Tree): String {
        if (!sparklines) return ""
        val node = tree.fn.graphNode()
        if ("number" in node.tags || node.op == "const") return ""

        val domain = tree.fn.domain
        val sampleDomain = if (domain.isFinite) domain else Functions.Domain.UNIT
        if (sampleDomain._isEmpty) return ""

        val sampleCount = 56
        val values = DoubleArray(sampleCount)
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        var finite = 0
        for (i in 0 until sampleCount) {
            val alpha = i.toDouble() / (sampleCount - 1)
            val x = sampleDomain.min + sampleDomain.length * alpha
            val v = try { tree.fn.eval(x) } catch (_: Throwable) { Double.NaN }
            values[i] = v
            if (v.isFinite()) {
                lo = min(lo, v)
                hi = max(hi, v)
                finite++
            }
        }
        if (finite < 2) return ""

        val padX = 10.0
        val padY = 7.0
        val x0 = tree.x + padX
        val bandW = nodeWidth - padX * 2.0
        val yTop = tree.y + padY
        val bandH = nodeHeight - padY * 2.0
        val range = hi - lo

        fun px(i: Int): Double = x0 + bandW * (i.toDouble() / (sampleCount - 1))
        fun py(v: Double): Double {
            val t = if (range <= 1e-12) 0.5 else ((v - lo) / range).coerceIn(0.0, 1.0)
            return yTop + bandH * (1.0 - t)
        }

        val path = StringBuilder()
        var pen = false
        for (i in 0 until sampleCount) {
            val v = values[i]
            if (!v.isFinite()) { pen = false; continue }
            path.append(if (pen) "L " else "M ").append(px(i)).append(' ').append(py(v)).append(' ')
            pen = true
        }

        return buildString {
            if (lo < 0.0 && hi > 0.0) {
                val zy = py(0.0)
                append("""<line x1="$x0" y1="$zy" x2="${x0 + bandW}" y2="$zy" stroke="$fieldSpark" stroke-width="0.6" stroke-opacity="0.16" stroke-dasharray="2 3"/>""")
            }
            append("""<path d="${path.toString().trim()}" fill="none" stroke="$fieldSpark" stroke-width="1.2" stroke-opacity="0.4" stroke-linejoin="round" stroke-linecap="round"/>""")
        }
    }

    val edges = StringBuilder()
    val nodeMarkup = StringBuilder()
    fun emit(tree: Tree) {
        tree.children.forEach { child ->
            val x1 = tree.x + nodeWidth
            val y1 = tree.y + nodeHeight * 0.5
            val x2 = child.x
            val y2 = child.y + nodeHeight * 0.5
            val mid = (x1 + x2) * 0.5
            edges.append("""<path d="M $x1 $y1 C $mid $y1 $mid $y2 $x2 $y2" fill="none" stroke="#555" stroke-width="1.2"/>""")
            emit(child)
        }
        val node = tree.fn.graphNode()
        val stroke = if (tree.repeated) "#b8b8b8" else "#707070"
        val fill = when {
            "number" in node.tags -> "#151515"
            "opaque" in node.tags -> "#1f1f1f"
            else -> "#101010"
        }
        val labelBaseline = tree.y + 19.0
        val detailBaseline = tree.y + nodeHeight - 10.0
        nodeMarkup.append("""<rect x="${tree.x}" y="${tree.y}" width="$nodeWidth" height="$nodeHeight" rx="6" fill="$fill" stroke="$stroke" stroke-width="1.2"/>""")
        nodeMarkup.append(sparklineMarkup(tree))
        nodeMarkup.append("""<text x="${tree.x + 12.0}" y="$labelBaseline" font-size="13">${mainLabel(tree)}</text>""")
        nodeMarkup.append("""<text x="${tree.x + 12.0}" y="$detailBaseline" font-size="11">${detailMarkup(tree.fn)}</text>""")
    }
    emit(root)

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="$totalWidth" height="$height" viewBox="0 0 $totalWidth $height" style="background:#050505;color:#f2f2f2;font-family:Iosevka, Menlo, monospace">
          <rect width="100%" height="100%" fill="#050505"/>
          ${edges}
          ${nodeMarkup}
        </svg>
    """.trimIndent()
}

/**
 * The graph as an [FLineMap] of drawable boxes and connectors — the same tree layout as [graphSvg], but as Field geometry
 * you can restyle, animate or hit-test.
 *
 * Keys are meaningful and stable within a call: `"node_<n>_<op>"` and `"edge_<n>"`, so a box can recolour just the sines,
 * or fade one edge. Structure only — no labels, no sparklines, no repeat marking; reach for [graphSvg] when you want the
 * annotated picture and this when you want to draw it yourself.
 *
 * Beyond [maxDepth] a node is emitted without its children. Shared subgraphs are expanded each time they appear, so the
 * node count can exceed the number of distinct nodes.
 */
fun Functions.Fn.graphFLineMap(
    nodeWidth: Double = 168.0,
    nodeHeight: Double = 54.0,
    xGap: Double = 215.0,
    yGap: Double = 84.0,
    maxDepth: Int = 10
): FLineMap {
    data class Tree(
        val fn: Functions.Fn,
        val depth: Int,
        val children: List<Tree>,
        var x: Double = 0.0,
        var y: Double = 0.0
    )

    fun build(fn: Functions.Fn, depth: Int): Tree =
        if (depth >= maxDepth) Tree(fn, depth, emptyList())
        else Tree(fn, depth, fn.graphNode().children.map { build(it.fn, depth + 1) })

    val root = build(this, 0)
    var leaf = 0
    fun layout(tree: Tree): Double {
        tree.x = tree.depth * xGap
        tree.y = if (tree.children.isEmpty()) leaf++ * yGap else {
            val ys = tree.children.map { layout(it) }
            (ys.first() + ys.last()) * 0.5
        }
        return tree.y
    }
    layout(root)

    val map = FLineMap()
    var edgeIndex = 0
    var nodeIndex = 0
    fun emit(tree: Tree) {
        tree.children.forEach { child ->
            map += "edge_${edgeIndex++}" to fline {
                moveTo(tree.x + nodeWidth, tree.y + nodeHeight * 0.5)
                lineTo(child.x, child.y + nodeHeight * 0.5)
                color = vec(0.42, 0.42, 0.42, 1.0)
                thickness = 1.0
            }
            emit(child)
        }
        map += "node_${nodeIndex++}_${tree.fn.graphNode().op}" to fline {
            rect(tree.x, tree.y, nodeWidth, nodeHeight)
            color = vec(0.88, 0.88, 0.88, 1.0)
            thickness = 1.0
        }
    }
    emit(root)
    return map
}

/**
 * Rebuild this node as [targetOp], reusing role-addressed children and scalar
 * metadata. The full transmutation vocabulary and its cross-family role bridging
 * (e.g. frequency ↔ period) live in [OpRegistry], the single source of truth.
 * Returns null when the target is not a viable transmutation of this node.
 */
internal fun Functions.Fn.rebuildAsOp(targetOp: String): Functions.Fn? =
    OpRegistry.rebuild(this, targetOp)
