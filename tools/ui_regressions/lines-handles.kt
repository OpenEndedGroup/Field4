// Named drag handles on a lines drawing: "low" and "high" are vertical lines 0.02 apart (a fifth of a pixel at
// this size), "high" with the higher priority; "pt" is a point. `lines-handles.js` drags them by hand and by name.
import afield.ui.visualization.LinesHandle

val handleAt = linkedMapOf("low" to 30.0, "high" to 30.02)
var handlePoint = 60.0 to 20.0
val handleLog = mutableListOf<String>()
fun drawHandles() {
    val drawing = handleAt.values.map { x -> fline { moveTo(x, 0.0); lineTo(x, 40.0) } } +
        fline { val (px, py) = handlePoint; moveTo(px - 1, py - 1); lineTo(px + 1, py - 1); lineTo(px + 1, py + 1); lineTo(px - 1, py + 1); lineTo(px - 1, py - 1) } +
        fline { moveTo(0.0, 0.0); lineTo(80.0, 0.0); lineTo(80.0, 40.0); lineTo(0.0, 40.0); lineTo(0.0, 0.0) }
    lines(drawing, channel = "handles", w = 640.0, h = 320.0, fitToSize = true,
        framing = afield.ui.visualization.LinesFraming.fixed(0.0, 0.0, 80.0, 40.0),
        handles = listOf(
            LinesHandle.x("low", handleAt.getValue("low")),
            LinesHandle.x("high", handleAt.getValue("high"), priority = 1),
            LinesHandle.xy("pt", handlePoint.first, handlePoint.second),
        ),
        onDrag = { e ->
            when (e.handle) {
                null -> {}
                "pt" -> handlePoint = e.x to e.y
                else -> handleAt[e.handle!!] = e.value
            }
            handleLog += "${e.phase.name.lowercase()}:${e.handle}"
            drawHandles()
        })
    val state = "{\"low\":${handleAt["low"]},\"high\":${handleAt["high"]},\"pt\":[${handlePoint.first},${handlePoint.second}]," +
        "\"log\":[${handleLog.joinToString(",") { "\"$it\"" }}]}"
    display("state", "<pre id='lines-handles-state'>$state</pre>")
}
drawHandles()
