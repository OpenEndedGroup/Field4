# Field Document: path_tracing_medium

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isDatalogRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. path tracing renderer

<!-- field-box id="pt_renderer" index="0" -->

```edn [field-box id="pt_renderer" role="properties"]
{"id" "pt_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["pt_medium"] "properties" {"children" #{#afield/box "pt_medium"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_renderer_code_.kt" "lines" 9 "characters" 216 "sha256" "2b12b11e18a24ddf4b4f160c4d1d141125cbcbb7c121734b6290ecf58ced8a17"}]}
```

```kotlin [field-property box="pt_renderer" name="code" path="pt_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · participating medium")
    Renderer(window)
}
start(renderer::run)
```

## 2. shafts of light through haze

<!-- field-box id="pt_medium" index="1" -->

```edn [field-box id="pt_medium" role="properties"]
{"id" "pt_medium" "index" 1 "name" "shafts of light through haze" "parents" ["root" "pt_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pt_renderer"} "name" "shafts of light through haze" "_tombstone" false "frame" #afield/rect [320.0 140.0 300.0 90.0] "preamble" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pt_medium_code_.kt" "lines" 78 "characters" 3954 "sha256" "e16af50dcc57bd42586b9ca607a522914ba8957becc4de0f66947b159bf0a64f"}]}
```

```kotlin [field-property box="pt_medium" name="code" path="pt_medium_code_.kt"]
import afield.four.fabricated.PathTraceEnvironment
import afield.four.fabricated.PathTraceMaterial
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.PathTracingRenderer
import afield.four.modules.fromContext
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

val renderer: Renderer = fromContext()
prop<PathTracingRenderer>(tracer)

// A bright strip light with four slats under it, and nothing else lit. Everything is
// extruded along z and the camera looks down z, so the light's fan is seen in
// cross-section: shafts, and shadows between them.
val scene = PathTraceScene().apply {
    val floor = material(PathTraceMaterial.diffuse(0.32f, 0.30f, 0.27f))
    val wall = material(PathTraceMaterial.diffuse(0.16f, 0.17f, 0.21f))
    val slat = material(PathTraceMaterial.diffuse(0.10f, 0.09f, 0.08f))
    val lamp = material(PathTraceMaterial.emissive(340f, 300f, 230f))
    val ball = material(PathTraceMaterial.plastic(0.78f, 0.76f, 0.72f, 0.30f))
    quad(Vector3f(-6f, 0f, -4f), Vector3f(-6f, 0f, 4f), Vector3f(6f, 0f, 4f), Vector3f(6f, 0f, -4f), floor)
    quad(Vector3f(-6f, 0f, -4f), Vector3f(-6f, 5.4f, -4f), Vector3f(6f, 5.4f, -4f), Vector3f(6f, 0f, -4f), wall)
    quad(Vector3f(-0.16f, 4.9f, -3.6f), Vector3f(0.16f, 4.9f, -3.6f),
        Vector3f(0.16f, 4.9f, 3.6f), Vector3f(-0.16f, 4.9f, 3.6f), lamp)
    for (x in listOf(-1.75f, -0.62f, 0.62f, 1.75f)) {
        quad(Vector3f(x - 0.26f, 3.5f, -3.6f), Vector3f(x + 0.26f, 3.5f, -3.6f),
            Vector3f(x + 0.26f, 3.5f, 3.6f), Vector3f(x - 0.26f, 3.5f, 3.6f), slat)
    }
    sphere(Vector3f(1.5f, 0.85f, 0.6f), 0.85f, ball, 40, 20)
}
val camera = Camera(renderer).also {
    it.at.set(0.15f, 2.35f, 9.2f); it.target.set(0.15f, 2.25f, 0f); it.p.fov = 0.82
}
tracer = PathTracingRenderer(renderer, scene,
    PathTraceEnvironment.constant(0f), 800, 500, camera, name = "medium-demo")
tracer.maxBounces = 8
tracer.samplesPerFrame = 2
tracer.showEnvironment = false
tracer.background = Vector4f(0f, 0f, 0f, 1f)
tracer.exposure = 0.22f

// ---- the medium. Bounds matter: an unbounded medium has zero transmittance to
// ---- infinity, which would make an environment map unreachable. Here the box is
// ---- the room, so rays leaving it are simply out of the haze.
tracer.mediumBounds = Vector3f(-6f, 0f, -3.9f) to Vector3f(6f, 5.2f, 3.9f)
tracer.mediumAlbedo = Vector3f(0.96f, 0.95f, 0.93f)
tracer.mediumAnisotropy = 0.4f
tracer.mediumDensity = 0.16f

tracer.install()
tracer.blitToScreen()
println("${tracer.emitterCount} emissive triangles of ${tracer.triangleCount}; " +
    "the medium is ${tracer.mediumDensity} per world unit, albedo ${tracer.mediumAlbedo}, " +
    "g ${tracer.mediumAnisotropy}")

// ---- MOVE THIS ONE PARAMETER: mediumDensity. -----------------------------------
// The loop cycles it every 192 samples and prints the value. At 0.0 you get the room
// with hard-edged pools of light on the floor and nothing in the air. As it rises the
// shafts between the slats appear, the lamp gains a halo, and the far wall goes soft.
// Past about 0.35 the haze starts to swallow the geometry -- and because every collision
// spends one of maxBounces, the image also darkens there unless you raise it.
//
// Try mediumAnisotropy next: 0.8 pulls the glow into a tight forward halo around the
// lamp, -0.8 throws it back toward the camera and flattens the shafts.
// Comment out the frames block to hold one setting.
frames {
    while (true) {
        for (density in listOf(0.0f, 0.08f, 0.16f, 0.40f)) {
            tracer.mediumDensity = density
            println("mediumDensity = $density")
            // Changing it resets accumulation, but not until the next frame is recorded,
            // so wait for the old budget to fall away before waiting for the new one.
            while (tracer.samples >= 192) frame()
            while (tracer.samples < 192) frame()
        }
    }
}
```

