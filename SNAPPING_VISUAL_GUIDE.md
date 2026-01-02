# Box Snapping System - Visual Guide

## Snapping Types Illustrated

### 1. Edge-to-Edge Alignment

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

### 2. Center Alignment

#### Horizontal Center
```
Before dragging:              During drag (Caps Lock ON):
┌──────┐                      ┌──────┐
│ Box A│                      │ Box A│
└──────┘                      └──────┘
    ↕                             ↕
┌────────────┐                ┌────────────┐
│   Box B    │                │   Box B    │  ← Centers aligned
└────────────┘                └────────────┘
```

#### Vertical Center
```
Before dragging:              During drag (Caps Lock ON):
┌─────┐ ┌─────────┐          ┌─────┐ ┌─────────┐
│Box A│ │  Box B  │          │Box A│ │  Box B  │
└─────┘ │         │          └─────┘ │         │  ← Centers aligned
        └─────────┘          ↔──────↔└─────────┘
```

### 3. Even Spacing

#### Horizontal Spacing
```
Before dragging:              During drag (Caps Lock ON):
┌───┐      ┌───┐             ┌───┐     ┌───┐     ┌───┐
│ A │      │ B │             │ A │     │ B │     │ C │
└───┘      └───┘             └───┘     └───┘     └───┘
             ┌───┐                  ↕       ↕
             │ C │  →         Equal gap   Equal gap
             └───┘
```

#### Vertical Spacing
```
Before:                       During drag (Caps Lock ON):
┌─────┐                      ┌─────┐
│  A  │                      │  A  │
└─────┘                      └─────┘
                                ↕  Equal gap
┌─────┐                      ┌─────┐
│  C  │          →           │  B  │
└─────┘                      └─────┘
                                ↕  Equal gap
        ┌─────┐              ┌─────┐
        │  B  │              │  C  │
        └─────┘              └─────┘
```

## Snap Zones

### Threshold Visualization

```
        No Snap Zone
             ↕
    ╔════════════════╗
    ║                ║
    ║   ┌──────┐     ║  ← 10px snap threshold
    ║   │ Box  │     ║
    ║   └──────┘     ║
    ║                ║
    ╚════════════════╝
         Snap Zone
```

When a box is dragged within 10 pixels of another box's edge, it snaps.

### Extended Spacing Threshold

```
        No Spacing Zone
             ↕
    ╔════════════════════╗
    ║                    ║
    ║   ┌───┐   ┌───┐    ║  ← 30px spacing threshold
    ║   │ A │   │ B │    ║
    ║   └───┘   └───┘    ║
    ║                    ║
    ║      [drag C       ║
    ║       here]        ║
    ║                    ║
    ╚════════════════════╝
      Spacing Detection Zone
```

For even spacing, detection works up to 30 pixels away.

## Multiple Snap Candidates

### Priority Selection

When multiple snap targets are available, the closest one wins:

```
         ┌───┐
         │ A │  ← 8px away (left edge)
         └───┘

    ┌─────┐
    │  B  │      ← Being dragged
    └─────┘

              ┌───┐
              │ C │  ← 12px away (right edge)
              └───┘

Result: Snaps to Box A (closer)
```

### Independent Axes

Horizontal and vertical snapping work independently:

```
┌───┐               ┌───┐
│ A │               │ B │
└───┘               └───┘
        ┌─────┐
        │  D  │    ← Being dragged
        └─────┘
┌───┐               ┌───┐
│ C │               │ E │
└───┘               └───┘

Can snap to:
- Horizontal: Box B's left edge
- Vertical: Box C's top edge
Both at the same time!
```

## User Workflow

### Step-by-Step Usage

```
1. Initial State              2. Enable Snapping          3. Drag Near Target
   (Caps Lock OFF)              (Press Caps Lock)           (Shows snap guides)

┌────┐   ┌────┐            ┌────┐   ┌────┐            ┌────┐   ┌────┐
│ A  │   │ B  │            │ A  │   │ B  │            │ A  │───│ B  │
└────┘   └────┘            └────┘   └────┘            └────┘   └────┘
   ┌────┐                     ┌────┐                          ┌────┐
   │ C  │                     │ C  │      →                   │ C  │
   └────┘                     └────┘                          └────┘
                              [CAPS]                      (Snapped!)


4. Final Position             Console Output:
   (Release mouse)            
                              Box snapping: ENABLED
┌────┐   ┌────┐              Snapping horizontally: RIGHT_EDGE (8px)
│ A  │   │ B  │              Caps Lock snapping: (243,100) → (240,100)
└────┘   └────┘
         ┌────┐
         │ C  │
         └────┘
```

## Algorithm Overview

### Decision Flow

```
┌─────────────────┐
│  Mouse Moved    │
└────────┬────────┘
         │
         ↓
┌─────────────────┐      NO    ┌──────────────────┐
│ Caps Lock ON?   │────────────→│ Use Proposed Pos │
└────────┬────────┘             └──────────────────┘
         │ YES
         ↓
┌─────────────────┐
│ Get All Boxes   │
└────────┬────────┘
         │
         ↓
┌─────────────────┐
│ Find Horizontal │
│  Snap Targets   │
└────────┬────────┘
         │
         ↓
┌─────────────────┐
│  Find Vertical  │
│  Snap Targets   │
└────────┬────────┘
         │
         ↓
┌─────────────────┐
│ Select Closest  │
│ Target Per Axis │
└────────┬────────┘
         │
         ↓
┌─────────────────┐
│ Apply Snap if   │
│ Within Threshold│
└────────┬────────┘
         │
         ↓
┌─────────────────┐
│ Send to Server  │
└─────────────────┘
```

## Performance Characteristics

### Complexity Analysis

```
For N boxes:

Edge Alignment:
- Time: O(N) per drag event
- Space: O(N) for target list

Even Spacing:
- Time: O(N log N) for sorting + O(N) for checking
- Space: O(N) for sorted list

Overall: O(N log N) per drag event
Acceptable for typical use (< 100 boxes)
```

### Optimization Strategy

```
Only Active When:
✓ Caps Lock is ON
✓ Box is being dragged
✗ Not during resize
✗ Not during box creation
```

## Browser Console Examples

### Successful Snap
```
Box snapping: ENABLED (Caps Lock ON)
Snapping horizontally: LEFT_EDGE (distance: 5px)
Snapping vertically: CENTER (distance: 2px)
Caps Lock snapping: (245.3, 156.7) → (240.0, 155.0)
```

### No Snap (Too Far)
```
Box snapping: ENABLED (Caps Lock ON)
(No snap messages - too far from targets)
```

### Spacing Snap
```
Box snapping: ENABLED (Caps Lock ON)
Snapping horizontally: HORIZONTAL_SPACING (distance: 12px)
Caps Lock snapping: (298.5, 100.0) → (290.0, 100.0)
```

### Toggle During Drag
```
Box snapping: ENABLED (Caps Lock ON)
Snapping horizontally: RIGHT_EDGE (distance: 6px)
Box snapping: DISABLED (Caps Lock OFF)
(Smooth transition to free dragging)
```
