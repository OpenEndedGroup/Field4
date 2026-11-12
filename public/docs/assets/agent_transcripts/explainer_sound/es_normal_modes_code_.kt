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
