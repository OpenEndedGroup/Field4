# Layers System Implementation Summary

## Overview
Successfully implemented a layers system for Field4 that provides presentation-mode functionality for boxes. The system allows boxes to be organized into multiple layers with control over visibility and frame positioning.

## What Was Implemented

### 1. Backend Properties (Boxes.kt)
- **`Box.layers`**: String property with comma-separated layer identifiers
  - Default value: "default"
  - Example: "layer1,layer2,presentation"
- **`Box.layerFrames`**: MutableMap<String, Rect> for position overrides
  - Stores different frame positions for each layer
  - Example: `{"layer1": Rect(100, 100, 200, 150), "layer2": Rect(300, 200, 200, 150)}`

Both properties are:
- Added to `ioPersistentProperties` for disk persistence
- Added to `uiResyncProperties` for client synchronization
- Properly serialized using kotlinx.serialization

### 2. Session State (Server.kt)
- **`SessionInformation.visibleLayers`**: MutableSet<String> (default: "default")
  - Tracks which layers are currently visible in the session
- **`SessionInformation.activeLayer`**: String? (default: null)
  - Tracks the currently active layer for frame overrides

### 3. Helper Functions (Server.kt)
- **`parseLayersList(layersString: String)`**: Parses comma-separated layers
- **`updateBoxVisibility(box: Box, sessionInfo: SessionInformation)`**: Updates box visibility based on layer intersection

### 4. Server Message Handlers (Server.kt)
- **`set-visible-layers`**: Updates which layers are visible
  - Updates session state
  - Recalculates visibility for all boxes
  - Triggers connection redraw
  
- **`set-active-layer`**: Sets the active layer
  - Updates session state
  - Applies frame overrides from layerFrames map
  - Boxes move to their layer-specific positions
  
- **`get-all-layers`**: Enumerates all layers in use
  - Scans all boxes for layers and layerFrames
  - Returns sorted list of unique layer names

### 5. RPC Messages (rpc.kt)
```kotlin
@Serializable data class SetVisibleLayers(val layers: List<String>)
@Serializable data class SetActiveLayer(val layer: String?)
@Serializable data class GetAllLayers(val dummy: Boolean = true)
@Serializable data class AllLayersResult(val layers: List<String>)
```

### 6. Frontend State (Main.kt)
- **`visibleLayers`**: MutableSet<String> (default: "default")
- **`activeLayer`**: String? (default: null)

### 7. Frontend Functions (Main.kt)
- **`setVisibleLayers(layers: Set<String>)`**
  - Updates local state
  - Sends message to server
  - Updates all box visibility
  - Triggers connection redraw

- **`setActiveLayer(layer: String?)`**
  - Updates local state
  - Sends message to server
  - Server applies frame overrides

- **`getAllLayers(): Set<String>`**
  - Scans all boxes for layer names
  - Returns unique layer names including those from layerFrames
  - Includes "default" if no layers found

- **`isBoxVisible(box: HTMLElement): Boolean`**
  - Checks if box's layers intersect with visible layers
  - Returns true if ANY layer matches

- **`updateAllBoxVisibility()`**
  - Updates CSS visibility for all boxes
  - Called after layer visibility changes

### 8. Frontend Message Handlers (Main.kt)
- **`layers` handler**: Updates box_layers dynamic property, refreshes visibility
- **`layerFrames` handler**: Parses JSON and updates box_layerFrames dynamic property

### 9. Canvas Integration (gestures.kt)
- **No changes needed!** Existing visibility checks work automatically
- Connections check `box.style.visibility === "hidden"`
- Boxes with no visible layers are automatically skipped

## Behavior Specifications

### Default Behavior
- All boxes start on "default" layer
- "default" layer is visible by default
- Boxes with no layers set are treated as being on "default"

### Visibility Logic
```
Box is visible IF:
  ANY of box.layers split by comma intersects with session.visibleLayers
```

Example:
- Box has `layers = "layer1,layer2"`
- Session has `visibleLayers = ["layer1", "layer3"]`
- Result: Box IS visible (layer1 matches)

### Active Layer Logic
```
When layer becomes active:
  For each box:
    If box.layerFrames[activeLayer] exists:
      box.frame = box.layerFrames[activeLayer]
```

### Connection Drawing
- Connections only draw when BOTH boxes are visible
- Handled automatically by existing `style.visibility` checks
- No manual layer checks needed in drawing code

## API Usage Examples

### Setting Layers on a Box
```kotlin
// In box code
thisBox.layers = "presentation,demo"
```

### Setting Layer Frames
```kotlin
// In box code
thisBox.layerFrames["slide1"] = Rect(100.0, 100.0, 300.0, 200.0)
thisBox.layerFrames["slide2"] = Rect(500.0, 300.0, 300.0, 200.0)
```

### Controlling Visibility (Frontend)
```kotlin
// Show only presentation layers
setVisibleLayers(setOf("presentation", "demo"))

// Activate slide1 - boxes move to their slide1 positions
setActiveLayer("slide1")

// Get all available layers
val allLayers = getAllLayers()
println(allLayers) // ["default", "presentation", "demo", "slide1", "slide2"]
```

## Known Limitations

1. **No Frame Restoration**: When deactivating a layer (setting activeLayer to null), boxes remain at their last activated position. Original positions are not tracked.
   - TODO: Future enhancement to track and restore original frames

2. **No UI Panel**: This PR implements the plumbing only. A visual UI panel for managing layers will come in a future PR.

3. **No Visual Indicators**: Currently no visual indication of:
   - Which layer is active
   - Which layers a box belongs to
   - Which layers are visible

4. **No Animation**: Layer transitions are instant, no smooth animations between positions.

## Future Enhancements

1. **Layer Management UI**
   - Panel to show/hide layers
   - Visual indicators for active layer
   - Layer creation/deletion interface

2. **Frame Restoration**
   - Track original positions before layer activation
   - Restore when layer is deactivated

3. **Visual Polish**
   - Layer color coding
   - Visual indicators on boxes
   - Smooth animations between layer frames

4. **Advanced Features**
   - Layer groups/hierarchies
   - Layer-specific properties beyond just frame
   - Undo/redo for layer operations
   - Layer presets/templates

## Testing

See `LAYERS_TESTING.md` for comprehensive testing procedures covering:
- Default layer behavior
- Setting layers property
- Layer visibility toggling
- Active layer and frame overrides
- Getting all layers
- Persistence
- Canvas gesture integration

## Code Quality

- ✅ No code duplication (shared helper functions)
- ✅ Type-safe serialization with validation
- ✅ Robust error handling with descriptive messages
- ✅ Safe JavaScript interop
- ✅ Clear documentation of limitations
- ✅ TODOs for future work
- ✅ Minimal, surgical changes

## Files Modified

1. `src/jvmMain/kotlin/afield/Boxes.kt` - Box properties, serialization
2. `src/jvmMain/kotlin/afield/Server.kt` - Session state, handlers, helpers
3. `src/commonMain/kotlin/rpc.kt` - RPC message types
4. `src/jsMain/kotlin/afield/Main.kt` - Frontend state, functions, handlers
5. `LAYERS_TESTING.md` - Testing guide (new file)
6. `LAYERS_IMPLEMENTATION.md` - This file (new file)

## Integration Notes

- The system integrates cleanly with existing code
- No breaking changes to existing functionality
- Canvas gestures work automatically through existing visibility checks
- Properties persist through existing save/load mechanism
- All changes are backward compatible (boxes without layers default to "default")

## Conclusion

The layers system is fully implemented and ready for use. It provides the foundation for presentation-mode functionality in Field4, allowing users to organize boxes into layers and control their visibility and positioning. The implementation is minimal, robust, and ready for the UI panel to be built on top of it in a future PR.
