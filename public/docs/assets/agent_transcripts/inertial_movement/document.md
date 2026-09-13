# Field Document: inertial_movement

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. what restarting a blend costs

<!-- field-box id="inertial_vs_restarting" index="0" -->

### Narrative

From `code`

The same target and two identical interpolants. The only difference is the `startAt`
line: restarting a blend throws away the speed it had, which is what any one-shot ease
does every time you re-aim it. Left alone, `inertialTo` re-solves and keeps that speed.

```edn [field-box id="inertial_vs_restarting" role="properties"]
{"id" "inertial_vs_restarting" "index" 0 "name" "what restarting a blend costs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "what restarting a blend costs" "_tombstone" false "frame" #afield/rect [80.0 900.0 310.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "inertial_vs_restarting_code_.kt" "lines" 28 "characters" 1057 "sha256" "e47d04b2046bd298ed52af210aedb483ad9012268f9a63e6ae26e0955704f16a"}]}
```

```kotlin [field-property box="inertial_vs_restarting" name="code" path="inertial_vs_restarting_code_.kt"]
// The same target and two identical interpolants. The only difference is the `startAt`
// line: restarting a blend throws away the speed it had, which is what any one-shot ease
// does every time you re-aim it. Left alone, `inertialTo` re-solves and keeps that speed.
var goal = 0.0
val carried = inertialTo<Double>(time = 0.4) { goal }
val restarted = inertialTo<Double>(time = 0.4) { goal }

val target = mutableListOf<Double>()
val reSolved = mutableListOf<Double>()
val fromRest = mutableListOf<Double>()

loop {
    // A target that never holds still, so every single frame is a re-aim.
    goal = sin(frameNumber / 40.0) * 0.8
    (restarted as NeedsStartingValue<Double>).startAt(fromRest.lastOrNull() ?: 0.0)

    target += goal
    reSolved += carried()
    fromRest += restarted()

    showSignals(
        "goal" to target.takeLast(300),
        "left to re-solve" to reSolved.takeLast(300),
        "restarted every frame" to fromRest.takeLast(300),
        title = "re-solving a blend against restarting it",
        xTitle = "frame",
    )
}
```

## 2. movement model — a character chasing a pad

<!-- field-box id="movement_playground" index="1" -->

### Narrative

From `code`

Drag the XY pad; the character chases it. Every slider is live — one coroutine on the
Framer, so a plain `var` per control is all this needs.

```edn [field-box id="movement_playground" role="properties"]
{"id" "movement_playground" "index" 1 "name" "movement model — a character chasing a pad" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "movement model — a character chasing a pad" "_tombstone" false "frame" #afield/rect [80.0 1750.0 390.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "movement_playground_code_.kt" "lines" 91 "characters" 4324 "sha256" "617a72d6c4944dab386a8527b508fdfac8fd789cabb7c653b1f214c53d606e34"}]}
```

```kotlin [field-property box="movement_playground" name="code" path="movement_playground_code_.kt"]
// Drag the XY pad; the character chases it. Every slider is live — one coroutine on the
// Framer, so a plain `var` per control is all this needs.
val view = Rect(0.0, 0.0, 720.0, 420.0)
val home = Vector2f(360f, 210f)

var pad = Vector2f(0.55f, -0.35f)
var topSpeed = 420.0
var acceleration = 1400.0
var deceleration = 2200.0
var turn = 2.0
var directional = 0.0
var halflife = 0.10
var compensation = 1.0

controlGroup("movement") {
    vec2Control("target", initialValue = pad, xRange = -1.0 to 1.0, yRange = -1.0 to 1.0,
                step = 0.02) { pad = it }
    slider("top speed (px/s)", min = 80.0, max = 700.0, step = 10.0, initialValue = topSpeed) { topSpeed = it }
    slider("acceleration (px/s²)", min = 200.0, max = 4000.0, step = 50.0, initialValue = acceleration) { acceleration = it }
    slider("deceleration (px/s²)", min = 200.0, max = 6000.0, step = 50.0, initialValue = deceleration) { deceleration = it }
    slider("turn strength", min = 0.0, max = 16.0, step = 0.25, initialValue = turn) { turn = it }
    slider("directional acceleration", min = 0.0, max = 1.0, step = 0.05, initialValue = directional) { directional = it }
    slider("smoothing half-life (s)", min = 0.0, max = 0.4, step = 0.005, initialValue = halflife) { halflife = it }
    slider("lag compensation", min = 0.0, max = 2.0, step = 0.05, initialValue = compensation) { compensation = it }
}
displayOpen("movement")

val character = MovementState().apply { placeAt(home) }
// The control arm: the same model with the smoothing taken off, so the distance between
// the two characters is exactly what the smoothing is adding.
val ghost = MovementState().apply { placeAt(home) }
val trail = ArrayDeque<Vector2f>()

// The stick: full deflection toward the target, easing off over the last few pixels.
// Turning a position into a desired *velocity* is the whole interface to the model.
fun stick(from: Vector2fc, to: Vector2fc): Vector2f {
    val gap = to - from
    val distance = gap.length()
    return if (distance < 3f) Vector2f() else gap * (min(topSpeed.toFloat(), distance * 5f) / distance)
}

// A character is where it is and where it is pointing.
fun whisker(state: MovementState, length: Float) = fline {
    moveTo(state.position.x, state.position.y)
    lineTo(state.position.x + state.heading.x * length, state.position.y + state.heading.y * length)
}

loop {
    val target = Vector2f(home.x + pad.x * 300f, home.y - pad.y * 170f)
    val params = MovementParams(
        acceleration = acceleration.toFloat(),
        deceleration = deceleration.toFloat(),
        turnStrength = turn.toFloat(),
        directionalAcceleration = directional.toFloat(),
        accelerationHalflife = halflife.toFloat(),
        decelerationHalflife = (halflife * 0.5).toFloat(),
        accelerationCompensation = compensation.toFloat(),
    )

    character.update(stick(character.position, target), 1f / 60f, params)
    ghost.update(
        stick(ghost.position, target), 1f / 60f,
        params.copy(accelerationHalflife = 0f, decelerationHalflife = 0f, accelerationCompensation = 0f),
    )

    trail.addLast(Vector2f(character.position))
    while (trail.size > 240) trail.removeFirst()

    val path = FLine()
    trail.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }

    showPlane(
        listOf(
            fline {
                moveTo(target.x - 10f, target.y); lineTo(target.x + 10f, target.y)
                moveTo(target.x, target.y - 10f); lineTo(target.x, target.y + 10f)
            }.apply { thickness = 1.5; color = vec(0.95, 0.85, 0.35, 1.0) },
            path.apply { thickness = 1.5; color = vec(0.35, 0.75, 0.95, 1.0) },
            whisker(ghost, 14f).apply { thickness = 1.0; color = vec(0.55, 0.55, 0.65, 1.0) },
            whisker(character, 22f).apply { thickness = 2.5; color = vec(0.35, 0.95, 0.6, 1.0) },
        ),
        window = view,
        // Inside the 520px output panel, so this box's panel does not overlap its
        // neighbour's in the column to the right.
        width = 500,
        height = 300,
        title = "drag the pad — green is the model, grey is the same model unsmoothed",
        caption = "%.0f px/s · %.0f px behind the target"
            .format(character.speed, target.distance(character.position)),
    )
}
```

## 3. inertialTo — the whole idea

<!-- field-box id="inertial_hello" index="2" -->

### Narrative

From `code`

Launch it (Alt-R). `goal` jumps between two values; reading `eased` arrives there with
inertia instead of snapping, and re-aims mid-flight without losing the speed it had.

```edn [field-box id="inertial_hello" role="properties"]
{"id" "inertial_hello" "index" 2 "name" "inertialTo — the whole idea" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "inertialTo — the whole idea" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "inertial_hello_code_.kt" "lines" 18 "characters" 609 "sha256" "816cb237174d1b1b731c8d210088a93a5591008b7bfcebcc06b0886e8b049809"}]}
```

```kotlin [field-property box="inertial_hello" name="code" path="inertial_hello_code_.kt"]
// Launch it (Alt-R). `goal` jumps between two values; reading `eased` arrives there with
// inertia instead of snapping, and re-aims mid-flight without losing the speed it had.
var goal = 0.0
val eased = inertialTo<Double>(time = 0.4) { goal }
val target = mutableListOf<Double>()
val seen = mutableListOf<Double>()

loop {
    goal = if ((frameNumber / 40) % 2 == 0) 1.0 else -1.0
    target += goal
    seen += eased()
    showSignals(
        "goal" to target.takeLast(240),
        "inertialTo" to seen.takeLast(240),
        title = "one value, chasing a square wave",
        xTitle = "frame",
    )
}
```

