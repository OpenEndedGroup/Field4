package afield.graphics

import afield.FLineAddLine
import afield.FLineDrawStyle
import afield.FLineEditSpace
import afield.FLineHandleKind
import afield.FLinePoint3
import afield.FLinePointRef
import afield.FLineTranslatePoints
import afield.stdlib.Vec3
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.pointSize
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EditableFLineViewState internal constructor(
    val sessionId: String
) {
    fun currentLines(): List<FLine> = getFLineEditSession(sessionId)?.currentLines() ?: emptyList()
}

internal class FLineEditSession(
    val sessionId: String,
    private val lines: MutableList<FLine>,
    private val fitTransform: FitTransform?,
    private val drawStyle: FLineDrawStyle
) {
    fun currentLines(): List<FLine> = synchronized(this) { lines.toList() }

    fun drawStyle(): FLineDrawStyle = drawStyle.copy(color = drawStyle.color.copyOf())

    fun translate(update: FLineTranslatePoints) {
        val refs = update.points.distinct()
        if (refs.isEmpty()) return
        val delta = when (update.space) {
            FLineEditSpace.MODEL -> vec(update.dx, update.dy, update.dz)
            FLineEditSpace.VIEW -> viewDeltaToModel(update.dx, update.dy, update.dz)
        }
        if (!delta.isFinite()) return

        val touched = LinkedHashSet<FLine>()
        synchronized(this) {
            refs.forEach { ref ->
                val point = resolve(ref) ?: return@forEach
                point.add(delta)
                touched += lines[ref.lineIndex]
            }
            touched.forEach { it.rev++ }
        }
    }

    fun addLine(update: FLineAddLine): FLine? {
        val sourcePoints = update.points.distinct()
        if (sourcePoints.size < 2) return null

        val modelPoints = sourcePoints.map { point ->
            when (update.space) {
                FLineEditSpace.MODEL -> vec(point.x, point.y, point.z)
                FLineEditSpace.VIEW -> viewPointToModel(point)
            }
        }.filter { it.isFinite() }

        if (modelPoints.size < 2) return null

        val style = update.style ?: drawStyle
        val line = FLine().apply {
            color = style.color.toVec4()
            thickness = style.thickness
            stroked = style.stroked
            filled = style.filled
            pointed = style.pointed
            pointSize = style.pointSize
            moveTo(modelPoints.first())
            modelPoints.drop(1).forEach { lineTo(it) }
            if (update.closed && modelPoints.size >= 3) close()
        }

        synchronized(this) {
            lines += line
        }
        return line
    }

    fun clear() {
        synchronized(this) {
            lines.clear()
        }
    }

    private fun viewDeltaToModel(dx: Double, dy: Double, dz: Double): Vec3 {
        val scale = fitTransform?.scale?.takeIf { kotlin.math.abs(it) > 1e-6f } ?: 1f
        return vec(dx / scale, dy / scale, dz / scale)
    }

    private fun viewPointToModel(point: FLinePoint3): Vec3 {
        val scale = fitTransform?.scale?.takeIf { kotlin.math.abs(it) > 1e-6f } ?: 1f
        val offsetX = fitTransform?.offsetX ?: 0f
        val offsetY = fitTransform?.offsetY ?: 0f
        return vec(point.x / scale - offsetX, point.y / scale - offsetY, point.z)
    }

    private fun resolve(ref: FLinePointRef): Vec3? {
        val line = lines.getOrNull(ref.lineIndex) ?: return null
        val node = line.nodes.getOrNull(ref.nodeIndex) ?: return null
        return when (ref.handle) {
            FLineHandleKind.TO -> if (node is FLine.Close) null else node.to
            FLineHandleKind.C1 -> (node as? FLine.CubicTo)?.c1
            FLineHandleKind.C2 -> (node as? FLine.CubicTo)?.c2
        }
    }
}

private val fLineEditSessions = ConcurrentHashMap<String, FLineEditSession>()

internal fun registerFLineEditSession(lines: List<FLine>, fitTransform: FitTransform?): FLineEditSession {
    val mutableLines = lines.toMutableList()
    val session = FLineEditSession(
        sessionId = "fline-edit-${UUID.randomUUID()}",
        lines = mutableLines,
        fitTransform = fitTransform,
        drawStyle = defaultDrawStyleFor(mutableLines)
    )
    fLineEditSessions[session.sessionId] = session
    return session
}

internal fun getFLineEditSession(sessionId: String): FLineEditSession? = fLineEditSessions[sessionId]

private fun defaultDrawStyleFor(lines: List<FLine>): FLineDrawStyle {
    val template = lines.lastOrNull() ?: return FLineDrawStyle()
    val color = template.color
    return FLineDrawStyle(
        color = floatArrayOf(color.x, color.y, color.z, color.w),
        thickness = template.thickness ?: 2.0,
        stroked = true,
        filled = false,
        pointed = template.pointed,
        pointSize = template.pointSize
    )
}

private fun FloatArray.toVec4() = afield.stdlib.vec(
    getOrElse(0) { 1f },
    getOrElse(1) { 1f },
    getOrElse(2) { 1f },
    getOrElse(3) { 1f }
)

private fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
