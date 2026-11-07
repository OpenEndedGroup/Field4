val penrose = deBruijnTiling(symmetry = 5, radius = 7.0, scale = 14.0)
val counts = penrose.typeCounts()
lines(penrose.lines(), w = 560.0, h = 560.0, fitToSize = true)
"thin ${counts[1]}, thick ${counts[2]}, thick/thin %.4f".format(counts.getValue(2).toDouble() / counts.getValue(1))
