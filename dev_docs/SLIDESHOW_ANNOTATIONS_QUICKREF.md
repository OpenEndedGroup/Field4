# Slideshow Deep Functionality - Quick Reference

## Quick Start

### Basic Annotation

```kotlin
step("Point to Box") {
    note("<p>See this box?</p>")
    selectBox(targetBox)
    annotate("pointer", targetBox)
}
```

### Animated Text

```kotlin
step("Animated Intro") {
    note("<h2>Hello!</h2><p>Watch this animate.</p>", animated = true)
    selectBox(myBox)
}
```

### Point to Code Line

```kotlin
step("Code Location") {
    note("<p>Line 42 is important!</p>")
    selectBox(codeBox, line = 42, highlight = 42)
    annotateToLine("line-ptr", codeBox, line = 42)
}
```

### Multiple Pointers

```kotlin
step("Related Items") {
    note("<p>These work together.</p>")
    annotate("box1", box1, color = "rgba(100, 200, 255, 0.7)")
    annotate("box2", box2, color = "rgba(255, 100, 200, 0.7)")
}
```

## Function Signatures

### annotate()

```kotlin
fun annotate(
    id: String,
    targetBox: Box,
    color: String = "rgba(128, 255, 164, 0.7)",
    strokeWidth: Double = 2.0,
    dashed: Boolean = false
)
```

### annotateToLine()

```kotlin
fun annotateToLine(
    id: String,
    targetBox: Box,
    property: String = "code",
    line: Int,
    color: String = "rgba(128, 255, 164, 0.7)",
    strokeWidth: Double = 2.0,
    dashed: Boolean = false
)
```

### note() - Updated

```kotlin
fun note(
    html: String,
    persistent: Boolean = false,
    anchorBox: Box? = null,
    animated: Boolean = false  // NEW!
)
```

## Common Patterns

### Progressive Tutorial

```kotlin
slideShow {
    step("Step 1") {
        note("<p>First, understand this.</p>", animated = true)
        annotate("intro", introBox)
    }
    
    step("Step 2") {
        note("<p>Now look at this code.</p>")
        selectBox(codeBox, line = 10, highlight = 10)
        annotateToLine("important", codeBox, line = 10)
    }
    
    step("Step 3") {
        note("<p>These work together.</p>")
        annotate("piece1", box1)
        annotate("piece2", box2)
    }
}
```

### Debug Walk-through

```kotlin
slideShow {
    step("Entry Point") {
        note("<p>Execution starts here.</p>")
        selectBox(mainBox, line = 1)
        annotateToLine("start", mainBox, line = 1, 
            color = "rgba(100, 255, 100, 0.8)")
    }
    
    step("Key Logic") {
        note("<p>This is where the magic happens.</p>", animated = true)
        selectBox(mainBox, line = 25, highlight = 25)
        annotateToLine("logic", mainBox, line = 25,
            color = "rgba(255, 180, 100, 0.8)")
    }
    
    step("Output") {
        note("<p>Results appear here.</p>")
        annotate("output", outputBox,
            color = "rgba(100, 200, 255, 0.8)")
    }
}
```

### Relationship Diagram

```kotlin
step("System Architecture") {
    note("""
        <h3>Component Relationships</h3>
        <ul>
          <li style="color: #64C8FF;">Input (Blue)</li>
          <li style="color: #64FF64;">Processing (Green)</li>
          <li style="color: #FFB464;">Output (Orange)</li>
        </ul>
    """.trimIndent(), persistent = true)
    
    annotate("input", inputBox, 
        color = "rgba(100, 200, 255, 0.8)", strokeWidth = 3.0)
    annotate("processing", processBox,
        color = "rgba(100, 255, 100, 0.8)", strokeWidth = 3.0)
    annotate("output", outputBox,
        color = "rgba(255, 180, 100, 0.8)", strokeWidth = 3.0)
}
```

## Color Palette

```kotlin
// Standard palette for consistency
val successColor = "rgba(128, 255, 164, 0.8)"  // Green - positive/success
val warningColor = "rgba(255, 180, 100, 0.8)"  // Orange - warning/attention
val infoColor = "rgba(100, 200, 255, 0.8)"     // Blue - information
val errorColor = "rgba(255, 100, 100, 0.8)"    // Red - error/critical
val neutralColor = "rgba(200, 200, 200, 0.7)"  // Gray - neutral

annotate("id", box, color = successColor)
```

## Animation Timing

- **Base speed**: 15ms per character
- **Period/!/? pause**: +300ms
- **Comma/;/: pause**: +150ms
- **Good for**: 50-200 character messages
- **Too slow for**: Long documentation

## Tips

### Do's
✅ Use annotations to guide attention  
✅ Animate short, important messages  
✅ Use consistent colors for related items  
✅ Keep annotation IDs unique  
✅ Combine with selectBox for context  

### Don'ts
❌ Don't animate every step (tedious)  
❌ Don't use too many annotations at once (confusing)  
❌ Don't mix random colors (inconsistent)  
❌ Don't point to hidden boxes (won't render)  
❌ Don't use very long animated text (slow)  

## Testing Template

```kotlin
fun Box.testAnnotations() {
    val box1 = this.children[0]
    val box2 = this.children[1]
    
    slideShow {
        step("Test 1: Single Annotation") {
            note("<p>Testing single line.</p>")
            annotate("test1", box1)
        }
        
        step("Test 2: Animation") {
            note("<p>Testing animation. Look carefully!</p>", animated = true)
        }
        
        step("Test 3: Line Annotation") {
            note("<p>Testing line pointer.</p>")
            annotateToLine("test3", box1, line = 1)
        }
        
        step("Test 4: Multiple") {
            note("<p>Testing multiple lines.</p>")
            annotate("test4a", box1, color = "rgba(255, 100, 100, 0.7)")
            annotate("test4b", box2, color = "rgba(100, 255, 100, 0.7)")
        }
        
        step("Test 5: Dashed") {
            note("<p>Testing dashed line.</p>")
            annotate("test5", box1, dashed = true)
        }
    }.apply {
        GlobalScope.launch { start() }
    }
}
```

## Troubleshooting

**Annotations not showing?**
- Check target box exists and is visible
- Verify box ID is correct
- Look for JavaScript console errors

**Animation too slow/fast?**
- Base speed is 15ms per character
- Modify in Main.kt if needed
- Consider shorter text

**Lines pointing wrong place?**
- System accounts for scrolling automatically
- Lines redraw when overlay moves
- Target box must be in viewport

**Multiple annotations overlapping?**
- Use different colors
- Consider multiple steps
- Adjust strokeWidth

## Full Example

See `advancedSlideShowExample()` in `slideshow_example.kt` for a complete working example.

## Documentation

- [Full Documentation](SLIDESHOW_ANNOTATIONS.md)
- [Slideshow Quickstart](SLIDESHOW_QUICKSTART.md)
- [Slideshow Features](SLIDESHOW_FEATURE.md)
- [Architecture](SLIDESHOW_ARCHITECTURE.md)

## Key Files

- `SlideShow.kt` - DSL and server logic
- `Main.kt` - Client rendering
- `rpc.kt` - Message types
- `slideshow_example.kt` - Examples
