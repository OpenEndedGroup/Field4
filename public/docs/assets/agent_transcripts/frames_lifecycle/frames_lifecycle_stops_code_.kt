// After six seconds, end() cancels this animation. Its .end block paints the red
// final frame before the loop stops.
val started = AnimationClock.nowSeconds()
val stopAfter = 6.0
var nextDraw = 0.0

fun disc(cx: Double, cy: Double, r: Double, rgba: Vec4) = fline {
    circle(cx, cy, r)
    color = rgba
    filled = true
    stroked = false
}

val draw: (Double, Boolean) -> Unit = { elapsed, stopped ->
    val accent = if (stopped) vec(1.0, 0.28, 0.30, 1.0) else vec(0.48, 0.85, 1.0, 1.0)
    val quiet = if (stopped) vec(1.0, 0.28, 0.30, 0.55) else vec(0.55, 0.62, 0.72, 0.7)
    val border = fline {
        rect(6.0, 6.0, 588.0, 238.0)
        color = quiet
        thickness = 1.4
        stroked = true
        filled = false
    }
    val track = fline {
        moveTo(60.0, 90.0)
        lineTo(540.0, 90.0)
        color = quiet
        thickness = 1.4
    }
    val gutter = fline {
        rect(60.0, 165.0, 480.0, 40.0)
        color = quiet
        thickness = 1.0
        stroked = true
        filled = false
    }
    // How far through the six seconds we are. It fills, and then it stops filling.
    val progress = fline {
        val w = 480.0 * (elapsed / stopAfter).coerceIn(0.0, 1.0)
        rect(60.0, 165.0, w, 40.0)
        color = accent
        filled = true
        stroked = false
    }
    // Position from the clock, not from frameNumber — so it moves at the same speed
    // whatever rate this machine's Runloop happens to be ticking at.
    val x = 60.0 + 480.0 * (0.5 - 0.5 * kotlin.math.cos(elapsed * 0.55))
    lines(
        border,
        track,
        gutter,
        progress,
        disc(x, 90.0, 14.0, accent),
        channel = "main",
        w = 600.0,
        h = 250.0,
        fitToSize = true,
    )
}

animate {
    val elapsed = AnimationClock.nowSeconds() - started
    if (elapsed >= nextDraw) {
        draw(elapsed, false)
        nextDraw = elapsed + 1.0 / 30.0
    }
    if (elapsed >= stopAfter) end()
}
    .end {
        // .end { } runs on cancellation — which end() has just caused. Two frames of it, so
        // the red state is definitely painted before the job goes away.
        repeat(2) {
            draw(AnimationClock.nowSeconds() - started, true)
            frame()
        }
    }
