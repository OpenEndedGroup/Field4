package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.TextLayout
import afield.graphics.text.Typeface
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f

/** Prepared typography in font units; all motion stays outside the immutable curve atlas. */
data class ReadingText(
    val text: String,
    val advance: Float,
    val outline: FLine,
    val slug: SlugPath,
    val layout: TextLayout? = null,
    val faces: List<Typeface> = listOfNotNull(layout?.typeface),
    val glyphFaces: Map<Int, Typeface> = emptyMap(),
) {
    private val measurements =
        java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, DiagramTextMetrics>()

    /** Cached font-space metrics; no GPU readback, physics step or native Slug work. */
    fun measure(start: Int = 0, endExclusive: Int = text.length): DiagramTextMetrics =
        measurements.computeIfAbsent(start to endExclusive) { prepareMetrics(start, endExclusive) }
}

data class ReadingLabel(
    val shape: ReadingText,
    val position: Vector3f,
    val opacity: Float,
    val scale: Float,
    val color: Vector4f = Vector4f(1f),
    val rotation: Float = 0f,
    val blur: Float = 0f, // Gaussian standard deviation in ems

)

/** A small CPU scene can share the same per-eye stroke, marker and analytic text renderer. */
interface ReadingScene {
    /** Maximum local blur per prepared shape, captured when the renderer is constructed. */
    val blurRequests: Map<ReadingText, Float> get() = emptyMap()
    val shapes: Map<String, ReadingText>
    val geometry: DiagramGeometry
    val labels: List<ReadingLabel>
    val maximumLabels: Int

    fun update(elapsedSeconds: Double)
}
