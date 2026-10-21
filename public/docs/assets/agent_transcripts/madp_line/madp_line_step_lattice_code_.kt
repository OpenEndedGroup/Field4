// Step Lattice: left to right is time (twelve steps a bar), up is pitch (fourteen lanes of a
// C major scale). The stroke is snapped onto that grid once, when it is drawn, into a melody
// and a bass line; the grid, the snapped notes and the cursor on the sounding step are drawn
// over it. cycle is the sound clock, so the cursor is the note you hear.
eachStroke(trail = 0.12) {
    val noteRows = listOf("c4", "d4", "e4", "f4", "g4", "a4", "b4", "c5", "d5", "e5", "f5", "g5", "a5", "c6")
    val bassRows = listOf("c3", "d3", "e3", "f3", "g3", "a3", "b3", "c4", "d4", "e4", "f4", "g4", "a4", "c5")
    val steps = 12
    val rows = noteRows.size
    val minX = points.minOf { it.x } - 24
    val maxX = points.maxOf { it.x } + 24
    val minY = points.minOf { it.y } - 28
    val maxY = points.maxOf { it.y } + 28
    val w = max(1.0, maxX - minX)
    val h = max(1.0, maxY - minY)
    fun gx(step: Int) = minX + w * step / (steps - 1)
    fun gy(row: Int) = maxY - h * row / (rows - 1)

    val rowSum = IntArray(steps)
    val count = IntArray(steps)
    val rawX = DoubleArray(steps)
    val rawY = DoubleArray(steps)
    for (p in points) {
        val step = Math.round(clamp01((p.x - minX) / w) * (steps - 1)).toInt()
        rowSum[step] += Math.round((1 - clamp01((p.y - minY) / h)) * (rows - 1)).toInt()
        count[step]++
        rawX[step] += p.x
        rawY[step] += p.y
    }
    val row = IntArray(steps) { if (count[it] > 0) Math.round(rowSum[it].toDouble() / count[it]).toInt() else -1 }
    val density = row.count { it >= 0 } / steps.toDouble()
    val panSigned = clamp01((points.last().x - points.first().x) / max(40.0, w * 0.7)) * 2 - 1
    fun toPan(v: Double) = 0.5 + v.coerceIn(-1.0, 1.0) * 0.5

    var cursorRow = 0.4
    var pulse = 0.2
    val melody = pat(row.joinToString(" ") { if (it < 0) "~" else noteRows[it] }) with s("triangle") with
        gain(signal { 0.02 + density * 0.05 + pulse * 0.08 }) with lpf(signal { 200 + cursorRow * 2600 + pulse * 600 }) with
        pan(toPan(panSigned * 0.7)) with room(0.38)
    val bass = pat(row.joinToString(" ") { if (it < 0) "~" else bassRows[max(0, it - 3)] }) with s("sine") with
        gain(0.01 + density * 0.06) with lpf(signal { 150 + cursorRow * 800 }) with pan(toPan(panSigned * -0.35))
    sound(stack(melody, bass))

    draw { t ->
        val step = floor((cycle % 1.0) * steps).toInt().coerceIn(0, steps - 1)
        val sounding = row[step] >= 0
        cursorRow = if (sounding) row[step] / (rows - 1.0) else 0.15
        pulse = if (sounding) 1.0 else 0.22

        val guides = FLine()
        guides.ignore = true
        for (r in 0 until rows) guides.segment(minX, gy(r), maxX, gy(r), if (r == row[step]) rgba(120, 190, 255, 0.18) else rgba(255, 255, 255, 0.05))
        for (s in 0 until steps) guides.segment(gx(s), minY, gx(s), maxY, if (s == step) rgba(255, 235, 120, 0.32) else rgba(255, 255, 255, 0.06))

        val connectors = FLine()
        connectors.ignore = true
        connectors.color = rgba(255, 215, 120, 0.18)
        val snapped = ArrayList<Vec2>()
        val beads = FLine().apply { pointed = true; stroked = false }
        for (s in 0 until steps) {
            if (row[s] < 0) continue
            connectors.segment(rawX[s] / count[s], rawY[s] / count[s], gx(s), gy(row[s]))
            snapped += vec(gx(s), gy(row[s]))
            if (s == step) beads.dot(gx(s), gy(row[s]), 4.6, rgba(255, 245, 170, 0.96))
            else beads.dot(gx(s), gy(row[s]), 2.6, rgba(255, 160, 120, 0.88))
        }
        val melodyLine = smooth(snapped)
        melodyLine.color = rgba(120, 220, 255, 0.75)
        listOf(guides, connectors, melodyLine, beads)
    }
}
