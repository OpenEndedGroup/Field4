// Outlines only, one closed FLine per rhombus: what a pen plotter wants. Each tile's two parallel
// pairs of edges are perpendicular to the two grid lines that made it.
val tiling = deBruijnTiling(symmetry = 5, radius = 5.0, scale = 16.0)
val outlines = tiling.lines(fill = false, outlineThickness = 1.2, palette = [vec(0.92, 0.92, 0.88, 1.0)])
lines(outlines, w = 480.0, h = 480.0, fitToSize = true)
"${outlines.size} closed outlines, ${tiling.adjacency().sumOf { it.size } / 2} shared edges"
