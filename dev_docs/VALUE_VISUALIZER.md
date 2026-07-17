# Value Visualizer Guide

Canonical prose reference for `ValueVisualizer` / `Box.viz()`
(`src/jvmMain/kotlin/afield/visualization/ValueVisualizer.kt`).
Runnable end-to-end examples: `dev_docs/viz_demo.kt`.

## Overview

The Value Visualizer is a compact visualizer for common Kotlin and Field linalg
types. It renders values as small HTML fragments and integrates with Field4's
`display()` function to show them in disclosure areas. Output uses a fixed,
curated per-type color palette (see [Styling](#styling)) — not a single
monochrome scheme.

## Quick Start

```kotlin
// Use viz() to display any value
viz("debug", vec(1, 2, 3))
viz("numbers", listOf(1, 2, 3, 4, 5))
viz("data", mapOf("x" to 1.0, "y" to 2.0))
```

## Type Support

The visualizer automatically handles:

- **Primitives**: numbers (smart formatting), booleans, strings, chars
- **Vectors**: `Vector2f/3f/4f` and `Vector2d/3d/4d` (Field's generated linalg
  types in `afield.stdlib.linalg`; double-precision vectors are down-converted
  to float for display) → `vec2(x, y)` / `vec3(...)` / `vec4(...)`
- **Quaternions**: `Quaternionf` (`Quaternionfc`) only → `quat(x, y, z, w)`.
  There is no double-precision quaternion type in this codebase.
- **Matrices**: `Matrix2f/3f/4f` (single precision) → `mat4 [det=…, t=(…)]`.
  Double-precision matrix branches are commented out in source and fall through
  to the generic formatter.
- **Collections**: lists, sets, maps, arrays (including primitive arrays)

These types are API-shaped like JOML but are backed by Field's own generated
linalg package (`afield.stdlib.linalg`), not the `org.joml` library, following
the JOML → generated-linalg migration.

### Rendering quick reference

```kotlin
viz("num", 42)                          // → 42
viz("float", 3.14159)                   // → 3.142
viz("bool", true)                       // → true
viz("str", "hello")                     // → hello        (unquoted)
viz("v3", vec(1, 2, 3))                 // → vec3(1, 2, 3)
viz("quat", quat(45.0, vec(0, 1, 0)))   // → quat(x, y, z, w)
viz("mat", mat4())                      // → mat4 [det=…, t=(…)]
viz("list", listOf(1, 2, 3))            // → [1, 2, 3] (3)
viz("map", mapOf("x" to 1, "y" to 2))   // → {x -> 1, y -> 2} (2)
viz("set", setOf("a", "b", "c"))        // → {a, b, c} (3)
```

Note that map entries use an ASCII `->` separator, and strings render unquoted.

## Smart Truncation

Lists and sets are shown in full up to `LIST_INLINE_ELISION_THRESHOLD` (30)
elements. Above that threshold, only the first `MAX_LIST_PREVIEW` (20) elements
are previewed, followed by a `… +N more items` expander:

```kotlin
val bigList = (1..100).toList()
viz("large", bigList)
// Shows: [1, 2, …, 20 … +80 more items] (100)
```

Maps truncate separately, at `MAX_MAP_ENTRIES` (5) entries. Strings truncate
after `MAX_STRING_LENGTH` (100) characters. Nesting is limited to `MAX_DEPTH`
(3) levels.

## Summary Statistics

All-numeric collections get an automatic stats summary appended after the
preview (`[μ=…, σ=…, range=…]`). The standard deviation is a population stdDev,
and numeric literals are formatted to three decimals (trailing zeros trimmed):

```kotlin
val data = (1..100).map { it * 0.1 }
viz("stats", data)
// [0.1, 0.2, …, 2.0 … +80 more items] [μ=5.05, σ=2.887, range=0.1…10] (100)
```

## Inline Sparklines for Numeric Collections

`DoubleArray`, `FloatArray`, `IntArray`, and predominantly-numeric `List<*>`
values get a small inline SVG sparkline appended after their preview + stats,
giving the *shape* of the series at a glance:

```kotlin
viz("ramp", DoubleArray(64) { it.toDouble() })          // rising line
viz("sine", DoubleArray(128) { sin(it / 20.0) })        // sinusoid + dashed 0 baseline
viz("mixed", listOf(1, 2.5, 3, 4L, 5.0, 6.25, 7, 8.0))  // list sparkline
```

The renderer mirrors the safe recipe used for the Fn graph-card plot
(`renderFnPlot`):

- **NaN / ∞ become gaps** — the pen lifts across non-finite samples rather than
  drawing a spurious segment to zero.
- **Flat/degenerate series are padded** so a constant array still draws a line.
- **A faint dashed zero baseline** is drawn only when the real data straddles
  zero (positive-only series get no baseline).
- **Huge series are downsampled** to `SPARKLINE_MAX_SAMPLES` (240) points; the
  original count is noted in the element's `title` tooltip.
- **Graceful degradation**: fewer than `SPARKLINE_MIN_POINTS` (3) finite values
  → no sparkline. So empty, single-element, and all-NaN arrays simply omit it.
- For `List<*>`, non-numeric elements become NaN gaps at their real index, and a
  sparkline is drawn only when the numeric elements are the majority (≥60%).

The whole render is wrapped in `runCatching`, so a misbehaving element costs only
the sparkline, never the value display.

## Inline Geometric Thumbnails (linalg vectors & matrices)

Beneath the terse text for the Field linalg types, `ValueVisualizer` appends a
small, **guarded** inline SVG thumbnail — the same discipline as the Fn plot
(`renderFnPlot` / `formatFn`) and the FLine thumbnail (`renderFLineThumbnail`):
the text head is preserved verbatim and the thumbnail is a bonus wrapped in
`runCatching`, so a misbehaving value costs only the thumbnail, never the value
display.

- **`Vector2fc` / `Vector4fc`** → an auto-scaled arrow from a centered origin
  over faint axes (vec4 draws the (x, y) arrow; z/w stay in the text).
  `renderVec2Thumbnail`.
- **`Vector3fc`** → an isometric arrow with a fixed-pixel x/y/z reference frame
  (the frame is decoupled from the data scale so it can never overflow).
  `renderVec3Thumbnail`.
- **`Matrix2/3/4fc`** → a compact colored cell grid, a diverging heatmap around
  zero (neutral `#222` → `#6cf` for positive, → `#f83` for negative, by
  |v| / maxAbs), with per-cell value labels for 2×2 and 3×3 (4×4 is colors-only).
  Matrix storage is column-major (accessor `m<col><row>()`); cells are laid out
  in row-major math order. `renderMatrixThumbnail`.

Degenerate values are safe: a non-finite (NaN/∞) component skips the thumbnail
(the plain text is preserved), a zero vector draws axes + an origin dot, and an
all-zero matrix draws neutral cells. Sizes: `VEC_THUMBNAIL_SIZE` (84px square)
and `MATRIX_CELL_SIZE` (24px per cell).

Verified by `src/jvmTest/kotlin/afield/LinalgThumbnailVisualizerTest.kt` (drives
the real `ValueVisualizer.visualize`). A loadable demo is
`dev_docs/agents/transcripts/linalg_thumbnails_demo.yaml` — replay it into a
fresh Field4 (agent backdoor / `field_run_transcript`) and look at each box's
output.

## Compact Formatting

- Numbers use scientific notation for very large/small values
- Strings truncate after 100 characters
- Nested structures indent automatically

## Styling

The visualizer applies a distinct hue per type:

```
Numbers:      #8cf (cyan)
Strings:      #8f8 (green)
Booleans:     #f88 (red)
Vectors:      #fc8 (orange)
Quaternions:  #f8c (pink)
Matrices:     #cf8 (lime)
Byte Arrays:  #c8f (purple)
Structural:   #aaa, #888, #666 (grays)
```

## Custom Printers

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

## ToHTML Interface

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

## Integration

Field4 has several complementary visualization functions:

| Function    | Use case                | Output type              |
|-------------|-------------------------|--------------------------|
| `viz()`     | Compact inline display  | HTML in disclosure       |
| `tree()`    | Deep object inspection  | Tree view (iframe)       |
| `column()`  | Property browsing       | Column browser (iframe)  |
| `println()` | Console output          | Text in output area      |
| `display()` | Custom HTML             | Raw HTML in disclosure   |

`viz()` complements the others with compact inline display, fast value preview,
and statistical summaries for numeric data.

## Implementation Details

### Core function

```kotlin
fun Box.viz(channel: String = "viz", value: Any?) {
    display(channel, ValueVisualizer.visualize(value))
}
```

The `viz()` extension wraps the visualizer and sends HTML to the disclosure
area. (`Any?.h` exposes the same visualized HTML as a property.)

### Visualization pipeline

1. Check for the `ToHTML` interface
2. Look up a custom printer in `ValuePrinterRegistry`
3. Match against the built-in type handlers
4. Fall back to `toString()`

### Performance

- Truncation limits prevent DOM bloat
- Statistics are calculated on-demand only for numeric collections
- HTML is pre-escaped for safety
- A depth limit (`MAX_DEPTH` = 3) and circular-reference detection prevent
  infinite loops

### Circular reference handling

```kotlin
class Node(val name: String) {
    var next: Node? = null
}

val node1 = Node("A")
val node2 = Node("B")
node1.next = node2
node2.next = node1  // Circular reference

viz("nodes", node1)  // Shows [circular reference] when node1 is revisited
```

Visited objects are tracked in an identity set backed by
`java.util.IdentityHashMap` (`newIdentitySet()`), so the same instance is
detected on a second visit. Primitives and linalg value types are not tracked —
they cannot form circular references.

## Examples

See `dev_docs/viz_demo.kt` for comprehensive runnable examples of all features.
