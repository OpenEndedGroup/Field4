/*
Prism Choir: draw long arcs or loops.

Run this box to take the pen. Press Alt+D, choose the pencil and draw on the empty canvas to
the right. Each stroke comes alive: this block runs once for it (its shape sets up a synth
choir: length is loudness, rise is brightness, direction is pan), and draw { } runs sixty
times a second, returning the lines to show. The louder the choir, the wider the ribbon swings.

Edit this box and run it again: strokes you draw from then on use the new code, and the ones
already on the canvas keep theirs. Run another box to give it the pen; clearStrokes() wipes
the page. Local variables here belong to one stroke, shared by its sound and its drawing.
*/
eachStroke(trail = 0.18) {
    val first = points.first()
    val last = points.last()
    val span = min(1.0, length(curve) / 520)
    var panning = ((last.x - first.x) / 260).coerceIn(-1.0, 1.0)
    var height = min(1.0, abs(last.y - first.y) / 240)
    var flow = 0.0
    var spark = 0.0
    fun toPan(v: Double) = 0.5 + v.coerceIn(-1.0, 1.0) * 0.5

    val choir = pat("c4 e4 g4 b4") with s("sawtooth") with gain(signal { 0.06 + span * 0.16 }) with
        lpf(signal { 240 + height * 1600 + spark * 1400 }) with pan(signal { toPan(panning) }) with room(0.45)
    val bass = pat("c3 g3 c4 g3") with s("triangle") with gain(signal { 0.03 + flow * 0.12 }) with
        lpf(signal { 180 + spark * 900 }) with pan(signal { toPan(panning * -0.6) })
    sound(stack(choir, bass))

    draw { t ->
        val base = resample(points, 72)
        flow = min(1.0, length(base) / 420)
        panning = ((base.last().x - base.first().x) / 260.0).coerceIn(-1.0, 1.0)
        height = min(1.0, abs(base.last().y - base.first().y) / 240.0)

        val audioHit = min(1.0, rms() * 7)
        val hue = 205 + audioHit * 80 + t * 20
        var motion = 0.0
        val ribbon = ArrayList<Vec2>()
        val sparks = FLine().apply { pointed = true; stroked = false }
        for (i in base.indices) {
            val p = base[i]
            val n = normalAt(base, i)
            val wave = (triWave(t * 0.48 + i * 0.08 + audioHit * 0.25) * 0.65 + driftNoise(i * 0.21 + audioHit * 0.5, t, 0.9)) *
                (7 + audioHit * 60) * envelope(i, base.size)
            motion += abs(wave)
            ribbon += vec(p.x + n.x * wave, p.y + n.y * wave)
            if (i % 8 == 0) {
                val size = 1.1 + max(audioHit, min(1.0, abs(wave) / 28)) * 6
                sparks.dot(p.x - n.x * wave * 0.35, p.y - n.y * wave * 0.35, size, cssHsla(hue + i * 3 + 40, 0.98, 0.78, 0.86))
            }
        }
        spark = max(audioHit * 0.7, min(1.0, motion / (base.size * 22)))

        val line = smooth(ribbon)
        line.color = cssHsla(hue + base.size * 3, 0.96, 0.68, 0.88)
        line.overshoot = 10.0
        listOf(line, sparks)
    }
}
