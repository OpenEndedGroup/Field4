# Field Document: video_one_vr

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · Headset / preview / Field pane

<!-- field-box id="video1_context" index="0" -->

```edn [field-box id="video1_context" role="properties"]
{"id" "video1_context" "index" 0 "name" "1 · Headset / preview / Field pane" "parents" ["root"] "children" ["video1_screen"] "properties" {"children" #{#afield/box "video1_screen"} "parents" #{#afield/box "root"} "name" "1 · Headset / preview / Field pane" "_tombstone" false "frame" #afield/rect [0.0 0.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "video1_context_code_.kt" "lines" 120 "characters" 5121 "sha256" "86e8f77e3a2b9ebbe44dcf751aa6f8fbb5dd505b1ee69d617f432108a10c9e86"}]}
```

```kotlin [field-property box="video1_context" name="code" path="video1_context_code_.kt"]
import afield.graphics.vulkan.targets.*
import afield.graphics.vulkan.util.Options
import afield.media.browser.*

// Change before the first Renderer; switching desktop/OpenXR requires a fresh Field process.
val headsetMode = false
val withFieldBrowser = true // optional Field-in-headset and its desktop companion

Options.enableVR = headsetMode
Options.enableHDR = false // SDR content; high precision is independent of HDR output

prop<Window>(window) = Window("video_one · Field", initialDimensions = Rect(40.0, 50.0, 1100.0, 850.0))
val preview =
    if (withFieldBrowser) Window("video_one · headset preview", initialDimensions = Rect(1180.0, 50.0, 1100.0, 850.0))
    else null
preview?.bestEffortPresentation = true

prop<Renderer>(renderer) = Renderer(window, additionalWindows = listOfNotNull(preview))
val ownedRenderer = renderer

// Bootstrap OpenXR before reading its recommended eye dimensions.
if (headsetMode) {
    renderer.openXRHmdTarget = OpenXRHmdTarget(renderer)
    repeat(8) { if (renderer.openXRHmdTarget?.hasEyeViews == false) renderer.render() }
    check(renderer.openXRHmdTarget?.hasEyeViews == true) { "Connect WiVRn before launching OpenXR mode" }
} else renderer.render()

// The editor and the movie preview are separate desktop windows. In VR the editor is
// another module in the same projection layer, and stays sharp over the movie.
val workspace =
    if (withFieldBrowser)
        FieldVrWorkspace(
            renderer,
            FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
            width = 2560,
            height = 1600,
            readyBoxUid = thisBox.id,
            withDepth = false,
            widthMeters = 1.6f,
            distanceMeters = 1.7f,
        )
    else null

val xr = renderer.openXRHmdTarget
if (xr != null)
    check(xr.highPrecisionSwapchain) {
        "The runtime selected an eight-bit eye format; inspect the offered OpenXR formats before continuing"
    }
val eyeSize = xr?.let { renderer.runOnQueueThread { it.sideBySideRTTParameters() } }
prop<MultiviewStereoTarget>(stereo) =
    workspace?.stereo
        ?: MultiviewStereoTarget(
            renderer,
            eyeWidth = eyeSize?.width?.div(2) ?: 1600,
            eyeHeight = eyeSize?.height ?: 1200,
            openXR = xr,
            withDepth = false,
        )
val ownedStereo = stereo

if (!headsetMode) {
    stereo.stereoCamera.base.at.set(0f, 0f, 0f)
    stereo.stereoCamera.base.target.set(0f, 0f, -1f)
}

workspace?.panel?.panel?.faceToward(vec(-2.3f, 0f, -1.6f), vec(0f, 0f, 0f))

var pairPreview = false
stereo.mirrorToScreen(windowIndex = if (preview != null) 1 else 0, name = "video-preview", stereoPair = { pairPreview })

controlGroup("preview") {
    checkbox("Both eyes side by side", initialValue = false) { pairPreview = it }
    checkbox("Desktop preview enabled", initialValue = true) { (preview ?: window).presentationEnabled = it }
}

// Hiding the panel also releases/drains its input, rather than clicking an invisible editor.
if (workspace != null) {
    val visibleBrowser = checkNotNull(renderer.renderList.preTargets["field-vr-browser"])
    val hiddenBrowser =
        afield.graphics.vulkan.Target { command, _ ->
            workspace.browser.prepareSampledImagesFor(command)
            workspace.headsetDevices?.poll(deliver = false)
        }
    controlGroup("Field browser") {
        checkbox("Show Field pane in headset", initialValue = true) { visible ->
            workspace.input.cancel()
            workspace.grab.cancel()
            workspace.companion.input.releasePointer()
            workspace.headsetDevices?.releasePointer()
            stereo.layered.modules.getValue(workspace.panel.name).current.enabled = visible
            renderer.renderList.preTargets["field-vr-browser"] = if (visible) visibleBrowser else hiddenBrowser
        }
        button("Bring Field pane in front") { down -> if (down) workspace.recenter() }
    }
}

fun eyeFormatName(format: Int?): String = when (format) {
    org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT -> "16-bit float"
    org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM -> "16-bit normalized"
    org.lwjgl.vulkan.VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32,
    org.lwjgl.vulkan.VK10.VK_FORMAT_A2R10G10B10_UNORM_PACK32 -> "10-bit normalized"
    null -> "not connected"
    else -> "8-bit/other (Vulkan format $format)"
}

displayOpen("preview")
displayOpen("Field browser")
display(
    "precision",
    "<div style='padding:12px'>${stereo.eyeWidth} × ${stereo.eyeHeight} per eye · scene format ${eyeFormatName(stereo.layered.parameters.format)} · XR format ${eyeFormatName(xr?.swapchainColorFormat)} · high-precision XR ${xr?.highPrecisionSwapchain ?: "not connected"}. WiVRn stream bit depth must be verified separately.</div>",
)

// Playback owns the render loop. This scope owns windows, browser and eye targets.
loop { frame() }
    .end {
        workspace?.close()
        ownedRenderer.renderList.preTargets.remove("stereo-multiview", ownedStereo)
        ownedRenderer.openXRHmdTarget?.clearEyeSource(ownedStereo.layered)
        ownedRenderer.cleanup()
    }
```

## 2. 2 · Stereo screen placement

<!-- field-box id="video1_screen" index="1" -->

```edn [field-box id="video1_screen" role="properties"]
{"id" "video1_screen" "index" 1 "name" "2 · Stereo screen placement" "parents" ["root" "video1_context"] "children" ["video1_shader" "video1_playback" "video1_accumulation"] "properties" {"children" #{#afield/box "video1_shader" #afield/box "video1_playback" #afield/box "video1_accumulation"} "parents" #{#afield/box "root" #afield/box "video1_context"} "name" "2 · Stereo screen placement" "_tombstone" false "frame" #afield/rect [600.0 0.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "video1_screen_code_.kt" "lines" 90 "characters" 3559 "sha256" "1b2ec479108c145df5b0241db0b606791441fa2a7cf70c8fd5b4baa197b27612"}]}
```

```kotlin [field-property box="video1_screen" name="code" path="video1_screen_code_.kt"]
import afield.graphics.scene.video.*
import afield.ui.output.ControlGroupBuilder
import afield.ui.output.ControlValueHandle

// One movie owns decoding and uploads; box 4 owns its editable graphics pipeline.
prop<StereoVideoSurface>(movie) = StereoVideoSurface(renderer, stereo, automaticDrawing = false)
// Capture this launch's instance for cleanup if the inherited property is replaced.
val ownedMovie = movie

val screen = movie.placement
var screenDistance = 2.5f

// UI handles mirror programmatic recenter/aspect changes without re-firing callbacks.
prop<ControlValueHandle<Double>>(aspectControl)
val poseMirrors = mutableListOf<Pair<ControlValueHandle<Double>, () -> Double>>()

fun ControlGroupBuilder.poseSlider(
    label: String,
    min: Double,
    max: Double,
    value: () -> Double,
    change: (Double) -> Unit,
) {
    poseMirrors += slider(label, min = min, max = max, step = .01, initialValue = value(), onUpdate = change) to value
}

// Flatten the gaze direction: recentering keeps the movie upright, even after a head tilt.
fun recenterScreen() {
    val head = Vector3f()
    val orientation = Quaternionf()
    val forward = vec(0f, 0f, -1f)
    if (renderer.openXRHmdTarget?.headPose(head, orientation) == true) orientation.transform(forward)

    forward.y = 0f
    if (forward.lengthSquared() < 1e-6f) forward.set(0f, 0f, -1f)
    forward.normalize()

    screen.center.set(head).fma(screenDistance, forward)
    screen.rotationDegrees.set(
        0f,
        Math.toDegrees(kotlin.math.atan2(-forward.x.toDouble(), -forward.z.toDouble())).toFloat(),
        0f,
    )

    poseMirrors.forEach { (control, value) -> control.set(value()) }
}
recenterScreen()

controlGroup("screen") {
    slider("Arc width (metres)", min = .2, max = 10.0, step = .01, initialValue = 3.0) {
        screen.widthMeters = it.toFloat()
    }
    slider("Bend (degrees)", min = -160.0, max = 160.0, step = .1, initialValue = 35.0) {
        screen.bendDegrees = it.toFloat()
    }
    aspectControl = slider("Displayed aspect ratio", min = .3, max = 4.0, step = .001, initialValue = screen.aspect.toDouble()) {
        screen.aspect = it.toFloat()
    }
    button("Use native per-eye aspect") { down ->
        if (down) {
            screen.aspect = movie.nativeAspect
            aspectControl.set(screen.aspect.toDouble())
        }
    }
    slider("Recenter distance (metres)", min = .5, max = 10.0, step = .01, initialValue = 2.5) {
        screenDistance = it.toFloat()
    }
    button("Bring screen in front") { down -> if (down) recenterScreen() }
}

controlGroup("room position") {
    poseSlider("Center X (metres)", -10.0, 10.0, { screen.center.x.toDouble() }) { screen.center.x = it.toFloat() }
    poseSlider("Center Y (metres)", -3.0, 5.0, { screen.center.y.toDouble() }) { screen.center.y = it.toFloat() }
    poseSlider("Center Z (metres)", -10.0, 2.0, { screen.center.z.toDouble() }) { screen.center.z = it.toFloat() }
    poseSlider("Yaw (degrees)", -180.0, 180.0, { screen.rotationDegrees.y.toDouble() }) {
        screen.rotationDegrees.y = it.toFloat()
    }
    poseSlider("Pitch (degrees)", -89.0, 89.0, { screen.rotationDegrees.x.toDouble() }) {
        screen.rotationDegrees.x = it.toFloat()
    }
    poseSlider("Roll (degrees)", -180.0, 180.0, { screen.rotationDegrees.z.toDouble() }) {
        screen.rotationDegrees.z = it.toFloat()
    }
}

displayOpen("screen")
displayOpen("room position")

// Transport drives renderer.render(); this box only owns the movie lifetime.
loop { frame() }.end { ownedMovie.close() }
```

## 3. 5 · Temporal accumulation shader

<!-- field-box id="video1_accumulation" index="2" -->

### Narrative

From `code`

This box's vertex / fragment tabs own the video-local accumulation shader.
The movie provides ping-pong images and documented weights; Alt+R reloads the shader.

```edn [field-box id="video1_accumulation" role="properties"]
{"id" "video1_accumulation" "index" 2 "name" "5 · Temporal accumulation shader" "parents" ["root" "video1_screen"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "video1_screen"} "name" "5 · Temporal accumulation shader" "_tombstone" false "frame" #afield/rect [2400.0 0.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "video1_accumulation_code_.kt" "lines" 5 "characters" 252 "sha256" "94a040625df78973854f1be7ab3bfdeccc95c52c45e737900786ec4ea13f1941"} {"name" "vertex" "language" "glsl" "path" "video1_accumulation_vertex_.glsl" "lines" 7 "characters" 232 "sha256" "f59b8aa3fd805c2f809e3cab79d7cad1d729e88e8d99c8ee5c493a7fbc3e41c9"} {"name" "fragment" "language" "glsl" "path" "video1_accumulation_fragment_.glsl" "lines" 38 "characters" 2077 "sha256" "c07d1e8ef21f2093e86a7e8b6efd0e6ed375dad572ecedd9fccbb79ab77a79d4"}]}
```

```kotlin [field-property box="video1_accumulation" name="code" path="video1_accumulation_code_.kt"]
// This box's vertex / fragment tabs own the video-local accumulation shader.
// The movie provides ping-pong images and documented weights; Alt+R reloads the shader.
movie.accumulationModuleFactory = { configuration ->
    moduleHere(configuration)
}
```

```glsl [field-property box="video1_accumulation" name="vertex" path="video1_accumulation_vertex_.glsl"]
#version 450
layout(location = 0) out vec2 uv; // packed image, top-left origin
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = vec2(p.x, 1 - p.y);
    gl_Position = vec4(p * 2 - 1, 0, 1);
}
```

```glsl [field-property box="video1_accumulation" name="fragment" path="video1_accumulation_fragment_.glsl"]
#version 450
layout(binding = 0) uniform sampler2D luma; // full-resolution packed SBS P010 luma, R16_UNORM
layout(binding = 2) uniform sampler2D previousImage; // previous linear-light running mean
layout(binding = 1) uniform sampler2D chroma; // half-resolution interleaved Cb/Cr, R16G16_UNORM
layout(push_constant) uniform AccumulationPush {
    vec4 image; // packed width/height, reserved, reserved
    vec4 colorimetry; // Kr, Kb, full-range flag, reserved
    vec4 weights; // incoming normalized weight, history valid, reserved, reserved
} pc;
layout(location = 0) in vec2 uv; // packed SBS coordinates, top-left origin
layout(location = 0) out vec4 color;
vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + .055) / 1.055, vec3(2.4)), step(vec3(.04045), c));
}
void main() {
    vec2 q = uv;
    int sourceEye = q.x < .5 ? 0 : 1;
    // Bilinear filtering must never sample the other eye across the middle seam.
    vec2 lower = vec2(float(sourceEye) * .5, 0), upper = vec2(float(sourceEye + 1) * .5, 1);
    vec2 qY = clamp(q, lower + .5 / pc.image.xy, upper - .5 / pc.image.xy);
    vec2 qUV = clamp(q, lower + 1 / pc.image.xy, upper - 1 / pc.image.xy);
    // P010 stores 10-bit codes in the high ten bits of normalized 16-bit texels.
    float codeY = texture(luma, qY).r * (65535.0 / 64.0);
    vec2 codeUV = texture(chroma, qUV).rg * (65535.0 / 64.0);
    bool full = pc.colorimetry.z > .5;
    float y = full ? codeY / 1023.0 : (codeY - 64.0) / 876.0;
    vec2 cbcr = (codeUV - 512.0) / (full ? 1023.0 : 896.0);
    float kr = pc.colorimetry.x, kb = pc.colorimetry.y, kg = 1 - kr - kb;
    vec3 rgb = vec3(y + 2 * (1 - kr) * cbcr.y,
    y - 2 * kb * (1 - kb) / kg * cbcr.x - 2 * kr * (1 - kr) / kg * cbcr.y,
    y + 2 * (1 - kb) * cbcr.x);
    // Display-referred SDR code values -> linear; float eyes retain linear light until
    // display/stream encoding.
    vec3 incoming = toLinear(clamp(rgb, 0, 1));
    vec3 previous = pc.weights.y > .5 ? texture(previousImage, qY).rgb : incoming;
    color = vec4(mix(previous, incoming, pc.weights.x), 1);

}
```

## 4. 4 · Video shader

<!-- field-box id="video1_shader" index="3" -->

### Narrative

From `code`

The box's vertex / fragment tabs are the shader source. moduleHere supplies Alt+R,
Update Shader, and typed Kotlin accessors for its documented push constants.

```edn [field-box id="video1_shader" role="properties"]
{"id" "video1_shader" "index" 3 "name" "4 · Video shader" "parents" ["root" "video1_screen"] "children" ["video1_playback"] "properties" {"children" #{#afield/box "video1_playback"} "parents" #{#afield/box "root" #afield/box "video1_screen"} "name" "4 · Video shader" "_tombstone" false "frame" #afield/rect [1800.0 0.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "video1_shader_code_.kt" "lines" 7 "characters" 235 "sha256" "53437949ea2e45560adb2b9ab4e4841e678bd4f42b722e8ae3a37c34a6c30b20"} {"name" "vertex" "language" "glsl" "path" "video1_shader_vertex_.glsl" "lines" 53 "characters" 1996 "sha256" "7ab5a457a6b1a676b37087147f741f097d76e9f54a40512d754bf179dfbf2680"} {"name" "fragment" "language" "glsl" "path" "video1_shader_fragment_.glsl" "lines" 41 "characters" 2359 "sha256" "2c9dd37e2c63c66dbb40175fdbff1b38fc489c06460c3f68100361923a9c5790"}]}
```

```kotlin [field-property box="video1_shader" name="code" path="video1_shader_code_.kt"]
// The box's vertex / fragment tabs are the shader source. moduleHere supplies Alt+R,
// Update Shader, and typed Kotlin accessors for its documented push constants.
val videoModule = moduleHere {
    +movie
}

movie.draw(videoModule)
```

```glsl [field-property box="video1_shader" name="vertex" path="video1_shader_vertex_.glsl"]
#version 450
#extension GL_EXT_multiview : require
layout(std140, set=0, binding=7) uniform StereoViews {
    mat4 stereoVpL; // left-eye projection/view
    mat4 stereoVpR; // right-eye projection/view
    vec4 stereoRightL; // left-eye camera right
    vec4 stereoRightR; // right-eye camera right
    vec4 stereoUpL; // left-eye camera up
    vec4 stereoUpR; // right-eye camera up
    vec4 stereoProjectionL; // projection XY and viewport size
    vec4 stereoProjectionR; // projection XY and viewport size
};

mat4 stereoViewProjection() {
    return gl_ViewIndex==0 ? stereoVpL : stereoVpR;
}
vec3 stereoCameraRight() {
    return (gl_ViewIndex==0 ? stereoRightL : stereoRightR).xyz;
}
vec3 stereoCameraUp() {
    return (gl_ViewIndex==0 ? stereoUpL : stereoUpR).xyz;
}
vec4 stereoProjection() {
    return gl_ViewIndex==0 ? stereoProjectionL : stereoProjectionR;
}
layout(push_constant) uniform MoviePush {
    mat4 model; // screen-local metres to room space
    vec4 surface; // arc width, height, bend radians, strip count
    vec4 image; // packed source width/height, swap eyes, vertical flip
    vec4 colorimetry; // Kr, Kb, full-range flag, reserved
    vec4 accumulation; // processed mix, valid image, reserved, reserved
}
pc;
layout(location = 0) out vec2 uv;
layout(location = 1) flat out int eye;
const vec2 corners[6] =
vec2[6](vec2(0, 0), vec2(0, 1), vec2(1, 1), vec2(0, 0), vec2(1, 1), vec2(1, 0));
void main() {
    int strip = gl_VertexIndex / 6;
    vec2 c = corners[gl_VertexIndex % 6];
    uv = vec2((float(strip) + c.x) / pc.surface.w, c.y);
    float x = (uv.x - .5) * pc.surface.x;
    float z = 0;
    float bend = pc.surface.z;
    if (abs(bend) > 1e-4) {
        float theta = (uv.x - .5) * bend;
        float radius = pc.surface.x / bend;
        x = radius * sin(theta);
        z = radius * 2 * sin(theta * .5) * sin(theta * .5);
    }
    eye = gl_ViewIndex;
    gl_Position = stereoViewProjection() * pc.model * vec4(x, (.5 - uv.y) * pc.surface.y, z, 1);
}
```

```glsl [field-property box="video1_shader" name="fragment" path="video1_shader_fragment_.glsl"]
#version 450
layout(binding = 0) uniform sampler2D luma; // full-resolution packed SBS P010 luma, R16_UNORM
layout(binding = 1) uniform sampler2D chroma; // half-resolution interleaved Cb/Cr, R16G16_UNORM
layout(binding = 2) uniform sampler2D accumulatedImage; // float16 linear-light running mean
layout(push_constant) uniform MoviePush {
    mat4 model; // screen-local metres to room space
    vec4 surface; // arc width, height, bend radians, strip count
    vec4 image; // packed source width/height, swap eyes, vertical flip
    vec4 colorimetry; // Kr, Kb, full-range flag, reserved
    vec4 accumulation; // processed mix, valid image, reserved, reserved
}
pc;
layout(location = 0) in vec2 uv; // per-eye image coordinates, top-left origin
layout(location = 1) flat in int eye; // current output eye: 0 left, 1 right
layout(location = 0) out vec4 color;
vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + .055) / 1.055, vec3(2.4)), step(vec3(.04045), c));
}
void main() {
    int sourceEye = pc.image.z > .5 ? 1 - eye : eye;
    vec2 q = vec2((float(sourceEye) + uv.x) * .5, pc.image.w > .5 ? 1 - uv.y : uv.y);
    // Bilinear filtering must never sample the other eye across the middle seam.
    vec2 lower = vec2(float(sourceEye) * .5, 0), upper = vec2(float(sourceEye + 1) * .5, 1);
    vec2 qY = clamp(q, lower + .5 / pc.image.xy, upper - .5 / pc.image.xy);
    vec2 qUV = clamp(q, lower + 1 / pc.image.xy, upper - 1 / pc.image.xy);
    // P010 stores 10-bit codes in the high ten bits of normalized 16-bit texels.
    float codeY = texture(luma, qY).r * (65535.0 / 64.0);
    vec2 codeUV = texture(chroma, qUV).rg * (65535.0 / 64.0);
    bool full = pc.colorimetry.z > .5;
    float y = full ? codeY / 1023.0 : (codeY - 64.0) / 876.0;
    vec2 cbcr = (codeUV - 512.0) / (full ? 1023.0 : 896.0);
    float kr = pc.colorimetry.x, kb = pc.colorimetry.y, kg = 1 - kr - kb;
    vec3 rgb = vec3(y + 2 * (1 - kr) * cbcr.y,
    y - 2 * kb * (1 - kb) / kg * cbcr.x - 2 * kr * (1 - kr) / kg * cbcr.y,
    y + 2 * (1 - kb) * cbcr.x);
    // Display-referred SDR code values -> linear; float eyes retain linear light until
    // display/stream encoding.
    vec3 sharp = toLinear(clamp(rgb, 0, 1));
    vec3 blurred = pc.accumulation.y > .5 ? texture(accumulatedImage, qY).rgb : sharp;
    color = vec4(mix(sharp, blurred, pc.accumulation.x), 1);
}
```

## 5. 3 · Movie / transport

<!-- field-box id="video1_playback" index="4" -->

```edn [field-box id="video1_playback" role="properties"]
{"id" "video1_playback" "index" 4 "name" "3 · Movie / transport" "parents" ["root" "video1_shader" "video1_screen"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "video1_shader" #afield/box "video1_screen"} "name" "3 · Movie / transport" "_tombstone" false "frame" #afield/rect [1200.0 0.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "video1_playback_code_.kt" "lines" 158 "characters" 6403 "sha256" "57e1a46831caee51e1a1a3db5ebae3030c25a9599d1ce3c158f2a004771eade0"}]}
```

```kotlin [field-property box="video1_playback" name="code" path="video1_playback_code_.kt"]
import afield.ui.output.ControlValueHandle

// A local path, with ~ expansion; changing this field alone does not reopen the decoder.
// The checkout ships an original stereo calibration movie; an environment override selects yours.
var selectedFile =
    System.getenv("FIELD_STEREO_VIDEO") ?: java.io.File("public/docs/assets/stereo_video/stereo-demo.mkv").absolutePath

var scrubber: ControlValueHandle<Double>? = null
var playingControl: ControlValueHandle<Boolean>? = null
var speedControl: ControlValueHandle<Double>? = null
var persistenceControl: ControlValueHandle<Double>? = null
var mixControl: ControlValueHandle<Double>? = null
var eyeSwapControl: ControlValueHandle<Boolean>? = null

// Rebuild only on file change, when duration changes. Handles keep playback ticks out of
// the document edit log; seeks pause, and an explicit Playing gesture resumes.
fun showTransport() {
    displayClear("transport")
    controlGroup("transport") {
        playingControl = checkbox("Playing", initialValue = movie.playing) { if (it) movie.play() else movie.pause() }
        scrubber = slider(
            "Seek (seconds)",
            min = 0.0,
            max = movie.duration.coerceAtLeast(.001),
            step = .01,
            initialValue = 0.0,
        ) {
                movie.seek(it)
            }
        speedControl = slider("Speed", min = .1, max = 8.0, step = .01, initialValue = movie.speed) {
            movie.speed = it
        }
        button("Material: unlimited") { down -> if (down) movie.materialFramesPerSecond = 0 }
        button("Material: 24 fps") { down -> if (down) movie.materialFramesPerSecond = 24 }
        button("Material: 12 fps") { down -> if (down) movie.materialFramesPerSecond = 12 }
        checkbox("Alternate frames above 4x", initialValue = movie.selectiveDecode) { movie.selectiveDecode = it }
        checkbox("Skip non-reference pictures", initialValue = movie.skipNonReferenceFrames) {
            movie.skipNonReferenceFrames = it
        }
        mixControl = slider(
            "Accumulation mix",
            min = 0.0,
            max = 1.0,
            step = .001,
            initialValue = movie.accumulationMix.toDouble(),
        ) {
                movie.accumulationMix = it.toFloat()
            }
        persistenceControl = slider(
            "Persistence half-life (s)",
            min = 0.0,
            max = 10.0,
            step = .01,
            initialValue = movie.accumulationHalfLife,
        ) {
                movie.accumulationHalfLife = it
            }
        slider(
            "Incoming frame weight",
            min = 0.0,
            max = 4.0,
            step = .01,
            initialValue = movie.accumulationInputWeight,
        ) {
            movie.accumulationInputWeight = it
        }
        button("Clear accumulation") { down -> if (down) movie.clearAccumulation() }
        button("1x sharp") { down ->
            if (down) {
                movie.speed = 1.0
                movie.accumulationMix = 0f
                speedControl?.set(1.0)
                mixControl?.set(0.0)
            }
        }
        button("2x sharp") { down ->
            if (down) {
                movie.speed = 2.0
                movie.accumulationMix = 0f
                speedControl?.set(2.0)
                mixControl?.set(0.0)
            }
        }
        button("2x blur") { down ->
            if (down) {
                movie.speed = 2.0
                movie.accumulationMix = 1f
                movie.accumulationHalfLife = 4.0
                speedControl?.set(2.0)
                mixControl?.set(1.0)
                persistenceControl?.set(4.0)
                movie.play()
            }
        }
        button("8x blur") { down ->
            if (down) {
                movie.speed = 8.0
                movie.accumulationMix = 1f
                speedControl?.set(8.0)
                mixControl?.set(1.0)
                movie.play()
            }
        }
        button("Back to start") { down -> if (down) movie.seek(0.0) }
        eyeSwapControl = checkbox("Swap left and right", initialValue = movie.swapEyes) { movie.swapEyes = it }
        button("Swap eyes") { down ->
            if (down) {
                movie.swapEyes = !movie.swapEyes
                eyeSwapControl?.set(movie.swapEyes)
            }
        }
        checkbox("Flip vertically", initialValue = false) { movie.flipVertically = it }
    }
}

fun openMovie() {
    if (movie.load(selectedFile)) {
        aspectControl.set(movie.nativeAspect.toDouble())
        showTransport()
        movie.play()
    }
}

controlGroup("movie file") {
    stringInput("Full SBS file", initialValue = selectedFile) { selectedFile = it }
    button("Load movie") { down -> if (down) openMovie() }
}

// Rerunning the transport refreshes controls without reopening the playing movie.
if (!movie.hasFrame && selectedFile.isNotBlank()) openMovie() else showTransport()

displayOpen("movie file")
displayOpen("transport")
displayOpen("status")

// Render at the runtime cadence; send inexpensive transport/status updates at four Hz.
var lastStatus = 0L

loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()

    val now = System.nanoTime()
    if (now - lastStatus > 250_000_000L) {
        lastStatus = now
        scrubber?.set(movie.timestamp)
        playingControl?.set(movie.playing)

        val label = movie.error ?: movie.path ?: "Choose a full-resolution SBS movie, then Load movie"
        val safe = label.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        display(
            "status",
            "<div style='padding:12px;width:430px'>$safe<br>${movie.width} × ${movie.height} packed · native per-eye aspect ${movie.nativeAspect}<br>${movie.timestamp.f2} / ${movie.duration.f2} seconds · ${if (movie.playing) "playing" else "paused"} · frame ${movie.uploadedFrameIndex}<br>${movie.speed}× · material ${if (movie.materialFramesPerSecond == 0) "uncapped" else "${movie.materialFramesPerSecond} fps"} · stride ${movie.selectedStride} · decode ${movie.decodeMicros / 1000.0} ms · queue drops ${movie.droppedFrames}<br>Material omissions ${movie.omittedMaterialFrames} · accumulated ${movie.accumulationSamples} frames · linear RGBA16F → floating-point eyes. No sound. Playback loops at the end. Scrubbing pauses.</div>",
        )
    }

    frame()
}
```

