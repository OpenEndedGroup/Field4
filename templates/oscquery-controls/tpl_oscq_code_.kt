import afield.io.osc.query.*

// Field controls that are also OSCQuery parameters: TouchDesigner, Max, Chataigne and VDMX find
// them as "Field" on the network, and setting one there moves the slider here. Every copy of this
// box publishes under a path of its own.
var gain = 0.5
var mute = false

val q = oscQuery("Field")
val path = "/field_" + id.take(6)
q.controls(path) {
    slider("gain", 0.0, 1.0, initialValue = gain) { gain = it }
    checkbox("mute", mute) { mute = it }
}
markdown("Published under `$path`. Browse **http://localhost:${q.httpPort}/** for the namespace.")
