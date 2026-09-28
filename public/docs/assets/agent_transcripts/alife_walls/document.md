# Field Document: alife_walls

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. Following a wall, and climbing a light

<!-- field-box id="cling_and_climb" index="0" -->

### Narrative

From `code`

Two local drives that know about the environment, in the same weighted-blend idiom as
separate/align/cohere.

cling(distance, clockwise) — follow the nearest wall at a chosen standoff. Tangential motion
plus a radial correction, which is exactly what orbit() is; the difference is that the thing
being orbited is read off the geometry rather than passed in.

gradient(field, towards) — climb (or descend) a scalar field by central difference. Taxis:
chemotaxis, phototaxis, whichever the field means. It finds a LOCAL maximum and has nothing
to say about the global one, which is why two lamps split a population rather than gathering
it — that is the correct behaviour, not a weight to tune away.

```edn [field-box id="cling_and_climb" role="properties"]
{"id" "cling_and_climb" "index" 0 "name" "Following a wall, and climbing a light" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Following a wall, and climbing a light" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cling_and_climb_code_.kt" "lines" 74 "characters" 3342 "sha256" "5363be63e69c656b10d2047d0e1a5fd0d5544d31993b6d046ee28fd3cc266c4d"}]}
```

```kotlin [field-property box="cling_and_climb" name="code" path="cling_and_climb_code_.kt"]
// Two local drives that know about the environment, in the same weighted-blend idiom as
// separate/align/cohere.
//
//   cling(distance, clockwise) — follow the nearest wall at a chosen standoff. Tangential motion
//   plus a radial correction, which is exactly what orbit() is; the difference is that the thing
//   being orbited is read off the geometry rather than passed in.
//
//   gradient(field, towards) — climb (or descend) a scalar field by central difference. Taxis:
//   chemotaxis, phototaxis, whichever the field means. It finds a LOCAL maximum and has nothing
//   to say about the global one, which is why two lamps split a population rather than gathering
//   it — that is the correct behaviour, not a weight to tune away.

val w = ScriptWorld(width = 200.0, height = 200.0, seed = 11L, wrap = false)
w.addDisc(62.0, 130.0, 26.0, segments = 28, name = "pillar")
w.addRect(120.0, 40.0, 50.0, 34.0, name = "block")

val lamps = pointSourceField(
    150.0, 160.0, 1.0,
    45.0, 40.0, 1.0,
    falloff = 26.0
)

// Six clingers round the pillar, alternating direction — a population that all clings the same
// way never meets itself coming back.
repeat(6) { i ->
    val a = 2.0 * Math.PI * i / 6.0
    w.spawn(
        62.0 + Math.cos(a) * 36.0, 130.0 + Math.sin(a) * 36.0,
        maxSpeed = 26.0, radius = 1.0, tag = "cling"
    ) {
        moveFor(12.0, 26.0) { cling(distance = 3.0, clockwise = self.id % 2 == 0) }
    }
}

// Nine climbers on a grid centred between the two lamps, with the block in the way of one of
// them. Centred deliberately: start them nearer one lamp and "they split" is a claim about the
// starting positions rather than about the sensing.
repeat(9) { i ->
    val x = 78.0 + (i % 3) * 22.0
    val y = 78.0 + (i / 3) * 22.0
    w.spawn(x, y, maxSpeed = 18.0, radius = 1.0, tag = "climb") {
        moveFor(12.0, 18.0) {
            gradient(lamps, towards = true, probe = 2.0)
            avoidWalls(clearance = 3.0, weight = 0.5)
            separate(range = 5.0, weight = 0.4)
        }
    }
}

w.run(720)

val clingers = w.living.filter { it.tag == "cling" }
val climbers = w.living.filter { it.tag == "climb" }
fun near(a: ScriptActor, x: Double, y: Double) = Math.hypot(a.position.x - x, a.position.y - y) < 10.0

println("cling  : mean distance to the nearest wall %.2f (asked for radius 1.0 + 3.0 = 4.0)".format(
    clingers.map { w.wallField.distanceTo(it.position.x, it.position.y) }.average()))
println("climb  : mean field value %.3f".format(
    climbers.map { lamps.at(it.position.x, it.position.y) }.average()))
println("         %d at the lamp at (150, 160), %d at the lamp at (45, 40), %d still travelling".format(
    climbers.count { near(it, 150.0, 160.0) },
    climbers.count { near(it, 45.0, 40.0) },
    climbers.count { !near(it, 150.0, 160.0) && !near(it, 45.0, 40.0) }))
println()
println("Nothing told the climbers which lamp to pick — a gradient sensor reads the slope where")
println("it is standing and knows nothing about the other maximum. The split is the whole point.")

lines(
    *(w.obstacleFLines() + w.trailFLines()).toTypedArray(),
    channel = "arena", w = 440.0, h = 440.0, fitToSize = true
)

// Try this: `gradient(lamps, towards = false)` and the climbers seek the dark instead — the
// corners of the arena, and the shadow behind the block.
```

## 2. A wall, and three answers to hitting it

<!-- field-box id="hit_a_wall" index="1" -->

### Narrative

From `code`

A wall in alife_script is a line SEGMENT, and a segment has no thickness. So the obvious
collision test — "does the agent's new position overlap a wall" — is false for any step big
enough to step over it, at any speed at all. The collision here is swept: it solves for the
first moment of contact along the step, not for where the step happened to land.

Three agents, one wall, three answers to "what now".

```edn [field-box id="hit_a_wall" role="properties"]
{"id" "hit_a_wall" "index" 1 "name" "A wall, and three answers to hitting it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A wall, and three answers to hitting it" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "hit_a_wall_code_.kt" "lines" 39 "characters" 2032 "sha256" "ecd2b1bd212edee01f4a3dd4f5cfd81ba8cb1d4ec4451859b7b520b0a45aa957"}]}
```

```kotlin [field-property box="hit_a_wall" name="code" path="hit_a_wall_code_.kt"]
// A wall in alife_script is a line SEGMENT, and a segment has no thickness. So the obvious
// collision test — "does the agent's new position overlap a wall" — is false for any step big
// enough to step over it, at any speed at all. The collision here is swept: it solves for the
// first moment of contact along the step, not for where the step happened to land.
//
// Three agents, one wall, three answers to "what now".

val w = ScriptWorld(width = 200.0, height = 200.0, seed = 1L, wrap = false)
// A room as well as the barrier, so a BOUNCE agent has something to come back off. The arena
// edge is NOT a wall — ScriptWorld.confine just clamps there — so without this the bouncer
// parks in a corner and the trail says nothing.
w.addPolygon(12.0, 12.0, 188.0, 12.0, 188.0, 188.0, 12.0, 188.0, name = "room")
w.addWall(120.0, 30.0, 120.0, 170.0, name = "barrier")

listOf(WallResponse.SLIDE, WallResponse.BOUNCE, WallResponse.STOP).forEachIndexed { i, r ->
    w.spawn(
        25.0, 60.0 + i * 25.0, maxSpeed = 90.0, radius = 2.0, steerResponse = 1.0,
        vx = 90.0, vy = 16.0, wallResponse = r, restitution = 0.9, tag = r.name
    ) { moveFor(8.0) { keepVelocity() } }
}
w.run(420)

w.actors.forEach {
    println("%-7s %3d contact(s), ended at %6.1f, %6.1f".format(
        it.tag, it.wallContacts, it.position.x, it.position.y))
}
println()
println("90 units/s at 60 Hz is 1.5 units per step against a radius of 2 — no tunnelling risk.")
println("Raise maxSpeed and vx to 900 and every number above stays sane; that is the whole")
println("point of solving for the contact instead of testing the endpoint.")

lines(
    *(w.obstacleFLines() + w.trailFLines()).toTypedArray(),
    channel = "wall", w = 440.0, h = 440.0, fitToSize = true
)

// Try this: set `restitution = 0.3` on the BOUNCE agent and it loses most of its speed at the
// wall. Set it to 1.0 and the trail is a billiard. SLIDE is the default because it is the only
// one of the three that lets an agent still get somewhere while touching something.
```

## 3. Routed seek against direct seek, on a maze

<!-- field-box id="route_vs_seek" index="2" -->

### Narrative

From `code`

seek() points at the goal and pushes. That is correct and cheap when there is a straight line,
and when there is a barrier in the way it presses into the barrier for as long as you let it.
No amount of avoidWalls() fixes that: avoidance is local, and the decision to go the OTHER way
round a wall is not. route() plans — a radius-aware A* through the world's walls, smoothed
against the same swept test the physics uses, then followed waypoint by waypoint.

The maze is an S. The first barrier is open only at the BOTTOM, the second only at the TOP, so
no fixed "always turn left" solves it. Both arms run avoidWalls at the same weight: this is
routing against the best local steering available, not routing against nothing.

```edn [field-box id="route_vs_seek" role="properties"]
{"id" "route_vs_seek" "index" 2 "name" "Routed seek against direct seek, on a maze" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Routed seek against direct seek, on a maze" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "route_vs_seek_code_.kt" "lines" 78 "characters" 3606 "sha256" "4e1d99fb5f09f352f7e70c89791025fe7787958ad43395c913c50eb822ad2e0f"}]}
```

```kotlin [field-property box="route_vs_seek" name="code" path="route_vs_seek_code_.kt"]
// seek() points at the goal and pushes. That is correct and cheap when there is a straight line,
// and when there is a barrier in the way it presses into the barrier for as long as you let it.
// No amount of avoidWalls() fixes that: avoidance is local, and the decision to go the OTHER way
// round a wall is not. route() plans — a radius-aware A* through the world's walls, smoothed
// against the same swept test the physics uses, then followed waypoint by waypoint.
//
// The maze is an S. The first barrier is open only at the BOTTOM, the second only at the TOP, so
// no fixed "always turn left" solves it. Both arms run avoidWalls at the same weight: this is
// routing against the best local steering available, not routing against nothing.

val GOAL_X = 175.0
val GOAL_Y = 100.0
val STEPS = 1800                 // 30 s at 60 Hz
val SEEDS = listOf(1L, 2L, 3L, 4L)
val PER_SEED = 8

fun maze(seed: Long, routed: Boolean): ScriptWorld {
    val w = ScriptWorld(width = 200.0, height = 200.0, seed = seed, wrap = false)
    w.addWall(70.0, 45.0, 70.0, 200.0, name = "open at the bottom")
    w.addWall(130.0, 0.0, 130.0, 155.0, name = "open at the top")
    val jitter = kotlin.random.Random(seed)      // same jitter in both arms: same start positions
    repeat(PER_SEED) {
        w.spawn(
            18.0 + jitter.nextDouble(-8.0, 8.0), 100.0 + jitter.nextDouble(-28.0, 28.0),
            maxSpeed = 35.0, radius = 1.2, steerResponse = 0.3,
            tag = if (routed) "routed" else "direct"
        ) {
            val goal = point(GOAL_X, GOAL_Y)         // R3c: this now works inside moveUntil too
            moveUntil(maxSeconds = STEPS / 60.0, speed = 35.0, until = { near(goal, 6.0) }) {
                if (routed) route(goal, clearance = 3.5) else seek(goal)
                avoidWalls(clearance = 3.0, weight = 0.6)
                separate(range = 4.0, weight = 0.4)
            }
            rest(2.0)
        }
    }
    return w
}

val curves = LinkedHashMap<String, List<Double>>()
val shown = LinkedHashMap<String, ScriptWorld>()
for (routed in listOf(true, false)) {
    val name = if (routed) "routed" else "direct"
    val cumulative = MutableList(STEPS) { 0.0 }
    var population = 0
    var travel = 0.0
    for (s in SEEDS) {
        val w = maze(s, routed)
        val done = mutableSetOf<Int>()
        repeat(STEPS) { t ->
            w.step()
            for (a in w.actors) if (a.id !in done && a.distanceTo(GOAL_X, GOAL_Y) <= 6.0) done += a.id
            cumulative[t] = cumulative[t] + done.size
        }
        population += w.actors.size
        travel += w.totalPathLength()
        if (s == SEEDS.first()) shown[name] = w
    }
    curves[name] = cumulative.map { it / population }
    println("%-7s arrived %.3f of %d   mean travel %.1f".format(
        name, cumulative.last() / population, population, travel / population))
}
println()
println("Both arms travel — the second column is the non-degeneracy check. A margin over a")
println("control that never left home would mean nothing.")

showSignals(
    "routed" to curves["routed"]!!,
    "direct" to curves["direct"]!!,
    channel = "arrivals",
    title = "fraction arrived, ${SEEDS.size} seeds x $PER_SEED agents per arm",
    yTitle = "of 1"
)

// Try this: change `route(goal, clearance = 3.5)` to `clearance = 0.5`. The route still gets
// there, but it plans 1.7 units from every corner — inside the reach of the avoidWalls term the
// same agent is running — and the two terms then disagree all the way round each corner. The
// arrival curve sags. Plan outside your own avoidance range.
```

