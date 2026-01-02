# Box Snapping System Enhancements

## Overview

This document describes the enhancements made to the box snapping system in Field4, implementing visual feedback and resize snapping support as requested.

## Changes Summary

### 1. Visual Snap Guides (Cyan Markers)

**What was added:**
- Real-time visual feedback showing which boxes are being aligned
- Subtle cyan guide lines that appear during drag and resize operations
- Different visual styles for edge alignment vs. spacing measurements

**Implementation:**
- Uses the existing SVG overlay layer (`#overlaysvglayer`)
- Creates a dedicated SVG group (`#snap-guides`) for snap visualizations
- Lines are drawn dynamically as snapping occurs
- Automatically cleared when dragging/resizing ends or snapping is not active

**Visual Design:**
- **Color**: Subtle cyan (`rgba(72, 201, 255, 0.6)`) to match Field4's theme
- **Style**: Dashed lines (4px dash, 3px gap)
- **Edge Markers**: Small circles at alignment points
- **Spacing Markers**: Measurement ticks showing equal spacing

### 2. Resize Snapping Support

**What was added:**
- Full snapping support during box resize operations
- Edge-to-edge snapping for all four sides (top, bottom, left, right)
- Visual guides during resize (same as drag)
- Integration with existing resize handler

**Behavior:**
- **Right Edge Resize**: Snaps to other boxes' left or right edges
- **Left Edge Resize**: Snaps to other boxes' left or right edges
- **Bottom Edge Resize**: Snaps to other boxes' top or bottom edges
- **Top Edge Resize**: Snaps to other boxes' top or bottom edges

## Technical Details

### Files Modified

1. **`src/jsMain/kotlin/afield/BoxSnapping.kt`** (major changes)
   - Added `SnapLine` data class for visual line information
   - Updated `SnapTarget` to include reference box and snap line
   - Added `drawSnapGuides()` method to render visual feedback
   - Added `addEdgeMarkers()` for edge alignment visualization
   - Added `addSpacingMarkers()` for spacing measurement visualization
   - Added `clearSnapGuides()` to clean up visuals
   - Updated all snap target finding methods to include snap line data
   - Added new `applySnappingForResize()` method for resize operations
   - Updated `applySnapping()` to draw visual guides

2. **`src/jsMain/kotlin/afield/Main.kt`** (minor changes)
   - Updated `resizeHandler` to call `applySnappingForResize()`
   - Added `clearSnapGuides()` call to `dragend` event
   - Added `clearSnapGuides()` call to `resizeend` event

3. **`SNAPPING_SYSTEM.md`** (documentation)
   - Added "Visual Feedback" section
   - Added "Resize Snapping" section
   - Updated "Future Enhancements" (removed completed items)

### Key Code Components

#### Visual Guide Rendering

```kotlin
private fun drawSnapGuides(targets: List<SnapTarget>, draggedBox: Rect) {
    val group = getSnapGuidesGroup()
    group.innerHTML = ""  // Clear previous guides
    
    targets.forEach { target ->
        target.snapLine?.let { line ->
            // Create line element with cyan color
            val lineElement = document.createElementNS("...", "line")
            lineElement.setAttribute("stroke", "rgba(72, 201, 255, 0.6)")
            // ... add to SVG
        }
    }
}
```

#### Resize Snapping Logic

```kotlin
fun applySnappingForResize(
    resizedBox: HTMLElement,
    proposedX: Double,
    proposedY: Double,
    proposedW: Double,
    proposedH: Double
): Rect {
    // Check each edge for snap targets
    // Adjust position and size based on snaps
    // Draw visual guides
    return Rect(snappedX, snappedY, snappedW, snappedH)
}
```

### Design Decisions

1. **SVG vs Canvas**: Used SVG for guides because:
   - Easy to create/destroy elements
   - Existing SVG layer already in place
   - Clean integration with Field4's architecture
   - Good performance for small number of lines

2. **Visual Style**: Chose subtle cyan because:
   - Matches Field4's existing color scheme
   - Visible but not distracting
   - Good contrast on dark background
   - Already used for other UI elements

3. **Snap Line Extent**: Lines span the full range of both boxes:
   - Clearly shows the alignment relationship
   - More visible than just point markers
   - Standard in design tools

4. **Resize Implementation**: Separate method because:
   - Resize has different constraints (size + position)
   - Different edge detection logic needed
   - Keeps drag logic clean
   - Easier to test and maintain

## User Experience

### Using Visual Guides

1. **Enable Snapping**: Press Caps Lock
2. **Start Dragging or Resizing**: Click and drag a box
3. **Observe Guides**: Cyan lines appear when near snap points
4. **Snap Occurs**: Position/size adjusts automatically
5. **Release**: Guides disappear

### Visual Guide Types

#### Edge Alignment
```
Box A                        Box B
┌─────┐    cyan line         ┌─────┐
│     │═════════════════════►│     │
└─────┘                      └─────┘
```

#### Spacing Measurement
```
Box A       Box B       Box C
┌───┐       ┌───┐       ┌───┐
│   │───┼───│   │───┼───│   │
└───┘   ↑   └───┘   ↑   └───┘
     equal     equal
```

## Testing Recommendations

### Manual Testing Checklist

#### Drag Snapping with Visuals
- [ ] Create 3+ boxes at different positions
- [ ] Enable Caps Lock
- [ ] Drag one box near another
- [ ] Verify cyan lines appear showing alignment
- [ ] Verify lines disappear when moving away
- [ ] Verify lines disappear when drag ends
- [ ] Test with Caps Lock off (no guides should appear)

#### Resize Snapping with Visuals
- [ ] Create 2+ boxes at different positions
- [ ] Enable Caps Lock
- [ ] Resize box from right edge near another box
- [ ] Verify cyan lines appear showing edge alignment
- [ ] Verify resize snaps to edge
- [ ] Test all four edges (top, bottom, left, right)
- [ ] Verify lines disappear when resize ends

#### Spacing with Visuals
- [ ] Create 3 boxes in a row with uneven spacing
- [ ] Enable Caps Lock
- [ ] Drag middle box to center
- [ ] Verify spacing measurement lines appear
- [ ] Verify measurement ticks are visible

#### Performance
- [ ] Test with 10+ boxes
- [ ] Verify no lag during drag/resize
- [ ] Verify guides update smoothly
- [ ] Check console for errors

## Console Output

### Drag Snapping
```
Box snapping: ENABLED (Caps Lock ON)
Snapping horizontally: LEFT_EDGE (distance: 5px)
Caps Lock snapping: (245.3, 156.7) → (240.0, 156.7)
```

### Resize Snapping
```
Box snapping: ENABLED (Caps Lock ON)
Resize snap: right edge to right edge (8px)
Caps Lock resize snapping: (100, 100, 200, 150) → (100, 100, 205, 150)
```

## Known Limitations

1. **No multi-box resize**: Multiple selected boxes don't snap during resize (only drag)
2. **No spacing during resize**: Even spacing detection is only for drag, not resize
3. **Single snap per operation**: Only shows guide for the closest snap on each axis
4. **No grid snapping**: Only snaps to other boxes, not a background grid

## Future Enhancements

Potential improvements:

1. **Adaptive Guides**: Make guides more prominent when very close to snap point
2. **Distance Labels**: Show pixel distance on guides
3. **Magnetic Effect**: Gradual pull-in as you approach snap points
4. **Custom Colors**: User-configurable guide colors
5. **Grid Mode**: Optional grid snapping alongside box snapping
6. **Smart Guides**: Adobe-style guides showing multiple alignments
7. **Spacing Numbers**: Display actual spacing values on measurement guides

## Performance Notes

- Guides are only drawn when Caps Lock is ON
- SVG elements are created/destroyed on demand
- No continuous animation or polling
- Minimal impact on drag/resize performance
- Scales well with typical box counts (< 50 boxes)

## Compatibility

- Works in all modern browsers (Chrome, Firefox, Safari, Edge)
- Uses standard SVG DOM APIs
- No browser-specific features
- Falls back gracefully if SVG layer not found

## Summary

These enhancements make the box snapping system significantly more user-friendly by:

1. **Visibility**: Users can now see what's being aligned
2. **Understanding**: Visual guides explain the snap behavior
3. **Confidence**: Clear feedback when snapping occurs
4. **Completeness**: Resize operations now have same snapping as drag

The implementation maintains the clean, self-contained architecture of the original snapping system while adding valuable visual feedback that makes the feature much more discoverable and understandable.
