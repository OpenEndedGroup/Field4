# Field Document: path_tracing_material_shader_check

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. path tracing renderer

<!-- field-box id="path_trace_renderer" index="0" -->

```edn [field-box id="path_trace_renderer" role="properties"]
{"id" "path_trace_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["path_trace_material_check"] "properties" {"children" #{#afield/box "path_trace_material_check"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 110.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "path_trace_renderer_code_.kt" "lines" 9 "characters" 208 "sha256" "b6c2bd22807c33e2c2e4d8717a315d29f7748bf5fc921acfae8e2aad2e0a0ec1"}]}
```

```kotlin [field-property box="path_trace_renderer" name="code" path="path_trace_renderer_code_.kt"]
import afield.four.util.Options
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
{"id" "path_trace_material_check" "index" 1 "name" "editable-material-check" "parents" ["root" "path_trace_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "path_trace_renderer"} "name" "editable-material-check" "_tombstone" false "frame" #afield/rect [320.0 180.0 360.0 120.0]} "blockProperties" [{"name" "compute" "language" "glsl" "path" "path_trace_material_check_compute_.glsl" "lines" 3 "characters" 111 "sha256" "3f35ab62c9062e7520cf50e53f2facce58f272fe9bdba7fb8da6ec9b6d6539a1"} {"name" "code" "language" "kotlin" "path" "path_trace_material_check_code_.kt" "lines" 48 "characters" 2313 "sha256" "1f7ef7229af07eaee31376ba105ec1fbb54664e7f26cc549f1073f2bf8da7bfe"}]}
```

```glsl [field-property box="path_trace_material_check" name="compute" path="path_trace_material_check_compute_.glsl"]
{{ pathTracing.preamble }}
{% include "editable-material-check.pathTracePaint" %}
{{ pathTracing.integrator }}
```

```kotlin [field-property box="path_trace_material_check" name="code" path="path_trace_material_check_code_.kt"]
import afield.four.fabricated.*
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f
import afield.stdlib.reloadComputeShader
import kotlinx.coroutines.delay

val renderer: Renderer = fromContext()
val scene = PathTraceScene().apply {
    val m = material(PathTraceMaterial.emissive(0f,0f,0f))
    quad(Vector3f(-20f,-20f,0f),Vector3f(20f,-20f,0f),Vector3f(20f,20f,0f),Vector3f(-20f,20f,0f),m)
}
val camera = Camera(renderer).also { it.at.set(0f,0f,3f); it.target.set(0f,0f,0f) }
prop<PathTracingRenderer>(tracer)
tracer = pathTracingHere(scene, PathTraceEnvironment.constant(0f), width=32, height=24, camera=camera, name="editable-study")
tracer.materialMesh.gain = { 2f }
tracer.maxBounces = 1
tracer.blitToScreen()
fun afield.Box.ownerForTest() = this
val owner = ownerForTest()
val verify: suspend () -> Unit = {
    PathTraceGpuSmoke.awaitSamples(tracer, 8)
    tracer.paused = true
    check(kotlin.math.abs(tracer.readLinear()[0]-2f)<1e-5) { "Initial box material hook was not evaluated" }
    val original = tracer.materialModule.current
    owner.setPropertyRaw("pathTracePaint", "invalid GLSL")
    check(!reloadComputeShader(owner))
    check(tracer.materialModule.current === original) { "Failed reload replaced the working pipeline" }
    owner.setPropertyRaw("pathTracePaint", """
        layout(push_constant) uniform PaintPC { float gain; float bias; } pc;
        Material shadeMaterial(Material material, PathTraceSurface surface) {
            material.base.rgb = vec3(0);
            material.emission.rgb = vec3(pc.gain + pc.bias, 0, 0);
            return material;
        }
    """.trimIndent())
    check(reloadComputeShader(owner))
    check(tracer.materialModule.current !== original)
    check(tracer.materialModule.current.ci.pushConstants.single().members.map { it.name } == listOf("gain", "bias"))
    tracer.materialMesh.push["bias"] = 1f
    tracer.paused = false
    delay(100)
    PathTraceGpuSmoke.awaitSamples(tracer, 4)
    tracer.paused = true
    check(kotlin.math.abs(tracer.readLinear()[0]-3f)<1e-5) { "Reload retained old accumulation or failed to preserve push values" }
    println("PASS EDITABLE MATERIAL BOX: seeded templates, includes, reflected push constants, changed ABI, last-valid reload, accumulation reset")
    tracer.close()
}
start(verify)
```

