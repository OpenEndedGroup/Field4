import afield.audio.modal.*

// Loading radius across (0.15–0.7 of the rim), density ratio up (1–30, log spaced); each
// cell is one loaded drum's rms distance from 1:2:3:4:5, bright where it is close.
val map = harmonicityMap(linSpace(0.15, 0.7, 12), logSpace(1.0, 30.0, 12), h = 0.07)
val tabla = [Raman.compositeRadius to Raman.compositeMassRatio]
lines(map.cells(w = 12.0, h = 12.0, marks = tabla), w = 400.0, h = 400.0, fitToSize = true)
val (i, j) = map.best
println("most harmonic cell: radius ${map.radii[j].f2}, density × ${map.massRatios[i].f2}, ${map.cents[i][j].f1} cents")
println("bare skin: ${map.cents[0][0].f1} cents")
