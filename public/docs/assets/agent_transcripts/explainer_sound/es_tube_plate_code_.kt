// Slices of air in a tube. Each line is one slice; the plate at the left end pushes on the first,
// each slice pushes on the next, and the far end lets the disturbance out instead of echoing it.
val n = 80
val gap = 7.0               // pixels between slices at rest
val u = DoubleArray(n)      // how far each slice has moved from rest, in pixels
val v = DoubleArray(n)
var target = 0.0
var plate = 0.0
var colour = false

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline { rect(0.0, 0.0, 640.0, 260.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.0) }

fun draw() {
    val slices = (1 until n - 1).map { i ->
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
        moveTo(30.0, 50.0); lineTo(630.0, 50.0)
        moveTo(30.0, 210.0); lineTo(630.0, 210.0)
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
