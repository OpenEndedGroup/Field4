import afield.math.distribution.*

// 1. A performance whose tempo we know: 48 beats speeding up from 84 to 132 bpm and easing back to
//    108, every beat early or late by about 12 ms; a low note on each bar, a higher one between.
val beats = 48
fun bpm(k: Int) = when {
    k < 24 -> 84.0 + 48.0 * k / 24
    k < 36 -> 132.0 - 24.0 * (k - 24) / 12
    else -> 108.0
}
val late = normal(0, 0.012).samples(beats, seed = 4)
val beatTimes = DoubleArray(beats)
var at = 0.5
for (k in 0 until beats) {
    beatTimes[k] = at + late[k]
    at += 60.0 / bpm(k)
}
val length = beatTimes.last() + 1.0
val performance =
    patTimed(beatTimes.mapIndexed { k, t -> TimedNote(t, t + 0.1, if (k % 4 == 0) 45.0 else 52.0) }, beats = length)
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.05, sustain = 0.3)
val heard = patRenderStereo(performance, cycles = 1, cps = 1 / length, voice = voice)

// 2. Where the notes start, found in the sound itself
val onsets =
    patDetectOnsetSamples(heard.mono, PatSoundDocs.SR, minGapSeconds = 0.3).map { it.toDouble() / PatSoundDocs.SR }

// 3. Follow the beat. Two unknowns: the period, and when the last beat fell. At each onset the beat
//    moves on by a period, the period drifts by about 3%, and the onset is the beat, give or take 15 ms.
val period = uniform(0.3, 1.0) // 60 to 200 bpm: we do not know the tempo
val beat = known(onsets[0]) // the first onset was a beat
val post = Posterior(period, beat, particles = 3000)
val predicted = ArrayList<Double>() // where each next beat should fall, before it is heard
val tempo = ArrayList<Triple<Double, Double, Double>>() // time, mean bpm, sd
for (t in onsets.drop(1)) {
    predicted += post.E(beat + period).value
    post.evolve(beat to beat + period + normal(0, 0.004), period to period * normal(0, 0.03).exp())
    post.observe(normal(beat, 0.015), t)
    tempo += Triple(t, post.E(60 / period).value, kotlin.math.sqrt(post.Var(60 / period).value))
}

// 4. An accompaniment on the predicted beats: it can only follow what it has already heard
val accompaniment = patTimed(predicted.map { p -> TimedNote(p, p + 0.05, 76.0, gain = 0.45) }, beats = length)
val reply = patRenderStereo(
    accompaniment,
    cycles = 1,
    cps = 1 / length,
    voice = patVoice(wave = PatWave.SINE, decay = 0.03, sustain = 0.2),
)
val n = minOf(heard.frames, reply.frames)
display(
    "audio",
    afield.audio.engine.WavFloatArrayWriter.encodeStereo16(
        FloatArray(n) { heard.left[it] * 0.8f + reply.left[it] * 0.5f },
        FloatArray(n) { heard.right[it] * 0.8f + reply.right[it] * 0.5f },
        PatSoundDocs.SR,
        normalizePeak = 0.9f,
    ),
)

// 5. The tempo it heard, against the tempo that was played
fun px(t: Double) = 30 + t / length * 600
fun py(b: Double) = 230 - (b - 60) / 110 * 210
val truth = fline {
    moveTo(px(beatTimes[0]), py(bpm(0)))
    for (k in 1 until beats) lineTo(px(beatTimes[k]), py(bpm(k - 1)))
    color = vec(1.0, 1.0, 1.0, 0.5)
}
val followed = fline {
    moveTo(px(tempo[0].first), py(tempo[0].second))
    for ((t, m, _) in tempo) lineTo(px(t), py(m))
    color = vec(0.0, 0.67, 1.0, 1.0)
}
val band = fline {
    moveTo(px(tempo[0].first), py(minOf(170.0, tempo[0].second + 2 * tempo[0].third)))
    for ((t, m, sd) in tempo) lineTo(px(t), py(minOf(170.0, m + 2 * sd)))
    for ((t, m, sd) in tempo.reversed()) lineTo(px(t), py(maxOf(60.0, m - 2 * sd)))
    filled = true
    stroked = false
    color = vec(0.0, 0.67, 1.0, 0.25)
}
val heardAt = onsets.map { t ->
    fline {
        moveTo(px(t), 236.0)
        lineTo(px(t), 242.0)
        color = vec(1.0, 0.67, 0.0, 1.0)
    }
}
val grid =
    listOf(80, 100, 120, 140, 160).map { b ->
        fline {
            moveTo(30.0, py(b.toDouble()))
            lineTo(630.0, py(b.toDouble()))
            color = vec(1.0, 1.0, 1.0, 0.12)
        }
    }
lines(grid + band + truth + followed + heardAt, channel = "tempo", w = 660.0, h = 250.0)

val errors = onsets.drop(1).indices.map { kotlin.math.abs(predicted[it] - onsets[it + 1]) * 1000 }
val now = tempo.last()
println("${onsets.size} onsets heard; tempo now ${now.second.f1} bpm, give or take ${now.third.f1} (played: 108)")
fun median(xs: List<Double>) = xs.sorted()[xs.size / 2].toInt()
println(
    "prediction error, first 8 beats: median ${median(errors.take(8))} ms; after them: median ${median(errors.drop(8))} ms",
)

// 6. How much does the tempo drift? Ask the evidence: the same filter with three drift rates
fun evidenceFor(drift: Double): Double {
    val p = uniform(0.3, 1.0)
    val b = known(onsets[0])
    val f = Posterior(p, b, particles = 3000)
    for (t in onsets.drop(1)) {
        f.evolve(b to b + p + normal(0, 0.004), p to p * normal(0, drift).exp())
        f.observe(normal(b, 0.015), t)
    }
    return f.logEvidence
}
for (drift in [0.01, 0.03, 0.1]) println("log evidence with ${drift * 100}% drift per beat: ${evidenceFor(drift).f1}")
