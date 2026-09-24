# Field Document: skinned_volume_path_trace

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="chain_renderer" index="0" -->

```edn [field-box id="chain_renderer" role="properties"]
{"id" "chain_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["skinned_volume_trace"] "properties" {"children" #{#afield/box "skinned_volume_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chain_renderer_code_.kt" "lines" 9 "characters" 221 "sha256" "f23e4958c4740029324481f504f3e4dcd8c32d101d0c44f45a77d7efdf714fdd"}]}
```

```kotlin [field-property box="chain_renderer" name="code" path="chain_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Skinning through Manifold")
    Renderer(window)
}
start(renderer::run)
```

## 2. a twisted limb, skinned two ways, weighed, traced

<!-- field-box id="skinned_volume_trace" index="1" -->

```edn [field-box id="skinned_volume_trace" role="properties"]
{"id" "skinned_volume_trace" "index" 1 "name" "a twisted limb, skinned two ways, weighed, traced" "parents" ["root" "chain_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "chain_renderer"} "name" "a twisted limb, skinned two ways, weighed, traced" "_tombstone" false "frame" #afield/rect [320.0 140.0 300.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "skinned_volume_trace_code_.kt" "lines" 86 "characters" 3579 "sha256" "e205f7561af91fc731e3d1369b837e465e599ef175ed6b0f17b965c92fdc46fc"}]}
```

```kotlin [field-property box="skinned_volume_trace" name="code" path="skinned_volume_trace_code_.kt"]
import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.fabricated.importTriMesh
import afield.four.modules.CappedTwistFixture
import afield.four.modules.GltfSkinning.SkinningMethod
import afield.four.modules.GltfSkinning.SkinningOptions
import afield.four.modules.fromContext
import afield.four.modules.skinnedGltfToTriMesh
import afield.four.util.Camera
import afield.manifold.ManifoldLibrary
import afield.manifold.toSolid
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import java.io.File

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

require(ManifoldLibrary.isAvailable) {
    ManifoldLibrary.describe() + "\nOn macOS: brew install manifold, then run this box again."
}

val twist = 180f   // degrees about the bone's own axis; 180 is the worst case for LBS

// ---- stage one: skin the same file two ways -----------------------------------
val rig = CappedTwistFixture.locate()
fun posed(method: SkinningMethod) = skinnedGltfToTriMesh(
    rig, clip = "twist", seconds = CappedTwistFixture.secondsFor(twist),
    options = SkinningOptions(method = method),
)

// ---- stage two: each pose becomes a solid, is weighed, and comes back shaded ----
// 60 degrees splits the caps from the wall and leaves the 30-degree facets of the
// twelve-sided wall smooth.
fun weighed(method: SkinningMethod) = posed(method).toSolid().use { solid ->
    println("%-16s genus %d, volume %.4f (rest %.1f)".format(
        method.name, solid.genus, solid.volume, CappedTwistFixture.REST_VOLUME))
    solid.toTriMesh(normals = true, minSharpAngle = 60.0)
}
val lbs = weighed(SkinningMethod.LINEAR_BLEND)
val dqs = weighed(SkinningMethod.DUAL_QUATERNION)

// ---- stage three: lay both limbs on a floor and trace them --------------------
val scene = PathTraceScene()
val cyan = scene.material(PathTraceMaterial.plastic(0.30f, 0.72f, 0.90f, 0.35f))
val orange = scene.material(PathTraceMaterial.plastic(0.95f, 0.50f, 0.20f, 0.35f))
val floor = scene.material(PathTraceMaterial.plastic(0.5f, 0.51f, 0.53f, 0.5f))

// The rig stands along +y from 0 to 4. Turn it onto its side (+y to +x), centre it, and
// lift it by its radius so it rests on the floor at y = 0.
fun lying(z: Float) = Matrix4f().translation(-2f, CappedTwistFixture.RADIUS, z)
    .rotateZ(-0.5f * Math.PI.toFloat())
val a = scene.importTriMesh(lbs, cyan, lying(0.8f))
val b = scene.importTriMesh(dqs, orange, lying(-0.8f))
println("scene: ${a.triangleCount + b.triangleCount} triangles appended, " +
    "${a.degenerateCount + b.degenerateCount} dropped")

val reach = 8f
scene.quad(
    Vector3f(-reach, 0f, -reach), Vector3f(reach, 0f, -reach),
    Vector3f(reach, 0f, reach), Vector3f(-reach, 0f, reach),
    floor,
)

val environment = PathTraceEnvironment.fromFile(
    File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath,
)
val camera = Camera(renderer).also {
    it.at.set(1.2f, 3.4f, 5.2f)
    it.target.set(0f, 0.35f, 0f)
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
frames {
    while (tracer.samples < 500) { frame() }
    println("skinned volume study converged: ${tracer.samples} samples")
}
```

