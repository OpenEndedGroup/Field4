# Slideshow Visual Guide - Annotations & Animations

## Visual Examples

### Basic Annotation Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    Browser Canvas                            │
│                                                              │
│  ┌──────────────┐                                           │
│  │ Guide Note   │                                           │
│  │ with HTML    │╲                                          │
│  │ content      │ ╲                                         │
│  │              │  ╲   Annotation Line                      │
│  │ [Link →]     │   ╲  (Cubic Bezier)                       │
│  └──────────────┘    ╲                                      │
│                       ╲                                      │
│                        ○────────────────○                    │
│                                          ╲                   │
│                              ┌────────────╲──────────┐       │
│                              │  Target Box  ○        │       │
│                              │  (or specific line)   │       │
│                              │                       │       │
│                              └───────────────────────┘       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Text Animation Sequence

```
Frame 1:  W
Frame 2:  We
Frame 3:  Wel
Frame 4:  Welc
Frame 5:  Welco
Frame 6:  Welcom
Frame 7:  Welcome
Frame 8:  Welcome!    ← +300ms pause after '!'
Frame 9:  Welcome! T
Frame 10: Welcome! Th
...
```

### Multiple Annotations

```
┌─────────────────────────────────────────────────────────────┐
│                                                              │
│  ┌──────────────┐                                           │
│  │ Guide Note   │                                           │
│  │ "Components" │╲                                          │
│  └──────────────┘ ╲─────────Blue Line                      │
│                     ╲                                        │
│                      ○─────────────○                        │
│                                     ╲                        │
│                          ┌───────────╲──────┐               │
│                          │  Input Box  ○    │               │
│                          └──────────────────┘               │
│                                                              │
│  ┌──────────────┐                                           │
│  │ (same note)  │╲                                          │
│  └──────────────┘ ╲─────────Green Line                     │
│                     ○─────────────○                         │
│                                    ╲                         │
│                         ┌───────────╲──────┐                │
│                         │ Process Box ○    │                │
│                         └──────────────────┘                │
│                                                              │
│  ┌──────────────┐                                           │
│  │ (same note)  │╲                                          │
│  └──────────────┘ ╲─────────Orange Line                    │
│                     ○─────────────○                         │
│                                    ╲                         │
│                         ┌───────────╲──────┐                │
│                         │ Output Box  ○    │                │
│                         └──────────────────┘                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Code-to-Visual Mapping

### Example 1: Single Annotation

**Code:**
```kotlin
step("Important Box") {
    note("<p>Look at this box!</p>")
    annotate("ptr", targetBox)
}
```

**Visual Result:**
```
   ┌─────────────┐
   │ Look at     │
   │ this box!   │
   └─────────────┘
        │
        │ Green curved line
        ↓
   ┌─────────────┐
   │ Target Box  │
   │             │
   └─────────────┘
```

### Example 2: Line Annotation

**Code:**
```kotlin
step("Code Line") {
    note("<p>Line 42 is important!</p>")
    selectBox(codeBox, line = 42, highlight = 42)
    annotateToLine("line", codeBox, line = 42)
}
```

**Visual Result:**
```
   ┌──────────────────┐
   │ Line 42 is       │
   │ important!       │
   └──────────────────┘
          │
          │ Curved line points to
          ↓
   ┌──────────────────┐
   │ 40: function() { │
   │ 41:   const x =  │
   │→42:   return y;  │ ← Highlighted
   │ 43: }            │
   └──────────────────┘
```

### Example 3: Animated Text

**Code:**
```kotlin
step("Welcome") {
    note("<h2>Hello!</h2>", animated = true)
}
```

**Visual Timeline:**
```
T=0ms:    H
T=15ms:   He
T=30ms:   Hel
T=45ms:   Hell
T=60ms:   Hello
T=75ms:   Hello!
T=375ms:  Hello! ← Paused 300ms after '!'
```

## Layer Stack

```
┌─────────────────────────────────────────────┐
│         Browser Rendering Layers             │
├─────────────────────────────────────────────┤
│ Z=10000  │ Slide Note Overlay (HTML)       │
│          │ • Positioned absolutely         │
│          │ • Contains guide text           │
│          │ • Link handlers                 │
├─────────────────────────────────────────────┤
│ Z=0      │ SVG Overlay Layer               │
│          │ • overlaysvglayer element       │
│          │ • Contains annotation lines     │
│          │ • Group: slideAnnotations       │
├─────────────────────────────────────────────┤
│ Z=varies │ Box Elements                    │
│          │ • Actual code boxes             │
│          │ • Monaco editors                │
│          │ • Output areas                  │
└─────────────────────────────────────────────┘
```

## Annotation Line Anatomy

```
Start Point (○)
    │
    │ Control point 1
    │     ╱
    │    ╱
    │   ╱
    │  ╱   Cubic Bezier curve
    │ ╱    (smooth transition)
    │╱
    ╱
   ╱ Control point 2
  ╱
 ╱
○ End Point
```

**Parameters:**
- `strokeWidth`: Thickness (default 2.0)
- `color`: RGBA color (default green)
- `dashed`: Boolean for dash pattern
- Start circle: 3px radius
- End circle: 4px radius

## Color Palette Visual

```
┌────────────────────────────────────────────┐
│ Success/Positive (Green)                   │
│ rgba(128, 255, 164, 0.8) ▓▓▓▓▓▓▓▓▓▓       │
├────────────────────────────────────────────┤
│ Warning/Attention (Orange)                 │
│ rgba(255, 180, 100, 0.8) ▓▓▓▓▓▓▓▓▓▓       │
├────────────────────────────────────────────┤
│ Information (Blue)                         │
│ rgba(100, 200, 255, 0.8) ▓▓▓▓▓▓▓▓▓▓       │
├────────────────────────────────────────────┤
│ Error/Critical (Red)                       │
│ rgba(255, 100, 100, 0.8) ▓▓▓▓▓▓▓▓▓▓       │
├────────────────────────────────────────────┤
│ Neutral (Gray)                             │
│ rgba(200, 200, 200, 0.7) ▓▓▓▓▓▓▓▓▓▓       │
└────────────────────────────────────────────┘
```

## Animation Timing Diagram

```
Character stream:  H e l l o ,   w o r l d !
                   ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓
Base delay:       15ms per character
                   ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓
Punctuation:          comma↓       exclaim↓
                         +150ms        +300ms
                           ↓             ↓
Total timing:     ──────────────────────────────→
                  0   75  225  240   405  720ms
```

## State Machine Diagram

```
                    ┌──────────────┐
                    │ No Slideshow │
                    │   Active     │
                    └──────┬───────┘
                           │
                   slideShow.start()
                           │
                           ↓
                    ┌──────────────┐
                    │ Step Shown   │
              ┌─────┤ with Note    │─────┐
              │     │ & Annotations│     │
              │     └──────────────┘     │
              │                          │
         next()│                      ↑previous()
              │                          │
              ↓                          │
        ┌──────────────┐           ┌────────────┐
        │ Clear Old    │           │ Go Back    │
        │ Annotations  │           │            │
        └──────┬───────┘           └────────────┘
               │
               ↓
        ┌──────────────┐
        │ Draw New     │
        │ Annotations  │
        └──────┬───────┘
               │
               ↓
        ┌──────────────┐
        │ Animate Text │
        │ (if enabled) │
        └──────────────┘
```

## Reposition Flow

```
User action (scroll, drag, resize)
        ↓
canvasGestures.rebuildConnectionGroup()
        │
        ↓
repositionSlideNoteOverlayIfVisible()
        │
        ├→ positionSlideNoteOverlay()
        │  (Update overlay position)
        │
        └→ redrawSlideAnnotations()
           (Update line positions)
                 ↓
           Lines stay connected!
```

## Complete Example Visual

**Code:**
```kotlin
slideShow {
    step("System Flow") {
        note("""
            <h3>Data Flow</h3>
            <p>Input → Process → Output</p>
        """.trimIndent(), animated = true)
        
        annotate("in", inputBox, 
            color = "rgba(100, 200, 255, 0.8)")
        annotate("proc", processBox,
            color = "rgba(100, 255, 100, 0.8)")
        annotate("out", outputBox,
            color = "rgba(255, 180, 100, 0.8)")
    }
}
```

**Visual Result:**
```
┌────────────────────────────────────────────────────────────┐
│                                                             │
│  ┌──────────────────┐                                      │
│  │ Data Flow        │  (Animates in character by char)     │
│  │ Input → Process  │                                      │
│  │       → Output   │                                      │
│  └──────────────────┘                                      │
│         │  │  │                                            │
│      Blue│Green│Orange                                     │
│         │  │  │                                            │
│    ┌────○  │  │                                            │
│    │       │  │                                            │
│    ↓       │  │                                            │
│  ┌─────┐   │  │                                            │
│  │Input│   │  │                                            │
│  │ Box │   │  │                                            │
│  └─────┘   │  │                                            │
│            │  │                                            │
│      ┌─────○  │                                            │
│      │        │                                            │
│      ↓        │                                            │
│  ┌────────┐   │                                            │
│  │Process │   │                                            │
│  │  Box   │   │                                            │
│  └────────┘   │                                            │
│               │                                            │
│         ┌─────○                                            │
│         │                                                  │
│         ↓                                                  │
│  ┌────────┐                                                │
│  │Output  │                                                │
│  │  Box   │                                                │
│  └────────┘                                                │
│                                                             │
└────────────────────────────────────────────────────────────┘
```

## See Also

- [Quick Reference](SLIDESHOW_ANNOTATIONS_QUICKREF.md)
- [Full Documentation](SLIDESHOW_ANNOTATIONS.md)
- [Implementation Details](DEEP_SLIDESHOW_IMPLEMENTATION.md)
- [Example Code](src/jvmMain/kotlin/afield/slideshow_example.kt)
