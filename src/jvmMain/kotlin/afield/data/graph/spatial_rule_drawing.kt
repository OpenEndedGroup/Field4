package afield.data.graph

import afield.geometry.path.FLine
import afield.graphics.render.circle
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.label
import afield.graphics.render.onClick
import afield.graphics.render.pickDetail
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4

/** A colored path through 3D positions. The eventual drawing surface decides how to project it. */
fun spatialStroke(points: List<Vec3>, color: Vec4, width: Double = 1.0, opacity: Double = 1.0): FLine =
    FLine().apply {
        for ((i, point) in points.withIndex()) {
            if (i == 0) moveTo(point) else lineTo(point)
        }
        this.color = Vec4(color.x, color.y, color.z, color.w * opacity.coerceIn(0.0, 1.0).toFloat())
        thickness = width
    }

/** A filled marker in the XY plane at [at], including its Z coordinate. */
fun spatialDot(at: Vec3, radius: Double, color: Vec4): FLine =
    FLine().apply {
        circle(0, 0, radius, segments = 16)
        nodes.forEach { node -> node.storage.forEach { it.add(at) } }
        this.color = Vec4(color)
        filled = true
        stroked = false
    }

/** Explain the published fact when the reader clicks its FLine. */
fun SpatialRuleWorld.inspectOnClick(
    fact: SpatialFact,
    line: FLine,
    show: (SpatialExplanation) -> Unit,
): FLine {
    val snapshot = explain(fact) ?: return line
    val label = "spatial-fact-${fact.id}"
    line.label = label
    line.pickDetail = false
    // Reuse the output's callback slots. A click from an older frame can arrive after a slot changes hands.
    line.onClick = { hit -> if (hit.label == label) show(snapshot) }
    return line
}
