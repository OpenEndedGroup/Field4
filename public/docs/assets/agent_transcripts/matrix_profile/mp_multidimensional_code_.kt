import afield.math.matrixprofile.*
import kotlin.math.*

// Four channels of noise. A 40-sample gesture appears in channels 1 and 3, at two times.
val rnd = java.util.Random(13)
val channels = 4 { 1500 { rnd.nextGaussian() }.toMutableList() }
for (at in listOf(300, 1000)) for (k in 0 until 40) {
    channels[1][at + k] += 4 * sin(2 * PI * k / 40)
    channels[3][at + k] -= 4 * sin(2 * PI * k / 40)
}

val mmp = multiMatrixProfile(channels, m = 40)
showSignals(*Array(4) { k -> "${k + 1} channel(s)" to mmp.profile(k + 1) }, channel = "main")

val motif = mmp.motifs(k = 2, count = 1).first()
markdown("info", "two-channel motif at ${motif.indices}, channels ${motif.channels.sorted()}")
