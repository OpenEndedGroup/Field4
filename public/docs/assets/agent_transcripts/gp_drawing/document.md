# Field Document: gp_drawing

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 - A field over the plane

<!-- field-box id="gp_field2d" index="0" -->

### Narrative

From `code`

A scalar field pinned at four points: the mean, two whole-field draws on one colour scale,
and the standard deviation, dark at the observations. Change the values or the seeds and run again.

```edn [field-box id="gp_field2d" role="properties"]
{"id" "gp_field2d" "index" 0 "name" "3 - A field over the plane" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - A field over the plane" "_tombstone" false "frame" #afield/rect [3400.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gp_field2d_code_.kt" "lines" 44 "characters" 1564 "sha256" "1cfd82c9aa563d4a2e575a29ccf8d2536013091ecebbbb73814ff0aa96f11c9d"}]}
```

```kotlin [field-property box="gp_field2d" name="code" path="gp_field2d_code_.kt"]
// A scalar field pinned at four points: the mean, two whole-field draws on one colour scale,
// and the standard deviation, dark at the observations. Change the values or the seeds and run again.
val points = [vec(-0.8, -0.5), vec(0.55, -0.5), vec(-0.3, 0.65), vec(0.65, 0.55)]
val field = gp2D(
    points,
    doubleArrayOf(0.9, -0.7, -0.8, 0.65),
    kernel = GpKernels.matern(1.5, doubleArrayOf(0.55, 0.8)),
    noiseStd = 0.04,
)
val side = 40
val queries =
    Array(side * side) { i ->
        doubleArrayOf(
            -1.2 + 2.4 * (i % side) / (side - 1),
            -1.2 + 2.4 * (i / side) / (side - 1),
        )
    }
val joint = field.posterior(queries)
fun grid(values: DoubleArray) = Array(side) { row -> DoubleArray(side) { col -> values[row * side + col] } }

// panels side by side, separated by a thin gap
fun row(vararg panels: Array<DoubleArray>, gap: Double) =
    Array(side) { r ->
        panels.flatMapIndexed { i, p -> (if (i > 0) List(2) { gap } else emptyList()) + p[r].toList() }.toDoubleArray()
    }

// the mean and two whole-field draws share one colour scale; then the standard deviation
showHeatmap(
    row(grid(joint.mean), grid(joint.sample(seed = 57)), grid(joint.sample(seed = 58)), gap = 0.0),
    vmin = -2.5,
    vmax = 2.5,
    normalize = false,
    colormap = "coolwarm",
    width = 720,
    channel = "mean, two draws",
)
showHeatmap(
    grid(joint.variance.map { sqrt(it) }.toDoubleArray()),
    vmin = 0.0,
    normalize = false,
    colormap = "magma",
    width = 240,
    channel = "standard deviation",
)
```

## 2. 1 - A closed loop through six pins

<!-- field-box id="gp_loop" index="1" -->

### Narrative

From `code`

Six pins around a loop. Drag any pin's control below and the loop redraws: the orange mean
and six blue draws, all closed, all passing near every pin. Edit `start` to keep a shape.

```edn [field-box id="gp_loop" role="properties"]
{"id" "gp_loop" "index" 1 "name" "1 - A closed loop through six pins" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - A closed loop through six pins" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gp_loop_code_.kt" "lines" 68 "characters" 2403 "sha256" "f0e6dabe8d11a6733a2c9d5b9e0f29ef0a0b9f450b8990ec3cd2915ab483e5c9"}]}
```

```kotlin [field-property box="gp_loop" name="code" path="gp_loop_code_.kt"]
// Six pins around a loop. Drag any pin's control below and the loop redraws: the orange mean
// and six blue draws, all closed, all passing near every pin. Edit `start` to keep a shape.
val start = [
    vec(1.0, 0.0),
    vec(0.45, 0.8),
    vec(-0.5, 0.95),
    vec(-1.1, 0.0),
    vec(-0.4, -0.75),
    vec(0.6, -0.9),
]
val pins = start.map { vec(it.x, it.y, 0) }.toMutableList()
var lengthScale = 0.6

fun plot(line: FLine) = line.transform { vec(it.x * 200, -it.y * 200, 0) }

fun redraw() {
    // each pin's place on a parameter that runs once round the loop, 0 up to 1
    val at = DoubleArray(pins.size) { it.toDouble() / pins.size }
    // period 1: t = 0 and t = 1 are the same point, so every draw closes without a seam
    val loop = gpCurve(
        at,
        pins,
        kernel = GpKernels.periodic(period = 1.0, lengthScale = lengthScale, variance = 0.04),
        noiseStd = DoubleArray(pins.size) { 0.01 },
        dimensions = 2,
    )
    val distribution = loop.posterior(DoubleArray(201) { it / 200.0 })
    val draws =
        (0L..5L).map { seed ->
            plot(distribution.sampleLine(seed)).apply {
                color = vec(0.3, 0.65, 0.9, 0.8)
                thickness = 1.5
            }
        }
    val mean = plot(distribution.meanLine()).apply {
        color = vec(1, 0.5, 0.2, 1)
        thickness = 3.0
    }
    val dots = plot(FLine().apply { pins.forEach { moveTo(it.x, it.y) } }).apply {
        stroked = false
        pointed = true
        pointSize = 3.0
        color = vec(1, 1, 1, 1)
    }
    // a fixed frame, so the picture does not rescale as pins move
    val frame = plot(FLine().rect(-1.6, -1.4, 3.2, 2.8)).apply {
        color = vec(0.3, 0.3, 0.3, 1)
        thickness = 1.0
    }
    lines([frame] + draws + mean + dots, w = 520.0, h = 460.0, fitToSize = true)
}

// one panel of controls; running the box puts them back at `start`
controlGroup("pins", columns = 3) {
    pins.indices.forEach { i ->
        vec2Control("pin ${i + 1}", Vector2f(pins[i].x, pins[i].y), xRange = -1.5 to 1.5, yRange = -1.3 to 1.3) { v ->
                pins[i] = vec(v.x, v.y, 0)
                redraw()
            }
            .set(Vector2f(pins[i].x, pins[i].y))
    }
    slider("length scale", min = 0.1, max = 1.5, initialValue = lengthScale) {
            lengthScale = it
            redraw()
        }
        .set(lengthScale)
}
redraw()
```

## 3. 2 - Four measurements, two sliders

<!-- field-box id="gp_conditioning" index="2" -->

### Narrative

From `code`

Four measurements and a smoothness assumption: five draws (blue), the mean (orange) and the
95% pointwise interval (thin orange). Move the sliders: a longer length scale makes stiffer
functions; a noisier third measurement lets the draws pass further from it.

```edn [field-box id="gp_conditioning" role="properties"]
{"id" "gp_conditioning" "index" 2 "name" "2 - Four measurements, two sliders" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Four measurements, two sliders" "_tombstone" false "frame" #afield/rect [2200.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gp_conditioning_code_.kt" "lines" 66 "characters" 2172 "sha256" "bcdcd6211a08233e3a341967eb5ef5be2a6b292d8ba5eb9fb00e31dd8d0c587d"}]}
```

```kotlin [field-property box="gp_conditioning" name="code" path="gp_conditioning_code_.kt"]
/*
Four measurements and a smoothness assumption: five draws (blue), the mean (orange) and the
95% pointwise interval (thin orange). Move the sliders: a longer length scale makes stiffer
functions; a noisier third measurement lets the draws pass further from it.
*/
val xs = doubleArrayOf(-1.2, -0.35, 0.1, 0.95)
val ys = doubleArrayOf(-0.7, 0.8, 0.35, -0.4)
var lengthScale = 0.48
var thirdNoise = 0.22

fun plot(line: FLine) = line.transform { vec(it.x * 180, -it.y * 120, 0) }

fun redraw() {
    val gp = gp1D(
        xs,
        ys,
        noiseStd = doubleArrayOf(0.06, 0.06, thirdNoise, 0.06),
        kernel = GpKernels.rbf(lengthScale = lengthScale),
    )
    val grid = DoubleArray(181) { -1.8 + 3.6 * it / 180 }
    val posterior = gp.posterior(grid)
    val draws =
        (0L..4L).map { seed ->
            plot(posterior.sampleFLine(grid, seed)).apply {
                color = vec(0.3, 0.65, 0.9, 0.7)
                thickness = 1.5
            }
        }
    val (lower, upper) = posterior.intervalFLines(grid)
    val band = [lower, upper].map {
        plot(it).apply {
            color = vec(1, 0.5, 0.2, 0.5)
            thickness = 1.0
        }
    }
    val mean = plot(posterior.meanFLine(grid)).apply {
        color = vec(1, 0.5, 0.2, 1)
        thickness = 3.0
    }
    val pins = plot(FLine().apply { xs.indices.forEach { moveTo(xs[it], ys[it]) } }).apply {
        stroked = false
        pointed = true
        pointSize = 3.0
        color = vec(1, 1, 1, 1)
    }
    val frame = plot(FLine().rect(-1.8, -2.5, 3.6, 5.0)).apply {
        color = vec(0.3, 0.3, 0.3, 1)
        thickness = 1.0
    }
    lines([frame] + draws + band + mean + pins, w = 700.0, h = 420.0, fitToSize = true)
}

// running the box puts the sliders back at the values above
controlGroup("controls") {
    slider("length scale", min = 0.1, max = 1.5, initialValue = lengthScale) {
            lengthScale = it
            redraw()
        }
        .set(lengthScale)
    slider("noise of the third measurement", min = 0.01, max = 0.8, initialValue = thirdNoise) {
            thirdNoise = it
            redraw()
        }
        .set(thirdNoise)
}
redraw()
```

