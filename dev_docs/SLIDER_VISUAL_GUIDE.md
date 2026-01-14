# Slider Implementation - Visual Guide

## How It Looks in Field4

```
┌─────────────────────────────────────────────────────────────┐
│  Field4 Box (Kotlin Code)                                    │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ var size = 50.0                                         │ │
│  │                                                         │ │
│  │ slider(                                                 │ │
│  │     label = "Size",                                     │ │
│  │     min = 10.0,                                         │ │
│  │     max = 100.0,                                        │ │
│  │     step = 1.0,                                         │ │
│  │     initialValue = size,                                │ │
│  │     channel = "controls"                                │ │
│  │ ) { newValue ->                                         │ │
│  │     size = newValue                                     │ │
│  │     println("Size is now: $size")                       │ │
│  │ }                                                       │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
│  Output Area (Below Box)                                     │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ ▶ controls                                              │ │ ← Disclosure header
│  │ ┌───────────────────────────────────────────────────┐  │ │
│  │ │ Size                                      50       │  │ │ ← Label and value
│  │ │ ├───────────●──────────────────────────────┤       │  │ │ ← Slider (draggable)
│  │ │                                                     │  │ │
│  │ └───────────────────────────────────────────────────┘  │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## Multiple Sliders Example

```
┌─────────────────────────────────────────────────────────────┐
│  Field4 Box                                                   │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ var x = 0.0                                             │ │
│  │ var y = 0.0                                             │ │
│  │ var rotation = 0.0                                      │ │
│  │                                                         │ │
│  │ sliders(                                                │ │
│  │     channel = "transform",                              │ │
│  │     "x" to SliderConfig("X", -100.0, 100.0, 1.0, x)    │ │
│  │             { x = it },                                 │ │
│  │     "y" to SliderConfig("Y", -100.0, 100.0, 1.0, y)    │ │
│  │             { y = it },                                 │ │
│  │     "rot" to SliderConfig("Rotation", 0.0, 360.0,      │ │
│  │                           1.0, rotation) { rotation=it}│ │
│  │ )                                                       │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
│  Output Area                                                 │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ ▼ transform                                             │ │ ← Expanded disclosure
│  │ ┌───────────────────────────────────────────────────┐  │ │
│  │ │ X                                          0       │  │ │
│  │ │ ├──────────────────●────────────────────────┤      │  │ │
│  │ │                                                     │  │ │
│  │ │ Y                                          0       │  │ │
│  │ │ ├──────────────────●────────────────────────┤      │  │ │
│  │ │                                                     │  │ │
│  │ │ Rotation                                   0       │  │ │
│  │ │ ├──────────────────●────────────────────────┤      │  │ │
│  │ └───────────────────────────────────────────────────┘  │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## Color Picker Example

```
┌─────────────────────────────────────────────────────────────┐
│  Output Area with Color Picker                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ ▼ color picker                                          │ │
│  │ ┌───────────────────────────────────────────────────┐  │ │
│  │ │ Red                                      255       │  │ │
│  │ │ ├──────────────────────────────────────●─┤         │  │ │
│  │ │                                                     │  │ │
│  │ │ Green                                    128       │  │ │
│  │ │ ├──────────────────────●─────────────────┤         │  │ │
│  │ │                                                     │  │ │
│  │ │ Blue                                     0         │  │ │
│  │ │ ├●────────────────────────────────────────┤        │  │ │
│  │ └───────────────────────────────────────────────────┘  │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ ▼ color                                                 │ │
│  │ ┌───────────────────────────────────────────────────┐  │ │
│  │ │     ┌────────────────────────────┐                 │  │ │
│  │ │     │                            │                 │  │ │
│  │ │     │         #FF8000            │ ← Color preview│ │ │
│  │ │     │                            │                 │  │ │
│  │ │     └────────────────────────────┘                 │  │ │
│  │ │              #FF8000                               │  │ │
│  │ └───────────────────────────────────────────────────┘  │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## Data Flow Architecture

```
Browser (Frontend)                    Server (Backend)
┌──────────────────┐                 ┌──────────────────┐
│                  │                 │                  │
│  HTML Slider     │  WebSocket      │  Server.kt       │
│  <input range>   │ ─────────────>  │  Handler         │
│                  │  SliderUpdate   │  slider-update   │
│  ● User drags    │  {              │                  │
│    slider        │   sliderId,     │  ┌─────────────┐ │
│                  │   value: 75.0   │  │ Lookup      │ │
│  sliders.js      │  }              │  │ Callback    │ │
│  handleSliderInput│                 │  └──────┬──────┘ │
│  sendSliderUpdate│                 │         │        │
└──────────────────┘                 │    ┌────▼─────┐  │
                                     │    │ Execute  │  │
         ▲                           │    │ Callback │  │
         │                           │    └────┬─────┘  │
         │ HTML Display              │         │        │
         │                           │    ┌────▼─────┐  │
         │                           │    │ Update   │  │
         │                           │    │ Variable │  │
         │                           │    │ x = 75.0 │  │
         │                           │    └──────────┘  │
         │                           │                  │
         └───────────────────────────┼──────────────────┘
                                     │
                                     └─ outputs.kt
                                        slider()
                                        display(html)
```

## Component Interaction

```
┌─────────────────────────────────────────────────────────────┐
│ Field4 User Code (Box)                                       │
│ ┌───────────────────────────────────────────────────────┐   │
│ │ var myValue = 50.0                                    │   │
│ │                                                       │   │
│ │ slider("Parameter", 0.0, 100.0, 1.0, myValue) {      │   │
│ │     myValue = it                     ┌───────────────┼───┼──┐
│ │     // Your code here                │ Callback      │   │  │
│ │ }                                    └───────────────┼───┼──┘
│ └───────────────────────────────────────────────────────┘   │
│                          │                                   │
│                          │ calls                             │
│                          ▼                                   │
│ ┌───────────────────────────────────────────────────────┐   │
│ │ outputs.kt                                            │   │
│ │ • Generates HTML for slider                           │   │
│ │ • Registers callback with UUID                        │   │
│ │ • Sends HTML to browser via display()                 │   │
│ └───────────────────────────────────────────────────────┘   │
│                          │                                   │
│                          │                                   │
│                          ▼                                   │
│ ┌───────────────────────────────────────────────────────┐   │
│ │ Browser (sliders.js)                                  │   │
│ │ • Renders HTML slider                                 │   │
│ │ • Captures user input                                 │   │
│ │ • Sends SliderUpdate via WebSocket                    │   │
│ └───────────────────────────────────────────────────────┘   │
│                          │                                   │
│                          │                                   │
│                          ▼                                   │
│ ┌───────────────────────────────────────────────────────┐   │
│ │ Server.kt                                             │   │
│ │ • Receives SliderUpdate message                       │   │
│ │ • Looks up callback by slider ID                      │   │
│ │ • Invokes callback with new value                     │   │
│ └───────────────────────────────────────────────────────┘   │
│                          │                                   │
│                          │ updates                           │
│                          ▼                                   │
│                    myValue = 75.0                            │
└─────────────────────────────────────────────────────────────┘
```

## Styling & Theme Integration

The sliders match Field4's dark theme:

```
Colors:
  Background:    rgba(0,0,0,0.2)     - Semi-transparent black
  Label:         #ddd               - Light gray
  Value:         #aaa (monospace)   - Slightly darker gray
  Accent:        #4a9eff            - Blue (slider track)
  Border:        4px radius         - Rounded corners

Fonts:
  Labels:        11px, Field4 default
  Values:        10px, monospace
  
Layout:
  Padding:       8px all around
  Margin:        8px vertical spacing
  Flex:          Label left, value right
  Width:         100% of container
```

## Key Features Illustrated

1. **Real-time Updates**: Value display updates as you drag
2. **Precise Control**: Decimal places match step size
3. **Grouped Organization**: Multiple sliders under one disclosure
4. **Visual Feedback**: Current value always visible
5. **Callback Pattern**: Your code runs on every change
6. **Disclosure Integration**: Collapsible, resizable containers
