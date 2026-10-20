import afield.media.webrtc.webrtcSink

// Published as login/field-shader: your aaaframe login, or this computer's user name on
// Field's own relay. A browser ShaderTrinket receives it by that name. To join a class,
// run WebRTCNetwork.join("https://<your aaaframe>") once; Field asks you to sign in.
@exported val sink = webrtcSink("field-shader", 640, 360)

val module = moduleHere { sink.rtt.apply() }
val quad = Mesh()
module += "quad" to quad
sink.rtt += "content" to module

quad.clock = { vec(System.nanoTime() / 1e9, 0.0, 0.0, 0.0) }
quad.set(4, 2) {
    pos.add(-1, -1, 0)
    pos.add(1, -1, 0)
    pos.add(1, 1, 0)
    pos.add(-1, 1, 0)
    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}

display("main", sink.html())
