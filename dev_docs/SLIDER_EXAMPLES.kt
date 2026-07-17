// SLIDER USAGE EXAMPLES
// Copy these into Field4 boxes to test the slider functionality.
//
// API reference lives in the KDoc on Box.slider(), Box.sliders(), and SliderConfig in
// src/jvmMain/kotlin/afield/outputs.kt; the full control family (checkbox, colorPicker,
// vec2Control, controlGroup, ...) is documented in the KDoc on those factory functions.

// ============================================
// Example 1: Simple Parameter Slider
// ============================================
var size = 50.0

slider(
    label = "Size",
    min = 10.0,
    max = 100.0,
    step = 1.0,
    initialValue = size,
    channel = "controls"
) { newValue ->
    size = newValue
    println("Size is now: $size")
}

// ============================================
// Example 2: Color Picker (hand-rolled RGB group)
// A group of 0-255 sliders driving a live display() swatch.
// (For a single native colour widget, use Box.colorPicker() instead.)
// ============================================
var r = 255.0
var g = 128.0
var b = 0.0

fun showColor() {
    val hex = "#%02X%02X%02X".format(r.toInt(), g.toInt(), b.toInt())
    display("color", """
        <div style="
            width: 150px;
            height: 150px;
            background-color: $hex;
            border: 2px solid white;
            border-radius: 8px;
            margin: 10px auto;
        "></div>
        <div style="text-align: center; color: white; font-family: monospace;">
            $hex
        </div>
    """)
}

sliders(
    channel = "color picker",
    "r" to SliderConfig("Red", 0.0, 255.0, 1.0, r) { r = it; showColor() },
    "g" to SliderConfig("Green", 0.0, 255.0, 1.0, g) { g = it; showColor() },
    "b" to SliderConfig("Blue", 0.0, 255.0, 1.0, b) { b = it; showColor() }
)

showColor() // initial display

// ============================================
// Example 3: Animation Control
// ============================================
var animSpeed = 1.0
var animAmplitude = 10.0
var running = true

sliders(
    "animation params",
    "speed" to SliderConfig("Speed", 0.0, 3.0, 0.1, animSpeed) {
        animSpeed = it
    },
    "amp" to SliderConfig("Amplitude", 1.0, 50.0, 1.0, animAmplitude) {
        animAmplitude = it
    }
)

// Use in an animation loop (example)
// loop {
//     if (running) {
//         val offset = animAmplitude * sin(time * animSpeed)
//         // apply offset to your visualization
//     }
// }
