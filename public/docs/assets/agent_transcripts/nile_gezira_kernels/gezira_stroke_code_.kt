import afield.graphics.nile.*

// An open wave of two cubics, stroked 2 pixels wide, on a 48 x 24 grid.
val wave = listOf(
    Bezier(doubleArrayOf(3.0, 12.0, 10.0, 0.0, 17.0, 24.0, 24.0, 12.0)),
    Bezier(doubleArrayOf(24.0, 12.0, 31.0, 0.0, 38.0, 24.0, 45.0, 12.0)),
)
val t = (GeziraStroke.strokeBezierPath(2.0) then Gezira.rasterizer).trace(wave)
println(t)

val s = 16.0
val outline = t.get<Bezier>("StrokeBezierPath")
val spans = t.get<Span>("WindingSpans")
lines(
    listOf(Gezira.grid(48, 24, s)) +
        Gezira.pixels(Gezira.coverage(spans, 48, 24), 48, s) +
        Gezira.pieces(outline, s) +
        Gezira.cuts(outline, s),
    channel = "stroked wave",
)
