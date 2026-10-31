# Field Document: path_tracing_material_shader_check

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. path tracing renderer

<!-- field-box id="path_trace_renderer" index="0" -->

```edn [field-box id="path_trace_renderer" role="properties"]
{"id" "path_trace_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["path_trace_material_check"] "properties" {"children" #{#afield/box "path_trace_material_check"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_renderer_code_.kt" "lines" 10 "characters" 220 "sha256" "d9a523d21baae9bd403e31097aeb6360849ac2629258b04f0145cc3d54c4d2a9"}]}
```

```kotlin [field-property box="path_trace_renderer" name="code" path="path_trace_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Path tracing")
    Renderer(window)
}
start(renderer::run)
```

## 2. editable-material-check

<!-- field-box id="path_trace_material_check" index="1" -->

```edn [field-box id="path_trace_material_check" role="properties"]
{"id" "path_trace_material_check" "index" 1 "name" "editable-material-check" "parents" ["root" "path_trace_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "path_trace_renderer"} "name" "editable-material-check" "_tombstone" false "frame" #afield/rect [320.0 180.0 360.0 120.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "path_trace_material_check_compute_.glsl" "lines" 3 "characters" 112 "sha256" "f84237973383a2b88ee8701b1b0aa3f83cdad1cc9729d6e3bc29e03819860561"} {"name" "code" "language" "kotlin" "path" "path_trace_material_check_code_.kt" "lines" 63 "characters" 2361 "sha256" "7c570daa29a3a625619f236e6e9a325c0182a369f39f485d8cea2e4cb852ac21"}]}
```

```glsl [field-property box="path_trace_material_check" name="compute" path="path_trace_material_check_compute_.glsl"]
{{ pathTracing.preamble }}
{% property "editable-material-check.pathTracePaint" %}
{{ pathTracing.integrator }}
```

```kotlin [field-property box="path_trace_material_check" name="code" path="path_trace_material_check_code_.kt"]
import afield.graphics.scene.fabricated.*
import afield.graphics.vulkan.util.Camera
import kotlinx.coroutines.delay

val scene = PathTraceScene().apply {
    val m = material(PathTraceMaterial.emissive(0f, 0f, 0f))
    quad(Vector3f(-20f, -20f, 0f), Vector3f(20f, -20f, 0f), Vector3f(20f, 20f, 0f), Vector3f(-20f, 20f, 0f), m)
}
val camera = Camera(renderer).also {
    it.at.set(0f, 0f, 3f)
    it.target.set(0f, 0f, 0f)
}
prop<PathTracingRenderer>(tracer)
tracer = pathTracingHere(
    scene,
    PathTraceEnvironment.constant(0f),
    width = 32,
    height = 24,
    camera = camera,
    name = "editable-study",
)
tracer.materialMesh.gain = { 2f }
tracer.maxBounces = 1
tracer.blitToScreen()
fun afield.document.graph.Box.ownerForTest() = this
val owner = ownerForTest()
val verify: suspend () -> Unit = {
    PathTraceGpuSmoke.awaitSamples(tracer, 8)
    tracer.paused = true
    check(kotlin.math.abs(tracer.readLinear()[0] - 2f) < 1e-5) { "Initial box material hook was not evaluated" }
    val original = tracer.materialModule.current
    owner.setPropertyRaw("pathTracePaint", "invalid GLSL")
    check(!reloadComputeShader(owner))
    check(tracer.materialModule.current === original) { "Failed reload replaced the working pipeline" }
    owner.setPropertyRaw(
        "pathTracePaint",
        """
        layout(push_constant) uniform PaintPC { float gain; float bias; } pc;
        Material shadeMaterial(Material material, PathTraceSurface surface) {
            material.base.rgb = vec3(0);
            material.emission.rgb = vec3(pc.gain + pc.bias, 0, 0);
            return material;
        }
        """
            .trimIndent(),
    )
    check(reloadComputeShader(owner))
    check(tracer.materialModule.current !== original)
    check(tracer.materialModule.current.ci.pushConstants.single().members.map { it.name } == ["gain", "bias"])
    tracer.materialMesh.push["bias"] = 1f
    tracer.paused = false
    delay(100)
    PathTraceGpuSmoke.awaitSamples(tracer, 4)
    tracer.paused = true
    check(kotlin.math.abs(tracer.readLinear()[0] - 3f) < 1e-5) {
        "Reload retained old accumulation or failed to preserve push values"
    }
    println(
        "PASS EDITABLE MATERIAL BOX: seeded templates, includes, reflected push constants, changed ABI, last-valid reload, accumulation reset",
    )
    tracer.close()
}
start(verify)
```

