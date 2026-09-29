# Field Document: chart_primitive

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 4 - An assertion you can watch fail

<!-- field-box id="chart_expect" index="0" -->

```edn [field-box id="chart_expect" role="properties"]
{"id" "chart_expect" "index" 0 "name" "4 - An assertion you can watch fail" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - An assertion you can watch fail" "_tombstone" false "frame" #afield/rect [634.2 226.0 534.2 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chart_expect_code_.kt" "lines" 27 "characters" 1084 "sha256" "b8e31c24c28c72c5e92040648755c5b11b85147d33ec1d46de1376d8780b8f29"}]}
```

```kotlin [field-property box="chart_expect" name="code" path="chart_expect_code_.kt"]
import kotlin.math.exp

// `Chart.expect` is `Table.expect`'s counterpart, and it DRAWS the claim rather than
// only checking it: the accepted range becomes a band, the bound it names is ruled,
// the data outside recolours, and the footnote counts them. An unbounded side draws no
// edge — that is the visual difference between "at most 1e-6" and "0 to 1e-6".
val iterations = (0..40).toList()
val residual = iterations.map { i ->
    val base = exp(-i * 0.22) * 1.5e-6
    if (i == 11 || i == 23) base + 2.4e-6 else base   // two real overshoots
}

val c = Chart(
    title = "residual per iteration",
    xLabel = "iteration",
    yLabel = "|r|",
    note = "Adam, lr 0.05",
)
    .line("residual", iterations, residual)
    .points("residual", iterations, residual)
    .expect(max = 1e-6, because = "converged below 1e-6")

showChart(c)

// And `deviations` hands the same list back, so a test asserts on the claim the figure
// makes rather than on its markup. This is the whole reason the primitive earns its keep.
facts(c.deviations.map { "iteration ${it.index}" to it.y })
```

## 2. 2 - A DataFrame charts itself

<!-- field-box id="chart_dataframe" index="1" -->

```edn [field-box id="chart_dataframe" role="properties"]
{"id" "chart_dataframe" "index" 1 "name" "2 - A DataFrame charts itself" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - A DataFrame charts itself" "_tombstone" false "frame" #afield/rect [634.2 60.0 463.9 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chart_dataframe_code_.kt" "lines" 21 "characters" 750 "sha256" "50d78b2e86c97aa05e4529d1b290f9945de419d3261996b14421f1639a6335e2"}]}
```

```kotlin [field-property box="chart_dataframe" name="code" path="chart_dataframe_code_.kt"]
import org.jetbrains.kotlinx.dataframe.api.*

// A frame already renders as a table — that is the right compact form, because a frame
// is rows and columns first. This is the other question a reader has about a numeric
// column: what shape is it.
val runs = dataFrameOf("iteration", "residual", "step")(
    0, 1.00, 0.50,
    1, 0.62, 0.44,
    2, 0.31, 0.39,
    3, 0.18, 0.35,
    4, 0.09, 0.31,
    5, 0.05, 0.28,
    6, 0.02, 0.25,
)

// No column named: the first numeric column becomes the x axis and the rest become
// series, each with a pickable points mark in the line's own colour.
showChart(runs, title = "a frame, charting its own columns")

// ...and the last expression is still the frame, so the table is right underneath it.
runs
```

## 3. 1 - A chart, in one call

<!-- field-box id="chart_one_call" index="2" -->

```edn [field-box id="chart_one_call" role="properties"]
{"id" "chart_one_call" "index" 2 "name" "1 - A chart, in one call" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - A chart, in one call" "_tombstone" false "frame" #afield/rect [60.0 60.0 405.3 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chart_one_call_code_.kt" "lines" 21 "characters" 822 "sha256" "6c5dd097c528cdd5e3e7f2726f68ea925b53a622773fe3679c10935e2db1eb30"}]}
```

```kotlin [field-property box="chart_one_call" name="code" path="chart_one_call_code_.kt"]
import kotlin.math.exp
import kotlin.math.sin

// One call. `showChart` builds it, puts it on screen, and hands it back so you can
// keep asking it questions — the same shape as `showTable` and `showSignals`.
val t = (0..240).map { it / 240.0 * 6.2831853 }
val chart = showChart(
    "damped sine" to t.map { exp(-it * 0.25) * sin(it * 2.0) },
    "envelope" to t.map { exp(-it * 0.25) },
    x = t,
    title = "a chart, in one call",
    xLabel = "t (radians)",
    yLabel = "amplitude",
)

// The ticks are not this box's problem, and they are not the renderer's either:
// `niceTicks` is one public copy of Heckbert's algorithm that five files each had
// privately. The plot frame it landed on is readable too.
println(chart)
println("y ticks: " + niceTicks(-1.0, 1.0, 5))
println("plot frame: " + chart.plotRect())
```

## 4. 3 - Hover a bar, click a datum

<!-- field-box id="chart_pick" index="3" -->

### Narrative

From `code`

Bar and point marks set `onClick`, `hoverColor` and `label`, which is exactly what
`Box.lines` looks for when it decides between the interactive SVG renderer and Skia's
(someSkiko.kt:37). So hovering and clicking work on a chart nobody configured —
by construction rather than by wiring.

Hover a bar: it lights up. Click one: this handler runs, here, in this box.

```edn [field-box id="chart_pick" role="properties"]
{"id" "chart_pick" "index" 3 "name" "3 - Hover a bar, click a datum" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - Hover a bar, click a datum" "_tombstone" false "frame" #afield/rect [60.0 226.0 475.6 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chart_pick_code_.kt" "lines" 20 "characters" 953 "sha256" "36d59fdb370afd0a8ce6504ea75e95e29b6d9b12248457e809918059e3c3d8a5"}]}
```

```kotlin [field-property box="chart_pick" name="code" path="chart_pick_code_.kt"]
// Bar and point marks set `onClick`, `hoverColor` and `label`, which is exactly what
// `Box.lines` looks for when it decides between the interactive SVG renderer and Skia's
// (someSkiko.kt:37). So hovering and clicking work on a chart nobody configured —
// by construction rather than by wiring.
//
// Hover a bar: it lights up. Click one: this handler runs, here, in this box.
val c = Chart(title = "hover a bar, then click one", yLabel = "gates")
    .bars("cost", listOf("xor", "maj3", "adder", "mux", "parity"), listOf(6, 11, 27, 4, 19))

c.onPick = { pick ->
    println("clicked ${pick.category} = ${pick.y} (index ${pick.index})")
    println("  the hit itself: ${pick.hit.kind} at ${pick.hit.position}")
}

showChart(c)

// Set `pickable = false` for a figure whose points are scenery rather than data: it is
// one registered callback and one <g> per datum, which a ten-thousand-point scatter
// does not want.
println("marks: " + c.marks)
```

