# Field Document: polytope_path_trace_lineage

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="chain_renderer" index="0" -->

```edn [field-box id="chain_renderer" role="properties"]
{"id" "chain_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["polytope_lineage_trace"] "properties" {"children" #{#afield/box "polytope_lineage_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chain_renderer_code_.kt" "lines" 9 "characters" 219 "sha256" "19487060214b1bc963fb497fc9abca03cb6699de062ca5d14c0a5c38ecdd6c71"}]}
```

```kotlin [field-property box="chain_renderer" name="code" path="chain_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Polytope to path tracer")
    Renderer(window)
}
start(renderer::run)
```

## 2. lineage chooses the material

<!-- field-box id="polytope_lineage_trace" index="1" -->

```edn [field-box id="polytope_lineage_trace" role="properties"]
{"id" "polytope_lineage_trace" "index" 1 "name" "lineage chooses the material" "parents" ["root" "chain_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "chain_renderer"} "name" "lineage chooses the material" "_tombstone" false "frame" #afield/rect [320.0 140.0 280.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "polytope_lineage_trace_code_.kt" "lines" 97 "characters" 4520 "sha256" "293d9ae6faf188ad12ad5375afb98ce350179775a29e4d94b70889b6631c1718"}]}
```

```kotlin [field-property box="polytope_lineage_trace" name="code" path="polytope_lineage_trace_code_.kt"]
import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.fabricated.importTriMesh
import afield.four.modules.fromContext
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f
import afield.stdlib.polytope.ElementId
import afield.stdlib.polytope.NormalPolicy
import afield.stdlib.polytope.canonicalConway
import afield.stdlib.polytope.triangulate
import java.io.File

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

// ---- the topology ---------------------------------------------------------------
// Bevel the dodecahedron, then canonicalize so the faces come out edge-tangent and the
// whole thing is worth looking at. Both steps keep the lineage.
val chain = canonicalConway("bD")
val poly = chain.polyhedron
val geometry = poly.triangulate(normals = NormalPolicy.Flat)

// Bevel sends every output face back to exactly one element of the seed, so `single()` is
// safe here and would throw the moment that stopped being true. The rank is the whole
// classification: 2 means the face sits over one of the dodecahedron's pentagons, 1 over
// one of its edges, 0 over one of its vertices.
fun seedRank(face: ElementId): Int = chain.sourceElements(face).single().rank

val faces = poly.topology.elements(2)
val census = faces.groupingBy { seedRank(it.id) }.eachCount().toSortedMap()
println("bD: ${faces.size} faces, ${geometry.triangleCount} triangles")
census.forEach { (rank, count) ->
    val sides = faces.filter { seedRank(it.id) == rank }
        .map { poly.topology.faceCycle(it.id).size }.distinct().sorted()
    println("  rank $rank -> $count faces, $sides sides each")
}
println("  the dodecahedron has 12 faces, 30 edges and 20 vertices")

// ---- the materials -------------------------------------------------------------
// Thin glass rather than solid: a face of a closed polyhedron is a patch, and solid glass
// measures its attenuation between surface crossings, which a patch does not give it.
val scene = PathTraceScene()
// Roughness 0.32 rather than a mirror finish: a decagon is flat and large, and a flat
// mirror this size returns one direction of a hazy sky, which reads as pale rather than
// as metal. Spreading the lobe averages the map and the gold tint comes back.
val gold = scene.material(PathTraceMaterial.metal(1.0f, 0.73f, 0.31f, 0.32f))
val glass = scene.material(PathTraceMaterial.thinGlass(1.52f, Vector3f(0.9f, 0.97f, 0.94f)))
val plastic = scene.material(PathTraceMaterial.plastic(0.05f, 0.09f, 0.34f, 0.3f))
val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.55f))

fun materialFor(face: ElementId): Int = when (seedRank(face)) {
    2 -> gold      // the twelve decagons, one per pentagon of the dodecahedron
    1 -> glass     // the thirty squares, one per edge
    else -> plastic // the twenty hexagons, one per vertex
}

// `sourceFace(t)` is the polygon triangle t was cut from, so the per-triangle selector is
// the lineage lookup with nothing in between.
val added = scene.importTriMesh(
    geometry.mesh,
    { t -> materialFor(geometry.sourceFace(t)) },
)
println("scene: ${added.triangleCount} triangles appended, ${added.degenerateCount} dropped")
println("       bounds ${added.boundsMin} .. ${added.boundsMax}")

val low = added.boundsMin.y - 0.02f
val reach = 6f
scene.quad(
    Vector3f(-reach, low, -reach), Vector3f(reach, low, -reach),
    Vector3f(reach, low, reach), Vector3f(-reach, low, reach),
    floor,
)

// ---- the render ----------------------------------------------------------------
val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val camera = Camera(renderer).also {
    it.at.set(2.9f, 0.85f, 4.6f)
    it.target.set(0f, 0.02f, 0f)
    it.p.fov = 0.44
}
// 1000 x 614 is the window's own aspect, so the blit fills it rather than letterboxing.
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = 2
tracer.maxBounces = 8
tracer.exposure = 1.35f
tracer.environmentRotation = 2.1f
tracer.indirectClamp = 10f // Optional biased suppression of indirect fireflies.
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
frames {
    while (tracer.samples < 900) { frame() }
    println("lineage study converged: ${tracer.samples} samples")
}
```

