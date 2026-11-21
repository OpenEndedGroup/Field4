import afield.graphics.nile.*

// A line of serif text: quadratic glyph outlines, the same kind of input as Gezira's tiger.
val text = Gezira.glyphs("Pixels on a screen", 6.0, 46.0, size = 40.0)
val t = Gezira.rasterizer.trace(text)
println("${text.size} segments, ${t.get<Bezier>("DecomposeBeziers").size} pieces, ${t.output.size} spans")
val ours = Gezira.coverage(t.get("CombineEdgeSamples"), 360, 64)
val skia = Gezira.skiaCoverage(text, 360, 64)
val (worst, mean) = Gezira.error(ours, skia)
println("kernels vs Skia: max |diff| %.3f, mean %.4f".format(worst, mean))
lines(Gezira.pixels(ours, 360, 2.0), channel = "kernels")
lines(Gezira.pixels(skia, 360, 2.0), channel = "skia")
