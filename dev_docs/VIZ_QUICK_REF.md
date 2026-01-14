# Value Visualizer Quick Reference

## Basic Usage

```kotlin
// Display any value with viz()
viz("debug", myValue)
viz("output", vec(1, 2, 3))
```

## Type Support

### Primitives
```kotlin
viz("num", 42)              // → 42
viz("float", 3.14159)       // → 3.142
viz("bool", true)           // → true
viz("str", "hello")         // → "hello"
```

### JOML Types
```kotlin
viz("v2", vec(1, 2))                    // → vec2(1, 2)
viz("v3", vec(1, 2, 3))                 // → vec3(1, 2, 3)
viz("v4", vec(1, 2, 3, 1))              // → vec4(1, 2, 3, 1)
viz("quat", quat(45.0, vec(0, 1, 0)))   // → quat(x, y, z, w)
viz("mat", mat4())                      // → mat4 [det=..., t=(...)]
```

### Collections
```kotlin
viz("list", listOf(1, 2, 3))           // → [1, 2, 3] (3)
viz("map", mapOf("x" to 1, "y" to 2))  // → {x → 1, y → 2} (2)
viz("set", setOf("a", "b", "c"))       // → {a, b, c} (3)
```

### Large Collections
```kotlin
val bigList = (1..100).map { it * 0.1 }
viz("data", bigList)
// → [0.1, 0.2, 0.3, 0.4, 0.5 … +95 more] [μ=5.05, σ=2.9, range=0.1…10] (100)
```

## Custom Types

### Register a Printer
```kotlin
data class MyType(val x: Int, val y: Int)

registerValuePrinter<MyType> { 
    "<span style='color: #fc8;'>MyType(${it.x}, ${it.y})</span>"
}

viz("custom", MyType(1, 2))
```

### Implement ToHTML
```kotlin
data class MyData(val values: List<Int>) : ToHTML {
    override fun toHTML() = 
        "<div style='color: #8cf;'>${values.size} items</div>"
}

viz("obj", MyData(listOf(1, 2, 3)))
```

## Comparison with Other Visualizers

| Function | Use Case | Output Type |
|----------|----------|-------------|
| `viz()` | Compact inline display | HTML in disclosure |
| `tree()` | Deep object inspection | Tree view (iframe) |
| `column()` | Property browsing | Column browser (iframe) |
| `println()` | Console output | Text in output area |
| `display()` | Custom HTML | Raw HTML in disclosure |

## Color Scheme (Monochrome)

- Numbers: cyan (#8cf)
- Strings: green (#8f8)
- Booleans: red (#f88)
- Vectors: orange (#fc8)
- Quaternions: pink (#f8c)
- Matrices: lime (#cf8)
- Structure: grays (#aaa, #888, #666)

## Performance Notes

- Collections truncate at 5 items preview
- Strings truncate at 100 characters
- Recursion limited to depth 3
- Statistics computed only for numeric collections
- All HTML is escaped for safety

## See Also

- `VALUE_VISUALIZER.md` - Full documentation
- `viz_demo.kt` - Comprehensive examples
- `outputs.kt` - Display infrastructure
