import afield.media.webrtc.webrtcPublish
import afield.media.webrtc.webrtcPublishers
import afield.media.webrtc.webrtcSource

/*
The shader jam in StackSL. This program is published to the class as
@you/stacktrinket, and a classmate's stream is a second picture beside `state`:
`0 sample1` reads it at this pixel. Pick whose stream from the publishers list. The
program is in the [[property stackSL]] tab; see this box's notes.
*/
val publishers = webrtcPublishers()
val stream = webrtcSource(publishers.names.firstOrNull() ?: "nobody", 640, 360)
val feedback = stackFeedback { input("remote", stream) }

// Publish what the window shows, as a 640×640 stream; its preview is the `stream` panel.
val sink = webrtcPublish(feedback.picture, "stacktrinket", 640, 640, show = "stream")

// Until you pick someone, show the first publisher who is not this box.
publishers.onChange { names ->
    val others = names.filter { it != sink.publishedAs }
    if (stream.target == "nobody" && others.isNotEmpty()) stream.watch(others.first())
}
publishers.onPick { name -> stream.watch(name) }
display("publishers", publishers.html { stream.target })
