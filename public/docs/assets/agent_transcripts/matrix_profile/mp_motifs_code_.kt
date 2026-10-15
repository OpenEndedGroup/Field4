import afield.math.matrixprofile.*
import kotlin.math.*

// A slow random walk with the same wiggle hidden in it three times.
val rnd = java.util.Random(4)
var level = 0.0
val series = 3000 { level += rnd.nextGaussian() * 0.1; level }.toMutableList()
for (at in [500, 1400, 2500]) for (k in 0 until 60) series[at + k] += 2 * sin(2 * PI * k / 60)

val mp = matrixProfile(series, m = 60)
val motif = mp.motifs(1).first()

showSignals(
    "series" to series, "matrix profile" to mp.profile,
    regions = motif.indices.map { SignalRegion("motif", it, it + mp.m, "#8fff9a") },
    title = "motif at ${motif.indices}, pair distance ${"%.2f".format(motif.pairDistance)}",
    channel = "main", normalize = true, width = 1000, height = 420, floatingLegend = true,
)
