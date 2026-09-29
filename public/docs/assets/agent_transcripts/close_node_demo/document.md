# Field Document: close_node_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. the shipped primitive that has an interior Close in it

<!-- field-box id="close_node_arrow" index="0" -->

### Narrative

From `code`

Not a hand-built fixture: this is what afield.stdlib.arrow returns when you ask for a
head at both ends. Its appendHead ends each arrowhead with close(), so the node list is
MoveTo LineTo | MoveTo LineTo LineTo Close | MoveTo LineTo LineTo Close
and the first Close has a whole arrowhead after it.

```edn [field-box id="close_node_arrow" role="properties"]
{"id" "close_node_arrow" "index" 0 "name" "the shipped primitive that has an interior Close in it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the shipped primitive that has an interior Close in it" "_tombstone" false "frame" #afield/rect [80.0 700.0 470.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "close_node_arrow_code_.kt" "lines" 56 "characters" 2669 "sha256" "1be5a18c222f70b762b62f929af6315afd6c19a95f38155ddfcb174eeb2dbbb6"}]}
```

```kotlin [field-property box="close_node_arrow" name="code" path="close_node_arrow_code_.kt"]
// Not a hand-built fixture: this is what afield.stdlib.arrow returns when you ask for a
// head at both ends. Its appendHead ends each arrowhead with close(), so the node list is
//   MoveTo LineTo | MoveTo LineTo LineTo Close | MoveTo LineTo LineTo Close
// and the first Close has a whole arrowhead after it.
val a = arrow(vec(0.0, 0.0), vec(300.0, 120.0), startHead = true, endHead = true)

val closes = a.nodes.withIndex().filter { it.value is FLine.Close }.map { it.index }
val interior = closes.filter { it < a.nodes.size - 1 }

val ghost = { l: FLine -> l.copy { thickness = 9.0; color = vec(0.45, 0.5, 0.58, 0.55) } }
val cyan = { l: FLine -> l.copy { thickness = 3.0; color = vec(0.35, 0.85, 1.0, 0.95) } }

// Everything here threw IndexOutOfBoundsException on this exact line before 2026-08-01.
val reversed = a.byReversing()
val subdivided = a.bySubdividing(1)
val dashes = a.byDashing(14f, 8f).map { cyan(it).copy { thickness = 3.0 } }
val hull = a.byConvexHull2D()

showPlaneGrid(
    "arrow(startHead, endHead)\n${a.nodes.size} nodes, Closes at $closes" to listOf(cyan(a)),
    "byReversing\n${reversed.nodes.size} nodes, ${reversed.nodes.count { it is FLine.Close }} Closes kept"
        to listOf(ghost(a), cyan(reversed)),
    "bySubdividing(1)\n${subdivided.nodes.count { it is FLine.Close }} Closes kept"
        to listOf(ghost(a), cyan(subdivided)),
    "byDashing(14, 8)\n${dashes.size} dashes" to (listOf(ghost(a)) + dashes),
    columns = 2,
    cellWidth = 420,
    cellHeight = 230,
)

// The sweep, run live: every one of these used to throw on this arrow.
val ops = listOf<Pair<String, (FLine) -> Any?>>(
    "byReversing" to { l -> l.byReversing() },
    "bySubdividing" to { l -> l.bySubdividing(1) },
    "bySimplifying" to { l -> l.bySimplifying(2f) },
    "byConvexHull2D" to { l -> l.byConvexHull2D() },
    "byFiltering" to { l -> l.byFiltering { _, _ -> true } },
    "windingNumber" to { l -> l.windingNumber(vec(150f, 60f)) },
    "contains" to { l -> l.contains(vec(150f, 60f)) },
    "centroid2D" to { l -> l.centroid2D() },
    "fast2DBounds" to { l -> l.fast2DBounds() },
    "byDashing" to { l -> l.byDashing(14f, 8f).size },
)
val results = ops.map { (name, op) ->
    val r = runCatching { op(a.copy()) }
    if (r.isSuccess) "  ok    $name" else "  THREW $name: ${r.exceptionOrNull()}"
}
val bad = results.count { it.contains("THREW") }

(listOf(
    "arrow(startHead = true, endHead = true): ${a.nodes.size} nodes, " +
        "Closes at $closes, of which ${interior.size} interior",
    "convex hull: ${hull.nodes.size} nodes",
    "",
    "$bad of ${ops.size} operations throw on it:",
) + results).joinToString("\n")
```

## 2. reversing a closed contour

<!-- field-box id="close_node_reversal" index="1" -->

### Narrative

From `code`

A square that relies on close() for its fourth side, and a two-contour outline whose
counter also relies on close(). Write those closing sides as an explicit lineTo back to
the start and the reversal below comes out right — the defect only shows on a shape
where the Close is carrying a segment nothing else draws.

```edn [field-box id="close_node_reversal" role="properties"]
{"id" "close_node_reversal" "index" 1 "name" "reversing a closed contour" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "reversing a closed contour" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "close_node_reversal_code_.kt" "lines" 82 "characters" 3663 "sha256" "d8bcc1e27af3cf462a4c85d10bbbe42312e0c632fcf4b5b2470ad057702f25c6"}]}
```

```kotlin [field-property box="close_node_reversal" name="code" path="close_node_reversal_code_.kt"]
// A square that relies on close() for its fourth side, and a two-contour outline whose
// counter also relies on close(). Write those closing sides as an explicit lineTo back to
// the start and the reversal below comes out right — the defect only shows on a shape
// where the Close is carrying a segment nothing else draws.
val square = fline {
    moveTo(0, 0); lineTo(200, 0); lineTo(200, 200); lineTo(0, 200); close()
}

val glyph = fline {
    moveTo(0, 0)
    cubicTo(vec(-40, 150, 0), vec(60, 260, 0), vec(160, 250, 0))
    cubicTo(vec(260, 240, 0), vec(300, 120, 0), vec(250, 20, 0))
    close()
    moveTo(80, 70)
    cubicTo(vec(60, 140, 0), vec(110, 190, 0), vec(170, 180, 0))
    cubicTo(vec(220, 172, 0), vec(230, 110, 0), vec(200, 62, 0))
    close()
}

// The old implementation, written out here rather than remembered: one backwards walk
// over the whole node list, emitting each instruction's reverse as it goes. The `.to`
// reads are `endPointOf` so this runs at all — the crash is not what this box is about —
// but the *structure* is the old one, and the structure is the defect: a Close met on the
// way back is emitted right there, which in the reversed order is immediately after the
// moveTo, where the subpath's first instruction belongs.
val oldByReversing = { line: FLine ->
    val out = FLine()
    if (line.nodes.isNotEmpty()) {
        out.moveTo(line.currentPoint!!)
        for (i in line.nodes.size - 1 downTo 1) {
            val node = line.nodes[i]
            val lands = line.endPointOf(i - 1)
            when (node) {
                is FLine.LineTo -> out.lineTo(lands)
                is FLine.CubicTo -> out.cubicTo(node.c2, node.c1, lands)
                is FLine.MoveTo -> out.moveTo(lands)
                is FLine.Close -> out.close()
            }
        }
    }
    out
}

val ghost = { l: FLine -> l.copy { thickness = 9.0; color = vec(0.45, 0.5, 0.58, 0.55) } }
val cyan = { l: FLine -> l.copy { thickness = 3.0; color = vec(0.35, 0.85, 1.0, 0.95) } }

// How much of the shape the reversal actually retraces. A reversal that drops a segment
// is shorter than what it reversed, and that is the whole story in one number.
val coverage = { original: FLine, reversed: FLine ->
    val a = original.cursor().lengthD()
    val b = reversed.cursor().lengthD()
    "%.0f of %.0f units retraced (%.0f%%)".format(b, a, 100.0 * b / a)
}

val squareOld = oldByReversing(square)
val squareNew = square.byReversing()
val glyphOld = oldByReversing(glyph)
val glyphNew = glyph.byReversing()

showPlaneGrid(
    "closed square, old reversal\n${coverage(square, squareOld)}" to listOf(ghost(square), cyan(squareOld)),
    "closed square, byReversing today\n${coverage(square, squareNew)}" to listOf(ghost(square), cyan(squareNew)),
    "two contours, old reversal\n${coverage(glyph, glyphOld)}" to listOf(ghost(glyph), cyan(glyphOld)),
    "two contours, byReversing today\n${coverage(glyph, glyphNew)}" to listOf(ghost(glyph), cyan(glyphNew)),
    columns = 2,
    cellWidth = 420,
    cellHeight = 230,
)

// Reversing twice is the identity now, which the old walk could not manage on anything
// closed: it is not even length-preserving once, so it cannot be an involution.
val roundTrip = { l: FLine ->
    val back = l.byReversing().byReversing()
    val same = l.nodes.map { it::class.simpleName } == back.nodes.map { it::class.simpleName }
    if (same) "identical" else "DIFFERENT: ${back.nodes.map { it::class.simpleName }}"
}

listOf(
    "grey = the original, cyan = its reversal",
    "square   reverse-twice: ${roundTrip(square)}",
    "glyph    reverse-twice: ${roundTrip(glyph)}",
).joinToString("\n")
```

