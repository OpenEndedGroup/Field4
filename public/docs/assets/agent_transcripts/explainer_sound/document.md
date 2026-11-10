# Field Document: explainer_sound

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 24 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. sine_sweep

<!-- field-box id="es_sine_sweep" index="0" -->

### Narrative

From `code`

One sine wave, drawn and heard. How often it repeats is its pitch; how far it swings, its loudness.

```edn [field-box id="es_sine_sweep" role="properties"]
{"id" "es_sine_sweep" "index" 0 "name" "sine_sweep" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "sine_sweep" "_tombstone" false "frame" #afield/rect [500.0 300.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_sine_sweep_code_.kt" "lines" 51 "characters" 1270 "sha256" "45a9bc32c1ce2ea48c8bde8593151e433432b30f07ed564d453af5633a7b3799"}]}
```

```kotlin [field-property box="es_sine_sweep" name="code" path="es_sine_sweep_code_.kt"]
// One sine wave, drawn and heard. How often it repeats is its pitch; how far it swings, its loudness.
val pitch = Param(220f)
val level = Param(0f)
var hz = 220.0
var amplitude = 0.8
var on = false

// Twenty milliseconds of the wave, 640 pixels wide.
// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 240.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun draw() = lines(
    frame,
    fline {
        moveTo(0.0, 120.0)
        for (x in 1..640) lineTo(x.toDouble(), 120.0 - 100.0 * amplitude * sin(2 * PI * hz * 0.02 * x / 640))
        thickness = 3.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    },
    channel = "wave",
    w = 640.0,
    h = 240.0,
)

fun update() {
    pitch.base = hz.toFloat()
    level.base = if (on) (0.15 * amplitude).toFloat() else 0f
    draw()
}

update()
controlGroup {
    slider("frequency", 20.0, 20000.0, step = 1.0, initialValue = 220.0, law = ControlLaw.LOGARITHMIC) {
        hz = it
        update()
    }
    slider("amplitude", 0.0, 1.0, step = 0.01, initialValue = 0.8) {
        amplitude = it
        update()
    }
    checkbox("sound") {
        on = it
        update()
    }
}

audio(SineOsc(pitch), gain = level)
```

## 2. microphone

<!-- field-box id="es_microphone" index="1" -->

### Narrative

From `code`

What the microphone hears, taken apart into sines. Tick "listen"; each bar is how much of one
frequency, 20 Hz wide, is in the last tenth of a second, from 0 to 2,500 Hz. A whistle stands up
as one bar; a sung vowel as a row of bars at whole multiples of the note. Nothing is played back.

```edn [field-box id="es_microphone" role="properties"]
{"id" "es_microphone" "index" 1 "name" "microphone" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "microphone" "_tombstone" false "frame" #afield/rect [300.0 380.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_microphone_code_.kt" "lines" 66 "characters" 2197 "sha256" "84d93be56e80b71c82a56d785e88adeb74edb89e023313c316f52d130e22cca5"}]}
```

```kotlin [field-property box="es_microphone" name="code" path="es_microphone_code_.kt"]
/*
What the microphone hears, taken apart into sines. Tick "listen"; each bar is how much of one
frequency, 20 Hz wide, is in the last tenth of a second, from 0 to 2,500 Hz. A whistle stands up
as one bar; a sung vowel as a row of bars at whole multiples of the note. Nothing is played back.
*/
var listening = false
val mic = audioIn.scope(4096)

fun bars(amps: List<Double>) {
    // Scaled to the loudest bar, but never so far that room noise fills the picture.
    val top = amps.maxOrNull()?.coerceAtLeast(2e-3) ?: 2e-3
    val shapes = amps.mapIndexed { i, a ->
        val h = 200 * (a / top).coerceIn(0.0, 1.0)
        fline {
            rect(i * 5.0, 220 - h, 4.0, h.coerceAtLeast(0.5))
            filled = true
            stroked = false
            color = vec(0.4, 0.9, 1.0, 1.0)
        }
    }
    // A tick every 500 Hz along the bottom.
    val ticks = fline {
        (0..5).forEach { k ->
            moveTo(k * 125.0, 224.0)
            lineTo(k * 125.0, 234.0)
        }
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val frame = fline {
        rect(0.0, 0.0, 640.0, 240.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.0)
    }
    lines([frame, ticks] + shapes, channel = "spectrum", w = 640.0, h = 240.0)
}

bars(List(128) { 0.0 })
controlGroup {
    checkbox("listen") { listening = it }
}

loop {
    if (listening) {
        val snap = mic.snapshot()
        val x = snap.left
        val n = x.size
        if (n > 0) {
            // A Hann window, then the strength of each 20 Hz step: a slow Fourier transform, 128 bins.
            val windowed = DoubleArray(n) { x[it] * (0.5 - 0.5 * cos(2 * PI * it / n)) }
            bars(
                List(128) { b ->
                    val f = (b + 0.5) * 20.0 / snap.sampleRate
                    var re = 0.0
                    var im = 0.0
                    for (i in 0 until n) {
                        re += windowed[i] * cos(2 * PI * f * i)
                        im -= windowed[i] * sin(2 * PI * f * i)
                    }
                    sqrt(re * re + im * im) / n
                },
            )
        }
    }
    delay(60)
} + audio(mic, gain = 0f)
```

## 3. triangle_params

<!-- field-box id="es_triangle_params" index="2" -->

### Narrative

From `code`

One repeating triangle and the three numbers that place it. The faint line is the same triangle
with no shift, for comparison; the sound follows amplitude and frequency.

```edn [field-box id="es_triangle_params" role="properties"]
{"id" "es_triangle_params" "index" 2 "name" "triangle_params" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "triangle_params" "_tombstone" false "frame" #afield/rect [500.0 220.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_triangle_params_code_.kt" "lines" 59 "characters" 1587 "sha256" "f6b6cc9d816120dfcba6e533fe30da2650ec7d745e6da95b1f846a381ad88650"}]}
```

```kotlin [field-property box="es_triangle_params" name="code" path="es_triangle_params_code_.kt"]
// One repeating triangle and the three numbers that place it. The faint line is the same triangle
// with no shift, for comparison; the sound follows amplitude and frequency.
var amplitude = 0.6
var hz = 220.0
var phase = 0.0
var on = false
val pitch = Param(220f)
val level = Param(0f)

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 240.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun triangle(t: Double) = 1 - 4 * abs(t - floor(t + 0.5))

fun wave(shift: Double, c: Vec4) = fline {
    moveTo(0.0, 120.0 - 100 * amplitude * triangle(-shift))
    for (x in 1..640) lineTo(x.toDouble(), 120.0 - 100 * amplitude * triangle(hz * 0.02 * x / 640 - shift))
    thickness = 3.0
    color = c
}

fun update() {
    pitch.base = hz.toFloat()
    level.base = if (on) (0.15 * amplitude).toFloat() else 0f
    lines(
        frame,
        wave(0.0, vec(1.0, 1.0, 1.0, 0.35)),
        wave(phase, vec(0.4, 0.9, 1.0, 1.0)),
        channel = "wave",
        w = 640.0,
        h = 240.0,
    )
}

update()
controlGroup {
    slider("amplitude", 0.0, 1.0, step = 0.01, initialValue = 0.6) {
        amplitude = it
        update()
    }
    slider("frequency", 55.0, 880.0, step = 1.0, initialValue = 220.0, law = ControlLaw.LOGARITHMIC) {
        hz = it
        update()
    }
    slider("phase", 0.0, 1.0, step = 0.01, initialValue = 0.0) {
        phase = it
        update()
    }
    checkbox("sound") {
        on = it
        update()
    }
}

audio(TriOsc(pitch), gain = level)
```

## 4. repeated_jumps

<!-- field-box id="es_repeated_jumps" index="3" -->

### Narrative

From `code`

A plate that jumps. Hold "jump" to move it forward by "distance"; tick "repeat" and it jumps back
and forth by itself, "rate" times a second. With "sound" on you hear the air the plate pushes.

```edn [field-box id="es_repeated_jumps" role="properties"]
{"id" "es_repeated_jumps" "index" 3 "name" "repeated_jumps" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "repeated_jumps" "_tombstone" false "frame" #afield/rect [900.0 140.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_repeated_jumps_code_.kt" "lines" 85 "characters" 2944 "sha256" "d97c59b21f6144fd7ebc6485db58226f01ff08575882cdff3a86d2048127c24e"}]}
```

```kotlin [field-property box="es_repeated_jumps" name="code" path="es_repeated_jumps_code_.kt"]
// A plate that jumps. Hold "jump" to move it forward by "distance"; tick "repeat" and it jumps back
// and forth by itself, "rate" times a second. With "sound" on you hear the air the plate pushes.
var distance = 0.5
var rate = 4.0
var held = false
var repeat = false
val level = Param(0f)
val swing = Param(0f) // how far the repeated motion goes
val offset = Param(0f) // where "jump" holds the plate
val pace = Param(4f)

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 300.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun position(t: Double) =
    (if (held) distance else 0.0) + (if (repeat) distance * (if ((t * rate) % 1.0 < 0.5) 1.0 else -1.0) else 0.0)

fun update() {
    swing.base = if (repeat) distance.toFloat() else 0f
    offset.base = if (held) distance.toFloat() else 0f
    pace.base = rate.toFloat()
}

// The plate, seen from the side, and a quarter of a second of its position below it.
fun draw(now: Double) {
    // Faster than a few jumps a second the eye cannot follow it, so the plate is drawn as a blur
    // across the whole of its travel, the way a fast-moving thing looks.
    val blur = repeat && rate > 6
    val x = if (blur) 120 - 60 * distance + (if (held) 60 * distance else 0.0) else 120 + 60 * position(now)
    val plate = fline {
        rect(x, 20.0, if (blur) 12 + 120 * distance else 12.0, 120.0)
        filled = true
        stroked = false
        color = vec(1.0, 0.6, 0.2, if (blur) 0.45 else 1.0)
    }
    val rest = fline {
        moveTo(126.0, 14.0)
        lineTo(126.0, 146.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val trace = fline {
        moveTo(0.0, 230.0 - 60 * position(now - 0.25))
        for (i in 1..640) lineTo(i.toDouble(), 230.0 - 60 * position(now - 0.25 + 0.25 * i / 640))
        thickness = 2.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    val axis = fline {
        moveTo(0.0, 230.0)
        lineTo(640.0, 230.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    lines(frame, plate, rest, axis, trace, channel = "plate", w = 640.0, h = 300.0)
}

draw(0.0)
controlGroup {
    keys("jump") { _, down ->
        held = down
        update()
    }
    slider("distance", 0.0, 1.0, step = 0.01, initialValue = 0.5) {
        distance = it
        update()
    }
    checkbox("repeat") {
        repeat = it
        update()
    }
    checkbox("sound") { level.base = if (it) 0.15f else 0f }
    slider("rate", 1.0, 440.0, step = 1.0, initialValue = 4.0, law = ControlLaw.LOGARITHMIC) {
        rate = it
        update()
    }
}

val start = AnimationClock.nowSeconds()
// A held offset makes no sound, only its changes do, so a high-pass filter takes the offset out.
loop { draw(AnimationClock.nowSeconds() - start) } +
    audio((SquareOsc(pace) * swing + offset) / hpf(15, 0.1), gain = level)
```

## 5. draw_wave

<!-- field-box id="es_draw_wave" index="4" -->

### Narrative

From `code`

Draw one cycle and hear it repeated. Drag the points of the curve, or draw a new stroke across the
box; the keys play whatever is drawn at three pitches. The bars are how much of each multiple of
the key's frequency the shape holds.

```edn [field-box id="es_draw_wave" role="properties"]
{"id" "es_draw_wave" "index" 4 "name" "draw_wave" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "draw_wave" "_tombstone" false "frame" #afield/rect [900.0 220.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_draw_wave_code_.kt" "lines" 107 "characters" 3574 "sha256" "ac679b43f2d9b4609cc540a193464d4876086b47b1a4a32eb53b36f440a6719e"}]}
```

```kotlin [field-property box="es_draw_wave" name="code" path="es_draw_wave_code_.kt"]
/*
Draw one cycle and hear it repeated. Drag the points of the curve, or draw a new stroke across the
box; the keys play whatever is drawn at three pitches. The bars are how much of each multiple of
the key's frequency the shape holds.
*/
val table = FloatArray(2048)
val gates = 3 { Gate() }
val voices = [196f, 247f, 294f].map { WavetableOsc(table, Param(it)) }

// A starting shape: a lopsided hump, as 25 draggable points across one cycle.
fun starter() = fline {
    moveTo(0.0, 140.0)
    for (i in 1..24) {
        val p = i / 24.0
        lineTo(640 * p, 140.0 - 90 * sin(2 * PI * p) - 30 * sin(4 * PI * p))
    }
    thickness = 2.5
    color = vec(0.4, 0.9, 1.0, 1.0)
}

// One cycle, read left to right, as samples between -1 and 1 with no offset.
fun samples(shape: FLine): FloatArray {
    val points = shape.nodes.map { it.to.x.toDouble() to it.to.y.toDouble() }.sortedBy { it.first }
    val raw =
        DoubleArray(table.size) { i ->
            val x = 640.0 * i / table.size
            val j = points.indexOfFirst { it.first >= x }
            when {
                j < 0 -> (140 - points.last().second) / 100
                j == 0 -> (140 - points.first().second) / 100
                else -> {
                    val (ax, ay) = points[j - 1]
                    val (bx, by) = points[j]
                    val t = if (bx > ax) (x - ax) / (bx - ax) else 0.0
                    (140 - (ay + (by - ay) * t)) / 100
                }
            }
        }
    val mean = raw.average()
    val peak = raw.maxOf { abs(it - mean) }.coerceAtLeast(1e-6)
    return FloatArray(raw.size) { ((raw[it] - mean) / peak).toFloat() }
}

// How much of each of the first 24 multiples of the base frequency is in the cycle.
fun partials() {
    val n = table.size
    val amps =
        (1..24).map { k ->
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                re += table[i] * cos(2 * PI * k * i / n)
                im -= table[i] * sin(2 * PI * k * i / n)
            }
            2 * sqrt(re * re + im * im) / n
        }
    val bars = amps.mapIndexed { i, a ->
        fline {
            rect(10.0 + i * 26.0, 150 - 140 * a.coerceAtMost(1.0), 18.0, 140 * a.coerceAtMost(1.0))
            filled = true
            stroked = false
            color = vec(1.0, 0.6, 0.2, 1.0)
        }
    }
    val axis = fline {
        moveTo(0.0, 150.0)
        lineTo(640.0, 150.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    lines(bars + axis, channel = "partials", w = 640.0, h = 160.0)
}

fun use(shape: FLine) {
    samples(shape).copyInto(table)
    partials()
}

fun show(shape: FLine) {
    val axis = fline {
        moveTo(0.0, 140.0)
        lineTo(640.0, 140.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    lines(axis, shape, channel = "wave", w = 640.0, h = 280.0, editable = true, drawing = true) { edited ->
        // A new stroke replaces the shape; a dragged point reshapes it.
        val latest = edited.last()
        use(latest)
        if (edited.size > 2)
            show(
                latest.copy().apply {
                    thickness = 2.5
                    color = vec(0.4, 0.9, 1.0, 1.0)
                },
            )
    }
}

val first = starter()
show(first)
use(first)
controlGroup {
    keys("low", "middle", "high", letters = "wer") { key, down -> if (down) gates[key].open() else gates[key].close() }
}

audio(3 { i -> voices[i] * adsr(0.01, 0.05, 1.0, 0.25, gates[i]) }.reduce { a, b -> a + b } / 3.0, gain = 0.15f)
```

## 6. beats

<!-- field-box id="es_beats" index="5" -->

### Narrative

From `code`

Two sine tones a few hertz apart. Hold one key, then both: the sum swells and fades as the two
drift in and out of step. The picture is one second of what you hear; each column spans the
wave's highest and lowest points in a 640th of a second, so the outline is the loudness.

```edn [field-box id="es_beats" role="properties"]
{"id" "es_beats" "index" 5 "name" "beats" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "beats" "_tombstone" false "frame" #afield/rect [700.0 300.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_beats_code_.kt" "lines" 58 "characters" 1916 "sha256" "0801c4f334cf3cf291eaac69f64efed884534848f4399399bc01ac83bce8fbd4"}]}
```

```kotlin [field-property box="es_beats" name="code" path="es_beats_code_.kt"]
/*
Two sine tones a few hertz apart. Hold one key, then both: the sum swells and fades as the two
drift in and out of step. The picture is one second of what you hear; each column spans the
wave's highest and lowest points in a 640th of a second, so the outline is the loudness.
*/
var difference = 3.0
val held = BooleanArray(2)
val gates = 2 { Gate() }
val second = Param(223f)

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 240.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun draw() {
    val f = [220.0, 220.0 + difference]
    val columns =
        (0 until 640).map { x ->
            val values =
                (0 until 24).map { k ->
                    val t = (x + k / 24.0) / 640
                    (0..1).sumOf { i -> if (held[i]) sin(2 * PI * f[i] * t) else 0.0 }
                }
            fline {
                moveTo(x.toDouble(), 120 - 50 * values.max())
                lineTo(x.toDouble(), 120 - 50 * values.min() + 0.5)
                thickness = 1.0
                color = vec(0.4, 0.9, 1.0, 1.0)
            }
        }
    val axis = fline {
        moveTo(0.0, 120.0)
        lineTo(640.0, 120.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    lines([frame, axis] + columns, channel = "sum", w = 640.0, h = 240.0)
}

draw()
controlGroup {
    keys("220 Hz", "220 Hz + difference", letters = "we") { key, down ->
        held[key] = down
        if (down) gates[key].open() else gates[key].close()
        draw()
    }
    slider("difference", 0.5, 20.0, step = 0.1, initialValue = 3.0) {
        difference = it
        second.base = (220 + it).toFloat()
        draw()
    }
}

val tones = [SineOsc(Param(220f)), SineOsc(second)]
audio(2 { i -> tones[i] * adsr(0.01, 0.05, 1.0, 0.25, gates[i]) }.reduce { a, b -> a + b } / 2.0, gain = 0.15f)
```

## 7. circle_sine

<!-- field-box id="es_circle_sine" index="6" -->

### Narrative

From `code`

A point going round a circle, and its height over time. The trace scrolls left, newest at the circle.

```edn [field-box id="es_circle_sine" role="properties"]
{"id" "es_circle_sine" "index" 6 "name" "circle_sine" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "circle_sine" "_tombstone" false "frame" #afield/rect [300.0 300.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_circle_sine_code_.kt" "lines" 78 "characters" 2305 "sha256" "ff9d85515d5269e2d31fadbdb69054444ebe279426e3e93d35868f196122dc9e"}]}
```

```kotlin [field-property box="es_circle_sine" name="code" path="es_circle_sine_code_.kt"]
// A point going round a circle, and its height over time. The trace scrolls left, newest at the circle.
var radius = 0.8
var speed = 0.5 // turns per second
var start = 0.0 // where the point starts, as a fraction of a turn
var angle = 0.0
val heights = mutableListOf<Double>()

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 280.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun draw() {
    val cx = 110.0
    val cy = 140.0
    val r = 100 * radius
    val turn = 2 * PI * (angle + start)
    val px = cx + r * cos(turn)
    val py = cy - r * sin(turn)
    val guide = vec(1.0, 1.0, 1.0, 0.35)
    val circle = fline {
        circle(cx, cy, r)
        thickness = 1.5
        color = guide
    }
    val spoke = fline {
        moveTo(cx, cy)
        lineTo(px, py)
        thickness = 1.5
        color = guide
    }
    val level = fline {
        moveTo(px, py)
        lineTo(240.0, py)
        thickness = 1.0
        color = guide
    }
    val dot = fline {
        circle(px, py, 6.0)
        filled = true
        stroked = false
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val axis = fline {
        moveTo(240.0, cy)
        lineTo(640.0, cy)
        thickness = 1.0
        color = guide
    }
    val trace = fline {
        heights.reversed().forEachIndexed { i, h -> if (i == 0) moveTo(240.0, cy - h) else lineTo(240.0 + i, cy - h) }
        thickness = 3.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    lines(frame, axis, circle, spoke, level, trace, dot, channel = "circle", w = 640.0, h = 280.0)
}

draw()
controlGroup {
    slider("radius", 0.1, 1.2, step = 0.01, initialValue = 0.8) { radius = it }
    slider("turns per second", 0.1, 2.0, step = 0.01, initialValue = 0.5) { speed = it }
    slider("start", 0.0, 1.0, step = 0.01, initialValue = 0.0) { start = it }
}

// The trace takes one sample every sixtieth of a second, however unevenly frames arrive.
var clock = AnimationClock.nowSeconds()
loop {
    val now = AnimationClock.nowSeconds()
    while (clock < now) {
        clock += 1 / 60.0
        angle += speed / 60
        heights += 100 * radius * sin(2 * PI * (angle + start))
    }
    while (heights.size > 400) heights.removeAt(0)
    draw()
}
```

## 8. wall_plate

<!-- field-box id="es_wall_plate" index="7" -->

```edn [field-box id="es_wall_plate" role="properties"]
{"id" "es_wall_plate" "index" 7 "name" "wall_plate" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "wall_plate" "_tombstone" false "frame" #afield/rect [300.0 460.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_wall_plate_code_.kt" "lines" 84 "characters" 3177 "sha256" "5f51cb3fe2e5259313ea8857b20716bd16109addd26e8ea9a4a28f403fe87bf5"}]}
```

```kotlin [field-property box="es_wall_plate" name="code" path="es_wall_plate_code_.kt"]
import afield.media.image.util.PngEncoder

/*
A plate 60 cm across set in a wall on the left, pushing air back and forth: red is pressure above
the usual, blue below. When its waves are longer than the plate the sound spreads every way; when
they are shorter, the edges of the plate interfere and the sound is aimed ahead. The strip below
is the pressure along the plate's axis. The picture is four metres across, the bar is one metre;
time runs slowed, half a cycle a second, whatever the frequency.
*/
val imageW = 640
val imageH = 300
val fieldH = 228 // the pressure field; below it, a strip plotting the axis
val metre = 160.0
val cell = 2 // the field is solved on a 2 × 2 pixel grid and drawn up
val gw = imageW / cell
val gh = fieldH / cell
val px = IntArray(imageW * imageH)
var hz = 400.0
var phase = 0.0
// Each grid point's swing as a complex amplitude (re, im), worked out once per frequency.
val re = FloatArray(gw * gh)
val im = FloatArray(gw * gh)

fun solve() {
    val k = 2 * PI * hz / 343
    val sources = 16
    for (j in 0 until gh) for (i in 0 until gw) {
        var a = 0.0
        var b = 0.0
        for (s in 0 until sources) {
            val sy = fieldH / 2.0 + metre * 0.6 * ((s + 0.5) / sources - 0.5)
            val r = sqrt((cell * i + 2.0).pow(2) + (cell * j - sy).pow(2)) / metre
            a += cos(k * r) / sqrt(r + 0.05)
            b += sin(k * r) / sqrt(r + 0.05)
        }
        re[j * gw + i] = (a / sources).toFloat()
        im[j * gw + i] = (b / sources).toFloat()
    }
}

fun paint() {
    val c = cos(2 * PI * phase)
    val s = sin(2 * PI * phase)
    px.fill(0xff000000.toInt())
    for (j in 0 until fieldH) for (i in 0 until imageW) {
        val n = (j / cell) * gw + i / cell
        val p = tanh(0.9 * (re[n] * c + im[n] * s))
        val red = (255 * p.coerceAtLeast(0.0)).toInt()
        val blue = (255 * (-p).coerceAtLeast(0.0)).toInt()
        px[j * imageW + i] = (0xff shl 24) or (red shl 16) or (((red + blue) / 4) shl 8) or blue
    }
    for (j in fieldH / 2 - 48..fieldH / 2 + 48) for (i in 0..3) px[j * imageW + i] = -1
    for (j in fieldH - 8..fieldH - 6) for (i in 470 until 630) px[j * imageW + i] = -1
    // The strip: the pressure along the plate's axis, now, in cyan about a faint middle line.
    val mid = fieldH + 36
    for (i in 0 until imageW) px[mid * imageW + i] = 0xff444444.toInt()
    var last = mid
    for (i in 0 until imageW) {
        val n = (fieldH / 2 / cell) * gw + i / cell
        val y = (mid - 30 * tanh(0.9 * (re[n] * c + im[n] * s))).toInt()
        for (j in minOf(last, y) - 1..maxOf(last, y) + 1) px[j * imageW + i] = 0xff66e6ff.toInt()
        last = y
    }
    display("field", PngEncoder.encode(px, imageW, imageH))
}

solve()
paint()
controlGroup {
    slider("frequency", 150.0, 3000.0, step = 1.0, initialValue = 400.0, law = ControlLaw.LOGARITHMIC) {
        hz = it
        solve()
    }
}

var clock = AnimationClock.nowSeconds()
loop {
    val now = AnimationClock.nowSeconds()
    phase += 0.5 * (now - clock)
    clock = now
    paint()
    // A picture this size is a large message; about twelve a second is what the page keeps up with.
    delay(80)
}
```

## 9. square_partials

<!-- field-box id="es_square_partials" index="8" -->

### Narrative

From `code`

Sines at 1, 3, 5, 7… times a base frequency, each 1/n as tall, add up to a square wave. "sines" is
how many are in the sum; the faint line is the square they are approaching.

```edn [field-box id="es_square_partials" role="properties"]
{"id" "es_square_partials" "index" 8 "name" "square_partials" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "square_partials" "_tombstone" false "frame" #afield/rect [900.0 300.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_square_partials_code_.kt" "lines" 52 "characters" 1715 "sha256" "a9358377af20ad4baea48c019947ea6f1819179301867a5e0a09712b7650daa6"}]}
```

```kotlin [field-property box="es_square_partials" name="code" path="es_square_partials_code_.kt"]
// Sines at 1, 3, 5, 7… times a base frequency, each 1/n as tall, add up to a square wave. "sines" is
// how many are in the sum; the faint line is the square they are approaching.
var count = 1
var on = false
val level = Param(0f)
val weights = 50 { Param(0f) }

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 260.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun partial(i: Int) = 2 * i + 1 // 1, 3, 5, …
fun sum(t: Double) = (0 until count).sumOf { i -> 4 / PI / partial(i) * sin(2 * PI * partial(i) * t) }
fun square(t: Double) = if (t - floor(t) < 0.5) 1.0 else -1.0

fun update() {
    weights.forEachIndexed { i, w -> w.base = if (i < count) (4 / PI / partial(i)).toFloat() else 0f }
    level.base = if (on) 0.12f else 0f
    // Two cycles across the figure.
    fun wave(f: (Double) -> Double, c: Vec4, width: Double) = fline {
        moveTo(0.0, 130 - 90 * f(0.0))
        for (x in 1..640) lineTo(x.toDouble(), 130 - 90 * f(2.0 * x / 640))
        thickness = width
        color = c
    }
    lines(
        frame,
        wave(::square, vec(1.0, 1.0, 1.0, 0.35), 1.5),
        wave(::sum, vec(0.4, 0.9, 1.0, 1.0), 3.0),
        channel = "wave",
        w = 640.0,
        h = 260.0,
    )
}

update()
controlGroup {
    slider("sines", 1.0, 50.0, step = 1.0, initialValue = 1.0) {
        count = it.toInt()
        update()
    }
    checkbox("sound") {
        on = it
        update()
    }
}

// One oscillator per odd multiple of 110 Hz, each at the weight the sum gives it.
audio(50 { i -> SineOsc(Param(110f * partial(i))) * weights[i] }.reduce { a, b -> a + b } / 1.25, gain = level)
```

## 10. wave_synth

<!-- field-box id="es_wave_synth" index="9" -->

### Narrative

From `code`

Three keys, each a repeating pattern of the plate, and a fourth that plays the sum of the first two
as one pattern of its own. Each row is twenty milliseconds of a key;
the bottom row, below the line, is what you hear.
An invisible frame the size of the figure, so its bounds, and so its scale, never change.

```edn [field-box id="es_wave_synth" role="properties"]
{"id" "es_wave_synth" "index" 9 "name" "wave_synth" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "wave_synth" "_tombstone" false "frame" #afield/rect [300.0 220.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_wave_synth_code_.kt" "lines" 76 "characters" 2518 "sha256" "ff0973e3b0fb7dbe1ce5e9ea5432d9782d99afba8be5d8ebdd28a030e15b922f"}]}
```

```kotlin [field-property box="es_wave_synth" name="code" path="es_wave_synth_code_.kt"]
/*
Three keys, each a repeating pattern of the plate, and a fourth that plays the sum of the first two
as one pattern of its own. Each row is twenty milliseconds of a key;
the bottom row, below the line, is what you hear.
An invisible frame the size of the figure, so its bounds, and so its scale, never change.
*/
val frame = fline {
    rect(0.0, 0.0, 640.0, 300.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun square(t: Double) = if (t - floor(t) < 0.5) 1.0 else -1.0
fun triangle(t: Double) = 1 - 4 * abs(t - floor(t + 0.5))
fun saw(t: Double) = 2 * (t - floor(t + 0.5))

val patterns = listOf<(Double) -> Double>(
    { t -> 0.5 * square(220 * t) },
    { t -> 0.5 * triangle(330 * t) },
    { t -> 0.5 * saw(275 * t) },
    { t -> 0.5 * square(220 * t) + 0.5 * triangle(330 * t) },
)
val names = ["A", "B", "C", "A + B"]

// The fourth key reads one cycle of A + B from a table: 220 and 330 Hz repeat together 110 times a second.
val table = FloatArray(2048) { patterns[3](it / 2048.0 / 110).toFloat() }
val voices = listOf<Signal>(
    SquareOsc(Param(220f)) * 0.5,
    TriOsc(Param(330f)) * 0.5,
    SawOsc(Param(275f)) * 0.5,
    WavetableOsc(table, Param(110f)),
)
val gates = 4 { Gate() }
val held = BooleanArray(4)

fun row(y: Double, f: (Double) -> Double, c: Vec4, scale: Double, width: Double) = fline {
    fun at(x: Int) = (y - scale * f(0.02 * x / 640)).coerceIn(2.0, 298.0)
    moveTo(0.0, at(0))
    for (x in 1..640) lineTo(x.toDouble(), at(x))
    thickness = width
    color = c
}

fun draw() {
    val cyan = vec(0.4, 0.9, 1.0, 1.0)
    val idle = vec(0.4, 0.9, 1.0, 0.35)
    val rows = 4 { i ->
        row(
            24.0 + 44 * i,
            patterns[i],
            if (held[i]) cyan else idle,
            if (i == 3) 19.0 else 38.0,
            if (held[i]) 3.0 else 2.0,
        )
    }
    val sum =
        row(250.0, { t -> (0..3).sumOf { if (held[it]) patterns[it](t) else 0.0 } }, vec(1.0, 0.6, 0.2, 1.0), 40.0, 3.0)
    val rule = fline {
        moveTo(0.0, 198.0)
        lineTo(640.0, 198.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.3)
    }
    lines([frame] + rows + rule + sum, channel = "patterns", w = 640.0, h = 300.0)
}

draw()
controlGroup {
    keys(*names.toTypedArray(), letters = "asdf") { key, down ->
        held[key] = down
        if (down) gates[key].open() else gates[key].close()
        draw()
    }
}

audio(4 { i -> voices[i] * adsr(0.01, 0.05, 1.0, 0.2, gates[i]) }.reduce { a, b -> a + b } / 2.0, gain = 0.15f)
```

## 11. tube_plate

<!-- field-box id="es_tube_plate" index="10" -->

### Narrative

From `code`

Slices of air in a tube. Each line is one slice; the plate at the left end pushes on the first,
each slice pushes on the next, and the far end lets the disturbance out instead of echoing it.

```edn [field-box id="es_tube_plate" role="properties"]
{"id" "es_tube_plate" "index" 10 "name" "tube_plate" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tube_plate" "_tombstone" false "frame" #afield/rect [700.0 140.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_tube_plate_code_.kt" "lines" 80 "characters" 2778 "sha256" "d69523e98d5ef9cb04456685ea2637912a52e304af414b41789ebaacb5677646"}]}
```

```kotlin [field-property box="es_tube_plate" name="code" path="es_tube_plate_code_.kt"]
// Slices of air in a tube. Each line is one slice; the plate at the left end pushes on the first,
// each slice pushes on the next, and the far end lets the disturbance out instead of echoing it.
val n = 80
val gap = 7.0 // pixels between slices at rest
val u = DoubleArray(n) // how far each slice has moved from rest, in pixels
val v = DoubleArray(n)
var target = 0.0
var plate = 0.0
var colour = false

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 260.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun draw() {
    val slices =
        (1 until n - 1).map { i ->
            val x = 50 + i * gap + u[i]
            // Squeezed where neighbours are closer than usual, stretched where they are further apart.
            val strain = (u[i + 1] - u[i - 1]) / (2 * gap)
            val k = (abs(strain) * 4).coerceAtMost(1.0)
            fline {
                moveTo(x, 60.0)
                lineTo(x, 200.0)
                thickness = 2.0
                color = when {
                    !colour -> vec(1.0, 1.0, 1.0, 0.6)
                    strain < 0 -> vec(0.55 + 0.45 * k, 0.55 - 0.35 * k, 0.55 - 0.4 * k, 1.0)
                    else -> vec(0.55 - 0.4 * k, 0.55 - 0.1 * k, 0.55 + 0.45 * k, 1.0)
                }
            }
        }
    val walls = fline {
        moveTo(30.0, 50.0)
        lineTo(630.0, 50.0)
        moveTo(30.0, 210.0)
        lineTo(630.0, 210.0)
        thickness = 1.5
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val face = fline {
        rect(36 + plate, 52.0, 10.0, 156.0)
        filled = true
        stroked = false
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines([frame] + slices + walls + face, channel = "tube", w = 640.0, h = 260.0)
}

draw()
controlGroup {
    slider("plate", -12.0, 12.0, step = 0.1, initialValue = 0.0) { target = it }
    checkbox("colour") { colour = it }
}

// One step of the air each sixtieth of a second of animation time, however often frames arrive.
var clock = AnimationClock.nowSeconds()
loop {
    val now = AnimationClock.nowSeconds()
    var caught = 0
    while (clock < now && caught < 6) {
        clock += 1 / 60.0
        caught++
        // The plate follows the slider at no more than a slice's comfortable pace.
        plate += (target - plate).coerceIn(-1.5, 1.5)
        u[0] = plate
        for (i in 1 until n - 1) {
            v[i] += 0.25 * (u[i - 1] - 2 * u[i] + u[i + 1])
            // The last twenty slices soak the wave up, as if the tube went on.
            if (i > n - 22) v[i] *= 1 - 0.012 * (i - (n - 22))
        }
        for (i in 1 until n - 1) u[i] += v[i]
        u[n - 1] = u[n - 2]
    }
    if (clock < now) clock = now
    draw()
}
```

## 12. normal_modes

<!-- field-box id="es_normal_modes" index="11" -->

### Narrative

From `code`

Masses on a line of springs between two walls, moving in one of their normal modes. "mode"
chooses which (a line of n masses has n), and "add the lowest" mixes mode 1 in as well.

```edn [field-box id="es_normal_modes" role="properties"]
{"id" "es_normal_modes" "index" 11 "name" "normal_modes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "normal_modes" "_tombstone" false "frame" #afield/rect [500.0 380.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_normal_modes_code_.kt" "lines" 77 "characters" 2515 "sha256" "ef5869c0c9450d24af87414cd035e6da4f17786ec173357dd2762d4573eab0b8"}]}
```

```kotlin [field-property box="es_normal_modes" name="code" path="es_normal_modes_code_.kt"]
// Masses on a line of springs between two walls, moving in one of their normal modes. "mode"
// chooses which (a line of n masses has n), and "add the lowest" mixes mode 1 in as well.
var masses = 1
var mode = 1
var addLowest = false
var time = 0.0

// Mode k of n masses: mass j moves as sin(k π j / (n + 1)), at a frequency that rises with k.
fun shape(k: Int, j: Int) = sin(k * PI * j / (masses + 1))
fun rate(k: Int) = 2 * sin(k * PI / (2 * (masses + 1)))

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 300.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun spring(x0: Double, y0: Double, x1: Double, y1: Double) = fline {
    moveTo(x0, y0)
    for (i in 1..13) {
        val t = i / 14.0
        lineTo(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t + if (i % 2 == 0) 10.0 else -10.0)
    }
    lineTo(x1, y1)
    thickness = 2.0
    color = vec(1.0, 1.0, 1.0, 0.6)
}

fun draw() {
    val k = mode.coerceAtMost(masses)
    val mix = addLowest && k != 1
    val xs = (0..masses + 1).map { 8.0 + 624.0 * it / (masses + 1) }
    val ys =
        (0..masses + 1).map { j ->
            if (j == 0 || j == masses + 1) 150.0
            else {
                val main = shape(k, j) * cos(2 * PI * 0.6 * rate(k) * time)
                val low = if (mix) shape(1, j) * cos(2 * PI * 0.6 * rate(1) * time) else 0.0
                150 - 115 * (if (mix) (main + low) / 2 else main)
            }
        }
    val walls = fline {
        moveTo(8.0, 10.0)
        lineTo(8.0, 290.0)
        moveTo(632.0, 10.0)
        lineTo(632.0, 290.0)
        thickness = 4.0
        color = vec(1.0, 1.0, 1.0, 0.5)
    }
    val springs = (0..masses).map { spring(xs[it], ys[it], xs[it + 1], ys[it + 1]) }
    val balls =
        (1..masses).map { j ->
            fline {
                circle(xs[j], ys[j], 22.0)
                filled = true
                stroked = false
                color = vec(1.0, 0.6, 0.2, 1.0)
            }
        }
    lines([frame, walls] + springs + balls, channel = "springs", w = 640.0, h = 300.0)
}

draw()
controlGroup {
    slider("masses", 1.0, 5.0, step = 1.0, initialValue = 1.0) { masses = it.toInt() }
    slider("mode", 1.0, 5.0, step = 1.0, initialValue = 1.0) { mode = it.toInt() }
    checkbox("add the lowest") { addLowest = it }
}

var clock = AnimationClock.nowSeconds()
loop {
    val now = AnimationClock.nowSeconds()
    time += now - clock
    clock = now
    draw()
}
```

## 13. doppler

<!-- field-box id="es_doppler" index="12" -->

### Narrative

From `code`

A source moving left to right past a listener (orange), sending out rings that spread at the
speed of sound. Time is slowed a hundred times, so a ring grows by a fraction of the gap between
rings in each painted frame even at fifteen frames a second. Ahead of the source the rings crowd together, behind
it they spread out. With "sound" on you hear a 440 Hz tone at the pitch the listener receives.

```edn [field-box id="es_doppler" role="properties"]
{"id" "es_doppler" "index" 12 "name" "doppler" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "doppler" "_tombstone" false "frame" #afield/rect [900.0 460.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_doppler_code_.kt" "lines" 95 "characters" 3123 "sha256" "ac31582bccfcf5314d40a6195117102dc18883054648a75812235871a6ee3c62"}]}
```

```kotlin [field-property box="es_doppler" name="code" path="es_doppler_code_.kt"]
/*
A source moving left to right past a listener (orange), sending out rings that spread at the
speed of sound. Time is slowed a hundred times, so a ring grows by a fraction of the gap between
rings in each painted frame even at fifteen frames a second. Ahead of the source the rings crowd together, behind
it they spread out. With "sound" on you hear a 440 Hz tone at the pitch the listener receives.
*/
val metre = 64.0
val c = 343.0
val slow = 100.0 // picture time runs this many times slower than real time
val every = 0.27 // seconds of picture time between rings
var speed = 150.0 // metres per second
var on = false
var time = 0.0
val emitted = mutableListOf<Pair<Double, Double>>() // (time, x in metres) of each ring
var x = 0.0
var nextRing = 0.0
val pitch = Param(440f)
val level = Param(0f)
val listener = Pair(6.0, -2.5) // metres: right of centre, below the path

fun ring(cx: Double, cy: Double, r: Double) = fline {
    var drawing = false
    for (i in 0..72) {
        val a = 2 * PI * i / 72
        val px = cx + r * cos(a)
        val py = cy + r * sin(a)
        val inside = px in 0.0..640.0 && py in 0.0..300.0
        if (inside && drawing) lineTo(px, py) else if (inside) moveTo(px, py)
        drawing = inside
    }
}

fun step(dt: Double) {
    time += dt
    x += speed / slow * dt
    if (x > 10.5) {
        x = -0.5
        emitted.clear()
        nextRing = time
    }
    // A ring every few tenths of a second of picture time, placed where the source was when it left.
    while (nextRing <= time) {
        emitted += nextRing to x - speed / slow * (time - nextRing)
        nextRing += every
    }
    emitted.removeAll { (t0, _) -> c / slow * (time - t0) * metre > 800 }
    // The pitch heard now, from the source's motion towards the listener.
    val dx = listener.first - x
    val dy = listener.second
    val towards = speed * dx / sqrt(dx * dx + dy * dy)
    pitch.base = (440 * c / (c - towards.coerceAtMost(0.95 * c))).toFloat()
    level.base = if (on) 0.15f else 0f
}

fun draw() {
    val y = 130.0
    val rings = emitted.map { (t0, x0) ->
        ring(metre * x0, y, c / slow * (time - t0) * metre).apply {
            thickness = 1.5
            color = vec(0.4, 0.9, 1.0, 0.8)
        }
    }
    val source = fline {
        circle(metre * x, y, 8.0)
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.9)
    }
    val ear = fline {
        circle(metre * listener.first, y - metre * listener.second, 9.0)
        filled = true
        stroked = false
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val frame = fline {
        rect(0.0, 0.0, 640.0, 300.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.0)
    }
    lines([frame] + rings + source + ear, channel = "street", w = 640.0, h = 300.0)
}

draw()
controlGroup {
    slider("speed", 0.0, 300.0, step = 1.0, initialValue = 150.0) { speed = it }
    checkbox("sound") { on = it }
}

var clock = AnimationClock.nowSeconds()
loop {
    val now = AnimationClock.nowSeconds()
    step(now - clock)
    clock = now
    draw()
} + audio(SineOsc(pitch), gain = level)
```

## 14. reverb

<!-- field-box id="es_reverb" index="13" -->

### Narrative

From `code`

A room seen from above, and when its echoes reach the listener (orange) after a sound from the
source (white): each tick is one path off the walls, its height how much of the sound is left.
A bigger room spaces them out and stretches the tail; softer walls make each bounce lose more.
The keys play short notes through a reverberator set from the same two sliders.

```edn [field-box id="es_reverb" role="properties"]
{"id" "es_reverb" "index" 13 "name" "reverb" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "reverb" "_tombstone" false "frame" #afield/rect [700.0 540.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_reverb_code_.kt" "lines" 87 "characters" 3125 "sha256" "1035b8523b07144467bddfcdb7087a3e715672626bd3787d5a4468b05f1e7e8d"}]}
```

```kotlin [field-property box="es_reverb" name="code" path="es_reverb_code_.kt"]
/*
A room seen from above, and when its echoes reach the listener (orange) after a sound from the
source (white): each tick is one path off the walls, its height how much of the sound is left.
A bigger room spaces them out and stretches the tail; softer walls make each bounce lose more.
The keys play short notes through a reverberator set from the same two sliders.
*/
var size = 0.4
var soft = 0.3
val gates = 3 { Gate() }
val roomSize = Param(0.6f)
val damping = Param(0.4f)

fun draw() {
    val w = 4 + 16 * size // metres
    val h = 0.6 * w
    val reflect = 1 - 0.6 * soft
    val sx = 0.3 * w
    val sy = 0.45 * h
    val lx = 0.7 * w
    val ly = 0.55 * h
    // Mirror the room in its walls again and again: each mirrored source is one bouncing path.
    val direct = sqrt((sx - lx) * (sx - lx) + (sy - ly) * (sy - ly))
    val ticks = mutableListOf<FLine>()
    for (i in -12..12) for (j in -12..12) {
        val ix = i * w + if (i % 2 == 0) sx else w - sx
        val iy = j * h + if (j % 2 == 0) sy else h - sy
        val d = sqrt((ix - lx) * (ix - lx) + (iy - ly) * (iy - ly))
        val t = d / 343
        if (t > 0.6) continue
        // What is left of the sound, against the direct path; drawn as a square root so the tail shows.
        val a = reflect.pow(abs(i) + abs(j)) * direct / d
        ticks += fline {
            moveTo(20 + 1000 * t, 270.0)
            lineTo(20 + 1000 * t, 270 - 100 * sqrt(a))
            thickness = 1.5
            color = vec(0.4, 0.9, 1.0, 1.0)
        }
    }
    val scale = 7.0 // the plan: pixels per metre
    val plan = fline {
        rect(20.0, 10.0, scale * w, scale * h)
        thickness = 2.0
        color = vec(1.0, 1.0, 1.0, 0.35 + 0.5 * (1 - soft))
    }
    val source = fline {
        circle(20 + scale * sx, 10 + scale * sy, 4.0)
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.9)
    }
    val ear = fline {
        circle(20 + scale * lx, 10 + scale * ly, 4.0)
        filled = true
        stroked = false
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val axis = fline {
        moveTo(20.0, 270.0)
        lineTo(620.0, 270.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val frame = fline {
        rect(0.0, 0.0, 640.0, 280.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.0)
    }
    lines([frame, plan, source, ear, axis] + ticks, channel = "room", w = 640.0, h = 280.0)
    roomSize.base = (0.5 + 0.48 * size).toFloat()
    damping.base = (0.2 + 0.7 * soft).toFloat()
}

draw()
controlGroup {
    keys("low", "middle", "high", letters = "wer") { key, down -> if (down) gates[key].open() else gates[key].close() }
    slider("room size", 0.0, 1.0, step = 0.01, initialValue = 0.4) {
        size = it
        draw()
    }
    slider("soft walls", 0.0, 1.0, step = 0.01, initialValue = 0.3) {
        soft = it
        draw()
    }
}

val notes = [196f, 247f, 294f].mapIndexed { i, hz -> SawOsc(Param(hz)) / lpf(1800) * perc(0.25, 0.005, gates[i]) }
audio(notes.reduce { a, b -> a + b } / 2.0 / FreeVerb(roomSize, damping, Param(0.45f)), gain = 0.15f)
```

## 15. deck

<!-- field-box id="es_deck" index="14" -->

```edn [field-box id="es_deck" role="properties"]
{"id" "es_deck" "index" 14 "name" "deck" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "deck" "_tombstone" false "frame" #afield/rect [60.0 140.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_deck_code_.kt" "lines" 630 "characters" 27365 "sha256" "1555f6987dc48caa2b9c9e660bca6e36575f06af8bc75837bffd4a10b909a4e4"}]}
```

```kotlin [field-property box="es_deck" name="code" path="es_deck_code_.kt"]
import kotlinx.html.*

/*
The talk. Each figure is a box of the same name in this document; the deck starts a figure
when its slide comes up and stops it when the talk moves on. Nothing sounds until someone
holds a key or ticks "sound".
*/

presentation(title = "Sound") {
        section("Sound") {
            step("Sound", id = "opening") {
                audience {
                    +"""
                    # Sound

                    Everything we hear is air pressing on our ears a little harder, then a little
                    softer, many times a second.

                    This talk builds that idea up from molecules: how air carries a push, how a
                    repeated push becomes a note, how notes combine, how strings make them, and how
                    sound spreads through a room.
                """
                        .md
                    p(classes = "muted") {
                        +"After “Sound” by Bartosz Ciechanowski, ciechanow.ski/sound. Words and figures made in Field."
                    }
                }
                markdownNote(
                    "Every figure in this talk is a live box. Sound starts only when someone holds a key or ticks **sound**. [[next]]",
                )
            }
            step("Three keys", id = "keyboard") {
                audience {
                    figureBeside("keyboard") {
                        +"""
                        ## Three keys

                        Hold a key. Each moves the air in its own repeating pattern.
                    """
                            .md
                    }
                }
                markdownNote("Hold a key with the mouse, or click the figure and use W, E and R. [[back]] [[next]]")
            }
        }
        section("Air") {
            step("Air is a crowd", id = "air-crowd") {
                audience {
                    figureBeside("pressure_box") {
                        +"""
                        ## Air is a crowd

                        Air is molecules flying at about 500 m/s until they
                        hit something. This is a flat cartoon of a few
                        hundred.
                    """
                            .md
                    }
                }
                control("pressure_box", "length", 1.0)
                control("pressure_box", "follow one", false)
                markdownNote("Drag **speed** to slow time down. [[back]] [[next]]")
            }
            step("Pressure is hits on a wall", id = "air-pressure") {
                audience {
                    figureBeside("pressure_box") {
                        +"""
                        ## Pressure is hits on a wall

                        Each bounce pushes the wall. Pressure is how much
                        push each piece of wall gets: the plot.
                    """
                            .md
                    }
                }
                control("pressure_box", "length", 1.0)
                control("pressure_box", "follow one", false)
                markdownNote("The plot is hits per unit of wall per unit of time. [[back]] [[next]]")
            }
            step("Squeeze it", id = "air-squeeze") {
                audience {
                    figureBeside("pressure_box") {
                        +"""
                        ## Squeeze it

                        Half the length, twice the hits: twice the pressure.
                    """
                            .md
                    }
                }
                control("pressure_box", "length", 1.0)
                control("pressure_box", "follow one", false)
                cue("Halve the box") { control("pressure_box", "length", 0.5, glideMs = 2000) }
                markdownNote("Next squeezes the box to half its length over two seconds. [[back]] [[next]]")
            }
            step("Nobody goes far", id = "air-one-molecule") {
                audience {
                    figureBeside("pressure_box") {
                        +"""
                        ## Nobody goes far

                        One molecule, knocked about, barely leaves home. A
                        push travels by being handed on.
                    """
                            .md
                    }
                }
                control("pressure_box", "length", 1.0)
                control("pressure_box", "follow one", true)
                markdownNote("The orange trail is one molecule's path. [[back]] [[next]]")
            }
            step("A plate in a tube", id = "air-tube") {
                audience {
                    figureBeside("tube_plate") {
                        +"""
                        ## A plate in a tube

                        Each line is a slice of air. Push the plate: the
                        crowding runs down the tube; the slices only
                        shuffle.
                    """
                            .md
                    }
                }
                control("tube_plate", "plate", 0.0)
                control("tube_plate", "colour", false)
                cue("Push") { control("tube_plate", "plate", 12.0, glideMs = 300) }
                markdownNote("Next pushes the plate; or drag **plate** quickly one way, then back. [[back]] [[next]]")
            }
            step("Squeezed and stretched", id = "air-compression") {
                audience {
                    figureBeside("tube_plate") {
                        +"""
                        ## Squeezed and stretched

                        Red is squeezed, blue stretched. Either travels at
                        the speed of sound, about 343 m/s.
                    """
                            .md
                    }
                }
                control("tube_plate", "plate", 0.0)
                control("tube_plate", "colour", true)
                cue("Push") { control("tube_plate", "plate", 12.0, glideMs = 300) }
                cue("Pull back") { control("tube_plate", "plate", -12.0, glideMs = 600) }
                markdownNote(
                    "Colour is pressure: red above, blue below. Next pushes the plate, then Next pulls it back. [[back]] [[next]]",
                )
            }
        }
        section("Making sounds") {
            step("A jump is a pop", id = "sounds-pop") {
                audience {
                    figureBeside("repeated_jumps") {
                        +"""
                        ## A jump is a pop

                        A sudden move of the plate is a pop. A bigger move, a louder pop.
                    """
                            .md
                    }
                }
                control("repeated_jumps", "repeat", false)
                control("repeated_jumps", "distance", 0.5)
                control("repeated_jumps", "rate", 4.0)
                control("repeated_jumps", "sound", false)
                markdownNote(
                    "Tick **sound**, then hold **jump**; drag **distance** to change how far it moves. [[back]] [[next]]",
                )
            }
            step("Repeat it and it becomes a note", id = "sounds-repeat") {
                audience {
                    figureBeside("repeated_jumps") {
                        +"""
                        ## Repeat it and it becomes a note

                        Past about twenty a second the pops blur into a
                        tone. Its frequency, in hertz, is its pitch.
                    """
                            .md
                    }
                }
                control("repeated_jumps", "rate", 4.0)
                control("repeated_jumps", "repeat", false)
                control("repeated_jumps", "sound", false)
                cue("Repeat") { control("repeated_jumps", "repeat", true) }
                cue("Faster") { control("repeated_jumps", "rate", 220.0, glideMs = 4000) }
                markdownNote(
                    "Tick **sound** to hear it. Next starts the repeats at 4 per second, then Next glides to 220. [[back]] [[next]]",
                )
            }
            step("Sounds add", id = "sounds-add") {
                audience {
                    figureBeside("wave_synth") {
                        +"""
                        ## Sounds add

                        Two keys at once: the plate makes both motions, and
                        they add. The bottom line is what you hear.
                    """
                            .md
                    }
                }
                markdownNote("Hold one key, then two. [[back]] [[next]]")
            }
            step("One key, two sounds", id = "sounds-sum-key") {
                audience {
                    figureBeside("wave_synth") {
                        +"""
                        ## One key, two sounds

                        The last key plays A + B as one pattern. It sounds the same as holding both.
                    """
                            .md
                    }
                }
                markdownNote("Hold the first two keys together, then the fourth alone. [[back]] [[next]]")
            }
            step("Three numbers for a shape", id = "sounds-triangle") {
                audience {
                    figureBeside("triangle_params") {
                        +"""
                        ## Three numbers for a repeating shape

                        **Amplitude** is loudness, **frequency** is pitch,
                        **phase** is where it starts, which alone you cannot
                        hear.
                    """
                            .md
                    }
                }
                control("triangle_params", "amplitude", 0.6)
                control("triangle_params", "frequency", 220.0)
                control("triangle_params", "phase", 0.0)
                control("triangle_params", "sound", false)
                cue("Louder") { control("triangle_params", "amplitude", 1.0, glideMs = 1500) }
                cue("Higher") { control("triangle_params", "frequency", 440.0, glideMs = 1500) }
                cue("Shift") { control("triangle_params", "phase", 0.5, glideMs = 1500) }
                markdownNote(
                    "Tick **sound** first if you want it heard. Next: amplitude up, then frequency up, then phase shifted. [[back]] [[next]]",
                )
            }
            step("Half a cycle apart, they cancel", id = "sounds-cancel") {
                audience {
                    figureBeside("phase_cancel") {
                        +"""
                        ## Half a cycle apart, they cancel

                        Two equal tones add. Shift one by half a cycle and
                        every push meets a pull: silence.
                    """
                            .md
                    }
                }
                control("phase_cancel", "shift", 0.0)
                control("phase_cancel", "sound", false)
                cue("Shift half a cycle") { control("phase_cancel", "shift", 0.5, glideMs = 2500) }
                markdownNote("Tick **sound**, then Next slides the second tone half a cycle along. [[back]] [[next]]")
            }
            step("Draw a shape, hear it", id = "sounds-draw") {
                audience {
                    figureBeside("draw_wave", "wave", "controls") {
                        +"""
                        ## Draw a shape, hear it

                        Drag the points, then hold a key. Corners and jumps sound brighter.
                    """
                            .md
                    }
                }
                markdownNote("Drag points on the curve, then hold a key. [[back]] [[next]]")
            }
        }
        section("Pure tones") {
            step("A turning circle", id = "tones-circle") {
                audience {
                    figureBeside("circle_sine") {
                        +"""
                        ## A sine wave is a turning circle

                        Radius is amplitude, speed of turning is frequency, starting point is phase.
                    """
                            .md
                    }
                }
                control("circle_sine", "radius", 0.8)
                control("circle_sine", "turns per second", 0.5)
                control("circle_sine", "start", 0.0)
                markdownNote("Drag the sliders; the newest part of the trace is beside the circle. [[back]] [[next]]")
            }
            step("The range of hearing", id = "tones-sweep") {
                audience {
                    figureBeside("sine_sweep") {
                        +"""
                        ## The range of hearing

                        The purest tone. We hear about 20 Hz to 20,000 Hz,
                        best between 1,000 and 5,000.
                    """
                            .md
                    }
                }
                control("sine_sweep", "frequency", 220.0)
                control("sine_sweep", "sound", false)
                cue("Up an octave") { control("sine_sweep", "frequency", 440.0, glideMs = 1500) }
                markdownNote(
                    "Tick **sound**, then Next glides up an octave. Keep the room's volume low. [[back]] [[next]]",
                )
            }
            step("Close tones beat", id = "tones-beats") {
                audience {
                    figureBeside("beats") {
                        +"""
                        ## Two close tones beat

                        A few hertz apart, two sines drift in and out of
                        step: one tone, swelling and fading.
                    """
                            .md
                    }
                }
                markdownNote("Hold both keys; drag **difference**. [[back]] [[next]]")
            }
            step("Every shape is a sum of sines", id = "tones-square") {
                audience {
                    figureBeside("square_partials") {
                        +"""
                        ## Every shape is a sum of sines

                        Odd multiples, each smaller, build a square. The
                        ripples at the corners never shrink in height.
                    """
                            .md
                    }
                }
                control("square_partials", "sines", 1.0)
                control("square_partials", "sound", false)
                cue("Add sines") { control("square_partials", "sines", 25.0, glideMs = 4000) }
                markdownNote("Next adds sines one by one up to 25. [[back]] [[next]]")
            }
            step("Taking a drawing apart", id = "tones-fourier") {
                audience {
                    figureBeside("draw_wave", "wave", "partials", "controls") {
                        +"""
                        ## Taking a drawing apart

                        Your drawing is a sum of sines too. The bars say how much of each multiple.
                    """
                            .md
                    }
                }
                markdownNote("Redraw the shape and watch the bars. [[back]] [[next]]")
            }
            step("Listening back", id = "tones-microphone") {
                audience {
                    figureBeside("microphone") {
                        +"""
                        ## Listening back

                        Whistle: one bar. Sing a vowel: a row of them, at multiples of your note.
                    """
                            .md
                    }
                }
                control("microphone", "listen", false)
                markdownNote("Tick **listen** to open the microphone. [[back]] [[next]]")
            }
        }
        section("Masses and springs") {
            step("A mass between springs", id = "springs-one") {
                audience {
                    figureBeside("normal_modes") {
                        +"""
                        ## A mass between springs

                        It swings as a sine, at a pitch set by stiffness and
                        mass, however far it was pulled.
                    """
                            .md
                    }
                }
                control("normal_modes", "masses", 1.0)
                control("normal_modes", "mode", 1.0)
                control("normal_modes", "add the lowest", false)
                markdownNote("One mass has only one way to swing. [[back]] [[next]]")
            }
            step("Two masses, two ways to move", id = "springs-modes") {
                audience {
                    figureBeside("normal_modes") {
                        +"""
                        ## Two masses, two ways to move

                        Together, or against each other and a little faster.
                        Any motion mixes the two.
                    """
                            .md
                    }
                }
                control("normal_modes", "masses", 2.0)
                control("normal_modes", "mode", 1.0)
                control("normal_modes", "add the lowest", false)
                cue("The other mode") { control("normal_modes", "mode", 2.0) }
                cue("Both at once") { control("normal_modes", "add the lowest", true) }
                markdownNote("Next swaps mode 1 for mode 2, then Next mixes both. [[back]] [[next]]")
            }
            step("More masses, more modes", id = "springs-five") {
                audience {
                    figureBeside("normal_modes") {
                        +"""
                        ## More masses, more modes

                        Five masses, five modes. Higher modes wiggle more.
                    """
                            .md
                    }
                }
                control("normal_modes", "masses", 5.0)
                control("normal_modes", "mode", 3.0)
                control("normal_modes", "add the lowest", false)
                markdownNote("This is mode 3 of 5. Try the others one at a time. [[back]] [[next]]")
            }
            step("A string is many masses", id = "springs-string") {
                audience {
                    figureBeside("pluck") {
                        +"""
                        ## A string is many masses

                        A string's modes are whole numbers of half waves, at
                        whole multiples of the lowest pitch: the harmonics.
                    """
                            .md
                    }
                }
                control("pluck", "mode", 1.0)
                control("pluck", "length", 1.0)
                cue("Second mode") { control("pluck", "mode", 2.0) }
                cue("Third mode") { control("pluck", "mode", 3.0) }
                markdownNote("The string is shown slowed down. Next shows mode 2, then mode 3. [[back]] [[next]]")
            }
            step("Pluck it", id = "springs-pluck") {
                audience {
                    figureBeside("pluck") {
                        +"""
                        ## Pluck it

                        A pluck rings every mode at once. Near the middle it
                        is round; near the end, bright.
                    """
                            .md
                    }
                }
                control("pluck", "mode", 0.0)
                control("pluck", "length", 1.0)
                control("pluck", "pluck at", 0.25)
                cue("Near the end") { control("pluck", "pluck at", 0.06, glideMs = 800) }
                markdownNote(
                    "Press **pluck** (or P); Next moves the pluck near the end, then pluck again: brighter. [[back]] [[next]]",
                )
            }
            step("Shorter, tighter, lighter", id = "springs-fret") {
                audience {
                    figureBeside("pluck") {
                        +"""
                        ## Shorter, tighter, lighter: higher

                        Half the length, an octave up. Tighter is higher; heavier is lower.
                    """
                            .md
                    }
                }
                control("pluck", "mode", 0.0)
                control("pluck", "length", 1.0)
                cue("Half the length") { control("pluck", "length", 0.5, glideMs = 1500) }
                markdownNote("Pluck, then Next halves the string; pluck again an octave up. [[back]] [[next]]")
            }
        }
        section("Pressure waves") {
            step("A pulsing sphere", id = "waves-sphere") {
                audience {
                    figureBeside("pulsing_sphere") {
                        +"""
                        ## A pulsing sphere

                        Rings of high and low pressure. Their spacing is the
                        wavelength: 343 Hz makes one metre.
                    """
                            .md
                    }
                }
                control("pulsing_sphere", "frequency", 343.0)
                cue("Higher") { control("pulsing_sphere", "frequency", 1029.0, glideMs = 2000) }
                markdownNote("Next raises the frequency threefold; the rings crowd closer. [[back]] [[next]]")
            }
            step("Aimed sound", id = "waves-directional") {
                audience {
                    figureBeside("wall_plate") {
                        +"""
                        ## Aimed sound

                        A plate in a wall spreads low notes every way. Once the waves are shorter
                        than the plate, its edges interfere and the sound is aimed ahead.
                    """
                            .md
                    }
                }
                control("wall_plate", "frequency", 400.0)
                cue("Higher") { control("wall_plate", "frequency", 2500.0, glideMs = 2000) }
                markdownNote(
                    "Next raises the frequency past 2 kHz: the beam narrows. The strip is the pressure along the axis. [[back]] [[next]]",
                )
            }
            step("Later and quieter", id = "waves-distance") {
                audience {
                    figureBeside("speaker_ear") {
                        +"""
                        ## Further away: later and quieter

                        About 3 ms a metre. Twice as far, half the pressure swing.
                    """
                            .md
                    }
                }
                markdownNote("Drag the orange listener; the meters give the delay and the level. [[back]] [[next]]")
            }
            step("Decibels", id = "waves-decibels") {
                audience {
                    figureBeside("decibels") {
                        +"""
                        ## Decibels

                        Every ten times the pressure adds 20 dB. Twice the
                        distance costs about 6 dB.
                    """
                            .md
                    }
                }
                control("decibels", "pressure", 0.02)
                cue("Ten times the pressure") { control("decibels", "pressure", 0.2, glideMs = 1500) }
                markdownNote("Next multiplies the pressure by ten: 20 dB more. [[back]] [[next]]")
            }
            step("A moving source", id = "waves-doppler") {
                audience {
                    figureBeside("doppler") {
                        +"""
                        ## A moving source

                        Ahead of the source the rings crowd, and the pitch
                        is higher; behind, lower. Time is slowed a hundred
                        times.
                    """
                            .md
                    }
                }
                control("doppler", "speed", 150.0)
                control("doppler", "sound", false)
                markdownNote("Drag **speed**. Tick **sound** to hear it pass. [[back]] [[next]]")
            }
            step("Two speakers", id = "waves-two-speakers") {
                audience {
                    figureBeside("two_speakers") {
                        +"""
                        ## Two speakers

                        On the orange lines one speaker's push meets the other's pull and the tone nearly vanishes.
                    """
                            .md
                    }
                }
                control("two_speakers", "sound", false)
                markdownNote(
                    "Drag the orange listener onto an orange line and it goes quiet. Tick **sound** to hear it. [[back]] [[next]]",
                )
            }
            step("Echo", id = "waves-echo") {
                audience {
                    figureBeside("echo") {
                        +"""
                        ## Echo

                        Far from a wall, a second clap. Within about 50 ms,
                        closer than 8 m, one sound.
                    """
                            .md
                    }
                }
                markdownNote("Drag **distance to the wall**, then **clap**. [[back]] [[next]]")
            }
            step("A room", id = "waves-reverb") {
                audience {
                    figureBeside("reverb") {
                        +"""
                        ## A room

                        Bounces pile up into a tail: reverberation. Bigger,
                        harder rooms ring longer.
                    """
                            .md
                    }
                }
                control("reverb", "room size", 0.4)
                control("reverb", "soft walls", 0.3)
                cue("A big hall") { control("reverb", "room size", 0.9, glideMs = 1500) }
                markdownNote("Hold a key, then Next makes the room a hall. [[back]] [[next]]")
            }
        }
        section("Further reading") {
            step("Further reading", id = "further-reading") {
                audience {
                    +"""
                    # Further reading

                    - Eric J. Heller, [*Why You Hear What You Hear*](https://www.whyyouhearwhatyouhear.com/)
                      (Princeton, 2013): the physics of sound and its perception, with experiments to try.
                    - Neville H. Fletcher and Thomas D. Rossing, [*The Physics of Musical
                      Instruments*](https://archive.org/details/physicsofmusical0000flet) (Springer, 1991):
                      strings, plates, pipes and rooms in depth.
                    - Julius O. Smith III, [*Physical Audio Signal Processing*](https://ccrma.stanford.edu/~jos/pasp/),
                      free online: from waves on strings to digital models of them.
                """
                        .md
                    p(classes = "muted") {
                        +"After “Sound” by Bartosz Ciechanowski, https://ciechanow.ski/sound/. Words and figures made in Field."
                    }
                }
                markdownNote("The end. [[back]] [[finish]]")
            }
        }
    }
    .arrangeBoxes()
    .present()
```

## 16. phase_cancel

<!-- field-box id="es_phase_cancel" index="15" -->

### Narrative

From `code`

Two identical tones, the second slid along by "shift" (a fraction of a cycle), and their sum.

```edn [field-box id="es_phase_cancel" role="properties"]
{"id" "es_phase_cancel" "index" 15 "name" "phase_cancel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "phase_cancel" "_tombstone" false "frame" #afield/rect [700.0 220.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_phase_cancel_code_.kt" "lines" 55 "characters" 1618 "sha256" "931dd92319d5279056c002678fb4170117f643cbaf8f7105c6358638d06da714"}]}
```

```kotlin [field-property box="es_phase_cancel" name="code" path="es_phase_cancel_code_.kt"]
// Two identical tones, the second slid along by "shift" (a fraction of a cycle), and their sum.
var shift = 0.0
var on = false
val offset = Param(0f) // the second tone's shift, in radians
val level = Param(0f)

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 300.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun row(y: Double, scale: Double, f: (Double) -> Double, c: Vec4) = fline {
    moveTo(0.0, y - scale * f(0.0))
    for (x in 1..640) lineTo(x.toDouble(), y - scale * f(0.02 * x / 640))
    thickness = 3.0
    color = c
}

fun update() {
    offset.base = (2 * PI * shift).toFloat()
    level.base = if (on) 0.15f else 0f
    val first = { t: Double -> sin(2 * PI * 220 * t) }
    val second = { t: Double -> sin(2 * PI * 220 * t + 2 * PI * shift) }
    lines(
        frame,
        row(46.0, 38.0, first, vec(0.4, 0.9, 1.0, 1.0)),
        row(134.0, 38.0, second, vec(0.4, 0.9, 1.0, 0.6)),
        fline {
            moveTo(0.0, 182.0)
            lineTo(640.0, 182.0)
            thickness = 1.0
            color = vec(1.0, 1.0, 1.0, 0.35)
        },
        row(240.0, 28.0, { t -> first(t) + second(t) }, vec(1.0, 0.6, 0.2, 1.0)),
        channel = "wave",
        w = 640.0,
        h = 300.0,
    )
}

update()
controlGroup {
    slider("shift", 0.0, 1.0, step = 0.01, initialValue = 0.0) {
        shift = it
        update()
    }
    checkbox("sound") {
        on = it
        update()
    }
}

audio((SineOsc(Param(220f)) + SineOsc(Param(220f), phaseMod = offset)) / 2.0, gain = level)
```

## 17. echo

<!-- field-box id="es_echo" index="16" -->

### Narrative

From `code`

You clap, standing "distance to the wall" metres from a wall. The pulse travels there and back at
343 m/s, shown at its real speed. Below, a timeline: the clap at 0, its echo when it returns; the
shaded part is the first 50 ms, inside which the ear hears the two as one sound.

```edn [field-box id="es_echo" role="properties"]
{"id" "es_echo" "index" 16 "name" "echo" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "echo" "_tombstone" false "frame" #afield/rect [500.0 540.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_echo_code_.kt" "lines" 98 "characters" 3201 "sha256" "eb701441a0627685e306bd9f21b2a222d6635d43a4c2e9360fc336d7df7b1924"}]}
```

```kotlin [field-property box="es_echo" name="code" path="es_echo_code_.kt"]
/*
You clap, standing "distance to the wall" metres from a wall. The pulse travels there and back at
343 m/s, shown at its real speed. Below, a timeline: the clap at 0, its echo when it returns; the
shaded part is the first 50 ms, inside which the ear hears the two as one sound.
*/
var distance = 20.0
var clapped = -10.0
var time = 0.0
val gate = Gate()
val lag = Param(2 * 20f / 343)
val echoLevel = Param(0.15f)

fun echoGain(d: Double) = (0.6 * 4 / (2 * d)).coerceAtMost(0.6)

fun draw() {
    val metre = 600 / 60.0 // sixty metres across
    val wall = 20 + metre * distance
    val travelled = 343 * (time - clapped) // metres the pulse has gone
    val out = travelled <= distance
    val front = if (out) 20 + metre * travelled else wall - metre * (travelled - distance)
    val pulse =
        if (travelled > 2 * distance) null
        else
            fline {
                moveTo(front, 40.0)
                lineTo(front, 160.0)
                thickness = 3.0
                color = if (out) vec(0.4, 0.9, 1.0, 1.0) else vec(1.0, 0.6, 0.2, 1.0)
            }
    val wallLine = fline {
        rect(wall, 30.0, 8.0, 140.0)
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.7)
    }
    val you = fline {
        circle(20.0, 100.0, 8.0)
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.9)
    }
    // The timeline: 0 to 400 ms across the figure.
    val ms = 600 / 400.0
    val returns = 1000 * 2 * distance / 343
    val merged = fline {
        rect(20.0, 205.0, 50 * ms, 50.0)
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.12)
    }
    val axis = fline {
        moveTo(20.0, 255.0)
        lineTo(620.0, 255.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val clap = fline {
        moveTo(20.0, 255.0)
        lineTo(20.0, 210.0)
        thickness = 3.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    val echo = fline {
        moveTo(20 + ms * returns.coerceAtMost(400.0), 255.0)
        lineTo(20 + ms * returns.coerceAtMost(400.0), 255 - 45 * (echoGain(distance) / 0.6).coerceAtLeast(0.3))
        thickness = 3.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val frame = fline {
        rect(0.0, 0.0, 640.0, 280.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.0)
    }
    lines(listOfNotNull(frame, wallLine, you, pulse, merged, axis, clap, echo), channel = "wall", w = 640.0, h = 280.0)
}

draw()
controlGroup {
    keys("clap", letters = "c") { _, down ->
        if (down) {
            clapped = time
            gate.open()
        } else gate.close()
    }
    slider("distance to the wall", 2.0, 60.0, step = 0.5, initialValue = 20.0) {
        distance = it
        lag.base = (2 * it / 343).toFloat()
        echoLevel.base = echoGain(it).toFloat()
    }
}

val start = AnimationClock.nowSeconds()
// A clap is a short burst of noise; its echo is the same burst, later and quieter.
val burst = WhiteNoise() * perc(0.06, 0.001, gate)
loop {
    time = AnimationClock.nowSeconds() - start
    draw()
} + audio(burst + burst / Delay(lag, Param(0f), Param(1f), maxDelaySec = 1f) * echoLevel, gain = 0.15f)
```

## 18. pluck

<!-- field-box id="es_pluck" index="17" -->

### Narrative

From `code`

A string held at both ends. "pluck" pulls it aside at "pluck at" (the orange dot) and lets go.
What follows is a sum of the string's modes, each a whole number of half waves, ringing at whole multiples of the
lowest frequency, the high ones dying
away faster. "mode" shows one mode alone (0 shows the pluck); "length" stops the string short,
as a finger on a fret does.

```edn [field-box id="es_pluck" role="properties"]
{"id" "es_pluck" "index" 17 "name" "pluck" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pluck" "_tombstone" false "frame" #afield/rect [700.0 380.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_pluck_code_.kt" "lines" 102 "characters" 3598 "sha256" "a33bc7b375fe7caea6dc06b5151fd8b7c1048e791ccf6b1f10704c6951cbc70b"}]}
```

```kotlin [field-property box="es_pluck" name="code" path="es_pluck_code_.kt"]
/*
A string held at both ends. "pluck" pulls it aside at "pluck at" (the orange dot) and lets go.
What follows is a sum of the string's modes, each a whole number of half waves, ringing at whole multiples of the
lowest frequency, the high ones dying
away faster. "mode" shows one mode alone (0 shows the pluck); "length" stops the string short,
as a finger on a fret does.
*/
val n = 30
var at = 0.25 // where the last pluck was, as a fraction of the string
var depth = 0.8 // how far it was pulled, as a fraction of the picture's half-height
var length = 1.0
var mode = 0
var plucked = -10.0 // when the pluck happened, in seconds since launch
var time = 0.0

fun weight(k: Int) = depth * 2 / (k * k * PI * PI * at * (1 - at)) * sin(k * PI * at)
fun decay(k: Int) = 2.5 / k.toDouble().pow(0.7)
fun base() = 110 / length

// The sound: one sine per mode, its loudness set by the pluck and its own decay.
var audioPluck = -1f // audio time of the latest pluck; NaN asks the audio thread to stamp it
val partials =
    (1..n).map { k ->
        SineOsc(Param { (k * base()).toFloat() }) *
            Param {
                if (audioPluck.isNaN()) audioPluck = time
                if (audioPluck < 0) 0f else (weight(k) * exp(-(time - audioPluck) / decay(k))).toFloat()
            }
    }

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 300.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}
val left = 12.0
val middle = 150.0
fun stringEnd() = left + 616 * length

fun pluck(x: Double, y: Double) {
    at = ((x - left) / (stringEnd() - left)).coerceIn(0.05, 0.95)
    depth = ((middle - y) / 130).coerceIn(-1.0, 1.0)
    if (abs(depth) < 0.05) return
    plucked = time
    mode = 0
    audioPluck = Float.NaN
}

fun scenery(): List<FLine> {
    val rest = fline {
        moveTo(stringEnd(), middle)
        lineTo(628.0, middle)
        thickness = 1.5
        color = vec(1.0, 1.0, 1.0, 0.3)
    }
    val pegs = fline {
        circle(left, middle, 7.0)
        circle(stringEnd(), middle, 7.0)
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.8)
    }
    return [frame, rest, pegs]
}

fun string(y: (Double) -> Double) = fline {
    moveTo(left, middle)
    for (i in 1..300) lineTo(left + (stringEnd() - left) * i / 300, middle - 130 * y(i / 300.0))
    thickness = 3.0
    color = vec(0.4, 0.9, 1.0, 1.0)
}

// Shown slowed down: the lowest mode takes two seconds a swing.
fun animate() {
    val since = time - plucked
    val y = { x: Double ->
        if (mode > 0) sin(mode * PI * x) * cos(PI * mode * time)
        else (1..n).sumOf { k -> weight(k) * sin(k * PI * x) * cos(PI * k * since) * exp(-since / decay(k)) }
    }
    val mark = fline {
        circle(left + (stringEnd() - left) * at, middle + 24, 6.0)
        filled = true
        stroked = false
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(scenery() + string(y) + mark, channel = "string", w = 640.0, h = 300.0)
}

animate()
controlGroup {
    keys("pluck", letters = "p") { _, down -> if (down) pluck(left + (stringEnd() - left) * at, middle - 130 * 0.8) }
    slider("pluck at", 0.05, 0.5, step = 0.01, initialValue = 0.25) { at = it }
    slider("mode", 0.0, 6.0, step = 1.0, initialValue = 0.0) { mode = it.toInt() }
    slider("length", 0.5, 1.0, step = 0.01, initialValue = 1.0) { length = it }
}

val start = AnimationClock.nowSeconds()
loop {
    time = AnimationClock.nowSeconds() - start
    animate()
} + audio(partials.reduce { a, b -> a + b }, gain = 0.15f)
```

## 19. decibels

<!-- field-box id="es_decibels" index="18" -->

### Narrative

From `code`

Sound pressure level: 20 × log10 of the pressure swing over 20 micropascals, the quietest a
young ear hears at 1 kHz. Drag "pressure" (a logarithmic slider, in pascals) and read the level.

```edn [field-box id="es_decibels" role="properties"]
{"id" "es_decibels" "index" 18 "name" "decibels" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "decibels" "_tombstone" false "frame" #afield/rect [700.0 460.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_decibels_code_.kt" "lines" 19 "characters" 758 "sha256" "c633120ea5a91162cce0335abe92f76cb8355fa5bb082000a108d206cb828df9"}]}
```

```kotlin [field-property box="es_decibels" name="code" path="es_decibels_code_.kt"]
// Sound pressure level: 20 × log10 of the pressure swing over 20 micropascals, the quietest a
// young ear hears at 1 kHz. Drag "pressure" (a logarithmic slider, in pascals) and read the level.
fun level(pa: Double) = 20 * log10(pa / 20e-6)
lateinit var bar: MeterHandle

fun show(pa: Double) {
    val db = level(pa)
    markdown(
        "readout",
        "**${"%.3g".format(pa)} Pa → ${db.f1} dB**\n\n0 dB: hearing just begins · 60 dB: talking, a metre away · 120 dB: it starts to hurt",
    )
    if (::bar.isInitialized) bar.set(db)
}

show(0.02)
controlGroup {
    slider("pressure", 0.00002, 200.0, step = 0.00001, initialValue = 0.02, law = ControlLaw.LOGARITHMIC) { show(it) }
    bar = meter("decibels", 0.0, 140.0, initialValue = level(0.02))
}
```

## 20. two_speakers

<!-- field-box id="es_two_speakers" index="19" -->

### Narrative

From `code`

Two speakers on the left play the same tone. Each faint ring is a crest of one speaker's wave, a
wavelength apart; the orange curves are where a crest from one always meets a trough from the
other, so the two cancel. Drag the listener (the orange point). With "sound" on you hear the tone
at the loudness it has there. The picture is ten metres across.

```edn [field-box id="es_two_speakers" role="properties"]
{"id" "es_two_speakers" "index" 19 "name" "two_speakers" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "two_speakers" "_tombstone" false "frame" #afield/rect [300.0 540.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_two_speakers_code_.kt" "lines" 133 "characters" 4358 "sha256" "a924b9a12fcc0d802923d120338f107a9ffff13230b4956d9a19b273e706af33"}]}
```

```kotlin [field-property box="es_two_speakers" name="code" path="es_two_speakers_code_.kt"]
/*
Two speakers on the left play the same tone. Each faint ring is a crest of one speaker's wave, a
wavelength apart; the orange curves are where a crest from one always meets a trough from the
other, so the two cancel. Drag the listener (the orange point). With "sound" on you hear the tone
at the loudness it has there. The picture is ten metres across.
*/
val metre = 64.0
var hz = 343.0
var ear = Pair(5.0, 0.6) // metres from the midpoint between the speakers
var on = false
val pitch = Param(343f)
val level = Param(0f)
val speakers = [0.4 to -0.9, 0.4 to 0.9] // metres

fun px(x: Double) = metre * x
fun py(y: Double) = 150 - metre * y
fun distance(x: Double, y: Double, s: Pair<Double, Double>) =
    sqrt((x - s.first) * (x - s.first) + (y - s.second) * (y - s.second))

// How big the pressure swing is at (x, y): the two waves added with their phases.
fun loudness(x: Double, y: Double): Double {
    val k = 2 * PI * hz / 343
    var re = 0.0
    var im = 0.0
    speakers.forEach { s ->
        val r = distance(x, y, s).coerceAtLeast(0.1)
        re += cos(k * r) / sqrt(r)
        im += sin(k * r) / sqrt(r)
    }
    return sqrt(re * re + im * im)
}

// A circle, kept to the part inside the picture.
fun ring(cx: Double, cy: Double, r: Double) = fline {
    var drawing = false
    for (i in 0..120) {
        val a = 2 * PI * i / 120
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        val inside = x in 0.0..640.0 && y in 0.0..300.0
        if (inside && drawing) lineTo(x, y) else if (inside) moveTo(x, y)
        drawing = inside
    }
}

// Where the lower speaker is farther than the upper by exactly `gap` metres: for each step across,
// the height that gives that difference, found by halving (the difference only grows with height).
fun quiet(gap: Double) = fline {
    var started = false
    for (i in 1..120) {
        val x = 0.4 + 9.6 * i / 120
        var lo = -2.4
        var hi = 2.4
        fun diff(y: Double) = distance(x, y, speakers[0]) - distance(x, y, speakers[1])
        if ((diff(lo) - gap) * (diff(hi) - gap) > 0) {
            started = false
            continue
        }
        repeat(30) {
            val mid = (lo + hi) / 2
            if (diff(mid) < gap) lo = mid else hi = mid
        }
        if (started) lineTo(px(x), py(lo)) else moveTo(px(x), py(lo))
        started = true
    }
}

fun update() {
    pitch.base = hz.toFloat()
    level.base = if (on) (0.15 * (loudness(ear.first, ear.second) / 1.6).coerceAtMost(1.0)).toFloat() else 0f
}

fun show() {
    val wavelength = 343 / hz
    val frame = fline {
        rect(0.0, 0.0, 640.0, 300.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.0)
    }
    val crests = speakers.flatMap { s ->
        (1..(11 / wavelength).toInt()).map { k ->
            ring(px(s.first), py(s.second), metre * k * wavelength).apply {
                thickness = 1.0
                color = vec(0.4, 0.9, 1.0, 0.35)
            }
        }
    }
    // A path difference can be no larger than the 1.8 m between the speakers.
    val quietLines =
        (-6..5)
            .map { m -> (m + 0.5) * wavelength }
            .filter { abs(it) < 1.8 }
            .map {
                quiet(it).apply {
                    thickness = 2.5
                    color = vec(1.0, 0.6, 0.2, 0.7)
                }
            }
    val boxes = fline {
        speakers.forEach { (x, y) -> rect(px(x) - 10, py(y) - 14, 10.0, 28.0) }
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.9)
    }
    val scenery = ([frame] + crests + quietLines + boxes).onEach { it.fixed = true }
    val listener = fline {
        moveTo(px(ear.first), py(ear.second))
        pointed = true
        pointSize = 3.5
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(scenery + listener, channel = "field", w = 640.0, h = 300.0, editable = true) { edited ->
        val p = edited.last().nodes.first().to
        ear = (p.x / metre).coerceIn(0.5, 9.9) to ((150 - p.y) / metre).coerceIn(-2.3, 2.3)
        update()
    }
}

show()
update()
controlGroup {
    slider("frequency", 100.0, 1000.0, step = 1.0, initialValue = 343.0, law = ControlLaw.LOGARITHMIC) {
        hz = it
        update()
        show()
    }
    checkbox("sound") {
        on = it
        update()
    }
}

audio(SineOsc(pitch), gain = level)
```

## 21. speaker_ear

<!-- field-box id="es_speaker_ear" index="20" -->

### Narrative

From `code`

A speaker on the left and a listener (the orange point) you drag. The rings are one metre apart,
fainter as the sound spreads; the meters say how late the sound arrives and how much quieter it
is than one metre away. The picture is ten metres across; the bar is one metre.

```edn [field-box id="es_speaker_ear" role="properties"]
{"id" "es_speaker_ear" "index" 20 "name" "speaker_ear" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "speaker_ear" "_tombstone" false "frame" #afield/rect [500.0 460.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_speaker_ear_code_.kt" "lines" 84 "characters" 2708 "sha256" "f12542e846339273e2205526d5dee1fccb200278e9b6f4f9356eee1751580d7e"}]}
```

```kotlin [field-property box="es_speaker_ear" name="code" path="es_speaker_ear_code_.kt"]
/*
A speaker on the left and a listener (the orange point) you drag. The rings are one metre apart,
fainter as the sound spreads; the meters say how late the sound arrives and how much quieter it
is than one metre away. The picture is ten metres across; the bar is one metre.
*/
val metre = 64.0
var ear = Pair(3.0, 0.0) // metres from the speaker
lateinit var delay: MeterHandle
lateinit var level: MeterHandle

// A circle, kept to the part inside the picture.
fun ring(cx: Double, cy: Double, r: Double) = fline {
    var drawing = false
    for (i in 0..180) {
        val a = 2 * PI * i / 180
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        val inside = x in 0.0..640.0 && y in 0.0..300.0
        if (inside && drawing) lineTo(x, y) else if (inside) moveTo(x, y)
        drawing = inside
    }
}

fun measure() {
    val d = sqrt(ear.first * ear.first + ear.second * ear.second).coerceAtLeast(0.1)
    delay.set(1000 * d / 343)
    level.set(-20 * log10(d))
}

fun show() {
    val sx = 24.0
    val sy = 150.0
    val frame = fline {
        rect(0.0, 0.0, 640.0, 300.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.0)
    }
    // Brightness falls as one over the distance, as the pressure swing does.
    val rings =
        (1..10).map { m ->
            ring(sx, sy, metre * m).apply {
                thickness = 2.0
                color = vec(0.4, 0.9, 1.0, 0.25 + 0.75 / m)
            }
        }
    val speaker = fline {
        rect(sx - 16, sy - 22, 16.0, 44.0)
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.9)
    }
    val path = fline {
        moveTo(sx, sy)
        lineTo(sx + metre * ear.first, sy - metre * ear.second)
        thickness = 1.5
        color = vec(1.0, 0.6, 0.2, 0.5)
    }
    val scale = fline {
        moveTo(40.0, 285.0)
        lineTo(40 + metre, 285.0)
        thickness = 3.0
        color = vec(1.0, 1.0, 1.0, 0.8)
    }
    val scenery = ([frame, speaker, path, scale] + rings).onEach { it.fixed = true }
    val listener = fline {
        moveTo(sx + metre * ear.first, sy - metre * ear.second)
        pointed = true
        pointSize = 3.5
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(scenery + listener, channel = "room", w = 640.0, h = 300.0, editable = true) { edited ->
        val p = edited.last().nodes.first().to
        ear = ((p.x - sx) / metre).coerceIn(0.3, 9.6) to ((sy - p.y) / metre).coerceIn(-2.2, 2.2)
        measure()
        show()
    }
}

show()
controlGroup {
    delay = meter("delay, milliseconds", 0.0, 30.0, initialValue = 1000 * 3.0 / 343)
    level = meter("level, dB from 1 m", -20.0, 6.0, initialValue = -20 * log10(3.0))
}
measure()
```

## 22. keyboard

<!-- field-box id="es_keyboard" index="21" -->

### Narrative

From `code`

Three keys. Hold one to hear it; the line is the shape of the air pressure it makes.

```edn [field-box id="es_keyboard" role="properties"]
{"id" "es_keyboard" "index" 21 "name" "keyboard" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "keyboard" "_tombstone" false "frame" #afield/rect [300.0 140.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_keyboard_code_.kt" "lines" 46 "characters" 1427 "sha256" "db6ee721210755edd646f35fe525fa36b27a3a51ef062b118781924bd9d63009"}]}
```

```kotlin [field-property box="es_keyboard" name="code" path="es_keyboard_code_.kt"]
// Three keys. Hold one to hear it; the line is the shape of the air pressure it makes.
val shapes = listOf<(Double) -> Double>(
    { t -> sin(2 * PI * t) },
    { t -> 2 * abs(2 * (t - floor(t + 0.5))) - 1 },
    { t -> 2 * (t - floor(t + 0.5)) },
)
val pitches = [196.0, 247.0, 294.0]
val voices = listOf<Signal>(SineOsc(Param(196f)), TriOsc(Param(247f)), SawOsc(Param(294f)) / lpf(2400))
val gates = 3 { Gate() }
val held = BooleanArray(3)

// Twenty milliseconds of whatever is held, summed.
// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 240.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun draw() = lines(
    frame,
    fline {
        moveTo(0.0, 120.0)
        for (x in 1..640) {
            val t = 0.02 * x / 640
            val y = (0..2).sumOf { i -> if (held[i]) shapes[i](pitches[i] * t) / 3 else 0.0 }
            lineTo(x.toDouble(), 120.0 - 200.0 * y)
        }
        thickness = 3.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    },
    channel = "wave",
    w = 640.0,
    h = 240.0,
)

draw()
controlGroup {
    keys("low", "middle", "high", letters = "wer") { key, down ->
        held[key] = down
        if (down) gates[key].open() else gates[key].close()
        draw()
    }
}

audio(3 { i -> voices[i] * adsr(0.01, 0.05, 1.0, 0.25, gates[i]) }.reduce { a, b -> a + b } / 3.0, gain = 0.15f)
```

## 23. pressure_box

<!-- field-box id="es_pressure_box" index="22" -->

```edn [field-box id="es_pressure_box" role="properties"]
{"id" "es_pressure_box" "index" 22 "name" "pressure_box" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pressure_box" "_tombstone" false "frame" #afield/rect [500.0 140.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_pressure_box_code_.kt" "lines" 163 "characters" 5564 "sha256" "a8e7c8e86cf6fd5c664727232a8f8b5e47481bda2829e32ca01f14e4547889d8"}]}
```

```kotlin [field-property box="es_pressure_box" name="code" path="es_pressure_box_code_.kt"]
import afield.media.image.util.PngEncoder

// A flat box of molecules. They fly straight, bounce off one another and off the walls.
// "length" moves the right wall; the plot counts hits on it per unit of wall per second.
val imageW = 640
val imageH = 300
val n = 240
val r = 3.0
val x = DoubleArray(n) { 30 + Math.random() * 580 }
val y = DoubleArray(n) { 20 + Math.random() * 260 }
val vx = DoubleArray(n)
val vy = DoubleArray(n)
n { i ->
    val a = Math.random() * 2 * PI
    vx[i] = 2 * cos(a)
    vy[i] = 2 * sin(a)
}

var speed = 1.0
var length = 1.0
var follow = false
val trail = mutableListOf<Pair<Double, Double>>()
val flashes = mutableListOf<DoubleArray>() // x, y, age
val hitsPerFrame = mutableListOf<Double>()
val rate = mutableListOf<Double>()

val px = IntArray(imageW * imageH)
fun dot(cx: Double, cy: Double, radius: Double, argb: Int) {
    for (j in (cy - radius).toInt()..(cy + radius).toInt()) for (i in (cx - radius).toInt()..(cx + radius).toInt()) {
        if (i in 0 until imageW && j in 0 until imageH && (i - cx) * (i - cx) + (j - cy) * (j - cy) <= radius * radius)
            px[j * imageW + i] = argb
    }
}

// The picture, then the plot, then the controls, in that order down the output.
display("box", PngEncoder.encode(px, imageW, imageH))
fun plot() = showSignals(
    "hits on the right wall" to rate.takeLast(120),
    channel = "rate",
    width = 640,
    height = 200,
    yTitle = "pressure",
)
plot()
controlGroup {
    slider("speed", 0.0, 2.0, step = 0.01, initialValue = 1.0) { speed = it }
    slider("length", 0.3, 1.0, step = 0.01, initialValue = 1.0) { length = it }
    checkbox("follow one") {
        follow = it
        trail.clear()
    }
}

var frameCount = 0
// The simulation steps sixty times a second of animation time, however often frames arrive.
var clock = AnimationClock.nowSeconds()
val left = 20.0
val top = 10.0
val bottom = 290.0
fun right() = left + 600 * length

fun step() {
    val right = right()
    var hits = 0
    val steps = 2
    repeat(steps) {
        n { i ->
            x[i] += vx[i] * speed / steps
            y[i] += vy[i] * speed / steps
            if (x[i] < left + r) {
                x[i] = left + r
                vx[i] = abs(vx[i])
                flashes += doubleArrayOf(left, y[i], 0.0)
            }
            if (x[i] > right - r) {
                // A molecule the moving wall swept up is pushed back in, but that is not a hit.
                val swept = x[i] - vx[i] * speed / steps > right - r
                x[i] = right - r
                vx[i] = -abs(vx[i])
                if (!swept) {
                    hits++
                    flashes += doubleArrayOf(right, y[i], 0.0)
                }
            }
            if (y[i] < top + r) {
                y[i] = top + r
                vy[i] = abs(vy[i])
                flashes += doubleArrayOf(x[i], top, 0.0)
            }
            if (y[i] > bottom - r) {
                y[i] = bottom - r
                vy[i] = -abs(vy[i])
                flashes += doubleArrayOf(x[i], bottom, 0.0)
            }
        }
        // Equal masses colliding head on swap the part of their velocities along the line between them.
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = x[j] - x[i]
            val dy = y[j] - y[i]
            val d2 = dx * dx + dy * dy
            if (d2 < 4 * r * r && d2 > 1e-9) {
                val d = sqrt(d2)
                val nx = dx / d
                val ny = dy / d
                val approach = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
                if (approach > 0) {
                    vx[i] -= approach * nx
                    vy[i] -= approach * ny
                    vx[j] += approach * nx
                    vy[j] += approach * ny
                }
            }
        }
    }
    // A moving wall heats or cools the gas; hold the average speed steady so only the squeeze shows.
    val mean = (0 until n).sumOf { sqrt(vx[it] * vx[it] + vy[it] * vy[it]) } / n
    n { i ->
        vx[i] *= 2 / mean
        vy[i] *= 2 / mean
    }

    if (speed > 0) {
        hitsPerFrame += hits / speed
        if (hitsPerFrame.size > 240) hitsPerFrame.removeAt(0)
    }
    if (follow) {
        trail += x[0] to y[0]
        if (trail.size > 1500) trail.removeAt(0)
    }
}

loop {
    val now = AnimationClock.nowSeconds()
    var caught = 0
    while (clock < now && caught < 6) {
        clock += 1 / 60.0
        step()
        caught++
    }
    if (clock < now) clock = now
    val right = right()
    px.fill(0xff050505.toInt())
    for (j in top.toInt()..bottom.toInt()) for (i in left.toInt()..right.toInt()) px[j * imageW + i] =
        0xff101418.toInt()
    trail.forEach { (tx, ty) -> dot(tx, ty, 0.8, 0xffff9933.toInt()) }
    n { i -> dot(x[i], y[i], r, if (follow && i == 0) 0xffff9933.toInt() else 0xff66e6ff.toInt()) }
    flashes.forEach { f ->
        val glow = (255 * (1 - f[2] / 8)).toInt().coerceIn(0, 255)
        dot(f[0], f[1], 4.0, (0xff shl 24) or (glow shl 16) or (glow shl 8) or glow)
        f[2] += 1.0
    }
    flashes.removeAll { it[2] > 8 }
    display("box", PngEncoder.encode(px, imageW, imageH))
    // A picture this size is a large message; about twenty a second is what the page keeps up with.
    delay(50)

    frameCount++
    if (frameCount % 5 == 0 && hitsPerFrame.isNotEmpty()) {
        // Hits per frame of time on the right wall, divided by the wall's height: pressure, in our units.
        rate += 1000 * hitsPerFrame.average() / (bottom - top)
        plot()
    }
}
```

## 24. pulsing_sphere

<!-- field-box id="es_pulsing_sphere" index="23" -->

```edn [field-box id="es_pulsing_sphere" role="properties"]
{"id" "es_pulsing_sphere" "index" 23 "name" "pulsing_sphere" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pulsing_sphere" "_tombstone" false "frame" #afield/rect [900.0 380.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "es_pulsing_sphere_code_.kt" "lines" 48 "characters" 1793 "sha256" "e8c754dceee256e17c04ad0bdf064ddb0c3b655a6f393b0464810e0fc69914ed"}]}
```

```kotlin [field-property box="es_pulsing_sphere" name="code" path="es_pulsing_sphere_code_.kt"]
import afield.media.image.util.PngEncoder

/*
A sphere swelling and shrinking, seen in cross-section: red is pressure above the usual, blue below.
The picture is four metres across, and the bar at the bottom is one metre. Time runs slowed down,
half a cycle a second whatever the frequency, so only the spacing of the rings changes with it.
*/
val imageW = 640
val imageH = 300
val px = IntArray(imageW * imageH)
var hz = 343.0
var phase = 0.0

fun paint() {
    val wavelength = 160 * 343 / hz // pixels: 160 to the metre
    val cx = 160.0
    val cy = 150.0
    val r0 = 18 + 4 * sin(2 * PI * phase)
    for (j in 0 until imageH) for (i in 0 until imageW) {
        val r = sqrt((i - cx) * (i - cx) + (j - cy) * (j - cy))
        px[j * imageW + i] =
            if (r < r0) 0xff9aa4ad.toInt()
            else {
                // The swing falls with distance; in a flat picture of a sphere, as one over the distance.
                val p = (60 / r).coerceAtMost(1.0) * sin(2 * PI * ((r - r0) / wavelength - phase))
                val red = (255 * p.coerceAtLeast(0.0)).toInt()
                val blue = (255 * (-p).coerceAtLeast(0.0)).toInt()
                (0xff shl 24) or (red shl 16) or (((red + blue) / 4) shl 8) or blue
            }
    }
    for (j in 284..287) for (i in 20..180) px[j * imageW + i] = -1
    display("field", PngEncoder.encode(px, imageW, imageH))
}

paint()
controlGroup {
    slider("frequency", 86.0, 1372.0, step = 1.0, initialValue = 343.0, law = ControlLaw.LOGARITHMIC) { hz = it }
}

var clock = AnimationClock.nowSeconds()
loop {
    val now = AnimationClock.nowSeconds()
    phase += 0.5 * (now - clock)
    clock = now
    paint()
    // A picture this size is a large message; about twelve a second is what the page keeps up with.
    delay(80)
}
```

