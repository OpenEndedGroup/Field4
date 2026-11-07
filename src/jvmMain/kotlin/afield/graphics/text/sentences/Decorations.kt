package afield.graphics.text.sentences

import afield.geometry.path.FLine
import afield.graphics.render.filled
import afield.graphics.render.stroked

/**
 * Hooks for adding your own FLines to a diagram, one per kind of thing it draws.
 *
 * Each hook is handed a [Part] carrying the geometry that was drawn plus everything around it
 * -- the arc's own curve and relation, the words at either end, the whole [Diagram] -- and
 * returns whatever extra lines you want. Return an [FLine], a list of them, or null:
 *
 *     val deco = decorations {
 *         arc { dot(it.pointAt(0.5), 3.0) }                       // a bead at each arc's middle
 *         underline { ring(it.left, 4.0) }                        // a ring at each word's start
 *         text { if (it.kind == TextKind.RELATION) box(it.bounds) else null }
 *         treeEdge { if (it.child.leaf) tick(it.curve, 1.0, 6.0) else null }
 *     }
 *
 *     lines(d.flines(decorations = deco).everything())
 *
 * The returned lines land in [DiagramFLines.decorations] and are drawn last, over everything
 * else. Nothing is inserted into the per-kind lists, so [DiagramFLines.arcs] stays exactly the
 * arcs and you can still treat the parts separately.
 */
class Decorations {
    var arc: ((ArcPart) -> Any?)? = null
    var treeEdge: ((TreeEdgePart) -> Any?)? = null
    var connector: ((ConnectorPart) -> Any?)? = null
    var underline: ((UnderlinePart) -> Any?)? = null
    var node: ((NodePart) -> Any?)? = null
    var text: ((TextPart) -> Any?)? = null

    fun arc(f: (ArcPart) -> Any?) = apply { arc = f }
    fun treeEdge(f: (TreeEdgePart) -> Any?) = apply { treeEdge = f }
    fun connector(f: (ConnectorPart) -> Any?) = apply { connector = f }
    fun underline(f: (UnderlinePart) -> Any?) = apply { underline = f }
    fun node(f: (NodePart) -> Any?) = apply { node = f }
    fun text(f: (TextPart) -> Any?) = apply { text = f }

    val isEmpty: Boolean
        get() = arc == null && treeEdge == null && connector == null &&
            underline == null && node == null && text == null
}

fun decorations(build: Decorations.() -> Unit): Decorations = Decorations().apply(build)

/**
 * A lambda's return value as FLines. Forgiving on purpose: a single line, a collection, or
 * nothing at all are all reasonable things to write while hacking, and a lambda whose last
 * statement is not an expression returns Unit.
 */
internal fun asFLines(value: Any?): List<FLine> = when (value) {
    null, Unit -> emptyList()
    is FLine -> listOf(value)
    is Iterable<*> -> value.filterIsInstance<FLine>()
    is Array<*> -> value.filterIsInstance<FLine>()
    else -> emptyList()
}

/**
 * What a decoration hook gets handed.
 *
 * Everything carries [diagram], so any part can reach the sentence, the other words, the tree
 * and the layout config; and [flipY], so decorations end up in the same space as the lines they
 * are decorating -- use [at] rather than building points by hand.
 */
sealed interface Part {
    val diagram: Diagram
    val flipY: Boolean

    /** A layout point in the same space as the drawn lines. */
    fun at(p: P): P = if (flipY) P(p.x, -p.y) else p

    val config: LayoutConfig get() = diagram.config
    val sentence: Sentence get() = diagram.sentence
}

/** One dependency arc, with its relation and the words at either end. */
class ArcPart(
    override val diagram: Diagram,
    override val flipY: Boolean,
    val arc: DepArc,
    /** The FLine already produced for this arc. */
    val line: FLine,
) : Part {
    val edge: Edge get() = arc.edge
    val curve: Cubic get() = arc.curve
    val label: String get() = arc.label

    /** The governing word, or null for the root arc, which has none. */
    val governor: WordBox? get() = diagram.words.firstOrNull { it.token == edge.gov }
    val dependent: WordBox get() = diagram.words.first { it.token == edge.dep }

    /** How far this arc reaches, in words. */
    val reach: Int get() = kotlin.math.abs(edge.dep - edge.gov)

    fun pointAt(t: Double): P = at(curve.pointAt(t))
    fun angleAt(t: Double): Double = curve.angleAt(t)
    fun lowest(): P = at(curve.lowestPoint())
}

/** One constituency tree edge, with the nodes at both ends. */
class TreeEdgePart(
    override val diagram: Diagram,
    override val flipY: Boolean,
    val treeEdge: TreeEdge,
    val line: FLine,
) : Part {
    val parent: PlacedNode get() = diagram.node(treeEdge.parent)
    val child: PlacedNode get() = diagram.node(treeEdge.child)
    val curve: Cubic get() = treeEdge.curve

    fun pointAt(t: Double): P = at(curve.pointAt(t))
    fun angleAt(t: Double): Double = curve.angleAt(t)
}

/** The spline from a POS tag down to the start of its word. */
class ConnectorPart(
    override val diagram: Diagram,
    override val flipY: Boolean,
    val wordEdge: WordEdge,
    val line: FLine,
) : Part {
    val node: PlacedNode get() = diagram.node(wordEdge.node)
    val word: WordBox get() = diagram.word(wordEdge.token)
    val curve: Cubic get() = wordEdge.curve

    fun pointAt(t: Double): P = at(curve.pointAt(t))
}

/** A word's underline bracket. Only words present in the collapsed graph get one. */
class UnderlinePart(
    override val diagram: Diagram,
    override val flipY: Boolean,
    val word: WordBox,
    val line: FLine,
) : Part {
    val left: P get() = at(word.underline(config).first)
    val right: P get() = at(word.underline(config).second)
    val centre: P get() = at(word.anchor(config))
}

/** A constituency tree node. Has no line of its own -- the originals draw a dot. */
class NodePart(
    override val diagram: Diagram,
    override val flipY: Boolean,
    val node: PlacedNode,
) : Part {
    val label: String get() = node.label
    val position: P get() = at(node.at)
    /** The word below this node, when it is a POS tag. */
    val word: WordBox? get() = node.token?.let { diagram.word(it) }
}

/** A label about to be set: a word, a tree node's tag, or a relation name. */
class TextPart(
    override val diagram: Diagram,
    override val flipY: Boolean,
    val item: TextItem,
    val font: java.awt.Font,
) : Part {
    val kind: TextKind get() = item.kind
    val text: String get() = item.text
    val position: P get() = at(item.at)
    val angle: Double get() = item.angle

    /** The glyph outlines this label will be drawn as. */
    fun outline(): FLine = item.outlineFLine(font, flipY)

    /** Where the label sits, as a rectangle -- handy for boxing or underlining it. */
    val bounds: Rect
        get() = Rect.rotatedText(
            item.at,
            Java2DMetrics(font).width(item.text),
            font.size2D.toDouble(),
            item.angle,
        ).let {
            if (!flipY) it else Rect(it.x0, -it.y1, it.x1, -it.y0)
        }
}

// -- small builders, for the ticks and beads decoration usually wants ---------------------

/** A filled dot. */
fun dot(at: P, radius: Double = 2.0): FLine =
    ring(at, radius).also { it.filled = true; it.stroked = false }

/** A circle, stroked. Four cubics, which is the usual good-enough approximation. */
fun ring(at: P, radius: Double = 4.0): FLine {
    val k = radius * 0.5522847498
    val f = FLine().moveTo(at.x + radius, at.y)
    f.cubicTo(at.x + radius, at.y + k, at.x + k, at.y + radius, at.x, at.y + radius)
    f.cubicTo(at.x - k, at.y + radius, at.x - radius, at.y + k, at.x - radius, at.y)
    f.cubicTo(at.x - radius, at.y - k, at.x - k, at.y - radius, at.x, at.y - radius)
    f.cubicTo(at.x + k, at.y - radius, at.x + radius, at.y - k, at.x + radius, at.y)
    return f
}

/** A straight run through the given points. */
fun segment(vararg points: P): FLine {
    require(points.size >= 2) { "a segment needs at least two points" }
    val f = FLine().moveTo(points[0].x, points[0].y)
    for (p in points.drop(1)) f.lineTo(p.x, p.y)
    return f
}

/** A rectangle, stroked. */
fun box(r: Rect): FLine =
    segment(P(r.x0, r.y0), P(r.x1, r.y0), P(r.x1, r.y1), P(r.x0, r.y1), P(r.x0, r.y0))

/**
 * A tick standing off a curve at parameter [t], [length] long, perpendicular to the curve.
 * The staple decoration for marking a point on an arc or an edge.
 */
fun tick(curve: Cubic, t: Double, length: Double = 6.0, flipY: Boolean = false): FLine {
    val p = curve.pointAt(t)
    val d = curve.tangentAt(t)
    val n = d.length.let { if (it < 1e-9) P(0.0, 1.0) else P(-d.y / it, d.x / it) }
    fun f(q: P) = if (flipY) P(q.x, -q.y) else q
    return segment(f(p), f(P(p.x + n.x * length, p.y + n.y * length)))
}
