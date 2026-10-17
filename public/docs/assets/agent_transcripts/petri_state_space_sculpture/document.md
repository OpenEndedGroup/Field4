# Field Document: petri_state_space_sculpture

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. path tracing renderer

<!-- field-box id="chain_renderer" index="0" -->

```edn [field-box id="chain_renderer" role="properties"]
{"id" "chain_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["petri_sculpture_trace"] "properties" {"children" #{#afield/box "petri_sculpture_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chain_renderer_code_.kt" "lines" 9 "characters" 238 "sha256" "a638c9dcc41817acc78a680363fba75a1c79e62b490a665bfc9e3f7a409cfbd8"}]}
```

```kotlin [field-property box="chain_renderer" name="code" path="chain_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · a deadlock, sculpted and traced")
    Renderer(window)
}
start(renderer::run)
```

## 2. a deadlock, sculpted and traced

<!-- field-box id="petri_sculpture_trace" index="1" -->

```edn [field-box id="petri_sculpture_trace" role="properties"]
{"id" "petri_sculpture_trace" "index" 1 "name" "a deadlock, sculpted and traced" "parents" ["root" "chain_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "chain_renderer"} "name" "a deadlock, sculpted and traced" "_tombstone" false "frame" #afield/rect [320.0 140.0 300.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_sculpture_trace_code_.kt" "lines" 172 "characters" 7328 "sha256" "e9bf9dfb2885e660d207f88e852c43ecd45131f9f7f459dc2ffcc8f69211fae9"}]}
```

```kotlin [field-property box="petri_sculpture_trace" name="code" path="petri_sculpture_trace_code_.kt"]
import afield.graphics.scene.fabricated.PathTraceEnvironment
import afield.graphics.scene.fabricated.PathTraceMaterial
import afield.graphics.scene.fabricated.PathTraceScene
import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.graphics.scene.fabricated.importTriMesh
import afield.graphics.vulkan.util.Camera
import afield.simulation.petri.*
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

prop<PathTracingRenderer>(tracer)

// ---- the net: five philosophers, everyone reaching left first ------------------
fun table(seats: Int) = petri {
    for (i in 0 until seats) place("fork.$i", tokens = 1)
    for (i in 0 until seats) {
        val left = i
        val right = (i + 1) % seats
        t("grab.$i") { from("fork.$left"); to("holding.$i") }
        t("eat.$i") { from("holding.$i"); from("fork.$right"); to("eating.$i") }
        t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
    }
}
val seats = 5
val full = table(seats).stateSpace()
val reduced = table(seats).stateSpace(reduction = PetriReduction.STUBBORN)

// ---- layout: depth is height, the marking decides where on the floor plan ------
// Each philosopher has a seat on a circle. Holding one fork pulls a marking halfway
// towards that seat, eating pulls it all the way, and the pulls add.
fun seat(i: Int) = Vec3(cos(2 * PI * i / seats).toFloat(), 0f, sin(2 * PI * i / seats).toFloat())
val layer = 0.62f

fun layout(graph: PetriStateGraph, offsetX: Float): List<Vec3> {
    val raw = graph.states.indices.map { id ->
        val m = graph.states[id]
        val p = Vec3(offsetX, -layer * graph.depth(id), 0f)
        for (i in 0 until seats) {
            val pull = 0.5f * m.count("holding.$i") + 1.0f * m.count("eating.$i")
            p.add(Vec3(seat(i)).mul(pull))
        }
        p
    }
    // Markings that land on the same spot (the pulls can cancel) are fanned out on a
    // small ring, so every marking stays its own bead.
    val placed = raw.map { Vec3(it) }
    raw.indices.groupBy { "%.3f %.3f %.3f".format(raw[it].x, raw[it].y, raw[it].z) }
        .values.filter { it.size > 1 }
        .forEach { group ->
            group.forEachIndexed { k, id ->
                val a = 2 * PI * k / group.size + 0.4
                placed[id].add(0.16f * cos(a).toFloat(), 0f, 0.16f * sin(a).toFloat())
            }
        }
    return placed
}

// The route to the deadlock as edges of the graph: fire the trace's transitions in turn.
fun traceEdges(graph: PetriStateGraph): Set<PetriStateEdge> {
    val trace = graph.deadlockTrace() ?: return emptySet()
    var at = 0
    return trace.transitions.mapTo(LinkedHashSet()) { name ->
        graph.edges.first { it.from == at && it.transition == name }.also { at = it.to }
    }
}

// A firing is a gentle arc rather than a straight rod: it bows away from the axis of its
// sculpture, which keeps the many firings between two layers from lying on top of each other.
fun arc(a: Vec3, b: Vec3, axis: Vec3): List<Vec3> {
    val mid = Vec3(a).add(b).mul(0.5f)
    val out = Vec3(mid.x - axis.x, 0f, mid.z - axis.z)
    val bow = if (out.length() < 1e-3f) Vec3(0.2f, 0f, 0f) else out.normalize().mul(0.22f)
    return (0..16).map { s ->
        val t = s / 16f
        val lift = 4f * t * (1f - t)
        Vec3(a).mul(1f - t).add(Vec3(b).mul(t)).add(Vec3(bow).mul(lift))
    }
}

// ---- the scene ------------------------------------------------------------------
val scene = PathTraceScene()
val grabbing = scene.material(PathTraceMaterial.metal(0.35f, 0.55f, 1.0f, 0.18f))
val eating = scene.material(PathTraceMaterial.metal(0.25f, 0.95f, 0.75f, 0.18f))
val puttingDown = scene.material(PathTraceMaterial.metal(0.85f, 0.45f, 1.0f, 0.18f))
val bead = scene.material(PathTraceMaterial.glass(ior = 1.5f))
val start = scene.material(PathTraceMaterial.metal(1.0f, 0.95f, 0.85f, 0.05f))
val route = scene.material(PathTraceMaterial.emissive(4.5f, 1.5f, 0.3f))
val stuck = scene.material(PathTraceMaterial.emissive(16.0f, 1.2f, 0.4f))
val floor = scene.material(PathTraceMaterial.plastic(0.05f, 0.05f, 0.06f, 0.12f))

val lo = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
val hi = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

fun sculpt(graph: PetriStateGraph, offsetX: Float) {
    val at = layout(graph, offsetX)
    val lit = traceEdges(graph)
    val dead = graph.deadMarkings.toSet()
    val axis = Vec3(offsetX, 0f, 0f)
    for (edge in graph.edges) {
        val material = when {
            edge in lit -> route
            edge.transition.startsWith("grab") -> grabbing
            edge.transition.startsWith("eat") -> eating
            else -> puttingDown
        }
        val radius = if (edge in lit) 0.026 else 0.011
        val added = scene.importTriMesh(
            tubeMesh(arc(at[edge.from], at[edge.to], axis), radius = radius, sides = 10),
            material, warning = {},
        )
        if (added.triangleCount > 0) { lo.min(added.boundsMin); hi.max(added.boundsMax) }
    }
    at.forEachIndexed { id, p ->
        when {
            id in dead -> scene.sphere(p, 0.085f, stuck)
            id == 0 -> scene.sphere(p, 0.085f, start)
            else -> scene.sphere(p, 0.045f, bead, segments = 24, rings = 12)
        }
    }
}

sculpt(full, offsetX = 0f)
sculpt(reduced, offsetX = 3.4f)

println("full graph: ${full.stateCount} markings, ${full.edgeCount} firings, " +
    "deadlock after ${full.deadlockTrace()!!.length}: ${full.deadlockTrace()!!.transitions}")
println("stubborn-set graph: ${reduced.stateCount} markings, ${reduced.edgeCount} firings, " +
    "deadlock after ${reduced.deadlockTrace()!!.length}: ${reduced.deadlockTrace()!!.transitions}")

// ---- floor, camera, tracer -----------------------------------------------------
val middle = Vec3(lo).add(hi).mul(0.5f)
val span = maxOf(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z)
val reach = span * 4f
val floorY = lo.y - 0.25f
scene.quad(
    Vec3(middle.x - reach, floorY, middle.z - reach),
    Vec3(middle.x + reach, floorY, middle.z - reach),
    Vec3(middle.x + reach, floorY, middle.z + reach),
    Vec3(middle.x - reach, floorY, middle.z + reach),
    floor,
)

val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val camera = Camera(renderer).also {
    it.at.set(middle.x + span * 0.15f, middle.y + span * 0.32f, middle.z + span * 1.05f)
    it.target.set(middle.x, middle.y - span * 0.04f, middle.z)
    it.p.fov = 0.62
}
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = 2
tracer.maxBounces = 10
tracer.exposure = 0.9f
tracer.environmentRotation = 2.1f
tracer.indirectClamp = 12f
// The sky still lights the glass and metal, but the camera sees black behind the
// sculpture, and the floor fades into black fog past it.
tracer.showEnvironment = false
tracer.background = Vector4f(0f, 0f, 0f, 1f)
tracer.fogColor = Vector3f(0f, 0f, 0f)
tracer.fogStartDistance = span * 0.95f
tracer.fogDensity = 4f / span
tracer.fogBackground = true
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
frames {
    while (tracer.samples < 900) { frame() }
    println("petri sculpture converged: ${tracer.samples} samples")
}
```

