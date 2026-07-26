@file:afield.CompletionCategory("Function")

package afield.stdlib

import afield.graphics.FLine
import afield.graphics.FLineMap
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

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

typealias FnGraphRebuilder = (List<Functions.Fn>) -> Functions.Fn

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
    fun child(role: String): Functions.Fn? = children.firstOrNull { it.role == role }?.fn
}

data class FnGraphPathElement(
    val node: FnGraphNode,
    val role: String?,
    val index: Int,
    val name: String? = null
) {
    val op: String get() = node.op
}

data class FnGraphCopyContext(
    val original: Functions.Fn,
    val node: FnGraphNode,
    val path: List<Int>,
    val depth: Int,
    val role: String? = null,
    val parent: FnGraphNode? = null,
    val ancestors: List<FnGraphPathElement> = emptyList()
) {
    val op: String get() = node.op
    val name: String? get() = original.name
    val domain: Functions.Domain get() = original.domain
    val parentOp: String? get() = parent?.op
    val occurrence: List<FnGraphPathElement> get() =
        ancestors + FnGraphPathElement(node, role, path.lastOrNull() ?: -1, name)
}

fun interface FnGraphEdit {
    fun edit(context: FnGraphCopyContext, copy: Functions.Fn): Functions.Fn?
}

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

data class FnGraphSnapshotEdge(
    val from: Int,
    val to: Int,
    val role: String
)

data class FnGraphSnapshot(
    val nodes: List<FnGraphSnapshotNode>,
    val edges: List<FnGraphSnapshotEdge>,
    val root: Int
)

fun Functions.Fn.graphNode(): FnGraphNode =
    graph ?: FnGraphNode(
        op = "opaque",
        label = name ?: "opaque",
        tags = setOf("opaque")
    )

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

fun Functions.Fn.rewriteGraph(
    preserveSharing: Boolean = false,
    edit: FnGraphEdit
): Functions.Fn = deepCopy(preserveSharing = preserveSharing, edit = edit)

fun Functions.Fn.mapGraphNumbers(
    preserveSharing: Boolean = false,
    transform: (value: Double, context: FnGraphCopyContext, key: String) -> Double
): Functions.Fn = rewriteGraph(preserveSharing) { context, copy ->
    val key = context.node.numbers.keys.firstOrNull { it == "value" } ?: return@rewriteGraph copy
    val current = context.node.numbers[key] ?: return@rewriteGraph copy
    val next = transform(current, context, key)
    if (next == current) copy else Functions.c(next, copy.domain).withName(copy.name)
}

fun Functions.Fn.replaceOps(
    replacements: Map<String, String>,
    preserveSharing: Boolean = false
): Functions.Fn = rewriteGraph(preserveSharing) { context, copy ->
    val target = replacements[context.op] ?: return@rewriteGraph copy
    copy.rebuildAsOp(target) ?: copy
}

fun Functions.Fn.replaceOp(
    from: String,
    to: String,
    preserveSharing: Boolean = false
): Functions.Fn = replaceOps(mapOf(from to to), preserveSharing)

fun Functions.Fn.pruneWhere(
    preserveSharing: Boolean = false,
    replacement: (FnGraphCopyContext) -> Functions.Fn = { Functions.zero(it.domain) },
    predicate: (FnGraphCopyContext) -> Boolean
): Functions.Fn = rewriteGraph(preserveSharing) { context, copy ->
    if (predicate(context)) replacement(context) else copy
}

fun Functions.Fn.graftWhere(
    graft: Functions.Fn,
    preserveSharing: Boolean = false,
    predicate: (FnGraphCopyContext) -> Boolean
): Functions.Fn = rewriteGraph(preserveSharing) { context, copy ->
    if (predicate(context)) graft.deepCopy(preserveSharing = true) else copy
}

fun Functions.Fn.duplicateWhere(
    preserveSharing: Boolean = false,
    duplicate: (Functions.Fn) -> Functions.Fn,
    merge: (original: Functions.Fn, duplicated: Functions.Fn) -> Functions.Fn,
    predicate: (FnGraphCopyContext) -> Boolean
): Functions.Fn = rewriteGraph(preserveSharing) { context, copy ->
    if (predicate(context)) merge(copy, duplicate(copy.deepCopy())) else copy
}

fun Functions.Fn.slideCopy(amount: Number, preserveSharing: Boolean = true): Functions.Fn =
    with(Functions) { this@slideCopy + this@slideCopy.deepCopy(preserveSharing).shift(amount) }

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
