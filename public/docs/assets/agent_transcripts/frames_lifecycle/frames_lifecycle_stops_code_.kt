/*
The same loop, with an end condition and a cleanup block. Watch it for six seconds:
the disc travels, the bar fills, and then everything freezes and turns red. Nothing
after that is the animation — it is the last frame the .end { } block painted.
*/
val started = AnimationClock.nowSeconds()
val stopAfter = 6.0
var nextDraw = 0.0

fun disc(cx: Double, cy: Double, r: Double, rgba: Vec4) = fline {
    (0..28).forEach { i ->
        val a = i / 28.0 * 2.0 * kotlin.math.PI
        val px = cx + r * kotlin.math.cos(a)
        val py = cy + r * kotlin.math.sin(a)
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
    color = rgba
    filled = true
    stroked = false
}

val draw: (Double, Boolean) -> Unit = { elapsed, stopped ->
    val accent = if (stopped) vec(1.0, 0.28, 0.30, 1.0) else vec(0.48, 0.85, 1.0, 1.0)
    val quiet = if (stopped) vec(1.0, 0.28, 0.30, 0.55) else vec(0.55, 0.62, 0.72, 0.7)
    val border = fline {
        moveTo(6.0, 6.0)
        lineTo(594.0, 6.0)
        lineTo(594.0, 244.0)
        lineTo(6.0, 244.0)
        close()
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
        moveTo(60.0, 165.0)
        lineTo(540.0, 165.0)
        lineTo(540.0, 205.0)
        lineTo(60.0, 205.0)
        close()
        color = quiet
        thickness = 1.0
        stroked = true
        filled = false
    }
    // How far through the six seconds we are. It fills, and then it stops filling.
    val progress = fline {
        val w = 480.0 * (elapsed / stopAfter).coerceIn(0.0, 1.0)
        moveTo(60.0, 165.0)
        lineTo(60.0 + w, 165.0)
        lineTo(60.0 + w, 205.0)
        lineTo(60.0, 205.0)
        close()
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
