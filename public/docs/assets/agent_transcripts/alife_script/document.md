# Field Document: alife_script

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. A division of labour one global rule does not buy

<!-- field-box id="patrol" index="0" -->

### Narrative

From `code`

What a per-agent program buys that one global rule cannot: a DIVISION OF LABOUR.

Six sites, twenty-four agents, two arms at the same speed on the same integrator.
- scripted: each agent walks the six sites in its OWN rotation, then feeds for 1.2 s.
- one global rule: the site is a function of the world clock, so everybody goes to the
same place at the same time. That is not a straw man — it is given the kinder 2.5 s
dwell, long enough to arrive and short enough to reach all six inside the run.

The number underneath is "how many of the six sites had somebody on them", averaged over every
step. Six agents spread over six sites scores 6; twenty-four agents in a heap scores 1.

```edn [field-box id="patrol" role="properties"]
{"id" "patrol" "index" 0 "name" "A division of labour one global rule does not buy" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A division of labour one global rule does not buy" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "patrol_code_.kt" "lines" 73 "characters" 3174 "sha256" "fafa47991dc60238180c7e8092b55c2961a54dbadce80ba0cc2fe81d32335e9d"}]}
```

```kotlin [field-property box="patrol" name="code" path="patrol_code_.kt"]
// What a per-agent program buys that one global rule cannot: a DIVISION OF LABOUR.
//
// Six sites, twenty-four agents, two arms at the same speed on the same integrator.
//   - scripted: each agent walks the six sites in its OWN rotation, then feeds for 1.2 s.
//   - one global rule: the site is a function of the world clock, so everybody goes to the
//     same place at the same time. That is not a straw man — it is given the kinder 2.5 s
//     dwell, long enough to arrive and short enough to reach all six inside the run.
//
// The number underneath is "how many of the six sites had somebody on them", averaged over every
// step. Six agents spread over six sites scores 6; twenty-four agents in a heap scores 1.

val sites = (0 until 6).map { i ->
    val a = 2.0 * Math.PI * i / 6
    Vector2d(100.0 + Math.cos(a) * 60.0, 100.0 + Math.sin(a) * 60.0)
}
val agents = 24
val steps = 900

fun world(scripted: Boolean) = ScriptWorld(width = 200.0, height = 200.0, seed = 21L).also { w ->
    for (i in 0 until agents) {
        val a = 2.0 * Math.PI * i / agents
        val order = (0 until 6).map { (it + i) % 6 }   // <- the per-agent part
        w.spawn(100.0 + Math.cos(a) * 18.0, 100.0 + Math.sin(a) * 18.0, maxSpeed = 45.0) {
            forever {
                if (scripted) {
                    for (s in order) {
                        val site = point(sites[s].x, sites[s].y)
                        phase("travel") {
                            moveUntil(maxSeconds = 5.0, until = { near(site, 7.0) }) {
                                arrive(site, slowRadius = 14.0); separate(range = 6.0, weight = 0.35)
                            }
                        }
                        phase("feed") { rest(1.2) }
                    }
                } else {
                    val s = ((time / 2.5).toInt()) % 6      // <- the global part: clock only
                    val site = point(sites[s].x, sites[s].y)
                    moveFor(0.1, 45.0) {
                        arrive(site, slowRadius = 14.0); separate(range = 6.0, weight = 0.35)
                    }
                }
            }
        }
    }
}

val arms = listOf("scripted" to world(true), "one global rule" to world(false))
val breadth = arms.map { mutableListOf<Double>() }
repeat(steps) {
    arms.forEachIndexed { i, (_, w) ->
        w.step()
        breadth[i] += sites.count { s -> w.living.any { it.distanceTo(s) <= 14.0 } }.toDouble()
    }
}

arms.forEachIndexed { i, (name, w) ->
    println("%-16s mean sites occupied %.3f   travel %.0f".format(
        name, breadth[i].average(), w.totalPathLength()))
}
println()
println("Both arms move (compare the travel column) — a margin over a control that never left")
println("home would mean nothing. The scripted arm travels LESS and covers MORE.")

showSignals(
    "scripted" to breadth[0],
    "one global rule" to breadth[1],
    channel = "breadth",
    title = "sites occupied, per step",
    yTitle = "of 6"
)

// Try this: set `order` to `(0 until 6).toList()` for every agent — same script, no rotation —
// and the scripted arm collapses towards the global one. The rotation IS the division of labour.
```

## 2. Phases: flock, scatter, flock, scatter

<!-- field-box id="regime" index="1" -->

### Narrative

From `code`

Phases: the same population, told to do different things at different times.

Ninety agents, four 5 s phases — flock, scatter, flock, scatter. `phase("name")` is just a label
on a stretch of a program, but because every agent is running its own program, a phase change is
ninety independent decisions rather than a global mode switch.

Measured with alife.kt's own polarization, through asBoidFlock(): 1 is everybody heading the same
way, 0 is no agreement. Nothing here asserts the phases worked — the plot shows whether they did.

Two things about the initial condition, and both of them are the whole reason this figure works.

RANDOM headings, not tangential ones. Ninety agents on a ring all facing along it is perfectly
symmetric, so their velocities cancel and polarization is EXACTLY 0.0 — and because align and
cohere preserve that symmetry, it is a fixed point: the first flock phase then reads 0.000 for
five seconds and the figure looks broken. A symmetric start is not disordered, it is cancelling.

And a velocity at all, rather than rest: align steers toward the mean NEIGHBOUR velocity, and a
population at rest has none — rest is not disordered, it is empty.

```edn [field-box id="regime" role="properties"]
{"id" "regime" "index" 1 "name" "Phases: flock, scatter, flock, scatter" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Phases: flock, scatter, flock, scatter" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "regime_code_.kt" "lines" 62 "characters" 3253 "sha256" "04005a47f43a490af1ddf4e166954d07ad811f8b2750d589a622afed22c87b6c"}]}
```

```kotlin [field-property box="regime" name="code" path="regime_code_.kt"]
// Phases: the same population, told to do different things at different times.
//
// Ninety agents, four 5 s phases — flock, scatter, flock, scatter. `phase("name")` is just a label
// on a stretch of a program, but because every agent is running its own program, a phase change is
// ninety independent decisions rather than a global mode switch.
//
// Measured with alife.kt's own polarization, through asBoidFlock(): 1 is everybody heading the same
// way, 0 is no agreement. Nothing here asserts the phases worked — the plot shows whether they did.

// Two things about the initial condition, and both of them are the whole reason this figure works.
//
// RANDOM headings, not tangential ones. Ninety agents on a ring all facing along it is perfectly
// symmetric, so their velocities cancel and polarization is EXACTLY 0.0 — and because align and
// cohere preserve that symmetry, it is a fixed point: the first flock phase then reads 0.000 for
// five seconds and the figure looks broken. A symmetric start is not disordered, it is cancelling.
//
// And a velocity at all, rather than rest: align steers toward the mean NEIGHBOUR velocity, and a
// population at rest has none — rest is not disordered, it is empty.
val rng = kotlin.random.Random(5L)
val w = ScriptWorld(width = 220.0, height = 220.0, seed = 5L)
repeat(90) { i ->
    val a = 2.0 * Math.PI * i / 90
    val h = rng.nextDouble(0.0, 2.0 * Math.PI)
    w.spawn(
        110.0 + Math.cos(a) * 40.0, 110.0 + Math.sin(a) * 40.0,
        maxSpeed = 30.0, vx = Math.cos(h) * 12.0, vy = Math.sin(h) * 12.0
    ) {
        forever {
            phase("flock") { moveFor(5.0) { align(range = 26.0); cohere(range = 26.0, weight = 0.5); separate(range = 7.0); avoidEdges() } }
            // turn = 40, not 6: at 60 Hz a small turn random-walks a heading less than a radian
            // across 5 s, which PRESERVES the alignment it inherited instead of breaking it.
            phase("scatter") { moveFor(5.0) { wander(turn = 40.0); separate(range = 9.0); avoidEdges() } }
        }
    }
}

val order = mutableListOf<Double>()
repeat(1200) {
    w.step()
    val f = w.asBoidFlock()
    order += polarization(f.vx, f.vy)
}

println("phase means over the last second of each 5 s phase:")
listOf("flock" to 0, "scatter" to 1, "flock" to 2, "scatter" to 3).forEach { (name, k) ->
    println("  %-8s %.3f".format(name, order.subList(k * 300 + 240, k * 300 + 300).average()))
}

// One output, on one channel. Two panels in a box stack on top of each other, and the plot IS the
// claim here — a picture of ninety trails is a grey doughnut whichever phase drew it.
// `marks` only on the two scatter boundaries: a mark per phase repeats "flock"/"scatter" four
// times in the legend, and a mark at step 0 labels nothing.
showSignals(
    "polarization" to order,
    channel = "order",
    title = "alignment: flock, scatter, flock, scatter — 5 s each",
    marks = listOf("scatter" to 300.0, "scatter" to 900.0)
)

// Try this: drop `turn = 40.0` to 6.0 and watch the scatter phases stop working — polarization
// stays high, because a gentle random walk does not decorrelate a heading inside five seconds.
// That was a real bug in this demo's first version, and the plot is what caught it.
```

## 3. One agent, one program

<!-- field-box id="one_agent" index="2" -->

### Narrative

From `code`

One agent, one program. That is the whole idea of this layer.

alife.kt's swarms take a rule and a parameter block and advance everybody with one step(). Here
each agent runs its own suspending program, and the clock is a fixed step, so the run is the
same every time you press play.

```edn [field-box id="one_agent" role="properties"]
{"id" "one_agent" "index" 2 "name" "One agent, one program" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "One agent, one program" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "one_agent_code_.kt" "lines" 22 "characters" 1297 "sha256" "d36468d618374e2441594ca975252d44e0f9a8fd4816f33597c7d1dc87dbde3c"}]}
```

```kotlin [field-property box="one_agent" name="code" path="one_agent_code_.kt"]
// One agent, one program. That is the whole idea of this layer.
//
// alife.kt's swarms take a rule and a parameter block and advance everybody with one step(). Here
// each agent runs its own suspending program, and the clock is a fixed step, so the run is the
// same every time you press play.

val w = ScriptWorld(width = 200.0, height = 200.0, seed = 1L)
w.spawn(100.0, 60.0, maxSpeed = 50.0) {
    val centre = point(100.0, 100.0)          // hoisted: see the note at the bottom
    forever { moveFor(4.0) { orbit(centre, distance = 40.0) } }
}
w.run(480)
lines(*w.trailFLines().toTypedArray(), channel = "orbit", w = 440.0, h = 240.0, fitToSize = true)

// Try this: change `distance = 40.0` to 20.0 and re-run — the circle tightens.
// Then swap `orbit(centre, ...)` for `wander(turn = 30.0)` and the same lines draw a random walk
// instead. The program is the agent's, so changing it changes one agent and nobody else.
//
// Why `centre` is hoisted out of the steering block: `point()` belongs to the behaviour scope,
// and inside `moveFor { }` the receiver is the steering builder, which does not expose it. Written
// inline it fails to compile with "cannot be called in this context with an implicit receiver" —
// so build targets where the program can see them, and pass them in.
```

