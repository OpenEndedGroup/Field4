/*
Each factory project rendered offline: load it, let it settle for half a second, press
PLAY, then render three seconds at 48 kHz and print the level of every 100 ms as a bar.
No audio device is involved, so this runs anywhere.

Each project gets its own simulator, closed at the end, so nothing here touches the shared
one the box to the left plays.
*/
val bars = " ▁▂▃▄▅▆▇█"
val l = FloatArray(4800)
val r = FloatArray(4800)

for (slot in 0 until 4) {
    val sim = FM1Sim.create()
    sim.preset(slot)
    repeat(5) { sim.render(l, r, 4800, 48000f) }
    sim.play()
    val levels = (0 until 30).map {
        sim.render(l, r, 4800, 48000f)
        kotlin.math.sqrt(l.indices.sumOf { i -> (l[i] * l[i] + r[i] * r[i]).toDouble() } / (2 * l.size))
    }
    val trace = levels.joinToString("") { bars[(it / 0.14 * 8).toInt().coerceIn(0, 8)].toString() }
    println("project $slot  playing=${sim.isPlaying}  $trace")
    sim.close()
}
