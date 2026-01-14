# Value Visualizer Implementation Guide

## Overview

The Value Visualizer is a compact, monochrome visualization system for common Kotlin and JOML types. It integrates with Field4's existing `display()` function to show values in disclosure areas.

## Quick Start

```kotlin
// Use viz() to display any value
viz("debug", vec(1, 2, 3))
viz("numbers", listOf(1, 2, 3, 4, 5))
viz("data", mapOf("x" to 1.0, "y" to 2.0))
```

## Features

### 1. Built-in Type Support

The visualizer automatically handles:

- **Primitives**: Numbers (with smart formatting), booleans, strings, chars
- **JOML Vectors**: Vector2f/3f/4f, Vector2d/3d/4d
- **JOML Quaternions**: Quaternionf, Quaterniond
- **JOML Matrices**: Matrix2f/3f/4f and their double counterparts
- **Collections**: Lists, sets, maps, arrays (including primitive arrays)

### 2. Smart Truncation

Large collections are automatically truncated with preview:
```kotlin
val bigList = (1..100).toList()
viz("large", bigList)
// Shows: [1, 2, 3, 4, 5 … +95 more] (100)
```

### 3. Summary Statistics

Numeric collections get automatic statistics:
```kotlin
val data = (1..100).map { it * 0.1 }
viz("stats", data)
// Shows preview + [μ=5.05, σ=2.9, range=0.1…10]
```

### 4. Compact Formatting

- Numbers use scientific notation for very large/small values
- Strings truncate after 100 characters
- Nested structures indent automatically
- All output uses monochrome color scheme

## Styling

The visualizer uses these colors:

```
Numbers:           #8cf (cyan)
Strings:           #8f8 (green)
Booleans:          #f88 (red)
JOML Vectors:      #fc8 (orange)
JOML Quaternions:  #f8c (pink)
JOML Matrices:     #cf8 (lime)
Byte Arrays:       #c8f (purple)
Structural:        #aaa, #888, #666 (grays)
```

## Advanced Features

### Custom Printers

Register custom formatters for your types:

```kotlin
data class Point3D(val x: Double, val y: Double, val z: Double)

// Register once
registerValuePrinter<Point3D> { point ->
    "<span style='color: #f80;'>Point3D(${point.x}, ${point.y}, ${point.z})</span>"
}

// Use anywhere
viz("point", Point3D(1.0, 2.0, 3.0))
```

### ToHTML Interface

Implement `ToHTML` for complete control:

```kotlin
data class ComplexData(val name: String, val values: List<Double>) : ToHTML {
    override fun toHTML(): String {
        return """
            <div style='border-left: 2px solid #8cf; padding-left: 10px;'>
                <div style='color: #8cf; font-weight: bold;'>${name}</div>
                <div style='color: #888; font-size: 11px;'>
                    ${values.size} values: ${values.take(3).joinToString(", ")}...
                </div>
            </div>
        """.trimIndent()
    }
}

viz("complex", ComplexData("Dataset A", (1..50).map { it * 1.5 }))
```

## Integration with Existing Visualizers

Field4 already has several visualization functions:

- `tree(channel, value)` - Tree view for complex objects
- `column(channel, value)` - Column browser for properties
- `logs(channel, filter)` - Log stream display

The `viz()` function complements these by providing:
- Compact inline display
- Fast preview of values
- Statistical summaries for numeric data

## Implementation Details

### Core Function

```kotlin
fun Box.viz(channel: String = "viz", value: Any?) {
    display(channel, ValueVisualizer.visualize(value))
}
```

The `viz()` extension function wraps the visualizer and sends HTML to the disclosure area.

### Visualization Pipeline

1. Check for `ToHTML` interface implementation
2. Look up custom printer in registry
3. Match against built-in type handlers
4. Fall back to `toString()`

### Performance

- Truncation limits prevent DOM bloat
- Statistics calculated on-demand only for numeric collections
- HTML is pre-escaped for safety
- Maximum depth limit (3 levels) prevents infinite loops
- **Circular reference detection** using identity hash tracking prevents infinite loops

### Circular Reference Handling

The visualizer automatically detects and prevents infinite loops from circular references:

```kotlin
class Node(val name: String) {
    var next: Node? = null
}

val node1 = Node("A")
val node2 = Node("B")
node1.next = node2
node2.next = node1  // Circular reference

viz("nodes", node1)  // Shows [circular reference] when it encounters node1 again
```

Objects are tracked using `System.identityHashCode()` to detect when the same object instance is visited multiple times. Primitives and value types (like JOML vectors) are not tracked as they cannot create circular references.

## Examples

See `viz_demo.kt` for comprehensive examples of all features.

## Future Enhancements

Potential future additions:

1. Interactive disclosure of truncated collections
2. Plotting numeric arrays
3. Syntax highlighting for code strings
4. Image preview for byte arrays
5. Thread `_print()` functions through visualizer
