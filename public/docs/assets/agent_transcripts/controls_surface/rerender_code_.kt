// Drag "gain" from left to right. When it passes 0.5 the callback rebuilds this group,
// replacing the slider's DOM under the pointer; the drag continues on the new one.
var rebuilt = 0
fun surface() {
    controlGroup("controls") {
        slider("gain", 0.0, 1.0, 0.01, 0.2) {
            display(
                "seen",
                "<div style='font:18px Iosevka,monospace;color:#0cc8ed'>gain ${"%.2f".format(it)}  rebuilt $rebuilt</div>",
            )
            if (it > 0.5 && rebuilt == 0) {
                rebuilt++
                surface()
            }
            if (it < 0.3) rebuilt = 0
        }
    }
}
surface()
displayOpen("controls")
