package afield.graphics.text.sentences

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.linalg.Vec4
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Dependency length over whole texts: how far apart, in words, each word sits from the word it
 * depends on, summed over a sentence, measured against reorderings of the same tree.
 *
 * The measurement and its baselines follow Futrell, Mahowald and Gibson, "Large-scale evidence of
 * dependency length minimization in 37 languages", PNAS 112(33):10336–10341 (2015),
 * doi:10.1073/pnas.1502134112. One arc's length is the number of words from head to dependent,
 * counting the dependent (|i − j| in word positions); a sentence's total sums every arc except the
 * root's, with punctuation removed first. The baselines keep the tree and change only the order:
 *
 *  - [Linearization.freeRandom]: the paper's free word order baseline, a random projective
 *    linearisation (Gildea and Temperley's procedure: at each head, shuffle the head and its
 *    dependents, then recurse);
 *  - [Linearization.fixedOrder]: the fixed word order baseline, one random weight in [−1, 1] per
 *    relation type for the sentence, the head weighted 0, every head's group sorted by weight;
 *  - [Linearization.fixedHeadPosition]: the supplement's fixed head position baseline, each
 *    dependent kept on the side of its head where it was observed, the order within a side
 *    shuffled;
 *  - [Linearization.optimal]: the shortest projective order of the tree.
 *
 * The retained parses ([SentenceTexts]) are Stanza's Universal Dependencies trees, which make
 * content words the heads of prepositions and complementizers. The paper normalised its corpora to
 * make prepositions and complementizers heads; it reports the same pattern on content-head trees.
 */

/** One retained, parsed passage: a title, where it came from, and its sentences. */
@Serializable
data class ParsedText(
    val key: String,
    val title: String,
    val source: String = "",
    val parser: String = "",
    val sentences: List<ParsedSentence>,
) {
    /** Every sentence measured; see [DependencyTree.lengths]. Sentences with no arcs left are skipped. */
    fun measure(samples: Int = 100, seed: Long = 1L): List<MeasuredSentence> =
        sentences.mapIndexedNotNull { i, s ->
            val tree = s.tree() ?: return@mapIndexedNotNull null
            if (tree.size < 2) return@mapIndexedNotNull null
            MeasuredSentence(this, i, s, tree, tree.lengths(samples, seed * 1_000_003L + i))
        }
}

/**
 * One parsed sentence as Stanza tagged it: words, universal and Penn tags, and a Universal
 * Dependencies tree given as one 1-based head per word (0 for the root) and its relation.
 */
@Serializable
data class ParsedSentence(
    val text: String,
    val paragraph: Int = 0,
    val words: List<String>,
    val upos: List<String>,
    val xpos: List<String> = emptyList(),
    val heads: List<Int>,
    val rels: List<String>,
) {
    /**
     * The tree with punctuation removed, as the paper measures it. A word attached to a removed
     * punctuation mark is attached to that mark's head instead. Null when the sentence has no root
     * left, or more than one: the paper excludes sentences that are not singly rooted.
     */
    fun tree(dropPunctuation: Boolean = true): DependencyTree? {
        val keep = words.indices.filter { !dropPunctuation || upos[it] != "PUNCT" }
        if (keep.isEmpty()) return null
        val newIndex = IntArray(words.size) { -1 }
        keep.forEachIndexed { k, i -> newIndex[i] = k }
        fun keptHead(i: Int): Int {
            var h = heads[i] - 1
            while (h >= 0 && newIndex[h] < 0) h = heads[h] - 1
            return if (h < 0) -1 else newIndex[h]
        }
        val head = IntArray(keep.size) { keptHead(keep[it]) }
        if (head.count { it < 0 } != 1) return null
        return DependencyTree(
            words = keep.map { words[it] },
            head = head,
            rels = keep.map { rels[it] },
            upos = keep.map { upos[it] },
            xpos = keep.map { xpos.getOrElse(it) { upos[it] } },
        )
    }
}

/** The retained passages that ship with Field, parsed once by `tools/parse_sentence_texts.py`. */
object SentenceTexts {
    /** In page order: speech, novel, novel, verse, scripture. */
    val keys = listOf("gettysburg", "pride_and_prejudice", "moby_dick", "sonnets", "genesis")

    fun load(key: String): ParsedText {
        val source = requireNotNull(javaClass.getResource("/afield/sentences/texts/$key.json")) {
            "no retained text '$key'; the shipped ones are ${keys.joinToString()}"
        }
        return Sentences.json.decodeFromString(ParsedText.serializer(), source.readText())
    }

    fun all(): List<ParsedText> = keys.map { load(it) }
}

/**
 * A dependency tree over [size] words in their observed order. Words are indexed 0 until [size];
 * [head] gives each word's head index, −1 for the root.
 *
 * An order is an `IntArray` listing word indices left to right, so `order[k]` is the word at
 * position k; [observed] is `0, 1, 2, …`.
 */
class DependencyTree(
    val words: List<String>,
    val head: IntArray,
    val rels: List<String> = List(words.size) { "dep" },
    val upos: List<String> = List(words.size) { "" },
    val xpos: List<String> = upos,
) {
    val size: Int get() = words.size
    val root: Int = head.indexOfFirst { it < 0 }

    init {
        require(head.size == words.size && rels.size == words.size) { "words, heads and relations differ in length" }
        require(head.count { it < 0 } == 1) { "a tree needs exactly one root" }
        require(head.all { it < size }) { "a head index is out of range" }
        // Every word reaches the root, so there is no cycle.
        for (i in 0 until size) {
            var h = i
            var steps = 0
            while (head[h] >= 0) {
                h = head[h]
                require(++steps <= size) { "the heads contain a cycle through word $i" }
            }
        }
    }

    /** Each word's dependents, in observed order. */
    val dependents: List<List<Int>> = List(size) { h -> (0 until size).filter { head[it] == h } }

    /** How many words each word's subtree holds, itself included. */
    val subtreeSize: IntArray = IntArray(size).also { s ->
        fun fill(v: Int): Int = (1 + dependents[v].sumOf { fill(it) }).also { s[v] = it }
        fill(root)
    }

    val observed: IntArray get() = IntArray(size) { it }

    /** The (head, dependent) pairs, root excluded. */
    val arcs: List<Pair<Int, Int>> get() = (0 until size).filter { head[it] >= 0 }.map { head[it] to it }

    /** Each word's position in [order]. */
    fun positions(order: IntArray): IntArray {
        require(order.size == size && order.sorted() == (0 until size).toList()) { "not an order of these $size words" }
        return IntArray(size).also { p -> order.forEachIndexed { k, w -> p[w] = k } }
    }

    /** One arc's length in [order]: |position of head − position of dependent|. */
    fun arcLength(dependent: Int, order: IntArray = observed): Int {
        val p = positions(order)
        return abs(p[head[dependent]] - p[dependent])
    }

    /** Total dependency length of [order]: the arc lengths summed, the root's arc excluded. */
    fun length(order: IntArray = observed): Int {
        val p = positions(order)
        return (0 until size).sumOf { if (head[it] < 0) 0 else abs(p[head[it]] - p[it]) }
    }

    /** True when every subtree occupies an unbroken run of positions, i.e. no two arcs cross and none spans the root. */
    fun isProjective(order: IntArray = observed): Boolean {
        val p = positions(order)
        val lo = IntArray(size) { p[it] }
        val hi = IntArray(size) { p[it] }
        fun span(v: Int) {
            for (d in dependents[v]) {
                span(d)
                lo[v] = minOf(lo[v], lo[d]); hi[v] = maxOf(hi[v], hi[d])
            }
        }
        span(root)
        return (0 until size).all { hi[it] - lo[it] + 1 == subtreeSize[it] }
    }

    /**
     * The same tree with its words rearranged into [order], as a [Sentence] that [SentenceDiagram]
     * draws with `d.scheme = Scheme.UD`. The constituency tree is a flat stand-in (every word
     * directly under ROOT), so draw the arcs and words and leave the tree edges out.
     */
    fun sentence(order: IntArray = observed): Sentence {
        val p = positions(order)
        val tokens = order.mapIndexed { k, w -> Token(id = k + 1, word = words[w], pos = xpos[w], upos = upos[w]) }
        val edges = order.mapIndexed { k, w ->
            val gov = if (head[w] < 0) 0 else p[head[w]] + 1
            Edge(
                gov = gov, dep = k + 1, rel = rels[w], base = rels[w].substringBefore(':'), label = rels[w],
                direction = if (gov == 0 || gov > k + 1) "left" else "right", distance = if (gov == 0) 0 else abs(gov - (k + 1)),
            )
        }
        val leaves = tokens.map { Node(id = it.id, label = it.pos ?: "X", leaf = true, depth = 1, word = it.word, token = it.id) }
        return Sentence(
            backend = "retained",
            text = order.joinToString(" ") { words[it] },
            tokens = tokens,
            constituency = Node(id = 0, label = "ROOT", depth = 0, children = leaves),
            treeDepth = 1,
            dependencies = Dependencies(ud = edges),
        )
    }

    /**
     * The tree as arcs over a row of word marks, for small multiples: word k at x = k × [spacing]
     * from [at], each arc a half-ellipse hanging [depth] × its width below the row, a dot per word,
     * and a short stroke up from the root. Coordinates are y-down, like the rest of the package, so
     * the arcs hang towards +y.
     */
    fun arcLines(
        order: IntArray = observed,
        at: P = P(0.0, 0.0),
        spacing: Double = 8.0,
        depth: Double = 0.5,
        dotRadius: Double = 1.2,
        ink: Vec4? = null,
        width: Double? = null,
    ): List<FLine> {
        val p = positions(order)
        val out = mutableListOf<FLine>()
        val y = at.y
        fun x(word: Int) = at.x + p[word] * spacing
        for ((h, d) in arcs) {
            val a = minOf(x(h), x(d))
            val b = maxOf(x(h), x(d))
            val dy = (b - a) * depth
            val k = 0.5522847498
            val mid = (a + b) / 2
            val r = (b - a) / 2
            out += FLine().moveTo(a, y)
                .cubicTo(a, y + dy * k, mid - r * k, y + dy, mid, y + dy)
                .cubicTo(mid + r * k, y + dy, b, y + dy * k, b, y)
        }
        out += segment(P(x(root), y), P(x(root), y - 3 * dotRadius))
        for (w in 0 until size) out += dot(P(x(w), y), dotRadius)
        out.forEach { f ->
            ink?.let { f.color = it }
            width?.let { f.thickness = it }
        }
        return out
    }

    /** How far [arcLines] hangs below its row for [order]: the widest arc's depth. */
    fun arcDepth(order: IntArray = observed, spacing: Double = 8.0, depth: Double = 0.5): Double {
        val p = positions(order)
        return arcs.maxOfOrNull { (h, d) -> abs(p[h] - p[d]) * spacing * depth } ?: 0.0
    }
}

/** [text] as filled glyph outlines, its baseline starting at [at]: a label for a figure drawn with `lines()`. */
fun outlinedText(text: String, at: P, font: java.awt.Font): FLine =
    TextItem(text, at, kind = TextKind.WORD).outlineFLine(font)

/** Ways of putting a tree's words in a line, each projective by construction. */
object Linearization {
    /** The paper's free word order baseline: at every head, the head and its dependents in random order. */
    fun freeRandom(tree: DependencyTree, rng: java.util.Random): IntArray =
        build(tree) { v, deps -> (deps + v).shuffled(rng) }

    /**
     * The paper's fixed word order baseline: each relation type gets one weight in [−1, 1], the
     * head 0, and every head's group is sorted by weight. Dependents sharing a relation, which the
     * weights cannot separate, are shuffled.
     */
    fun fixedOrder(tree: DependencyTree, weights: Map<String, Double>, rng: java.util.Random): IntArray =
        build(tree) { v, deps ->
            (deps + v).shuffled(rng).sortedBy { if (it == v) 0.0 else weights.getValue(tree.rels[it]) }
        }

    /** One random weight in [−1, 1] for every relation type in [tree]. */
    fun randomWeights(tree: DependencyTree, rng: java.util.Random): Map<String, Double> =
        tree.rels.distinct().sorted().associateWith { rng.nextDouble() * 2 - 1 }

    /** The fixed head position baseline: dependents stay on their observed side of the head, shuffled within it. */
    fun fixedHeadPosition(tree: DependencyTree, rng: java.util.Random): IntArray =
        build(tree) { v, deps ->
            deps.filter { it < v }.shuffled(rng) + v + deps.filter { it > v }.shuffled(rng)
        }

    /**
     * The shortest projective order (Gildea and Temperley 2007). At each head the dependents go
     * out on alternating sides from largest subtree to smallest, so the smallest sit nearest the
     * head; the largest goes on the side away from the head's own head, which keeps that arc short.
     */
    fun optimal(tree: DependencyTree): IntArray {
        val out = ArrayList<Int>(tree.size)
        // parentOnLeft is null for the root, which has no parent arc to keep short.
        fun place(v: Int, parentOnLeft: Boolean?) {
            val bySize = tree.dependents[v].sortedWith(compareByDescending<Int> { tree.subtreeSize[it] }.thenBy { it })
            val farIsRight = parentOnLeft == true
            val left = ArrayList<Int>()  // outermost first
            val right = ArrayList<Int>() // outermost first
            bySize.forEachIndexed { k, d ->
                val onRight = if (k % 2 == 0) farIsRight else !farIsRight
                (if (onRight) right else left) += d
            }
            for (d in left) place(d, parentOnLeft = false)
            out += v
            for (d in right.asReversed()) place(d, parentOnLeft = true)
        }
        place(tree.root, null)
        return out.toIntArray()
    }

    private fun build(tree: DependencyTree, arrange: (Int, List<Int>) -> List<Int>): IntArray {
        val out = ArrayList<Int>(tree.size)
        fun visit(v: Int) {
            for (u in arrange(v, tree.dependents[v])) if (u == v) out += v else visit(u)
        }
        visit(tree.root)
        return out.toIntArray()
    }
}

/**
 * A sentence's total dependency length beside its baselines: [samples] draws of each random
 * baseline and the optimum. The fixed-order baseline draws fresh relation weights per sample.
 */
class DependencyLengths(
    val observed: Int,
    val free: IntArray,
    val fixedOrder: IntArray,
    val fixedHeadPosition: IntArray,
    val optimal: Int,
) {
    val freeMean: Double get() = free.average()
    val fixedOrderMean: Double get() = fixedOrder.average()
    val fixedHeadPositionMean: Double get() = fixedHeadPosition.average()

    /** Observed over the free random mean: below 1 means shorter than chance. */
    val ratio: Double get() = observed / freeMean

    /** The fraction of free random reorderings at least as short as the observed order. */
    val freeQuantile: Double get() = free.count { it <= observed }.toDouble() / free.size
}

/** Measures this tree's observed order against [samples] of each random baseline, reproducibly from [seed]. */
fun DependencyTree.lengths(samples: Int = 100, seed: Long = 1L): DependencyLengths {
    require(samples > 0) { "samples must be positive" }
    val rng = java.util.Random(seed)
    return DependencyLengths(
        observed = length(),
        free = IntArray(samples) { length(Linearization.freeRandom(this, rng)) },
        fixedOrder = IntArray(samples) {
            length(Linearization.fixedOrder(this, Linearization.randomWeights(this, rng), rng))
        },
        fixedHeadPosition = IntArray(samples) { length(Linearization.fixedHeadPosition(this, rng)) },
        optimal = length(Linearization.optimal(this)),
    )
}

/** A sentence of a [ParsedText] with its tree and lengths. [length] is words without punctuation. */
class MeasuredSentence(
    val text: ParsedText,
    val index: Int,
    val sentence: ParsedSentence,
    val tree: DependencyTree,
    val lengths: DependencyLengths,
) {
    val length: Int get() = tree.size
    val ratio: Double get() = lengths.ratio
    override fun toString() = "${text.key}#$index n=$length observed=${lengths.observed} free≈${"%.1f".format(lengths.freeMean)}"
}
