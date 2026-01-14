# Interactive Sliders in Field4

This document describes how to use the new interactive slider functionality in Field4.

## Overview

The slider system allows you to display interactive HTML range sliders that can update variables in real-time. When a user moves a slider in the browser, the new value is sent back to the server via WebSocket, and a callback function is invoked with the new value.

## Basic Usage

### Single Slider

```kotlin
var myValue = 0.5

slider(
    label = "My Parameter",
    min = 0.0,
    max = 1.0,
    step = 0.01,
    initialValue = myValue,
    channel = "controls"
) { newValue ->
    myValue = newValue
    println("Slider value changed to: $myValue")
    // Your code here - e.g., update a visualization
}
```

### Multiple Sliders in a Group

```kotlin
var radius = 50.0
var rotation = 0.0
var opacity = 1.0

sliders(
    channel = "controls",
    "radius" to SliderConfig(
        label = "Radius",
        min = 10.0,
        max = 100.0,
        step = 1.0,
        initialValue = radius,
        onUpdate = { radius = it; println("Radius: $radius") }
    ),
    "rotation" to SliderConfig(
        label = "Rotation",
        min = 0.0,
        max = 360.0,
        step = 1.0,
        initialValue = rotation,
        onUpdate = { rotation = it; println("Rotation: $rotation") }
    ),
    "opacity" to SliderConfig(
        label = "Opacity",
        min = 0.0,
        max = 1.0,
        step = 0.05,
        initialValue = opacity,
        onUpdate = { opacity = it; println("Opacity: $opacity") }
    )
)
```

## API Reference

### `slider()` Function

```kotlin
fun Box.slider(
    label: String,
    min: Double = 0.0,
    max: Double = 1.0,
    step: Double = 0.01,
    initialValue: Double = (min + max) / 2,
    channel: String = "controls",
    onUpdate: (Double) -> Unit
)
```

Parameters:
- `label`: The text label displayed above the slider
- `min`: Minimum value of the slider range (default: 0.0)
- `max`: Maximum value of the slider range (default: 1.0)
- `step`: The increment step for the slider (default: 0.01)
- `initialValue`: The initial position of the slider (default: midpoint between min and max)
- `channel`: The disclosure/output channel name where the slider will appear (default: "controls")
- `onUpdate`: Callback function invoked when the slider value changes, receives the new value

### `sliders()` Function

```kotlin
fun Box.sliders(
    channel: String = "controls",
    vararg configs: Pair<String, SliderConfig>
)
```

Parameters:
- `channel`: The disclosure/output channel name where the sliders will appear (default: "controls")
- `configs`: Variable number of ID-Config pairs for multiple sliders

### `SliderConfig` Data Class

```kotlin
data class SliderConfig(
    val label: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val initialValue: Double,
    val onUpdate: (Double) -> Unit
)
```

## Implementation Details

### Architecture

The slider system consists of several components:

1. **RPC Messages** (`rpc.kt`):
   - `SliderUpdate`: Message sent from client to server when slider value changes

2. **Server Handler** (`Server.kt`):
   - `slider-update` handler: Receives slider updates and invokes registered callbacks

3. **Output Functions** (`outputs.kt`):
   - `slider()` and `sliders()`: Generate HTML and register callbacks
   - Callback registry: Maintains mapping from slider IDs to callback functions

4. **Frontend JavaScript** (`sliders.js`):
   - `sendSliderUpdate()`: Sends slider value updates to server via WebSocket

5. **HTML Template**:
   - Range input with label and value display
   - Inline event handlers for real-time updates

### Callback Registration

Each slider is assigned a unique ID (UUID-based). When a slider is created, its callback is registered in a global `ConcurrentHashMap`. When the server receives a `SliderUpdate` message, it looks up the callback by slider ID and invokes it with the new value.

### Disclosure Integration

Sliders are displayed using the same disclosure system as other output types (logs, charts, etc.). They appear in collapsible sections that can be opened/closed, resized, and scrolled.

## Example: Live Parameter Control

```kotlin
// Define parameters
var speed = 1.0
var amplitude = 10.0
var frequency = 2.0

// Display sliders
sliders(
    channel = "animation controls",
    "speed" to SliderConfig("Animation Speed", 0.1, 5.0, 0.1, speed) { speed = it },
    "amplitude" to SliderConfig("Amplitude", 1.0, 50.0, 1.0, amplitude) { amplitude = it },
    "frequency" to SliderConfig("Frequency", 0.1, 10.0, 0.1, frequency) { frequency = it }
)

// Use parameters in animation loop
loop {
    val y = amplitude * sin(frequency * time * speed)
    // ... render with y
}
```

## Styling

The sliders use the existing Field4 color scheme and styling conventions:
- Dark background with transparency
- Monospace font for values
- Blue accent color for the slider track
- Proper spacing and padding for visual consistency

Custom styling can be added via the inline styles in the HTML template or by modifying the CSS.

## Future Enhancements

Potential improvements:
- Support for different slider types (logarithmic, color pickers)
- Preset buttons or value snapping
- Linked sliders (e.g., min/max pairs)
- Keyboard input for precise values
- Undo/redo for parameter changes
- Save/load parameter presets
