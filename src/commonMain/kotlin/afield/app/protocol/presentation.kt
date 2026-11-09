package afield.app.protocol

import kotlinx.serialization.Serializable

/** The audience surface attached to an ordinary guide step. */
@Serializable
data class AudienceContent(
    val type: String = "html",
    val html: String = "",
    val url: String = "",
    val videoId: String = "",
    val document: String = "",
    val rect: List<Double>? = null,
    val macro: AudienceMacro? = null,
    val interactiveGuide: Boolean = false,
    /** Live box outputs placed in this HTML slide's figure slots; see [AudienceFigure]. */
    val figures: List<AudienceFigure> = emptyList()
)

/**
 * One live figure on an HTML slide: the output area of the box [box] (a uid in the presentation's own
 * document), shown in its own native view over the slot whose `data-field-figure` attribute is [id].
 * [channels] limits it to those output channels; empty shows every channel the box displays. [rect],
 * a fraction of the audience window, places it explicitly instead of measuring the slot. [name] is the
 * box name the deck used, kept for search and for exporting the slide later.
 */
@Serializable
data class AudienceFigure(
    val id: String,
    val box: String,
    val name: String,
    val channels: List<String> = emptyList(),
    val rect: List<Double>? = null
)

/**
 * The scale a figure page (`index.html?figure=`) gives a box's output area so all of it fits a
 * [viewWidth] × [viewHeight] view, with a small margin, between 0.2× and 4×.
 *
 * The area's size is the larger of its laid-out size and its content size ([scrollWidth],
 * [scrollHeight]): an output wider than the area (an editable drawing, a wide plot) overflows it, and
 * fitting the laid-out size alone would crop the overflow off the slide. Null while the area has no
 * size yet.
 */
fun figureFitZoom(
    offsetWidth: Double, offsetHeight: Double, scrollWidth: Double, scrollHeight: Double,
    viewWidth: Double, viewHeight: Double
): Double? {
    val width = maxOf(offsetWidth, scrollWidth)
    val height = maxOf(offsetHeight, scrollHeight)
    if (width < 1 || height < 1) return null
    return (minOf(viewWidth / width, viewHeight / height) * 0.96).coerceIn(0.2, 4.0)
}

@Serializable
data class AudienceMacro(val name: String, val scope: String = "document", val project: String = "")

/** Guide navigation remains on the JVM; Electron only renders its current audience surface. */
@Serializable
data class PresentationState(
    val slideshowId: String,
    val hostUid: String,
    val title: String,
    val index: Int,
    val titles: List<String>,
    val content: AudienceContent? = null,
    val transitionMs: Int = 450,
    val sectionPaths: List<List<String>> = emptyList(),
    val stepIds: List<String> = emptyList(),
    val cueIndex: Int = 0,
    val cueTitles: List<String> = emptyList(),
    val cueAction: Boolean = false,
    val checkpoint: String = "",
    val searchTexts: List<String> = emptyList()
)
