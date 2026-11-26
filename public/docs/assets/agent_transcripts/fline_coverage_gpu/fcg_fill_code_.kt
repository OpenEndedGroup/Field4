import afield.graphics.nile.FillRule

val W = 160
val H = 100

// Two floats per cell: .x is coverage under non-zero, .y under even-odd. Only the CPU writes it.
var mask = defaultBuffer { size = (W * H).vec2s }.buffer

// The shapes, in cell coordinates.
val pentagram = FLine()
for (k in 0..5) {
    val a = -Math.PI / 2 + k * 4 * Math.PI / 5
    val x = 30.0 + 26.0 * Math.cos(a)
    val y = 52.0 + 26.0 * Math.sin(a)
    if (k == 0) pentagram.moveTo(x, y) else pentagram.lineTo(x, y)
}
val circles = [FLine().circle(78.0, 40.0, 18.0), FLine().circle(94.0, 52.0, 18.0)]
// Outer and inner squares wound the same way: a hole under even-odd only.
val framed = FLine().rect(118.0, 22.0, 36.0, 36.0).rect(128.0, 32.0, 16.0, 16.0)
val wave = FLine().moveTo(64.0, 86.0)
wave.cubicTo(84.0, 56.0, 104.0, 116.0, 124.0, 86.0)
wave.cubicTo(134.0, 70.0, 108.0, 70.0, 116.0, 92.0)
val drawn = mutableListOf<FLine>()
var pending = false

// Draw everything into channel 0 under non-zero and channel 1 under even-odd.
fun paintMask(canvas: BufferCanvas) {
    canvas.fill(canvas.bounds, 0f)
    for ((channel, rule) in [0 to FillRule.NON_ZERO, 1 to FillRule.EVEN_ODD]) {
        canvas.fill(pentagram, 1f, rule, channel = channel)
        canvas.fill(circles, 1f, rule, channel = channel)
        canvas.fill(framed, 1f, rule, channel = channel)
        for (line in drawn) canvas.fill(line, 1f, rule, channel = channel)
        canvas.stroke(wave, width = 3.0, value = 1f, channel = channel)
    }
}
val expected = FloatArray(2 * W * H)
fun upload() {
    paintMask(bufferCanvas(expected, width = W, channels = 2, height = H))
    mask.mapAndWrite { paintMask(bufferCanvas(this, width = W, channels = 2, height = H)) }
}
upload()

prop<FrameBufferGroup>(fcMask) = mask
prop<Int>(fcWidth) = W
prop<Int>(fcHeight) = H

// The drawing layer: a frame stands for the grid, two world units per cell.
notation {
    frame("mask", frame.x + frame.w + 460, frame.y, 2.0 * W, 2.0 * H)
}
val cells = notation.space("mask", x = 0.0..W.toDouble(), y = 0.0..H.toDouble())

// A freehand stroke becomes one more closed outline; fill closes it.
notation.onStroke { s ->
    val pts = s.points.map { cells.toSpace(it) }
    if (pts.size < 3) return@onStroke
    val line = FLine()
    line.moveTo(pts[0].x, pts[0].y)
    for (p in pts.drop(1)) line.lineTo(p.x, p.y)
    drawn += line
    pending = true
}
// An ellipse clears the hand-drawn outlines.
val handled = mutableSetOf<String>()
notation.onChange { e ->
    for (m in e.added + e.updated) {
        if (m.id in handled || m.kind != MarkType.ELLIPSE) continue
        handled += m.id
        drawn.clear()
        pending = true
    }
}

val shown = FloatArray(2 * W * H)
var nextShow = 0L
frames {
    while (frame()) {
        val f = renderer.frameCounter
        if (pending) {
            pending = false
            upload()
        }
        if (f >= nextShow) {
            nextShow = f + 240
            mask.read { get(0, shown, 0, shown.size) }
            var worst = 0f
            for (i in shown.indices) worst = maxOf(worst, Math.abs(shown[i] - expected[i]))
            val view = bufferCanvas(shown, width = W, channels = 2, height = H)
            view.display = intArrayOf(0, 1, 1)
            view.zoom = 2
            display(
                "mask",
                view.toHTML() +
                    "<pre>white: inside under both rules, red: under non-zero only, renderer frame $f\nmax |mask buffer - CPU coverage| = $worst</pre>",
            )
        }
    }
}
