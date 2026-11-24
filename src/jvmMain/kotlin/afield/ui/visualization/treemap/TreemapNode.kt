/**
 * # Treemap trees
 *
 * The data a [Treemap] draws: a tree of [TreemapNode]s, each with a stable [TreemapNode.key], a name, a
 * weight and whatever payload the caller wants back when it is clicked. A leaf's area is its [TreemapNode.weight].
 * A branch's area is its own weight plus everything beneath it, recomputed on every layout, so a tree can be
 * edited in place between displays.
 *
 * Trees come from paths, `"src/afield/Box.kt" to 1200`, with the branches made as they are named ([treemapOf],
 * [toTreemap], [TreemapNode.put]); from the builder ([treemapTree]); from any object with children
 * ([treemapTreeOf]); from nested maps and lists ([treemapTreeOfMap]); or from a directory on disk
 * ([treemapTreeOfFiles]).
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
 * unless they have their own, its descendants. [category] and [measure] are what [TreemapColors.byCategory] and
 * [TreemapColors.byValue] colour by when they are not told otherwise; a treemap whose leaves carry either offers
 * them in its "Colour by" switch.
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

    // Children by name, built the first time a name is looked up, so building a tree by paths stays linear however
    // many children a branch has. Siblings may share a name; the index holds the first.
    private var byName: HashMap<String, TreemapNode>? = null

    /** This node's children, in their drawing order before any sort. */
    val children: List<TreemapNode> get() = kids

    /** The category [TreemapColors.byCategory] colours this leaf by, unless it is given another rule. */
    var category: String? = null

    /** The number [TreemapColors.byValue] colours this leaf by, unless it is given another rule; its [value] when null. */
    var measure: Double? = null

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
        child.parent?.remove(child)
        child.parent = this
        kids.add(child)
        byName?.putIfAbsent(child.name, child)
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
        byName?.let { index -> if (index[child.name] === child) kids.firstOrNull { it.name == child.name }?.let { index[child.name] = it } ?: index.remove(child.name) }
        return true
    }

    /** Remove every child. */
    fun clear() {
        kids.forEach { it.parent = null }
        kids.clear()
        byName = null
    }

    /** The direct child called [name], if there is one. */
    fun child(name: String): TreemapNode? {
        if (kids.size < 8) return kids.firstOrNull { it.name == name }
        val index = byName ?: HashMap<String, TreemapNode>(kids.size * 2).also { m -> kids.forEach { m.putIfAbsent(it.name, it) }; byName = m }
        return index[name]
    }

    /**
     * The branch at [path] beneath this node, `"src/afield/ui"`, making each one that does not exist yet.
     * Empty segments are skipped, so `"/a//b/"` is `"a/b"`; an empty path is this node.
     */
    fun at(path: String, separator: Char = '/'): TreemapNode = at(splitPath(path, separator))

    /** The node at [path], one name per segment, making each one that does not exist yet. */
    fun at(path: List<String>): TreemapNode {
        var n = this
        for (segment in path) n = n.child(segment) ?: n.add(TreemapNode(segment))
        return n
    }

    /** The node at [path] beneath this node, or null; it makes nothing. */
    operator fun get(path: String): TreemapNode? = get(splitPath(path, '/'))

    /** The node at [path], one name per segment, or null; it makes nothing. */
    operator fun get(path: List<String>): TreemapNode? {
        var n = this
        for (segment in path) n = n.child(segment) ?: return null
        return n
    }

    /** `tree["src/afield/Box.kt"] = 1200`: [put] without the extras. */
    operator fun set(path: String, weight: Number) {
        put(path, weight)
    }

    /** `tree += "src/afield/Box.kt" to 1200`: [add] without the extras, so repeating a path adds to it. */
    operator fun plusAssign(entry: Pair<String, Number>) {
        add(entry.first, entry.second)
    }

    /**
     * Set the size of the node at [path] to [weight], making it and the branches above it if they do not exist,
     * and return it. [data], [color], [category] and [measure] are set on it when given.
     *
     * Paths may run through one another: `"a/b"` and `"a/b/c"` make `b` a branch with its own weight, which counts
     * toward its size but is not drawn as a cell of its own.
     */
    fun put(
        path: String,
        weight: Number,
        data: Any? = null,
        color: Vec4? = null,
        category: String? = null,
        measure: Double? = null,
        separator: Char = '/',
    ): TreemapNode = put(splitPath(path, separator), weight, data, color, category, measure)

    /** [put], with the path given one name per segment, for names that may themselves contain the separator. */
    fun put(
        path: List<String>,
        weight: Number,
        data: Any? = null,
        color: Vec4? = null,
        category: String? = null,
        measure: Double? = null,
    ): TreemapNode = at(path).also { it.weight = weight.toDouble(); it.annotate(data, color, category, measure) }

    /**
     * Add [weight] to the node at [path], making it and the branches above it if they do not exist, and return it;
     * so adding the same path twice sums the two. [data], [color], [category] and [measure] are set when given.
     *
     * ```kotlin
     * val byCity = treemapTree { people.forEach { add("${it.country}/${it.city}", 1) } }
     * ```
     */
    fun add(
        path: String,
        weight: Number,
        data: Any? = null,
        color: Vec4? = null,
        category: String? = null,
        measure: Double? = null,
        separator: Char = '/',
    ): TreemapNode = add(splitPath(path, separator), weight, data, color, category, measure)

    /** [add], with the path given one name per segment, for names that may themselves contain the separator. */
    fun add(
        path: List<String>,
        weight: Number,
        data: Any? = null,
        color: Vec4? = null,
        category: String? = null,
        measure: Double? = null,
    ): TreemapNode = at(path).also { it.weight += weight.toDouble(); it.annotate(data, color, category, measure) }

    private fun annotate(data: Any?, color: Vec4?, category: String?, measure: Double?) {
        if (data != null) this.data = data
        if (color != null) this.color = color
        if (category != null) this.category = category
        if (measure != null) this.measure = measure
    }

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
 * A treemap tree from paths and sizes, the branches made as the paths name them:
 *
 * ```kotlin
 * val tree = treemapOf("foo/bar/gux" to 0.3, "foo/bar/qux" to 0.5, "foo/zap" to 1.2)
 * ```
 *
 * A path given twice is summed.
 */
fun treemapOf(vararg entries: Pair<String, Number>, name: String = "root", separator: Char = '/'): TreemapNode =
    entries.asIterable().toTreemap(name, separator)

/** A treemap tree from a map of paths to sizes, the branches made as the paths name them. */
fun Map<String, Number>.toTreemap(name: String = "root", separator: Char = '/'): TreemapNode =
    TreemapNode(name).also { root -> forEach { (path, w) -> root.add(path, w, separator = separator) } }

/** A treemap tree from pairs of path and size, the branches made as the paths name them; a repeated path is summed. */
@JvmName("toTreemapOfPairs")
fun Iterable<Pair<String, Number>>.toTreemap(name: String = "root", separator: Char = '/'): TreemapNode =
    TreemapNode(name).also { root -> forEach { (path, w) -> root.add(path, w, separator = separator) } }

/**
 * A treemap tree made in one pass over a collection: [entry] gives each item's path and size (or null to leave it
 * out), and the branches are made as the paths name them.
 *
 * ```kotlin
 * val tree = files.toTreemap(category = { it.extension }) { it.path to it.length() }
 * val census = people.toTreemap(measure = { it.age.toDouble() }) { "${it.country}/${it.city}" to 1 }
 * ```
 *
 * Items that land on the same path are summed, which makes this a group-by as well as a map. A leaf's
 * [TreemapNode.data] is its item, or the list of its items when more than one landed there, so a click gets back
 * what made the cell. [color], [category] and [measure] are read from each item and set on its leaf (the last
 * item's wins where several share one); a leaf with a [TreemapNode.category] or [TreemapNode.measure] is what the
 * "Colour by" switch offers.
 */
fun <T> Iterable<T>.toTreemap(
    name: String = "root",
    separator: Char = '/',
    color: ((T) -> Vec4?)? = null,
    category: ((T) -> String?)? = null,
    measure: ((T) -> Double?)? = null,
    entry: (T) -> Pair<String, Number>?,
): TreemapNode = asSequence().toTreemap(name, separator, color, category, measure, entry)

/** [Iterable.toTreemap] over a sequence, read once. */
fun <T> Sequence<T>.toTreemap(
    name: String = "root",
    separator: Char = '/',
    color: ((T) -> Vec4?)? = null,
    category: ((T) -> String?)? = null,
    measure: ((T) -> Double?)? = null,
    entry: (T) -> Pair<String, Number>?,
): TreemapNode {
    val root = TreemapNode(name)
    // The leaves this build gave a list of items, so a caller's own list held as data is never appended to.
    val gathered = java.util.IdentityHashMap<TreemapNode, MutableList<T>>()
    for (item in this) {
        val (path, w) = entry(item) ?: continue
        val leaf = root.add(path, w, null, color?.invoke(item), category?.invoke(item), measure?.invoke(item), separator)
        val items = gathered[leaf]
        when {
            items != null -> items.add(item)
            leaf.data == null -> leaf.data = item
            else -> gathered[leaf] = mutableListOf(@Suppress("UNCHECKED_CAST") (leaf.data as T), item).also { leaf.data = it }
        }
    }
    return root
}

private fun splitPath(path: String, separator: Char): List<String> = path.split(separator).filter { it.isNotEmpty() }

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
