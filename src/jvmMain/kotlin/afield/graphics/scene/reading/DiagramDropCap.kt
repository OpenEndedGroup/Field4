package afield.graphics.scene.reading

import afield.graphics.text.*
import afield.math.linalg.Vec2
import kotlin.math.max

internal data class DiagramDropCap(val lines: Int, val gapEm: Float, val end: Int, val typography: DiagramTypography)
internal data class ReadingDropCapKey(val lines: Int, val gapEm: Float, val end: Int, val face: Typeface)

/** Shape each style section as a run, preserving kerning/ligatures and original UTF-16 indices. */
private fun dropCapSlice(text: String, start: Int, end: Int, key: ReadingPreparationKey): Pair<List<PlacedGlyph>, Double> {
    val cuts = (listOf(start, end) + key.ranges.flatMap { listOf(it.start, it.end) }
        .filter { it > start && it < end }).distinct().sorted()
    val glyphs = ArrayList<PlacedGlyph>()
    var pen = 0.0
    for ((a, b) in cuts.zipWithNext()) {
        require(a == text.length || !Character.isLowSurrogate(text[a])) { "Style boundary splits a surrogate pair" }
        val face = key.ranges.lastOrNull { a >= it.start && b <= it.end }?.face ?: key.face
        val part = text.substring(a, b)
        require(face.canDisplay(part)) { "${face.fontName} cannot display '$part'" }
        val layout = layoutText(part, face, tracking = key.tracking)
        for (g in layout.glyphs) glyphs.add(PlacedGlyph(g.glyph, Vec2(g.origin.x + pen.toFloat(), 0f), 0, a + g.charIndex))
        pen += layout.advanceWidth
        if (b < end) pen += key.tracking
    }
    return glyphs to pen
}

/** A dropped initial fits from the first body's cap-height to the Nth body's baseline. */
internal fun prepareDropCapLayout(run: DiagramText, key: ReadingPreparationKey): TextLayout {
    val cap = requireNotNull(key.dropCap)
    val initial = layoutText(run.text.substring(0, cap.end), cap.face, tracking = key.tracking)
    val ink = initial.bounds
    val capX = -ink.x
    val capBaseline = (cap.lines - 1) * key.face.lineHeight - (ink.y + ink.h)
    val reserved = max(ink.w, capX + initial.advanceWidth) + cap.gapEm * key.face.size
    val all = ArrayList<PlacedGlyph>()
    val lines = ArrayList<TextLine>()
    var start = 0
    for ((index, text) in run.text.split('\n').withIndex()) {
        val baseline = index * key.face.lineHeight
        val end = start + text.length
        val bodyStart = if (index == 0) cap.end else start
        val (body, width) = dropCapSlice(run.text, bodyStart, end, key)
        val indent = if (index < cap.lines) reserved else 0.0
        val placed = ArrayList<PlacedGlyph>()
        if (index == 0) for (g in initial.glyphs) placed.add(
            PlacedGlyph(g.glyph, Vec2((g.origin.x + capX).toFloat(), capBaseline.toFloat()), 0, g.charIndex),
        )
        for (g in body) placed.add(PlacedGlyph(g.glyph,
            Vec2((g.origin.x + indent).toFloat(), baseline.toFloat()), index, g.charIndex))
        val edge = if (body.isNotEmpty()) indent + width else if (index == 0) reserved - cap.gapEm * key.face.size else 0.0
        lines.add(TextLine(index, text, baseline, edge, placed))
        all.addAll(placed)
        start = end + 1
    }
    return TextLayout(run.text, key.face, all, lines, key.tracking, key.face.lineHeight, TextAlign.LEFT)
}
