# Visual Snap Guide Examples

This document shows what the visual snap guides look like in different scenarios.

## Edge Alignment - Vertical Line

When aligning left or right edges:

```
Before (no snap guide):              After (with snap guide):

┌──────────┐                         ┌──────────┐
│  Box A   │                         │  Box A   │
│          │                         │          │
└──────────┘                         └──────────┘
                                     │    cyan
    ┌──────────┐                     │    dashed
    │  Box B   │  ← dragging         │    line
    │          │                     │
    └──────────┘                     ┌──────────┐
                                     │  Box B   │
                                     │          │
                                     └──────────┘
                                     
Visual: A vertical cyan dashed line connects the aligned edges
```

## Edge Alignment - Horizontal Line

When aligning top or bottom edges:

```
Before (no snap guide):              After (with snap guide):

┌──────────┐  ┌──────────┐          ┌──────────┐═════════┌──────────┐
│  Box A   │  │  Box B   │          │  Box A   │  cyan   │  Box B   │
│          │  │          │          │          │  dashed │          │
└──────────┘  └──────────┘          └──────────┘  line   └──────────┘
       
Visual: A horizontal cyan dashed line connects the aligned edges
```

## Spacing Measurement

When creating equal spacing between boxes:

```
Before (uneven spacing):             After (with spacing guide):

┌───┐       ┌───┐     ┌───┐         ┌───┐      ┌───┐      ┌───┐
│ A │       │ B │     │ C │         │ A │──┼───│ B │──┼───│ C │
└───┘       └───┘     └───┘         └───┘  ↑   └───┘  ↑   └───┘
                                           tick  =    tick  =
                                          (cyan)      (cyan)

Visual: Horizontal cyan line with vertical ticks showing equal gaps
```

## Resize Snapping - Right Edge

When resizing to align right edges:

```
Before (resizing):                   After (snapping):

┌──────────┐                         ┌──────────┐
│  Box A   │                         │  Box A   │
└──────────┘                         └──────────┘
                                     │    cyan
┌─────────────→                      │    guide
│  Box B      │                      │
│ (resizing)  │                      ┌────────────┐
└─────────────┘                      │  Box B     │
                                     │            │
                                     └────────────┘

Visual: Right edge snaps to match Box A's right edge
```

## Resize Snapping - Bottom Edge

When resizing to align bottom edges:

```
Before (resizing):                   After (snapping):

┌──────────┐  ┌──────────┐          ┌──────────┐══┌──────────┐
│  Box A   │  │  Box B   │          │  Box A   │  │  Box B   │
│          │  │          │          │          │  │          │
│          │  │ (resize) │          │          │  │          │
└──────────┘  │    ↓     │          └──────────┘══└──────────┘
              └──────────┘                   cyan guide

Visual: Bottom edge snaps to match Box A's bottom edge
```

## Multiple Snap Targets

When near multiple snap points (independent axes):

```
Box A         Box C
┌───┐         ┌───┐
│   │         │   │
└───┘         └───┘
  │    cyan    │
  │    guide   │
  │            │
┌─┴─────────┬──┴─┐
│  Box B    │    │  ← cyan guide
│ (dragging)│    │
└───────────┴────┘
      cyan guide

Visual: Multiple guides can appear - one for horizontal, one for vertical
```

## Color Reference

All visual guides use Field4's cyan theme color:

- **Line Color**: `rgba(72, 201, 255, 0.6)` - Subtle, semi-transparent
- **Marker Color**: `rgba(72, 201, 255, 0.8)` - Slightly more opaque
- **Line Style**: Dashed (4px dash, 3px gap)
- **Line Width**: 1.5px
- **Circle Radius**: 3px (for edge markers)
- **Tick Length**: 5px (for spacing markers)

## Interaction Flow

```
1. Enable Caps Lock
   ↓
2. Start Dragging/Resizing
   ↓
3. Move Near Another Box
   ↓
4. [Cyan Guide Appears] ← Visual Feedback
   ↓
5. Position/Size Snaps ← Automatic Adjustment
   ↓
6. [Console Log] ← Debug Info
   ↓
7. Release Mouse
   ↓
8. [Guide Disappears] ← Auto Cleanup
```

## Guide Lifecycle

```
Drag/Resize Start → Check Caps Lock → Find Snap Targets → Draw Guides
                                                              ↓
                                          Move Away ← Update Position → Snap Found
                                              ↓                              ↓
                                       Clear Guides                    Draw New Guides
                                              ↓                              ↓
                                          Continue Dragging ←────────────────┘
                                              ↓
                                         Release Mouse
                                              ↓
                                         Clear Guides
```

## Performance Characteristics

- **Guide Creation**: < 1ms for typical cases
- **Guide Update**: On every mouse move (throttled by browser)
- **Guide Removal**: Instant (innerHTML = "")
- **Memory**: Minimal (few SVG elements)
- **Scalability**: O(N) where N = number of boxes

## Browser Rendering

The guides are rendered using SVG elements:

```xml
<svg id="overlaysvglayer">
  <g id="snap-guides">
    <!-- Alignment line -->
    <line x1="100" y1="50" x2="100" y2="200"
          stroke="rgba(72, 201, 255, 0.6)"
          stroke-width="1.5"
          stroke-dasharray="4 3" />
    
    <!-- Edge markers (circles) -->
    <circle cx="100" cy="50" r="3"
            fill="rgba(72, 201, 255, 0.4)"
            stroke="rgba(72, 201, 255, 0.8)" />
    
    <!-- Spacing ticks (for measurements) -->
    <line x1="150" y1="95" x2="150" y2="105"
          stroke="rgba(72, 201, 255, 0.8)"
          stroke-width="1.5" />
  </g>
</svg>
```

## User Feedback Loop

```
Visual Cue (Cyan Line) → User Sees Alignment
         ↓
Position Snaps → User Feels Control
         ↓
Console Log → Developer Confirms
         ↓
Guide Disappears → Clean Finish
         ↓
User Confidence Increases
```

## Design Philosophy

The visual guides follow these principles:

1. **Subtle**: Don't distract from the content
2. **Informative**: Show what's being aligned
3. **Temporary**: Disappear when not needed
4. **Consistent**: Same style for all guide types
5. **Responsive**: Update in real-time
6. **Clean**: No visual clutter or artifacts

## Accessibility

- Guides are purely visual enhancement
- Core snapping works without guides
- Console logging provides text alternative
- Guides use high-contrast cyan color
- No flashing or rapid changes

## Notes

- Guides are not saved or persisted
- Guides don't interact with mouse events (pointer-events: none)
- Guides are overlaid on top of boxes (z-index: 0 in SVG layer)
- Guides work in all view states (zoomed, panned, etc.)
