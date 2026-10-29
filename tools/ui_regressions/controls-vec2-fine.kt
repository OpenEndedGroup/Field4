// A vec2 pad for the shift-drag test. Every value the callback receives is listed, in order, so the
// test sees what Kotlin saw and in what order.
val seen = mutableListOf<String>()
controlGroup("controls") {
    vec2Control("pos", Vector2f(0f, 0f)) {
        seen += "%.3f".format(it.x)
        display("seen", "<span id='vec2-fine-seen'>${seen.joinToString(" ")}</span>")
    }
}
displayOpen("controls")
