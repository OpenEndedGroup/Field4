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

/**
 * Fonts tried, in order, for a character the run's own face cannot display, the way a terminal or
 * browser borrows a glyph. Only families installed on this machine take part. Each borrowed face
 * is set at the run's size and style, and kerning stops at the borrowed character.
 *
 * Set [DiagramText.fallbacks] to choose a chain for one run, or to an empty list to fail instead.
 */
object DiagramFontFallback {
    /** Families to try, in order. Math faces come first: Mathlib text is where gaps show up. */
    val candidates: List<String> = listOf(
        "Noto Sans Math", "STIX Two Math", "DejaVu Math TeX Gyre", "Noto Sans Symbols",
        "Noto Sans Symbols 2", "DejaVu Sans", "Noto Sans", "Noto Sans CJK SC",
    )

    /** The installed members of [candidates], found on first use. */
    val installed: List<Typeface> by lazy {
        val have = Typefaces.available().toSet()
        candidates.filter { it in have }.map { Typefaces.named(it, strict = true) }
    }
}

/**
 * Ranges for the characters [ranges] (or [base]) cannot display, each set in the first face of
 * [chain] that can. Fails naming the character, its code point and the faces tried.
 */
internal fun fallbackRanges(
    text: String,
    base: Typeface,
    ranges: List<ReadingStyleRange>,
    chain: () -> List<Typeface>,
): List<ReadingStyleRange> {
    val out = ArrayList<ReadingStyleRange>()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val end = i + Character.charCount(cp)
        val face = ranges.lastOrNull { i >= it.start && end <= it.end }?.face ?: base
        if (cp != '\n'.code && !face.awtFont.canDisplay(cp)) {
            val tried = chain()
            val pick = tried.firstOrNull { it.awtFont.canDisplay(cp) }
                ?: throw IllegalArgumentException(
                    "No font can display '${String(Character.toChars(cp))}' " +
                        "(U+${"%04X".format(cp)}) at $i in '${text.take(80)}${if (text.length > 80) "…" else ""}': " +
                        "tried ${(listOf(face) + tried).joinToString { it.awtFont.family }}",
                )
            val f = face.awtFont
            var borrowed = pick.atSize(face.size)
            if (f.isBold || f.isItalic) borrowed = borrowed.withStyle(f.isBold, f.isItalic)
            val last = out.lastOrNull()
            if (last != null && last.end == i && last.face == borrowed) out[out.size - 1] = last.copy(end = end)
            else out.add(ReadingStyleRange(i, end, borrowed))
        }
        i = end
    }
    return out
}

internal data class ReadingStyleRange(val start: Int, val end: Int, val face: Typeface)

internal data class ReadingPreparationKey(
    val text: String,
    val face: Typeface,
    val tracking: Double,
    val ranges: List<ReadingStyleRange>,
    val dropCap: ReadingDropCapKey? = null,
    /** Coloured character ranges as (start, end, r, g, b, a), in the order they were set. */
    val colors: List<List<Float>> = emptyList(),
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
    val cap = run.dropCapDefinition?.let { definition ->
        val face = definition.typography.resolve(base, base.awtFont.isBold, base.awtFont.isItalic, null)
        val initial = run.text.substring(0, definition.end)
        require(face.canDisplay(initial)) { "${face.fontName} cannot display '$initial'" }
        val source = layoutText(initial, face).bounds
        require(source.h > 0) { "A drop cap needs visible ink" }
        val bodyCapHeight = layoutText("H", base).bounds.h
        val height = bodyCapHeight + (definition.lines - 1) * base.lineHeight
        ReadingDropCapKey(definition.lines, definition.gapEm, definition.end,
            face.atSize(face.size * height / source.h))
    }
    val styled = if (cap == null) ranges else ranges + ReadingStyleRange(0, cap.end, cap.face)
    // Borrowed faces go last so they win over the style they patch, exactly where it has no glyph.
    val resolvedRanges = styled + fallbackRanges(run.text, base, styled) { run.fallbacks ?: DiagramFontFallback.installed }
    val colors = run.colorList.map { (span, c) ->
        listOf(span.start.toFloat(), span.endExclusive.toFloat(), c.x, c.y, c.z, c.w)
    }
    return ReadingPreparationKey(run.text, base, run.tracking, resolvedRanges, cap, colors)
}

internal fun prepareReadingLayout(run: DiagramText, key: ReadingPreparationKey): TextLayout {
    if (key.dropCap != null) return prepareDropCapLayout(run, key)
    if (key.ranges.isEmpty()) {
        require(key.face.canDisplay(run.text)) {
            "${key.face.fontName} cannot display '${run.text}'"
        }
        return layoutText(run.text, key.face, tracking = key.tracking)
    }
    // Shape contiguous style sections, not individual characters: kerning and ligatures survive
    // within each section. A style boundary deliberately starts a new shaping context. Lines stack
    // at the face's line height, as layoutText does for an unstyled run.
    val glyphs = ArrayList<PlacedGlyph>()
    val lines = ArrayList<TextLine>()
    var lineStart = 0
    for ((li, lineText) in run.text.split('\n').withIndex()) {
        val lineEnd = lineStart + lineText.length
        val baselineY = li * key.face.lineHeight
        val cuts =
            (listOf(lineStart, lineEnd) +
                key.ranges.flatMap { listOf(it.start, it.end) }.filter { it in lineStart..lineEnd })
                .distinct()
                .sorted()
        require(cuts.all { it == run.text.length || !Character.isLowSurrogate(run.text[it]) }) {
            "A style boundary splits a Unicode surrogate pair"
        }
        val lineGlyphs = ArrayList<PlacedGlyph>()
        var pen = 0.0
        for ((a, b) in cuts.zipWithNext()) {
            val face = key.ranges.lastOrNull { a >= it.start && b <= it.end }?.face ?: key.face
            val text = run.text.substring(a, b)
            require(face.canDisplay(text)) { "${face.fontName} cannot display '$text'" }
            val part = layoutText(text, face, tracking = key.tracking)
            for (g in part.glyphs) lineGlyphs.add(
                PlacedGlyph(
                    g.glyph,
                    Vec2((g.origin.x + pen).toFloat(), baselineY.toFloat()),
                    li,
                    a + g.charIndex,
                ),
            )
            pen += part.advanceWidth
            if (b < lineEnd) pen += key.tracking
        }
        glyphs.addAll(lineGlyphs)
        lines.add(TextLine(li, lineText, baselineY, pen, lineGlyphs))
        lineStart = lineEnd + 1
    }
    return TextLayout(
        run.text,
        key.face,
        glyphs,
        lines,
        key.tracking,
        key.face.lineHeight,
        TextAlign.LEFT,
    )
}
