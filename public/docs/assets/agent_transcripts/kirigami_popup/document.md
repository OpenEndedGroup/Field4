# Field Document: kirigami_popup

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. path tracing renderer

<!-- field-box id="kirigami_popup_renderer" index="0" -->

```edn [field-box id="kirigami_popup_renderer" role="properties"]
{"id" "kirigami_popup_renderer" "index" 0 "name" "path tracing renderer" "parents" ["root"] "children" ["kirigami_popup_trace"] "properties" {"children" #{#afield/box "kirigami_popup_trace"} "parents" #{#afield/box "root"} "name" "path tracing renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 220.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_popup_renderer_code_.kt" "lines" 10 "characters" 224 "sha256" "5984f57e373dab8121f2d18cf9a76a30452db2e23cc9ace4820d082b0ae5bb9e"}]}
```

```kotlin [field-property box="kirigami_popup_renderer" name="code" path="kirigami_popup_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Kirigami, popped")
    Renderer(window)
}
start(renderer::run)
```

## 2. the popped sheet, path traced

<!-- field-box id="kirigami_popup_trace" index="1" -->

```edn [field-box id="kirigami_popup_trace" role="properties"]
{"id" "kirigami_popup_trace" "index" 1 "name" "the popped sheet, path traced" "parents" ["root" "kirigami_popup_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "kirigami_popup_renderer"} "name" "the popped sheet, path traced" "_tombstone" false "frame" #afield/rect [2400.0 40.0 300.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_popup_trace_code_.kt" "lines" 106 "characters" 3788 "sha256" "2dc9b2eba199c1991b0518637372e0b9a1895661e1d9da989c60016a23acbf52"}]}
```

```kotlin [field-property box="kirigami_popup_trace" name="code" path="kirigami_popup_trace_code_.kt"]
import afield.examples.chain.KirigamiPopUp
import afield.graphics.scene.fabricated.PathTraceEnvironment
import afield.graphics.scene.fabricated.PathTraceMaterial
import afield.graphics.scene.fabricated.PathTraceScene
import afield.graphics.scene.fabricated.PathTracingRenderer
import afield.graphics.scene.fabricated.importTriMesh
import afield.graphics.vulkan.util.Camera
import java.io.File

prop<PathTracingRenderer>(tracer)

val sheet = KirigamiPopUp.sheet(thickness = 0.06)

// Move to a strain in steps of at most 0.25%, each relaxed from the last, so the sheet stays on one branch.
fun stretchTo(target: Double): KirigamiPopUp.State {
    var state = sheet.stretch(sheet.strain)
    while (abs(target - sheet.strain) > 1e-9) {
        state = sheet.stretch(sheet.strain + (target - sheet.strain).coerceIn(-0.0025, 0.0025))
    }
    return state
}

val environment =
    PathTraceEnvironment.fromFile(File("public/docs/assets/fabricate_demo/venice_sunset_1k.exr").absolutePath)

// The tracer's scene is a snapshot, so every new pose is a new tracer under the same name.
fun trace(): PathTracingRenderer {
    val posed = sheet.mesh()
    val scene = PathTraceScene()
    val even = scene.material(PathTraceMaterial.plastic(0.95f, 0.42f, 0.10f, 0.3f))
    val odd = scene.material(PathTraceMaterial.plastic(0.10f, 0.38f, 0.85f, 0.3f))
    val ligament = scene.material(PathTraceMaterial.plastic(0.93f, 0.91f, 0.86f, 0.45f))
    val floor = scene.material(PathTraceMaterial.plastic(0.55f, 0.56f, 0.58f, 0.6f))
    scene.importTriMesh(
        posed.mesh,
        { t ->
            when (posed.parts[t]) {
                KirigamiPopUp.Part.EVEN_TILE -> even
                KirigamiPopUp.Part.ODD_TILE -> odd
                KirigamiPopUp.Part.LIGAMENT -> ligament
            }
        },
    )
    val reach = 30f
    val y = -0.9f
    scene.quad(
        Vector3f(-reach, y, -reach),
        Vector3f(reach, y, -reach),
        Vector3f(reach, y, reach),
        Vector3f(-reach, y, reach),
        floor,
    )
    val camera = Camera(renderer).also {
        it.at.set(8.0f, 8.5f, 11.5f)
        it.target.set(0f, -0.6f, 0.4f)
        it.p.fov = 0.62
    }
    return PathTracingRenderer(renderer, scene, environment, 1200, 736, camera, name = "kirigami-popup")
        .install()
        .also {
            it.samplesPerFrame = 4
            it.maxBounces = 6
            it.exposure = 1.1f
            it.environmentRotation = 2.1f
            it.indirectClamp = 10f
            it.blitToScreen()
        }
}

fun report(state: KirigamiPopUp.State) = markdown(
    "strain **%.2f%%**: mean tile tilt %.1f°, largest %.1f°, stress %.2e E"
        .format(100 * state.strain, state.meanTilt, state.maxTilt, state.stress),
)

// Launching stretches the sheet from flat to 6%, tracing each pose; then it holds. The slider moves it anywhere.
val ramp = (0..24).map { it * 0.0025 }.toMutableList()
var wanted = ramp.last()
slider("stretch (%)", min = 0.0, max = 6.0, step = 0.25, initialValue = 6.0) {
    ramp.clear()
    wanted = it / 100
}

var state = stretchTo(ramp.removeAt(0))
tracer = trace()
report(state)
var converged = false
frames {
    while (true) {
        val next = when {
            ramp.isNotEmpty() && tracer.samples >= 32 -> ramp.removeAt(0)
            ramp.isEmpty() && abs(wanted - sheet.strain) > 1e-9 -> wanted
            else -> null
        }
        if (next != null) {
            state = stretchTo(next)
            tracer = trace()
            report(state)
            converged = false
        }
        if (!converged && ramp.isEmpty() && tracer.samples >= 600) {
            converged = true
            println("popped sheet traced at strain %.2f%%: %d samples".format(100 * sheet.strain, tracer.samples))
        }
        frame()
    }
}
```

## 3. tilt and stress against stretch

<!-- field-box id="kirigami_popup_sweep" index="2" -->

```edn [field-box id="kirigami_popup_sweep" role="properties"]
{"id" "kirigami_popup_sweep" "index" 2 "name" "tilt and stress against stretch" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tilt and stress against stretch" "_tombstone" false "frame" #afield/rect [320.0 40.0 300.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_popup_sweep_code_.kt" "lines" 24 "characters" 1168 "sha256" "50cdec6fc1914fb3324f3fc8117327e0ff5160654bc4ea88973bc97b25356bed"}]}
```

```kotlin [field-property box="kirigami_popup_sweep" name="code" path="kirigami_popup_sweep_code_.kt"]
import afield.examples.chain.KirigamiPopUp

// Three sheets, each stretched through the same strains, every step relaxed from the last.
val strains = (0..40).map { it * 0.001 }
val thicknesses = [0.06, 0.08, 0.12]
val runs = thicknesses.map { t -> t to KirigamiPopUp.sheet(thickness = t).sweep(strains) }

val percent = strains.map { it * 100 }
val tilt =
    Chart(title = "tiles tilt once the sheet buckles", xLabel = "strain (%)", yLabel = "mean tile tilt (degrees)")
val stress = Chart(title = "and the sheet softens", xLabel = "strain (%)", yLabel = "stress / E (x 1000)")
for ((t, states) in runs) {
    val critical = KirigamiPopUp.threshold(states)
    val at4 = states.last()
    println(
        "t = $t: flat until strain %.2f%%, threshold / (t / bridge)^2 = %.4f; at 4%% mean tilt %.1f deg, max %.1f deg"
            .format(100 * critical, critical / ((t / 0.12) * (t / 0.12)), at4.meanTilt, at4.maxTilt),
    )
    tilt.line("t = $t", percent, states.map { it.meanTilt })
    tilt.vrule(100 * critical, "%.2f%%".format(100 * critical))
    stress.line("t = $t", percent, states.map { 1000 * it.stress })
}
display("tilt", tilt)
display("stress", stress)
```

