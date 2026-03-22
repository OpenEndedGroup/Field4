package afield.graphics

import afield.FLineEditSpace
import afield.FLineHandleKind
import afield.FLinePointRef
import afield.FLineTranslatePoints
import afield.stdlib.Vec3
import afield.stdlib.vec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class FLineEditSession(
    val sessionId: String,
    private val lines: List<FLine>,
    private val fitTransform: FitTransform?
) {
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

    private fun viewDeltaToModel(dx: Double, dy: Double, dz: Double): Vec3 {
        val scale = fitTransform?.scale?.takeIf { kotlin.math.abs(it) > 1e-6f } ?: 1f
        return vec(dx / scale, dy / scale, dz / scale)
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
    val session = FLineEditSession(
        sessionId = "fline-edit-${UUID.randomUUID()}",
        lines = lines,
        fitTransform = fitTransform
    )
    fLineEditSessions[session.sessionId] = session
    return session
}

internal fun getFLineEditSession(sessionId: String): FLineEditSession? = fLineEditSessions[sessionId]

private fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
