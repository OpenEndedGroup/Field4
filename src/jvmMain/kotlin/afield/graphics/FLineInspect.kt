@file:afield.CompletionCategory("Graphics")

package afield.graphics

import afield.Box
import afield.stdlib.FLineFaceCoordinates
import afield.stdlib.FLineHitKind
import afield.stdlib.FLineIntersection
import afield.stdlib.Vec4
import afield.stdlib.hoverColor
import afield.stdlib.label
import afield.stdlib.onClick
import afield.stdlib.vec
import java.util.Locale

/**
 * Make a figure answerable: [inspect] draws FLines the way `lines` does and then *names* whatever you
 * click on, in the figure's own coordinates.
 *
 * The problem it solves is the one the panel documents ran into — a figure was drawn, and then the
 * numbers about it were fished back out with `println` because there was no way to ask the picture
 * "which edge is this, and how far along it am I?". `inspect` wires [FLine.onClick] and
 * [FLine.hoverColor] for every line it is given, so a click reports the element under the pointer
 * (see [FLineIntersection]) and writes a readable card into a second output channel.
 */

/**
 * Draw [lines] and report what gets clicked.
 *
 * Same rendering as `Box.lines(...)` — this only adds the interaction — so the figure is unchanged by
 * inspecting it. A click writes a card naming the element into [labelChannel] and, if [onHit] is
 * given, calls that too. Any [FLine.onClick] a line already had still runs, after the card is written.
 *
 * ```kotlin
 * val panels = house.panels().mapIndexed { i, p -> p.toFLine().also { it.label = "panel $i" } }
 * inspect(panels, w = 620.0, h = 420.0)
 * ```
 *
 * Clicking the third panel's lower edge then shows `panel 2 · edge 1 · u 0.62` with the point in the
 * house's own millimetres — which is what the `println` was for.
 *
 * @param lines the figure. `inspect` sets `onClick` and (unless one is already set) `hoverColor` on
 *   each of them, so pass the lines you are drawing rather than copies.
 * @param channel output disclosure for the figure.
 * @param labelChannel output disclosure for the card describing the last click. Defaults to a
 *   sibling of [channel].
 * @param hover colour to flash under the pointer, or `null` to leave hovering alone.
 * @param onHit called with every hit, after the card is written.
 * @param describe renders the card's body; override it to say something domain-specific.
 * @return the lines, so the call can be the last expression in a box.
 */
fun Box.inspect(
    lines: List<FLine>,
    channel: String = "inspect",
    labelChannel: String = "$channel — what you clicked",
    w: Double = -1.0,
    h: Double = -1.0,
    fitToSize: Boolean = true,
    hover: Vec4? = vec(1.0, 0.84, 0.32, 0.9),
    onHit: ((FLineIntersection) -> Unit)? = null,
    describe: (FLineIntersection) -> String = { it.describe() }
): List<FLine> {
    lines.forEachIndexed { index, line ->
        val existing = line.onClick
        if (hover != null && line.hoverColor == null) line.hoverColor = Vec4(hover)
        if (line.label == null) line.label = "line $index"
        line.onClick = { hit ->
            display(labelChannel, hitCardHtml(hit, describe(hit)))
            onHit?.invoke(hit)
            existing?.invoke(hit)
        }
    }
    display(labelChannel, waitingCardHtml())
    this.lines(lines, channel = channel, w = w, h = h, fitToSize = fitToSize)
    return lines
}

/** [inspect] for a handful of lines written out one by one. */
fun Box.inspect(
    vararg lines: FLine,
    channel: String = "inspect",
    labelChannel: String = "$channel — what you clicked",
    w: Double = -1.0,
    h: Double = -1.0,
    fitToSize: Boolean = true,
    hover: Vec4? = vec(1.0, 0.84, 0.32, 0.9),
    onHit: ((FLineIntersection) -> Unit)? = null,
    describe: (FLineIntersection) -> String = { it.describe() }
): List<FLine> = inspect(
    lines.asList(),
    channel = channel,
    labelChannel = labelChannel,
    w = w,
    h = h,
    fitToSize = fitToSize,
    hover = hover,
    onHit = onHit,
    describe = describe
)

/**
 * One line of prose for a hit: what was clicked, and where on it.
 *
 * `panel 2 · edge 1 · u 0.62 · at (1240.0, 380.5, 2100.0)` — the element first, because that is the
 * part a person is looking for, and the point last in the figure's own coordinates. A hit an output
 * could not resolve says so ("somewhere on …") instead of inventing a part.
 */
fun FLineIntersection.describe(): String {
    val name = label ?: if (lineIndex >= 0) "line $lineIndex" else "a line"
    val where = onElement ?: position
    val at = "at (${fmt(where.x)}, ${fmt(where.y)}, ${fmt(where.z)})"
    return when (kind) {
        FLineHitKind.POINT -> "$name · point $elementIndex (node $nodeIndex) · $at"
        FLineHitKind.EDGE -> "$name · edge $elementIndex · u ${fmt(u)} · $at"
        FLineHitKind.FACE -> {
            // Say which convention the numbers follow when it is not the one a reader would assume.
            val convention = if (faceCoordinates == FLineFaceCoordinates.BOUNDING_BOX) " (bbox)" else ""
            "$name · face $elementIndex · u ${fmt(u)} v ${fmt(v)}$convention · $at"
        }
        FLineHitKind.UNKNOWN -> "somewhere on $name · nearest node $closestVertexIndex · $at"
    }
}

private fun fmt(value: Number): String {
    val d = value.toDouble()
    if (!d.isFinite()) return "—"
    return String.format(Locale.US, "%.3f", d).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

private fun escape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private const val CARD =
    "padding:12px 14px;border-radius:12px;border:1px solid rgba(255,255,255,0.08);" +
        "background:rgba(17,20,24,0.9);color:rgba(255,255,255,0.82);font-family:Iosevka,monospace;" +
        "font-size:12.5px;line-height:1.55;"

private fun waitingCardHtml(): String =
    """<div style="$CARD color:rgba(255,255,255,0.5);">Click a part of the figure.</div>"""

private fun hitCardHtml(hit: FLineIntersection, headline: String): String {
    val kindColour = when (hit.kind) {
        FLineHitKind.POINT -> "rgba(255,216,120,0.95)"
        FLineHitKind.EDGE -> "rgba(120,214,255,0.95)"
        FLineHitKind.FACE -> "rgba(190,160,255,0.95)"
        FLineHitKind.UNKNOWN -> "rgba(255,255,255,0.55)"
    }
    val detail = listOfNotNull(
        "line ${hit.lineIndex}".takeIf { hit.lineIndex >= 0 },
        "subpath ${hit.subPathIndex}".takeIf { hit.subPathIndex >= 0 },
        "node ${hit.nodeIndex}".takeIf { hit.nodeIndex >= 0 },
        "nearest node ${hit.closestVertexIndex}".takeIf { hit.closestVertexIndex >= 0 }
    ).joinToString(" · ")
    return """<div style="$CARD">""" +
        """<div style="color:$kindColour;letter-spacing:0.08em;text-transform:uppercase;font-size:10.5px;">${hit.kind.name.lowercase()}</div>""" +
        """<div style="font-size:13.5px;color:rgba(255,255,255,0.92);">${escape(headline)}</div>""" +
        """<div style="color:rgba(255,255,255,0.45);font-size:11px;">${escape(detail)}</div>""" +
        "</div>"
}
