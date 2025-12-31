# Layers System Testing Guide

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
- Box should remain visible since "default" layer is still active

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
- Create two boxes: Box A on "layer1", Box B on "layer2"
- Connect them (make Box B a child of Box A)
- Set visible layers to only "layer1"
- Verify the connection is NOT drawn (Box B is hidden)
- Set visible layers to "layer1,layer2"
- Verify the connection IS drawn (both visible)

## API Reference

### Frontend Functions (JavaScript/Kotlin)

#### `setVisibleLayers(layers: Set<String>)`
Sets which layers are currently visible.
- Sends message to server
- Updates all box visibility
- Triggers connection redraw

#### `setActiveLayer(layer: String?)`
Sets which layer is "active" for frame overrides.
- When a layer becomes active, boxes with frame overrides for that layer will move to those positions
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

### RPC Messages

#### `SetVisibleLayers(layers: List<String>)`
Server message to set visible layers.

#### `SetActiveLayer(layer: String?)`
Server message to set active layer.

#### `GetAllLayers()`
Request all layers from server.

#### `AllLayersResult(layers: List<String>)`
Response with all layer names.

## Expected Behavior

1. **Default State**: All boxes are on "default" layer and visible
2. **Layer Assignment**: Boxes can be on multiple layers (comma-separated)
3. **Visibility Logic**: Box is visible if ANY of its layers is in the visible set
4. **Connection Drawing**: Connections only drawn when BOTH boxes are visible
5. **Automatic Position Saving**: When switching from one active layer to another, current positions are automatically saved to the old layer before loading the new layer's positions
6. **Frame Overrides**: When layer becomes active, boxes jump to their saved positions for that layer (or stay in place if no position saved)
7. **Persistence**: Both `layers` and `layerFrames` are persisted to disk

## Known Limitations

1. Layer UI panel not yet implemented (planned for future PR)
2. No layer creation UI (layers are created implicitly by setting properties)
3. No visual indication of which layer is active
4. No animation between layer transitions (instant jumps)

## Future Enhancements

- Layer management UI panel
- Visual indicators for active layer
- Layer color coding
- Animation between layer frame transitions
- Undo/redo support for layer operations
