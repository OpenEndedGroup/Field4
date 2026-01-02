# Box Snapping System

## Overview

The box snapping system provides intelligent alignment assistance when dragging boxes in Field4. When enabled, it automatically aligns boxes to each other's edges and maintains even spacing, making it easier to create clean, organized layouts.

## Usage

### Enabling Snapping

**Press Caps Lock** to toggle snapping on/off while dragging boxes.

- **Caps Lock ON**: Snapping is active
- **Caps Lock OFF**: Normal free-form dragging

The system continuously monitors the Caps Lock state, so you can toggle it at any time during a drag operation.

## Features

### 1. Edge Alignment

When dragging a box with snapping enabled, it will automatically align to:

- **Left edges**: Aligns the left edge of the dragged box to the left or right edge of nearby boxes
- **Right edges**: Aligns the right edge of the dragged box to the right or left edge of nearby boxes
- **Top edges**: Aligns the top edge of the dragged box to the top or bottom edge of nearby boxes
- **Bottom edges**: Aligns the bottom edge of the dragged box to the bottom or top edge of nearby boxes
- **Center alignment**: Aligns the center of the dragged box to the center of nearby boxes (both horizontal and vertical)

### 2. Even Spacing

The system recognizes when a box is being positioned between two other boxes and suggests positions that create equal gaps:

- **Horizontal spacing**: When dragging between two boxes horizontally aligned
- **Vertical spacing**: When dragging between two boxes vertically aligned

### 3. Smart Threshold

- **Snap threshold**: 10 pixels - The maximum distance at which snapping activates
- **Extended threshold for spacing**: 30 pixels - Even spacing detection works at a longer range
- **Minimum spacing**: 5 pixels - The minimum gap required for spacing suggestions

## Technical Details

### Architecture

The snapping system is implemented in a self-contained file (`BoxSnapping.kt`) and integrates seamlessly with Field4's existing drag architecture:

1. **No Visual Jitter**: Snapping is applied before sending position updates to the server, maintaining the round-trip synchronization that prevents visual jitter
2. **Multi-View Sync**: Snapped positions are broadcast to all clients viewing the same document
3. **Non-Intrusive**: When Caps Lock is off, the system has zero impact on normal dragging behavior

### Implementation Files

- **`src/jsMain/kotlin/afield/BoxSnapping.kt`**: Complete snapping system implementation
- **`src/jsMain/kotlin/afield/Main.kt`**: Integration point in the `moveHandler` function

### Key Components

```kotlin
// Global instance
val boxSnappingSystem = BoxSnappingSystem()

// In Main.kt initialization
boxSnappingSystem.initialize()

// In moveHandler during drag
val (snappedX, snappedY) = boxSnappingSystem.applySnapping(
    targetElement,
    proposedX,
    proposedY,
    width,
    height
)
```

### How It Works

1. **Caps Lock Detection**: Uses keyboard event listeners to track Caps Lock state
2. **Target Collection**: Gathers all visible boxes and their positions
3. **Snap Calculation**: For each axis (horizontal/vertical):
   - Calculates distances to all possible snap points
   - Identifies even spacing opportunities
   - Selects the closest snap target within threshold
4. **Position Adjustment**: Returns the snapped position to be sent to server
5. **Server Round-Trip**: Server broadcasts position to all clients, maintaining sync

## Visual Feedback

When snapping is active, **subtle cyan guide lines** appear to show what alignments are being detected:

- **Edge Alignment**: Vertical or horizontal lines showing which edges are aligned
- **Spacing Measurements**: Lines with measurement ticks showing equal spacing between boxes
- **Snap Points**: Small cyan circles marking key alignment points

The visual guides automatically appear when you get close to a snap point and disappear when you move away or release the mouse.

### Visual Guide Colors
- **Line Color**: Subtle cyan (`rgba(72, 201, 255, 0.6)`)
- **Line Style**: Dashed (4px dash, 3px gap)
- **Markers**: Slightly more opaque cyan (`rgba(72, 201, 255, 0.8)`)

## Resize Snapping

In addition to drag-to-snap, the system now supports **resize snapping**:

- **Edge Snapping**: When resizing, edges snap to other box edges
- **All Four Edges**: Works with top, bottom, left, and right edges
- **Visual Feedback**: Same cyan guides appear during resize operations
- **Caps Lock Toggle**: Enable/disable with Caps Lock, just like drag snapping

### Resize Snap Behavior
- **Right Edge**: Snaps to right or left edges of other boxes
- **Left Edge**: Snaps to left or right edges of other boxes  
- **Bottom Edge**: Snaps to bottom or top edges of other boxes
- **Top Edge**: Snaps to top or bottom edges of other boxes

## Future Enhancements

Possible improvements for future versions:

- Configurable snap threshold via preferences
- Grid snapping option
- Snap to canvas grid or guides
- Snap to specific angles when dragging
- Multi-box snap (when dragging multiple selected boxes)

## Troubleshooting

### Snapping Not Working

1. **Check Caps Lock**: Make sure Caps Lock is actually enabled (check your keyboard indicator)
2. **Browser Support**: The system uses `getModifierState("CapsLock")` which is supported in modern browsers
3. **Multiple Boxes**: Snapping requires at least one other box to snap to

### Unexpected Behavior

- If snapping feels too aggressive, the threshold can be adjusted in `BoxSnapping.kt` by changing `SNAP_THRESHOLD`
- If spacing detection isn't working, check that boxes are reasonably aligned in the perpendicular axis

## Examples

### Example 1: Aligning Left Edges

```
Before:                 After (with snap):
┌─────┐                ┌─────┐
│ Box1│                │ Box1│
└─────┘                └─────┘
  ┌─────┐              ┌─────┐
  │ Box2│      →       │ Box2│  (left edges aligned)
  └─────┘              └─────┘
```

### Example 2: Even Spacing

```
Before:                 After (with snap):
┌───┐  ┌───┐          ┌───┐   ┌───┐   ┌───┐
│ A │  │ B │          │ A │   │ B │   │ C │
└───┘  └───┘          └───┘   └───┘   └───┘
         ┌───┐           (equal gaps maintained)
         │ C │      →
         └───┘
```

### Example 3: Vertical Alignment

```
Before:                 After (with snap):
┌─────┐                ┌─────┐
│     │                │     │
│ Box1│                │ Box1│
│     │                │     │
└─────┘                └─────┘
                       ┌─────┐
┌─────┐                │     │
│     │                │ Box2│ (top aligned with bottom of Box1)
│ Box2│      →         │     │
│     │                └─────┘
└─────┘
```
