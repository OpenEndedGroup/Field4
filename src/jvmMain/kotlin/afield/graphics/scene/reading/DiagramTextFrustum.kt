package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f

/**
 * Two text-fitted rectangular ends and four connecting rails. Parallel text planes produce a
 * rectangular pyramid frustum; independently tilted ends produce a flexible, skewed text holder.
 * Text, padding, frosting, sliding attachments and rails resolve from the SAME current frame.
 */
class DiagramTextFrustum internal constructor(
    val id: String,
    val front: DiagramText,
    val back: DiagramText,
    val frontFrame: DiagramTextFrame,
    val backFrame: DiagramTextFrame,
    val rails: DiagramPath,
    val group: DiagramGroup,
) {
    /** Null follows the front's natural padded aspect; a fixed aspect expands each end to fit. */
    var aspectRatio: Float? = null
        set(value) { require(value == null || value.isFinite() && value > 0f); field = value }

    var width: Float
        get() = rails.width
        set(value) { rails.width = value; frontFrame.path.width = value; backFrame.path.width = value }

    var color: Vector4f
        get() = rails.color
        set(value) {
            rails.color = Vector4f(value)
            frontFrame.path.color = Vector4f(value)
            backFrame.path.color = Vector4f(value)
        }

    /** Each end remains an independent clipped backdrop surface, including its own black floor. */
    fun frost(block: ReadingFrostSettings.() -> Unit) {
        frontFrame.frost.apply(block)
        backFrame.frost.apply(block)
    }

    internal fun outline(frame: DiagramFrame): FLine {
        val near = frontFrame.corners(frame).map { it.add(frame.pose(frontFrame.path).offset) }
        val far = backFrame.corners(frame).map { it.add(frame.pose(backFrame.path).offset) }
        val matched = readingMatchFrustumCorners(near, far)
        return FLine().apply {
            near.indices.forEach { i -> moveTo(near[i]); lineTo(matched[i]) }
        }
    }
}

/**
 * The end texts already own their physics anchors, scale and orientation. Their separation sets
 * depth; their measured sizes set taper. One aspect ratio keeps the two fitted rectangles similar.
 * Flip the back text's orientation by PI around Y to read it from outside the opposite end.
 */
fun ReadingDiagramBuilder.textFrustum(
    id: String,
    front: DiagramText,
    back: DiagramText,
    standoff: Float = .02f,
    block: DiagramTextFrustum.() -> Unit = {},
): DiagramTextFrustum {
    require(front !== back) { "A text frustum needs two distinct end runs" }
    lateinit var holder: DiagramTextFrustum
    val near = textFrame("$id-front-frame", front, standoff)
    val far = textFrame("$id-back-frame", back, standoff)
    val rails = path("$id-rails", shape = { holder.outline(this) }) { width = .0008f }
    val group = group(id, listOf(front, back, near.path, far.path, rails))
    holder = DiagramTextFrustum(id, front, back, near, far, rails, group)
    val aspect: (DiagramFrame) -> Double = { frame ->
        holder.aspectRatio?.toDouble() ?: near.naturalAspect(frame)
    }
    near.boundsAspect = aspect
    far.boundsAspect = aspect
    holder.apply(block)
    textFrusta.add(holder)
    return holder
}

/** Choose winding AND cyclic offset, so a back-facing label never twists the rails across itself. */
internal fun readingMatchFrustumCorners(near: List<Vector3f>, far: List<Vector3f>): List<Vector3f> {
    require(near.size == 4 && far.size == 4)
    return listOf(1, -1).flatMap { direction ->
        (0..3).map { start -> (0..3).map { i -> far[Math.floorMod(start + direction * i, 4)] } }
    }.minBy { order -> near.indices.sumOf { i -> near[i].distanceSquared(order[i]).toDouble() } }
}
