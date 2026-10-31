# Field Document: confluence_grid

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Confluence in 3D: 5 terms x 4 schedulers

<!-- field-box id="confluence_grid" index="0" -->

### Narrative

From `code`

A 5x4 confluence grid in 3D. Row = term, column = scheduler, all twenty reducing together.

Each row reduces one lambda term under four activation orders. The final report checks
readback against an independent normal-order evaluator and compares positioned graphs.
Confluence alone does not certify a layout; reaction counts and geometry are measured below.

The picture has to get two things right before any of that is visible in it.

1. The pose uses three rounds of type/port-ordered WL refinement and BFS layers.
Equal labels and hash collisions can leave ties; this is a layout heuristic, not a
canonical graph labelling algorithm. The final report compares positioned nodes AND
port-labelled edges, so equal coordinate sets alone cannot claim equal drawings.
2. On completion, freeze physics and ease from that fixed snapshot to the target.
The final landing frame snaps exactly and clears velocity.


Animation controls. Rerun setup after changing these values or the term/scheduler rows.

```edn [field-box id="confluence_grid" role="properties"]
{"id" "confluence_grid" "index" 0 "name" "Confluence in 3D: 5 terms x 4 schedulers" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Confluence in 3D: 5 terms x 4 schedulers" "_tombstone" false "frame" #afield/rect [40.0 40.0 620.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "confluence_grid_code_.kt" "lines" 364 "characters" 14477 "sha256" "d945649897b521c8457da9ed1ee63d8e9a0de3f232e9f68f6258f2b50c47edaf"}]}
```

```kotlin [field-property box="confluence_grid" name="code" path="confluence_grid_code_.kt"]
/*
A 5x4 confluence grid in 3D. Row = term, column = scheduler, all twenty reducing together.

Each row reduces one lambda term under four activation orders. The final report checks
readback against an independent normal-order evaluator and compares positioned graphs.
Confluence alone does not certify a layout; reaction counts and geometry are measured below.

The picture has to get two things right before any of that is visible in it.

1. The pose uses three rounds of type/port-ordered WL refinement and BFS layers.
   Equal labels and hash collisions can leave ties; this is a layout heuristic, not a
   canonical graph labelling algorithm. The final report compares positioned nodes AND
   port-labelled edges, so equal coordinate sets alone cannot claim equal drawings.
2. On completion, freeze physics and ease from that fixed snapshot to the target.
   The final landing frame snaps exactly and clears velocity.
*/

// Animation controls. Rerun setup after changing these values or the term/scheduler rows.
val stepEvery = 7
val displayEvery = 2
val frameMs = 17L
val landingFrames = 40
val maxFrames = 2600

val terms = [
    "succ two" to "(\\n f x. f (n f x)) (\\f x. f (f x))",
    "mul 2 3" to "(\\m n f. m (n f)) (\\f x. f (f x)) (\\f x. f (f (f x)))",
    "pred 3" to "(\\n f x. n (\\g h. h (g f)) (\\u. x) (\\u. u)) (\\f x. f (f (f x)))",
    "2^3" to "(\\f x. f (f (f x))) (\\f x. f (f x))",
    "3^3" to "(\\f x. f (f (f x))) (\\f x. f (f (f x)))",
]
val orders = [
    "FIRST_FOUND" to ActivationOrder.FIRST_FOUND,
    "LEFTMOST" to ActivationOrder.LEFTMOST_BY_ID,
    "RIGHTMOST" to ActivationOrder.RIGHTMOST_BY_ID,
    "ENERGY" to ActivationOrder.HIGHEST_PAIR_ENERGY,
]

/*
Type -> colour, hand-converted from interactionnets.kt's own baseHueDegrees (APP 208, LAM 280,
DUP 28, ERASE 8, ROOT 160, VAR 190, CONST 48) so the 3D view and the 2D one agree. Note ERASE
and DUP are only 20 degrees apart there and VAR and APP 18, which is not eye-discriminable, so
these are pushed apart deliberately. Alpha is low, so a cell reads as a haze, not as discs.
*/
val rgbOf = mapOf(
    "APP" to [0.38, 0.62, 0.95],
    "LAM" to [0.72, 0.45, 0.95],
    "DUP" to [0.98, 0.70, 0.32],
    "ERASE" to [0.95, 0.32, 0.30],
    "ROOT" to [0.30, 0.92, 0.66],
    "VAR" to [0.42, 0.88, 0.95],
    "CONST" to [0.95, 0.90, 0.40],
)

/** Three rounds of structural refinement; equal labels and hash collisions can leave ties. */
fun wlLabels(net: Net): Map<String, String> {
    var lab = net.nodes.associate { it.id to it.type.name }
    repeat(3) {
        lab =
            net.nodes.associate { n ->
                val ns = n.ports.joinToString(",") { p -> lab[net.connected(p)?.node?.id] ?: "-" }
                n.id to (lab.getValue(n.id) + "[" + ns + "]").hashCode().toString(36)
            }
    }
    return lab
}

/**
 * A heuristic pose: BFS depth from ROOT for the layer, and within a layer an ordering by WL label. Each layer is a ring
 * in x/z so the thing has volume to orbit.
 */
fun targetPose(net: Net): Map<String, Vec3> {
    val lab = wlLabels(net)
    val root =
        net.nodes.minByOrNull { (if (it.type.name == "ROOT") "0" else "1") + lab.getValue(it.id) } ?: return emptyMap()
    val depth = HashMap<String, Int>()
    val queue = ArrayDeque<Node>()
    depth[root.id] = 0
    queue += root
    while (queue.isNotEmpty()) {
        val n = queue.removeFirst()
        n.ports
            .mapNotNull { net.connected(it)?.node }
            .forEach { o ->
                if (!depth.containsKey(o.id)) {
                    depth[o.id] = depth.getValue(n.id) + 1
                    queue += o
                }
            }
    }
    val unreachedDepth = (depth.values.maxOrNull() ?: 0) + 1
    // Sort by WL label; unresolved ties retain their current order.
    net.nodes
        .filter { !depth.containsKey(it.id) }
        .sortedBy { lab.getValue(it.id) }
        .forEach { depth[it.id] = unreachedDepth }
    val byDepth = net.nodes.groupBy { depth.getValue(it.id) }
    val maxD = byDepth.keys.max().coerceAtLeast(1)
    val out = HashMap<String, Vec3>()
    byDepth.toSortedMap().forEach { (d, ns) ->
        val ring = ns.sortedBy { lab.getValue(it.id) + it.type.name }
        val radius = if (ring.size <= 1) 0.0 else 0.30 + 0.085 * ring.size
        ring.forEachIndexed { i, n ->
            val a = 2.0 * Math.PI * i / ring.size + d * 0.7
            out[n.id] = vec(radius * Math.cos(a), 1.0 - 2.0 * d / maxD, radius * Math.sin(a))
        }
    }
    return out
}

class Cell(val row: Int, val col: Int, val src: String, order: ActivationOrder) {
    val compiled = LambdaTerms.compile(LambdaTerms.parse(src))
    val reducer = Reducer(Lambda.rules(), order = order, relaxIterations = 0)
    var pos = HashMap<String, Vec3>()
    var vel = HashMap<String, Vec3>()
    var steps = 0
    var peak = compiled.net.nodes.size
    var done = false
    var landing = 0
    var pose: Map<String, Vec3> = emptyMap()
    var landingFrom: Map<String, Vec3> = emptyMap()

    fun seed() {
        val r = java.util.Random((row * 31 + col * 7).toLong())
        compiled.net.nodes.forEach {
            pos[it.id] = vec(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5)
            vel[it.id] = vec(0.0, 0.0, 0.0)
        }
    }

    fun step() {
        if (done) return
        if (reducer.stepDetailed(compiled.net) == null) {
            done = true
            pose = targetPose(compiled.net)
            landingFrom = pos.mapValues { (_, p) -> vec(p.x, p.y, p.z) }
            return
        }
        steps++
        if (compiled.net.nodes.size > peak) peak = compiled.net.nodes.size
        // newborn nodes start near the collision, i.e. near their neighbours' mean
        compiled.net.nodes
            .filter { !pos.containsKey(it.id) }
            .forEach { n ->
                val near = n.ports.mapNotNull { p -> pos[compiled.net.connected(p)?.node?.id] }
                val c =
                    if (near.isEmpty()) vec(0.0, 0.0, 0.0)
                    else
                        vec(
                            near.sumOf { it.x.toDouble() } / near.size,
                            near.sumOf { it.y.toDouble() } / near.size,
                            near.sumOf { it.z.toDouble() } / near.size,
                        )
                pos[n.id] = vec(c.x + 0.05, c.y + 0.05, c.z + 0.05)
                vel[n.id] = vec(0.0, 0.0, 0.0)
            }
    }

    /** 3D springs and repulsion on our own positions; Net.relax is 2D and we never call it. */
    fun physics() {
        if (done) return
        val ns = compiled.net.nodes.toList()
        val force = HashMap<String, Vec3>()
        ns.forEach { force[it.id] = vec(0.0, 0.0, 0.0) }
        for (i in ns.indices) for (j in i + 1 until ns.size) {
            val a = pos[ns[i].id] ?: continue
            val b = pos[ns[j].id] ?: continue
            var dx = a.x - b.x
            var dy = a.y - b.y
            var dz = a.z - b.z
            var d2 = (dx * dx + dy * dy + dz * dz).toDouble()
            if (d2 < 1e-6) {
                dx = 0.01f
                dy = 0.007f
                dz = 0.013f
                d2 = 1e-4
            }
            val m = 0.010 / d2
            val d = Math.sqrt(d2)
            val fa = force.getValue(ns[i].id)
            val fb = force.getValue(ns[j].id)
            force[ns[i].id] = vec(fa.x + dx / d * m, fa.y + dy / d * m, fa.z + dz / d * m)
            force[ns[j].id] = vec(fb.x - dx / d * m, fb.y - dy / d * m, fb.z - dz / d * m)
        }
        compiled.net.edges.forEach { e ->
            val a = pos[e.a.node.id] ?: return@forEach
            val b = pos[e.b.node.id] ?: return@forEach
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dz = b.z - a.z
            val d = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).coerceAtLeast(1e-4)
            val m = (d - 0.34) * 0.10
            val fa = force.getValue(e.a.node.id)
            val fb = force.getValue(e.b.node.id)
            force[e.a.node.id] = vec(fa.x + dx / d * m, fa.y + dy / d * m, fa.z + dz / d * m)
            force[e.b.node.id] = vec(fb.x - dx / d * m, fb.y - dy / d * m, fb.z - dz / d * m)
        }
        ns.forEach { n ->
            val p = pos[n.id] ?: return@forEach
            val v = vel.getValue(n.id)
            val f = force.getValue(n.id)
            val nv = vec((v.x + f.x) * 0.86, (v.y + f.y) * 0.86, (v.z + f.z) * 0.86)
            vel[n.id] = nv
            pos[n.id] = vec(p.x + nv.x, p.y + nv.y, p.z + nv.z)
        }
    }

    /** Ease from the frozen last physics frame, then land exactly on the target. */
    fun land() {
        if (!done || landing >= landingFrames) return
        landing++
        val t = landing.toDouble() / landingFrames
        val eased = t * t * (3.0 - 2.0 * t)
        pose.forEach { (id, target) ->
            val from = landingFrom[id] ?: target
            pos[id] =
                if (landing == landingFrames) vec(target.x, target.y, target.z)
                else
                    vec(
                        from.x + (target.x - from.x) * eased,
                        from.y + (target.y - from.y) * eased,
                        from.z + (target.z - from.z) * eased,
                    )
            vel[id] = vec(0.0, 0.0, 0.0)
        }
    }

    /** Actual local coordinates plus node types and port-labelled edges, without node IDs. */
    fun positionedGraph(): List<String> {
        fun nodeKey(node: Node) = node.type.name + "@" + pos.getValue(node.id).toString()
        val nodes = compiled.net.nodes.map { "node:" + nodeKey(it) }
        val edges =
            compiled.net.edges.map { edge ->
                val endpoints = [
                    nodeKey(edge.a.node) + ":" + edge.a.index,
                    nodeKey(edge.b.node) + ":" + edge.b.index,
                ].sorted()
                "edge:" + endpoints.joinToString("--")
            }
        return (nodes + edges).sorted()
    }

    fun originX() = col * 2.9

    fun originY() = -row * 2.6

    fun at(id: String): Vec3? = pos[id]?.let { vec(it.x + originX(), it.y + originY(), it.z) }
}

val cells = terms.flatMapIndexed { r, (_, src) -> orders.mapIndexed { c, (_, o) -> Cell(r, c, src, o) } }
cells.forEach {
    it.seed()
    repeat(60) { _ -> it.physics() }
}

fun geometry(): List<FLine> {
    val out = mutableListOf<FLine>()
    // edges: one thin, translucent FLine each
    cells.forEach { cell ->
        cell.compiled.net.edges.forEach { e ->
            val a = cell.at(e.a.node.id)
            val b = cell.at(e.b.node.id)
            if (a != null && b != null) {
                val f = FLine().moveTo(a).lineTo(b)
                // No thickness to set: `thicken` is a Java2D Stroke for the 2D path, and the
                // Three.js viewer draws 1px lines regardless. Thin is free; alpha is the dial.
                f.color = vec(0.72, 0.78, 0.86, if (cell.done) 0.20 else 0.13)
                out += f
            }
        }
    }
    // points: one pointed FLine per agent type, so colour is per type and the count stays low
    rgbOf.forEach { (type, rgb) ->
        val f = FLine()
        var any = false
        cells.forEach { cell ->
            cell.compiled.net.nodes
                .filter { it.type.name == type }
                .forEach { n ->
                    cell.at(n.id)?.let {
                        f.moveTo(it)
                        any = true
                    }
                }
        }
        if (any) {
            f.color = vec(rgb[0], rgb[1], rgb[2], 0.55)
            f.pointed = true
            f.pointSize = 0.9
            out += f
        }
    }
    // a fixed cage, so autoFrame does not rescale the view every frame
    val cage =
        FLine()
            .moveTo(vec(-1.6, -(terms.size - 1) * 2.6 - 1.6, -1.6))
            .lineTo(vec((orders.size - 1) * 2.9 + 1.6, 1.6, 1.6))
    cage.color = vec(0.0, 0.0, 0.0, 0.0)
    out += cage
    return out
}

println("rows: " + terms.joinToString(", ") { it.first })
println("cols: " + orders.joinToString(", ") { it.first })

/*
The animation runs as a frames { } loop: launch the box and the Runloop steps the grid
one frame per tick, so nothing blocks and no native Window or Renderer is needed. The
wall-clock gate keeps the tempo the same whether the Runloop ticks at 60 Hz or faster.
The Three.js viewer keeps its iframe while new geometry streams through its socket.
*/
val panelW = 1460
val panelH = 840
three(channel = "confluence3d", name = "confluence", width = panelW, height = panelH) {
    lines(geometry())
}

frames {
    var frame = 0
    var next = System.nanoTime()
    while (frame() && frame < maxFrames && !cells.all { it.done && it.landing >= landingFrames }) {
        if (System.nanoTime() < next) continue
        next += frameMs * 1_000_000
        frame++
        if (frame % stepEvery == 0)
            cells.forEach { it.step() } // One reaction per beat, independently of the display cadence.
        cells.forEach {
            it.physics()
            it.land()
        }
        if (frame % displayEvery == 0) {
            three(channel = "confluence3d", name = "confluence", width = panelW, height = panelH) {
                lines(geometry())
            }
        }
    }
    three(channel = "confluence3d", name = "confluence", width = panelW, height = panelH) {
        lines(geometry())
    }
    println("frames $frame")
    terms.forEachIndexed { r, (label, src2) ->
        val row = cells.filter { it.row == r }
        val keys = row.map { LambdaTerms.deBruijnKey(LambdaTerms.readback(it.compiled)) }.toSet()
        val oracle = LambdaTerms.deBruijnKey(LambdaTerms.referenceNormalOrder(LambdaTerms.parse(src2)))
        val placed = row.map { it.positionedGraph() }.toSet()
        val landed = row.all { c ->
            c.done &&
                c.landing == landingFrames &&
                c.pose.all { (id, target) -> c.pos[id] == target && c.vel[id] == vec(0.0, 0.0, 0.0) }
        }
        println(
            label.padEnd(10) +
                " steps " +
                row.joinToString("/") { it.steps.toString() }.padEnd(16) +
                " one answer: " +
                (keys.size == 1 && keys.first() == oracle) +
                "   landed exactly: " +
                landed +
                "   matching positioned graph: " +
                (landed && placed.size == 1),
        )
    }
    println("GRID3D DONE")
}
```

