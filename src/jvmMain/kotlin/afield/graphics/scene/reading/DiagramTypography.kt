package afield.graphics.scene.reading

import afield.graphics.text.*
import afield.math.linalg.Vec2

/**
 * Optional overrides, resolved against the enclosing run. Prepared once, never in the render loop.
 */
class DiagramTypography {
    var font: Typeface? = null
    var bold: Boolean? = null
    var italic: Boolean? = null
    var weight: Double? =
        null // CSS-style 100..900; AWT face selection, not arbitrary variable axes

    internal fun resolve(
        base: Typeface,
        baseBold: Boolean,
        baseItalic: Boolean,
        baseWeight: Double?,
    ): Typeface {
        val face = font ?: base
        val b = bold ?: (baseBold || face.awtFont.isBold)
        val i = italic ?: (baseItalic || face.awtFont.isItalic)
        var result = if (b || i || bold != null || italic != null) face.withStyle(b, i) else face
        (weight ?: baseWeight)?.let { result = result.withWeight(it) }
        return result
    }
}

internal data class ReadingStyleRange(val start: Int, val end: Int, val face: Typeface)

internal data class ReadingPreparationKey(
    val text: String,
    val face: Typeface,
    val tracking: Double,
    val ranges: List<ReadingStyleRange>,
)

internal fun readingPreparationKey(run: DiagramText, fallback: Typeface): ReadingPreparationKey {
    require(run.tracking.isFinite())
    val base = DiagramTypography().resolve(run.font ?: fallback, run.bold, run.italic, run.weight)
    val ranges =
        run.styles.map { (span, style) ->
            ReadingStyleRange(
                span.start,
                span.endExclusive,
                style.resolve(run.font ?: fallback, run.bold, run.italic, run.weight),
            )
        }
    return ReadingPreparationKey(run.text, base, run.tracking, ranges)
}

internal fun prepareReadingLayout(run: DiagramText, key: ReadingPreparationKey): TextLayout {
    if (key.ranges.isEmpty()) {
        require(key.face.canDisplay(run.text)) {
            "${key.face.fontName} cannot display '${run.text}'"
        }
        return layoutText(run.text, key.face, tracking = key.tracking)
    }
    // Shape contiguous style sections, not individual characters: kerning and ligatures survive
    // within each section. A style boundary deliberately starts a new shaping context.
    require('\n' !in run.text) {
        "Inline styles currently require a single baseline; use separate styled runs for multiple lines"
    }
    val cuts =
        (listOf(0, run.text.length) + key.ranges.flatMap { listOf(it.start, it.end) })
            .distinct()
            .sorted()
    require(cuts.all { it == run.text.length || !Character.isLowSurrogate(run.text[it]) }) {
        "A style boundary splits a Unicode surrogate pair"
    }
    val glyphs = ArrayList<PlacedGlyph>()
    var pen = 0.0
    for ((a, b) in cuts.zipWithNext()) {
        val face = key.ranges.lastOrNull { a >= it.start && b <= it.end }?.face ?: key.face
        val text = run.text.substring(a, b)
        require(face.canDisplay(text)) { "${face.fontName} cannot display '$text'" }
        val part = layoutText(text, face, tracking = key.tracking)
        for (g in part.glyphs) glyphs.add(
            PlacedGlyph(
                g.glyph,
                Vec2((g.origin.x + pen).toFloat(), g.origin.y),
                0,
                a + g.charIndex,
            ),
        )
        pen += part.advanceWidth
        if (b < run.text.length) pen += key.tracking
    }
    return TextLayout(
        run.text,
        key.face,
        glyphs,
        listOf(TextLine(0, run.text, 0.0, pen, glyphs)),
        key.tracking,
        key.face.lineHeight,
        TextAlign.LEFT,
    )
}
