import afield.graphics.nile.*

// A curved shape as an FLine, in pixel units on a 16 x 12 grid.
val shape =
    FLine()
        .moveTo(2.2, 10.6)
        .cubicTo(1.0, 3.0, 0.0, 7.0, -0.5, 0.0, 9.5, 1.4, 0.0)
        .cubicTo(12.0, 2.0, 0.0, 15.5, 6.0, 0.0, 13.8, 10.9, 0.0)
        .lineTo(2.2, 10.6)

val t = Gezira.edgeSampleRasterizer.trace(Gezira.beziers(shape))
println(t)

val s = 32.0
val pieces = t.get<Bezier>("DecomposeBeziers")
val samples = t.get<EdgeSample>("EdgeSamples")
val spans = t.get<Span>("CombineEdgeSamples")
lines(listOf(Gezira.grid(16, 12, s)) + Gezira.pieces(pieces, s) + Gezira.cuts(pieces, s), channel = "1 pieces")
lines(
    listOf(Gezira.grid(16, 12, s)) + Gezira.outline(shape, s) + Gezira.samples(samples, s),
    channel = "2 edge samples",
)
lines(
    listOf(Gezira.grid(16, 12, s)) + Gezira.pixels(Gezira.coverage(spans, 16, 12), 16, s) + Gezira.spans(spans, s),
    channel = "3 spans",
)
