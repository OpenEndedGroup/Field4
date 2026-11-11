package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.TextLayout
import afield.graphics.text.Typeface
import afield.graphics.text.slug.SlugPath
import afield.math.linalg.Quaternionf
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
    /** Render-only partitions; the logical run retains its complete layout, outline and measurements. */
    val renderParts: List<ReadingText> = emptyList(),
) {
    /** A partitioned run's slug is its first part; renderers draw these leaves, not that atlas alone. */
    val renderShapes: List<ReadingText> get() = renderParts.ifEmpty { listOf(this) }

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
    val orientation: Quaternionf = Quaternionf(), // full run orientation, before local roll

)

/** A small CPU scene can share the same per-eye stroke, marker and analytic text renderer. */
interface ReadingScene {
    /** Maximum local blur per prepared shape, captured when the renderer is constructed. */
    val blurRequests: Map<ReadingText, Float> get() = emptyMap()
    val shapes: Map<String, ReadingText>
    val geometry: DiagramGeometry
    val labels: List<ReadingLabel>
    val maximumLabels: Int
    /** Current score/physics snapshot, already updated for this render frame. */
    val frostSurfaces: List<ReadingFrostSurface> get() = emptyList()

    val individualTextFacing: Boolean get() = false
    /** Reader position in the diagram's coordinate system; publishing it does not advance physics. */
    fun setReaderPosition(position: Vector3f) {}
    fun update(elapsedSeconds: Double)
}
