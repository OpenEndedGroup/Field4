import afield.oscquery.*

// Field controls that are also OSCQuery parameters, under /surface. Drag them here and every
// listening remote sees the change; set them from a remote and the Field control moves.
var gain = 0.5
var mute = false
oscQuery("Field demo").controls("/surface") {
    slider("gain", 0.0, 1.0, initialValue = gain) { gain = it }
    checkbox("mute", mute) { mute = it }
}
