import afield.math.matrixprofile.*
import kotlin.math.*

// 40 beats of 80 samples. Beat 21 has a doubled, late pulse.
val rnd = java.util.Random(15)
fun bump(t: Double, at: Double, w: Double) = exp(-((t - at) / w).pow(2))
val beats = (40 * 80) { i ->
    val t = (i % 80).toDouble()
    val pulse = if (i / 80 == 21) 2 * (bump(t, 35.0, 3.0) + bump(t, 50.0, 3.0)) else 3 * bump(t, 30.0, 3.0)
    pulse - 0.6 * bump(t, 45.0, 6.0) + rnd.nextGaussian() * 0.05
}

val mp = matrixProfile(beats, m = 80)
showSignals("beats" to beats, "matrix profile" to mp.profile, channel = "main", normalize = true)

val discord = mp.discords(1).first()
markdown("info", "discord at ${discord.index} (beat ${discord.index / 80}), distance ${"%.2f".format(discord.distance)}")
