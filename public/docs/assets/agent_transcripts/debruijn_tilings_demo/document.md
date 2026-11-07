# Field Document: debruijn_tilings_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. slide a family of grid lines

<!-- field-box id="debruijn_slider" index="0" -->

### Narrative

From `code`

Drag the slider: every grid line of family 0 moves by `offset`, and the tiling re-forms.
Tiles flip in small groups ("phason flips") while the rest stay put.

```edn [field-box id="debruijn_slider" role="properties"]
{"id" "debruijn_slider" "index" 0 "name" "slide a family of grid lines" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "slide a family of grid lines" "_tombstone" false "frame" #afield/rect [80.0 1440.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn_slider_code_.kt" "lines" 22 "characters" 749 "sha256" "189db3b1d1b57a314c4fc7a05c662bcd3fa0edd0ba0e7bd09fce60c128f7638a"}]}
```

```kotlin [field-property box="debruijn_slider" name="code" path="debruijn_slider_code_.kt"]
// Drag the slider: every grid line of family 0 moves by `offset`, and the tiling re-forms.
// Tiles flip in small groups ("phason flips") while the rest stay put.
var offset = 0.0
var symmetry = 5.0

val draw = {
    val n = symmetry.toInt()
    val offsets = defaultDeBruijnOffsets(n).toMutableList()
    offsets[0] += offset
    val t = deBruijnTiling(symmetry = n, offsets = offsets, radius = 5.0, scale = 12.0)
    lines(t.lines(), w = 480.0, h = 480.0, fitToSize = true)
}

slider("family 0 offset", min = -1.0, max = 1.0, step = 0.01, initialValue = 0.0, channel = "offset") {
    offset = it
    draw()
}
slider("symmetry", min = 4.0, max = 12.0, step = 1.0, initialValue = 5.0, channel = "symmetry") {
    symmetry = it
    draw()
}
draw()
```

## 2. a Penrose tiling, coloured by rhombus type

<!-- field-box id="debruijn_penrose" index="1" -->

```edn [field-box id="debruijn_penrose" role="properties"]
{"id" "debruijn_penrose" "index" 1 "name" "a Penrose tiling, coloured by rhombus type" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a Penrose tiling, coloured by rhombus type" "_tombstone" false "frame" #afield/rect [80.0 80.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn_penrose_code_.kt" "lines" 4 "characters" 284 "sha256" "2f71f01e082009c0d328aff7ba1b7bec33d45f1910ca7d0e2c915bc593d12a7a"}]}
```

```kotlin [field-property box="debruijn_penrose" name="code" path="debruijn_penrose_code_.kt"]
val penrose = deBruijnTiling(symmetry = 5, radius = 7.0, scale = 14.0)
val counts = penrose.typeCounts()
lines(penrose.lines(), w = 560.0, h = 560.0, fitToSize = true)
"thin ${counts[1]}, thick ${counts[2]}, thick/thin %.4f".format(counts.getValue(2).toDouble() / counts.getValue(1))
```

## 3. a Penrose relief through Manifold

<!-- field-box id="debruijn_relief" index="2" -->

```edn [field-box id="debruijn_relief" role="properties"]
{"id" "debruijn_relief" "index" 2 "name" "a Penrose relief through Manifold" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a Penrose relief through Manifold" "_tombstone" false "frame" #afield/rect [80.0 2120.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn_relief_code_.kt" "lines" 29 "characters" 1128 "sha256" "6337ebb20a2404d551c7f7af5553ac3411e3b21eec501a0487853f785e926d68"}]}
```

```kotlin [field-property box="debruijn_relief" name="code" path="debruijn_relief_code_.kt"]
import afield.geometry.solid.manifold.*

/*
A Penrose relief: every rhombus inset by 0.6 and extruded, thin ones taller than thick ones,
then all unioned into one closed solid. Each tile's outline is a closed FLine, so it goes
straight into a Manifold Section.
*/
val tiling = deBruijnTiling(symmetry = 5, radius = 3.5, scale = 10.0)
val relief = Solid.scope {
    val prisms =
        tiling.tiles.map { tile ->
            Section.ofFLines([tile.outline()], sampleDistance = 10.0)
                .offset(-0.6, JoinType.MITER)
                .extruded(if (tile.type == 1) 6.0 else 3.0)
        }
    val base =
        Section.ofFLines(tiling.tiles.map { it.outline() }, sampleDistance = 10.0)
            .offset(0.5, JoinType.MITER)
            .extruded(1.5)
    Solid.union(prisms + base)
}
val mesh = relief.toTriMesh()
showMesh(
    mesh,
    title = "Penrose relief, ${tiling.tiles.size} rhombi",
    caption =
        "thin 36° tiles 6 high, thick 72° tiles 3 high · genus ${relief.genus} · volume %.0f".format(relief.volume),
    view = MeshView(yawDegrees = 20.0, pitchDegrees = 50.0, width = 760, height = 520),
)
```

## 4. one grid line's ribbon

<!-- field-box id="debruijn_ribbon" index="3" -->

### Narrative

From `code`

Every tile on one grid line's ribbon shares an edge with the next: the line threads a chain of
rhombi whose shared edges are all parallel. Line 0 of family 0 is drawn in red.

```edn [field-box id="debruijn_ribbon" role="properties"]
{"id" "debruijn_ribbon" "index" 3 "name" "one grid line's ribbon" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "one grid line's ribbon" "_tombstone" false "frame" #afield/rect [80.0 760.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn_ribbon_code_.kt" "lines" 10 "characters" 566 "sha256" "278541d16d503b98e5027b0c1225f5e24e2dda79c6d58fdf5c7977edb457880f"}]}
```

```kotlin [field-property box="debruijn_ribbon" name="code" path="debruijn_ribbon_code_.kt"]
// Every tile on one grid line's ribbon shares an edge with the next: the line threads a chain of
// rhombi whose shared edges are all parallel. Line 0 of family 0 is drawn in red.
val penrose = deBruijnTiling(symmetry = 5, radius = 7.0, scale = 14.0)
val line = DeBruijnLine(family = 0, index = 0)
val ribbon = penrose.ribbon(line)

val grey = [vec(0.32, 0.34, 0.40, 1.0), vec(0.22, 0.24, 0.30, 1.0)]
lines(penrose.lines(palette = grey, highlight = line), w = 560.0, h = 560.0, fitToSize = true)

"${ribbon.size} tiles on family ${line.family}, line ${line.index}"
```

## 5. animate family 0 sliding one spacing

<!-- field-box id="debruijn_offset_sweep" index="4" -->

### Narrative

From `code`

Slide every line of family 0 through one full spacing over six seconds. Most tiles hold still;
small groups flip as each line passes a crossing. At offset +1 the lines are back where they
started, relabelled, so the loop has no seam. The red ribbon is line 0 of family 0.

```edn [field-box id="debruijn_offset_sweep" role="properties"]
{"id" "debruijn_offset_sweep" "index" 4 "name" "animate family 0 sliding one spacing" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "animate family 0 sliding one spacing" "_tombstone" false "frame" #afield/rect [700.0 1440.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn_offset_sweep_code_.kt" "lines" 25 "characters" 1066 "sha256" "928b487a95b6908a0d892fcb28aec1c0fcad725bb137d846e5d43ac37a86e932"}]}
```

```kotlin [field-property box="debruijn_offset_sweep" name="code" path="debruijn_offset_sweep_code_.kt"]
/*
Slide every line of family 0 through one full spacing over six seconds. Most tiles hold still;
small groups flip as each line passes a crossing. At offset +1 the lines are back where they
started, relabelled, so the loop has no seam. The red ribbon is line 0 of family 0.
*/
val base = defaultDeBruijnOffsets(5)
val line = DeBruijnLine(family = 0, index = 0)
val window = Rect(-190.0, -190.0, 380.0, 380.0)
val seconds = 6.0
val started = AnimationClock.nowSeconds()

animate {
    val shift = ((AnimationClock.nowSeconds() - started) / seconds) % 1.0
    val offsets = base.toMutableList().also { it[0] += shift }
    // recenter = false: unchanged tiles stay put rather than shifting with the mean.
    val t = deBruijnTiling(symmetry = 5, offsets = offsets, radius = 5.0, scale = 12.0, recenter = false)
    showPlane(
        t.lines(highlight = line),
        title = "family 0 offset +%.2f".format(shift),
        caption = "thin ${t.typeCounts()[1]}, thick ${t.typeCounts()[2]}",
        width = 560,
        height = 560,
        window = window,
    )
}
```

## 6. 8, 7, 12-fold and disordered 6-fold

<!-- field-box id="debruijn_gallery" index="5" -->

### Narrative

From `code`

The same construction at four symmetries, one with its grid lines jittered.

```edn [field-box id="debruijn_gallery" role="properties"]
{"id" "debruijn_gallery" "index" 5 "name" "8, 7, 12-fold and disordered 6-fold" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "8, 7, 12-fold and disordered 6-fold" "_tombstone" false "frame" #afield/rect [700.0 80.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn_gallery_code_.kt" "lines" 16 "characters" 740 "sha256" "e9801045302ca4c19d26c6abd1f4ad41613c2f9de8c5d64cbd5ae996abb57961"}]}
```

```kotlin [field-property box="debruijn_gallery" name="code" path="debruijn_gallery_code_.kt"]
// The same construction at four symmetries, one with its grid lines jittered.
val panel = { title: String, t: DeBruijnTiling ->
    val counts =
        t.typeCounts().entries.joinToString("  ") { (type, n) -> "%.1f°: %d".format(type * 180.0 / t.symmetry, n) }
    "$title\n$counts" to t.lines()
}

showPlaneGrid(
    panel("8-fold, Ammann–Beenker", deBruijnTiling(symmetry = 8, radius = 5.0, scale = 10.0)),
    panel("7-fold", deBruijnTiling(symmetry = 7, radius = 5.0, scale = 10.0)),
    panel("12-fold", deBruijnTiling(symmetry = 12, radius = 4.0, scale = 8.0)),
    panel("6-fold, disorder 0.6", deBruijnTiling(symmetry = 6, radius = 6.0, scale = 10.0, disorder = 0.6)),
    columns = 2,
    cellWidth = 380,
    cellHeight = 380,
)
```

## 7. outlines for a pen plotter

<!-- field-box id="debruijn_plotter" index="6" -->

### Narrative

From `code`

Outlines only, one closed FLine per rhombus: what a pen plotter wants. Each tile's two parallel
pairs of edges are perpendicular to the two grid lines that made it.

```edn [field-box id="debruijn_plotter" role="properties"]
{"id" "debruijn_plotter" "index" 6 "name" "outlines for a pen plotter" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "outlines for a pen plotter" "_tombstone" false "frame" #afield/rect [700.0 760.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "debruijn_plotter_code_.kt" "lines" 6 "characters" 496 "sha256" "476bb5c27536f4da394c25dcdd60e280b38af44eeca1cb4db73598983f6494ab"}]}
```

```kotlin [field-property box="debruijn_plotter" name="code" path="debruijn_plotter_code_.kt"]
// Outlines only, one closed FLine per rhombus: what a pen plotter wants. Each tile's two parallel
// pairs of edges are perpendicular to the two grid lines that made it.
val tiling = deBruijnTiling(symmetry = 5, radius = 5.0, scale = 16.0)
val outlines = tiling.lines(fill = false, outlineThickness = 1.2, palette = [vec(0.92, 0.92, 0.88, 1.0)])
lines(outlines, w = 480.0, h = 480.0, fitToSize = true)
"${outlines.size} closed outlines, ${tiling.adjacency().sumOf { it.size } / 2} shared edges"
```

