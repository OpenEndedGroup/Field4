import afield.media.webrtc.WebRTCSink
import afield.media.webrtc.webrtcSink
import afield.media.webrtc.webrtcSource

// Same-machine round trip: this box publishes its wall clock as a time-code band, and a source
// watching the stream decodes each presented frame against the same clock.
val sink = webrtcSink("latency", 640, 360)
val module = moduleHere { sink.rtt.apply() }
val quad = Mesh()
module += "quad" to quad
sink.rtt += "content" to module
quad.clock = { vec(System.nanoTime() / 1e9, WebRTCSink.timecode().toDouble(), 0.0, 0.0) }
quad.set(4, 2) {
    pos.add(-1, -1, 0)
    pos.add(1, -1, 0)
    pos.add(1, 1, 0)
    pos.add(-1, 1, 0)
    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}
display("main", sink.html())

// Watch this sink under whatever name the network gave it (login/latency).
var source: afield.media.webrtc.WebRTCSource? = null
var frames = 0
loop {
    val name = sink.publishedAs
    if (source == null && name != null) source = webrtcSource(name, 640, 360, measure = true)
    source?.let { s ->
        frames++
        // Discard the first ~10 s of connection set-up, then report every ~10 s at 60 fps.
        if (frames == 600) s.latency.clear()
        if (frames % 600 == 0 && frames > 600) println(s.latency)
    }
    frame()
}
