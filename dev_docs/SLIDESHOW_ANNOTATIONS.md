# Slideshow Annotations and Animations

## Overview

This document describes the advanced slideshow features that allow you to draw lines from guide text to boxes and animate text for better user engagement.

## Features

### 1. Annotation Lines

Annotation lines allow you to draw visual connections from your slideshow guide text to:
- Entire boxes in your Field document
- Specific lines of code in editor boxes
- Multiple targets simultaneously

#### Drawing Lines to Boxes

Use the `annotate()` function in your step builder:

```kotlin
step("Pointing to a Box") {
    note("""
        <h3>Look Here!</h3>
        <p>This line points to an important box.</p>
    """.trimIndent())
    
    selectBox(targetBox)
    
    // Draw an annotation line
    annotate(
        id = "my-annotation",
        targetBox = targetBox,
        color = "rgba(128, 255, 164, 0.8)",  // optional
        strokeWidth = 2.5,                    // optional
        dashed = false                        // optional
    )
}
```

#### Drawing Lines to Specific Code Lines

Use the `annotateToLine()` function to point to specific lines:

```kotlin
step("Pointing to Code") {
    note("""
        <h3>Important Line</h3>
        <p>Check out line 42!</p>
    """.trimIndent())
    
    selectBox(codeBox, line = 42, highlight = 42)
    
    // Draw annotation to specific line
    annotateToLine(
        id = "line-pointer",
        targetBox = codeBox,
        property = "code",
        line = 42,
        color = "rgba(255, 180, 100, 0.8)",
        strokeWidth = 2.0,
        dashed = true
    )
}
```

#### Multiple Annotations

You can add multiple annotation lines in a single step:

```kotlin
step("Multiple Pointers") {
    note("""
        <h3>Related Components</h3>
        <p>These boxes work together.</p>
    """.trimIndent())
    
    selectBox(mainBox)
    
    annotate("box1", box1, color = "rgba(100, 200, 255, 0.7)")
    annotate("box2", box2, color = "rgba(255, 100, 200, 0.7)")
    annotate("box3", box3, color = "rgba(100, 255, 100, 0.7)")
}
```

### 2. Text Animation

Enable letter-by-letter text animation with automatic pauses on punctuation:

```kotlin
step("Animated Introduction") {
    note("""
        <h2>Welcome!</h2>
        <p>This text will animate in character by character.</p>
        <p>Notice the pause at the end of sentences.</p>
    """.trimIndent(), animated = true)  // Enable animation
    
    selectBox(welcomeBox)
}
```

#### Animation Behavior

- **Base Speed**: 15ms per character
- **Period/Exclamation/Question**: +300ms pause
- **Comma/Semicolon/Colon**: +150ms pause
- **HTML Preserved**: Animation works with HTML tags

## API Reference

### SlideStepBuilder Methods

#### `annotate()`

Draws a line from the guide text to a target box.

**Parameters:**
- `id: String` - Unique identifier for this annotation
- `targetBox: Box` - The box to point to
- `color: String` (default: `"rgba(128, 255, 164, 0.7)"`) - Line color
- `strokeWidth: Double` (default: `2.0`) - Line width in pixels
- `dashed: Boolean` (default: `false`) - Whether to use dashed line

**Example:**
```kotlin
annotate(
    id = "main-pointer",
    targetBox = myBox,
    color = "rgba(255, 200, 100, 0.9)",
    strokeWidth = 3.0,
    dashed = true
)
```

#### `annotateToLine()`

Draws a line from the guide text to a specific line in an editor.

**Parameters:**
- `id: String` - Unique identifier for this annotation
- `targetBox: Box` - The box containing the code
- `property: String` (default: `"code"`) - The property name containing the code
- `line: Int` - The line number to point to (1-indexed)
- `color: String` (default: `"rgba(128, 255, 164, 0.7)"`) - Line color
- `strokeWidth: Double` (default: `2.0`) - Line width in pixels
- `dashed: Boolean` (default: `false`) - Whether to use dashed line

**Example:**
```kotlin
annotateToLine(
    id = "line-42",
    targetBox = codeBox,
    property = "code",
    line = 42,
    color = "rgba(255, 100, 100, 0.8)",
    strokeWidth = 2.5
)
```

#### `note()` - Updated

The `note()` function now accepts an `animated` parameter.

**Parameters:**
- `html: String` - HTML content for the note
- `persistent: Boolean` (default: `false`) - Show close button
- `anchorBox: Box?` (default: `null`) - Box to anchor note to
- `animated: Boolean` (default: `false`) - Enable letter-by-letter animation

**Example:**
```kotlin
note(
    html = "<h3>Hello!</h3><p>Watch this text appear.</p>",
    animated = true,
    persistent = false
)
```

## Complete Example

```kotlin
fun Box.annotatedSlideShow() {
    val mainBox = this
    val helperBox = this.children.firstOrNull() ?: this
    
    slideShow {
        step("Introduction") {
            note("""
                <h2>Welcome to the Tour!</h2>
                <p>This guide demonstrates annotations and animations.</p>
                <p><a href="#slideshow:next">Let's begin →</a></p>
            """.trimIndent(), animated = true)
            
            selectBox(mainBox)
        }
        
        step("Single Annotation") {
            note("""
                <h3>Important Box</h3>
                <p>This arrow points to a key component.</p>
                <p><a href="#slideshow:next">Next →</a></p>
            """.trimIndent())
            
            selectBox(mainBox)
            annotate(
                id = "main",
                targetBox = helperBox,
                color = "rgba(100, 200, 255, 0.8)"
            )
        }
        
        step("Code Annotation") {
            note("""
                <h3>Key Line</h3>
                <p>Line 10 contains the main logic.</p>
                <p><a href="#slideshow:next">Next →</a></p>
            """.trimIndent(), animated = true)
            
            selectBox(helperBox, line = 10, highlight = 10)
            annotateToLine(
                id = "code-line",
                targetBox = helperBox,
                line = 10,
                color = "rgba(255, 180, 100, 0.8)",
                dashed = true
            )
        }
        
        step("Multiple Annotations") {
            note("""
                <h3>Related Parts</h3>
                <p>These boxes work together.</p>
                <p><a href="#slideshow:stop">Finish</a></p>
            """.trimIndent(), persistent = true)
            
            annotate("main", mainBox, color = "rgba(100, 255, 100, 0.7)")
            annotate("helper", helperBox, color = "rgba(255, 100, 255, 0.7)")
        }
    }.apply {
        GlobalScope.launch { start() }
    }
}
```

## Technical Details

### Rendering System

Annotations use Field's existing SVG overlay system:
- Lines drawn on the `overlaysvglayer` SVG element
- Uses the `slideAnnotations` SVG group
- Automatically cleared when steps change
- Redrawn when overlay repositions

### Line Drawing

- **Path Type**: Cubic Bezier curves for smooth appearance
- **Start Point**: Right edge of guide overlay (vertically centered)
- **End Point**: Left edge of target box (or specific line position)
- **Control Points**: Calculated based on distance for natural curve
- **Endpoints**: Small circles at both ends for clarity

### Animation System

- **DOM Traversal**: Walks text nodes in HTML structure
- **Progressive Reveal**: Shows characters one at a time
- **Punctuation Detection**: Pauses based on character type
- **HTML Safe**: Preserves tags and structure

### Performance

- **Lazy Rendering**: Annotations only drawn when visible
- **Efficient Updates**: Redraws only when necessary (on reposition)
- **Cleanup**: Automatic cleanup when hiding notes
- **No Memory Leaks**: Properly clears references

## Styling Tips

### Color Schemes

Good color choices for annotations:

```kotlin
// Success/Positive
color = "rgba(128, 255, 164, 0.8)"  // Green

// Warning/Attention
color = "rgba(255, 180, 100, 0.8)"  // Orange

// Information
color = "rgba(100, 200, 255, 0.8)"  // Blue

// Error/Important
color = "rgba(255, 100, 100, 0.8)"  // Red

// Neutral
color = "rgba(200, 200, 200, 0.7)"  // Gray
```

### Line Styles

```kotlin
// Subtle suggestion
strokeWidth = 1.5
dashed = true

// Strong emphasis
strokeWidth = 3.0
dashed = false

// Multiple related items
strokeWidth = 2.0
// Use different colors for each
```

## Best Practices

### 1. Annotation Usage

- **Don't Overuse**: Too many lines can be confusing
- **Color Consistency**: Use similar colors for related items
- **Clear Purpose**: Each annotation should have obvious intent
- **Progressive Disclosure**: Introduce concepts one at a time

### 2. Animation Usage

- **First Impressions**: Use animation for intro slides
- **Important Messages**: Animate critical information
- **Not Everywhere**: Too much animation becomes tedious
- **Keep It Short**: Long animated text can be slow

### 3. Combining Features

```kotlin
step("Best of Both") {
    // Animated text draws attention
    note("""
        <h3>Critical Section</h3>
        <p>This code handles the main processing loop.</p>
    """.trimIndent(), animated = true)
    
    selectBox(processingBox, line = 25, highlight = 25)
    
    // Annotation reinforces the point
    annotateToLine(
        id = "loop",
        targetBox = processingBox,
        line = 25,
        color = "rgba(255, 180, 100, 0.8)"
    )
}
```

## Troubleshooting

### Annotations Not Appearing

1. **Check Box IDs**: Ensure target boxes exist
2. **Verify Visibility**: Target boxes must be visible
3. **Check Console**: Look for JavaScript errors
4. **SVG Layer**: Confirm overlaysvglayer exists

### Animation Issues

1. **HTML Structure**: Ensure HTML is well-formed
2. **Timing**: Adjust delay if too fast/slow
3. **Long Text**: Consider breaking into multiple steps

### Positioning Problems

1. **Canvas Translation**: Annotations account for pan/zoom
2. **Scrolling**: System handles scroll automatically
3. **Dynamic Content**: Repositions on overlay movement

## Migration Guide

Existing slideshows continue to work without changes:

```kotlin
// Old code (still works)
step("My Step") {
    note("<p>Hello</p>")
    selectBox(myBox)
}

// New features (optional)
step("My Step") {
    note("<p>Hello</p>", animated = true)  // Add animation
    selectBox(myBox)
    annotate("pointer", myBox)             // Add annotation
}
```

## Future Enhancements

Potential future additions:
- Annotation arrows with different shapes
- Animation speed control
- Custom animation easing
- Annotation click handlers
- Multiple line styles (dotted, wavy, etc.)
- Annotation labels
- Monaco editor line position integration

## See Also

- [Slideshow Quickstart](SLIDESHOW_QUICKSTART.md)
- [Slideshow Feature Guide](SLIDESHOW_FEATURE.md)
- [Slideshow Architecture](SLIDESHOW_ARCHITECTURE.md)
- [Example Code](src/jvmMain/kotlin/afield/slideshow_example.kt)
