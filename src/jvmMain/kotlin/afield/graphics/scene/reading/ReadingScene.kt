package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.text.TextLayout
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
)

data class ReadingLabel(
    val shape: ReadingText,
    val position: Vector3f,
    val opacity: Float,
    val scale: Float,
    val color: Vector4f = Vector4f(1f),
    val rotation: Float = 0f,
)

/** A small CPU scene can share the same per-eye stroke, marker and analytic text renderer. */
interface ReadingScene {
    val shapes: Map<String, ReadingText>
    val geometry: DiagramGeometry
    val labels: List<ReadingLabel>
    val maximumLabels: Int

    fun update(elapsedSeconds: Double)
}
