import afield.graphics.nile.*

// The Ghostscript tiger through Gezira's kernels, then through Skia's SVG renderer.
val n = 300
val text = GeziraSvg.tiger()
val doc = GeziraSvg.parse(text)
val r = GeziraSvg.render(doc, n, n)
val skia = GeziraSvg.skia(text, n, n)
val diff = GeziraSvg.difference(r.canvas.rgba, skia, 10.0)
val (worst, mean) = r.canvas.error(skia)
val readout = listOf(
    doc.toString(),
    r.toString(),
    "Gezira vs Skia per channel: max |diff| %.3f, mean %.4f".format(worst, mean),
).joinToString("\n")
println(readout)
display("readout", "<pre style='margin:0; white-space:pre-wrap'>$readout</pre>")

// Three panels side by side: Gezira, Skia, and the difference times ten (white where they agree).
val gap = 12
val w = 3 * n + 2 * gap
val sheet = FloatArray(4 * w * n)
for ((k, img) in listOf(r.canvas.rgba, skia, diff).withIndex()) {
    for (y in 0 until n) for (x in 0 until n) {
        val s = 4 * (y * n + x)
        val d = 4 * (y * w + k * (n + gap) + x)
        for (c in 0..3) sheet[d + c] = img[s + c]
    }
}
display("gezira | skia | difference x10", GeziraSvg.png(sheet, w, n))
