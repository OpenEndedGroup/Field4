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
