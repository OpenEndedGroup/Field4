/*
A speaker on the left and a listener (the orange point) you drag. The rings are one metre apart,
fainter as the sound spreads; the meters say how late the sound arrives and how much quieter it
is than one metre away. The picture is ten metres across; the bar is one metre.
*/
val metre = 64.0
var ear = Pair(3.0, 0.0) // metres from the speaker
lateinit var delay: MeterHandle
lateinit var level: MeterHandle

// A circle, kept to the part inside the picture.
fun ring(cx: Double, cy: Double, r: Double) = fline {
    var drawing = false
    for (i in 0..180) {
        val a = 2 * PI * i / 180
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        val inside = x in 0.0..640.0 && y in 0.0..300.0
        if (inside && drawing) lineTo(x, y) else if (inside) moveTo(x, y)
        drawing = inside
    }
}

fun measure() {
    val d = sqrt(ear.first * ear.first + ear.second * ear.second).coerceAtLeast(0.1)
    delay.set(1000 * d / 343)
    level.set(-20 * log10(d))
}

fun show() {
    val sx = 24.0
    val sy = 150.0
    val frame = fline {
        rect(0.0, 0.0, 640.0, 300.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.0)
    }
    // Brightness falls as one over the distance, as the pressure swing does.
    val rings =
        (1..10).map { m ->
            ring(sx, sy, metre * m).apply {
                thickness = 2.0
                color = vec(0.4, 0.9, 1.0, 0.25 + 0.75 / m)
            }
        }
    val speaker = fline {
        rect(sx - 16, sy - 22, 16.0, 44.0)
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.9)
    }
    val path = fline {
        moveTo(sx, sy)
        lineTo(sx + metre * ear.first, sy - metre * ear.second)
        thickness = 1.5
        color = vec(1.0, 0.6, 0.2, 0.5)
    }
    val scale = fline {
        moveTo(40.0, 285.0)
        lineTo(40 + metre, 285.0)
        thickness = 3.0
        color = vec(1.0, 1.0, 1.0, 0.8)
    }
    val scenery = ([frame, speaker, path, scale] + rings).onEach { it.fixed = true }
    val listener = fline {
        moveTo(sx + metre * ear.first, sy - metre * ear.second)
        pointed = true
        pointSize = 3.5
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(scenery + listener, channel = "room", w = 640.0, h = 300.0, editable = true) { edited ->
        val p = edited.last().nodes.first().to
        ear = ((p.x - sx) / metre).coerceIn(0.3, 9.6) to ((sy - p.y) / metre).coerceIn(-2.2, 2.2)
        measure()
        show()
    }
}

show()
controlGroup {
    delay = meter("delay, milliseconds", 0.0, 30.0, initialValue = 1000 * 3.0 / 343)
    level = meter("level, dB from 1 m", -20.0, 6.0, initialValue = -20 * log10(3.0))
}
measure()
