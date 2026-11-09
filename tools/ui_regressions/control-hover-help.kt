import afield.animation.keyframes.KeyframeScope
import afield.animation.keyframes.slider
import afield.ui.visualization.*

val keys = KeyframeScope()
var keySize by keys.slider(0.4, 0.1..1.0, label = "Key size", hoverText = "Keyed help")
var enabled = false
controlGroup("hover help") {
    slider("A long gain label for testing", hoverText = "**Gain** & `units`\n\n- first\n- [guide](https://example.com)\n- [bad](javascript:alert)\n\n<img src=x onerror=\"window.controlHoverXss=1\">") { }
    slider("Plain slider") { }
    checkbox("Enabled", hoverText = "Toggle help") { enabled = it }
    button("Reset", hoverText = "Reset help") { }
    stringInput("Name", hoverText = "Name help") { }
    colorPicker("Tint", hoverText = "Colour help") { }
    vec2Control("Position", hoverText = "XY help") { }
    meter("Level", hoverText = "Level help")
    multiSlider("Steps", count = 3, hoverText = "Steps help") { }
    matrix("Grid", rows = 2, columns = 2, hoverText = "Grid help") { }
    rangeSlider("Range", hoverText = "Range help") { }
    menu("Mode", listOf("one", "two"), hoverText = "Mode help") { _, _ -> }
    gesture("Gesture", hoverText = "Gesture help") { }
    keys.controls()
}
displayOpen("hover help")
