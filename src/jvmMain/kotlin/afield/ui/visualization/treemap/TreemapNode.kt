/**
 * # Treemap trees
 *
 * The data a [Treemap] draws: a tree of [TreemapNode]s, each with a stable [TreemapNode.key], a name, a
 * weight and whatever payload the caller wants back when it is clicked. A leaf's area is its [TreemapNode.weight].
 * A branch's area is its own weight plus everything beneath it, recomputed on every layout, so a tree can be
 * edited in place between displays.
 *
 * Trees come from the builder ([treemapTree]), from any object with children ([treemapTreeOf]), from nested maps
 * and lists ([treemapTreeOfMap]), or from a directory on disk ([treemapTreeOfFiles]).
 */
package afield.ui.visualization.treemap

import afield.math.linalg.Vec4
import afield.ui.visualization.ToHTML
import java.io.File

/**
 * One rectangle's worth of a treemap: a leaf, or a branch whose rectangle holds its [children].
 *
 * [key] is the node's identity across displays and edits. Two displays that share a key draw the same
 * rectangle, so it morphs rather than jumps, and selection and zoom survive a rebuilt tree. By default it is
 * the path of names from the root, which is unique as long as siblings have distinct names.
 *
 * [weight] is the node's own size and [value] is its size including children; [value] is what the layout reads.
 * [data] is carried untouched for callbacks, and [color] overrides the treemap's colouring for this node and,
 * unless they have their own, its descendants.
 */
class TreemapNode(
    val name: String,
    weight: Double = 0.0,
    var data: Any? = null,
    var color: Vec4? = null,
    key: String? = null,
) : ToHTML {
    private var explicitKey: String? = key

    /** This node's own size; a branch's total also counts its children. */
    var weight: Double = weight
        set(v) {
            require(v.isFinite() && v >= 0.0) { "Treemap weight for '$name' must be finite and >= 0, not $v" }
            field = v
        }

    init {
        require(weight.isFinite() && weight >= 0.0) { "Treemap weight for '$name' must be finite and >= 0, not $weight" }
    }

    /** The node above this one, or null at the root. */
    var parent: TreemapNode? = null
        private set

    private val kids = ArrayList<TreemapNode>()

    /** This node's children, in their drawing order before any sort. */
    val children: List<TreemapNode> get() = kids

    /** The node's identity across displays: the explicit key if one was given, else the path of names from the root. */
    val key: String get() = explicitKey ?: parent?.let { "${it.key}/$name" } ?: name

    /** Its total size, its own [weight] plus its children's values, as of the last [sum] (layout calls it). */
    var value: Double = weight
        internal set

    /** How far below the root this node is; the root is 0. */
    val depth: Int get() = parent?.let { it.depth + 1 } ?: 0

    val isLeaf: Boolean get() = kids.isEmpty()

    /** The root, then each node down to and including this one. */
    val path: List<TreemapNode> get() = generateSequence(this) { it.parent }.toList().asReversed()

    /** Add [child] beneath this node and return it. A child already elsewhere in a tree is moved. */
    fun add(child: TreemapNode): TreemapNode {
        child.parent?.kids?.remove(child)
        child.parent = this
        kids.add(child)
        return child
    }

    /** Add a leaf called [name] of size [weight]. */
    fun leaf(name: String, weight: Double, data: Any? = null, color: Vec4? = null): TreemapNode =
        add(TreemapNode(name, weight, data, color))

    /** Add a branch called [name] and fill it with [block]. */
    fun branch(name: String, data: Any? = null, color: Vec4? = null, block: TreemapNode.() -> Unit = {}): TreemapNode =
        add(TreemapNode(name, 0.0, data, color)).apply(block)

    /** Remove [child] from beneath this node; true if it was there. */
    fun remove(child: TreemapNode): Boolean {
        if (!kids.remove(child)) return false
        child.parent = null
        return true
    }

    /** Remove every child. */
    fun clear() {
        kids.forEach { it.parent = null }
        kids.clear()
    }

    /** The direct child called [name], if there is one. */
    fun child(name: String): TreemapNode? = kids.firstOrNull { it.name == name }

    /** The node beneath this one (or this one) whose [key] is [key]. */
    fun find(key: String): TreemapNode? {
        // Path keys name their ancestors, so follow them down first; explicit keys fall back to a full search.
        var n = this
        while (true) {
            if (n.key == key) return n
            n = n.kids.firstOrNull { c -> c.key.let { key == it || key.startsWith("$it/") } } ?: break
        }
        return walk().firstOrNull { it.key == key }
    }

    /** This node and everything beneath it, parents before children. */
    fun walk(): Sequence<TreemapNode> = sequence {
        val stack = ArrayDeque<TreemapNode>()
        stack.addLast(this@TreemapNode)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            yield(n)
            for (i in n.kids.indices.reversed()) stack.addLast(n.kids[i])
        }
    }

    /** The leaves beneath this node, or this node if it is one. */
    fun leaves(): Sequence<TreemapNode> = walk().filter { it.isLeaf }

    /** True when [other] is this node or lies beneath it. */
    fun contains(other: TreemapNode): Boolean = generateSequence(other) { it.parent }.any { it === this }

    /** Recompute [value] for this node and everything beneath it, and return it. */
    fun sum(): Double {
        // Iterative post-order, so a deep tree (a long chain of directories) cannot overflow the stack.
        val order = walk().toList()
        for (i in order.indices.reversed()) {
            val n = order[i]
            n.value = n.weight + n.kids.sumOf { it.value }
        }
        return value
    }

    /** The number of nodes in this subtree. */
    val size: Int get() = walk().count()

    override fun toString(): String = "TreemapNode($key, value=$value, children=${kids.size})"

    /** Displayed as a value, a tree draws itself as a static treemap; [afield.document.graph.Box.treemap] is the interactive one. */
    override fun toHTML(): String = toTreemapHTML()
}

/**
 * Build a treemap tree with a builder:
 *
 * ```kotlin
 * val tree = treemapTree("budget") {
 *     branch("housing") { leaf("rent", 1400.0); leaf("power", 90.0) }
 *     leaf("food", 600.0)
 * }
 * ```
 */
fun treemapTree(name: String = "root", block: TreemapNode.() -> Unit): TreemapNode = TreemapNode(name).apply(block)

/**
 * A treemap tree mirroring any tree-shaped value: [children] gives each item's children, [weight] its own size
 * (by default 1 for a leaf and 0 for a branch, so every leaf counts the same), and [name] its label. Each node's
 * [TreemapNode.data] is the item it came from. [maxDepth] stops the walk, which also guards against cycles.
 */
fun <T> treemapTreeOf(
    root: T,
    children: (T) -> Iterable<T>,
    weight: (T) -> Double = { 1.0 },
    name: (T) -> String = { it.toString() },
    key: ((T) -> String)? = null,
    maxDepth: Int = 64,
): TreemapNode {
    fun build(item: T, depth: Int): TreemapNode {
        val kids = if (depth < maxDepth) children(item).toList() else emptyList()
        val node = TreemapNode(name(item), if (kids.isEmpty()) weight(item).coerceAtLeast(0.0) else 0.0, item, key = key?.invoke(item))
        kids.forEach { node.add(build(it, depth + 1)) }
        return node
    }
    return build(root, 0)
}

/**
 * A treemap tree from nested maps and lists, the shape JSON and EDN parse to. A number is a leaf of that size,
 * a map is a branch whose entries are its children, and a list is a branch whose children are named by index.
 * Anything else counts 1.
 */
fun treemapTreeOfMap(value: Any?, name: String = "root"): TreemapNode {
    fun build(name: String, v: Any?): TreemapNode = when (v) {
        is Number -> TreemapNode(name, v.toDouble().takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
        is Map<*, *> -> TreemapNode(name, 0.0, v).also { n -> v.forEach { (k, c) -> n.add(build(k.toString(), c)) } }
        is Iterable<*> -> TreemapNode(name, 0.0, v).also { n -> v.forEachIndexed { i, c -> n.add(build("$i", c)) } }
        else -> TreemapNode(name, 1.0, v)
    }
    return build(name, value)
}

/**
 * A treemap tree of the files under [dir], each sized by its length in bytes, branches by directory.
 * [maxDepth] directories deep at most; [include] filters files and directories (by default it skips hidden ones).
 * Each node's [TreemapNode.data] is its [File].
 */
fun treemapTreeOfFiles(
    dir: File,
    maxDepth: Int = 16,
    include: (File) -> Boolean = { !it.name.startsWith(".") },
): TreemapNode {
    fun build(f: File, depth: Int): TreemapNode =
        if (f.isDirectory && depth < maxDepth) {
            val node = TreemapNode(f.name.ifEmpty { f.path }, 0.0, f)
            f.listFiles()?.filter(include)?.sortedBy { it.name }?.forEach { node.add(build(it, depth + 1)) }
            node
        } else TreemapNode(f.name, if (f.isFile) f.length().toDouble() else 0.0, f)
    return build(dir, 0)
}
