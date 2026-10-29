// A slider whose callback re-displays its own control group once, mid-drag, as a box re-run does;
// the readout channel shows what Kotlin last received.
var gain = 0.2
var redisplayed = false
fun surface() {
    controlGroup("controls") {
        slider("gain", 0.0, 1.0, 0.01, 0.2) {
            gain = it
            display("seen", "<span id='slider-rerun-seen'>${"%.2f".format(it)}</span>")
            if (it > 0.5 && !redisplayed) { redisplayed = true; surface() }
        }
    }
}
surface()
displayOpen("controls")
