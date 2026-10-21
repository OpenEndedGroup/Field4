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

// Vectorize, simplify away the pencil's jitter (2 pixels), and keep curves at least 12 pixels long.
val curves = Polyvector.vectorize(drawing).toFLines().map { it.bySimplifying(2f) }.filter { it.length() > 12f }

// From here on the drawing is geometry: FLines, which anything that works on FLines works on.
// (byWaving's frequency is per node interval; this asks for one wave every 16 pixels.)
val light = Vec4(0.92f, 0.9f, 0.85f, 1f)
fun styled(lines: List<FLine>, thickness: Double) = lines.onEach { it.properties["color"] = light; it.properties["thickness"] = thickness }
val engraved = styled(curves.flatMap { c -> [c] + [3f, 6f, 9f].flatMap { d -> c.byCreatingParallelCurves(d, 120).toList() } }, 1.0)
val combed = styled(curves.flatMap { c -> [c] + c.byCreatingNormalLines(samples = (c.length() / 4).toInt().coerceAtLeast(2), length = 14f) }, 1.0)
val waved = styled(curves.map { it.byWaving(amplitude = 3f, frequency = it.length() / 16f / (it.nodes.size - 1), samples = (it.length() * 2).toInt().coerceAtLeast(20)) }, 1.6)

val black = Img(w, h, 3, FloatArray(w * h * 3) { 0.04f })
val pencil = Img(w, h, 3, FloatArray(w * h * 3) { 0.04f + 0.7f * ink[it / 3] })
showImgGrid(
    "the sketch" to pencil.drawFLines(emptyList(), scale = 2.0),
    "parallel curves" to black.drawFLines(engraved, scale = 2.0),
    "normals" to black.drawFLines(combed, scale = 2.0),
    "waves" to black.drawFLines(waved, scale = 2.0),
    columns = 2, cellSide = 440,
)
