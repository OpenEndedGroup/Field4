import afield.simulation.physics.*

// The three things worth turning: the escalator's speed, as seconds per step; the step height;
// and the spring constant between neighbouring coils. Lengths are in coil diameters.
var stepPeriod = 0.575f
var rise = 1.5f
var stiffness = 16000f

fun start() = SlinkyOnStairs(stepPeriod = stepPeriod, rise = rise, stiffness = stiffness)
var scene = start()

controlGroup("escalator") {
    slider("seconds per step", min = 0.2, max = 1.5, step = 0.005, initialValue = 0.575) {
        stepPeriod = it.toFloat()
        scene.stepPeriod = stepPeriod
    }
    slider("step rise", min = 0.8, max = 2.5, step = 0.05, initialValue = 1.5) {
        rise = it.toFloat()
        scene.stairs.rise = rise
    }
    slider("spring constant", min = 4000.0, max = 30000.0, step = 500.0, initialValue = 16000.0) {
        stiffness = it.toFloat()
        scene.slinky.stiffness = stiffness
    }
    button("start again") { down -> if (down) scene = start() }
}

// The loop can come round far more often than 60 times a second; the physics keeps to the clock.
val clock = System.nanoTime()
var simulated = 0.0
loop {
    val now = (System.nanoTime() - clock) / 1e9
    var catchUp = 0
    while (simulated < now && catchUp++ < 4) {
        scene.step()
        simulated += 1.0 / 60.0
    }
    simulated = maxOf(simulated, now - 0.1)

    // Follow the slinky, so a walk that keeps pace stays put and one that loses it drifts off.
    val c = scene.slinky.centroid()
    val behind = scene.stairsTravelled - scene.stepsDescended
    showPlane(
        scene.lines(),
        window = Rect(c.x - 5.0, c.y - 4.0, 10.0, 7.0),
        width = 720,
        height = 504,
        title = "slinky on an up escalator · ${scene.stepPeriod.f3} s per step",
        caption =
            "t = ${scene.time.f1} s · walked ${scene.stepsDescended.f1} steps · " +
                "escalator ${scene.stairsTravelled.f1} · behind by ${behind.f1}",
    )
}
