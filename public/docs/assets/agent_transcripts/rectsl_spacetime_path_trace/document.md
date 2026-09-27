# Field Document: rectsl_spacetime_path_trace

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="chain_renderer" index="0" -->

```edn [field-box id="chain_renderer" role="properties"]
{"id" "chain_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["rectsl_spacetime_trace"] "properties" {"children" #{#afield/box "rectsl_spacetime_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chain_renderer_code_.kt" "lines" 9 "characters" 213 "sha256" "44a886b3b252b96ed324de6a74cd7c6c6336ca714be1eb95a06ebab944778be7"}]}
```

```kotlin [field-property box="chain_renderer" name="code" path="chain_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · RectSL as a solid")
    Renderer(window)
}
start(renderer::run)
```

## 2. a RectSL animation, as a solid

<!-- field-box id="rectsl_spacetime_trace" index="1" -->

```edn [field-box id="rectsl_spacetime_trace" role="properties"]
{"id" "rectsl_spacetime_trace" "index" 1 "name" "a RectSL animation, as a solid" "parents" ["root" "chain_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "chain_renderer"} "name" "a RectSL animation, as a solid" "_tombstone" false "frame" #afield/rect [320.0 140.0 300.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rectsl_spacetime_trace_code_.kt" "lines" 210 "characters" 8823 "sha256" "f21d43ec9172be3db20f341c2c2147517855855d2954850959a0cb7d7ca5a911"}]}
```

```kotlin [field-property box="rectsl_spacetime_trace" name="code" path="rectsl_spacetime_trace_code_.kt"]
import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.fabricated.importTriMesh
import afield.four.modules.fromContext
import afield.four.util.Camera
import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.rectsl.compileRectSL
import afield.rectsl.toAdField
import afield.stdlib.boundsOf
import afield.stdlib.diagnose
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.linalg.Vector3f
import afield.stdlib.marchingCubes
import afield.stdlib.sampledField3D
import java.io.File

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

require(ManifoldLibrary.isAvailable) {
    ManifoldLibrary.describe() + "\nOn macOS: brew install manifold, then run this box again."
}

// ---- the animation -------------------------------------------------------------
// Two rects and four keyed tracks. `'( 0=a  4=b )` is RectSL's track literal: a value at
// a time, linearly interpolated, and `->x` takes one wherever it takes a number.
val source = """
#rectsl
->wall 8 8 abstract
->duration 4
->fps 30

"sweep" rect
  0.7 2.4 ->wh
  '( 0=-2.6  4=2.6 ) ->x
  '( 0=0  4=3.14159 ) ->rot

"pulse" rect
  1.2 1.2 ->wh
  1.6 -1.4 ->xy
  '( 0=0.3  4=2.2 ) ->w
""".trimIndent()

val program = compileRectSL(source).program
val field = program.toAdField()
println("RectSL: ${program.wall.width} x ${program.wall.height} wall, " +
    "${program.duration}s, ${field.rectCount} of ${program.nodes.size} rects lowered")
println("  worst AD-against-Fn.eval disagreement across the lowered channels: " +
    field.worstChannelDisagreement)
if (field.skipped.isNotEmpty()) println("  refused: ${field.skipped}")

// ---- time as the third axis ----------------------------------------------------
val nx = 104
val ny = 104
val nt = 80
val halfWall = 4.4       // the shapes reach 3.85, so this clears them
val halfDepth = 3.0      // how long the time axis is, in the wall's own units

// One autodiff tape per slice, and the tape is the reason this is sampled onto a grid
// rather than handed to a marcher directly: an ADTape grows monotonically, so it is worth
// reusing across a slice and wrong to keep for a whole march. Sampling first also makes
// the field safe to read from several threads.
val inner = FloatArray(nx * ny * nt)
val sampledAt = System.nanoTime()
for (k in 0 until nt) {
    val t = program.duration * k / (nt - 1.0)
    val slice = field.frameAt(t)
    for (j in 0 until ny) {
        val y = -halfWall + 2 * halfWall * j / (ny - 1.0)
        for (i in 0 until nx) {
            val x = -halfWall + 2 * halfWall * i / (nx - 1.0)
            inner[(k * ny + j) * nx + i] = slice.distanceAt(x, y).value.toFloat()
        }
    }
}
val sampleMs = (System.nanoTime() - sampledAt) / 1e6
println("sampled ${inner.size} points, ${nt} tapes, ${"%.0f".format(sampleMs)} ms")
println("  ${inner.count { it < 0f }} of them inside, distances " +
    "${"%.3f".format(inner.min())} to ${"%.3f".format(inner.max())}")

val innerBounds = boundsOf(
    vec(-halfWall, -halfWall, -halfDepth),
    vec(halfWall, halfWall, halfDepth),
)

// ---- what happens at the wall, measured both ways ------------------------------
// The animation is solid at t = 0 and at t = duration, so the surface of the unpadded
// volume runs into the sampling window and has to stop there. This is the case the
// `levelSet` row of Solid's own table is about: a march leaves that boundary open, and the
// level set closes it against the box.
val unpadded = sampledField3D(inner, nx, ny, nt, innerBounds)
val cutMarch = marchingCubes(unpadded, innerBounds, resolution = 140)
val cutSolid = Solid.levelSetOf(unpadded, innerBounds, edgeLength = 0.12, parallel = false)
println()
println("with the volume ending exactly at the first and last frame:")
println("  marchingCubes: ${cutMarch.triangleCount} triangles, " +
    "watertight ${cutMarch.isWatertight()}, ${cutMarch.diagnose().boundaryLoops.size} boundary loop(s)")
println("  levelSetOf:    ${cutSolid.triangleCount} triangles, status ${cutSolid.status}, " +
    "genus ${cutSolid.genus}, volume ${"%.3f".format(cutSolid.volume)}")
cutSolid.close()

// ---- and the version that gets rendered ----------------------------------------
// One empty slice each side, a hair outside, so the solid closes flat a fraction of a cell
// beyond the first and last frame instead of being serrated by the jump to `outside`.
val dz = 2 * halfDepth / (nt - 1.0)
val padded = FloatArray(nx * ny * (nt + 2))
val slab = nx * ny
java.util.Arrays.fill(padded, 0, slab, 0.25f)
System.arraycopy(inner, 0, padded, slab, inner.size)
java.util.Arrays.fill(padded, slab + inner.size, padded.size, 0.25f)
val bounds = boundsOf(
    vec(-halfWall, -halfWall, -(halfDepth + dz)),
    vec(halfWall, halfWall, halfDepth + dz),
)

val spacetime = sampledField3D(padded, nx, ny, nt + 2, bounds)
val marched = marchingCubes(spacetime, bounds, resolution = 140)
println()
println("and with one empty slice each side:")
println("  marchingCubes: ${marched.triangleCount} triangles, " +
    "watertight ${marched.isWatertight()}, genus ${marched.genus()}")

val whole = Solid.levelSetOf(spacetime, bounds, edgeLength = 0.12, parallel = false)
println("  levelSetOf:    ${whole.triangleCount} triangles, status ${whole.status}, " +
    "genus ${whole.genus}, volume ${"%.3f".format(whole.volume)}")

val parts = whole.decompose()
println("  ${parts.size} component(s): volumes " +
    parts.map { "%.2f".format(it.volume) }.joinToString(", ") +
    " — connected because the two rects overlap on screen at some frame")

// ---- the render ----------------------------------------------------------------
val scene = PathTraceScene()
// White base colour, because the per-vertex colour multiplies into it.
val body = scene.material(PathTraceMaterial.plastic(1.0f, 1.0f, 1.0f, 0.26f))
val floorMaterial = scene.material(PathTraceMaterial.plastic(0.42f, 0.43f, 0.45f, 0.5f))

val place = afield.stdlib.linalg.Matrix4f().scale(0.34f, 0.34f, 0.34f)
val sceneLo = Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
val sceneHi = Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
var appended = 0
var dropped = 0
parts.forEach { part ->
    val plain = part.toTriMesh(normals = true)
    // Colour every vertex by where it sits on the time axis, cool at the first frame and
    // warm at the last, so the render says which end of the solid the animation starts at.
    val colours = FloatArray(plain.vertexCount * 3)
    for (v in 0 until plain.vertexCount) {
        val u = ((plain.z(v) + halfDepth.toFloat()) / (2f * halfDepth.toFloat()))
            .coerceIn(0.0f, 1.0f)
        colours[v * 3] = 0.10f + 0.85f * u
        colours[v * 3 + 1] = 0.34f + 0.14f * u
        colours[v * 3 + 2] = 0.86f - 0.76f * u
    }
    val tinted = afield.stdlib.TriMesh(
        positions = plain.positions,
        indices = plain.indices,
        normals = plain.normals,
        colors = colours,
    )
    val added = scene.importTriMesh(
        tinted,
        body,
        transform = place,
        vertexColors = true,
        warning = {},
    )
    appended += added.triangleCount
    dropped += added.degenerateCount
    if (added.triangleCount > 0) { sceneLo.min(added.boundsMin); sceneHi.max(added.boundsMax) }
}
println("scene: $appended triangles, $dropped dropped, coloured by time along the third axis")
parts.forEach { it.close() }
whole.close()

val middle = Vector3f(sceneLo).add(sceneHi).mul(0.5f)
val span = maxOf(sceneHi.x - sceneLo.x, sceneHi.y - sceneLo.y, sceneHi.z - sceneLo.z)
val reach = span * 3f
scene.quad(
    Vector3f(middle.x - reach, sceneLo.y - 0.25f, middle.z - reach),
    Vector3f(middle.x + reach, sceneLo.y - 0.25f, middle.z - reach),
    Vector3f(middle.x + reach, sceneLo.y - 0.25f, middle.z + reach),
    Vector3f(middle.x - reach, sceneLo.y - 0.25f, middle.z + reach),
    floorMaterial,
)

val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val camera = Camera(renderer).also {
    it.at.set(middle.x + span * 1.1f, middle.y + span * 0.7f, middle.z + span * 1.5f)
    it.target.set(middle.x, middle.y, middle.z)
    it.p.fov = 0.6
}
tracer = PathTracingRenderer(renderer, scene, environment, 1000, 614, camera).install()
tracer.samplesPerFrame = 2
tracer.maxBounces = 8
tracer.exposure = 1.05f
tracer.environmentRotation = 2.1f
tracer.indirectClamp = 10f
tracer.blitToScreen()
println("${tracer.backend}: ${tracer.triangleCount} triangles")
frames {
    while (tracer.samples < 700) { frame() }
    println("rectsl spacetime study converged: ${tracer.samples} samples")
}
```

