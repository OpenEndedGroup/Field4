# Field Document: variable_width_strokes

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 5 - Thinning a tablet stroke

<!-- field-box id="swept-thinned" index="0" -->

### Narrative

From `code`

A tablet-like stroke: a point every 0.6 units, and pressure with a little sensor wobble.

```edn [field-box id="swept-thinned" role="properties"]
{"id" "swept-thinned" "index" 0 "name" "5 - Thinning a tablet stroke" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - Thinning a tablet stroke" "_tombstone" false "frame" #afield/rect [4620.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "swept-thinned_code_.kt" "lines" 37 "characters" 1341 "sha256" "40ca1a06b82fa5a7ce0155bf27c0f5c253be56fe82e0f044934ebc8b8e007281"}]}
```

```kotlin [field-property box="swept-thinned" name="code" path="swept-thinned_code_.kt"]
// A tablet-like stroke: a point every 0.6 units, and pressure with a little sensor wobble.
val rnd = java.util.Random(1)
val raw =
    (0 until 1000).map { i ->
        val s = i / 999.0
        val p = (0.15 + 0.85 * sin(PI * s).pow(0.7) + 0.03 * rnd.nextGaussian()).coerceIn(0.0, 1.0)
        vec(600 * s, 60 * sin(9 * s) + 0.1 * rnd.nextGaussian(), 12 * p.pow(1.75))
    }
val thin = thinPenSamples(raw, tolerance = 0.5)

val rawOutline = sweptCircleStroke(raw, samplesPerSegment = 4).shell
val thinOutline = sweptCircleStroke(thin).shell
println("raw: ${raw.size} samples, ${sweptCircleDegenerateSpans(raw).size} spans without an envelope")
println("thinned: ${thin.size} samples, ${sweptCircleDegenerateSpans(thin).size} spans without an envelope")

fun ink(f: FLine, dy: Double) = (f + vec(0.0, dy)).apply {
    filled = true
    stroked = false
    color = vec(0.95, 0.95, 0.9, 1.0)
}
fun dots(knots: List<Vec3>, dy: Double, size: Double) = knots.map { k ->
    fline {
        moveTo(k.x, k.y + dy)
        lineTo(k.x + 0.01, k.y + dy)
    }
        .apply {
            pointed = true
            pointSize = size
            color = vec(1.0, 0.4, 0.3, 1.0)
        }
}
lines(
    [ink(rawOutline, 0.0), ink(thinOutline, 170.0)] + dots(raw, 0.0, 0.5) + dots(thin, 170.0, 1.2),
    w = 900.0,
    h = 380.0,
    fitToSize = true,
)
```

## 2. 1 - Pill, trapezoid pill, swept spline

<!-- field-box id="swept-o" index="1" -->

### Narrative

From `code`

A quick hand-written "o": fourteen pen samples, pressure rising and falling.

```edn [field-box id="swept-o" role="properties"]
{"id" "swept-o" "index" 1 "name" "1 - Pill, trapezoid pill, swept spline" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Pill, trapezoid pill, swept spline" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "swept-o_code_.kt" "lines" 49 "characters" 1404 "sha256" "793076bc942d44ac4a5d6548b1bb084e28655baded6901436ea27a44e1b9491b"}]}
```

```kotlin [field-property box="swept-o" name="code" path="swept-o_code_.kt"]
// A quick hand-written "o": fourteen pen samples, pressure rising and falling.
val w = 20.0
val knots =
    (0..13).map { i ->
        val a = 2.2 + 2 * PI * i / 12.5
        val p = 0.3 + 0.7 * sin(PI * i / 13)
        val r = w * p.pow(1.75)
        vec(90 + 70 * cos(a) * (1 + 0.04 * i), 100 + 80 * sin(a), r)
    }

fun ink(f: FLine, dx: Double) = (f + vec(dx, 0.0)).apply {
    filled = true
    stroked = false
    color = vec(0.95, 0.95, 0.9, 1.0)
}

// pill: each span at its mean radius
val pills = knots.zipWithNext { a, b ->
    val m = (a.z + b.z) / 2
    sweptCircleStroke([vec(a.x, a.y, m), vec(b.x, b.y, m)]).shell
}
// trapezoid pill: each span straight, radius linear from end to end
val trapezoids = knots.zipWithNext { a, b -> sweptCircleStroke([a, b]).shell }
// swept spline: cubic Béziers in (x, y, r) through every sample
val spline = sweptCircleStroke(knots).shell

val samples = knots.map { k ->
    fline {
        moveTo(k.x, k.y)
        lineTo(k.x + 0.01, k.y)
    }
        .apply {
            pointed = true
            pointSize = 1.5
            color = vec(1.0, 0.4, 0.3, 1.0)
        }
}

lines(
    pills.map { ink(it, 0.0) } +
        trapezoids.map { ink(it, 220.0) } +
        ink(spline, 440.0) +
        samples +
        samples.map { it + vec(220.0, 0.0) } +
        samples.map { it + vec(440.0, 0.0) },
    w = 900.0,
    h = 300.0,
    fitToSize = true,
)
```

## 3. 6 - Sharp turns

<!-- field-box id="swept-corners" index="2" -->

### Narrative

From `code`

A "z" written quickly: a sample every 1.5 units along three strokes, pressing less as it goes.

```edn [field-box id="swept-corners" role="properties"]
{"id" "swept-corners" "index" 2 "name" "6 - Sharp turns" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 - Sharp turns" "_tombstone" false "frame" #afield/rect [5760.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "swept-corners_code_.kt" "lines" 23 "characters" 959 "sha256" "458ddae339b83e39b27f9db0313440f1b41c3dec7a8deec8eb223d70321f0ca9"}]}
```

```kotlin [field-property box="swept-corners" name="code" path="swept-corners_code_.kt"]
// A "z" written quickly: a sample every 1.5 units along three strokes, pressing less as it goes.
val turns = [vec(0.0, 0.0), vec(160.0, 0.0), vec(0.0, 140.0), vec(160.0, 140.0)]
val path =
    turns
        .zipWithNext { a, b ->
            val n = (a.distance(b) / 1.5).toInt()
            (0 until n).map { j -> a + (b - a) * (j.toDouble() / n) }
        }
        .flatten() + turns.last()
val raw = path.mapIndexed { i, p -> vec(p.x, p.y, 9.0 * (0.95 - 0.5 * i / path.size).pow(1.75)) }

val knots = thinPenSamples(raw)
val corners = penCorners(knots)
println("${raw.size} samples thin to ${knots.size}; corners at $corners")

fun ink(f: FLine, dx: Double) = (f + vec(dx, 0.0)).apply {
    filled = true
    stroked = false
    color = vec(0.95, 0.95, 0.9, 1.0)
}
val smooth = sweptCircleStroke(knots).shell
val sharp = sweptCircleStroke(knots, corners = corners).shell
lines(ink(smooth, 0.0), ink(sharp, 260.0), w = 900.0, h = 320.0, fitToSize = true)
```

## 4. 2 - Envelope of one cubic

<!-- field-box id="swept-cubic" index="3" -->

### Narrative

From `code`

One cubic whose radius swells from 2 to about 17 and back to 2: the envelope against the plain ribbon.

```edn [field-box id="swept-cubic" role="properties"]
{"id" "swept-cubic" "index" 3 "name" "2 - Envelope of one cubic" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Envelope of one cubic" "_tombstone" false "frame" #afield/rect [1200.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "swept-cubic_code_.kt" "lines" 48 "characters" 1639 "sha256" "1e923c621222588e98e37116800c2c1d799dc96e4a611203afbe5d0f93f64afd"}]}
```

```kotlin [field-property box="swept-cubic" name="code" path="swept-cubic_code_.kt"]
// One cubic whose radius swells from 2 to about 17 and back to 2: the envelope against the plain ribbon.
val centre = fline {
    moveTo(0, 0)
    cubicTo(40, 45, 80, -30, 120, 20)
}
val radius = parameterFromLambda { t, _ -> 2f + 26f * t * t * (1f - t) * 4f }

val swept = centre.bySweptCircleEnveloping(radius, samples = 200, flatness = 0.02)
val ribbon = centre.byVariableRibboning(parameterFromLambda { t, p -> 2f * radius.evaluate(t, p) }, samples = 200)
// reference: the union of 1500 discs along the line
val reference = centre.byJtsVariableBuffering(
    numSamples = 1500,
    distance = { t -> radius.evaluate(t.toFloat(), vec(0f, 0f, 0f)).toDouble() },
)

println("swept envelope: %.3f from the union of discs".format(swept.shell.outlineDistanceTo(reference)))
println("byVariableRibboning: %.3f".format(ribbon.outlineDistanceTo(reference)))

val circles =
    centre.sampleSeries(17).map { s ->
        val r = radius.evaluate(s.t, s.point).toDouble()
        fline { circle(s.point.x.toDouble(), s.point.y.toDouble(), r) }
            .apply {
                thickness = 0.8
                color = vec(1.0, 1.0, 1.0, 0.35)
            }
    }
val outlines: List<FLine> = [
    centre.copy().apply { color = vec(1.0, 1.0, 1.0, 0.5) },
    ribbon.copy().apply {
        color = vec(1.0, 0.45, 0.3, 1.0)
        thickness = 1.5
    },
    swept.left.copy().apply {
        color = vec(0.4, 0.9, 1.0, 1.0)
        thickness = 2.0
    },
    swept.right.copy().apply {
        color = vec(0.4, 0.9, 1.0, 1.0)
        thickness = 2.0
    },
]
lines(
    circles + outlines,
    w = 900.0,
    h = 380.0,
    fitToSize = true,
)
```

## 5. 4 - A pressure stroke from the drawing layer

<!-- field-box id="swept-notation" index="4" -->

### Narrative

From `code`

Write a pressure stroke onto the drawing layer, then read it back as pen samples.

```edn [field-box id="swept-notation" role="properties"]
{"id" "swept-notation" "index" 4 "name" "4 - A pressure stroke from the drawing layer" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - A pressure stroke from the drawing layer" "_tombstone" false "frame" #afield/rect [3480.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "swept-notation_code_.kt" "lines" 22 "characters" 752 "sha256" "7d8938f14699d0e4d9869db12a5c88fc1d7facba66f1cafafd1b8788adf059f8"}]}
```

```kotlin [field-property box="swept-notation" name="code" path="swept-notation_code_.kt"]
// Write a pressure stroke onto the drawing layer, then read it back as pen samples.
notation {
    stroke(
        (0..40).map { i -> vec(-300.0 + 9.0 * i, 420.0 + 40.0 * sin(i / 5.0)) },
        name = "ink",
        pressure = { 0.15 + 0.85 * sin(PI * it).pow(0.7) },
    )
}
val mark = notation.named("ink")!!
val knots = mark.points.zip(mark.pressures) { p, pressure -> vec(p.x, p.y, 12.0 * pressure.pow(1.75)) }
val outline = sweptCircleStroke(knots)
println("${knots.size} pen samples, ${sweptCircleDegenerateSpans(knots).size} spans without an envelope")
lines(
    outline.shell.copy().apply {
        filled = true
        stroked = false
        color = vec(0.95, 0.95, 0.9, 1.0)
    },
    w = 900.0,
    h = 260.0,
    fitToSize = true,
)
```

## 6. 3 - Where there is no envelope

<!-- field-box id="swept-degenerate" index="5" -->

### Narrative

From `code`

The pen nearly stops and presses: around the middle sample the radius changes faster than
the centre moves, and the circle swallows itself.

```edn [field-box id="swept-degenerate" role="properties"]
{"id" "swept-degenerate" "index" 5 "name" "3 - Where there is no envelope" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - Where there is no envelope" "_tombstone" false "frame" #afield/rect [2340.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "swept-degenerate_code_.kt" "lines" 37 "characters" 1117 "sha256" "0562d25a5340c81e9416da213c4019b5fdd3a9777554e89c2aae1b0b6530bb21"}]}
```

```kotlin [field-property box="swept-degenerate" name="code" path="swept-degenerate_code_.kt"]
// The pen nearly stops and presses: around the middle sample the radius changes faster than
// the centre moves, and the circle swallows itself.
val knots = [
    vec(0.0, 0.0, 4.0),
    vec(60.0, 20.0, 7.0),
    vec(66.0, 22.0, 30.0),
    vec(72.0, 20.0, 8.0),
    vec(150.0, 0.0, 4.0),
]
val spans = sweptCircleDegenerateSpans(knots)
println("no envelope on " + spans.joinToString { "%.4f..%.4f".format(it.start, it.endInclusive) })

val outline = sweptCircleStroke(knots)
val beziers = sweptCircleBeziers(knots)
val path = fline {
    moveTo(beziers[0][0].x, beziers[0][0].y)
    for (b in beziers) cubicTo(b[1].x, b[1].y, b[2].x, b[2].y, b[3].x, b[3].y)
}
lines(
    outline.shell.copy().apply {
        filled = true
        stroked = false
        color = vec(0.95, 0.95, 0.9, 0.35)
    },
    outline.left.copy().apply {
        color = vec(0.4, 0.9, 1.0, 1.0)
        thickness = 2.0
    },
    outline.right.copy().apply {
        color = vec(0.4, 0.9, 1.0, 1.0)
        thickness = 2.0
    },
    path.copy().apply { color = vec(1.0, 1.0, 1.0, 0.6) },
    w = 900.0,
    h = 320.0,
    fitToSize = true,
)
```

## 7. 7 - Outline strokes as they are drawn

<!-- field-box id="swept-onstroke" index="6" -->

### Narrative

From `code`

Outline every stroke drawn by hand from now on, at the width the pen pressed.

```edn [field-box id="swept-onstroke" role="properties"]
{"id" "swept-onstroke" "index" 6 "name" "7 - Outline strokes as they are drawn" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 - Outline strokes as they are drawn" "_tombstone" false "frame" #afield/rect [6900.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "swept-onstroke_code_.kt" "lines" 12 "characters" 454 "sha256" "eb63b33d0ec1715740bb13c51976d1b6157d42f26fbe01384e54b3dafa6a51c1"}]}
```

```kotlin [field-property box="swept-onstroke" name="code" path="swept-onstroke_code_.kt"]
// Outline every stroke drawn by hand from now on, at the width the pen pressed.
val inked = mutableListOf<FLine>()
notation.onStroke { mark ->
    val knots = thinPenSamples(mark.penKnots(10.0))
    inked +=
        sweptCircleStroke(knots, corners = penCorners(knots)).shell.apply {
            filled = true
            stroked = false
            color = vec(0.95, 0.95, 0.9, 1.0)
        }
    lines(inked, w = 900.0, h = 400.0, fitToSize = true)
}
```

