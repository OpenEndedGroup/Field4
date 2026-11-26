import afield.geometry.conformal.*

// A grid of small circles pushed through a map. Each comes out as a small ellipse,
// coloured by |μ|: dark blue is still a circle, orange is a long ellipse.
var which = 0
var amount = 0.6
val names = ["z + a·z², conformal", "a stretch along x", "a twist near the middle"]

fun map(x: Double, y: Double): DoubleArray = when (which) {
    0 -> doubleArrayOf(x + 0.35 * amount * (x * x - y * y), y + 0.35 * amount * 2 * x * y)
    1 -> doubleArrayOf(x * (1 + amount), y)
    else -> {
        val turn = 1.8 * amount * exp(-1.5 * (x * x + y * y))
        doubleArrayOf(x * cos(turn) - y * sin(turn), x * sin(turn) + y * cos(turn))
    }
}

// |μ| = |f_z̄| / |f_z|, from the map's derivatives at (x, y).
fun distortion(x: Double, y: Double): Double {
    val h = 1e-5
    val a = map(x + h, y)
    val b = map(x - h, y)
    val c = map(x, y + h)
    val d = map(x, y - h)
    val ux = (a[0] - b[0]) / (2 * h)
    val vx = (a[1] - b[1]) / (2 * h)
    val uy = (c[0] - d[0]) / (2 * h)
    val vy = (c[1] - d[1]) / (2 * h)
    return hypot(ux - vy, vx + uy) / hypot(ux + vy, vx - uy)
}

fun colour(argb: Int) =
    vec(((argb shr 16) and 255) / 255.0, ((argb shr 8) and 255) / 255.0, (argb and 255) / 255.0, 1.0)
// World coordinates to the 640 × 360 picture, y up.
fun sx(x: Double) = 300 + 175 * x
fun sy(y: Double) = 180 - 175 * y

fun draw() {
    val drawn = mutableListOf<FLine>()
    // The square grid the circles sit on, carried by the map.
    for (k in -5..5) for (vertical in [true, false]) drawn += fline {
        for (s in 0..60) {
            val t = -1.0 + 2.0 * s / 60
            val p = if (vertical) map(0.2 * k, 0.7 * t) else map(t, 0.14 * k)
            if (s == 0) moveTo(sx(p[0]), sy(p[1])) else lineTo(sx(p[0]), sy(p[1]))
        }
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    var largest = 0.0
    for (i in -5..5) for (j in -3..3) {
        val cx = 0.2 * i
        val cy = 0.2 * j
        val k = distortion(cx, cy)
        largest = max(largest, k)
        drawn += fline {
            for (s in 0..32) {
                val p = map(cx + 0.07 * cos(2 * PI * s / 32), cy + 0.07 * sin(2 * PI * s / 32))
                if (s == 0) moveTo(sx(p[0]), sy(p[1])) else lineTo(sx(p[0]), sy(p[1]))
            }
            thickness = 2.0
            color = colour(MeshPicture.distortionColor(k, 0.6))
        }
    }
    lines(drawn, channel = "map", w = 640.0, h = 360.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 360.0))
    markdown(
        "readout",
        "**${names[which]}**: largest |μ| = ${largest.f2}, so the longest ellipse is ${dilatation(min(largest, 0.99)).f2} times as long as it is wide",
    )
}

draw()
controlGroup {
    slider("map", 0.0, 2.0, step = 1.0, initialValue = 0.0) {
        which = it.toInt()
        draw()
    }
    slider("amount", 0.0, 1.0, initialValue = amount) {
        amount = it
        draw()
    }
}
