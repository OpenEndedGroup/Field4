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

- **Snap threshold**: 10 pixels (`SNAP_THRESHOLD`) - The applied-snap gate. On each axis, snapping only commits when the closest target is within 10px.
- **Candidate detection + per-type apply gate**: Edge/center candidates are collected out to 20px (`SNAP_THRESHOLD * 2`) and engage when they are the closest target within 10px; even-spacing candidates are collected out to 30px (`SNAP_THRESHOLD * 3`) and engage within that same 30px window (`applyThresholdFor`). So a spacing snap applies across its full 30px detection window, while edge/center snaps commit within 10px.
- **Minimum spacing**: 5 pixels (`MIN_SPACING`) - The minimum gap required for an even-spacing suggestion.

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

> **Console debugging**: `BoxSnapping.kt` can log the snap decisions (enabled/disabled, per-axis snap type/distance, before→after coordinates), but this output is gated behind `private const val DEBUG_SNAPPING = false`. Nothing appears in the browser console during normal use — a developer must flip `DEBUG_SNAPPING` to `true` and recompile to see it.

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

## Illustrated

Conceptual ASCII diagrams of each snap type and the guide visuals. (Folded in from the former SNAPPING_VISUAL_GUIDE.md / SNAPPING_VISUAL_EXAMPLES.md.)

### Edge-to-Edge Alignment

#### Left Edge Snapping
```
Before dragging:              During drag (Caps Lock ON):
┌────────┐                    ┌────────┐
│  Box A │                    │  Box A │
└────────┘                    └────────┘

      ┌────────┐              ┌────────┐
      │  Box B │              │  Box B │    ← Snaps left edge to left edge
      └────────┘              └────────┘
```

#### Right Edge Snapping
```
Before dragging:              During drag (Caps Lock ON):
    ┌────────┐                    ┌────────┐
    │  Box A │                    │  Box A │
    └────────┘                    └────────┘

┌────────┐                    ┌────────┐
│  Box B │                    │  Box B │    ← Snaps right edge to right edge
└────────┘                    └────────┘
```

#### Top Edge Snapping
```
Before dragging:              During drag (Caps Lock ON):
┌────────┐  ┌────────┐        ┌────────┐  ┌────────┐
│  Box A │  │  Box B │        │  Box A │  │  Box B │  ← Tops aligned
└────────┘  │        │        └────────┘  │        │
            │        │                    │        │
            └────────┘                    └────────┘
```

#### Bottom Edge Snapping
```
Before dragging:              During drag (Caps Lock ON):
┌────────┐                    ┌────────┐
│  Box A │  ┌────────┐        │  Box A │  ┌────────┐
│        │  │  Box B │        │        │  │  Box B │  ← Bottoms aligned
│        │  └────────┘        │        │  └────────┘
└────────┘                    └────────┘
```

### Center Alignment

Center alignment reuses the edge snap types internally: a horizontal center snap is tagged `LEFT_EDGE`, a vertical center snap is tagged `TOP_EDGE` (there is no `CENTER` value in the `SnapType` enum).

```
Horizontal Center:            Vertical Center:
┌──────┐                      ┌─────┐ ┌─────────┐
│ Box A│                      │Box A│ │  Box B  │
└──────┘                      └─────┘ │         │  ← Centers aligned
┌────────────┐               ↔──────↔└─────────┘
│   Box B    │  ← Centers
└────────────┘     aligned
```

### Even Spacing (drag only)

```
Horizontal:                   Vertical:
┌───┐     ┌───┐     ┌───┐     ┌─────┐
│ A │     │ B │     │ C │     │  A  │
└───┘     └───┘     └───┘     └─────┘
     ↕         ↕                 ↕  Equal gap
  Equal gap  Equal gap        ┌─────┐
                              │  B  │
                              └─────┘
                                 ↕  Equal gap
                              ┌─────┐
                              │  C  │
                              └─────┘
```

### Priority Selection & Independent Axes

When several targets are in range on an axis, the closest one wins (drag). Horizontal and vertical snaps are computed independently, so a box can snap on both axes at once.

```
         ┌───┐               ┌───┐               ┌───┐
         │ A │ ← 8px away     │ A │               │ B │
         └───┘               └───┘               └───┘
    ┌─────┐                          ┌─────┐
    │  B  │ ← dragged                │  D  │  ← Being dragged
    └─────┘                          └─────┘
              ┌───┐          ┌───┐               ┌───┐
              │ C │ ← 12px   │ C │               │ E │
              └───┘          └───┘
Result: snaps to A (closer)  Can snap horizontally to B's left edge AND
                             vertically to C's top edge simultaneously.
```

### Resize Snapping

Resize snaps all four edges (right/left/bottom/top) to the nearest matching edge of another box, with the same cyan guides. Unlike drag, resize appends every in-range edge match, so multiple simultaneous edge guides from different boxes can render at once.

```
Right edge:                          Bottom edge:
┌──────────┐                         ┌──────────┐══┌──────────┐
│  Box A   │                         │  Box A   │  │  Box B   │
└──────────┘                         │          │  │ (resize) │
│  cyan guide                        └──────────┘══└──────────┘
┌────────────┐                                cyan guide
│  Box B     │  ← right edge snapped
└────────────┘     to Box A's right edge
```

### The Guides Themselves (SVG)

Guides are drawn into a `<g id="snap-guides">` group created lazily under `#overlaysvglayer` (the overlay layer sits at `z-index: 17`; guides use `pointer-events: none`). If `#overlaysvglayer` is missing the code throws (`error(...)`) rather than degrading silently.

#### Color / style reference
| Property | Value |
|----------|-------|
| Line color | `rgba(72, 201, 255, 0.6)` (subtle cyan) |
| Marker color | `rgba(72, 201, 255, 0.8)` |
| Edge-marker fill | `rgba(72, 201, 255, 0.4)` |
| Line style | Dashed (`stroke-dasharray "4 3"`) |
| Line width | 1.5px |
| Circle radius | 3px (edge markers) |
| Tick half-length | 5px (10px total span, spacing markers) |

#### Rendered markup (example)
```xml
<svg id="overlaysvglayer">
  <g id="snap-guides">
    <!-- Alignment line -->
    <line x1="100" y1="50" x2="100" y2="200"
          stroke="rgba(72, 201, 255, 0.6)"
          stroke-width="1.5"
          stroke-dasharray="4 3" pointer-events="none" />
    <!-- Edge markers (circles) -->
    <circle cx="100" cy="50" r="3"
            fill="rgba(72, 201, 255, 0.4)"
            stroke="rgba(72, 201, 255, 0.8)" pointer-events="none" />
    <!-- Spacing ticks (for measurements) -->
    <line x1="150" y1="95" x2="150" y2="105"
          stroke="rgba(72, 201, 255, 0.8)"
          stroke-width="1.5" pointer-events="none" />
  </g>
</svg>
```

### Complexity

O(N log N) per drag/resize move for N boxes — the even-spacing search sorts the other boxes by position; edge alignment is O(N). Guides are only computed while Caps Lock is on.
