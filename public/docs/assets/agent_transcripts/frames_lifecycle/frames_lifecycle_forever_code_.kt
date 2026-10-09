// An animate { } with no end condition. It runs until you press stop, or until you
// re-run this box. The running indicator on the box stays lit the whole time.
val started = AnimationClock.nowSeconds()
/*
Trail length and resolution are a bandwidth decision, not a taste one: every redraw
serialises the whole trail to SVG and pushes it down the output socket. 8 s at 0.06
is ~130 points; 12 s at 0.02 is ~600, enough to saturate the connection.
*/
val trailSeconds = 8.0
val trailStep = 0.06
var nextDraw = 0.0

fun disc(cx: Double, cy: Double, r: Double, rgba: Vec4) = fline {
    circle(cx, cy, r)
    color = rgba
    filled = true
    stroked = false
}

fun border(rgba: Vec4) = fline {
    moveTo(8.0, 8.0)
    lineTo(552.0, 8.0)
    lineTo(552.0, 312.0)
    lineTo(8.0, 312.0)
    close()
    color = rgba
    thickness = 1.2
    stroked = true
    filled = false
}

fun atX(s: Double) = 280.0 + 230.0 * kotlin.math.cos(s * 1.1)
fun atY(s: Double) = 160.0 + 120.0 * kotlin.math.sin(s * 1.7)

val draw: (Double) -> Unit = { t ->
    val trail = fline {
        var s = kotlin.math.max(0.0, t - trailSeconds)
        var first = true
        while (s <= t) {
            if (first) {
                moveTo(atX(s), atY(s))
                first = false
            } else lineTo(atX(s), atY(s))
            s += trailStep
        }
        color = vec(0.30, 0.52, 0.68, 0.55)
        thickness = 1.2
    }
    lines(
        border(vec(0.45, 0.52, 0.62, 0.6)),
        trail,
        disc(atX(t), atY(t), 13.0, vec(0.48, 0.85, 1.0, 1.0)),
        channel = "main",
        w = 560.0,
        h = 320.0,
        fitToSize = true,
    )
}

animate {
    val t = AnimationClock.nowSeconds() - started
    // Gate the redraw on the clock. The loop still advances every Runloop frame —
    // frameNumber proves it — but nobody can see 3000 pictures a second.
    if (t >= nextDraw) {
        draw(t)
        nextDraw = t + 1.0 / 30.0
    }
}
