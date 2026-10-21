import afield.media.webrtc.webrtcPublishers
import afield.media.webrtc.webrtcSource

/*
A classmate's WebRTC stream as a second picture beside `state`: `0 sample1` reads it
at this pixel. Pick whose stream from the publishers list. The program is in the
[[property stackSL]] tab; see this box's notes.
*/
val publishers = webrtcPublishers()
val stream = webrtcSource(publishers.names.firstOrNull() ?: "nobody", 640, 360)
publishers.onChange { names ->
    if (stream.target == "nobody" && names.isNotEmpty()) stream.watch(names.first())
}
publishers.onPick { name -> stream.watch(name) }
display("publishers", publishers.html { stream.target })

stackFeedback { input("remote", stream) }
