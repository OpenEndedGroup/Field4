# Field Document: polytope_manifold_path_trace

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="chain_renderer" index="0" -->

```edn [field-box id="chain_renderer" role="properties"]
{"id" "chain_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["polytope_manifold_trace"] "properties" {"children" #{#afield/box "polytope_manifold_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chain_renderer_code_.kt" "lines" 9 "characters" 221 "sha256" "4234324aea74e30a33f6b7e4be99ae1e3cf71405d4e2ee18297fb7f01d6597be"}]}
```

```kotlin [field-property box="chain_renderer" name="code" path="chain_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Polytope through Manifold")
    Renderer(window)
}
start(renderer::run)
```

## 2. polytope, eroded into a shell, cut open, traced

<!-- field-box id="polytope_manifold_trace" index="1" -->

```edn [field-box id="polytope_manifold_trace" role="properties"]
{"id" "polytope_manifold_trace" "index" 1 "name" "polytope, eroded into a shell, cut open, traced" "parents" ["root" "chain_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "chain_renderer"} "name" "polytope, eroded into a shell, cut open, traced" "_tombstone" false "frame" #afield/rect [320.0 140.0 300.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_manifold_trace_code_.kt" "lines" 113 "characters" 5447 "sha256" "2942e9ae4dfddb02fb112e745e70d884c62aeed327f2606934b4390d8c6e00e9"}]}
```

```kotlin [field-property box="polytope_manifold_trace" name="code" path="polytope_manifold_trace_code_.kt"]
import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.fabricated.importTriMesh
import afield.four.modules.fromContext
import afield.four.util.Camera
import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.manifold.toSolid
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.linalg.Vector3f
import afield.stdlib.signedVolume
import afield.stdlib.weld
import afield.stdlib.polytope.NormalPolicy
import afield.stdlib.polytope.canonicalConway
import afield.stdlib.polytope.triangulate
import java.io.File

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

// The Boolean stage is the middle of this chain rather than an extra, so this refuses
// rather than falling back to something that looks similar.
require(ManifoldLibrary.isAvailable) {
    ManifoldLibrary.describe() + "\nOn macOS: brew install manifold, then run this box again."
}

// ---- one parameter -------------------------------------------------------------
val thickness = 0.09   // wall thickness, in the polytope's own units (it spans about 2)
// --------------------------------------------------------------------------------

// ---- stage one: topology to a mesh ---------------------------------------------
val chain = canonicalConway("bD")
val geometry = chain.polyhedron.triangulate(normals = NormalPolicy.Flat)
// The triangulator cuts each polygon on its own, so a corner shared by three faces comes
// out as three vertices. Weld before handing it over: a manifold is an index property, and
// 360 unwelded vertices describe the same surface without sharing an edge anywhere.
val plain = geometry.mesh.weld(1e-6)
println("bD: ${geometry.triangleCount} triangles, " +
    "${geometry.mesh.vertexCount} vertices unwelded, ${plain.vertexCount} welded")
println("   watertight ${plain.isWatertight()}, genus ${plain.genus()}")

// ---- stage two: the Booleans ---------------------------------------------------
val cut = Solid.scope {
    val solid = plain.toSolid()
    println("Manifold: status ${solid.status}, genus ${solid.genus}, " +
        "volume ${"%.4f".format(solid.volume)}, area ${"%.4f".format(solid.surfaceArea)}")
    println("   Field says volume ${"%.4f".format(plain.signedVolume())}, " +
        "differing by ${"%.2e".format(kotlin.math.abs(plain.signedVolume() - solid.volume))}")

    // Erode by a ball, then subtract: the wall left behind is the set of points within
    // `thickness` of the surface, so its thickness is the same everywhere. Scaling the
    // solid down instead would give a wall that is thicker at the vertices than at the
    // face centres, which looks similar and is not the same shape.
    val ball = Solid.sphere(thickness, segments = 24)
    val inner = solid.minkowskiDifference(ball)
    val wall = solid - inner
    println("shell: volume ${"%.4f".format(wall.volume)}, genus ${wall.genus}, " +
        "${wall.triangleCount} triangles")
    // A constant-thickness wall over a convex body has volume area*t minus a curvature
    // term, so the ratio lands a little under one and says how much the corners take back.
    println("   area * thickness = ${"%.4f".format(solid.surfaceArea * thickness)}, " +
        "ratio ${"%.3f".format(wall.volume / (solid.surfaceArea * thickness))}")

    // Open it up. trimByPlane keeps the side the normal points toward, so this keeps the
    // lower half and the camera looks down into it. A rim seen across rather than
    // end-on is what makes the wall's thickness a thing you can see.
    wall.trimByPlane(0.0, -1.0, 0.0, 0.0).checked("cut shell")
}
// A 20-degree crease rather than the default 60: this surface is all facets, and at 60 the
// shallow ones average together and the polytope stops reading as one.
val shaded = cut.toTriMesh(normals = true, minSharpAngle = 20.0)
println("cut: genus ${cut.genus}, volume ${"%.4f".format(cut.volume)}, " +
    "${shaded.triangleCount} triangles for the render")

// ---- stage three: the render ---------------------------------------------------
val scene = PathTraceScene()
val body = scene.material(PathTraceMaterial.plastic(0.52f, 0.17f, 0.09f, 0.24f))
val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.55f))
val added = scene.importTriMesh(shaded, body)
println("scene: ${added.triangleCount} triangles appended, ${added.degenerateCount} dropped")

val low = added.boundsMin.y - 0.02f
val reach = 6f
scene.quad(
    Vector3f(-reach, low, -reach), Vector3f(reach, low, -reach),
    Vector3f(reach, low, reach), Vector3f(-reach, low, reach),
    floor,
)

val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val camera = Camera(renderer).also {
    it.at.set(1.75f, 2.35f, 2.85f)
    it.target.set(0f, -0.3f, 0f)
    it.p.fov = 0.52
}
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = 2
tracer.maxBounces = 8
tracer.exposure = 1.35f
tracer.environmentRotation = 2.1f
tracer.indirectClamp = 10f
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
cut.close()
frames {
    while (tracer.samples < 900) { frame() }
    println("shell study converged: ${tracer.samples} samples")
}
```

