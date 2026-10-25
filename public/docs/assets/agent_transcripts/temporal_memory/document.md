# Field Document: temporal_memory

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 - the dancer, live (launch)

<!-- field-box id="tm_dancer_live" index="0" -->

```edn [field-box id="tm_dancer_live" role="properties"]
{"id" "tm_dancer_live" "index" 0 "name" "3 - the dancer, live (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - the dancer, live (launch)" "_tombstone" false "frame" #afield/rect [60.0 2160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_dancer_live_code_.kt" "lines" 35 "characters" 1911 "sha256" "b296fbd40fafc3f0ceae92b36c6726ba2e0d296da0ee90db91bac8a19cf0454e"}]}
```

```kotlin [field-property box="tm_dancer_live" name="code" path="tm_dancer_live_code_.kt"]
import afield.io.mocap.*
import afield.math.oracle.*
import kotlin.math.*

// six takes of modern dance (CMU subject 05): every joint, in the dancer's own frame, at 30 Hz,
// without the standing still before and after each take
val dir = "public/docs/assets/temporal_memory/cmu_05"
val takes = ["05_02", "05_07", "05_09", "05_12", "05_14", "05_18"].map { Bvh.read("$dir/$it.bvh.gz") }
val dance = Trajectory.join(takes.map { it.toTrajectory(rate = 30.0).trimStill() })
val memory = VariableMarkovOracle.build(dance)
val colours = [vec(0.48, 0.85, 1.0, 1.0), vec(1.0, 0.62, 0.29, 1.0), vec(0.56, 1.0, 0.6, 1.0),
               vec(1.0, 0.88, 0.4, 1.0), vec(0.77, 0.55, 1.0, 1.0), vec(1.0, 0.48, 0.62, 1.0)]

// the continuation as a figure, stepped at 30 Hz; the floor square and an invisible point
// above head height keep the framing still, with room for raised arms
val player = Improviser(memory, seed = 2, reuseWeight = 3.0, reuseMemory = 60.0)
val floor = fline { moveTo(-12.0, 0.0, -12.0); lineTo(12.0, 0.0, -12.0); lineTo(12.0, 0.0, 12.0); lineTo(-12.0, 0.0, 12.0); lineTo(-12.0, 0.0, -12.0); color = vec(1, 1, 1, 0.25) }
frames {
    val period = (1e9 / 30).toLong()
    var next = System.nanoTime()
    while (frame()) {
        if (System.nanoTime() < next) continue
        next += period
        val pose = player.step()
        val take = dance.episodeOf(max(0, player.position))
        val c = colours[take]
        three(name = "dancer", width = 640, height = 480) {
            // solid tubes rather than lines, so the figure stays visible at any size
            mesh(takes[0].skeletonMesh(pose, dance.channels, radius = 0.6, color = doubleArrayOf(c.x.toDouble(), c.y.toDouble(), c.z.toDouble())), wireframe = false)
            lines(floor, fline { moveTo(0.0, 48.0, 0.0); color = vec(0, 0, 0, 0) })
            helpers(false)
            camera(yaw = 30.0, pitch = 12.0)
        }
    }
}
```

## 2. 6 - five ways to play the dance

<!-- field-box id="tm_judged" index="1" -->

```edn [field-box id="tm_judged" role="properties"]
{"id" "tm_judged" "index" 1 "name" "6 - five ways to play the dance" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 - five ways to play the dance" "_tombstone" false "frame" #afield/rect [60.0 4260.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_judged_code_.kt" "lines" 33 "characters" 2071 "sha256" "99553df9ab59d402e421c8c82da07f810976d86b21c27b3f9ee79cb4ac72f910"}]}
```

```kotlin [field-property box="tm_judged" name="code" path="tm_judged_code_.kt"]
import afield.io.mocap.*
import afield.math.oracle.*
import afield.math.temporal.*
import kotlin.math.*

// six takes of modern dance (CMU subject 05): every joint, in the dancer's own frame, at 30 Hz,
// without the standing still before and after each take
val dir = "public/docs/assets/temporal_memory/cmu_05"
val takes = ["05_02", "05_07", "05_09", "05_12", "05_14", "05_18"].map { Bvh.read("$dir/$it.bvh.gz") }
val dance = Trajectory.join(takes.map { it.toTrajectory(rate = 30.0).trimStill() })
val memory = VariableMarkovOracle.build(dance)
val colours = [vec(0.48, 0.85, 1.0, 1.0), vec(1.0, 0.62, 0.29, 1.0), vec(0.56, 1.0, 0.6, 1.0),
               vec(1.0, 0.88, 0.4, 1.0), vec(0.77, 0.55, 1.0, 1.0), vec(1.0, 0.48, 0.62, 1.0)]

// two minutes, five ways, same seed; the judged ones forecast all 114 channels with Timestral-28M
val seconds = 120.0
val key = ["LeftHand", "RightHand", "Head", "LeftToeBase", "RightToeBase"].flatMap { j -> ["x", "y", "z"].map { dance.channels.indexOf("$j.$it") } }
val runs = TimestralSession().use { session ->
    fun judged(mode: JudgeMode) = ForecastJudge(session, memory, lockstep = true).use { judge ->
        Improviser(memory, seed = 1, jumpProbability = 0.1, judge = judge, judgeMode = mode).take(seconds)
    }
    linkedMapOf(
        "loop the first take" to Improviser(memory, policy = Policy.Loop).take(seconds),
        "nearest history" to Improviser(memory, seed = 1, policy = Policy.NearestHistory, jumpProbability = 0.1).take(seconds),
        "oracle" to Improviser(memory, seed = 1, jumpProbability = 0.1).take(seconds),
        "oracle, Timestral vetoes" to judged(JudgeMode.Veto),
        "oracle, Timestral prefers" to judged(JudgeMode.Prefer),
    )
}
markdown("main", "| two minutes from 61 s of dance | coverage | repeats itself | join roughness | jumps |\n|---|---|---|---|---|\n" +
    runs.entries.joinToString("\n") { (name, p) ->
        "| $name | %.2f | %.2f | %.2f | %d |".format(p.coverage(dance.length), p.outputRepetition(channels = key), p.joinRoughness(dance), p.segments.size - 1)
    })
```

## 3. 2 - the signal, and where it came from

<!-- field-box id="tm_dance_signal" index="2" -->

```edn [field-box id="tm_dance_signal" role="properties"]
{"id" "tm_dance_signal" "index" 2 "name" "2 - the signal, and where it came from" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - the signal, and where it came from" "_tombstone" false "frame" #afield/rect [60.0 1460.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_dance_signal_code_.kt" "lines" 26 "characters" 1630 "sha256" "0d57cbc9f64899681d4b57c12bf83e1b416fc754d45a0e279a9aa939bbdfa53c"}]}
```

```kotlin [field-property box="tm_dance_signal" name="code" path="tm_dance_signal_code_.kt"]
import afield.io.mocap.*
import afield.math.oracle.*
import kotlin.math.*

// six takes of modern dance (CMU subject 05): every joint, in the dancer's own frame, at 30 Hz,
// without the standing still before and after each take
val dir = "public/docs/assets/temporal_memory/cmu_05"
val takes = ["05_02", "05_07", "05_09", "05_12", "05_14", "05_18"].map { Bvh.read("$dir/$it.bvh.gz") }
val dance = Trajectory.join(takes.map { it.toTrajectory(rate = 30.0).trimStill() })
val memory = VariableMarkovOracle.build(dance)
val colours = [vec(0.48, 0.85, 1.0, 1.0), vec(1.0, 0.62, 0.29, 1.0), vec(0.56, 1.0, 0.6, 1.0),
               vec(1.0, 0.88, 0.4, 1.0), vec(0.77, 0.55, 1.0, 1.0), vec(1.0, 0.48, 0.62, 1.0)]

val played = Improviser(memory, seed = 2, reuseWeight = 3.0, reuseMemory = 60.0).take(60.0)
val seconds = List(played.output.length) { it / 30.0 }
fun height(joint: String) = played.output.channel("$joint.y").toList()

// shade each stretch of output by the take it was played from
val hex = ["#7ad9ff", "#ff9e4a", "#8fff9a", "#ffe066", "#c58cff", "#ff7a9e"]
val bands = (played.segments + Segment(played.output.length, -1, -1, Reason.Continue, 0.0, 0, 0.0)).zipWithNext().mapNotNull { (a, b) ->
    if (a.episode < 0) null else SignalRegion("${a.episode + 1}", a.outputStart / 30.0, b.outputStart / 30.0, hex[a.episode])
}
showSignals("right hand" to height("RightHand"), "left hand" to height("LeftHand"), "head" to height("Head"),
    x = seconds, xTitle = "seconds", yTitle = "height", regions = bands,
    title = "heights of hands and head; bands numbered by take",
    channel = "main", width = 1000, height = 380)
```

## 4. 4 - take over a channel, live

<!-- field-box id="tm_live" index="3" -->

```edn [field-box id="tm_live" role="properties"]
{"id" "tm_live" "index" 3 "name" "4 - take over a channel, live" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - take over a channel, live" "_tombstone" false "frame" #afield/rect [60.0 2860.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_live_code_.kt" "lines" 43 "characters" 1960 "sha256" "0c6a01aa712efe0e17bc7b7878680beb5370617ebb5e69db00496c5eb7e71539"}]}
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
checkbox("take over x", channel = "take over") { hold = it }
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

## 5. 5 - Timestral, a forecast and its fan

<!-- field-box id="tm_fan" index="4" -->

```edn [field-box id="tm_fan" role="properties"]
{"id" "tm_fan" "index" 4 "name" "5 - Timestral, a forecast and its fan" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - Timestral, a forecast and its fan" "_tombstone" false "frame" #afield/rect [60.0 3560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_fan_code_.kt" "lines" 21 "characters" 1184 "sha256" "4481b3aa5c5977d9d47f9557430fe56e2b1fce42b16061f78d06f125e26c766f"}]}
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

## 6. 1 - a dancer's memory

<!-- field-box id="tm_dance" index="5" -->

```edn [field-box id="tm_dance" role="properties"]
{"id" "tm_dance" "index" 5 "name" "1 - a dancer's memory" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - a dancer's memory" "_tombstone" false "frame" #afield/rect [60.0 760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_dance_code_.kt" "lines" 33 "characters" 1812 "sha256" "95a0d66409eb970798b37be18720ee18c28e9ac0ab629d0bc1cf7df33d23b78d"}]}
```

```kotlin [field-property box="tm_dance" name="code" path="tm_dance_code_.kt"]
import afield.io.mocap.*
import afield.math.oracle.*
import kotlin.math.*

// six takes of modern dance (CMU subject 05): every joint, in the dancer's own frame, at 30 Hz,
// without the standing still before and after each take
val dir = "public/docs/assets/temporal_memory/cmu_05"
val takes = ["05_02", "05_07", "05_09", "05_12", "05_14", "05_18"].map { Bvh.read("$dir/$it.bvh.gz") }
val dance = Trajectory.join(takes.map { it.toTrajectory(rate = 30.0).trimStill() })
val memory = VariableMarkovOracle.build(dance)
val colours = [vec(0.48, 0.85, 1.0, 1.0), vec(1.0, 0.62, 0.29, 1.0), vec(0.56, 1.0, 0.6, 1.0),
               vec(1.0, 0.88, 0.4, 1.0), vec(0.77, 0.55, 1.0, 1.0), vec(1.0, 0.48, 0.62, 1.0)]

// one minute of new dance from 61 seconds of memory
val played = Improviser(memory, seed = 2, reuseWeight = 3.0, reuseMemory = 60.0).take(60.0)

// a pose every half second for the first sixteen seconds, two rows, coloured by take
val poses = (0 until 32).flatMap { k ->
    val t = k * 15
    val take = dance.episodeOf(max(0, played.source[t]))
    val at = doubleArrayOf((k % 16) * 14.0, if (k < 16) 0.0 else -42.0, 0.0)
    takes[0].skeleton(played.output.frame(t), dance.channels, at).onEach { it.color = colours[take]; it.thickness = 2.0 }
}
print("${played.segments.size - 1} jumps; every take used: coverage ${"%.2f".format(played.coverage(dance.length))}")
// an invisible point above the rows leaves room for the viewer's toolbar
val headroom = fline { moveTo(0.0, 70.0, 0.0); color = vec(0, 0, 0, 0) }
three(name = "dance-strip", width = 1000, height = 420) {
    lines(poses + headroom)
    helpers(false)
    camera(yaw = 0.0, pitch = 4.0)
    title("one minute from six takes, first sixteen seconds, a pose every half second")
    caption("colour = which take the pose was played from")
}
```

## 7. 0 - lab notebook

<!-- field-box id="tm_notebook" index="6" -->

```edn [field-box id="tm_notebook" role="properties"]
{"id" "tm_notebook" "index" 6 "name" "0 - lab notebook" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "0 - lab notebook" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tm_notebook_code_.kt" "lines" 65 "characters" 3889 "sha256" "592b52a3837446a743544d3ea3e9c5ca7b47bc2fef73a7c2abb8b661bd356483"}]}
```

```kotlin [field-property box="tm_notebook" name="code" path="tm_notebook_code_.kt"]
markdown("""
# Continuing a gesture — lab notebook

October 2026. Goal: remember a little performed movement, continue it for as long as you like, and stay
responsive when someone takes over a channel. No training. Two backends behind one player: the Variable
Markov Oracle (explicit memory) and Mistral's Timestral (a pretrained forecaster).

**Boxes.** 1–3 are a dancer: six CMU mocap takes (subject 05, modern dance), the oracle's continuation as a
strip of poses, the signal with the take each moment came from, and the dancer live. 4 is two oscillators
with a slider to take over x. 5 needs Timestral installed (TimestralSession.install()) and draws a forecast
fan. 6 plays the dance five ways, two of them judged by Timestral.

## Timestral alone

- Fed back on itself, 28M fades to a tenth of the signal within minutes; 209M keeps a smooth figure-eight
  going for five minutes but loses an amplitude-modulated channel and settles into silence on bursty
  material. Replans: 28M ~30 ms, 209M 150–250 ms (p95 up to 660 ms); 114 channels: a few hundred ms.
- Known-future covariates work: y = x one second late, x's future given, error on y halves (0.20 vs 0.44).
- Quantiles are per step: drawn as a fan, never sampled as performances.

## The hybrid, and how we got it wrong first

First fixture: three synthetic "phrase" takes built from four gestures. Timestral judging the oracle
(prefer / veto) changed most decisions; repetition, measured as memory-position reuse, went up. Then Marc asked
to see the played signal: both the plain and judged oracle were cycling rise → hesitate → burst → pause every
6 s. The takes were copies of the same four gestures, so memory-position measures saw variety that the output
did not have. Two lessons: measure the output (outputRepetition, now on Performance), and use real material.

## The dancer

Real material broke the improviser three ways, each visible only on 114 channels:

1. Thresholds summed over channels. A correction norm over 114 channels never passed maxCorrection = 1, so
   it froze (5122 holds in 5400 steps). Now every correction and cue distance is RMS per channel.
2. Suffix links do not cross takes. 3 of 6 takes were never entered. Added bridges: nearest moments in
   other takes, as extra candidates with no shared context.
3. Takes end alike. At a take's end the best matches were other endings, a loop around everyone's last
   seconds (coverage 0.34). Now: no jump with less than a phrase of runway; a take ends by beginning the
   least recently played other take within restartTolerance; holds last at most a phrase.

Then: trimStill() for still lead-ins, and escapeReplays: while replaying something heard in the last
reuseMemory seconds, consider a jump every step.

| dance, 3 min from 61 s | coverage | repeats itself | holds |
|---|---|---|---|
| loop one take | 0.15 | 0.97 | 0 |
| nearest history | 0.43–0.64 | 0.66–0.77 | 3–10 |
| oracle, first try | 0.42 | 0.85 | 31 |
| oracle now | 1.00 | 0.55–0.58 | 0 |

A three-minute replay of 61 s must repeat about two thirds of its windows, so 0.55 is real recombination.

**Timestral as judge on the dance (box 6, two minutes):** coverage unchanged, repetition 0.45–0.48 vs the
oracle's 0.43, preferring gives slightly smoother joins and fewer jumps. It changes most decisions and moves
the result a little. Not a new capability on this material.

## Open

- The judge with known covariates: a performer's channel as Timestral's known future, not just its history.
- Blind A/B viewing of oracle vs judged dance; the measures disagree at the margins.
- Hard endpoints ("arrive here in 4 s"); tempo, offset and amplitude transforms of recalled passages.
- More dancers: the bridges make a memory of several performers possible.

Full report: dev_docs/TEMPORAL_MEMORY_EXPERIMENTS.md. Data: public/docs/assets/temporal_memory/cmu_05/README.md.
""".trimIndent())
```

