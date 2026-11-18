import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.io.drawingon.drawingOn
import afield.math.linalg.vec

val pad = drawingOn("ipad")

val plan = FLine().moveTo(120, 160).lineTo(520, 160).lineTo(520, 460).lineTo(120, 460).lineTo(120, 160)
plan.color = vec(1.0, 1.0, 1.0, 0.35)
pad.publish(listOf(plan), title = "Studio plan")

pad.onEvent { event ->
    if (event.type == "stroke.end" && !event.upload) {
        val response = FLine().moveTo(160, 200).lineTo(480, 420)
        response.color = vec(1.0, 1.0, 1.0, 0.6)
        pad.publish(listOf(plan, response), title = "Studio plan + response")
    }
}
