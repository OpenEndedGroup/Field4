import afield.graphics.interaction.*
import afield.graphics.scene.reading.*
import afield.graphics.vulkan.util.WindowPointer

// Point at the reading space: the mouse over the desktop mirror, or the right controller in a headset.
// The hovered item stays bright and everything else dims; a click prints what was clicked.
val pointingDrawing = drawing
val pointingPlayer = sumPlayer
val pointingMouse = stereo.mirrorPointer(WindowPointer(window, "reading-pointer"), stereoPair = { showStereoPair })
val pointingController = stereo.pointer()
val pointing = DiagramPointerInteraction({ ray -> pointingDrawing.pick(ray, pointingPlayer) })
val pointingOverride = arrayOfNulls<Pointer>(1) // a fixed pointer for captures and tests; empty uses the device
val pointingLog = mutableListOf<String>()
val dimmed = HashMap<DiagramItem, Float>()
fun restoreDimmed() {
    for ((item, alpha) in dimmed) when (item) {
        is DiagramText -> item.color = Vector4f(item.color).apply { w = alpha }
        is DiagramPath -> item.color = Vector4f(item.color).apply { w = alpha }
        is DiagramDot -> item.color = Vector4f(item.color).apply { w = alpha }
        else -> {}
    }
    dimmed.clear()
}
fun dimAllBut(keep: DiagramItem) {
    val d = pointingPlayer.diagram
    for (item in d.texts + d.paths + d.dots) if (item !== keep) {
        val c = when (item) {
            is DiagramText -> item.color
            is DiagramPath -> item.color
            is DiagramDot -> item.color
            else -> null
        } ?: continue
        dimmed[item] = c.w
        val faint = Vector4f(c).apply { w *= .3f }
        when (item) {
            is DiagramText -> item.color = faint
            is DiagramPath -> item.color = faint
            is DiagramDot -> item.color = faint
            else -> {}
        }
    }
}
var pointingLit: DiagramItem? = null
val previousPointingUpdate = pointingDrawing.beforeUpdate
val pointingUpdate: () -> Unit = {
    previousPointingUpdate()
    val device = pointingOverride[0] ?: if (renderer.openXRHmdTarget != null) pointingController else pointingMouse
    pointing.update(device.sample())
    val over = pointing.hover?.item
    if (over !== pointingLit) {
        restoreDimmed()
        if (over != null) dimAllBut(over)
        pointingLit = over
        pointingLog += "hover " + (pointing.hover?.describe() ?: "nothing")
    }
    if (pointing.justReleased) pointingLog += "click " + (pointing.hover?.describe() ?: "nothing")
}
pointingDrawing.beforeUpdate = pointingUpdate
println("pointing ready: mouse over the mirror, or the right controller in a headset")

loop { frame() }
    .end {
        restoreDimmed()
        if (pointingDrawing.beforeUpdate === pointingUpdate) pointingDrawing.beforeUpdate = previousPointingUpdate
    }
