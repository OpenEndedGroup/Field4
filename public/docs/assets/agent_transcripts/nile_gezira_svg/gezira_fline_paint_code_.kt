import afield.graphics.nile.*

// Ordinary FLines, painted by their own color, filled, stroked, thickness and fillRule.
fun circle(cx: Double, cy: Double, r: Double): FLine {
    val k = 0.5522847498 * r
    return FLine()
        .moveTo(cx + r, cy)
        .cubicTo(cx + r, cy + k, 0.0, cx + k, cy + r, 0.0, cx, cy + r, 0.0)
        .cubicTo(cx - k, cy + r, 0.0, cx - r, cy + k, 0.0, cx - r, cy, 0.0)
        .cubicTo(cx - r, cy - k, 0.0, cx - k, cy - r, 0.0, cx, cy - r, 0.0)
        .cubicTo(cx + k, cy - r, 0.0, cx + r, cy - k, 0.0, cx + r, cy, 0.0)
        .close()
}
fun FLine.square(x: Double, y: Double, s: Double) =
    moveTo(x, y).lineTo(x + s, y).lineTo(x + s, y + s).lineTo(x, y + s).close()

val disc = circle(70.0, 60.0, 40.0)
disc.filled = true
disc.stroked = false
disc.color = vec(0.95, 0.55, 0.15, 1.0)
val ring = circle(70.0, 60.0, 40.0)
ring.thickness = 6.0
ring.color = vec(0.2, 0.15, 0.1, 1.0)
val frame = FLine().square(120.0, 20.0, 80.0).square(140.0, 40.0, 40.0)
frame.filled = true
frame.stroked = false
frame.fillRule = FillRule.EVEN_ODD
frame.color = vec(0.25, 0.5, 0.85, 0.8)
val wave = FLine().moveTo(20.0, 110.0).cubicTo(60.0, 70.0, 0.0, 100.0, 150.0, 0.0, 200.0, 110.0, 0.0)
wave.thickness = 3.0
wave.color = vec(0.8, 0.1, 0.3, 1.0)
val shapes = [disc, ring, frame, wave]
val r = GeziraFLine.render(shapes, 220, 130)
println(r)
display("gezira", GeziraSvg.png(r.canvas.rgba, 220, 130))
lines(shapes, channel = "field")
