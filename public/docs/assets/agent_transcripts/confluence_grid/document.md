# Field Document: confluence_grid

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Confluence in 3D: 5 terms x 4 schedulers

<!-- field-box id="confluence_grid" index="0" -->

```edn [field-box id="confluence_grid" role="properties"]
{"id" "confluence_grid" "index" 0 "name" "Confluence in 3D: 5 terms x 4 schedulers" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Confluence in 3D: 5 terms x 4 schedulers" "_tombstone" false "frame" #afield/rect [40.0 40.0 620.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "confluence_grid_code_.kt" "lines" 282 "characters" 13530 "sha256" "cc0db37a515c346b1c514b24a619e67077488506d675720af71bc831e74b3ad4"}]}
```

```kotlin [field-property box="confluence_grid" name="code" path="confluence_grid_code_.kt"]
import afield.graphics.FLine

// A 5x4 confluence grid in 3D. Row = term, column = scheduler, all twenty reducing together.
//
// Interaction nets are STRONGLY confluent, so every cell in a row takes the same NUMBER of
// reactions (measured: 3/3/3/3, 26/26/26/26, 36/36/36/36) and ends on an identical graph
// (measured: same per-type census; 3^3 -> {APP=3, DUP=10, LAM=2, ROOT=1} four times over).
// What differs is WHICH reaction fires at each beat: 2 to 13 pairs are available, mean 8.5 on
// 3^4. So the columns take visibly different routes through the same number of steps.
//
// Two things this has to get right to say that honestly.
//
// 1. The layout must not invent difference. `Net.relax` is 2D and path-dependent, so identical
//    graphs draw four different ways and the picture claims the opposite of the theorem. The
//    pose here is built from a Weisfeiler-Leman refinement — a node's label is its type folded
//    with its neighbours' labels, in PORT ORDER, three rounds — so the ordering is a structural
//    invariant. Nothing reads an id or an insertion order, which is what leaked last time.
// 2. The landing must be legible. While a cell reduces it runs its own 3D physics; when it
//    reaches normal form it interpolates over 40 frames onto the canonical pose. Four dances,
//    one final shape.

val terms = listOf(
    "succ two" to "(\\n f x. f (n f x)) (\\f x. f (f x))",
    "mul 2 3"  to "(\\m n f. m (n f)) (\\f x. f (f x)) (\\f x. f (f (f x)))",
    "pred 3"   to "(\\n f x. n (\\g h. h (g f)) (\\u. x) (\\u. u)) (\\f x. f (f (f x)))",
    "2^3"      to "(\\f x. f (f (f x))) (\\f x. f (f x))",
    "3^3"      to "(\\f x. f (f (f x))) (\\f x. f (f (f x)))"
)
val orders = listOf(
    "FIRST_FOUND" to ActivationOrder.FIRST_FOUND,
    "LEFTMOST"    to ActivationOrder.LEFTMOST_BY_ID,
    "RIGHTMOST"   to ActivationOrder.RIGHTMOST_BY_ID,
    "ENERGY"      to ActivationOrder.HIGHEST_PAIR_ENERGY
)

// Type -> colour, hand-converted from interactionnets.kt's own baseHueDegrees (APP 208, LAM 280,
// DUP 28, ERASE 8, ROOT 160, VAR 190, CONST 48) so the 3D view and the 2D one agree. Note ERASE
// and DUP are only 20 degrees apart there and VAR and APP 18, which is not eye-discriminable, so
// these are pushed apart deliberately. Alpha is low: this should read as a haze, not as discs.
val rgbOf = mapOf(
    "APP" to listOf(0.38, 0.62, 0.95),
    "LAM" to listOf(0.72, 0.45, 0.95),
    "DUP" to listOf(0.98, 0.70, 0.32),
    "ERASE" to listOf(0.95, 0.32, 0.30),
    "ROOT" to listOf(0.30, 0.92, 0.66),
    "VAR" to listOf(0.42, 0.88, 0.95),
    "CONST" to listOf(0.95, 0.90, 0.40)
)

/** Three rounds of Weisfeiler-Leman refinement. Structural, so isomorphic nets agree. */
fun wlLabels(net: Net): Map<String, String> {
    var lab = net.nodes.associate { it.id to it.type.name }
    repeat(3) {
        lab = net.nodes.associate { n ->
            val ns = n.ports.joinToString(",") { p -> lab[net.connected(p)?.node?.id] ?: "-" }
            n.id to (lab.getValue(n.id) + "[" + ns + "]").hashCode().toString(36)
        }
    }
    return lab
}

/**
 * A pose determined by topology alone: BFS depth from ROOT for the layer, and within a layer an
 * ordering by WL label. Each layer is a ring in x/z so the thing has volume to orbit.
 */
fun canonicalPose(net: Net): Map<String, Vec3> {
    val lab = wlLabels(net)
    val root = net.nodes.minByOrNull { (if (it.type.name == "ROOT") "0" else "1") + lab.getValue(it.id) }
        ?: return emptyMap()
    val depth = HashMap<String, Int>()
    val queue = ArrayDeque<Node>()
    depth[root.id] = 0; queue += root
    while (queue.isNotEmpty()) {
        val n = queue.removeFirst()
        n.ports.mapNotNull { net.connected(it)?.node }.forEach { o ->
            if (!depth.containsKey(o.id)) { depth[o.id] = depth.getValue(n.id) + 1; queue += o }
        }
    }
    val unreachedDepth = (depth.values.maxOrNull() ?: 0) + 1
    // Unreached nodes are ordered by WL label, never by insertion order.
    net.nodes.filter { !depth.containsKey(it.id) }
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
            done = true; pose = canonicalPose(compiled.net); return
        }
        steps++
        if (compiled.net.nodes.size > peak) peak = compiled.net.nodes.size
        // newborn nodes start near the collision, i.e. near their neighbours' mean
        compiled.net.nodes.filter { !pos.containsKey(it.id) }.forEach { n ->
            val near = n.ports.mapNotNull { p -> pos[compiled.net.connected(p)?.node?.id] }
            val c = if (near.isEmpty()) vec(0.0, 0.0, 0.0) else
                vec(near.sumOf { it.x.toDouble() } / near.size,
                    near.sumOf { it.y.toDouble() } / near.size,
                    near.sumOf { it.z.toDouble() } / near.size)
            pos[n.id] = vec(c.x + 0.05, c.y + 0.05, c.z + 0.05)
            vel[n.id] = vec(0.0, 0.0, 0.0)
        }
    }

    /** 3D springs and repulsion on our own positions; Net.relax is 2D and we never call it. */
    fun physics() {
        val ns = compiled.net.nodes.toList()
        val force = HashMap<String, Vec3>()
        ns.forEach { force[it.id] = vec(0.0, 0.0, 0.0) }
        for (i in ns.indices) for (j in i + 1 until ns.size) {
            val a = pos[ns[i].id] ?: continue; val b = pos[ns[j].id] ?: continue
            var dx = a.x - b.x; var dy = a.y - b.y; var dz = a.z - b.z
            var d2 = (dx * dx + dy * dy + dz * dz).toDouble()
            if (d2 < 1e-6) { dx = 0.01f; dy = 0.007f; dz = 0.013f; d2 = 1e-4 }
            val m = 0.010 / d2
            val d = Math.sqrt(d2)
            val fa = force.getValue(ns[i].id); val fb = force.getValue(ns[j].id)
            force[ns[i].id] = vec(fa.x + dx / d * m, fa.y + dy / d * m, fa.z + dz / d * m)
            force[ns[j].id] = vec(fb.x - dx / d * m, fb.y - dy / d * m, fb.z - dz / d * m)
        }
        compiled.net.edges.forEach { e ->
            val a = pos[e.a.node.id] ?: return@forEach; val b = pos[e.b.node.id] ?: return@forEach
            val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
            val d = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).coerceAtLeast(1e-4)
            val m = (d - 0.34) * 0.10
            val fa = force.getValue(e.a.node.id); val fb = force.getValue(e.b.node.id)
            force[e.a.node.id] = vec(fa.x + dx / d * m, fa.y + dy / d * m, fa.z + dz / d * m)
            force[e.b.node.id] = vec(fb.x - dx / d * m, fb.y - dy / d * m, fb.z - dz / d * m)
        }
        ns.forEach { n ->
            val p = pos[n.id] ?: return@forEach
            val v = vel.getValue(n.id); val f = force.getValue(n.id)
            val nv = vec((v.x + f.x) * 0.86, (v.y + f.y) * 0.86, (v.z + f.z) * 0.86)
            vel[n.id] = nv
            pos[n.id] = vec(p.x + nv.x, p.y + nv.y, p.z + nv.z)
        }
    }

    /** Once landed, ease the physics positions onto the canonical pose. */
    fun land() {
        if (!done || landing >= 40) return
        landing++
        val t = landing / 40.0
        pose.forEach { (id, target) ->
            val p = pos[id] ?: target
            pos[id] = vec(p.x + (target.x - p.x) * t * 0.35,
                          p.y + (target.y - p.y) * t * 0.35,
                          p.z + (target.z - p.z) * t * 0.35)
        }
    }

    fun originX() = col * 2.9
    fun originY() = -row * 2.6
    fun at(id: String): Vec3? = pos[id]?.let { vec(it.x + originX(), it.y + originY(), it.z) }
}

val cells = terms.flatMapIndexed { r, (_, src) -> orders.mapIndexed { c, (_, o) -> Cell(r, c, src, o) } }
cells.forEach { it.seed(); repeat(60) { _ -> it.physics() } }

fun geometry(): List<FLine> {
    val out = mutableListOf<FLine>()
    // edges: one thin, translucent FLine each
    cells.forEach { cell ->
        cell.compiled.net.edges.forEach { e ->
            val a = cell.at(e.a.node.id); val b = cell.at(e.b.node.id)
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
            cell.compiled.net.nodes.filter { it.type.name == type }.forEach { n ->
                cell.at(n.id)?.let { f.moveTo(it); any = true }
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
    val cage = FLine()
        .moveTo(vec(-1.6, -(terms.size - 1) * 2.6 - 1.6, -1.6))
        .lineTo(vec((orders.size - 1) * 2.9 + 1.6, 1.6, 1.6))
    cage.color = vec(0.0, 0.0, 0.0, 0.0)
    out += cage
    return out
}

println("rows: " + terms.joinToString(", ") { it.first })
println("cols: " + orders.joinToString(", ") { it.first })

// The driver is a sleep loop, NOT `frames { }`. Measured 2026-09-18: a frames loop in a box
// with no Window/Renderer gets ZERO ticks in 60 s, because the frame clock IS the render loop.
// Nothing here needs a renderer -- the 3D lives in the Three.js viewer in this box's output --
// so the box drives its own clock and blocks its own thread, which is what a running box is.
// Re-calling the viewer helper emits a byte-identical iframe string, so it does not remount;
// the geometry streams over the socket the first call opened.
// Sizing: there is NO box-side lever, measured rather than assumed. In the live window
// (page-eval on getBoundingClientRect) the panel sits at 480x174 -- exactly `DEFAULT_W = 480` and
// `DEFAULT_H = 150` plus the header -- while `dataset.afieldIntrinsicWidth/Height` correctly reads
// 1460/840. So the viewer does publish `afield-iframe-size`, the listener at
// `outputareas2.js:560` does fire `markContentChanged`, and the panel still never grows. Four
// candidate fixes were each killed by that same measurement: `threeLines(height = ...)`,
// `three(width = ...)` in pixels, a 1480x880 box, and a fresh channel name (so it is not a
// persisted size either). The remaining fix is in the output-area sizing pass, which is shared,
// load-bearing and pinned by `tools/run_ui_regressions.py --id h2-panel-sizing`, so it is not
// something to change from in here. Drag the panel once and the size sticks.
val panelW = 1460
val panelH = 840
three(channel = "confluence3d", name = "confluence", width = panelW, height = panelH) {
    lines(geometry())
}
Thread.sleep(300)

var frame = 0
while (frame < 2600 && !cells.all { it.done && it.landing >= 40 }) {
    frame++
    if (frame % 7 == 0) cells.forEach { it.step() }   // one reaction per 7 frames: ~12s for the slowest row
    cells.forEach { it.physics(); it.land() }
    if (frame % 2 == 0) {
        three(channel = "confluence3d", name = "confluence", width = panelW, height = panelH) {
                    lines(geometry())
        }
        Thread.sleep(34)
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
    val poses = row.map { c -> c.pose.values.map { v -> "%.3f,%.3f,%.3f".format(v.x, v.y, v.z) }.sorted() }.toSet()
    println(label.padEnd(10) + " steps " + row.joinToString("/") { it.steps.toString() }.padEnd(16) +
        " one answer: " + (keys.size == 1 && keys.first() == oracle) +
        "   identical pose: " + (poses.size == 1))
}
println("GRID3D DONE")
```

