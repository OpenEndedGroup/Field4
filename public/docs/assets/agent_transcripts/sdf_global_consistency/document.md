# Field Document: sdf_global_consistency

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - sample circles before and after

<!-- field-box id="sdf_circles" index="0" -->

### Narrative

From `code`

Two discs joined with min: exact outside, only a bound inside.

```edn [field-box id="sdf_circles" role="properties"]
{"id" "sdf_circles" "index" 0 "name" "1 - sample circles before and after" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - sample circles before and after" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sdf_circles_code_.kt" "lines" 36 "characters" 1573 "sha256" "bd9134ba2cb616cd56983645aab4752cc58a94b29bd8d05eda4975d2564f4a5f"}]}
```

```kotlin [field-property box="sdf_circles" name="code" path="sdf_circles_code_.kt"]
// Two discs joined with min: exact outside, only a bound inside.
fun disc(cx: Double, cy: Double, r: Double) = { x: Double, y: Double -> hypot(x - cx, y - cy) - r }
val left = disc(-0.4, 0.0, 0.6)
val right = disc(0.4, 0.0, 0.6)
val union = { x: Double, y: Double -> min(left(x, y), right(x, y)) }

val pseudo = sdfGrid2D(xMin = -1.2, yMin = -1.0, xMax = 1.2, yMax = 1.0, n = 13, field = union)
val fixed = pseudo.repaired()
println("pseudo   ${pseudo.consistency()}")
println("repaired ${fixed.consistency()}")
val scale = 120.0
fun circles(grid: SdfGrid2D, dx: Double) = buildList {
    val nearest = grid.nearestSurfaceDistances()
    for (j in 0 until grid.ny) for (i in 0 until grid.nx) {
        val d = grid[i, j]
        val miss = nearest[j * grid.nx + i] - abs(d) // > 0 falls short, < 0 swallows surface
        val c = (abs(miss) / grid.h).coerceIn(0.0, 1.0)
        add(
            fline { circle((grid.x(i) + dx) * scale, grid.y(j) * scale, abs(d) * scale) }
                .apply {
                    color = if (d < 0) vec(0.15 + 0.85 * c, 0.3, 0.7 - 0.5 * c, 0.8) else vec(0.6, 0.6, 0.6, 0.15)
                },
        )
    }
    val s = grid.surfacePoints()
    for (k in 0 until s.size / 2) {
        add(
            fline { circle((s[2 * k] + dx) * scale, s[2 * k + 1] * scale, 2.0) }
                .apply {
                    filled = true
                    color = vec(1.0, 0.67, 0.0, 1.0)
                },
        )
    }
}
lines(circles(pseudo, 0.0) + circles(fixed, 2.6), channel = "circles", w = 640.0, h = 260.0, fitToSize = true)
```

## 2. 2 - offsets from a bound vs a distance

<!-- field-box id="sdf_offsets" index="1" -->

### Narrative

From `code`

A box with a disc cut from its corner by max: the zero set is right, the values beside it are
only bounds, so offset curves drawn from them come out in the wrong place.

```edn [field-box id="sdf_offsets" role="properties"]
{"id" "sdf_offsets" "index" 1 "name" "2 - offsets from a bound vs a distance" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - offsets from a bound vs a distance" "_tombstone" false "frame" #afield/rect [60.0 420.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sdf_offsets_code_.kt" "lines" 37 "characters" 1674 "sha256" "3a82ecaa484a80ff26387b9afd565c8c4e80f94426b66e3d4e6b8c17b26ce101"}]}
```

```kotlin [field-property box="sdf_offsets" name="code" path="sdf_offsets_code_.kt"]
// A box with a disc cut from its corner by max: the zero set is right, the values beside it are
// only bounds, so offset curves drawn from them come out in the wrong place.
fun disc(cx: Double, cy: Double, r: Double) = { x: Double, y: Double -> hypot(x - cx, y - cy) - r }
fun rect(hx: Double, hy: Double) = { x: Double, y: Double ->
    val qx = abs(x) - hx
    val qy = abs(y) - hy
    hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0)
}
val square = rect(0.8, 0.6)
val hole = disc(0.8, 0.6, 0.5)
val cut = { x: Double, y: Double -> max(square(x, y), -hole(x, y)) }

val pseudo = sdfGrid2D(xMin = -1.5, yMin = -1.5, xMax = 1.5, yMax = 1.5, n = 121, field = cut)
val fixed = pseudo.repaired()
println("pseudo   ${pseudo.consistency()}")
println("repaired ${fixed.consistency()}")

// Offsets at +-0.2 and +-0.4 from each grid; orange is the shape itself.
val scale = 120.0
fun offsets(grid: SdfGrid2D, dx: Double) = buildList {
    for (iso in [-0.4, -0.2, 0.0, 0.2, 0.4]) {
        val contours = generateIsoContours(grid::bilinear, -1.5, 1.5, -1.5, 1.5, 240, 240, iso)
        for (c in contours) {
            add(
                fline {
                    moveTo((c[0].x + dx) * scale, c[0].y * scale)
                    for (p in c) lineTo((p.x + dx) * scale, p.y * scale)
                    lineTo((c[0].x + dx) * scale, c[0].y * scale)
                }
                    .apply {
                        color = if (iso == 0.0) vec(1.0, 0.67, 0.0, 1.0) else vec(0.0, 0.67, 1.0, 0.8)
                    },
            )
        }
    }
}
lines(offsets(pseudo, 0.0) + offsets(fixed, 3.2), channel = "offsets", w = 640.0, h = 300.0, fitToSize = true)
```

