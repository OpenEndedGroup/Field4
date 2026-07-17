# Layers System Testing Guide

Canonical testing map: `dev_docs/testing/TESTING_STRATEGY.md`. This file is a
feature-specific manual checklist for layer behavior.

## Overview
The layers system allows boxes to be organized into multiple presentation layers, with control over visibility and frame positioning per layer.

## Manual Testing Steps

### 1. Test Default Layer Behavior
- Create a new box
- Verify it appears visible (default layer is "default" which is always visible)
- Check that `box.layers` property defaults to "default"

### 2. Test Setting Layers Property
```kotlin
// In a box's code, set the layers property
thisBox.layers = "layer1,layer2"
```
- Box should now be part of both "layer1" and "layer2"
- Setting `layers` replaces the box's layer set verbatim (no implicit "default"
  is re-added). Since `visibleLayers` still defaults to `{"default"}`, the box
  now has zero intersection and becomes HIDDEN. To make it visible, either
  include "default" in the string (`"default,layer1,layer2"`) or call
  `setVisibleLayers(setOf("default","layer1","layer2"))` first.
  (`isBoxVisible` returns true only when a box's own layers intersect
  `visibleLayers`.)

### 3. Test Layer Visibility Toggle
```kotlin
// From browser console or box code
setVisibleLayers(setOf("layer1"))
```
- Boxes with `layers = "layer1"` or `layers = "layer1,default"` should be visible
- Boxes with only `layers = "layer2"` should be hidden
- Connections between boxes should only draw for visible boxes

### 4. Test Active Layer and Automatic Position Saving
```kotlin
// Assign box to layers
thisBox.layers = "layer1,layer2"

// Activate layer1 and move box
setActiveLayer("layer1")
// Move box to position (100, 100) manually
thisBox.frame = Rect(100.0, 100.0, 200.0, 150.0)

// Switch to layer2 - position at (100, 100) is automatically saved to layer1
setActiveLayer("layer2")
// Move box to position (300, 200) manually
thisBox.frame = Rect(300.0, 200.0, 200.0, 150.0)

// Switch back to layer1 - box should return to (100, 100)
setActiveLayer("layer1")
// Verify box is at (100, 100)

// Switch to layer2 again - box should return to (300, 200)
setActiveLayer("layer2")
// Verify box is at (300, 200)
```

**Key behavior**: When switching between active layers, the current position is automatically saved to the old layer before loading the new layer's position. This means moving boxes while a layer is active automatically records their positions for that layer.

### 5. Test Get All Layers
```kotlin
// Get all layers in use across all boxes
val layers = getAllLayers()
println(layers) // Should show all unique layer names from boxes and layerFrames
```

### 6. Test Persistence
- Set layers and layerFrames on boxes
- Save the document
- Close and reopen the document
- Verify layers and layerFrames are restored

### 7. Test Canvas Gesture Connection Drawing
`gestures.kt` needs no layer-specific logic: box/connection culling rides on the
existing visibility check. `updateAllBoxVisibility()` sets each box's CSS
visibility from `isBoxVisible()`, and connection drawing skips boxes that are not
visible, so hidden-layer boxes and their connections drop out automatically.

- Create two boxes: Box A on "layer1", Box B on "layer2"
- Connect them (make Box B a child of Box A)
- Set visible layers to only "layer1"
- Verify the connection is NOT drawn (Box B is hidden)
- Set visible layers to "layer1,layer2"
- Verify the connection IS drawn (both visible)

### 8. Test Animated Layer Transitions
```kotlin
// Assign box to layers
thisBox.layers = "layer1,layer2"

// Activate layer1 with animation
setActiveLayer("layer1", animated = true, duration = 500.0)
// Move box manually
thisBox.frame = Rect(100.0, 100.0, 200.0, 150.0)

// Switch to layer2 with smooth animation
setActiveLayer("layer2", animated = true, duration = 500.0)
// Box should smoothly transition to its saved position (or stay if none)

// Test animated visibility changes
setVisibleLayers(setOf("layer1"), animated = true, duration = 300.0)
// Boxes should fade out if not in layer1
setVisibleLayers(setOf("layer1", "layer2"), animated = true, duration = 300.0)
// Boxes should fade back in
```

### 9. Test Backend-Initiated Animated Rect
```kotlin
// From box code, animate to a new position
thisBox.setFrameAnimated(Rect(300.0, 200.0, 250.0, 200.0), duration = 1000.0)
// Box should smoothly transition to the new position
```

## API Reference

### Frontend Functions (JavaScript/Kotlin)

#### `setVisibleLayers(layers: Set<String>, animated: Boolean = false, duration: Double = 300.0)`
Sets which layers are currently visible.
- Sends message to server
- Updates all box visibility
- If `animated = true`, boxes fade in/out over the specified duration
- Triggers connection redraw

#### `setActiveLayer(layer: String?, animated: Boolean = false, duration: Double = 500.0)`
Sets which layer is "active" for frame overrides.
- When a layer becomes active, boxes with frame overrides for that layer will move to those positions
- If `animated = true`, boxes smoothly transition to their new positions over the specified duration
- Pass `null` to deactivate all layers

#### `getAllLayers(): Set<String>`
Returns all layer names currently in use.
- Scans all boxes for their `layers` property
- Includes layers from `layerFrames` maps
- Always includes "default" if no layers found

#### `isBoxVisible(box: HTMLElement): Boolean`
Checks if a specific box is visible based on current layer visibility.
- Returns true if any of the box's layers intersect with visible layers

#### `updateAllBoxVisibility()`
Updates the CSS visibility of all boxes based on current layer settings.

### Backend Properties

#### `Box.layers: String`
Comma-separated list of layer identifiers.
- Default: "default"
- Example: "layer1,layer2,presentation"

#### `Box.layerFrames: MutableMap<String, Rect>`
Map of layer names to frame positions.
- When a layer becomes active, the box moves to its corresponding frame
- Default: empty map

#### `Box.setFrameAnimated(newFrame: Rect, duration: Double = 500.0)`
Animates the box frame change over the specified duration.
- Updates the frame property
- Sends animated rect message to frontend
- Duration in milliseconds (default 500ms)

### RPC Messages

#### `SetVisibleLayers(layers: List<String>, animated: Boolean = false, duration: Double = 300.0)`
Server message to set visible layers.
- `animated`: If true, boxes fade in/out
- `duration`: Animation duration in milliseconds

#### `SetActiveLayer(layer: String?, animated: Boolean = false, duration: Double = 500.0)`
Server message to set active layer.
- `animated`: If true, boxes smoothly transition to new positions
- `duration`: Animation duration in milliseconds

#### `GetAllLayers()`
Request all layers from server.

#### `AllLayersResult(layers: List<String>)`
Response with all layer names.

#### `Set_animated_rect(uid: String, property: String, value: Rect, duration: Double = 500.0)`
Animates a box's rect property change.
- Backend-initiated animation
- Duration in milliseconds

## Expected Behavior

1. **Default State**: All boxes are on "default" layer and visible
2. **Layer Assignment**: Boxes can be on multiple layers (comma-separated)
3. **Visibility Logic**: Box is visible if ANY of its layers is in the visible set
4. **Connection Drawing**: Connections only drawn when BOTH boxes are visible
5. **Automatic Position Saving**: When switching from one active layer to another, current positions are automatically saved to the old layer before loading the new layer's positions
6. **Frame Overrides**: When layer becomes active, boxes jump to their saved positions for that layer (or stay in place if no position saved)
7. **Animated Transitions**: When `animated = true`, boxes smoothly transition positions and fade in/out
8. **Persistence**: Both `layers` and `layerFrames` are persisted to disk

## Known Limitations

1. Layer UI panel not yet implemented (planned for future PR)
2. No layer creation UI (layers are created implicitly by setting properties)
3. No visual indication of which layer is active

## Future Enhancements

- Layer management UI panel
- Visual indicators for active layer
- Layer color coding
- Undo/redo support for layer operations
- Custom easing functions for animations
