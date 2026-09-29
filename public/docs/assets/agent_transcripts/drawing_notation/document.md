# Field Document: drawing_notation

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. fontana mix

<!-- field-box id="dn_fontana" index="0" -->

### Narrative

From `code`

After John Cage's Fontana Mix (1958): six curves, a grid, a point, and a straight line.
Cage's performer laid a line from a point inside the grid to one outside it and measured
where it crossed the curves. Here the line is yours: draw one across the sheet (Alt+D, then L)
and this box reads it. Run the box first; it draws the sheet.

```edn [field-box id="dn_fontana" role="properties"]
{"id" "dn_fontana" "index" 0 "name" "fontana mix" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "fontana mix" "_tombstone" false "frame" #afield/rect [2160.0 120.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dn_fontana_code_.kt" "lines" 23 "characters" 1313 "sha256" "8b629c1c51d5f55b2263f1fe0190848841780efa5373bcb55f9cdee42075c274"}]}
```

```kotlin [field-property box="dn_fontana" name="code" path="dn_fontana_code_.kt"]
// After John Cage's Fontana Mix (1958): six curves, a grid, a point, and a straight line.
// Cage's performer laid a line from a point inside the grid to one outside it and measured
// where it crossed the curves. Here the line is yours: draw one across the sheet (Alt+D, then L)
// and this box reads it. Run the box first; it draws the sheet.
val colors = listOf("#e03131", "#1971c2", "#2f9e44", "#f08c00", "#9c36b5", "#0c8599")
notation("sheet") {
    frame("fontana mix", 1200, 100, 900, 520)
    for (i in 0 until 6) {
        val curve = (0..48).map { k -> vec(1230 + k * 17.5, 170 + i * 75 + 55 * sin(k / 5.0 + i * 1.9)) }
        stroke(curve, name = "curve ${i + 1}", stroke = colors[i])
    }
    rect(1530, 250, 240, 200, name = "grid", stroke = "#868e96")
    ellipse(1605, 320, 12, 12, name = "point", fill = "#868e96")
}

fun read(line: Mark) {
    val crossings = notation.intersect(line.start!!, line.end!!).filter { it.mark.name?.startsWith("curve") == true }
    println("${crossings.size} crossings along the line:")
    crossings.forEach { println("  ${it.mark.name}  %.2f of the way along".format(it.value)) }
}

notation.lines.lastOrNull { it.handDrawn }?.let(::read)
notation.onChange("fontana") { edit -> (edit.added + edit.updated).lastOrNull { it.kind == MarkType.LINE }?.let(::read) }
```

## 2. projection 1

<!-- field-box id="dn_projection" index="1" -->

### Narrative

From `code`

After Morton Feldman's Projection 1 (1950): time runs left to right; the grid's three rows
are high, middle and low. Draw boxes (Alt+D, then R) in the grid, and this box reads them
as events: when each starts, how long it lasts, which register it sits in.

```edn [field-box id="dn_projection" role="properties"]
{"id" "dn_projection" "index" 1 "name" "projection 1" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "projection 1" "_tombstone" false "frame" #afield/rect [1120.0 700.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dn_projection_code_.kt" "lines" 16 "characters" 929 "sha256" "55ec64b308105d403da98dd393148cdee3330539c2190f020568dc21d3c513e1"}]}
```

```kotlin [field-property box="dn_projection" name="code" path="dn_projection_code_.kt"]
// After Morton Feldman's Projection 1 (1950): time runs left to right; the grid's three rows
// are high, middle and low. Draw boxes (Alt+D, then R) in the grid, and this box reads them
// as events: when each starts, how long it lasts, which register it sits in.
notation("grid") {
    stroke = "#868e96"
    frame("projection", 100, 700, 960, 270)
    for (row in 1..2) line(listOf(vec(100, 700 + row * 90), vec(1060, 700 + row * 90)))
    rect(160, 715, 120, 60, fill = "#1971c2")                     // one to start with
}
val score = notation.space("projection", x = 0.0..16.0, y = 3.0..0.0)   // 16 beats; 3 rows
fun register(v: Double) = when { v >= 2 -> "high"; v >= 1 -> "middle"; else -> "low" }
fun read() = score.events.filter { it.mark.kind == MarkType.RECTANGLE }.forEach {
    println("beat %.1f for %.1f, %s".format(it.start, it.duration, register(it.value)))
}
read()
notation.onChange("projection") { read() }
```

## 3. draw

<!-- field-box id="dn_draw" index="2" -->

### Narrative

From `code`

Drawing from code. Everything this block makes belongs to this box: run it again and
the marks are replaced, not duplicated. Marks drawn by hand are never touched.

```edn [field-box id="dn_draw" role="properties"]
{"id" "dn_draw" "index" 2 "name" "draw" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "draw" "_tombstone" false "frame" #afield/rect [200.0 200.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dn_draw_code_.kt" "lines" 9 "characters" 489 "sha256" "0b9d4bb35db555055728ce0ea83d7b9a2a260d25db241485f8621954900d5b37"}]}
```

```kotlin [field-property box="dn_draw" name="code" path="dn_draw_code_.kt"]
// Drawing from code. Everything this block makes belongs to this box: run it again and
// the marks are replaced, not duplicated. Marks drawn by hand are never touched.
val listener = inside.boxes.values.first { it.name == "listen" }
notation {
    stroke = "#1971c2"
    text("drawn by ${name}", frame.x, frame.y - 34)
    ellipse(frame.x - 20, frame.y - 20, frame.w + 40, frame.h + 40)
    arrow(from = thisBox, to = listener, label = "tells")   // drag either box: the arrow follows
}
```

## 4. listen

<!-- field-box id="dn_listen" index="3" -->

### Narrative

From `code`

Listening to the drawing: run this, then draw a stroke anywhere (Alt+D, then P).
The handler runs when the stroke is finished; stop the box to remove it.

```edn [field-box id="dn_listen" role="properties"]
{"id" "dn_listen" "index" 3 "name" "listen" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "listen" "_tombstone" false "frame" #afield/rect [620.0 320.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dn_listen_code_.kt" "lines" 5 "characters" 293 "sha256" "347c7e330799ba84ddb2722a4909ddcebab91eff81156acf3d01fb22827d8de7"}]}
```

```kotlin [field-property box="dn_listen" name="code" path="dn_listen_code_.kt"]
// Listening to the drawing: run this, then draw a stroke anywhere (Alt+D, then P).
// The handler runs when the stroke is finished; stop the box to remove it.
notation.onStroke { s ->
    println("a stroke of ${s.points.size} points, ${s.length.toInt()} long, from ${s.start} to ${s.end}")
}
```

## 5. crescendo

<!-- field-box id="dn_crescendo" index="4" -->

### Narrative

From `code`

A crescendo read by cutting it. A vertical line at a beat crosses the hairpin's two sides,
and the gap between the crossings is how loud it is: nothing at the point, most at the mouth.
Run this to draw one; then draw your own in the frame with the pen (Alt+D, then P) and this
box reads everything in the frame again. Two hairpins, or a diminuendo, read the same way.

```edn [field-box id="dn_crescendo" role="properties"]
{"id" "dn_crescendo" "index" 4 "name" "crescendo" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "crescendo" "_tombstone" false "frame" #afield/rect [960.0 1060.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dn_crescendo_code_.kt" "lines" 23 "characters" 1350 "sha256" "1672b8823bd5f3a3d7037b72ebabd6401ea8b488e761edbca7bf7569444f7376"}]}
```

```kotlin [field-property box="dn_crescendo" name="code" path="dn_crescendo_code_.kt"]
// A crescendo read by cutting it. A vertical line at a beat crosses the hairpin's two sides,
// and the gap between the crossings is how loud it is: nothing at the point, most at the mouth.
// Run this to draw one; then draw your own in the frame with the pen (Alt+D, then P) and this
// box reads everything in the frame again. Two hairpins, or a diminuendo, read the same way.
notation("hairpin") {
    frame("dynamics", 100, 1060, 800, 200)
    val hand = { i: Int -> 2.5 * sin(i * 2.3) + 1.5 * sin(i * 0.7) }     // a hand is not a ruler
    val hairpin = (0..60).map { i ->
        val k = if (i <= 30) i else 60 - i                                // out along the top, back along the bottom
        val side = if (i <= 30) -1 else 1
        vec(860 - k * 23.0, 1160 + side * (75 - k * 2.5) + hand(i))
    }
    stroke(hairpin, name = "hairpin", stroke = "#e03131")
}
val dynamics = notation.space("dynamics", x = 0.0..8.0, y = 0.0..1.0)
val markings = listOf("niente", "pp", "p", "mp", "mf", "f", "ff")
fun read() = (0..7).forEach { beat ->
    val ys = dynamics.vertical(beat.toDouble()).filter { !it.isSpan }.map { it.value }
    val gap = if (ys.size >= 2) ys.max() - ys.min() else 0.0
    println("beat $beat: %.2f %s".format(gap, markings[(gap / 0.75 * 6).roundToInt().coerceIn(0, 6)]))
}
read()
notation.onStroke("crescendo") { read() }
```

## 6. read

<!-- field-box id="dn_read" index="5" -->

### Narrative

From `code`

Reading the drawing: every mark, hand-drawn or not, with what it is over and what it joins.

```edn [field-box id="dn_read" role="properties"]
{"id" "dn_read" "index" 5 "name" "read" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "read" "_tombstone" false "frame" #afield/rect [200.0 460.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dn_read_code_.kt" "lines" 6 "characters" 359 "sha256" "5615fa808ed71203212dc487e38764b81954f359b46993d4db640b7ce06d979f"}]}
```

```kotlin [field-property box="dn_read" name="code" path="dn_read_code_.kt"]
// Reading the drawing: every mark, hand-drawn or not, with what it is over and what it joins.
for (m in notation.marks) {
    val where = m.box?.let { " over '${it.name}'" } ?: ""
    val joins = if (m.kind == MarkType.ARROW) " from '${m.fromBox?.name}' to '${m.toBox?.name}'" else ""
    println("${m.kind.name.lowercase()} ${m.label ?: ""}$where$joins")
}
```

