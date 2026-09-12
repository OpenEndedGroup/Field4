# Field Document: inertial_movement

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. inertial easing — re-aiming without losing speed

<!-- field-box id="inertial_easing" index="0" -->

```edn [field-box id="inertial_easing" role="properties"]
{"id" "inertial_easing" "index" 0 "name" "inertial easing — re-aiming without losing speed" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "inertial easing — re-aiming without losing speed" "_tombstone" false "frame" #afield/rect [80.0 80.0 280.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "inertial_easing_code_.kt" "lines" 156 "characters" 5801 "sha256" "c79001ef5f2fcc8ab649642d0d993747b5f950bfba8e7b8017b6beac5aa9568f"}]}
```

```kotlin [field-property box="inertial_easing" name="code" path="inertial_easing_code_.kt"]
import java.util.concurrent.atomic.AtomicReference

// Launch (Alt-R), open the output disclosure, and drag the sliders.
//
// Both traces run the same curve. `InertialEasing.solve` is asked for the smoothstep that
// is a given distance from its target AND travelling at a given speed, which is what lets
// a blend be re-aimed part-way instead of restarting. The bright trace tells it the speed
// the value already has; the dim one says zero.

val knobs = AtomicReference(listOf(0.35, 0.05))
fun knob(index: Int, value: Double) {
    knobs.updateAndGet { old -> old.mapIndexed { i, v -> if (i == index) value else v } }
}
val jumpWanted = AtomicReference(false)
val keepMoving = AtomicReference(true)

controlGroup("easing") {
    slider("blend time (s)", min = 0.05, max = 1.5, step = 0.01, initialValue = 0.35) { knob(0, it) }
    slider("overshoot", min = 0.0, max = 0.3, step = 0.01, initialValue = 0.05) { knob(1, it) }
    checkbox("target keeps moving", initialValue = true) { keepMoving.set(it) }
    button("jump the target") { if (it) jumpWanted.set(true) }
}
displayOpen("easing")

// Before animating: `inertialTo { }` is the three lines in the loop below, packaged. Driven
// off the same clock with the same blend time the two must agree to the last bit, so this
// is the box checking its own claim rather than asserting it.
run {
    var seconds = 0.0
    var goal = 0.0
    val packaged = inertialTo<Double>(time = 0.35, steps = null, clock = { seconds }) { goal }
    var t = 0.0
    var s = 0.0
    var offset = 0.0
    var value = 0.0
    var aimedAt = 0.0
    var worst = 0.0
    packaged()                                     // prime its clock; nothing has moved yet
    repeat(180) { i ->
        goal = if (i < 30) 0.0 else if (i < 90) 1.0 else -0.5
        if (goal != aimedAt) {
            val solved = InertialEasing.solve(goal - value, InertialEasing.smoothstepDt(t, s))
            t = solved.t
            s = solved.s
            offset = value - InertialEasing.smoothstep(t, s)
            aimedAt = goal
        }
        seconds += 1.0 / 60.0
        t += (1.0 / 60.0) / 0.35
        value = offset + InertialEasing.smoothstep(t, s)
        worst = maxOf(worst, abs(packaged() - value))
    }
    println("inertialTo against the hand-driven solve, 180 frames: worst difference %.3g".format(worst))
}

// Somewhere for `jump the target` to go. A fixed ring rather than a random number, so two
// runs of this box are the same run.
val jumps = listOf(0.8, -0.7, 0.25, -0.95, 0.55)
var jumpIndex = 0

// The inertial arm.
var easedT = 0.0
var easedS = 0.0
var easedOffset = 0.0
var eased = 0.0
// The same curve, always re-solved from a standstill.
var naiveT = 0.0
var naiveS = 0.0
var naiveOffset = 0.0
var naive = 0.0

var aimedAt = 0.0
var goal = 0.0
var phase = 0.0
var reaims = 0
var overshoots = 0

val history = ArrayDeque<Triple<Double, Double, Double>>()
val samples = 300
val view = Rect(0.0, 0.0, 720.0, 420.0)

// y grows DOWN in Field's 2D outputs, so a value climbing the screen is a value subtracted.
fun place(index: Int, value: Double): Pair<Double, Double> =
    (index * (720.0 / samples)) to (210.0 - value * 150.0)

fun trace(pick: (Triple<Double, Double, Double>) -> Double, paint: Vec4, width: Double): FLine {
    val line = FLine()
    history.forEachIndexed { i, sample ->
        val (x, y) = place(i, pick(sample))
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    return line.apply { thickness = width; color = paint }
}

loop {
    val settings = knobs.get()
    val blendTime = settings[0]
    val overshoot = settings[1]
    val dt = 1.0 / 60.0

    if (jumpWanted.getAndSet(false)) {
        goal = jumps[jumpIndex % jumps.size]
        jumpIndex++
    }
    // Ticked on, the target moves every single frame — sixty re-aims a second, which is the
    // case the whole exercise is about.
    if (keepMoving.get()) {
        phase += dt
        goal = sin(phase * 1.7) * 0.8
    }

    if (goal != aimedAt) {
        reaims++
        // The one difference: the velocity the value is actually travelling at...
        val carried = InertialEasing.solve(
            goal - eased, InertialEasing.smoothstepDt(easedT, easedS), overshoot
        )
        easedT = carried.t
        easedS = carried.s
        easedOffset = eased - InertialEasing.smoothstep(easedT, easedS)
        if (carried.t < -0.5) overshoots++

        // ...against a standing start, which is what a one-shot interpolant restarts into.
        val standing = InertialEasing.solve(goal - naive, 0.0, overshoot)
        naiveT = standing.t
        naiveS = standing.s
        naiveOffset = naive - InertialEasing.smoothstep(naiveT, naiveS)
        aimedAt = goal
    }

    val step = dt / max(blendTime, 1e-4)
    easedT += step
    naiveT += step
    eased = easedOffset + InertialEasing.smoothstep(easedT, easedS)
    naive = naiveOffset + InertialEasing.smoothstep(naiveT, naiveS)

    history.addLast(Triple(goal, eased, naive))
    while (history.size > samples) history.removeFirst()

    val zero = fline { moveTo(0, 210); lineTo(720, 210) }
        .apply { thickness = 1.0; color = vec(0.45, 0.45, 0.55, 1.0) }

    showPlane(
        listOf(
            zero,
            trace({ it.first }, vec(0.55, 0.55, 0.65, 1.0), 1.5),
            trace({ it.third }, vec(1.0, 0.45, 0.35, 1.0), 2.0),
            trace({ it.second }, vec(0.35, 0.95, 0.6, 1.0), 3.0),
        ),
        window = view,
        title = "one target, two blends — the bright one is told the speed it already has",
        caption = "grey: target · red: re-solved from a standstill · green: inertial · " +
            "blend %.2f s · overshoot %.2f · %d re-aims, %d of them overshooting"
                .format(blendTime, overshoot, reaims, overshoots),
    )
}
```

## 2. movement model — a character chasing a pad

<!-- field-box id="movement_playground" index="1" -->

```edn [field-box id="movement_playground" role="properties"]
{"id" "movement_playground" "index" 1 "name" "movement model — a character chasing a pad" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "movement model — a character chasing a pad" "_tombstone" false "frame" #afield/rect [2000.0 80.0 280.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "movement_playground_code_.kt" "lines" 142 "characters" 6166 "sha256" "b3ece9c00f5d3d9a8f3232875337812129bff0a4e2527d79e9cae4140417f729"}]}
```

```kotlin [field-property box="movement_playground" name="code" path="movement_playground_code_.kt"]
import java.util.concurrent.atomic.AtomicReference

// Launch (Alt-R), open the output disclosure, and drag the XY pad. Every slider is live.

val view = Rect(0.0, 0.0, 720.0, 420.0)
val home = Vector2f(360f, 210f)

// 0 top speed, 1 acceleration, 2 deceleration, 3 turn strength,
// 4 directional acceleration, 5 acceleration half-life, 6 compensation, 7 rotation half-life
val knobs = AtomicReference(listOf(420.0, 1400.0, 2200.0, 2.0, 0.0, 0.10, 1.0, 0.15))
fun knob(index: Int, value: Double) {
    knobs.updateAndGet { old -> old.mapIndexed { i, v -> if (i == index) value else v } }
}
val pad = AtomicReference(Vector2f(0.55f, -0.35f))
val recentreWanted = AtomicReference(false)

controlGroup("movement") {
    vec2Control("target", initialValue = Vector2f(0.55f, -0.35f),
                xRange = -1.0 to 1.0, yRange = -1.0 to 1.0, step = 0.02) { pad.set(Vector2f(it)) }
    slider("top speed (px/s)", min = 80.0, max = 700.0, step = 10.0, initialValue = 420.0) { knob(0, it) }
    slider("acceleration (px/s²)", min = 200.0, max = 4000.0, step = 50.0, initialValue = 1400.0) { knob(1, it) }
    slider("deceleration (px/s²)", min = 200.0, max = 6000.0, step = 50.0, initialValue = 2200.0) { knob(2, it) }
    slider("turn strength", min = 0.0, max = 16.0, step = 0.25, initialValue = 2.0) { knob(3, it) }
    slider("directional acceleration", min = 0.0, max = 1.0, step = 0.05, initialValue = 0.0) { knob(4, it) }
    slider("acceleration half-life (s)", min = 0.0, max = 0.4, step = 0.005, initialValue = 0.10) { knob(5, it) }
    slider("compensation", min = 0.0, max = 2.0, step = 0.05, initialValue = 1.0) { knob(6, it) }
    slider("rotation half-life (s)", min = 0.02, max = 0.6, step = 0.01, initialValue = 0.15) { knob(7, it) }
    button("recentre") { if (it) recentreWanted.set(true) }
}
displayOpen("movement")

val character = MovementState()
character.placeAt(home)
// The control arm: the same model with the smoothing taken off, so the distance between the
// two characters is exactly what the smoothing is adding.
val ghost = MovementState()
ghost.placeAt(home)

val trail = ArrayDeque<Vector2f>()
val ghostTrail = ArrayDeque<Vector2f>()

fun toward(from: Vector2fc, to: Vector2fc, speed: Float): Vector2f {
    val dx = to.x() - from.x()
    val dy = to.y() - from.y()
    val distance = sqrt(dx * dx + dy * dy)
    // A dead zone, so arriving is arriving rather than an endless twitch about the target.
    if (distance < 3f) return Vector2f()
    // Full deflection toward the target, easing off over the last few pixels — this is the
    // stick, and it is the only thing the model is told.
    val wanted = min(speed, distance * 5f)
    return Vector2f(dx / distance * wanted, dy / distance * wanted)
}

fun arrow(state: MovementState, length: Float): FLine {
    val ahead = state.heading
    val across = Vector2f(-ahead.y, ahead.x)
    val tip = state.position + ahead * length
    val left = state.position - ahead * (length * 0.5f) + across * (length * 0.42f)
    val right = state.position - ahead * (length * 0.5f) - across * (length * 0.42f)
    return fline {
        moveTo(tip.x, tip.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        lineTo(tip.x, tip.y)
    }
}

fun path(points: ArrayDeque<Vector2f>): FLine {
    val line = FLine()
    points.forEachIndexed { i, p -> if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y) }
    return line
}

loop {
    val settings = knobs.get()
    val dt = 1f / 60f

    if (recentreWanted.getAndSet(false)) {
        character.placeAt(home)
        ghost.placeAt(home)
        trail.clear()
        ghostTrail.clear()
    }

    // The pad's y is up-positive and the plane view's y grows down, so the pad is subtracted.
    val aim = pad.get()
    val target = Vector2f(home.x + aim.x * 300f, home.y - aim.y * 170f)

    val params = MovementParams(
        acceleration = settings[1].toFloat(),
        deceleration = settings[2].toFloat(),
        directionalAcceleration = settings[4].toFloat(),
        turnStrength = settings[3].toFloat(),
        accelerationHalflife = settings[5].toFloat(),
        decelerationHalflife = (settings[5] * 0.5).toFloat(),
        accelerationCompensation = settings[6].toFloat(),
        rotationHalflife = settings[7].toFloat(),
    )

    val requested = toward(character.position, target, settings[0].toFloat())
    character.update(requested, dt, params)

    val ghostRequested = toward(ghost.position, target, settings[0].toFloat())
    ghost.update(
        ghostRequested, dt,
        params.copy(accelerationHalflife = 0f, decelerationHalflife = 0f, accelerationCompensation = 0f),
    )

    trail.addLast(Vector2f(character.position))
    ghostTrail.addLast(Vector2f(ghost.position))
    while (trail.size > 240) trail.removeFirst()
    while (ghostTrail.size > 240) ghostTrail.removeFirst()

    val crosshair = fline {
        moveTo(target.x - 10f, target.y)
        lineTo(target.x + 10f, target.y)
        moveTo(target.x, target.y - 10f)
        lineTo(target.x, target.y + 10f)
    }.apply { thickness = 1.5; color = vec(0.95, 0.85, 0.35, 1.0) }

    val border = fline {
        moveTo(1, 1); lineTo(719, 1); lineTo(719, 419); lineTo(1, 419); lineTo(1, 1)
    }.apply { thickness = 1.0; color = vec(0.3, 0.3, 0.38, 1.0) }

    val behind = target.distance(character.position)

    showPlane(
        listOf(
            border,
            crosshair,
            path(ghostTrail).apply { thickness = 1.0; color = vec(0.5, 0.5, 0.6, 1.0) },
            path(trail).apply { thickness = 1.5; color = vec(0.35, 0.75, 0.95, 1.0) },
            arrow(ghost, 13f).apply { thickness = 1.0; color = vec(0.55, 0.55, 0.65, 1.0) },
            arrow(character, 18f).apply { thickness = 2.5; color = vec(0.35, 0.95, 0.6, 1.0) },
        ),
        window = view,
        title = "drag the pad — green is the model, grey is the same model unsmoothed",
        caption = ("%.0f px/s · %.0f px behind the target · turn %.2f · directional %.2f · " +
            "half-life %.3f s · compensation %.2f")
            .format(character.speed, behind, settings[3], settings[4], settings[5], settings[6]),
    )
}
```

