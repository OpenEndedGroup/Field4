import afield.graphics.nile.*

// Gezira's TransformBeziers: one kernel, in front of the rasteriser.
fun rotate(degrees: Double, cx: Double, cy: Double): Stage<Bezier, Bezier> {
    val c = cos(degrees * PI / 180)
    val s = sin(degrees * PI / 180)
    return mapKernel("TransformBeziers") { b ->
        Bezier(
            DoubleArray(b.p.size) { i ->
                val x = b.p[i - i % 2] - cx
                val y = b.p[i - i % 2 + 1] - cy
                if (i % 2 == 0) cx + c * x - s * y else cy + s * x + c * y
            },
        )
    }
}

val square = FLine().moveTo(4.0, 4.0).lineTo(12.0, 4.0).lineTo(12.0, 12.0).lineTo(4.0, 12.0).lineTo(4.0, 4.0)
val t = (rotate(20.0, 8.0, 8.0) then Gezira.edgeSampleRasterizer).trace(Gezira.beziers(square))
println(t)
val px = 24.0
val spans = t.get<Span>("CombineEdgeSamples")
lines(
    listOf(Gezira.grid(16, 16, px)) +
        Gezira.pixels(Gezira.coverage(spans, 16, 16), 16, px) +
        Gezira.pieces(t.get("DecomposeBeziers"), px),
    channel = "rotated",
)
