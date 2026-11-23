import afield.graphics.nile.*

// A star filled with a gradient, then a translucent circle composited over it, then a
// stroked ring multiplied on top: three paints, two blends, one canvas.
val canvas = Canvas(64, 48)
val star = FLine().moveTo(28.0 + 20.0, 24.0)
for (k in 1 until 10) {
    val a = PI * k / 5
    val r = if (k % 2 == 0) 20.0 else 8.5
    star.lineTo(28.0 + r * cos(a), 24.0 + r * sin(a))
}
val gradient = GeziraComposite.linearGradient(8.0, 4.0, 48.0, 44.0, Rgba.of(1.0, 0.75, 0.1), Rgba.of(0.9, 0.1, 0.4))
canvas.draw(Gezira.beziers(star), GeziraComposite.texture(gradient))
canvas.draw(Gezira.circle(40.0, 28.0, 13.0), GeziraComposite.color(Rgba.of(0.1, 0.5, 1.0, 0.55)))
val ring = GeziraStroke.strokeBezierPath(3.0)(Gezira.circle(22.0, 30.0, 11.0))
val t = canvas.draw(ring, GeziraComposite.color(Rgba.of(0.3, 0.9, 0.5, 0.9)), GeziraComposite.MULTIPLY)
println(t)
lines(canvas.pixels(8.0), channel = "canvas")
