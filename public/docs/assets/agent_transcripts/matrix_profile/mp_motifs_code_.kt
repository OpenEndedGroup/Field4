import afield.math.matrixprofile.*
import kotlin.math.*

// A slow random walk with the same wiggle hidden in it three times.
val rnd = java.util.Random(4)
var level = 0.0
val series = 3000 { level += rnd.nextGaussian() * 0.1; level }.toMutableList()
for (at in listOf(500, 1400, 2500)) for (k in 0 until 60) series[at + k] += 2 * sin(2 * PI * k / 60)

val mp = matrixProfile(series, m = 60)
showSignals("series" to series, "matrix profile" to mp.profile, channel = "main", normalize = true)

val motif = mp.motifs(1).first()
markdown("info", "motif at ${motif.indices}, pair distance ${"%.2f".format(motif.pairDistance)}")
