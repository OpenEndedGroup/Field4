package afield.data.graph

import afield.document.graph.Box
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
import afield.runtime.lifecycle.AnimationClock
import afield.runtime.lifecycle.Frames
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.loop
import afield.ui.output.displayOpen
import afield.ui.visualization.LinesDragEvent
import afield.ui.visualization.LinesFraming
import afield.ui.visualization.lines
import java.util.concurrent.ConcurrentLinkedQueue

/** A colored path through 3D positions. The eventual drawing surface decides how to project it. */
fun spatialStroke(points: List<Vec3>, color: Vec4, width: Double = 1.0, opacity: Double = 1.0): FLine =
    FLine().apply {
        for ((i, point) in points.withIndex()) {
            if (i == 0) moveTo(point) else lineTo(point)
        }
        this.color = Vec4(color.x, color.y, color.z, color.w * opacity.coerceIn(0.0, 1.0).toFloat())
        thickness = width
    }

/** A straight stroke along [edge], from its committed endpoints. */
fun spatialStroke(edge: SpatialEdge, color: Vec4, width: Double = 1.0, opacity: Double = 1.0): FLine =
    spatialStroke(listOf(edge.from.pos, edge.to.pos), color, width, opacity)

/** A filled marker in the XY plane at [at], including its Z coordinate. */
fun spatialDot(at: Vec3, radius: Double, color: Vec4, opacity: Double = 1.0): FLine =
    FLine().apply {
        circle(0, 0, radius, segments = 16)
        nodes.forEach { node -> node.storage.forEach { it.add(at) } }
        this.color = Vec4(color.x, color.y, color.z, color.w * opacity.coerceIn(0.0, 1.0).toFloat())
        filled = true
        stroked = false
    }

/** A filled polygon through [points], closed back to the first. */
fun spatialFill(points: List<Vec3>, color: Vec4, opacity: Double = 1.0): FLine =
    spatialStroke(points, color, opacity = opacity).apply {
        close()
        filled = true
        stroked = false
    }

/** [triangle] filled, from its committed corners. */
fun spatialFill(triangle: SpatialTriangle, color: Vec4, opacity: Double = 1.0): FLine =
    spatialFill(triangle.points.map(SpatialPoint::pos), color, opacity)

/**
 * Step [world] on the animation clock and draw what it returns into [channel], at most [fps]
 * times a second. World coordinates are the drawing's pixels, from (0, 0) to ([w], [h]), unless
 * [framing] says otherwise. [after] receives each frame's lines once they are drawn, for a readout
 * beneath the drawing. [onPress] receives the world position of each press on the drawing; it runs
 * between frames, so it may read the world and change variables the processes read. Closing the
 * loop (stopping the box) closes the world.
 */
fun Box.play(
    world: SpatialRuleWorld,
    channel: String = "spatial",
    w: Double = 600.0,
    h: Double = 360.0,
    fps: Double = 30.0,
    framing: LinesFraming = LinesFraming.Fixed(0.0, 0.0, w, h),
    after: (List<FLine>) -> Unit = {},
    onPress: ((Vec3) -> Unit)? = null,
): Frames {
    require(fps > 0.0) { "fps must be positive" }
    displayOpen(channel)
    val presses = ConcurrentLinkedQueue<Vec3>()
    val onDrag: ((LinesDragEvent) -> Unit)? = onPress?.let {
        { event -> if (event.phase == LinesDragEvent.Phase.START) presses.add(Vec3(event.x.toFloat(), event.y.toFloat(), 0f)) }
    }
    var last = Double.NEGATIVE_INFINITY
    return loop {
        if (onPress != null) while (true) onPress(presses.poll() ?: break)
        val time = AnimationClock.nowSeconds()
        if (time - last + 1e-6 >= 1.0 / fps) {
            last = time
            val drawing = world.step(time)
            lines(drawing, channel = channel, w = w, h = h, fitToSize = true, framing = framing, onDrag = onDrag)
            after(drawing)
        }
    }.end { world.close() }
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
