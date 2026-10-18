import afield.geometry.vectorize.polyvector.*

// A sketch, drawn the way a hand would: each stroke laid down three times with a little wobble, in a
// soft pencil line about three pixels wide.
val random = java.util.Random(3)
val strokes = [
    [Vec2(40f, 200f), Vec2(110f, 60f), Vec2(180f, 200f)],         // an A
    [Vec2(70f, 140f), Vec2(150f, 140f)],                         // its crossbar
    [Vec2(220f, 60f), Vec2(220f, 200f)],                         // a post
    [Vec2(220f, 130f), Vec2(300f, 130f)],                        // meeting a beam: a T
    [Vec2(250f, 60f), Vec2(340f, 200f)],                         // and a stroke crossing it: an X
    (0..30).map { Vec2(40f + 300f * it / 30, (245 + 12 * sin(it / 30.0 * 2 * PI)).toFloat()) },  // a wave
]
val w = 380; val h = 280
val ink = FloatArray(w * h)
for (stroke in strokes) repeat(3) {
    val jitter = { random.nextGaussian().toFloat() * 1.5f }
    val pts = stroke.map { Vec2(it.x + jitter(), it.y + jitter()) }
    for (k in 0 until pts.size - 1) {
        val a = pts[k]; val b = pts[k + 1]
        val n = (a.distance(b) * 2).toInt()
        for (s in 0..n) {
            val t = s.toFloat() / n
            val cx = a.x + (b.x - a.x) * t; val cy = a.y + (b.y - a.y) * t
            for (y in (cy - 4).toInt()..(cy + 4).toInt()) for (x in (cx - 4).toInt()..(cx + 4).toInt()) {
                if (x !in 0 until w || y !in 0 until h) continue
                val d = hypot(x + 0.5 - cx, y + 0.5 - cy) / 1.6
                ink[y * w + x] = maxOf(ink[y * w + x], (0.8 * exp(-d * d)).toFloat())
            }
        }
    }
}
val drawing = Img(w, h, 3, FloatArray(w * h * 3) { 1f - ink[it / 3] })

val field = Polyvector.fitField(Polyvector.inkOf(drawing))

// The field is built once; dragging the slider only re-traces it, so it answers quickly.
fun show(prune: Double) {
    val result = field.trace(TraceParameters(pruneRatio = prune))
    showImgGrid("${result.curves.size} curves, coverage ${"%.2f".format(result.coverage())}" to
        Img(w, h, 3, FloatArray(w * h * 3) { 0.04f + 0.7f * ink[it / 3] })
  .drawFLines(listOf(result.streamlinesFLine()) + result.toFLines(color = Vec4(1f, 0.4f, 0.2f, 1f), thickness = 2.0), scale = 2.0),
        channel = "curves", columns = 1, cellSide = 640)
}
slider("prune short branches", min = 0.0, max = 1.0, step = 0.05, initialValue = 0.75) { show(it) }
show(0.75)
