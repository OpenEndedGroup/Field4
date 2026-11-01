# Field Document: chart_clip

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 4 - Every mark kind, at the edge

<!-- field-box id="clip_marks" index="0" -->

### Narrative

From `code`

Clipping is not only about lines. Every mark kind is cut against plotRect, and the
interesting cases are the ones that used to be cut WRONG rather than not at all:
a bar was clamped in y but not in x, and `coerceIn` left a bar wholly off the axis
as a one-pixel smear along the frame instead of nothing.

```edn [field-box id="clip_marks" role="properties"]
{"id" "clip_marks" "index" 0 "name" "4 - Every mark kind, at the edge" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - Every mark kind, at the edge" "_tombstone" false "frame" #afield/rect [900.0 640.0 500.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "clip_marks_code_.kt" "lines" 48 "characters" 2149 "sha256" "0fe58ad274f12302768c4f0c781b65074815d57db03909143f061c64ed0f5079"}]}
```

```kotlin [field-property box="clip_marks" name="code" path="clip_marks_code_.kt"]
/*
Clipping is not only about lines. Every mark kind is cut against plotRect, and the
interesting cases are the ones that used to be cut WRONG rather than not at all:
a bar was clamped in y but not in x, and `coerceIn` left a bar wholly off the axis
as a one-pixel smear along the frame instead of nothing.
*/
val c =
    Chart(title = "half of everything is outside", xLabel = "x", yLabel = "y")
        .area("area", (0..40).toList(), (0..40).map { it * 0.6 - 6.0 })
        .line("line", (0..40).toList(), (0..40).map { 22.0 - it * 0.9 })
        .points("points", (0..40).toList(), (0..40).map { 4.0 + it * 0.4 })
        .rule(11.0, "an in-window rule")
        .rule(99.0, "a rule off the axis")
c.xDomain = 8.0 to 32.0
c.yDomain = 0.0 to 20.0

showChart(c)

/*
Nothing outside the plot rectangle, and the off-axis rule is not drawn at all --
a rule at 99 has no position inside a 0..20 axis, so it gets none. Count only MARK lines:
the tick labels, the title and the footnote live outside plotRect on purpose, and
counting those would be measuring the wrong thing.
*/
val plot = c.plotRect()
println("plotRect: $plot")
["area", "line", "points"].forEach { name ->
    val ns =
        c.toFLines().filter { it.label?.startsWith(name) == true }.flatMap { l -> l.nodes.mapNotNull { it.toOrNull } }
    val over: Double = ns.maxOf { p ->
        val px = p.x.toDouble()
        val py = p.y.toDouble()
        maxOf(plot.x - px, px - (plot.x + plot.w), plot.y - py, py - (plot.y + plot.h), 0.0)
    }
    println("  %-7s %3d nodes, worst %.2f px outside plotRect".format(name, ns.size, over))
}
/*
`points` is the one that is not zero, and it is not a clip failure. A dot is dropped
when its CENTRE leaves the window, not cut in half, so a dot sitting on the edge puts
its own radius -- 3 px by default -- outside. Half a disc hanging off the axis reads
as a rendering artefact; a whole dot on the line does not.
*/
println(
    "lines labelled 'a rule off the axis': " + c.toFLines().count { it.label == "a rule off the axis" },
)
println(
    "lines labelled 'an in-window rule':  " + c.toFLines().count { it.label == "an in-window rule" },
)
```

## 2. 2 - The same figure, unclipped

<!-- field-box id="clip_off" index="1" -->

### Narrative

From `code`

Set clip = false to compare the same corner without clipping at the plot bounds.

```edn [field-box id="clip_off" role="properties"]
{"id" "clip_off" "index" 1 "name" "2 - The same figure, unclipped" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - The same figure, unclipped" "_tombstone" false "frame" #afield/rect [900.0 60.0 440.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "clip_off_code_.kt" "lines" 30 "characters" 1053 "sha256" "ccbd399179d466b9129079e1289ba68fe7a552a385b4471d0fb473ac645b4425"}]}
```

```kotlin [field-property box="clip_off" name="code" path="clip_off_code_.kt"]
// Set clip = false to compare the same corner without clipping at the plot bounds.
val outline = [
    -1.0 to -1.5,
    1.0 to -1.5,
    1.0 to 1.5,
    -1.0 to 1.5,
    -1.0 to -1.5,
]

val c =
    Chart(title = "the same corner, unclipped", xLabel = "x", yLabel = "y")
        .line("outline", outline.map { it.first }, outline.map { it.second })
c.width = 780.0
c.height = 440.0
c.xDomain = 0.82 to 1.06
c.yDomain = 1.32 to 1.56
c.clip = false // <-- poke this one word

showChart(c)

// The panel does not crop what falls outside the frame. It fits the geometry's own
// bounds, so the axes are scaled down until the stray outline fits beside them.
val ps = c.toFLines().flatMap { l -> l.nodes.mapNotNull { it.toOrNull } }
val w = ps.maxOf { it.x } - ps.minOf { it.x }
val h = ps.maxOf { it.y } - ps.minOf { it.y }
println("drawn bounds: %.0fx%.0f for a %.0fx%.0f frame".format(w, h, c.width, c.height))
println(
    "a panel therefore draws this figure at %.0f%% of its declared size"
        .format(100.0 * minOf(c.width / w, c.height / h)),
)
```

## 3. 1 - The corner, clipped

<!-- field-box id="clip_on" index="2" -->

### Narrative

From `code`

A magnified corner of a body far larger than the window — the shape of figure that
finds this bug, because it is the one that needs an explicit domain. The outline is
2 x 3; the window is 0.24 x 0.24 around one of its corners.

```edn [field-box id="clip_on" role="properties"]
{"id" "clip_on" "index" 2 "name" "1 - The corner, clipped" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - The corner, clipped" "_tombstone" false "frame" #afield/rect [60.0 60.0 440.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "clip_on_code_.kt" "lines" 39 "characters" 1208 "sha256" "5e4d0958296fb3f9f4929199467173dcf1bdc0d5e50b5ec2a2ca972ae1eef280"}]}
```

```kotlin [field-property box="clip_on" name="code" path="clip_on_code_.kt"]
/*
A magnified corner of a body far larger than the window — the shape of figure that
finds this bug, because it is the one that needs an explicit domain. The outline is
2 x 3; the window is 0.24 x 0.24 around one of its corners.
*/
val outline = [
    -1.0 to -1.5,
    1.0 to -1.5,
    1.0 to 1.5,
    -1.0 to 1.5,
    -1.0 to -1.5,
]

val c =
    Chart(title = "one corner of a 2 x 3 outline", xLabel = "x", yLabel = "y")
        .line("outline", outline.map { it.first }, outline.map { it.second })
c.width = 780.0
c.height = 440.0
c.xDomain = 0.82 to 1.06
c.yDomain = 1.32 to 1.56

showChart(c)

/*
`plotRect` is the rectangle the axes bound, and the one mark geometry is clipped to.
`toFLines()` is the whole figure in pixels, and its union bounds are exactly what
`linesToHTML` publishes to the panel. Everything is inside the 780 x 440 asked for.
*/
val ps = c.toFLines().flatMap { l -> l.nodes.mapNotNull { it.toOrNull } }
println("plotRect: " + c.plotRect())
println(
    "drawn:    %.0f,%.0f %.0fx%.0f"
        .format(
            ps.minOf { it.x },
            ps.minOf { it.y },
            ps.maxOf { it.x } - ps.minOf { it.x },
            ps.maxOf { it.y } - ps.minOf { it.y },
        ),
)
```

## 4. 3 - A note, wrapped and not

<!-- field-box id="note_wrap" index="3" -->

### Narrative

From `code`

The other half of the same property. A note used to be one unwrapped line; this one
is 142 characters, and it is verbatim the note the tetgen corner figure shipped with
before someone shortened it by hand to work around exactly this.

```edn [field-box id="note_wrap" role="properties"]
{"id" "note_wrap" "index" 3 "name" "3 - A note, wrapped and not" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - A note, wrapped and not" "_tombstone" false "frame" #afield/rect [60.0 640.0 500.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "note_wrap_code_.kt" "lines" 29 "characters" 1181 "sha256" "cdce6d0a54956619871a58c9f62c079369891ffb98b5cbdf6e3c833236250ecf"}]}
```

```kotlin [field-property box="note_wrap" name="code" path="note_wrap_code_.kt"]
/*
The other half of the same property. A note used to be one unwrapped line; this one
is 142 characters, and it is verbatim the note the tetgen corner figure shipped with
before someone shortened it by hand to work around exactly this.
*/
val note =
    "a 0.24 x 0.24 window on a body 2 x 3 across. The true corner is at " +
        "(1.0, 1.5); every level set cuts it off, and halving h halves what is left."

fun figure(clip: Boolean) =
    Chart(title = "a long note", xLabel = "x", yLabel = "y", note = note)
        .line("ramp", (0..10).toList(), (0..10).toList())
        .also {
            it.width = 780.0
            it.height = 440.0
            it.clip = clip
        }

showChart(figure(clip = true))

// Flip the argument to `false` and re-run: the note goes back to one line running off
// the right edge, and the whole figure shrinks to fit beside it.
[true, false].forEach { on ->
    val ps = figure(on).toFLines().flatMap { l -> l.nodes.mapNotNull { it.toOrNull } }
    val w = ps.maxOf { it.x } - ps.minOf { it.x }
    println(
        "clip = %-5s  figure %4.0f px wide in a 780 px frame  ->  %3.0f%%".format(on, w, 100.0 * minOf(1.0, 780.0 / w)),
    )
}
```

