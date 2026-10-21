import afield.media.webrtc.webrtcPublishers
import afield.media.webrtc.webrtcSource

// Until you pick someone, show the first publisher on the network (on Field's own relay,
// that is the box beside this one).
val publishers = webrtcPublishers()
val source = webrtcSource(publishers.names.firstOrNull() ?: "nobody", 640, 360)
publishers.onChange { names ->
    if (source.target == "nobody" && names.isNotEmpty()) source.watch(names.first())
}
publishers.onPick { name -> source.watch(name) }
display("publishers", publishers.html { source.target })

val material = moduleHere { textures("remote" to source) }
val screen = Mesh()
material += "screen" to screen
renderer += "webrtc-remote" to material

screen.set(4, 2) {
    pos.add(-0.9, -0.9, 0.0)
    pos.add(0.9, -0.9, 0.0)
    pos.add(0.9, 0.9, 0.0)
    pos.add(-0.9, 0.9, 0.0)
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)
    tri.add(0, 1, 2, 0, 2, 3)
}

var said = ""
loop {
    val line = "@${source.target}: ${source.status}"
    if (line != said) println(line)
    said = line
    frame()
}
