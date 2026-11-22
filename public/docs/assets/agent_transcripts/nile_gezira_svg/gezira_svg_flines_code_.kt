import afield.graphics.nile.*

// The same document as FLines, for anything in Field that draws them.
val doc = GeziraSvg.parse(GeziraSvg.tiger())
val outlines = GeziraSvg.flines(doc, SvgTransform.scale(0.5))
println("${outlines.size} FLines from ${doc.shapes.size} shapes")
lines(outlines, channel = "flines")
