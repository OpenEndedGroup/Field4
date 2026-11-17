package afield.ai.agent

import afield.app.protocol.Rect
import afield.document.graph.Box
import afield.ui.notation.Mark
import afield.ui.notation.MarkType
import afield.ui.notation.notation
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlin.math.max

/**
 * AI4's two tools for the drawing layer (Alt+D): [ReadNotationRequest] reads the document's Excalidraw
 * scene as compact mark summaries, and [ViewNotationRequest] takes a picture of one part of it,
 * cropped to a mark, a box or a world rectangle.
 *
 * Both read through the box-facing [afield.ui.notation.Notation] API, from the root box, so what the
 * agent is told matches what a box's `notation.marks` sees. The picture comes from the same headless
 * capture page as [ViewBoxOutputRequest] ([BoxOutputCapture.captureRegion]), whose canvas camera is
 * moved to frame the region; the user's camera is never touched.
 */

@JsonClassDescription("Read the drawing layer: the marks (strokes, arrows, lines, rectangles, ellipses, text, frames) people and boxes draw over the canvas with Alt+D. Returns one summary per mark: id, kind, label (its name or text), world bounds [x, y, w, h] in canvas units (the same units as box frames), the box it sits over, the boxes an arrow runs from and to, the box that owns a mark code drew, the box it is anchored to, its colour, and optionally its points. Use it whenever the user refers to something they drew, circled, wrote or pointed at on the canvas, before answering or editing; then look at it with ViewNotationRequest. Filters combine: name (a mark's label, e.g. `score`), over (a box uid or name, e.g. `Synth`), region [x, y, w, h], kinds (e.g. [`arrow`, `stroke`]). An empty drawing is not an error: it returns no marks and says so. An unknown box in `over` fails with the list of known boxes.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.READ_DOCUMENT])
data class ReadNotationRequest(
    @JsonPropertyDescription("Only marks labelled this (a name code gave it, its own text, or the text bound to it); case-insensitive.")
    val name: String = "",
    @JsonPropertyDescription("Only marks whose centre lies over this box (uid or unique name).")
    val over: String = "",
    @JsonPropertyDescription("Only marks whose bounds intersect this world rectangle, [x, y, w, h].")
    val region: List<Double> = emptyList(),
    @JsonPropertyDescription("Only these kinds: rectangle, ellipse, diamond, line, arrow, stroke, text, frame, image.")
    val kinds: List<String> = emptyList(),
    @JsonPropertyDescription("Also return each line, arrow and stroke's world points, thinned to at most maxPoints per mark.")
    val includePoints: Boolean = false,
    val maxPoints: Int = 32,
    @JsonPropertyDescription("At most this many marks, front-most last; clamped to 1..500.")
    val maxMarks: Int = 200,
)

@JsonClassDescription("Look at part of the drawing layer (the Alt+D marks over the canvas) as a picture cropped to it. Returns the picture, attached for you to inspect, plus the world region shown, the zoom it was drawn at, and the ids of the marks inside it. Frame it by mark (an id or label from ReadNotationRequest, e.g. `score`), by box (its frame and everything drawn over it), or by region [x, y, w, h] in world units; with none of them it frames the whole drawing. Use it after ReadNotationRequest whenever the shape of what was drawn matters: a sketch, a circled area, handwriting. Boxes under the marks are shown unless showBoxes = false. It uses an independent browser, so the user's camera is not moved. Fails if the drawing is empty, if the mark or box is unknown, or if the drawing layer did not load in the capture page.")
@afield.ai.tools.AgentToolMeta("graph", effects = [afield.ai.tools.ToolEffect.CAPTURE_IMAGE])
data class ViewNotationRequest(
    @JsonPropertyDescription("A mark id, or a mark label (its name or text). Frames that mark.")
    val mark: String = "",
    @JsonPropertyDescription("A box uid or unique name. Frames the box together with the marks over it.")
    val box: String = "",
    @JsonPropertyDescription("A world rectangle [x, y, w, h] to frame.")
    val region: List<Double> = emptyList(),
    @JsonPropertyDescription("World units added around the framed area on every side; clamped to 0..400.")
    val padding: Double = 24.0,
    @JsonPropertyDescription("Show the boxes under the drawing (default), or hide them to see the ink alone.")
    val showBoxes: Boolean = true,
    @JsonPropertyDescription("What you are looking for. Travels with the picture as its caption.")
    val note: String = "",
)

/** One mark as [ReadNotationRequest] reports it. Box references are names, with the uid beside them. */
data class NotationMarkSummary(
    val id: String,
    val kind: String,
    val label: String? = null,
    /** World bounds `[x, y, w, h]`, rounded to 0.1. */
    val bounds: List<Double>,
    val over: String? = null,
    val from: String? = null,
    val to: String? = null,
    val owner: String? = null,
    val anchoredTo: String? = null,
    val frame: String? = null,
    val stroke: String? = null,
    val fill: String? = null,
    val handDrawn: Boolean,
    val pointCount: Int? = null,
    val length: Double? = null,
    val points: List<List<Double>>? = null,
)

data class NotationReadResult(
    val success: Boolean,
    val message: String,
    /** Live marks in the whole drawing, before filtering. */
    val total: Int = 0,
    /** Marks that passed the filters, before [NotationMarkSummary] truncation to maxMarks. */
    val matched: Int = 0,
    /** World bounds of every live mark, `[x, y, w, h]`; empty when there are none. */
    val drawingBounds: List<Double> = emptyList(),
    val marks: List<NotationMarkSummary> = emptyList(),
)

/** What [ViewNotationRequest] returns: the picture as a tool image, and what it frames. */
data class NotationView(
    val success: Boolean,
    val message: String,
    /** The world rectangle the picture shows, `[x, y, w, h]`. */
    val region: List<Double> = emptyList(),
    /** Screen pixels per world unit in the picture. */
    val zoom: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val inkFraction: Double = 0.0,
    val dominantColours: List<String> = emptyList(),
    /** Ids of the marks whose bounds intersect [region]. */
    val marks: List<String> = emptyList(),
    val path: String = "",
    val imageAttached: Boolean = false,
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val dataUrl: String = "",
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val note: String = "",
) : afield.ai.tools.CarriesToolImages, afield.ai.tools.SummarisesForToolLog {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    override val toolImages: List<afield.ai.tools.ToolImage>
        get() = if (dataUrl.isBlank()) emptyList() else listOf(afield.ai.tools.ToolImage(dataUrl,
            "Drawing layer, world region ${region.joinToString(", ", "[", "]")} at zoom ${"%.2f".format(zoom)}, ${width}x$height, " +
                "captured in an independent browser. " + captureReadingInstruction(note)))

    override fun toolLogSummary(): String =
        "success=$success images=${toolImages.size} region=$region zoom=${"%.2f".format(zoom)} ${width}x$height marks=${marks.size} path=$path" +
            (if (!success) " message=${message.take(160)}" else "")
}

/** A picture of a world rectangle of the canvas, drawing layer included, or the reason there is none. */
data class RegionShot(
    val success: Boolean,
    val message: String,
    val image: java.awt.image.BufferedImage? = null,
    val path: String = "",
    val zoom: Double = 0.0,
) {
    companion object {
        fun failed(message: String) = RegionShot(false, message)
    }
}

private fun Double.r1(): Double = Math.round(this * 10.0) / 10.0

private fun Rect.list(): List<Double> = listOf(x.r1(), y.r1(), w.r1(), h.r1())

private fun Box.ref(): String = if (name.isBlank() || name == id) id else "$name ($id)"

private fun List<Double>.toRect(what: String): Rect {
    require(size == 4 && all { it.isFinite() }) { "$what must be [x, y, w, h], not $this" }
    require(this[2] > 0 && this[3] > 0) { "$what must have a positive width and height, not $this" }
    return Rect(this[0], this[1], this[2], this[3])
}

internal fun union(rects: List<Rect>): Rect? {
    if (rects.isEmpty()) return null
    val x0 = rects.minOf { it.x }; val y0 = rects.minOf { it.y }
    val x1 = rects.maxOf { it.x + it.w }; val y1 = rects.maxOf { it.y + it.h }
    return Rect(x0, y0, x1 - x0, y1 - y0)
}

/** Whether [a] and [b] overlap, counting a zero-width or zero-height mark on the edge as inside. */
internal fun touches(a: Rect, b: Rect): Boolean =
    a.x <= b.x + b.w && b.x <= a.x + a.w && a.y <= b.y + b.h && b.y <= a.y + a.h

private fun thin(points: List<List<Double>>, max: Int): List<List<Double>> {
    if (points.size <= max || max < 2) return points
    return (0 until max).map { i -> points[Math.round(i * (points.size - 1).toDouble() / (max - 1)).toInt()] }
}

private fun summarise(m: Mark, includePoints: Boolean, maxPoints: Int): NotationMarkSummary {
    val linear = m.kind.isLinear
    val pts = if (linear) m.points.map { listOf(it.x.toDouble().r1(), it.y.toDouble().r1()) } else emptyList()
    val arrowish = m.kind == MarkType.ARROW || m.kind == MarkType.LINE
    return NotationMarkSummary(
        id = m.id,
        kind = m.kind.name.lowercase(),
        label = m.label?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
        bounds = m.bounds.list(),
        over = m.box?.ref(),
        from = if (arrowish) m.fromBox?.ref() else null,
        to = if (arrowish) m.toBox?.ref() else null,
        owner = m.owner?.ref() ?: m.ownerId,
        anchoredTo = m.anchoredTo?.ref(),
        frame = m.frame?.let { it.label ?: it.id },
        stroke = m.stroke,
        fill = m.fill.takeIf { it != "transparent" },
        handDrawn = m.handDrawn,
        pointCount = if (linear) pts.size else null,
        length = if (linear) m.length.r1() else null,
        points = if (linear && includePoints) thin(pts, maxPoints.coerceIn(2, 2000)) else null,
    )
}

private val KIND_NAMES = MarkType.entries.associateBy { it.name.lowercase() } + mapOf("freedraw" to MarkType.STROKE)

/** [ReadNotationRequest]. */
fun FieldGraphTools.readNotation(req: ReadNotationRequest): NotationReadResult = onOwner {
    val notation = info.root.notation
    val all = notation.marks
    val kinds = req.kinds.map { k ->
        KIND_NAMES[k.trim().lowercase().removeSuffix("s")] ?: KIND_NAMES[k.trim().lowercase()]
            ?: throw IllegalArgumentException("Unknown kind '$k'. Kinds: ${KIND_NAMES.keys.joinToString()}")
    }.toSet()
    val overBox = req.over.takeIf { it.isNotBlank() }?.let(::resolve)
    val region = req.region.takeIf { it.isNotEmpty() }?.toRect("region")
    val overIds = overBox?.let { b -> notation.over(b).map { it.id }.toSet() }
    val matched = all.filter { m ->
        (req.name.isBlank() || m.label?.trim().equals(req.name.trim(), ignoreCase = true)) &&
            (kinds.isEmpty() || m.kind in kinds) &&
            (overIds == null || m.id in overIds) &&
            (region == null || touches(m.bounds, region))
    }
    val limit = req.maxMarks.coerceIn(1, 500)
    val shown = matched.takeLast(limit)
    val filtered = req.name.isNotBlank() || kinds.isNotEmpty() || overBox != null || region != null
    NotationReadResult(
        success = true,
        message = when {
            all.isEmpty() -> "The drawing layer is empty: nothing has been drawn in ${info.documentName}."
            matched.isEmpty() -> "No mark matched; the drawing has ${all.size} mark(s). Drop a filter, or read drawingBounds."
            else -> "${matched.size} of ${all.size} mark(s)" + (if (filtered) " matched" else "") +
                (if (shown.size < matched.size) "; the front-most ${shown.size} are listed" else "") +
                ". Bounds are world [x, y, w, h], the units of box frames. Look at them with ViewNotationRequest."
        },
        total = all.size,
        matched = matched.size,
        drawingBounds = union(all.map { it.bounds })?.list().orEmpty(),
        marks = shown.map { summarise(it, req.includePoints, req.maxPoints) },
    )
}

/** [ViewNotationRequest]. */
fun FieldGraphTools.viewNotation(req: ViewNotationRequest): NotationView {
    val chosen = listOf(req.mark, req.box).count { it.isNotBlank() } + (if (req.region.isNotEmpty()) 1 else 0)
    require(chosen <= 1) { "Give one of mark, box or region, not several." }
    val padding = req.padding.coerceIn(0.0, 400.0)
    val (framed, live) = onOwner {
        val notation = info.root.notation
        val all = notation.marks
        val target: Rect = when {
            req.mark.isNotBlank() -> {
                val m = notation[req.mark.trim()] ?: notation.named(req.mark)
                    ?: throw IllegalArgumentException("No mark '${req.mark}'. Marks: " +
                        all.joinToString(", ") { (it.label?.take(30) ?: it.kind.name.lowercase()) + " (${it.id})" }.take(600).ifEmpty { "none" })
                m.bounds
            }
            req.box.isNotBlank() -> {
                val b = resolve(req.box)
                union(listOf(b.frame) + notation.over(b).map { it.bounds })!!
            }
            req.region.isNotEmpty() -> req.region.toRect("region")
            else -> union(all.map { it.bounds })
                ?: throw IllegalArgumentException("The drawing layer is empty: there is nothing to look at.")
        }
        // A line along an axis has zero width or height; give it room to be seen.
        val padded = Rect(target.x - padding, target.y - padding, max(target.w, 1.0) + 2 * padding, max(target.h, 1.0) + 2 * padding)
        (padded to all.filter { touches(it.bounds, padded) }.map { it.id }) to all.size
    }
    val (target, inside) = framed
    val shot = captureRegion(target, req.showBoxes, live)
    val image = shot.image
    if (!shot.success || image == null) return NotationView(false, shot.message, region = target.list(), marks = inside)
    val stats = PictureStats.of(image)
    val blank = stats.inkFraction < 0.002
    return NotationView(
        success = true,
        message = "Captured world region ${target.list()} at zoom ${"%.2f".format(shot.zoom)}, ${image.width}x${image.height}, " +
            "holding ${inside.size} mark(s)" +
            (if (blank) ". It looks BLANK: almost every pixel is the background colour ${stats.dominant.firstOrNull()?.first ?: ""}." else ".") +
            " The picture is attached; it was ${shot.message}. The user's view is unchanged.",
        region = target.list(), zoom = shot.zoom, width = image.width, height = image.height,
        inkFraction = stats.inkFraction,
        dominantColours = stats.dominant.map { (hex, share) -> "$hex ${"%.1f".format(share * 100)}%" },
        marks = inside, path = shot.path, imageAttached = true, dataUrl = jpegDataUrl(image), note = req.note,
    )
}

/** The tool names [registerNotationTools] adds. */
val notationToolNames: List<String> = listOf(ReadNotationRequest::class, ViewNotationRequest::class).map { it.java.simpleName }

internal fun registerNotationTools(tools: afield.ai.tools.ToolRegistry) {
    tools.register(ReadNotationRequest::class.java, { ctx -> ctx.fieldGraph != null }) { ctx, req -> ctx.fieldGraph!!.readNotation(req) }
    tools.register(ViewNotationRequest::class.java, { ctx -> ctx.fieldGraph?.seesOutput == true }) { ctx, req -> ctx.fieldGraph!!.viewNotation(req) }
}

