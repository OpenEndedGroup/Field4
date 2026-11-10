/*
A room seen from above, and when its echoes reach the listener (orange) after a sound from the
source (white): each tick is one path off the walls, its height how much of the sound is left.
A bigger room spaces them out and stretches the tail; softer walls make each bounce lose more.
The keys play short notes through a reverberator set from the same two sliders.
*/
var size = 0.4
var soft = 0.3
val gates = 3 { Gate() }
val roomSize = Param(0.6f)
val damping = Param(0.4f)

fun draw() {
    val w = 4 + 16 * size // metres
    val h = 0.6 * w
    val reflect = 1 - 0.6 * soft
    val sx = 0.3 * w
    val sy = 0.45 * h
    val lx = 0.7 * w
    val ly = 0.55 * h
    // Mirror the room in its walls again and again: each mirrored source is one bouncing path.
    val direct = sqrt((sx - lx) * (sx - lx) + (sy - ly) * (sy - ly))
    val ticks = mutableListOf<FLine>()
    for (i in -12..12) for (j in -12..12) {
        val ix = i * w + if (i % 2 == 0) sx else w - sx
        val iy = j * h + if (j % 2 == 0) sy else h - sy
        val d = sqrt((ix - lx) * (ix - lx) + (iy - ly) * (iy - ly))
        val t = d / 343
        if (t > 0.6) continue
        // What is left of the sound, against the direct path; drawn as a square root so the tail shows.
        val a = reflect.pow(abs(i) + abs(j)) * direct / d
        ticks += fline {
            moveTo(20 + 1000 * t, 270.0)
            lineTo(20 + 1000 * t, 270 - 100 * sqrt(a))
            thickness = 1.5
            color = vec(0.4, 0.9, 1.0, 1.0)
        }
    }
    val scale = 7.0 // the plan: pixels per metre
    val plan = fline {
        rect(20.0, 10.0, scale * w, scale * h)
        thickness = 2.0
        color = vec(1.0, 1.0, 1.0, 0.35 + 0.5 * (1 - soft))
    }
    val source = fline {
        circle(20 + scale * sx, 10 + scale * sy, 4.0)
        filled = true
        stroked = false
        color = vec(1.0, 1.0, 1.0, 0.9)
    }
    val ear = fline {
        circle(20 + scale * lx, 10 + scale * ly, 4.0)
        filled = true
        stroked = false
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val axis = fline {
        moveTo(20.0, 270.0)
        lineTo(620.0, 270.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val frame = fline {
        rect(0.0, 0.0, 640.0, 280.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.0)
    }
    lines([frame, plan, source, ear, axis] + ticks, channel = "room", w = 640.0, h = 280.0)
    roomSize.base = (0.5 + 0.48 * size).toFloat()
    damping.base = (0.2 + 0.7 * soft).toFloat()
}

draw()
controlGroup {
    keys("low", "middle", "high", letters = "wer") { key, down -> if (down) gates[key].open() else gates[key].close() }
    slider("room size", 0.0, 1.0, step = 0.01, initialValue = 0.4) {
        size = it
        draw()
    }
    slider("soft walls", 0.0, 1.0, step = 0.01, initialValue = 0.3) {
        soft = it
        draw()
    }
}

val notes = [196f, 247f, 294f].mapIndexed { i, hz -> SawOsc(Param(hz)) / lpf(1800) * perc(0.25, 0.005, gates[i]) }
audio(notes.reduce { a, b -> a + b } / 2.0 / FreeVerb(roomSize, damping, Param(0.45f)), gain = 0.15f)
