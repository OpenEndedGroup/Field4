// Every tile on one grid line's ribbon shares an edge with the next: the line threads a chain of
// rhombi whose shared edges are all parallel. Line 0 of family 0 is drawn in red.
val penrose = deBruijnTiling(symmetry = 5, radius = 7.0, scale = 14.0)
val line = DeBruijnLine(family = 0, index = 0)
val ribbon = penrose.ribbon(line)

val grey = [vec(0.32, 0.34, 0.40, 1.0), vec(0.22, 0.24, 0.30, 1.0)]
lines(penrose.lines(palette = grey, highlight = line), w = 560.0, h = 560.0, fitToSize = true)

"${ribbon.size} tiles on family ${line.family}, line ${line.index}"
