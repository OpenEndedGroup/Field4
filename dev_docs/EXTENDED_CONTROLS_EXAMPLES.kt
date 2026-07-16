// EXTENDED CONTROL TYPES EXAMPLES
// Copy these into Field4 boxes to test the new control functionality

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector4f

// ============================================
// Example 1: 2D Vector Control
// ============================================
var position = Vector2f(0.0f, 0.0f)

vec2Control(
    label = "Position",
    initialValue = position,
    xRange = -200.0 to 200.0,
    yRange = -200.0 to 200.0,
    step = 1.0,
    channel = "transform"
) { newValue ->
    position = newValue
    println("Position: (${position.x}, ${position.y})")
}

// ============================================
// Example 2: Checkbox Control
// ============================================
var enabled = true

checkbox(
    label = "Enable Effect",
    initialValue = enabled,
    channel = "settings"
) { newValue ->
    enabled = newValue
    println("Effect enabled: $enabled")
}

// ============================================
// Example 3: String Input Control
// ============================================
var name = "Untitled"

stringInput(
    label = "Name",
    initialValue = name,
    channel = "settings"
) { newValue ->
    name = newValue
    println("Name changed to: $name")
}

// ============================================
// Example 4: Color Picker (Vector4f, Normalized)
// ============================================
var color = Vector4f(1.0f, 0.5f, 0.0f, 1.0f)  // Orange, fully opaque

colorPicker(
    label = "Color",
    initialValue = color,
    channel = "appearance"
) { newValue ->
    color = newValue
    println("Color: rgba(${color.x}, ${color.y}, ${color.z}, ${color.w})")
    // Use color in your rendering code
}

// ============================================
// Example 5: Sync to Box Properties
// ============================================
// This automatically creates controls and syncs them to box properties
// Properties are marked as persistable

syncToProperty("myScale", 1.0, "properties", "Scale")
syncToProperty("myVisible", true, "properties", "Visible")
syncToProperty("myTitle", "Default Title", "properties", "Title")
syncToProperty("myOffset", Vector2f(0.0f, 0.0f), "properties", "Offset")
syncToProperty("myTint", Vector4f(1.0f, 1.0f, 1.0f, 1.0f), "properties", "Tint")

// Access the synced properties later:
// val scale = getPropertyRaw("myScale") as Double
// val visible = getPropertyRaw("myVisible") as Boolean
// etc.

// ============================================
// Example 6: Mixed Controls Group
// ============================================
var speed = 1.0
var bounce = true
var label = "Object"
var velocity = Vector2f(0.0f, 0.0f)
var tint = Vector4f(1.0f, 1.0f, 1.0f, 1.0f)

// All in one channel
slider("Speed", 0.0, 5.0, 0.1, speed, "animation") { speed = it }
checkbox("Bounce", bounce, "animation") { bounce = it }
stringInput("Label", label, "animation") { label = it }
vec2Control("Velocity", velocity, channel = "animation") { velocity = it }
colorPicker("Tint", tint, "animation") { tint = it }

// ============================================
// Example 7: Animation with Live Controls
// ============================================
var animSpeed = 1.0
var animAmplitude = 50.0
var animColor = Vector4f(1.0f, 0.0f, 0.0f, 1.0f)
var animEnabled = true

slider("Speed", 0.1, 3.0, 0.1, animSpeed, "animation") { animSpeed = it }
slider("Amplitude", 1.0, 100.0, 1.0, animAmplitude, "animation") { animAmplitude = it }
colorPicker("Color", animColor, "animation") { animColor = it }
checkbox("Enabled", animEnabled, "animation") { animEnabled = it }

// Use in animation loop:
// loop {
//     if (animEnabled) {
//         val offset = animAmplitude * sin(time * animSpeed)
//         // Apply offset and color to your visualization
//     }
// }

// ============================================
// Example 8: Scene Configuration
// ============================================
var cameraPos = Vector2f(0.0f, 0.0f)
var backgroundColor = Vector4f(0.1f, 0.1f, 0.1f, 1.0f)
var gridVisible = true
var gridSize = "20"

vec2Control("Camera", cameraPos, -500.0 to 500.0, -500.0 to 500.0, 10.0, "scene") { 
    cameraPos = it
}
colorPicker("Background", backgroundColor, "scene") {
    backgroundColor = it
}
checkbox("Show Grid", gridVisible, "scene") {
    gridVisible = it
}
stringInput("Grid Size", gridSize, "scene") {
    gridSize = it
}
