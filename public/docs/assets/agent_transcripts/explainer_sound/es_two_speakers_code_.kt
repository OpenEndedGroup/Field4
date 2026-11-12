/*
Two speakers on the left play the same tone. Each faint ring is a crest of one speaker's wave, a
wavelength apart; the orange curves are where a crest from one always meets a trough from the
other, so the two cancel. Drag the listener (the orange point). With "sound" on you hear the tone
at the loudness it has there. The picture is ten metres across.
*/
val metre = 64.0
var hz = 343.0
var ear = Pair(5.0, 0.6) // metres from the midpoint between the speakers
var on = false
val pitch = Param(343f)
val level = Param(0f)
val speakers = [0.4 to -0.9, 0.4 to 0.9] // metres

fun px(x: Double) = metre * x
fun py(y: Double) = 150 - metre * y
fun distance(x: Double, y: Double, s: Pair<Double, Double>) =
    sqrt((x - s.first) * (x - s.first) + (y - s.second) * (y - s.second))

// How big the pressure swing is at (x, y): the two waves added with their phases.
fun loudness(x: Double, y: Double): Double {
    val k = 2 * PI * hz / 343
    var re = 0.0
    var im = 0.0
    speakers.forEach { s ->
        val r = distance(x, y, s).coerceAtLeast(0.1)
        re += cos(k * r) / sqrt(r)
        im += sin(k * r) / sqrt(r)
    }
    return sqrt(re * re + im * im)
}

// A circle, kept to the part inside the picture.
fun ring(cx: Double, cy: Double, r: Double) = fline {
    var drawing = false
    for (i in 0..120) {
        val a = 2 * PI * i / 120
        val x = cx + r * cos(a)
        val y = cy + r * sin(a)
        val inside = x in 0.0..640.0 && y in 0.0..300.0
        if (inside && drawing) lineTo(x, y) else if (inside) moveTo(x, y)
        drawing = inside
    }
}

// Where the lower speaker is farther than the upper by exactly `gap` metres: for each step across,
// the height that gives that difference, found by halving (the difference only grows with height).
fun quiet(gap: Double) = fline {
    var started = false
    for (i in 1..120) {
        val x = 0.4 + 9.6 * i / 120
        var lo = -2.4
        var hi = 2.4
        fun diff(y: Double) = distance(x, y, speakers[0]) - distance(x, y, speakers[1])
        if ((diff(lo) - gap) * (diff(hi) - gap) > 0) {
            started = false
            continue
        }
        repeat(30) {
            val mid = (lo + hi) / 2
            if (diff(mid) < gap) lo = mid else hi = mid
        }
        if (started) lineTo(px(x), py(lo)) else moveTo(px(x), py(lo))
        started = true
    }
}

fun update() {
    pitch.base = hz.toFloat()
    level.base = if (on) (0.15 * (loudness(ear.first, ear.second) / 1.6).coerceAtMost(1.0)).toFloat() else 0f
}

fun show() {
    val wavelength = 343 / hz
    val frame = fline {
        rect(0.0, 0.0, 640.0, 300.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.0)
    }
    val crests = speakers.flatMap { s ->
        (1..(11 / wavelength).toInt()).map { k ->
            ring(px(s.first), py(s.second), metre * k * wavelength).apply {
                thickness = 1.0
                color = vec(0.4, 0.9, 1.0, 0.35)
            }
        }
    }
    // A path difference can be no larger than the 1.8 m between the speakers.
    val quietLines =
        (-6..5)
            .map { m -> (m + 0.5) * wavelength }
            .filter { abs(it) < 1.8 }
            .map {
                quiet(it).apply {
                    thickness = 2.5
                    color = vec(1.0, 0.6, 0.2, 0.7)
                }
            }
    val boxes = fline {
        speakers.forEach { (x, y) -> rect(px(x) - 10, py(y) - 14, 10.0, 28.0) }
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.9)
    }
    val scenery = ([frame] + crests + quietLines + boxes).onEach { it.fixed = true }
    val listener = fline {
        moveTo(px(ear.first), py(ear.second))
        pointed = true
        pointSize = 3.5
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(scenery + listener, channel = "field", w = 640.0, h = 300.0, editable = true) { edited ->
        val p = edited.last().nodes.first().to
        ear = (p.x / metre).coerceIn(0.5, 9.9) to ((150 - p.y) / metre).coerceIn(-2.3, 2.3)
        update()
    }
}

show()
update()
controlGroup {
    slider("frequency", 100.0, 1000.0, step = 1.0, initialValue = 343.0, law = ControlLaw.LOGARITHMIC) {
        hz = it
        update()
        show()
    }
    checkbox("sound") {
        on = it
        update()
    }
}

audio(SineOsc(pitch), gain = level)
