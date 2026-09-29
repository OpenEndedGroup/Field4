# Field Document: physics_manifold_path_trace

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="chain_renderer" index="0" -->

```edn [field-box id="chain_renderer" role="properties"]
{"id" "chain_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["physics_manifold_trace"] "properties" {"children" #{#afield/box "physics_manifold_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chain_renderer_code_.kt" "lines" 9 "characters" 218 "sha256" "012ed8443bbd6a083b44dfcd6e1745e705ebe33816199803005ea815f12cf9d3"}]}
```

```kotlin [field-property box="chain_renderer" name="code" path="chain_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Cloth through Manifold")
    Renderer(window)
}
start(renderer::run)
```

## 2. a draped cloth, welded into one solid, traced

<!-- field-box id="physics_manifold_trace" index="1" -->

```edn [field-box id="physics_manifold_trace" role="properties"]
{"id" "physics_manifold_trace" "index" 1 "name" "a draped cloth, welded into one solid, traced" "parents" ["root" "chain_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "chain_renderer"} "name" "a draped cloth, welded into one solid, traced" "_tombstone" false "frame" #afield/rect [320.0 140.0 300.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "physics_manifold_trace_code_.kt" "lines" 132 "characters" 5734 "sha256" "5a6f5a18c61b32bc4850e95e6fa8b425aea82fee83fde35582bc981d6ef18c10"}]}
```

```kotlin [field-property box="physics_manifold_trace" name="code" path="physics_manifold_trace_code_.kt"]
import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.fabricated.importTriMesh
import afield.four.modules.fromContext
import afield.four.physics.PlaneCollider
import afield.four.physics.SimdVerlet
import afield.four.physics.SphereCollider
import afield.four.physics.addCloth
import afield.four.physics.addColliders
import afield.four.util.Camera
import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.linalg.Vector3f
import java.io.File

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

require(ManifoldLibrary.isAvailable) {
    ManifoldLibrary.describe() + "\nOn macOS: brew install manifold, then run this box again."
}

// ---- the knobs ------------------------------------------------------------------
val side = 14           // particles per side; struts go as 2*side*(side-1)
val spacing = 0.17f     // rest length between neighbours
val rod = 0.035         // radius of the rod each strut becomes
val settleFrames = 300  // physics frames before the geometry is taken
// ---------------------------------------------------------------------------------

// ---- stage one: the physics ----------------------------------------------------
// Gravity is +y in SimdVerlet, which is why the sphere sits BELOW the sheet at y = 0.8
// and the floor's legal side is above a plane whose normal is -y.
val sim = SimdVerlet(SimdVerlet.State3.empty())
val cloth = sim.addCloth(
    width = side,
    height = side,
    spacing = spacing,
    stiffness = 60f,
    curvatureStrength = 20f,
    position = { c, r -> vec((c - (side - 1) * 0.5f) * spacing, 0f, (r - (side - 1) * 0.5f) * spacing) },
    pinned = { _, _ -> false },
)
val world = sim.addColliders(
    SphereCollider(0f, 0.78f, 0f, radius = 0.52f).also { it.friction = 0.8f; it.margin = 0.01f },
    PlaneCollider(0f, -1f, 0f, offset = -1.28f).also { it.friction = 0.9f },
)
repeat(settleFrames) {
    cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = 16)
}
println("${cloth.count} particles, ${world.contacts} contacts on the last frame")

// ---- stage two: 364 rods, one batched union ------------------------------------
// The path tracer has +y up and SimdVerlet has +y down, so read the physics out with y
// negated once, here, rather than flipping the camera and the floor to match it.
fun at(col: Int, row: Int): Triple<Double, Double, Double> {
    val p = cloth.position(col, row)
    return Triple(p.x.toDouble(), -p.y.toDouble(), p.z.toDouble())
}

val struts = buildList {
    for (row in 0 until side) for (col in 0 until side) {
        if (col + 1 < side) add(at(col, row) to at(col + 1, row))
        if (row + 1 < side) add(at(col, row) to at(col, row + 1))
    }
}
val quads = (side - 1) * (side - 1)
println("${struts.size} struts, ${quads} quads")

val (net, netGenus) = Solid.scope {
    // A capsule is the convex hull of two balls, so `hullOf` does the orientation and
    // there is no basis to build. `segments = 10` keeps the triangle count of 364 of these
    // in six figures rather than seven.
    val rods = struts.map { (a, b) ->
        Solid.hullOf(
            Solid.sphere(rod, segments = 10).translated(a.first, a.second, a.third),
            Solid.sphere(rod, segments = 10).translated(b.first, b.second, b.third),
        )
    }
    // One batched union rather than 363 nested ones: Manifold plans the whole set.
    val start = System.nanoTime()
    val welded = Solid.union(rods).checked("cloth net")
    println("union of ${rods.size} rods: ${"%.0f".format((System.nanoTime() - start) / 1e6)} ms, " +
        "${welded.triangleCount} triangles, volume ${"%.5f".format(welded.volume)}")
    // A Pair's Solid is exempted from the scope's release, same as a bare one.
    welded to welded.genus
}
val shaded = net.toTriMesh(normals = true, minSharpAngle = 30.0)
val plain = net.toTriMesh()
println("genus $netGenus against $quads quads in the sheet; " +
    if (netGenus == quads) "they agree, so no two rods have been welded by contact"
    else "they differ by ${quads - netGenus}, so that many cycles have been closed by folds touching")
println("Field's own reckoning: watertight ${plain.isWatertight()}, genus ${plain.genus()}")

// ---- stage three: the render ---------------------------------------------------
val scene = PathTraceScene()
val metal = scene.material(PathTraceMaterial.metal(0.95f, 0.78f, 0.42f, 0.22f))
val floor = scene.material(PathTraceMaterial.plastic(0.5f, 0.51f, 0.53f, 0.5f))
val added = scene.importTriMesh(shaded, metal)
println("scene: ${added.triangleCount} triangles appended, ${added.degenerateCount} dropped")

val reach = 6f
scene.quad(
    Vector3f(-reach, -1.30f, -reach), Vector3f(reach, -1.30f, -reach),
    Vector3f(reach, -1.30f, reach), Vector3f(-reach, -1.30f, reach),
    floor,
)

val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val camera = Camera(renderer).also {
    it.at.set(1.75f, 0.4f, 2.25f)
    it.target.set(0f, -0.8f, 0f)
    it.p.fov = 0.62
}
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = 2
tracer.maxBounces = 6
tracer.exposure = 1.3f
tracer.environmentRotation = 2.1f
tracer.indirectClamp = 10f
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
net.close()
frames {
    while (tracer.samples < 700) { frame() }
    println("cloth study converged: ${tracer.samples} samples")
}
```

