# Value Visualizer Test Examples

Canonical testing map: `dev_docs/testing/TESTING_STRATEGY.md`. This file is a
feature-specific example list and candidate source for future automated coverage.

This document shows how to use the new Value Visualizer system in Field4.

## Basic Usage

```kotlin
// Visualize any value with the viz() function
viz("debug", 42)                    // Numbers
viz("debug", "Hello world")         // Strings
viz("debug", vec(1, 2, 3))          // JOML vectors
viz("debug", listOf(1, 2, 3, 4, 5)) // Collections
```

## Examples

### Primitives
```kotlin
viz("numbers", 42)
viz("numbers", 3.14159)
viz("numbers", 1.23e-10)
viz("booleans", true)
viz("strings", "Hello, Field4!")
```

### JOML Types
```kotlin
// Vectors
viz("vec2", vec(1.0, 2.0))
viz("vec3", vec(1.0, 2.0, 3.0))
viz("vec4", vec(1.0, 2.0, 3.0, 1.0))

// Quaternions
viz("quat", quat(45.0, vec(0, 1, 0)))

// Matrices
viz("mat4", mat4())
```

### Collections
```kotlin
// Lists with summary stats
val numbers = (1..100).map { it * 0.1 }
viz("list", numbers)  // Shows first 5, then stats (mean, stddev, range)

// Maps
viz("map", mapOf("x" to 1, "y" to 2, "z" to 3))

// Large lists truncate nicely
viz("large", (1..1000).toList())
```

### Custom Printers

Register custom printers for your types:

```kotlin
data class Point3D(val x: Double, val y: Double, val z: Double)

registerValuePrinter<Point3D> { point ->
    "<span style='color: #f80;'>Point3D(${point.x}, ${point.y}, ${point.z})</span>"
}

viz("custom", Point3D(1.0, 2.0, 3.0))
```

### ToHTML Interface

Implement ToHTML for full control:

```kotlin
data class MyComplexObject(val data: List<Int>) : ToHTML {
    override fun toHTML(): String {
        return """
            <div style='color: #8cf; font-weight: bold;'>
                MyComplexObject with ${data.size} elements
            </div>
        """.trimIndent()
    }
}

viz("object", MyComplexObject(listOf(1, 2, 3)))
```

## Styling

The visualizer uses monochrome colors:
- Numbers: cyan (#8cf)
- Strings: green (#8f8) 
- Booleans: red (#f88)
- JOML vectors: orange (#fc8)
- JOML quaternions: pink (#f8c)
- JOML matrices: lime (#cf8)
- Structural elements: grays
- Byte arrays: purple (#c8f)

All output is compact and designed to work in disclosure areas.
