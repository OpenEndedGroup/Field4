# Field Document: life3d_manifold_path_trace

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="chain_renderer" index="0" -->

```edn [field-box id="chain_renderer" role="properties"]
{"id" "chain_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["life3d_manifold_trace"] "properties" {"children" #{#afield/box "life3d_manifold_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chain_renderer_code_.kt" "lines" 9 "characters" 220 "sha256" "c468f59499b1de0bb236e49269d719d7a01585f0875835faae127b23e41c00c6"}]}
```

```kotlin [field-property box="chain_renderer" name="code" path="chain_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · 3D Life through Manifold")
    Renderer(window)
}
start(renderer::run)
```

## 2. 3D life, decomposed, traced

<!-- field-box id="life3d_manifold_trace" index="1" -->

```edn [field-box id="life3d_manifold_trace" role="properties"]
{"id" "life3d_manifold_trace" "index" 1 "name" "3D life, decomposed, traced" "parents" ["root" "chain_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "chain_renderer"} "name" "3D life, decomposed, traced" "_tombstone" false "frame" #afield/rect [320.0 140.0 300.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "life3d_manifold_trace_code_.kt" "lines" 179 "characters" 8162 "sha256" "594224ff60a18582a1d95b253866e4f98ecfc331074e91132f71a301dba48cfc"}]}
```

```kotlin [field-property box="life3d_manifold_trace" name="code" path="life3d_manifold_trace_code_.kt"]
import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.fabricated.importTriMesh
import afield.four.modules.fromContext
import afield.four.util.Camera
import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.stdlib.IntVolume
import afield.stdlib.LIFE3D_BLOB
import afield.stdlib.asDensityField
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.linalg.Vector3f
import afield.stdlib.marchBounds
import afield.stdlib.step
import afield.stdlib.toMesh
import java.io.File
import kotlin.random.Random

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

require(ManifoldLibrary.isAvailable) {
    ManifoldLibrary.describe() + "\nOn macOS: brew install manifold, then run this box again."
}

// ---- the three parameters ------------------------------------------------------
val generations = 8
val edgeLength = 0.7    // level-set triangle size, in voxels
val iso = 0.5           // occupancy level taken as the surface
// --------------------------------------------------------------------------------

// ---- stage one: the automaton --------------------------------------------------
// A fixed seed, so the census below is the same number every time this runs.
val n = 36
val rng = Random(11)
val volume0 = IntVolume(n, n, n, IntArray(n * n * n), wrap = false)
val half = 8
val lo = n / 2 - half
for (z in lo until lo + 2 * half) for (y in lo until lo + 2 * half) for (x in lo until lo + 2 * half) {
    volume0[x, y, z] = if (rng.nextDouble() < 0.45) 1 else 0
}

var volume = volume0
val populations = mutableListOf(volume.population())
repeat(generations) { volume = volume.step(LIFE3D_BLOB); populations += volume.population() }
println("LIFE3D_BLOB populations: ${populations.joinToString(" -> ")}")

// smoothRadius = 0 marches the raw occupancy. A radius-1 blur is the usual choice and is
// the wrong one here: this rule leaves filaments and isolated cells, and a 27-voxel
// average over those never reaches an iso of 0.5, so the surface comes back empty.
val field = volume.asDensityField(smoothRadius = 0, iso = iso)
val bounds = volume.marchBounds()

// ---- stage two: two marchers on one field --------------------------------------
val marched = volume.toMesh(resolution = 110, smoothRadius = 0, iso = iso)
println("Field's marchingCubes: ${marched.triangleCount} triangles, " +
    "watertight ${marched.isWatertight()}, genus ${marched.genus()}")

// Manifold's convention is positive inside, which is what asDensityField already gives, so
// this is levelSet rather than levelSetOf: the latter negates for Field's SDF family.
// A fresh Vec3 per sample, because `field` is called from Manifold's own worker threads.
//
// parallel = false, and that is the whole reason the census below is a number worth
// printing: the parallel path is faster and not reproducible. The two runs underneath say
// so rather than the comment doing it alone.
fun march(parallel: Boolean): Pair<Solid, Double> {
    val t0 = System.nanoTime()
    val solid = Solid.levelSet(bounds, edgeLength = edgeLength, parallel = parallel) { x, y, z ->
        field(vec(x, y, z)).toDouble()
    }
    return solid to (System.nanoTime() - t0) / 1e6
}

val (whole, sequentialMs) = march(parallel = false)
println("Manifold's levelSet:   ${whole.triangleCount} triangles, status ${whole.status}, " +
    "genus ${whole.genus}, volume ${"%.4f".format(whole.volume)}, " +
    "${"%.0f".format(sequentialMs)} ms sequential")
repeat(2) { attempt ->
    val (parallelSolid, ms) = march(parallel = true)
    val components = Solid.scope { parallelSolid.decompose().size }
    println("  the same field with parallel = true, run ${attempt + 1}: " +
        "${parallelSolid.triangleCount} triangles, ${components} components, " +
        "volume ${"%.4f".format(parallelSolid.volume)}, ${"%.0f".format(ms)} ms")
    parallelSolid.close()
}

// ---- stage three: the census ---------------------------------------------------
// The two genus numbers disagree, and the reason is components rather than holes: Field's
// genus() reads one Euler characteristic and solves 2 - 2g for g, which is the genus of a
// connected surface. decompose() is the operation that says how many surfaces there are.
val parts = whole.decompose()
val volumes = parts.map { it.volume }
println()
println("${parts.size} separate clusters survived ${generations} generations")
println("  largest ${"%.2f".format(volumes.first())}, smallest ${"%.3f".format(volumes.last())}, " +
    "median ${"%.3f".format(volumes[volumes.size / 2])}")
println("  they sum to ${"%.2f".format(volumes.sum())} against the whole solid's " +
    "${"%.2f".format(whole.volume)}")
println("  and each one on its own has genus ${parts.take(6).map { it.genus }} ...")

// ---- stage four: the render ----------------------------------------------------
val scene = PathTraceScene()
val gold = scene.material(PathTraceMaterial.metal(1.0f, 0.74f, 0.33f, 0.22f))
// Plastic rather than glass for the other forty-seven. At a tenth of a unit across, a
// transparent fragment over a pale floor is a fragment you cannot see.
val body = scene.material(PathTraceMaterial.plastic(0.06f, 0.13f, 0.42f, 0.28f))
val floor = scene.material(PathTraceMaterial.plastic(0.30f, 0.31f, 0.33f, 0.5f))

// Centre the volume on the origin and scale it into view: the field is in voxels.
val centre = Vector3f(n / 2f, n / 2f, n / 2f)
val scale = 2.4f / n
val placement = afield.stdlib.linalg.Matrix4f()
    .scale(scale, scale, scale)
    .translate(-centre.x, -centre.y, -centre.z)

var appended = 0
var dropped = 0
val sceneLo = Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
val sceneHi = Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
parts.forEachIndexed { index, part ->
    val added = scene.importTriMesh(
        part.toTriMesh(normals = true, minSharpAngle = 25.0),
        if (index == 0) gold else body,
        transform = placement,
        warning = {},
    )
    appended += added.triangleCount
    dropped += added.degenerateCount
    if (added.triangleCount > 0) { sceneLo.min(added.boundsMin); sceneHi.max(added.boundsMax) }
}
println()
println("scene: $appended triangles from ${parts.size} clusters, $dropped dropped")
println("  occupying ${"%.2f".format(sceneHi.x - sceneLo.x)} x " +
    "${"%.2f".format(sceneHi.y - sceneLo.y)} x ${"%.2f".format(sceneHi.z - sceneLo.z)} " +
    "of the 2.40-unit cube the volume was scaled into")
parts.forEach { it.close() }
whole.close()

// The frame comes off the import reports rather than off the voxel grid: eight
// generations of a decaying rule leave a cloud much smaller than the volume it started
// in, and a camera placed for the grid puts it in the middle distance.
val middle = Vector3f(sceneLo).add(sceneHi).mul(0.5f)
val span = maxOf(sceneHi.x - sceneLo.x, sceneHi.y - sceneLo.y, sceneHi.z - sceneLo.z)
val reach = span * 3f
scene.quad(
    Vector3f(middle.x - reach, sceneLo.y - 0.30f, middle.z - reach),
    Vector3f(middle.x + reach, sceneLo.y - 0.30f, middle.z - reach),
    Vector3f(middle.x + reach, sceneLo.y - 0.30f, middle.z + reach),
    Vector3f(middle.x - reach, sceneLo.y - 0.30f, middle.z + reach),
    floor,
)

val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val camera = Camera(renderer).also {
    it.at.set(
        middle.x + span * 1.25f,
        middle.y + span * 0.75f,
        middle.z + span * 1.75f,
    )
    it.target.set(middle.x, middle.y, middle.z)
    it.p.fov = 0.58
}
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = 2
tracer.maxBounces = 8
tracer.exposure = 1.3f
tracer.environmentRotation = 2.1f
tracer.indirectClamp = 10f
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
frames {
    while (tracer.samples < 700) { frame() }
    println("life3d study converged: ${tracer.samples} samples")
}
```

