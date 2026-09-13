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
