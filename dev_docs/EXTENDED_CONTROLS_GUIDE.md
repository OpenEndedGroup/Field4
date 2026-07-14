# Extended Control Types - Implementation Guide

## Overview

The control system has been extended beyond simple sliders to include a comprehensive set of UI controls for real-time parameter editing in Field4. All controls use the same WebSocket-based backchannel and disclosure system.

## Control Types

### 1. Slider (Single Value)
Range control for numeric values.

```kotlin
var speed = 1.0
slider("Speed", 0.0, 5.0, 0.1, speed, "controls") { speed = it }
```

**Parameters:**
- `label`: Display label
- `min`, `max`: Range bounds
- `step`: Increment size
- `initialValue`: Starting value
- `channel`: Disclosure channel
- `onUpdate`: Callback receiving new value

### 2. Vec2 Control (2D Vector)
Two sliders for X/Y coordinate editing.

```kotlin
var position = Vector2f(0.0f, 0.0f)
vec2Control(
    label = "Position",
    initialValue = position,
    xRange = -100.0 to 100.0,
    yRange = -100.0 to 100.0,
    step = 1.0,
    channel = "transform"
) { newValue ->
    position = newValue
}
```

**Parameters:**
- `label`: Display label
- `initialValue`: Starting Vector2f value
- `xRange`, `yRange`: Range bounds for each axis (Pair<Double, Double>)
- `step`: Increment size
- `channel`: Disclosure channel
- `onUpdate`: Callback receiving new Vector2f

**Data Type:**
JOML Vector2f from `afield.stdlib.linalg.Vector2f`. The codebase has a type alias:
```kotlin
typealias Vec2 = Vector2f  // from afield.stdlib.linalg
```

### 3. Checkbox (Boolean)
Toggle control for boolean values.

```kotlin
var enabled = true
checkbox("Enable Effect", enabled, "settings") { enabled = it }
```

**Parameters:**
- `label`: Display label
- `initialValue`: Starting boolean value
- `channel`: Disclosure channel
- `onUpdate`: Callback receiving new boolean

### 4. String Input (Text)
Text input field with default value.

```kotlin
var name = "Untitled"
stringInput("Name", name, "settings") { name = it }
```

**Parameters:**
- `label`: Display label
- `initialValue`: Starting string value
- `channel`: Disclosure channel
- `onUpdate`: Callback receiving new string

### 5. Color Picker (Vec4)
Color selection with RGB picker and alpha slider. Colors are normalized (0.0-1.0 range).

```kotlin
var color = Vector4f(1.0f, 0.5f, 0.0f, 1.0f)  // Orange, fully opaque
colorPicker("Color", color, "appearance") { color = it }
```

**Parameters:**
- `label`: Display label
- `initialValue`: Starting Vector4f color (RGBA normalized)
- `channel`: Disclosure channel
- `onUpdate`: Callback receiving new Vector4f

**Data Type:**
JOML Vector4f from `afield.stdlib.linalg.Vector4f`. The codebase has a type alias:
```kotlin
typealias Vec4 = Vector4f  // from afield.stdlib.linalg
// x = red, y = green, z = blue, w = alpha
// All values normalized: 0.0f to 1.0f
```

**Color Conversion:**
To convert to/from 0-255 range:
```kotlin
// Vector4f to RGB
val r = (color.x * 255).toInt()
val g = (color.y * 255).toInt()
val b = (color.z * 255).toInt()

// RGB to Vector4f
val color = Vector4f(r / 255.0f, g / 255.0f, b / 255.0f, 1.0f)
```

## Property Sync Convenience Method

The `syncToProperty()` method automatically creates the appropriate control type based on the initial value's type and marks the property as persistable.

```kotlin
fun Box.syncToProperty(
    propertyName: String,
    initialValue: Any,
    channel: String = "controls",
    label: String = propertyName
)
```

**Supported Types:**
- `Double` → Creates slider (0.0 to 1.0 range)
- `Boolean` → Creates checkbox
- `String` → Creates string input
- `Vector2f` → Creates vec2 control
- `Vector4f` → Creates color picker

**Usage:**
```kotlin
// Create controls and sync to properties
syncToProperty("scale", 1.0, "properties", "Scale")
syncToProperty("visible", true, "properties", "Visible")
syncToProperty("title", "Default", "properties", "Title")
syncToProperty("offset", Vector2f(0.0f, 0.0f), "properties", "Offset")
syncToProperty("tint", Vector4f(1.0f, 1.0f, 1.0f, 1.0f), "properties", "Tint")

// Access synced properties later
val scale = getPropertyRaw("scale") as Double
val visible = getPropertyRaw("visible") as Boolean
val title = getPropertyRaw("title") as String
val offset = getPropertyRaw("offset") as Vector2f
val tint = getPropertyRaw("tint") as Vector4f
```

**Benefits:**
- Properties are automatically marked as persistable
- Appropriate control type is chosen based on value type
- Values are automatically synced via `setPropertyRaw()`
- Properties persist across sessions

## Complete Example

```kotlin
// Animation parameters
var speed = 1.0
var amplitude = 50.0
var enabled = true
var direction = Vector2f(1.0f, 0.0f)
var color = Vector4f(1.0f, 0.0f, 0.0f, 1.0f)
var label = "Animation"

// Create all controls in one channel
slider("Speed", 0.1, 3.0, 0.1, speed, "animation") { speed = it }
slider("Amplitude", 1.0, 100.0, 1.0, amplitude, "animation") { amplitude = it }
checkbox("Enabled", enabled, "animation") { enabled = it }
vec2Control("Direction", direction, -1.0 to 1.0, -1.0 to 1.0, 0.1, "animation") { 
    direction = it
}
colorPicker("Color", color, "animation") { color = it }
stringInput("Label", label, "animation") { label = it }

// Use in animation loop
loop {
    if (enabled) {
        val offset = amplitude * sin(time * speed)
        val dx = direction.x * offset
        val dy = direction.y * offset
        // Apply dx, dy, color to your visualization
    }
}
```

## Architecture

### Data Flow
```
Browser UI → JavaScript Handler → WebSocket Message → Server Handler → Callback → Variable Update
```

### Message Types (RPC)
- `SliderUpdate(sliderId: String, value: Double)`
- `Vec2Update(controlId: String, x: Double, y: Double)`
- `CheckboxUpdate(controlId: String, value: Boolean)`
- `StringUpdate(controlId: String, value: String)`
- `ColorUpdate(controlId: String, r: Double, g: Double, b: Double, a: Double)`

### Callback Registries
Each control type has its own thread-safe callback registry:
- `sliderCallbacks: ConcurrentHashMap<String, (Double) -> Unit>`
- `vec2Callbacks: ConcurrentHashMap<String, (Vector2f) -> Unit>`
- `checkboxCallbacks: ConcurrentHashMap<String, (Boolean) -> Unit>`
- `stringCallbacks: ConcurrentHashMap<String, (String) -> Unit>`
- `colorCallbacks: ConcurrentHashMap<String, (Vector4f) -> Unit>`

### HTML Generation
Each control generates HTML with:
- Consistent styling (dark theme)
- Real-time value display
- Data attributes for control ID and parameters
- Event handlers that call JavaScript functions

### JavaScript Handlers
- `handleSliderInput()`
- `handleVector2fInput()`
- `handleCheckboxInput()`
- `handleStringInput()`
- `handleColorInput()`
- `handleColorAlphaInput()`

All handlers:
1. Update the UI (value displays)
2. Send WebSocket message with afield.send()
3. Include all necessary data for server callback

## Styling

All controls use consistent styling:
- Background: `rgba(0,0,0,0.2)`
- Labels: `#ddd`, 11px
- Values: `#aaa`, 10px monospace
- Accent: `#4a9eff` (blue)
- Border radius: 4px
- Padding: 8px

## Best Practices

1. **Use Descriptive Labels**: Make it clear what each control does
2. **Group Related Controls**: Use the same channel for related parameters
3. **Appropriate Ranges**: Set min/max that make sense for your use case
4. **Step Size**: Match precision needs (0.01 for smooth, 1.0 for discrete)
5. **Initial Values**: Set sensible defaults
6. **Property Sync**: Use for parameters that should persist across sessions

## Common Patterns

### Scene Configuration
```kotlin
var cameraPos = Vector2f(0.0f, 0.0f)
var backgroundColor = Vector4f(0.1f, 0.1f, 0.1f, 1.0f)
var gridVisible = true
var gridSize = 20.0

vec2Control("Camera", cameraPos, -500.0 to 500.0, -500.0 to 500.0, 10.0, "scene") {
    cameraPos = it
}
colorPicker("Background", backgroundColor, "scene") { backgroundColor = it }
checkbox("Show Grid", gridVisible, "scene") { gridVisible = it }
slider("Grid Size", 10.0, 100.0, 5.0, gridSize, "scene") { gridSize = it }
```

### Effect Parameters
```kotlin
var effectStrength = 0.5
var effectColor = Vector4f(1.0f, 1.0f, 1.0f, 1.0f)
var effectEnabled = true
var effectMode = "blur"

slider("Strength", 0.0, 1.0, 0.05, effectStrength, "effects") { effectStrength = it }
colorPicker("Color", effectColor, "effects") { effectColor = it }
checkbox("Enabled", effectEnabled, "effects") { effectEnabled = it }
stringInput("Mode", effectMode, "effects") { effectMode = it }
```

### Persistent Settings
```kotlin
// These will be saved to the box's metadata
syncToProperty("uiScale", 1.0, "settings", "UI Scale")
syncToProperty("showHelp", true, "settings", "Show Help")
syncToProperty("userName", "User", "settings", "User Name")
syncToProperty("themeColor", Vector4f(0.5f, 0.5f, 1.0f, 1.0f), "settings", "Theme Color")
```
