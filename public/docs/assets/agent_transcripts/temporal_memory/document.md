# Field Document: temporal_memory

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 5 - memory proposes, Timestral judges

<!-- field-box id="tm_judged" index="0" -->

```edn [field-box id="tm_judged" role="properties"]
{"id" "tm_judged" "index" 0 "name" "5 - memory proposes, Timestral judges" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - memory proposes, Timestral judges" "_tombstone" false "frame" #afield/rect [60.0 3160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_judged_code_.kt" "lines" 49 "characters" 2569 "sha256" "5ca41d0e51e0986fb544bfd65b5a964f25b0f63f9d66ef87fe850782c40f8530"}]}
```

```kotlin [field-property box="tm_judged" name="code" path="tm_judged_code_.kt"]
import afield.math.oracle.*
import afield.math.temporal.*
import kotlin.math.*

val rate = 20.0
val r = java.util.Random(11)
fun rise() = 30 { it / 29.0 }
fun hesitate() = 25 { 1.0 + 0.03 * r.nextGaussian() }
fun burst() = 30 { 1.0 + 0.7 * sin(it * 8 * PI / 29) }
fun pause() = (20 + r.nextInt(20)) { 0.0 }
val demo = Trajectory.join([
    rise() + hesitate() + burst() + pause() + rise() + burst() + pause(),
    rise() + burst() + pause() + rise() + hesitate() + hesitate() + burst() + pause(),
    hesitate() + burst() + pause() + rise() + hesitate() + burst() + pause() + rise(),
].map { Trajectory.of(rate, "v" to it) })
val memory = VariableMarkovOracle.build(demo)
val seconds = 180.0

// Four ways to play the same memory from the same seed: the oracle alone, the oracle told to
// avoid recent passages harder, and Timestral judging the oracle's proposals two ways.
val runs = TimestralSession().use { session ->
    fun judged(mode: JudgeMode, reuseWeight: Double) = ForecastJudge(session, memory, lockstep = true).use { judge ->
        Improviser(memory, seed = 1, jumpProbability = 0.15, judge = judge, judgeMode = mode, reuseWeight = reuseWeight).take(seconds)
    }
    linkedMapOf(
        "oracle alone" to Improviser(memory, seed = 1, jumpProbability = 0.15).take(seconds),
        "oracle, reuseWeight 6" to Improviser(memory, seed = 1, jumpProbability = 0.15, reuseWeight = 6.0).take(seconds),
        "Timestral vetoes" to judged(JudgeMode.Veto, 1.0),
        "Timestral prefers, reuseWeight 6" to judged(JudgeMode.Prefer, 6.0),
    )
}

markdown("table", "| three minutes of the phrases | reuse | coverage | join roughness | jumps |\n|---|---|---|---|---|\n" +
    runs.entries.joinToString("\n") { (name, p) ->
        "| $name | %.2f | %.2f | %.2f | %d |".format(p.reuse(), p.coverage(demo.length), p.joinRoughness(demo), p.segments.size - 1)
    })

// what the plain oracle and the judged one actually played, the first half minute of each
val shown = (30 * rate).toInt()
val compared = ["oracle alone", "Timestral prefers, reuseWeight 6"]
for (name in compared) {
    showSignals(name to runs.getValue(name).output.values[0].take(shown), x = List(shown) { it / rate },
        channel = name, width = 1000, height = 180)
}

// and where in memory each was playing from over the same half minute
showSignals(*compared.map { name -> name to runs.getValue(name).sourceSeconds().take(shown) }.toTypedArray(),
    x = List(shown) { it / rate }, xTitle = "seconds", yTitle = "memory position (s)",
    channel = "main", width = 1000, height = 300)
```

## 2. 3 - take over a channel, live

<!-- field-box id="tm_live" index="1" -->

```edn [field-box id="tm_live" role="properties"]
{"id" "tm_live" "index" 1 "name" "3 - take over a channel, live" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - take over a channel, live" "_tombstone" false "frame" #afield/rect [60.0 1960.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_live_code_.kt" "lines" 43 "characters" 1937 "sha256" "a0586291a2454eab8968fe9ede95510f2edaf40557e896568a5be1ac13829c61"}]}
```

```kotlin [field-property box="tm_live" name="code" path="tm_live_code_.kt"]
import afield.math.oracle.*
import kotlin.math.*

// Two coupled oscillators as the memory. Launch this box, tick "take over x" and drag the
// slider: x follows your hand, y keeps playing from memory and realigns to what you do.
// Untick to give x back. The panel draws the last ten seconds of (x, y) as a trail.
val rate = 20.0
val n = (45 * rate).toInt()
val demo = Trajectory.of(rate,
    "x" to n { val t = it / rate; sin(2 * PI * 0.5 * t) * (1 + 0.3 * sin(2 * PI * 0.05 * t)) },
    "y" to n { val t = it / rate; 0.7 * sin(2 * PI * 0.75 * t + 0.6) })
val player = Improviser(VariableMarkovOracle.build(demo), seed = 3)

var hold = false
var value = 0.0
checkbox("take over x") { hold = it }
slider("x", min = -1.5, max = 1.5, initialValue = 0.0) { value = it }

val trail = ArrayDeque<DoubleArray>()
frames {
    // frames ticks far faster than the memory's 20 Hz, so step on a wall clock
    val period = (1e9 / rate).toLong()
    var next = System.nanoTime()
    var lastDraw = 0L
    while (frame()) {
        val now = System.nanoTime()
        while (now >= next) {
            if (hold) player.perform("x", value) else player.release("x")
            trail.addLast(player.step())
            if (trail.size > 200) trail.removeFirst()
            next += period
        }
        if (now - lastDraw > 100_000_000 && trail.size > 2) {
            lastDraw = now
            val path = FLine().apply {
                trail.forEachIndexed { i, f -> if (i == 0) moveTo(f[0] * 150, -f[1] * 150) else lineTo(f[0] * 150, -f[1] * 150) }
            }.apply { color = vec(0.48, 0.85, 1.0, 0.9); thickness = 2.0 }
            val head = FLine().apply { moveTo(trail.last()[0] * 150, -trail.last()[1] * 150) }
                .apply { stroked = false; pointed = true; pointSize = 6.0; color = if (hold) vec(1, 0.5, 0.2, 1) else vec(1, 1, 1, 1) }
            lines(path, head, channel = "trail", w = 420.0, h = 320.0)
        }
    }
}
```

## 3. 4 - Timestral, a forecast and its fan

<!-- field-box id="tm_fan" index="2" -->

```edn [field-box id="tm_fan" role="properties"]
{"id" "tm_fan" "index" 2 "name" "4 - Timestral, a forecast and its fan" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - Timestral, a forecast and its fan" "_tombstone" false "frame" #afield/rect [60.0 2560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_fan_code_.kt" "lines" 21 "characters" 1184 "sha256" "4481b3aa5c5977d9d47f9557430fe56e2b1fce42b16061f78d06f125e26c766f"}]}
```

```kotlin [field-property box="tm_fan" name="code" path="tm_fan_code_.kt"]
import afield.math.oracle.*
import afield.math.temporal.*
import kotlin.math.*

// Needs the forecaster once: print(TimestralSession.install("mistralai/Timestral-28M"))
print(TimestralSession.status())

val rate = 20.0
val n = (20 * rate).toInt()
val history = Trajectory.of(rate, "x" to n { val t = it / rate; sin(2 * PI * 0.5 * t) * (1 + 0.3 * sin(2 * PI * 0.05 * t)) })
val forecast = TimestralSession().use { it.propose(ContinuationRequest(0, history, horizon = 200)).get() }

fun plot(line: FLine) = line.transform { vec(it.x * 30, -it.y * 120, 0) }
val ahead = DoubleArray(forecast.horizon) { (n + it) / rate }
val past = plot(FLine().apply { for (t in 0 until n) if (t == 0) moveTo(0.0, history.values[0][0]) else lineTo(t / rate, history.values[0][t]) })
    .apply { color = vec(1, 1, 1, 0.9); thickness = 1.5 }
val fan = [0.01 to 0.99, 0.1 to 0.9, 0.25 to 0.75].map { (lo, hi) ->
    plot(forecast.bandFLine(0, lo, hi, ahead)).apply { filled = true; stroked = false; color = vec(1, 0.5, 0.2, 0.18) }
}
val median = plot(forecast.medianFLine(0, ahead)).apply { color = vec(1, 0.5, 0.2, 1); thickness = 3.0 }
lines(fan + past + median, w = 900.0, h = 320.0, fitToSize = true)
```

## 4. 1 - three phrases

<!-- field-box id="tm_phrases" index="3" -->

```edn [field-box id="tm_phrases" role="properties"]
{"id" "tm_phrases" "index" 3 "name" "1 - three phrases" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - three phrases" "_tombstone" false "frame" #afield/rect [60.0 760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_phrases_code_.kt" "lines" 22 "characters" 1057 "sha256" "4c5353446d81bdbda4bdb4e41de1c16fd78f3a7fbec9d0f4ddf4bbf27238aaf8"}]}
```

```kotlin [field-property box="tm_phrases" name="code" path="tm_phrases_code_.kt"]
import afield.math.oracle.*
import kotlin.math.*

// Three short recordings made of the same four gestures in different orders. Replace these
// with your own: any Trajectory.of(rate, "name" to values, ...) per take, joined as episodes.
val rate = 20.0
val r = java.util.Random(11)
fun rise() = 30 { it / 29.0 }
fun hesitate() = 25 { 1.0 + 0.03 * r.nextGaussian() }
fun burst() = 30 { 1.0 + 0.7 * sin(it * 8 * PI / 29) }
fun pause() = (20 + r.nextInt(20)) { 0.0 }
val demo = Trajectory.join([
    rise() + hesitate() + burst() + pause() + rise() + burst() + pause(),
    rise() + burst() + pause() + rise() + hesitate() + hesitate() + burst() + pause(),
    hesitate() + burst() + pause() + rise() + hesitate() + burst() + pause() + rise(),
].map { Trajectory.of(rate, "v" to it) })

val memory = VariableMarkovOracle.build(demo)
print("$demo\n$memory")
showSignals(*demo.series(), x = List(demo.length) { it / rate }, xTitle = "seconds",
    marks = demo.episodeStarts.drop(1).map { "take" to it / rate },
    channel = "main", width = 1000, height = 260)
```

## 5. 2 - the oracle recombines them

<!-- field-box id="tm_oracle" index="4" -->

```edn [field-box id="tm_oracle" role="properties"]
{"id" "tm_oracle" "index" 4 "name" "2 - the oracle recombines them" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - the oracle recombines them" "_tombstone" false "frame" #afield/rect [60.0 1260.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_oracle_code_.kt" "lines" 29 "characters" 1759 "sha256" "f53d61e9dba93fea3440529a6d737a0aa9b97d4f8e5c5b8732407cc88f8717b6"}]}
```

```kotlin [field-property box="tm_oracle" name="code" path="tm_oracle_code_.kt"]
import afield.math.oracle.*
import kotlin.math.*

val rate = 20.0
val r = java.util.Random(11)
fun rise() = 30 { it / 29.0 }
fun hesitate() = 25 { 1.0 + 0.03 * r.nextGaussian() }
fun burst() = 30 { 1.0 + 0.7 * sin(it * 8 * PI / 29) }
fun pause() = (20 + r.nextInt(20)) { 0.0 }
val demo = Trajectory.join([
    rise() + hesitate() + burst() + pause() + rise() + burst() + pause(),
    rise() + burst() + pause() + rise() + hesitate() + hesitate() + burst() + pause(),
    hesitate() + burst() + pause() + rise() + hesitate() + burst() + pause() + rise(),
].map { Trajectory.of(rate, "v" to it) })
val memory = VariableMarkovOracle.build(demo)

// One minute from about 35 seconds of memory. Change the seed for another performance,
// jumpProbability for more or fewer recombinations, minPhrase for longer stretches.
val played = Improviser(memory, seed = 4, jumpProbability = 0.3, minPhrase = 1.0).take(60.0)

val seconds = List(played.output.length) { it / rate }
val byTake = demo.episodeStarts.indices.map { e ->
    "from take ${e + 1}" to played.source.map { if (it >= 0 && demo.episodeOf(it) == e) it / rate else Double.NaN }
}
print("${played.segments.size} segments, reuse ${"%.2f".format(played.reuse())}, coverage ${"%.2f".format(played.coverage(demo.length))}")
print(played.segments.take(8).joinToString("\n") { "  at ${it.outputStart / rate}s: ${it.reason} to take ${it.episode + 1} @ ${"%.1f".format(it.source / rate)}s, context ${it.context}" })
showSignals("performance" to played.output.values[0].toList(), x = seconds, channel = "main", width = 1000, height = 220)
showSignals(*byTake.toTypedArray(), x = seconds, xTitle = "seconds", yTitle = "memory position (s)",
    channel = "memory", width = 1000, height = 300, floatingLegend = true)
```

## 6. 0 - lab notebook

<!-- field-box id="tm_notebook" index="5" -->

```edn [field-box id="tm_notebook" role="properties"]
{"id" "tm_notebook" "index" 5 "name" "0 - lab notebook" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "0 - lab notebook" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_notebook_code_.kt" "lines" 68 "characters" 4181 "sha256" "8fb867a52519c88c8a33fce2b021e33fe901599995ad3b0333d6975e39840231"}]}
```

```kotlin [field-property box="tm_notebook" name="code" path="tm_notebook_code_.kt"]
markdown("""
# Continuing a gesture — lab notebook

October 2026. What this document is for: remember a few seconds of performed behaviour, continue it,
and stay responsive when someone takes over a channel. No training. Two backends, one player.

**Boxes.** 1 records three phrase takes and builds the oracle. 2 recombines them and shows which
take each moment came from. 3 is live: tick "take over x" and drag. 4 needs Timestral installed
and draws a forecast as a quantile fan. 5 compares four ways of playing the same memory.

## What we measured (5-minute performances, 20 Hz, seed 1)

- **Oracle (VMO).** Holds the demo's spread, frequency and speed on every fixture; uses 92–98% of
  the memory. Every sample comes from the recording, and `segments` says why it jumped.
- **Timestral alone, fed back on itself.** 28M fades to a tenth of the signal within minutes.
  209M keeps a smooth figure-eight going for all five minutes, but loses an amplitude-modulated
  channel and settles into silence on the phrases. Replanning: 28M about 30 ms, 209M 150–250 ms
  (p95 up to 660 ms).
- **Known-future covariate.** y = x one second late; giving Timestral x's future halves the error on
  y (0.20 vs 0.44 mean absolute error). The most useful steering control we found.

## The hybrid, three tries

| phrases, 5 min | reuse | coverage | join roughness | jumps |
|---|---|---|---|---|
| oracle alone | 0.25 | 0.98 | 0.89 | 250 |
| judge prefers (28M) | 0.45 | 0.87 | 0.69 | 269 |
| judge vetoes at 1.2× | 0.54 | 0.74 | 0.89 | 258 |
| oracle, reuseWeight 6 | 0.20 | 0.92 | 0.60 | 360 |
| judge prefers + reuseWeight 6 | 0.22 | 0.97 | 0.63 | 267 |

1. *Prefer.* The judge changed 55–70% of decisions. Joins got smoother, but repetition nearly
   doubled: a pinball-loss judge rewards the predictable continuation, which on phrases is what
   just played.
2. *Veto.* Expected it to remove bad joins without the pull. It vetoed most jumps on the phrases and
   repetition got worse still. On the figure-eight it vetoed almost nothing: Timestral agrees with
   every candidate on smooth periodic material.
3. *Prefer + a stronger reuse penalty.* Smooth joins and low reuse together. But the plain oracle
   with the same penalty gets nearly there on its own, with more, shorter jumps. Honest summary: on
   these fixtures the judge buys longer phrases at the same smoothness, not a new capability.

The 209M judge gave the same picture as 28M at five to ten times the cost.

**Then box 5, three minutes instead of five, same seed:** prefer + reuseWeight 6 came out at
reuse 0.50, coverage 0.64, join roughness 0.40, where the 5-minute run said 0.22 / 0.97 / 0.63.
So the "reuse penalty cancels the pull" result does not hold up across lengths: judged runs
vary much more than oracle-only ones (0.26 / 0.93 / 0.87 here vs 0.25 / 0.98 / 0.89).
What does hold every time: the judge makes joins smoother and costs variety on phrase
material. Next experiment should be several seeds × lengths per setting, not one run.

**Then Marc asked to see the played signal, not just memory position.** Both the plain and the
judged oracle play rise → hesitation → burst → pause about every 6 s, near-identically. The
memory trace looked varied only because the takes are copies of the same four gestures.
`reuse` counts source positions, so a loop made of copies passes it. Two consequences: we need
an output-side repetition measure (matrix profile / autocorrelation of the performance), and
this fixture is too poor to say anything about recombination. Next: takes that really differ.

## Open

- A measure of "better dynamics" that is neither the judge's own score nor a statistic the oracle
  already matches. Blind A/B listening or viewing is probably the honest one.
- Timestral steering through known covariates inside the hybrid: a performer's channel as a known
  future, not just as history.
- Takeover is realigned by matching history; a hard endpoint ("arrive here in 4 s") is not supported.
- Tempo, offset and amplitude transforms of recalled passages; the oracle plays them as recorded.

Full report: dev_docs/TEMPORAL_MEMORY_EXPERIMENTS.md. Page: Continuing a Gesture.
""".trimIndent())
```

