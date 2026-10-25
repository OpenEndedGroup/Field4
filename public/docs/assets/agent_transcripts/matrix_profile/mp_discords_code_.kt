import afield.math.matrixprofile.*

// 40 beats of 80 samples. Beat 21 has a doubled, late pulse.
val rnd = java.util.Random(15)
fun bump(t: Double, at: Double, w: Double) = exp(-((t - at) / w).pow(2))
val beats = (40 * 80) { i ->
    val t = (i % 80).toDouble()
    val pulse = if (i / 80 == 21) 2 * (bump(t, 35.0, 3.0) + bump(t, 50.0, 3.0)) else 3 * bump(t, 30.0, 3.0)
    pulse - 0.6 * bump(t, 45.0, 6.0) + rnd.nextGaussian() * 0.05
}

val mp = matrixProfile(beats, m = 80)
val discord = mp.discords(1).first()

showSignals(
    "beats" to beats,
    "matrix profile" to mp.profile,
    regions = [SignalRegion("discord", discord.index, discord.index + mp.m, "#ff7a9e")],
    title = "discord at ${discord.index} (beat ${discord.index / 80}), distance ${discord.distance.f2}",
    channel = "main",
    normalize = true,
    width = 1000,
    height = 420,
    floatingLegend = true,
)
