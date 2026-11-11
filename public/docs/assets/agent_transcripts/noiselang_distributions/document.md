# Field Document: noiselang_distributions

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 11 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 10 · following a performer's tempo as the notes arrive

<!-- field-box id="noise_tempo_follow" index="0" -->

```edn [field-box id="noise_tempo_follow" role="properties"]
{"id" "noise_tempo_follow" "index" 0 "name" "10 · following a performer's tempo as the notes arrive" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "10 · following a performer's tempo as the notes arrive" "_tombstone" false "frame" #afield/rect [500.0 600.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_tempo_follow_code_.kt" "lines" 118 "characters" 4919 "sha256" "6237d3c5632d6bd1f539a75abe45b8ec360f3ef33f7705b51df6e57bebcb5146"}]}
```

```kotlin [field-property box="noise_tempo_follow" name="code" path="noise_tempo_follow_code_.kt"]
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
```

## 2. 4 · a distribution of FLines

<!-- field-box id="noise_walks" index="1" -->

```edn [field-box id="noise_walks" role="properties"]
{"id" "noise_walks" "index" 1 "name" "4 · a distribution of FLines" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · a distribution of FLines" "_tombstone" false "frame" #afield/rect [500.0 180.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_walks_code_.kt" "lines" 33 "characters" 1108 "sha256" "48276aaf40bef0d14a89620d5af66403d1d695d0e371583c4364dd88f8b5d769"}]}
```

```kotlin [field-property box="noise_walks" name="code" path="noise_walks_code_.kt"]
import afield.math.distribution.*

// One graph: heading drifts by an independent normal each step; x and y accumulate.
val steps = 40
var heading: Dist = Dist.dirac(0)
var x: Dist = Dist.dirac(0)
var y: Dist = Dist.dirac(0)
val xs = ArrayList<Dist>()
val ys = ArrayList<Dist>()
for (k in 0 until steps) {
    heading = heading + normal(0, 0.25)
    x = x + heading.cos() * 4
    y = y + heading.sin() * 4
    xs += x
    ys += y
}
val reach = (x * x + y * y).sqrt()

// 120 joint draws of every node: each sample is one whole path.
val walks = 120
val drawn = jointSamples(*(xs + ys).toTypedArray(), n = walks, seed = 7)
val paths =
    (0 until walks).map { w ->
        fline {
            moveTo(200, 150)
            for (k in 0 until steps) lineTo(200 + drawn[k][w], 150 - drawn[steps + k][w])
            color = vec(0.0, 0.67, 1.0, 0.35)
        }
    }
lines(paths, channel = "walks", w = 400.0, h = 300.0)

println("E(reach) = ${E(reach, n = 50_000)}   (a straight line would be ${steps * 4})")
__m("reach").distribution(reach, batch = 20_000, title = "distance from the start after $steps steps")
```

## 3. 1 · spike, die, two dice — launch me

<!-- field-box id="noise_spike_to_triangle" index="2" -->

```edn [field-box id="noise_spike_to_triangle" role="properties"]
{"id" "noise_spike_to_triangle" "index" 2 "name" "1 · spike, die, two dice — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · spike, die, two dice — launch me" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_spike_to_triangle_code_.kt" "lines" 13 "characters" 365 "sha256" "f175368cb641f43424446b4d4cba3671b7981b95b32c7dfd0308f4255399385a"}]}
```

```kotlin [field-property box="noise_spike_to_triangle" name="code" path="noise_spike_to_triangle_code_.kt"]
import afield.math.distribution.*

val a = Dist.dirac(5)
val d1 = die(6)
val d2 = die(6)
val s = a + d1 + d2

loop {
    __m("spike").distribution(a, batch = 500, title = "A = 5, a Dirac spike")
    __m("die").distribution(d1, batch = 500, title = "D1, a fair die")
    __m("sum").distribution(s, batch = 500, title = "S = A + D1 + D2", exact = 12.0)
    frame()
}
```

## 4. 8 · a sine whose pitch is a distribution — launch me, sound on

<!-- field-box id="noise_live_pitch" index="3" -->

```edn [field-box id="noise_live_pitch" role="properties"]
{"id" "noise_live_pitch" "index" 3 "name" "8 · a sine whose pitch is a distribution — launch me, sound on" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "8 · a sine whose pitch is a distribution — launch me, sound on" "_tombstone" false "frame" #afield/rect [500.0 460.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_live_pitch_code_.kt" "lines" 22 "characters" 869 "sha256" "b680837964a734bb16482e31bc4156a212099b99951da040ed288e290670ef68"}]}
```

```kotlin [field-property box="noise_live_pitch" name="code" path="noise_live_pitch_code_.kt"]
import afield.math.distribution.*

/*
LAUNCH me (sound on). The weighted pentatonic from box 6, as frequencies, picks the pitch of a sine
at every rising edge of an 8 Hz pulse; the same pulse gates the envelope. The view under `heard`
is the pitches you are hearing, settling onto the scale's exact probabilities. Change the weights
or the clock and re-launch.
*/
val scale = categorical([0, 3, 5, 7, 10, 12], [4, 1, 2, 3, 1, 2])
val hz = 220 * (scale * (kotlin.math.ln(2.0) / 12)).exp()

val clock = PulseOsc(freq = Param(8f), width = Param(0.35f))
val pitch = hz.param(trigger = clock)
val env =
    ADSR(gate = clock, attack = Param(0.004f), decay = Param(0.06f), sustain = Param(0.35f), release = Param(0.08f))
val voice = SineOsc(freq = pitch) * env * 0.5f

audio(voice) +
    loop {
        __m("heard").distribution(pitch, title = "heard")
        frame()
    }
```

## 5. 11 · tracking a point seen through noise

<!-- field-box id="noise_track_point" index="4" -->

```edn [field-box id="noise_track_point" role="properties"]
{"id" "noise_track_point" "index" 4 "name" "11 · tracking a point seen through noise" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "11 · tracking a point seen through noise" "_tombstone" false "frame" #afield/rect [40.0 740.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_track_point_code_.kt" "lines" 66 "characters" 2273 "sha256" "d071413ccf1ce0fc607b4a1a2d171d096f332202c87d9d156337137694df9cc5"}]}
```

```kotlin [field-property box="noise_track_point" name="code" path="noise_track_point_code_.kt"]
import afield.math.distribution.*

// A point wanders, moving about 3 units a step, and is seen only through noise of about 12 units.
val steps = 60
val moves = normal(0, 3).samples(2 * steps, seed = 8)
val noise = normal(0, 12).samples(2 * steps, seed = 9)
val truth = ArrayList<Pair<Double, Double>>()
var tx = 200.0
var ty = 150.0
for (k in 0 until steps) {
    tx += moves[2 * k] + 1.5 // a slow drift to the right
    ty += moves[2 * k + 1]
    truth += tx to ty
}
val seen = truth.mapIndexed { k, (a, b) -> (a + noise[2 * k]) to (b + noise[2 * k + 1]) }

// Where is it? Two unknowns, a position. Between sightings it moves; each sighting is it, ±12.
val x = normal(200, 20)
val y = normal(150, 20)
val post = Posterior(x, y, particles = 2000)
val path = ArrayList<Pair<Double, Double>>()
for ((sx, sy) in seen) {
    post.evolve(x to x + 1.5 + normal(0, 3), y to y + normal(0, 3))
    post.observe(normal(x, 12), sx)
    post.observe(normal(y, 12), sy)
    path += post.E(x).value to post.E(y).value
}

// The truth, what was seen, the posterior's path, and 300 draws of where it is now
val drawn = post.samples(x, y, n = 300)
val pictures = [
    fline {
        moveTo(truth[0].first, truth[0].second)
        for ((a, b) in truth) lineTo(a, b)
        color = vec(1.0, 1.0, 1.0, 0.6)
    },
    fline {
        moveTo(path[0].first, path[0].second)
        for ((a, b) in path) lineTo(a, b)
        color = vec(0.0, 0.67, 1.0, 1.0)
    },
] +
        seen.map { (a, b) ->
            fline { circle(a, b, 0.8) }
                .apply {
                    filled = true
                    color = vec(1.0, 0.67, 0.0, 0.8)
                }
        } +
        (0 until 300).map { i ->
            fline { circle(drawn[0][i], drawn[1][i], 0.45) }
                .apply {
                    filled = true
                    color = vec(0.0, 0.67, 1.0, 0.35)
                }
        }
lines(pictures, channel = "track", w = 480.0, h = 300.0)

fun rms(ps: List<Pair<Double, Double>>) = kotlin.math.sqrt(
    ps.indices.sumOf { k ->
        val dx = ps[k].first - truth[k].first
        val dy = ps[k].second - truth[k].second
        dx * dx + dy * dy
    } / ps.size,
)
println("distance from the truth: sightings ${rms(seen).f1}, posterior ${rms(path).f1}")
```

## 6. 5 · a posterior, given readings and coin flips — launch me

<!-- field-box id="noise_posterior" index="5" -->

```edn [field-box id="noise_posterior" role="properties"]
{"id" "noise_posterior" "index" 5 "name" "5 · a posterior, given readings and coin flips — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 · a posterior, given readings and coin flips — launch me" "_tombstone" false "frame" #afield/rect [40.0 320.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_posterior_code_.kt" "lines" 13 "characters" 450 "sha256" "ac23af72b9b85076ac2700fb87c412e6d46c888b54132ec008faf0dca6213fdb"}]}
```

```kotlin [field-property box="noise_posterior" name="code" path="noise_posterior_code_.kt"]
import afield.math.distribution.*

val mu = normal(0, 10)
val reading = mu given observed(normal(mu, 1), 2.9, 3.3, 3.1)

val p = uniform(0, 1)
val coin = p given observed(bernoulli(p), 1, 1, 0, 1, 1, 1, 0, 1, 1, 0)

loop {
    __m("reading").distribution(reading, batch = 2000, title = "mu given three readings", exact = 9.3 / 3.01)
    __m("coin").distribution(coin, batch = 2000, title = "bias given 7 heads in 10", exact = 8.0 / 12)
    frame()
}
```

## 7. 6 · a weighted scale as a pattern, heard

<!-- field-box id="noise_weighted_scale" index="6" -->

```edn [field-box id="noise_weighted_scale" role="properties"]
{"id" "noise_weighted_scale" "index" 6 "name" "6 · a weighted scale as a pattern, heard" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 · a weighted scale as a pattern, heard" "_tombstone" false "frame" #afield/rect [500.0 320.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_weighted_scale_code_.kt" "lines" 18 "characters" 448 "sha256" "c62ae98232bc79590f4ef96b0646a6be099f7472105853f6e2845ea4ffd2fe61"}]}
```

```kotlin [field-property box="noise_weighted_scale" name="code" path="noise_weighted_scale_code_.kt"]
import afield.math.distribution.*

val scale = categorical([0, 3, 5, 7, 10, 12], [4, 1, 2, 3, 1, 2])
val tune = (60 + scale).pat().segment(8).texts()

showPatRollOf(
    "60 + scale" to tune,
    title = "eight draws a cycle from a weighted pentatonic",
    cycles = 4,
    pitched = true,
)
showPatAudio(
    tune,
    cycles = 4,
    cps = 0.5,
    voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.05, sustain = 0.7),
    channel = "audio",
)
```

## 8. 2 · P, E, Var and quantiles, with standard errors

<!-- field-box id="noise_queries" index="7" -->

```edn [field-box id="noise_queries" role="properties"]
{"id" "noise_queries" "index" 7 "name" "2 · P, E, Var and quantiles, with standard errors" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · P, E, Var and quantiles, with standard errors" "_tombstone" false "frame" #afield/rect [40.0 180.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_queries_code_.kt" "lines" 20 "characters" 754 "sha256" "7823faaeef227f49f83123b9299d6a17767c8aea428e11370c940ac1f08ea10d"}]}
```

```kotlin [field-property box="noise_queries" name="code" path="noise_queries_code_.kt"]
import afield.math.distribution.*

fun show(label: String, e: Estimate, exact: Double) =
    println("%-24s %-28s exact %.5f   z = %+.2f".format(label, e, exact, e.zFrom(exact)))

val x = die(6)
val y = die(6)
show("E(5 + X + Y)", E(5 + x + y), 12.0)
show("P(X + Y < 10)", P(x + y lt 10), 30.0 / 36)
show("E(X - X)", E(x - x), 0.0)
show("Var(X - X')", Var(x - x.iid()), 35.0 / 6)

val birthday = draws(23, uniformInt(1, 365))
val noShared = (0 until 23).fold(1.0) { p, k -> p * (365.0 - k) / 365.0 }
show("P(shared birthday)", P(hasDuplicates(birthday)), 1 - noShared)

val u = uniform(-1, 1)
val v = uniform(-1, 1)
show("4 P(u² + v² < 1)", P(u * u + v * v lt 1) * 4, Math.PI)
show("median of exp(1)", quantile(exponential(1), 0.5), kotlin.math.ln(2.0))
```

## 9. 3 · samples settling under a density — launch me

<!-- field-box id="noise_density" index="8" -->

```edn [field-box id="noise_density" role="properties"]
{"id" "noise_density" "index" 8 "name" "3 · samples settling under a density — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · samples settling under a density — launch me" "_tombstone" false "frame" #afield/rect [500.0 40.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_density_code_.kt" "lines" 10 "characters" 319 "sha256" "597a3efa3d95f8e6905d9d543be1f6943c83363b3b0ba8ece609a3146f95b897"}]}
```

```kotlin [field-property box="noise_density" name="code" path="noise_density_code_.kt"]
import afield.math.distribution.*

val sum = normal(0, 1) + normal(2, 0.5)
val squared = normal(0, 1).squared()

loop {
    __m("sum").distribution(sum, batch = 400, title = "N(0,1) + N(2,0.5)", exact = 2.0)
    __m("sq").distribution(squared, batch = 400, title = "Z², no closed form here", exact = 1.0)
    frame()
}
```

## 10. 7 · a rhythm conditioned to five hits in sixteen

<!-- field-box id="noise_five_in_sixteen" index="9" -->

```edn [field-box id="noise_five_in_sixteen" role="properties"]
{"id" "noise_five_in_sixteen" "index" 9 "name" "7 · a rhythm conditioned to five hits in sixteen" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 · a rhythm conditioned to five hits in sixteen" "_tombstone" false "frame" #afield/rect [40.0 460.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_five_in_sixteen_code_.kt" "lines" 15 "characters" 488 "sha256" "14913a706832fe7550d903d01165afdab862d4bd170680b76b2fb0bba64f84b0"}]}
```

```kotlin [field-property box="noise_five_in_sixteen" name="code" path="noise_five_in_sixteen_code_.kt"]
import afield.math.distribution.*

val steps = List(16) { bernoulli(0.4) }
val rhythm = steps.given(steps.sum() eq 5)
val hats = struct(rhythm.pat().map { it != 0.0 }, pat("hh"))
val beat = stack(hats, pat("bd"))

showPatRollOf("kick" to pat("bd"), "hats" to hats, title = "five hits in sixteen, a new five each cycle", cycles = 4)
showPatAudio(
    beat,
    cycles = 4,
    cps = 0.5,
    voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.03, sustain = 0.3),
    channel = "audio",
)
```

## 11. 9 · a Gaussian process as distributions — where is its peak?

<!-- field-box id="noise_gp_curves" index="10" -->

```edn [field-box id="noise_gp_curves" role="properties"]
{"id" "noise_gp_curves" "index" 10 "name" "9 · a Gaussian process as distributions — where is its peak?" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "9 · a Gaussian process as distributions — where is its peak?" "_tombstone" false "frame" #afield/rect [40.0 600.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "noise_gp_curves_code_.kt" "lines" 34 "characters" 1168 "sha256" "c6f96582a771b57fdbf3eda82a030fbfe9cbdc1c9a23126b94ff52bf94d49adb"}]}
```

```kotlin [field-property box="noise_gp_curves" name="code" path="noise_gp_curves_code_.kt"]
import afield.math.distribution.*

val xs = doubleArrayOf(0.5, 2.0, 3.5, 6.0, 7.5)
val ys = doubleArrayOf(0.2, 1.1, 0.4, 0.9, -0.3)
val gp = gp1D(xs, ys, GpKernels.rbf(lengthScale = 1.0), noiseStd = 0.1)

val grid = DoubleArray(41) { it * 0.2 }
val f = gp.dists(grid)

fun px(x: Double) = 20 + x * 45
fun py(y: Double) = 150 - y * 70
val curves = jointSamples(*f.toTypedArray(), n = 40, seed = 3)
val drawn =
    (0 until 40).map { c ->
        fline {
            moveTo(px(grid[0]), py(curves[0][c]))
            for (i in 1 until grid.size) lineTo(px(grid[i]), py(curves[i][c]))
            color = vec(0.0, 0.67, 1.0, 0.3)
        }
    }
val readings =
    xs.indices.map { i ->
        fline { circle(px(xs[i]), py(ys[i]), 4.0) }
            .apply {
                filled = true
                color = vec(1.0, 0.67, 0.0, 1.0)
            }
    }
lines(drawn + readings, channel = "curves", w = 400.0, h = 260.0)

val peakAt = argmax(f) * 0.2
println("P(tops 1.2 somewhere) = ${P(maximum(f) gt 1.2)}")
println("E(where the peak is)  = ${E(peakAt)}")
display("peak", distributionSvg(Progressive(peakAt).draw(20_000), title = "x of the curve's highest point"))
```

