import afield.graphics.render.color
import afield.io.drawingon.DrawingOnCapture
import afield.io.drawingon.DrawingOnNetworkConfig
import afield.io.drawingon.drawingOn
import afield.math.linalg.vec
import afield.ui.visualization.lines

// Paste into its own Field box and set the iPad's Field target to "network".
val pad = drawingOn("network")
val config = DrawingOnNetworkConfig()
var activeSession: String? = null
var activeTake: DrawingOnCapture? = null

pad.onEvent { event ->
    if (!event.upload && event.type in setOf("stroke.end", "stroke.correct", "stroke.visibility", "layers.state")) {
        if (activeSession != event.session) {
            activeTake = pad.capture(event.session)
            activeSession = event.session
        }
        val take = checkNotNull(activeTake).refresh()
        val graph = take.networkAt(config = config)
        val paths = graph.toFLines().onEach { it.color = vec(1, 1, 1, 0.55) }
        lines(paths, w = 560.0, h = 360.0, fitToSize = true)
        pad.publish(paths, title = "Clean drawing")
    }
}
