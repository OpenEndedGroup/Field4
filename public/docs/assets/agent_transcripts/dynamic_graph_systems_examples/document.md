# Field Document: dynamic_graph_systems_examples

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 1 — six ways to propose an edge

<!-- field-box id="dgs_six_systems" index="0" -->

### Narrative

From `code`

Six DynamicGraphSystems, one cloud, one clock, one reinforcement budget. The only thing
that differs between panels is what the system thinks an edge means. Brightness is
weight / 4.0 -- the shared ceiling -- so it is a FIXED scale and a dim panel is a system
whose weights genuinely stayed low.

```edn [field-box id="dgs_six_systems" role="properties"]
{"id" "dgs_six_systems" "index" 0 "name" "1 — six ways to propose an edge" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — six ways to propose an edge" "_tombstone" false "frame" #afield/rect [60.0 60.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dgs_six_systems_code_.kt" "lines" 62 "characters" 3515 "sha256" "6860da5bfddfb932c8760b734fd725b4134aad4ea2bb244c2ba3da1204331ddb"}]}
```

```kotlin [field-property box="dgs_six_systems" name="code" path="dgs_six_systems_code_.kt"]
// Six DynamicGraphSystems, one cloud, one clock, one reinforcement budget. The only thing
// that differs between panels is what the system thinks an edge means. Brightness is
// weight / 4.0 -- the shared ceiling -- so it is a FIXED scale and a dim panel is a system
// whose weights genuinely stayed low.
val cloud = List(220) { i ->
    val a = i * 2.39996322972865332
    val r = 0.5 * Math.sqrt((i + 0.5) / 220.0)
    Vec3((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat(), 0f)
}
val window = dynamicGraphWindow(cloud, padFraction = 0.06)
val ceiling = 4.0

fun build(system: DynamicGraphSystem): Pair<DynamicPointState, DynamicEdgeList> {
    val state = DynamicPointState(cloud.map { Vec3(it) }.toMutableList())
    val edges = DynamicEdgeList(state.pointCount)
    val hash = Vec3SpatialHash(0.06)
    repeat(20) { stepDynamicGraph(1f / 60f, state, edges, hash, system) }
    return state to edges
}

val systems = listOf<Pair<String, DynamicGraphSystem>>(
    "kNN k=5" to Vec3BreathingAnisotropicKnnSystem(
        k = 5, ttl = 10f, reinforce = 4.0, maxWeight = ceiling, mutualOnly = true, queryRadius = 0.12),
    "flowline" to Vec3FlowlineAdvectionGraphSystem(
        influenceRadius = 0.10, stepLength = 0.08, ttl = 10f, reinforce = 4.0, maxWeight = ceiling),
    "tangent fan" to Vec3LocalTangentTriangulationSystem(
        neighborhoodK = 6, ttl = 10f, reinforce = 4.0, maxWeight = ceiling, maxNeighborRadius = 0.10),
    "hysteresis" to Vec3PersistentProximityHysteresisSystem(
        rEnter = 0.055, rExit = 0.075, ttl = 10f, reinforce = 4.0, maxWeight = ceiling),
    "routing, 1 arc" to Vec3SignalRoutingGraphSystem(
        neighborhoodK = 6, ttl = 10f, reinforce = 1.0, maxWeight = ceiling, routeCount = 1,
        maxNeighborRadius = 0.10),
    "alpha-event" to Vec3AlphaEventHypergraphCollapseSystem(
        neighborhoodK = 6, alphaRadius = 0.075, ttl = 10f, reinforce = 4.0, maxWeight = ceiling,
        maxNeighborRadius = 0.09, minArea = 1e-4)
)

// "What fraction of edges close a triangle" is the one exact structural fact separating the
// six: it is 0 % for a functional map and 100 % for anything that builds faces.
val panels = systems.map { (name, system) ->
    val (state, edges) = build(system)
    val adjacency = Array(state.pointCount) { HashSet<Int>() }
    for (e in edges.values()) { adjacency[e.i].add(e.j); adjacency[e.j].add(e.i) }
    val closed = edges.values().count { e ->
        adjacency[e.i].any { it != e.j && adjacency[e.j].contains(it) } }
    val fraction = if (edges.values().isEmpty()) 0.0 else closed.toDouble() / edges.values().size
    val wmax = edges.values().maxOfOrNull { it.weight } ?: 0.0
    println("%-16s %4d edges  %5.1f%% in a triangle  mean degree %.2f  wmax %.4f (%.1f%% of ceiling)".format(
        name, edges.values().size, 100.0 * fraction, 2.0 * edges.values().size / state.pointCount,
        wmax, 100.0 * wmax / ceiling))
    // dynamicGraphFLines, not dynamicGraphPanel: a still figure is VECTOR here -- one FLine
    // per edge, weight driving colour AND thickness, through showPlaneGrid -> drawToSVG.
    "%s: %de %d%%tri".format(name, edges.values().size, Math.round(100.0 * fraction)) to
        dynamicGraphFLines(state.points, edges.values(), maxWeight = ceiling, colormap = "magma")
}

// One shared window, so nothing but the topology differs between the six panels.
showPlaneGrid(
    *panels.toTypedArray(),
    columns = 3, cellWidth = 400, cellHeight = 400,
    window = dynamicGraphPlaneWindow(window),
)
```

## 2. 2 — reinforcement and forgetting

<!-- field-box id="dgs_decay" index="1" -->

### Narrative

From `code`

Both decay laws against their closed forms. decayExponential must be independent of the
step size it is reached by; decayLinear describes a different curve, which is the control
that stops this being a statement about the harness.

```edn [field-box id="dgs_decay" role="properties"]
{"id" "dgs_decay" "index" 1 "name" "2 — reinforcement and forgetting" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — reinforcement and forgetting" "_tombstone" false "frame" #afield/rect [530.0 60.0 330.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dgs_decay_code_.kt" "lines" 49 "characters" 2310 "sha256" "ef868041bb19cc011d4cdbc6164db8f593eae0d02d0baa0dc94e59d64ce2668f"}]}
```

```kotlin [field-property box="dgs_decay" name="code" path="dgs_decay_code_.kt"]
// Both decay laws against their closed forms. decayExponential must be independent of the
// step size it is reached by; decayLinear describes a different curve, which is the control
// that stops this being a statement about the harness.
fun weightAfter(steps: Int, seconds: Float, exponential: Boolean): Double {
    val edges = DynamicEdgeList(2)
    edges.reinforce(0, 1, 1.0, ttl = 1000f, maxWeight = 1.0)
    val dt = seconds / steps
    repeat(steps) {
        if (exponential) edges.decayExponential(dt, 0.35) else edges.decayLinear(dt, 0.35)
    }
    return edges.get(0, 1)?.weight ?: 0.0
}

println("exponential, 2 s at lambda = 0.35 -- closed form e^-0.7 = %.9f".format(Math.exp(-0.7)))
for (n in listOf(1, 4, 120)) println("   in %3d steps: %.9f".format(n, weightAfter(n, 2f, true)))
println("linear, 2 s at 0.35/s -- closed form 1 - 0.7 = 0.300000000")
for (n in listOf(1, 4, 120)) println("   in %3d steps: %.9f".format(n, weightAfter(n, 2f, false)))

val halfLife = Math.log(2.0) / 0.35
val e = DynamicEdgeList(2)
e.reinforce(0, 1, 1.0, ttl = 1000f, maxWeight = 1.0)
e.decayExponential(halfLife.toFloat(), 0.35)
println("ln2/lambda = %.4f s halves the weight: %.9f".format(halfLife, e.get(0, 1)!!.weight))

// ttl is the OTHER way an edge dies, and it is independent of weight.
val heavy = DynamicEdgeList(2)
heavy.reinforce(0, 1, 8.0, ttl = 0.5f, maxWeight = 8.0)
repeat(4) { heavy.decayExponential(0.1f, 1e-9) }
println("ttl 0.5 s, at t = 0.4 s: ${heavy.values().size} edge of weight ${heavy.get(0, 1)?.weight}")
heavy.decayExponential(0.2f, 1e-9)
println("            at t = 0.6 s: ${heavy.values().size} edges -- expired at the ceiling")

// Sampled by stepping a real edge store, not by evaluating the formula.
val samples = 240
val expo = DoubleArray(samples)
val lin = DoubleArray(samples)
val a = DynamicEdgeList(2).also { it.reinforce(0, 1, 1.0, 1000f, 1.0) }
val b = DynamicEdgeList(2).also { it.reinforce(0, 1, 1.0, 1000f, 1.0) }
for (i in 0 until samples) {
    expo[i] = a.get(0, 1)?.weight ?: 0.0
    lin[i] = b.get(0, 1)?.weight ?: 0.0
    a.decayExponential(1f / 60f, 0.35)
    b.decayLinear(1f / 60f, 0.35)
}
showSignals(
    "decayExponential(lambda = 0.35)" to expo.toList(),
    "decayLinear(0.35 per second)" to lin.toList(),
    x = (0 until samples).map { it / 60.0 }
)
```

## 3. 3 — hysteresis, against a memoryless control

<!-- field-box id="dgs_hysteresis" index="2" -->

### Narrative

From `code`

A pair not yet joined must come within rEnter; a pair already joined only has to stay
within rExit. The control arm is rExit == rEnter -- the identical system with the memory
removed and nothing else changed.

```edn [field-box id="dgs_hysteresis" role="properties"]
{"id" "dgs_hysteresis" "index" 2 "name" "3 — hysteresis, against a memoryless control" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — hysteresis, against a memoryless control" "_tombstone" false "frame" #afield/rect [1000.0 60.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dgs_hysteresis_code_.kt" "lines" 70 "characters" 3686 "sha256" "b41885c96d0a7a75336fdd9d8c394dbd1a54f89b7eb0753d4073526ce7050aa7"}]}
```

```kotlin [field-property box="dgs_hysteresis" name="code" path="dgs_hysteresis_code_.kt"]
// A pair not yet joined must come within rEnter; a pair already joined only has to stay
// within rExit. The control arm is rExit == rEnter -- the identical system with the memory
// removed and nothing else changed.
fun walk(rExit: Double): Pair<Boolean, Boolean> {
    val state = DynamicPointState(mutableListOf(Vec3(0f, 0f, 0f), Vec3(0.05f, 0f, 0f)))
    val edges = DynamicEdgeList(2)
    val hash = Vec3SpatialHash(0.2)
    val system = Vec3PersistentProximityHysteresisSystem(
        rEnter = 0.10, rExit = rExit, ttl = 0.2f, reinforce = 4.0, maxWeight = 8.0)
    repeat(30) { stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.2) }
    val formed = edges.get(0, 1) != null
    state.points[1] = Vec3(0.13f, 0f, 0f)
    repeat(60) { stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.2) }
    return formed to (edges.get(0, 1) != null)
}
println("rExit 0.16 (hysteresis):  formed at d=0.05 = %s, survives d=0.13 = %s".format(
    walk(0.16).first, walk(0.16).second))
println("rExit 0.10 (control arm): formed at d=0.05 = %s, survives d=0.13 = %s".format(
    walk(0.10).first, walk(0.10).second))

// On a wobbling cloud the difference is how much WORK the graph does to stay put.
val n = 200
val base = List(n) { i ->
    val a = i * 2.39996322972865332
    val r = 0.45 * Math.sqrt((i + 0.5) / n)
    Vec3((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat(), 0f)
}
// One window, from the cloud at rest, shared by both panels -- otherwise the sparser arm is
// drawn at its own scale and the comparison says nothing.
val window = dynamicGraphWindow(base, padFraction = 0.06)

class Flicker(val appeared: Int, val vanished: Int, val standing: Int, val lines: List<FLine>)

fun flicker(rExit: Double): Flicker {
    val state = DynamicPointState(base.map { Vec3(it) }.toMutableList())
    val edges = DynamicEdgeList(n)
    val hash = Vec3SpatialHash(0.06)
    val system = Vec3PersistentProximityHysteresisSystem(
        rEnter = 0.055, rExit = rExit, ttl = 0.35f, reinforce = 3.0, maxWeight = 3.0)
    var appeared = 0
    var vanished = 0
    var present = HashSet<Pair<Int, Int>>()
    repeat(150) { frame ->
        for (i in 0 until n) {
            val phase = 0.31 * i + 0.16 * frame
            state.points[i] = Vec3(base[i]).add(
                vec(0.012 * Math.cos(phase), 0.012 * Math.sin(1.7 * phase), 0.0))
        }
        stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.9)
        val now = edges.values().map { it.i to it.j }.toHashSet()
        appeared += (now - present).size
        vanished += (present - now).size
        present = now
    }
    return Flicker(appeared, vanished, present.size,
        dynamicGraphFLines(state.points, edges.values(), maxWeight = 3.0, colormap = "magma"))
}
val on = flicker(rExit = 0.075)
val off = flicker(rExit = 0.055)
println("hysteresis on:  ${on.appeared} created, ${on.vanished} lost, ${on.standing} standing after 150 frames")
println("hysteresis off: ${off.appeared} created, ${off.vanished} lost, ${off.standing} standing after 150 frames")
println("...the memoryless arm creates %.2fx as many and loses %.1fx as many".format(
    off.appeared.toDouble() / on.appeared, off.vanished.toDouble() / on.vanished))
// The figure is not allowed to crop its own subject silently.
println("points outside the shared window: ${dynamicGraphOutsideWindow(base, window)}")
showPlaneGrid(
    "rExit 0.075: ${on.appeared} in, ${on.vanished} out" to on.lines,
    "rExit = rEnter: ${off.appeared} in, ${off.vanished} out" to off.lines,
    columns = 2, cellWidth = 460, cellHeight = 460, window = dynamicGraphPlaneWindow(window),
)
```

## 4. 6 — LAUNCH ME: morphogenesis, and its tip-growth-off twin

<!-- field-box id="dgs_morphogenesis" index="3" -->

### Narrative

From `code`

LAUNCH this box, do not just run it.

The full pipeline: diffuse a resource along the edges and climb its gradient, relax the
springs, grow tips where the incident edges pull coherently, merge what has collided,
smooth, jitter. The right-hand panel is the same pipeline with ONE stage removed -- tip
growth -- so the difference between the panels is that stage and nothing else.

```edn [field-box id="dgs_morphogenesis" role="properties"]
{"id" "dgs_morphogenesis" "index" 3 "name" "6 — LAUNCH ME: morphogenesis, and its tip-growth-off twin" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — LAUNCH ME: morphogenesis, and its tip-growth-off twin" "_tombstone" false "frame" #afield/rect [1000.0 300.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dgs_morphogenesis_code_.kt" "lines" 79 "characters" 3684 "sha256" "2589f72a905639ecaa319a49d77ff30616cc9364e53da343032287933936e9e5"}]}
```

```kotlin [field-property box="dgs_morphogenesis" name="code" path="dgs_morphogenesis_code_.kt"]
// LAUNCH this box, do not just run it.
//
// The full pipeline: diffuse a resource along the edges and climb its gradient, relax the
// springs, grow tips where the incident edges pull coherently, merge what has collided,
// smooth, jitter. The right-hand panel is the same pipeline with ONE stage removed -- tip
// growth -- so the difference between the panels is that stage and nothing else.
val seedRing = List(64) { i ->
    val a = 2.0 * Math.PI * i / 64
    Vec3((0.42 * Math.cos(a)).toFloat(), (0.42 * Math.sin(a)).toFloat(), 0f)
}
// Sized for where the structure ENDS UP. Re-fitting per frame would make growth invisible.
val window = doubleArrayOf(-0.95, -0.95, 0.95, 0.95)
val ceiling = 3.0

class Morph(
    val state: DynamicPointState,
    val kinetics: DynamicPointKinetics,
    val edges: DynamicEdgeList,
    val hash: Vec3SpatialHash,
    val pipeline: DynamicProcessPipeline,
    val system: DynamicGraphSystem
)

fun morph(grow: Boolean): Morph {
    val state = DynamicPointState(seedRing.map { Vec3(it) }.toMutableList())
    val kinetics = DynamicPointKinetics(mutableListOf(), mutableListOf(), mutableListOf())
    kinetics.ensureSize(state.pointCount)
    // Seed the resource on a quarter of the ring, so chemotaxis has a gradient to climb.
    for (i in 0 until 16) kinetics.resource[i] = 6.0
    val stages = mutableListOf<DynamicPointProcess>(
        SignalDiffusionAndChemotaxisProcess(diffusionRate = 0.5, chemoGain = 0.35, maxSpeed = 0.30),
        // restLengthScale is length x weight, NOT a length: rest = restLengthScale / weight.
        // 0.075 is a rest length of 0.05 at a typical weight of 1.5.
        SpringMotionProcess(springK = 1.2, damping = 3.0, restLengthScale = 0.075, maxSpeed = 0.30)
    )
    if (grow) stages += DirectedTipGrowthProcess(
        coherenceThreshold = 1.6, spawnDistance = 0.035, localDensityRadius = 0.05,
        localDensityMax = 6, childEdgeWeight = 1.5, childEdgeTtl = 1.5f, maxWeight = ceiling,
        maxPointCount = 900)
    stages += MergeClosePointsProcess(mergeDistance = 0.012, minCombinedWeight = 2.0)
    stages += RelaxToMidpointProcess(gain = 1.5)
    stages += JitterProcess(amplitude = 0.04)
    return Morph(
        state, kinetics, DynamicEdgeList(state.pointCount), Vec3SpatialHash(0.05),
        DynamicProcessPipeline(stages),
        Vec3PersistentProximityHysteresisSystem(
            rEnter = 0.05, rExit = 0.07, ttl = 0.8f, reinforce = 3.0, maxWeight = ceiling)
    )
}

animateEvolution(
    morph(grow = true) to morph(grow = false),
    totalSteps = 560,
    seconds = 10.0,
    columns = 2,
    cellSide = 490,
    framesTarget = 200,
    // Geometric: the 900-point cap is reached at step 79, so a linear schedule would spend
    // seven eighths of the clip on the aftermath. ramp = 25 puts the mid-point at step 93.
    ramp = 25.0,
    step = { (grown, flat) ->
        for (m in listOf(grown, flat)) {
            stepDynamicGraphWithExponentialDecay(
                1f / 60f, m.state, m.edges, m.hash, m.system, 0.6)
            m.pipeline.step(1f / 60f, m.state, m.kinetics, m.edges, m.hash)
        }
        grown to flat
    },
    panels = { (grown, flat), step ->
        listOf(
            "grow ON  s%d  %dpts %de".format(
                step, grown.state.pointCount, grown.edges.values().size) to
                dynamicGraphPanel(grown.state.points, grown.edges.values(), window, 490, 490, ceiling, "magma"),
            "grow OFF  s%d  %dpts %de".format(
                step, flat.state.pointCount, flat.edges.values().size) to
                dynamicGraphPanel(flat.state.points, flat.edges.values(), window, 490, 490, ceiling, "magma")
        )
    }
)
```

## 5. 4 — the weight threshold recovers the co-moving groups

<!-- field-box id="dgs_threshold" index="4" -->

### Narrative

From `code`

A static 240-point background mesh with three rigid 22-point clusters crossing it.
Cluster-internal pairs are adjacent for the whole four seconds; cluster-to-background
pairs only in passing. The minWeight cut has to tell those apart using nothing but
accumulated weight -- and it recovers the groups exactly.

```edn [field-box id="dgs_threshold" role="properties"]
{"id" "dgs_threshold" "index" 4 "name" "4 — the weight threshold recovers the co-moving groups" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — the weight threshold recovers the co-moving groups" "_tombstone" false "frame" #afield/rect [60.0 300.0 470.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dgs_threshold_code_.kt" "lines" 72 "characters" 3352 "sha256" "a6d045efeb3f39fbc214c42c7f87781f9ab24d6107deac592781138fd3a0befe"}]}
```

```kotlin [field-property box="dgs_threshold" name="code" path="dgs_threshold_code_.kt"]
// A static 240-point background mesh with three rigid 22-point clusters crossing it.
// Cluster-internal pairs are adjacent for the whole four seconds; cluster-to-background
// pairs only in passing. The minWeight cut has to tell those apart using nothing but
// accumulated weight -- and it recovers the groups exactly.
fun disc(n: Int, r: Double) = List(n) { i ->
    val a = i * 2.39996322972865332
    val rad = r * Math.sqrt((i + 0.5) / n)
    Vec3((rad * Math.cos(a)).toFloat(), (rad * Math.sin(a)).toFloat(), 0f)
}

val nBg = 240
val perCluster = 22
val n = nBg + 3 * perCluster
val background = disc(nBg, 0.50)
val blobs = List(3) { disc(perCluster, 0.070) }
val starts = listOf(Vec3(-0.44f, -0.20f, 0f), Vec3(-0.30f, 0.40f, 0f), Vec3(0.34f, -0.44f, 0f))
val velocities = listOf(Vec3(0.20f, 0.07f, 0f), Vec3(0.16f, -0.19f, 0f), Vec3(-0.12f, 0.20f, 0f))
// Used ONLY to check the answer, never to build the graph.
val group = IntArray(n) { if (it < nBg) -1 else (it - nBg) / perCluster }

val points = MutableList(n) { Vec3(0f, 0f, 0f) }
for (i in 0 until nBg) points[i] = Vec3(background[i])
val state = DynamicPointState(points)
val edges = DynamicEdgeList(n)
val hash = Vec3SpatialHash(0.055)
val ceiling = 3.0
val system = Vec3PersistentProximityHysteresisSystem(
    rEnter = 0.062, rExit = 0.075, ttl = 0.4f, reinforce = 2.5, maxWeight = ceiling)

repeat(240) { f ->
    val t = f / 60.0
    for (c in 0 until 3) {
        val centre = Vec3(starts[c]).add(Vec3(velocities[c]).mul(t.toFloat()))
        for (k in 0 until perCluster) {
            state.points[nBg + c * perCluster + k] = Vec3(blobs[c][k]).add(centre)
        }
    }
    stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.30)
}
println(dynamicGraphSummary(state, edges))

fun componentSizes(graph: PointGraph): List<Int> {
    val parent = IntArray(graph.pointCount) { it }
    fun find(a: Int): Int {
        var x = a
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
        return x
    }
    for (e in graph.edges) { val ra = find(e.i); val rb = find(e.j); if (ra != rb) parent[ra] = rb }
    val sizes = HashMap<Int, Int>()
    for (v in 0 until graph.pointCount) sizes.merge(find(v), 1) { a, b -> a + b }
    return sizes.values.sortedDescending()
}

// Pinned rather than fitted: the clusters are somewhere else at t = 0 and at t = 4 s, so a
// window derived from the final frame would frame a different picture from the run's.
val window = doubleArrayOf(-0.60, -0.60, 0.60, 0.60)
println("points outside that window: ${dynamicGraphOutsideWindow(state.points, window)}")
val panels = listOf(0.0, 0.5, 1.2, 2.0).map { cut ->
    val graph = edges.toPointGraph(minWeight = cut)
    val groups = componentSizes(graph).filter { it > 1 }
    val cross = graph.edges.count { group[it.i] != group[it.j] }
    println("minWeight %.1f -> %4d edges, %3d crossing a group boundary, components > 1: %s".format(
        cut, graph.edges.size, cross, groups))
    val kept = edges.values().filter { it.weight > cut }
    "w>%.1f  %de  %d groups".format(cut, graph.edges.size, groups.size) to
        dynamicGraphFLines(state.points, kept, maxWeight = ceiling, colormap = "magma")
}
showPlaneGrid(
    *panels.toTypedArray(),
    columns = 2, cellWidth = 430, cellHeight = 430, window = dynamicGraphPlaneWindow(window),
)
```

## 6. 5 — LAUNCH ME: the graph rewiring while nothing moves

<!-- field-box id="dgs_breathing" index="5" -->

### Narrative

From `code`

LAUNCH this box (animate/loop), do not just run it: animateEvolution returns a Frames,
and a Frames that is only built never runs.

The cloud NEVER MOVES. Every edge that appears or disappears is the anisotropic metric
breathing -- an axis that rotates with the clock and a stretch along it between 0.25 and
2.0. The right-hand panel is the control: same k, same cloud, same code path, clock
pinned at zero, so it holds exactly 590 edges for all 420 steps.

```edn [field-box id="dgs_breathing" role="properties"]
{"id" "dgs_breathing" "index" 5 "name" "5 — LAUNCH ME: the graph rewiring while nothing moves" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — LAUNCH ME: the graph rewiring while nothing moves" "_tombstone" false "frame" #afield/rect [530.0 300.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dgs_breathing_code_.kt" "lines" 62 "characters" 2541 "sha256" "835aedb16e88d744b2f6d36ebe2bc88529e953e4e2d01153227fa80bfe9d0eb6"}]}
```

```kotlin [field-property box="dgs_breathing" name="code" path="dgs_breathing_code_.kt"]
// LAUNCH this box (animate/loop), do not just run it: animateEvolution returns a Frames,
// and a Frames that is only built never runs.
//
// The cloud NEVER MOVES. Every edge that appears or disappears is the anisotropic metric
// breathing -- an axis that rotates with the clock and a stretch along it between 0.25 and
// 2.0. The right-hand panel is the control: same k, same cloud, same code path, clock
// pinned at zero, so it holds exactly 590 edges for all 420 steps.
val n = 260
val cloud = List(n) { i ->
    val a = i * 2.39996322972865332
    val r = 0.5 * Math.sqrt((i + 0.5) / n)
    Vec3((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat(), 0f)
}
val window = dynamicGraphWindow(cloud, padFraction = 0.06)
val ceiling = 1.2

class Breath(
    val warped: DynamicPointState,
    val plain: DynamicPointState,
    val warpedEdges: DynamicEdgeList,
    val plainEdges: DynamicEdgeList,
    val hash: Vec3SpatialHash
)

val warpedSystem = Vec3BreathingAnisotropicKnnSystem(
    k = 5, ttl = 0.25f, reinforce = 6.0, maxWeight = ceiling, mutualOnly = true, queryRadius = 0.13)
val plainSystem = Vec3BreathingAnisotropicKnnSystem(
    k = 5, ttl = 0.25f, reinforce = 6.0, maxWeight = ceiling, mutualOnly = true, queryRadius = 0.13)

val seed = Breath(
    DynamicPointState(cloud.map { Vec3(it) }.toMutableList()),
    DynamicPointState(cloud.map { Vec3(it) }.toMutableList()),
    DynamicEdgeList(n), DynamicEdgeList(n), Vec3SpatialHash(0.065)
)

animateEvolution(
    seed,
    totalSteps = 420,
    seconds = 8.0,
    columns = 2,
    cellSide = 460,
    framesTarget = 160,
    step = { s ->
        stepDynamicGraphWithExponentialDecay(
            1f / 60f, s.warped, s.warpedEdges, s.hash, warpedSystem, 2.5)
        // The system advances state.time itself, so pinning it either side of the call is
        // what "the same code with the time-dependence removed" means.
        s.plain.time = 0f
        stepDynamicGraphWithExponentialDecay(
            1f / 60f, s.plain, s.plainEdges, s.hash, plainSystem, 2.5)
        s.plain.time = 0f
        s
    },
    panels = { s, step ->
        listOf(
            "breathing, t=%.2fs, %d edges".format(s.warped.time, s.warpedEdges.values().size) to
                dynamicGraphPanel(s.warped.points, s.warpedEdges.values(), window, 460, 460, ceiling, "magma"),
            "frozen, step %d, %d edges".format(step, s.plainEdges.values().size) to
                dynamicGraphPanel(s.plain.points, s.plainEdges.values(), window, 460, 460, ceiling, "magma")
        )
    }
)
```

