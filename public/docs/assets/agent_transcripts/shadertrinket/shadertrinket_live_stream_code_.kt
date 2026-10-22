import afield.media.webrtc.webrtcPublishers
import afield.media.webrtc.webrtcSource

// Live Stream Input Demo: iChannel2 is a classmate's stream (ShaderTrinket's Receive into iChannel2).
// Click a name in the list under this box. Until you do, it shows the first publisher on the
// network; on Field's own relay that is this document's Publish box.
val publishers = webrtcPublishers()
val stream = webrtcSource(publishers.names.firstOrNull() ?: "nobody", 960, 540)
publishers.onChange { names ->
    if (stream.target == "nobody" && names.isNotEmpty()) stream.watch(names.first())
}
publishers.onPick { name -> stream.watch(name) }
display("publishers", publishers.html { stream.target })

val canvas = shadertoyCanvas()
canvas += shadertoyHere {
    +canvas.rtt
    textures("iChannel2" to stream)
}
