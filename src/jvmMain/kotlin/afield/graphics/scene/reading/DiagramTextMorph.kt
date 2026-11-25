package afield.graphics.scene.reading

import afield.math.linalg.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Characters `[start, endExclusive)` of a morph's text, and the key that matches them on the other side.
 *
 * Keys are the caller's: a subterm's path in an expression tree, a word's index in a parse, a cell
 * name. Two spans match when their keys are equal. A null key never matches, so its characters fade.
 */
data class DiagramKeyedSpan(val start: Int, val endExclusive: Int, val key: Any?) {
    init {
        require(start in 0 until endExclusive) { "A keyed span needs 0 <= start < endExclusive, got [$start, $endExclusive)" }
    }
}

/**
 * A morph between two lines of text whose pieces are matched by key: a piece whose key appears on
 * both sides glides from where it sits in the first line to where it sits in the second; an
 * unmatched piece fades out (first line) or in (second line) where it stands. Built with
 * [ReadingDiagramBuilder.textMorph] and played with [DiagramScoreBuilder.morph].
 *
 * Each side is laid out once as a complete, invisible run ([from], [to]), so every piece is placed by
 * the measured position of its characters in a whole line, not by counting characters. Pieces are
 * separate runs of the same face and scale. In a monospaced face they coincide exactly with the whole
 * line; in a proportional face kerning across a piece boundary is lost, a sub-pixel difference at
 * reading sizes.
 */
class DiagramTextMorph
internal constructor(
    val id: String,
    /** The first line, laid out whole and never drawn; its pose carries the morph's progress. */
    val from: DiagramText,
    /** The second line, laid out whole and never drawn. */
    val to: DiagramText,
    val pieces: List<Piece>,
    /** Every visible piece, for fading the whole morph in or out. */
    val group: DiagramGroup,
) {
    enum class Role {
        /** Same key and same characters on both sides: one run that moves. */
        GLIDE,
        /** Same key, different characters: the first side's run, moving and fading out. */
        CROSS_FROM,
        /** Same key, different characters: the second side's run, moving and fading in. */
        CROSS_TO,
        /** In the first line only. */
        FADE_OUT,
        /** In the second line only. */
        FADE_IN,
    }

    class Piece internal constructor(
        val text: DiagramText,
        val role: Role,
        val key: Any?,
        /** Characters of [DiagramTextMorph.from], or null for a second-line-only piece. */
        val fromSpan: DiagramKeyedSpan?,
        /** Characters of [DiagramTextMorph.to], or null for a first-line-only piece. */
        val toSpan: DiagramKeyedSpan?,
    ) {
        val moves get() = fromSpan != null && toSpan != null
    }

    /** Pieces whose key is present on both sides. */
    val matched: Int get() = pieces.count { it.role == Role.GLIDE || it.role == Role.CROSS_FROM }

    /** Keyed (non-null) pieces of each side that found no partner. */
    val unmatchedKeys: List<Any> get() = pieces.filter { (it.role == Role.FADE_OUT || it.role == Role.FADE_IN) && it.key != null }.map { it.key!! }

    /** Morph progress at this frame: 0 shows the first line, 1 the second. */
    fun progress(frame: DiagramFrame): Float = frame.pose(from).morph.coerceIn(0f, 1f)
}

/**
 * Lay out [from] and [to] at [at] (and [toAt]) and build the pieces that morph between them.
 *
 * [fromSpans] and [toSpans] cut each line into keyed pieces; they must not overlap. Characters they
 * leave out become unkeyed pieces (whitespace alone is dropped), which fade. A key may be used once
 * per side. [block] styles every run alike — font, scale, colour — and is applied to the hidden
 * layout runs too, so pieces and layout agree. At mid-flight a gliding piece is [lift] metres nearer
 * the reader and off its line, up when it travels right and down when it travels left, so two pieces
 * that swap places pass on either side of each other rather than through. That offset is a quarter of
 * the distance travelled, never more than [arc] metres: a piece that only shifts to make room stays
 * close to its line.
 *
 * ```kotlin
 * val graph = readingDiagram {
 *     val p = point("p", 0, 0, pinned = true)
 *     textMorph("swap", "a + b", listOf(DiagramKeyedSpan(0, 1, "a"), DiagramKeyedSpan(4, 5, "b")),
 *                       "b + a", listOf(DiagramKeyedSpan(0, 1, "b"), DiagramKeyedSpan(4, 5, "a")), p.anchor)
 * }
 * val score = diagramScore(graph, 6.0) { morph(graph.textMorph("swap"), at = 1.0, seconds = 2.0) }
 * ```
 */
fun ReadingDiagramBuilder.textMorph(
    id: String,
    from: String,
    fromSpans: List<DiagramKeyedSpan>,
    to: String,
    toSpans: List<DiagramKeyedSpan>,
    at: DiagramAnchor,
    toAt: DiagramAnchor = at,
    lift: Float = .02f,
    arc: Float = .025f,
    block: DiagramText.() -> Unit = {},
): DiagramTextMorph {
    require(lift.isFinite() && arc.isFinite())
    val fromCut = morphCut(from, fromSpans, "first")
    val toCut = morphCut(to, toSpans, "second")
    val fromRun = text("$id-from", from, at) { block(); color = color.copyAlpha(0f) }
    val toRun = text("$id-to", to, toAt) { block(); color = color.copyAlpha(0f) }
    fromRun.properties["textMorphLayout"] = id
    toRun.properties["textMorphLayout"] = id
    val toByKey = toCut.filter { it.key != null }.associateBy { it.key }
    val fromKeys = fromCut.mapNotNull { it.key }.toSet()

    val pieces = ArrayList<DiagramTextMorph.Piece>()
    var n = 0
    fun piece(text: String, anchor: DiagramAnchor, role: DiagramTextMorph.Role, key: Any?, a: DiagramKeyedSpan?, b: DiagramKeyedSpan?) {
        val run = text("$id-${n++}", text, anchor) { block() }
        run.properties["textMorphPiece"] = id
        pieces += DiagramTextMorph.Piece(run, role, key, a, b)
    }
    // The pieces are placed through the layout runs' own measured spans, live, so they follow the
    // runs' anchors, physics and score offsets like any other text-attached drawing.
    fun fromPlace(s: DiagramKeyedSpan) = fromRun.characters(s.start, s.endExclusive).baseline
    fun toPlace(s: DiagramKeyedSpan) = toRun.characters(s.start, s.endExclusive).baseline
    lateinit var morph: DiagramTextMorph
    fun glide(a: DiagramKeyedSpan, b: DiagramKeyedSpan) = DiagramAnchor(fromRun.owner) { f ->
        val p = f.position(fromPlace(a))
        val q = f.position(toPlace(b))
        val t = morph.progress(f)
        val bump = sin(PI * t).toFloat()
        // Rightward pieces arc up, leftward ones down, so two pieces that swap pass on either side.
        // The arc grows with the distance travelled, up to [arc]: a piece that shifts a little to make
        // room for a neighbour stays near its line; one that crosses the line takes the full arc.
        val dxTravel = q.x - p.x
        val side = if (dxTravel > 0f) 1f else if (dxTravel < 0f) -1f else 0f
        val amplitude = minOf(arc, abs(dxTravel) * ARC_PER_METRE)
        p.lerp(q, t).add(0f, side * amplitude * bump, lift * bump)
    }
    for (a in fromCut) {
        val b = a.key?.let { toByKey[it] }
        val textA = from.substring(a.start, a.endExclusive)
        when {
            b == null -> piece(textA, fromPlace(a), DiagramTextMorph.Role.FADE_OUT, a.key, a, null)
            to.substring(b.start, b.endExclusive) == textA -> piece(textA, glide(a, b), DiagramTextMorph.Role.GLIDE, a.key, a, b)
            else -> {
                piece(textA, glide(a, b), DiagramTextMorph.Role.CROSS_FROM, a.key, a, b)
                piece(to.substring(b.start, b.endExclusive), glide(a, b), DiagramTextMorph.Role.CROSS_TO, a.key, a, b)
            }
        }
    }
    for (b in toCut) if (b.key == null || b.key !in fromKeys) {
        piece(to.substring(b.start, b.endExclusive), toPlace(b), DiagramTextMorph.Role.FADE_IN, b.key, null, b)
    }
    val group = group("$id-pieces", pieces.map { it.text })
    morph = DiagramTextMorph(id, fromRun, toRun, pieces, group)
    textMorphs.add(morph)
    return morph
}

/** How far off its line a gliding piece arcs per metre it travels, until it reaches the morph's `arc`. */
internal const val ARC_PER_METRE = .25f

/** Sorted, non-overlapping keyed spans plus unkeyed pieces for the characters between them. */
internal fun morphCut(text: String, spans: List<DiagramKeyedSpan>, side: String): List<DiagramKeyedSpan> {
    val sorted = spans.sortedBy { it.start }
    for (s in sorted) require(s.endExclusive <= text.length) { "The $side line has ${text.length} characters; span $s runs past it" }
    for ((a, b) in sorted.zipWithNext()) require(a.endExclusive <= b.start) { "Spans $a and $b of the $side line overlap" }
    val keys = sorted.mapNotNull { it.key }
    require(keys.size == keys.toSet().size) { "A key may be used once per line; the $side line repeats ${keys.groupBy { it }.filter { it.value.size > 1 }.keys}" }
    val out = ArrayList<DiagramKeyedSpan>()
    var i = 0
    fun gap(a: Int, b: Int) {
        // Split a gap at whitespace so each unkeyed word fades on its own, and drop the whitespace.
        var s = a
        while (s < b) {
            while (s < b && text[s].isWhitespace()) s++
            var e = s
            while (e < b && !text[e].isWhitespace()) e++
            if (e > s) out += DiagramKeyedSpan(s, e, null)
            s = e
        }
    }
    for (s in sorted) {
        gap(i, s.start)
        if (text.substring(s.start, s.endExclusive).isNotBlank()) out += s
        i = s.endExclusive
    }
    gap(i, text.length)
    return out
}

private fun afield.math.linalg.Vector4f.copyAlpha(a: Float) = afield.math.linalg.Vector4f(x, y, z, a)
