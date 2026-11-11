import afield.simulation.optics.*

// The crests of a light wave arriving at glass. In glass the wave is slower, so the crests bunch up;
// arriving at a slant, each crest bends where it crosses, and the wave turns.
val axis = 180.0
val wavelength = 44.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
var angle = 0.0
var index = 1.5
var playing = true
var travelled = 0.0

fun draw() {
    val theta = angle * PI / 180
    val turned = Refraction.snellAngle(theta, 1.0, index)
    val parts = mutableListOf(
        fline {
            rect(0, axis, 640, 180)
            filled = true
            stroked = false
            color = vec(0.4, 0.9, 1.0, 0.12)
        },
    )
    for (crest in Refraction.crossingCrests(theta, index, wavelength, travelled, 640.0, 360.0, axis)) {
        for (segment in listOfNotNull(crest.air, crest.glass)) parts += fline {
            moveTo(segment[0], segment[1])
            lineTo(segment[2], segment[3])
            color = cyan
            thickness = 2.0
        }
    }
    // Which way the wave is going, in air and in glass.
    parts += fline {
        moveTo(320 - 150 * sin(theta), axis - 150 * cos(theta))
        lineTo(320.0, axis)
        lineTo(320 + 150 * sin(turned), axis + 150 * cos(turned))
        color = orange
        thickness = 2.0
    }
    showPlane(
        parts,
        channel = "wave",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 360.0),
        interactive = false,
    )
}

draw()
controlGroup {
    checkbox("playing", initialValue = true) { playing = it }
    slider("angle", 0.0, 70.0, step = 0.5, initialValue = 0.0) {
        angle = it
        draw()
    }
    slider("glass index", 1.0, 2.4, step = 0.01, initialValue = 1.5) {
        index = it
        draw()
    }
}

// Paced by the clock, not by the loop, which runs far faster than the display: the wave moves
// 25 pixels a second, a wavelength in under two seconds, and is redrawn thirty times a second.
var last = AnimationClock.nowSeconds()
var drawnAt = 0.0
loop {
    val now = AnimationClock.nowSeconds()
    if (playing) travelled += 25.0 * (now - last)
    last = now
    if (now - drawnAt >= 1.0 / 30) {
        drawnAt = now
        draw()
    }
}
