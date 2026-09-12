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
