# Field Document: neural_clouds

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="cloud_renderer" index="0" -->

```edn [field-box id="cloud_renderer" role="properties"]
{"id" "cloud_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["cloud_train"] "properties" {"children" #{#afield/box "cloud_train"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cloud_renderer_code_.kt" "lines" 12 "characters" 265 "sha256" "0142566f5745b83d8a058b6bb1489af3fb3cc4a2d7bf6815a14d7bcb19ae3dd3"}]}
```

```kotlin [field-property box="cloud_renderer" name="code" path="cloud_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Neural clouds")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. train the in-scatter network

<!-- field-box id="cloud_train" index="1" -->

```edn [field-box id="cloud_train" role="properties"]
{"id" "cloud_train" "index" 1 "name" "train the in-scatter network" "parents" ["root" "cloud_renderer"] "children" ["cloud_compare" "cloud_view"] "properties" {"children" #{#afield/box "cloud_compare" #afield/box "cloud_view"} "parents" #{#afield/box "root" #afield/box "cloud_renderer"} "name" "train the in-scatter network" "_tombstone" false "frame" #afield/rect [380.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cloud_train_code_.kt" "lines" 28 "characters" 1144 "sha256" "e94e108329493ccafda392103f8838d915092cf7b83295a45d3ce3c331cb6b1d"}]}
```

```kotlin [field-property box="cloud_train" name="code" path="cloud_train_code_.kt"]
import afield.graphics.volume.*

// Published for the boxes below.
prop<CloudScene>(cloudScene)
prop<NeuralCloud>(cloudNet)
prop<AmbientFit>(cloudAmbient)

cloudScene = CloudScene()

// Training points are drawn where these cameras see cloud. cloud_compare's view is not one of them.
val cameras = [
    CloudScene.Camera(doubleArrayOf(0.0, 2.2, 7.5), doubleArrayOf(0.0, 1.95, 0.0)),
    CloudScene.Camera(doubleArrayOf(-5.5, 3.4, 4.5), doubleArrayOf(0.0, 1.9, 0.0)),
    CloudScene.Camera(doubleArrayOf(4.0, 1.6, -6.0), doubleArrayOf(0.0, 2.0, 0.0)),
]

// Each label is the mean of 8 path-traced estimates: noisy, and the network averages the noise out.
val samples = NeuralCloud.samples(cloudScene, cameras, count = 12000, pathsPerSample = 8)
cloudNet = NeuralCloud.train(samples, hidden = intArrayOf(16, 16), steps = 8000)
cloudAmbient = NeuralCloud.ambient(samples)

// #include "cloud/network" and "cloud/scene" in this box's descendants now mean these.
cloudHere(cloudNet, cloudAmbient, cloudScene)

display(
    "training",
    "${samples.size} samples, ${cloudNet.weights}, loss ${cloudNet.trainingLoss.takeLast(200).average().f4}",
)
```

## 3. compare against the path tracer

<!-- field-box id="cloud_compare" index="2" -->

```edn [field-box id="cloud_compare" role="properties"]
{"id" "cloud_compare" "index" 2 "name" "compare against the path tracer" "parents" ["root" "cloud_train"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cloud_train"} "name" "compare against the path tracer" "_tombstone" false "frame" #afield/rect [380.0 200.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cloud_compare_code_.kt" "lines" 31 "characters" 1422 "sha256" "cf7d440f3636b41163562e1fbd3938ffe766611eabf6e62c95e91d7fd5f3a5a7"}]}
```

```kotlin [field-property box="cloud_compare" name="code" path="cloud_compare_code_.kt"]
import afield.graphics.volume.*
import java.io.File
import java.util.Base64

val view = CloudScene.Camera(doubleArrayOf(4.6, 0.9, 3.6), doubleArrayOf(0.0, 2.05, 0.0), 40.0)
val w = 200
val h = 125
val none = CloudScene.InScatter { _, _, _, _, into -> into.fill(0.0) }

val reference = cloudScene.render(view, w, h, cloudScene.reference(48))
val single = cloudScene.render(view, w, h, none)
val ambient = cloudScene.render(view, w, h, cloudAmbient)
val neural = cloudScene.render(view, w, h, cloudNet.inScatter)

// Pixels the cloud touches: where an absurd in-scatter changes the picture.
val mask = cloudScene.render(view, w, h, CloudScene.InScatter { _, _, _, _, into -> into.fill(1e3) })

val file = File.createTempFile("neural_clouds", ".png")
writeCloudComparison(file, reference, [single, ambient, neural])
val png = Base64.getEncoder().encodeToString(file.readBytes())
val rows =
    ["single scatter" to single, "fitted ambient" to ambient, "network" to neural].joinToString("") { (name, image) ->
        val e = cloudError(image, reference, mask)
        "<tr><td>$name</td><td>${e.relativeMeanAbs.f3}</td><td>${e.rmse.f3}</td></tr>"
    }
display(
    "compare",
    "<img style='width:100%' src='data:image/png;base64,$png'>" +
        "<div>reference | single scatter | fitted ambient | network; below, |error| x4</div>" +
        "<table><tr><th></th><th>relative MAE</th><th>RMSE</th></tr>$rows</table>",
)
```

## 4. neural cloud shader

<!-- field-box id="cloud_view" index="3" -->

### Narrative

From `code`

A fullscreen triangle; the fragment shader does all the work.

```edn [field-box id="cloud_view" role="properties"]
{"id" "cloud_view" "index" 3 "name" "neural cloud shader" "parents" ["root" "cloud_train"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "cloud_train"} "name" "neural cloud shader" "_tombstone" false "frame" #afield/rect [740.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cloud_view_code_.kt" "lines" 8 "characters" 228 "sha256" "7fb3294fb296610d1ac6c7c4ad49378f085eeaa283cccfc9d6c7de27cb6711f4"} {"name" "vertex" "language" "glsl" "path" "cloud_view_vertex_.glsl" "lines" 7 "characters" 196 "sha256" "70608faa9d5a10444a303a98fe7634c1e2bd82aeed98094381d7fc60349f3911"} {"name" "fragment" "language" "glsl" "path" "cloud_view_fragment_.glsl" "lines" 25 "characters" 1031 "sha256" "eb4130b11c61fde0beba91499cb58494467c706a258b97766d07c48eeb6b26e3"}]}
```

```kotlin [field-property box="cloud_view" name="code" path="cloud_view_code_.kt"]
// A fullscreen triangle; the fragment shader does all the work.
val module = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
module += "fullscreen" to fullscreenTriangle(module)
renderer += module
```

```glsl [field-property box="cloud_view" name="vertex" path="cloud_view_vertex_.glsl"]
#version 460
layout(location=0) out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = vec2(p.x, 1 - p.y);
    gl_Position = vec4(p * 2 - 1, 0, 1);
}
```

```glsl [field-property box="cloud_view" name="fragment" path="cloud_view_fragment_.glsl"]
#version 460
// cloudNetwork / cloudAmbient (weights as constants) and the raymarcher, from cloud_train
#include "cloud/network"
#include "cloud/scene"
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;

void main() {
    // Top: fitted ambient | network | single scatter.
    // Bottom: predicted in-scatter | sun transmittance | sky visibility.
    vec2 grid = vec2(uv.x * 3.0, uv.y * 2.0);
    vec2 st = fract(grid);
    int panel = int(grid.y) * 3 + int(grid.x);
    int modes[6] = int[](1, 2, 0, 3, 4, 5);
    int mode = modes[min(panel, 5)];

    // window aspect from the pixel footprint, then one panel's share of it
    float aspect = fwidth(uv.y) / fwidth(uv.x) * 2.0 / 3.0;
    vec2 ndc = vec2(st.x * 2.0 - 1.0, 1.0 - st.y * 2.0);
    vec3 c = cloudRender(vec3(4.6, 0.9, 3.6), vec3(0.0, 2.05, 0.0), 40.0, ndc, aspect, mode, gl_FragCoord.xy);

    if (mode < 3) c = 1.0 - exp(-c * 1.2); // the tone curve writeCloudComparison uses
    if (min(st.x, st.y) < 0.004) c = vec3(0.0);
    color = vec4(c, 1.0);
}
```

