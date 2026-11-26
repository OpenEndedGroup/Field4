import afield.io.drawingon.drawingOn
import kotlinx.serialization.json.*

val pad = drawingOn("ipad")
val roomWidth = 400.0
val plan =
    FLine().moveTo(120, 160).lineTo(120 + roomWidth, 160).lineTo(120 + roomWidth, 460).lineTo(120, 460).lineTo(120, 160)
plan.color = vec(1.0, 1.0, 1.0, 0.45)

fun showPlan(extra: List<FLine> = emptyList()) {
    val drawing = [plan] + extra
    pad.publish(drawing, title = "Studio plan")
    lines(drawing, w = 640.0, h = 520.0)
}
showPlan()

val endpoints = mutableMapOf<String, Pair<Double, Double>>()
pad.onEvent { event ->
    if (!event.upload) {
        val stroke = event.data["stroke"]?.jsonPrimitive?.contentOrNull
        if (event.type == "stroke.samples" && stroke != null) {
            val last = event.data["samples"]?.jsonArray?.lastOrNull()?.jsonObject
            val x = last?.get("drawingX")?.jsonPrimitive?.doubleOrNull
            val y = last?.get("drawingY")?.jsonPrimitive?.doubleOrNull
            if (x != null && y != null) endpoints[stroke] = x to y
        }
        if (event.type == "stroke.end" && stroke != null) {
            val point = endpoints.remove(stroke)
            if (point != null && event.data["cancelled"]?.jsonPrimitive?.booleanOrNull != true) {
                val (x, y) = point
                val marker = FLine().moveTo(x - 12, y).lineTo(x + 12, y).moveTo(x, y - 12).lineTo(x, y + 12)
                marker.color = vec(1.0, 1.0, 1.0, 0.8)
                showPlan([marker])
                println("Pencil endpoint ($x, $y) at ${event.time}s")
            }
        }
    }
}
