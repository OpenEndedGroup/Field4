package afield.graphics.text.sentences

import afield.geometry.path.FLine
import afield.math.linalg.Vec2
import afield.math.linalg.Vec4
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.thickness
import afield.graphics.render.stroked
import afield.math.linalg.vec

/**
 * Converts a laid-out [Diagram] into [FLine]s ready to draw.
 *
 * Layout works with y increasing downward (see [P]).  If your drawing surface is y-up,
 * pass `flipY = true` and every curve comes back negated in y -- do it here once rather
 * than threading a sign through the layout.
 *
 * Text is handed back as [TextItem]s rather than being baked into the FLines, because
 * how a label gets drawn (which property key, which font, which shader) is a property of
 * your drawing setup, not of the layout.  See [textFLine] for the one-line version once
 * you know the key you want.
 */

/**
 * Interop with Field's vectors.
 *
 * The layout keeps its own immutable [P] rather than using [Vec2] throughout, because [Vec2] is
 * `Vector2f` and mutable: every point the layout hands out sits inside a value-semantics result
 * ([Cubic], [DepArc], [Diagram]), and the collision solver re-derives curves from those points
 * repeatedly. A caller mutating one in place would silently invalidate bounds and collision
 * decisions already computed from it. Converting at the boundary costs one allocation and makes
 * that impossible.
 */
fun P.toVec2(flipY: Boolean = false): Vec2 =
    vec(x, if (flipY) -y else y)

fun Vec2.toP(flipY: Boolean = false): P =
    P(x.toDouble(), if (flipY) -y.toDouble() else y.toDouble())

private fun Double.flip(flipY: Boolean) = if (flipY) -this else this

/** A single cubic as an FLine. */
fun Cubic.toFLine(flipY: Boolean = false): FLine =
    FLine()
        .moveTo(a.x, a.y.flip(flipY))
        .cubicTo(
            c1.x, c1.y.flip(flipY),
            c2.x, c2.y.flip(flipY),
            b.x, b.y.flip(flipY),
        )

enum class TextKind {
    /** The word itself. */
    WORD,

    /** A constituency node label: ROOT, S, NP, VBZ. */
    TAG,

    /** A dependency relation's long name, set along its arc. */
    RELATION,
}

/**
 * A label to draw: what, where, and at what angle.
 *
 * [angle] is in degrees and already flipped to read left-to-right, so it can be used as
 * a rotation directly.
 */
data class TextItem(
    val text: String,
    val at: P,
    val angle: Double = 0.0,
    val kind: TextKind,
)

/**
 * The fonts a diagram's three kinds of label want, sized from the layout itself so
 * [DiagramFLines.textOutlines] does not have to guess and cannot disagree with what the layout
 * reserved space for.
 */
data class TextFonts(
    val word: java.awt.Font,
    val tag: java.awt.Font,
    val relation: java.awt.Font,
) {
    operator fun get(kind: TextKind): java.awt.Font = when (kind) {
        TextKind.WORD -> word
        TextKind.TAG -> tag
        TextKind.RELATION -> relation
    }
}

data class DiagramFLines(
    val treeEdges: List<FLine>,
    /** POS tag to word-start connectors; the originals draw these like [treeEdges]. */
    val wordEdges: List<FLine>,
    val arcs: List<FLine>,
    val underlines: List<FLine>,
    val texts: List<TextItem>,
    /** Sized from the layout; override per call if you want something else. */
    val fonts: TextFonts,
    /** Whatever the [Decorations] hooks returned, in draw order. Drawn last, over everything. */
    val decorations: List<FLine> = emptyList(),
) {
    val curves: List<FLine> get() = treeEdges + wordEdges + arcs + underlines + decorations

    /** Just the constituency tree, tags through to the words. */
    val tree: List<FLine> get() = treeEdges + wordEdges

    /** The labels as one-node FLines carrying their string under [key]. */
    fun textFLines(key: String = "text", flipY: Boolean = false): List<FLine> =
        texts.map { it.textFLine(key, flipY) }

    /** Curves and labels together, for when you just want to throw it all on screen. */
    fun all(key: String = "text", flipY: Boolean = false): List<FLine> =
        curves + textFLines(key, flipY)
}

/**
 * Everything needed to draw [this], as FLines plus a list of labels.
 *
 * [wordFont] is the one thing the layout cannot supply, since it holds metrics rather than a
 * font; pass it to guarantee the drawn glyphs match the widths that were measured.
 */
fun Diagram.toFLines(
    flipY: Boolean = false,
    family: String = "Georgia",
    wordFont: java.awt.Font? = null,
    decorate: Decorations? = null,
): DiagramFLines {
    val config = this.config
    val fonts = TextFonts(
        word = wordFont ?: font(family, config.wordSize),
        tag = font(family, config.tagSize),
        relation = font(family, config.labelSize),
    )

    val underlines = words.filter { it.underlined }.map { w ->
        val (l, r) = w.underline(config)
        val tick = config.tickHeight
        // The bracket-ended underline of the originals: a short tick up at each end.
        FLine()
            .moveTo(l.x, (l.y - tick).flip(flipY))
            .lineTo(l.x, l.y.flip(flipY))
            .lineTo(r.x, r.y.flip(flipY))
            .lineTo(r.x, (r.y - tick).flip(flipY))
    }

    val texts = mutableListOf<TextItem>()
    for (w in words) {
        texts += TextItem(w.word, P(w.left, w.baseline), 0.0, TextKind.WORD)
    }
    for (n in nodes) {
        texts += TextItem(n.label, n.at, 0.0, TextKind.TAG)
    }
    if (config.arcLabels) {
        for (a in arcs) {
            texts += TextItem(a.label, a.labelAt, a.labelAngle, TextKind.RELATION)
        }
    }

    val treeLines = treeEdges.map { it.curve.toFLine(flipY) }
    val connectorLines = wordEdges.map { it.curve.toFLine(flipY) }
    val arcLines = arcs.map { it.curve.toFLine(flipY) }
    val underlined = words.filter { it.underlined }

    // Decorations run in draw order, each hook seeing the line that was produced for it.
    val extra = mutableListOf<FLine>()
    if (decorate != null && !decorate.isEmpty) {
        decorate.treeEdge?.let { hook ->
            treeEdges.forEachIndexed { i, e ->
                extra += asFLines(hook(TreeEdgePart(this, flipY, e, treeLines[i])))
            }
        }
        decorate.connector?.let { hook ->
            wordEdges.forEachIndexed { i, e ->
                extra += asFLines(hook(ConnectorPart(this, flipY, e, connectorLines[i])))
            }
        }
        decorate.arc?.let { hook ->
            arcs.forEachIndexed { i, a ->
                extra += asFLines(hook(ArcPart(this, flipY, a, arcLines[i])))
            }
        }
        decorate.underline?.let { hook ->
            underlined.forEachIndexed { i, w ->
                extra += asFLines(hook(UnderlinePart(this, flipY, w, underlines[i])))
            }
        }
        decorate.node?.let { hook ->
            nodes.forEach { n -> extra += asFLines(hook(NodePart(this, flipY, n))) }
        }
        decorate.text?.let { hook ->
            texts.forEach { t -> extra += asFLines(hook(TextPart(this, flipY, t, fonts[t.kind]))) }
        }
    }

    return DiagramFLines(
        treeEdges = treeLines,
        wordEdges = connectorLines,
        arcs = arcLines,
        underlines = underlines,
        texts = texts,
        fonts = fonts,
        decorations = extra,
    )
}

/**
 * Lay out and convert in one step -- the usual call when driving from Field.
 *
 * Defined here rather than on [SentenceDiagram] so that class stays independent of the
 * graphics stack.
 */
fun SentenceDiagram.flines(
    flipY: Boolean = false,
    family: String = "Georgia",
    decorate: Decorations? = null,
): DiagramFLines = diagram().toFLines(flipY, family, wordFont, decorate)

/**
 * A [TextItem] as a degenerate one-node FLine carrying the string in its node properties.
 *
 * Note that `lines()` will not draw this: its renderer only honours geometry plus colour,
 * thickness, stroked and filled -- there is no text-carrying property it looks for.  This
 * exists for your own renderer, or as a positional marker.  To actually see text through
 * `lines()`, use [outlineFLine] / [textOutlines], which turn it into glyph outlines.
 */
fun TextItem.textFLine(key: String = "text", flipY: Boolean = false): FLine {
    val f = FLine().moveTo(at.x, at.y.flip(flipY))
    f.nodes.last().properties[key] = text
    f.properties["sentences.kind"] = kind.name
    if (angle != 0.0) f.properties["sentences.angle"] = angle
    return f
}

/**
 * Colours and widths for the parts of a diagram.
 *
 * Every field is nullable and null means "leave it alone". That is the default, because an
 * unstyled FLine draws white in Field, which is what you want on a dark canvas -- writing black
 * in by default would make the whole diagram vanish there. [onWhite] is the other case, and is
 * what [svg] uses.
 */
data class DiagramStyle(
    val curve: Vec4? = null,
    val curveWidth: Double? = null,
    val underline: Vec4? = null,
    val underlineWidth: Double? = null,
    val word: Vec4? = null,
    val tag: Vec4? = null,
    val relation: Vec4? = null,
) {
    companion object {
        /** Leaves every line exactly as it comes. */
        val untouched = DiagramStyle()

        /** Dark ink on a light page, as in the original PDFs. */
        val onWhite = DiagramStyle(
            curve = vec(0.07, 0.07, 0.07, 1.0), curveWidth = 0.9,
            underline = vec(0.4, 0.4, 0.4, 1.0), underlineWidth = 0.8,
            word = vec(0.0, 0.0, 0.0, 1.0),
            tag = vec(0.54, 0.54, 0.54, 1.0),
            relation = vec(0.6, 0.6, 0.6, 1.0),
        )
    }
}

private fun FLine.paint(colour: Vec4?, width: Double?): FLine {
    // Only ever write: reading FLine.color pins the line to white (see StandardDrawing).
    if (colour != null) color = colour
    if (width != null) thickness = width
    return this
}

/** A font at an exact size; AWT's constructor only takes an integer size. */
fun font(name: String = "Georgia", size: Double = 26.0): java.awt.Font =
    java.awt.Font(name, java.awt.Font.PLAIN, 12).deriveFont(size.toFloat())

private val frc = java.awt.font.FontRenderContext(null, true, true)

/**
 * A [TextItem] as glyph outlines -- real FLines that `lines()` draws like anything else.
 *
 * This is what the 2011 PDFs contain: their text is outlined, not set, which is why no
 * strings can be extracted from them.  Filled and unstroked by default, so it reads as
 * type rather than as wireframe.
 *
 * With [flipY] the whole diagram mirrors, glyphs included -- that is what flipping means.
 * If you want a y-up world with readable type, leave this false and flip in your camera.
 */
fun TextItem.outlineFLine(font: java.awt.Font, flipY: Boolean = false): FLine {
    val outline = font.createGlyphVector(frc, text).outline
    // Composes as scale * translate * rotate: glyphs are built at the origin on their
    // baseline, moved into place, then the whole thing mirrors if asked.
    val at = java.awt.geom.AffineTransform()
    if (flipY) at.scale(1.0, -1.0)
    at.translate(this.at.x, this.at.y)
    if (angle != 0.0) at.rotate(Math.toRadians(angle))

    val f = afield.geometry.path.java2d.FLinesAndJavaShapes.javaShapeToFLine(outline, at)
    f.filled = true
    f.stroked = false
    f.properties["sentences.kind"] = kind.name
    return f
}

/**
 * Every label as glyph outlines, one FLine per label.
 *
 * Sizes default to the same ones [Svg] uses. Pass [SentenceDiagram.wordFont] as [word] to
 * guarantee the drawn glyphs match the widths the layout measured with.
 */
fun DiagramFLines.textOutlines(
    word: java.awt.Font = fonts.word,
    tag: java.awt.Font = fonts.tag,
    relation: java.awt.Font = fonts.relation,
    flipY: Boolean = false,
): List<FLine> = texts.map {
    val f = when (it.kind) {
        TextKind.WORD -> word
        TextKind.TAG -> tag
        TextKind.RELATION -> relation
    }
    it.outlineFLine(f, flipY)
}

/**
 * Curves and text together, all as FLines -- the one call that gives a complete drawable
 * diagram:
 *
 *     lines(d.flines().everything())
 */
fun DiagramFLines.everything(
    word: java.awt.Font = fonts.word,
    tag: java.awt.Font = fonts.tag,
    relation: java.awt.Font = fonts.relation,
    flipY: Boolean = false,
    style: DiagramStyle = DiagramStyle.untouched,
): List<FLine> {
    treeEdges.forEach { it.paint(style.curve, style.curveWidth) }
    wordEdges.forEach { it.paint(style.curve, style.curveWidth) }
    arcs.forEach { it.paint(style.curve, style.curveWidth) }
    underlines.forEach { it.paint(style.underline, style.underlineWidth) }

    val outlines = texts.zip(textOutlines(word, tag, relation, flipY)) { item, line ->
        line.paint(
            when (item.kind) {
                TextKind.WORD -> style.word
                TextKind.TAG -> style.tag
                TextKind.RELATION -> style.relation
            },
            null,
        )
    }
    return curves + outlines
}

/**
 * The diagram as an SVG string, drawn through Field's own FLine renderer.
 *
 * There is deliberately no second renderer in this package: Field goes FLine to SVG internally
 * (`afield.ui.visualization.drawToSVG`), so anything else would be a duplicate that drifts -- which it
 * did, twice, over label and tag sizes. Going through the FLines also means decorations show up
 * here exactly as they do on screen.
 */
fun SentenceDiagram.svg(
    decorate: Decorations? = null,
    style: DiagramStyle = DiagramStyle.onWhite,
    margin: Double = 40.0,
    flipY: Boolean = false,
): String {
    val f = flines(flipY, decorate = decorate)
    val lines = f.everything(flipY = flipY, style = style).toMutableList()
    if (margin > 0) {
        // An invisible box to hold the margin open: drawToSVG sizes itself from the bounds, and a
        // line that is neither stroked nor filled still contributes to them.
        val b = diagram().bounds.expand(margin)
        lines += box(if (flipY) Rect(b.x0, -b.y1, b.x1, -b.y0) else b)
            .also { it.stroked = false; it.filled = false }
    }
    return afield.ui.visualization.drawToSVG(lines, -1.0, -1.0)
}

fun SentenceDiagram.writeSvg(
    path: String,
    decorate: Decorations? = null,
    style: DiagramStyle = DiagramStyle.onWhite,
    margin: Double = 40.0,
): String {
    val s = svg(decorate, style, margin)
    java.io.File(path).writeText(s)
    return s
}
