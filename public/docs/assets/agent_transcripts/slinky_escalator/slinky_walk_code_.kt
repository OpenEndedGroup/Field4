import afield.simulation.physics.*

// Lengths are in coil diameters. The slow-motion factor changes only how fast the picture runs.
var run = 1.5f
var rise = 1.1f
var stiffness = 32000f
var stepPeriod = 0f
var slowMotion = 5

fun start() = SlinkyOnStairs(run = run, rise = rise, stiffness = stiffness, stepPeriod = stepPeriod)
var scene = start()

controlGroup("slinky") {
    slider("tread depth", min = 1.2, max = 2.6, step = 0.05, initialValue = 1.5) {
        run = it.toFloat()
        scene = start()
    }
    slider("step rise", min = 0.6, max = 3.0, step = 0.05, initialValue = 1.1) {
        rise = it.toFloat()
        scene = start()
    }
    slider("spring constant", min = 8000.0, max = 48000.0, step = 1000.0, initialValue = 32000.0) {
        stiffness = it.toFloat()
        scene = start()
    }
    // The escalator: 0 is still stairs; about 0.09 matches the default walk.
    slider("seconds per step", min = 0.0, max = 0.3, step = 0.005, initialValue = 0.0) {
        stepPeriod = it.toFloat()
        scene = start()
    }
    slider("slow motion", min = 1.0, max = 10.0, step = 1.0, initialValue = 5.0) { slowMotion = it.toInt() }
    button("start again") { down -> if (down) scene = start() }
}

// One frame of the picture every sixtieth of a second, each a slowMotion-th of a sixtieth of the walk.
val clock = AnimationClock.nowSeconds()
var shown = 0.0
loop {
    val now = AnimationClock.nowSeconds() - clock
    var catchUp = 0
    while (shown < now && catchUp++ < 4) {
        scene.step(1f / 60f / slowMotion)
        shown += 1.0 / 60.0
    }
    shown = maxOf(shown, now - 0.1)

    val c = scene.slinky.centroid()
    val carried = scene.stairsTravelled - scene.stepsDescended
    showPlane(
        scene.lines(),
        window = Rect(c.x - 5.0, c.y - 3.5, 10.0, 7.0),
        width = 720,
        height = 504,
        title = "a slinky walking down stairs · 1/$slowMotion speed",
        caption =
            "t = ${scene.time.f2} s · ${scene.flips} flips · ${scene.stepsDescended.f1} steps down" +
                (if (scene.stepPeriod > 0f) " · carried up ${carried.f1}" else ""),
    )
}
