import afield.graphics.nile.*

// The tiger as Field FLines, drawn by Field's own line renderer.
val outlines = GeziraSvg.flines(GeziraSvg.tiger())
println("${outlines.size} FLines")
lines(outlines, channel = "flines")
