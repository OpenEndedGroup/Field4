// SLIDER USAGE EXAMPLES
// Copy these into Field4 boxes to test the slider functionality

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
// Example 2: Multiple Related Sliders
// ============================================
var x = 0.0
var y = 0.0
var rotation = 0.0

sliders(
    channel = "transform",
    "x" to SliderConfig("X", -200.0, 200.0, 1.0, x) { x = it },
    "y" to SliderConfig("Y", -200.0, 200.0, 1.0, y) { y = it },
    "rot" to SliderConfig("Rotation", 0.0, 360.0, 1.0, rotation) { rotation = it }
)

// ============================================
// Example 3: Color Picker
// ============================================
var r = 255.0
var g = 128.0
var b = 0.0

fun updateColorDisplay() {
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
    "r" to SliderConfig("Red", 0.0, 255.0, 1.0, r) { 
        r = it
        updateColorDisplay()
    },
    "g" to SliderConfig("Green", 0.0, 255.0, 1.0, g) { 
        g = it
        updateColorDisplay()
    },
    "b" to SliderConfig("Blue", 0.0, 255.0, 1.0, b) { 
        b = it
        updateColorDisplay()
    }
)
updateColorDisplay() // Initial display

// ============================================
// Example 4: Fine Control with Different Steps
// ============================================
var coarse = 50.0
var fine = 0.5

slider("Coarse", 0.0, 100.0, 5.0, coarse, "precision") { coarse = it }
slider("Fine", 0.0, 1.0, 0.001, fine, "precision") { fine = it }

// ============================================
// Example 5: Animation Control
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
