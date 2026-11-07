package afield.graphics.text.sentences

import afield.geometry.path.FLine
import afield.math.linalg.Vec4
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness

/**
 * FLines for a [KelloggDiagram], through the same styling and text-outline path as the arc
 * layout. Only the geometry differs; nothing about how it reaches the screen does.
 */
data class KelloggFLines(
    /** Baselines and prepositional shelves: the horizontals words sit on. */
    val baselines: List<FLine>,
    /** Bisectors, half-bars and backslashes: the marks that separate the parts of a clause. */
    val bars: List<FLine>,
    val slants: List<FLine>,
    /** Dotted lines to subordinate clauses, already broken into dashes. */
    val risers: List<FLine>,
    val texts: List<TextItem>,
    val fonts: TextFonts,
    /**
     * The font each label is drawn in, at the size the layout reserved room for. Kept per label
     * rather than per kind, because a Reed-Kellogg word's size depends on the line it sits on.
     */
    val perText: List<java.awt.Font>,
) {
    val curves: List<FLine> get() = baselines + bars + slants + risers

    /**
     * Glyph outlines, each at the size the layout measured it at.
     *
     * Passing [word] or [modifier] overrides that, which will make the drawing disagree with the
     * space reserved for it -- change `KelloggConfig.fontSize` / `modifierSize` instead.
     */
    fun textOutlines(
        word: java.awt.Font? = null,
        modifier: java.awt.Font? = null,
        flipY: Boolean = false,
    ): List<FLine> = texts.mapIndexed { i, t ->
        val override = if (t.kind == TextKind.WORD) word else modifier
        t.outlineFLine(override ?: perText[i], flipY)
    }

    fun everything(
        word: java.awt.Font? = null,
        modifier: java.awt.Font? = null,
        flipY: Boolean = false,
        style: DiagramStyle = DiagramStyle.untouched,
    ): List<FLine> {
        baselines.forEach { it.paintPart(style.curve, style.curveWidth) }
        bars.forEach { it.paintPart(style.curve, style.curveWidth) }
        slants.forEach { it.paintPart(style.curve, style.curveWidth) }
        risers.forEach { it.paintPart(style.underline, style.underlineWidth) }
        val outlines = texts.zip(textOutlines(word, modifier, flipY)) { item, line ->
            line.paintPart(if (item.kind == TextKind.WORD) style.word else style.tag, null)
        }
        return curves + outlines
    }
}

internal fun FLine.paintPart(colour: Vec4?, width: Double?): FLine {
    // Only ever write: reading FLine.color pins the line to white.
    if (colour != null) color = colour
    if (width != null) thickness = width
    return this
}

private fun P.flipped(flipY: Boolean) = if (flipY) P(x, -y) else this

private fun straight(a: P, b: P, flipY: Boolean): FLine {
    val p = a.flipped(flipY)
    val q = b.flipped(flipY)
    return FLine().moveTo(p.x, p.y).lineTo(q.x, q.y)
}

/** One logical line as a run of dashes, since FLine carries no dash pattern of its own. */
private fun dashes(a: P, b: P, dash: Double, flipY: Boolean): List<FLine> {
    val span = (b - a).length
    if (span < 1e-6 || dash <= 0) return listOf(straight(a, b, flipY))
    val out = mutableListOf<FLine>()
    var t = 0.0
    while (t < span) {
        val t2 = minOf(t + dash, span)
        out += straight(a.lerp(b, t / span), a.lerp(b, t2 / span), flipY)
        t = t2 + dash
    }
    return out
}

fun KelloggDiagram.toFLines(
    flipY: Boolean = false,
    family: String = "Georgia",
    wordFont: java.awt.Font? = null,
): KelloggFLines {
    val fonts = TextFonts(
        word = wordFont ?: font(family, config.fontSize),
        tag = font(family, config.fontSize * 0.62),
        relation = font(family, config.fontSize * 0.62),
    )

    val baselines = mutableListOf<FLine>()
    val bars = mutableListOf<FLine>()
    val slants = mutableListOf<FLine>()
    val risers = mutableListOf<FLine>()

    for (l in lines) when (l.kind) {
        KLineKind.BASELINE, KLineKind.SHELF -> baselines += straight(l.a, l.b, flipY)
        KLineKind.BISECTOR, KLineKind.HALF_BAR, KLineKind.BACKSLASH ->
            bars += straight(l.a, l.b, flipY)
        KLineKind.SLANT -> slants += straight(l.a, l.b, flipY)
        KLineKind.RISER -> risers += dashes(l.a, l.b, config.dash, flipY)
    }

    // Words on a line are WORD; anything on a slant reuses RELATION so styling can tell them
    // apart. The size, though, comes from the word itself -- see KWord.size.
    val texts = words.map {
        TextItem(
            it.text,
            it.at.flipped(flipY),
            if (flipY) -it.angle else it.angle,
            if (it.angle == 0.0) TextKind.WORD else TextKind.RELATION,
        )
    }
    val cache = mutableMapOf<Double, java.awt.Font>()
    val perText = words.map { w ->
        if (wordFont != null && w.size == config.fontSize) wordFont
        else cache.getOrPut(w.size) { font(family, w.size) }
    }

    return KelloggFLines(baselines, bars, slants, risers, texts, fonts, perText)
}

/**
 * Lay this sentence out as a Reed-Kellogg diagram.
 *
 * Font size and metrics come from the arc layout's config, so the two views of one sentence are
 * set the same way and `wordFont` carries over.
 */
fun SentenceDiagram.kellogg(
    kelloggConfig: KelloggConfig? = null,
): KelloggDiagram {
    val k = kelloggConfig ?: KelloggConfig(
        fontSize = config.wordSize,
        metrics = config.metrics,
    )
    return Kellogg(k).layout(sentence, scheme)
}

fun SentenceDiagram.kelloggFLines(
    flipY: Boolean = false,
    family: String = "Georgia",
    kelloggConfig: KelloggConfig? = null,
): KelloggFLines = kellogg(kelloggConfig).toFLines(flipY, family, wordFont)

/** The Reed-Kellogg diagram as SVG, through Field's own renderer. */
fun SentenceDiagram.kelloggSvg(
    style: DiagramStyle = DiagramStyle.onWhite,
    margin: Double = 40.0,
    kelloggConfig: KelloggConfig? = null,
): String {
    val k = kellogg(kelloggConfig)
    val f = k.toFLines(wordFont = wordFont)
    val lines = f.everything(style = style).toMutableList()
    if (margin > 0) {
        val b = k.bounds.expand(margin)
        lines += box(b).also { it.stroked = false; it.filled = false }
    }
    return afield.ui.visualization.drawToSVG(lines, -1.0, -1.0)
}
