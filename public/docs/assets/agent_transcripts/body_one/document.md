# Field Document: body_one

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · Headset / preview / Field pane

<!-- field-box id="body1_context" index="0" -->

```edn [field-box id="body1_context" role="properties"]
{"id" "body1_context" "index" 0 "name" "1 · Headset / preview / Field pane" "parents" ["root"] "children" ["body1_drawing"] "properties" {"children" #{#afield/box "body1_drawing"} "parents" #{#afield/box "root"} "name" "1 · Headset / preview / Field pane" "_tombstone" false "frame" #afield/rect [0.0 0.0 500.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "body1_context_code_.kt" "lines" 116 "characters" 4684 "sha256" "7f0483b54240b1ffe74ccd5904428beafaba626b0708177b503e48b6367bdf17"}]}
```

```kotlin [field-property box="body1_context" name="code" path="body1_context_code_.kt"]
import afield.graphics.vulkan.util.Options
import afield.media.browser.*

// Renderer startup. Change before the first Renderer; switching desktop/OpenXR requires a fresh Field process.
val headsetMode = true
val withFieldBrowser = true // optional Field-in-headset and its desktop companion
Options.enableVR = headsetMode
Options.enableHDR = false // SDR content; high precision is independent of HDR output
prop<Window>(window) = Window("body_one · Field", initialDimensions = Rect(40.0, 50.0, 1100.0, 850.0))
val preview =
    if (withFieldBrowser) Window("body_one · headset preview", initialDimensions = Rect(1180.0, 50.0, 1100.0, 850.0))
    else null
preview?.bestEffortPresentation = true
prop<Renderer>(renderer) = Renderer(window, additionalWindows = if (preview != null) [preview] else [])
val ownedRenderer = renderer
if (headsetMode) {
    renderer.openXRHmdTarget = OpenXRHmdTarget(renderer)
    repeat(8) { if (renderer.openXRHmdTarget?.hasEyeViews == false) renderer.render() }
    check(renderer.openXRHmdTarget?.hasEyeViews == true) { "Connect WiVRn before launching OpenXR mode" }
} else renderer.render()
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

// Stereo target and native mirror. Both reuse the current predicted XR views.
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
stereo.mirrorToScreen(windowIndex = if (preview != null) 1 else 0, name = "body-preview", stereoPair = { pairPreview })

// Desktop preview and browser visibility.
controlGroup("preview") {
    checkbox("Both eyes side by side", initialValue = false) { pairPreview = it }
    checkbox("Desktop preview enabled", initialValue = true) { renderer.desktopPresentationEnabled = it }
}
if (workspace != null) {
    val visibleBrowser = renderer.renderList.preTargets["field-vr-browser"]!!
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
markdown(
    "precision",
    """
    ### Eye images

    ${stereo.eyeWidth} × ${stereo.eyeHeight} per eye.

    | Stage | Format |
    |---|---|
    | Scene | ${eyeFormatName(stereo.layered.parameters.format)} |
    | OpenXR | ${eyeFormatName(xr?.swapchainColorFormat)} |

    WiVRn stream bit depth is selected separately in the headset.
""",
    width = 560,
)

// Own the workspace and renderer for this launch.

loop { frame() }
    .end {
        workspace?.close()
        ownedRenderer.renderList.preTargets.remove("stereo-multiview", ownedStereo)
        ownedRenderer.openXRHmdTarget?.clearEyeSource(ownedStereo.layered)
        ownedRenderer.cleanup()
    }
```

## 2. 2 · Tracking markers and connections

<!-- field-box id="body1_drawing" index="1" -->

```edn [field-box id="body1_drawing" role="properties"]
{"id" "body1_drawing" "index" 1 "name" "2 · Tracking markers and connections" "parents" ["root" "body1_context"] "children" ["body1_tracking"] "properties" {"children" #{#afield/box "body1_tracking"} "parents" #{#afield/box "root" #afield/box "body1_context"} "name" "2 · Tracking markers and connections" "_tombstone" false "frame" #afield/rect [600.0 0.0 500.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "body1_drawing_code_.kt" "lines" 39 "characters" 1507 "sha256" "db030f2bf3eea2bf94d1983fd2d7781300872756e12532789d0fdbb894ab35a9"} {"name" "vertex" "language" "glsl" "path" "body1_drawing_vertex_.glsl" "lines" 52 "characters" 1925 "sha256" "da4fb84e08efc0ffbe43960894b7c2e2abb080cabd49536892f2a049e116f035"} {"name" "fragment" "language" "glsl" "path" "body1_drawing_fragment_.glsl" "lines" 13 "characters" 504 "sha256" "7eb68433c9497fd4c424e92c222ca427c8ee61e30584bcecaf428f3afa664639"}]}
```

```kotlin [field-property box="body1_drawing" name="code" path="body1_drawing_code_.kt"]
import org.lwjgl.vulkan.VK10.*

// Pipeline linkage. StereoViews supplies this frame's predicted view/projection for each eye.
val trackingModule = moduleHere {
    stereo.configure(this)
    +stereo.layered
    depthTest = false
    depthWrite = false
    blend = true
    srcColor = VK_BLEND_FACTOR_SRC_ALPHA
    dstColor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
}

// Small CPU-driven scene: push constants carry every marker/segment, no particle runtime.
prop<MutableList<Array<Vector4f>>>(trackingMarks) = MutableList(384) {
    arrayOf(vec(0f, 0f, 0f, .005f), vec(0f, 0f, 0f, 1f), vec(1f, 1f, 1f, 0f))
}

// Marker ABI: [0] start/centre + radius, [1] end + shape, [2] linear RGBA.
// This shared property is looked up by the tracking box on each access.
for ((index, mark) in trackingMarks.withIndex()) {
    val geometry = Mesh()
    trackingModule += "tracking mark $index" to geometry
    geometry.resize(6, 0)
    geometry.push.bind("a") { mark[0] } // world start/centre, width in metres
    geometry.push.bind("b") { mark[1] } // world end, 1 = circle / 0 = strip
    geometry.push.bind("tint") { mark[2] } // linear RGBA; zero alpha hides the mark
}

// Attach explicitly: configuring a target alone does not add a draw module.
stereo.layered += "body-tracking" to trackingModule
trackingModule.current.enabled = true

// Retire the attachment with this box.
loop { frame() }
    .end {
        trackingModule.disable()
        stereo.layered.modules.remove("body-tracking", trackingModule)
    }
```

```glsl [field-property box="body1_drawing" name="vertex" path="body1_drawing_vertex_.glsl"]
#version 460
#extension GL_EXT_multiview : require

// The shared eye buffer is supplied by MultiviewStereoTarget.configure.
layout(std140, set = 0, binding = 7) uniform StereoViews {
    mat4 stereoVpL; // left-eye view/projection
    mat4 stereoVpR; // right-eye view/projection
    vec4 stereoRightL; // left-eye world right
    vec4 stereoRightR; // right-eye world right
    vec4 stereoUpL; // left-eye world up
    vec4 stereoUpR; // right-eye world up
    vec4 stereoProjectionL; // left projection scale and raster size
    vec4 stereoProjectionR; // right projection scale and raster size
};

layout(push_constant) uniform Mark {
    vec4 a; // world start/centre, radius or half-width in metres
    vec4 b; // world end, 1 = circular joint / 0 = segment
    vec4 tint; // linear RGBA; zero alpha hides unavailable observations
} pc;

layout(location = 0) out vec2 uv; // local square coordinates for analytic coverage
layout(location = 1) flat out vec4 ink; // linear RGBA
layout(location = 2) flat out float roundMark; // 1 = circle / 0 = strip

const vec2 corners[6] = vec2[](
vec2(-1,-1), vec2(1,-1), vec2(1,1),
vec2(-1,-1), vec2(1,1), vec2(-1,1)
);

void main() {
    uv = corners[gl_VertexIndex];
    ink = pc.tint;
    roundMark = pc.b.w;
    vec3 right = (gl_ViewIndex == 0 ? stereoRightL : stereoRightR).xyz;
    vec3 up = (gl_ViewIndex == 0 ? stereoUpL : stereoUpR).xyz;
    vec3 p;

    // Expand on the GPU, independently for the current eye.
    if (pc.b.w > 0.5) {
        p = pc.a.xyz + (right * uv.x + up * uv.y) * pc.a.w;
    }
    else {
        vec3 d = pc.b.xyz - pc.a.xyz;
        vec3 side = cross(d, cross(right, up));
        side = dot(side, side) > 1e-12 ? normalize(side) : right;
        p = mix(pc.a.xyz, pc.b.xyz, uv.x * 0.5 + 0.5) + side * uv.y * pc.a.w;
    }
    gl_Position = pc.tint.a > 0.0
    ? (gl_ViewIndex == 0 ? stereoVpL : stereoVpR) * vec4(p, 1)
    : vec4(0, 0, -1, 1);
}
```

```glsl [field-property box="body1_drawing" name="fragment" path="body1_drawing_fragment_.glsl"]
#version 460

layout(location = 0) in vec2 uv; // local square coordinates
layout(location = 1) flat in vec4 ink; // linear RGBA
layout(location = 2) flat in float roundMark; // circle or strip coverage
layout(location = 0) out vec4 outColor; // straight alpha for the module's blend mode

void main() {
    float d = roundMark > 0.5 ? length(uv) : abs(uv.y);
    float aa = max(fwidth(d), 1e-5);
    float coverage = 1.0 - smoothstep(1.0 - aa, 1.0, d);
    outColor = vec4(ink.rgb, ink.a * coverage);
}
```

## 3. 3 · Tracking / body queries

<!-- field-box id="body1_tracking" index="2" -->

```edn [field-box id="body1_tracking" role="properties"]
{"id" "body1_tracking" "index" 2 "name" "3 · Tracking / body queries" "parents" ["root" "body1_drawing"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "body1_drawing"} "name" "3 · Tracking / body queries" "_tombstone" false "frame" #afield/rect [1200.0 0.0 500.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "body1_tracking_code_.kt" "lines" 288 "characters" 12556 "sha256" "7b769b71abf5920c15d986d6f7ab2fb1293cd924425f93fa31871eb4667f5703"}]}
```

```kotlin [field-property box="body1_tracking" name="code" path="body1_tracking_code_.kt"]
import afield.graphics.scene.body.*
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.targets.OpenXRHmdTarget.*

// Shared state and artistic controls. All positions and lengths are in world-space metres.
val xr = renderer.openXRHmdTarget!!
var showHands = true
var showBody = true
var showControllers = true
var showAxes = true
var showBones = true
var inspectionCopy = true
var markerRadius = .006f
var lineWidth = .0015f
var axisLength = .035f
val inspectionOffset = vec(0f, 0f, -1.6f)
val headPosition = vec(0f, 0f, 0f)
val headOrientation = Quaternionf()

// Inspection is a translation of the drawing; raw observations remain unchanged.
fun recenterInspection() {
    if (xr.headPose(headPosition, headOrientation)) {
        inspectionOffset.set(0f, 0f, -1.6f)
        headOrientation.transform(inspectionOffset)
        inspectionOffset.y = 0f
    }
}
recenterInspection()

// View controls. These do not change the observations supplied by the runtime.
controlGroup("tracking view") {
    checkbox("Inspection copy ahead of me", initialValue = true) { inspectionCopy = it }
    button("Recenter inspection copy") { down -> if (down) recenterInspection() }
    checkbox("Hands", initialValue = true) { showHands = it }
    checkbox("Body / raw trackers", initialValue = true) { showBody = it }
    checkbox("Controllers", initialValue = true) { showControllers = it }
    checkbox("Joint orientation axes", initialValue = true) { showAxes = it }
    checkbox("Connections between available joints", initialValue = true) { showBones = it }
    slider("Joint radius (metres)", min = .002, max = .03, step = .001, initialValue = .006) {
        markerRadius = it.toFloat()
    }
    slider("Line half-width (metres)", min = .0005, max = .006, step = .0005, initialValue = .0015) {
        lineWidth = it.toFloat()
    }
    slider("Axis length (metres)", min = .01, max = .15, step = .005, initialValue = .035) { axisLength = it.toFloat() }
}

// Geometric queries. Radii zero reduce the volume model to joint centres and limb segments.
prop<BodyGeometry>(trackedBody) = xr.bodyGeometry()
var showBounds = true
var showClosestPoint = true
var roundedQueries = true
var includeHead = true
var headRadius = .09f
var limbRadius = .04f
var queryHit: BodyHit? = null
var queryJoint: BodyHit? = null
val queryWorld = vec(0f, 0f, -1f)
controlGroup("body queries") {
    checkbox("Show body bounds", initialValue = true) { showBounds = it }
    checkbox("Show closest point", initialValue = true) { showClosestPoint = it }
    checkbox("Head sphere / limb cylinders", initialValue = true) { roundedQueries = it }
    checkbox("Include head in bounds and queries", initialValue = true) { includeHead = it }
    slider("Head sphere radius (metres)", min = .02, max = .3, step = .005, initialValue = .09) {
        headRadius = it.toFloat()
    }
    slider("Limb cylinder radius (metres)", min = .005, max = .2, step = .005, initialValue = .04) {
        limbRadius = it.toFloat()
    }
}

// Marker construction and Markdown observation reporting.
val rows = mutableListOf<String>()
var used = 0

fun xyz(p: Vector3f) = "${p.x.f2}, ${p.y.f2}, ${p.z.f2}"

fun mark(a: Vector3f, b: Vector3f, color: Vector4f, radius: Float, circular: Boolean) {
    check(used < trackingMarks.size) { "Increase the tracking mark pool" }
    val m = trackingMarks[used++]
    val offset = if (inspectionCopy) inspectionOffset else vec(0f, 0f, 0f)
    m[0].set(a.x + offset.x, a.y + offset.y, a.z + offset.z, radius)
    m[1].set(b.x + offset.x, b.y + offset.y, b.z + offset.z, if (circular) 1f else 0f)
    m[2].set(color)
}

fun segment(a: JointPose?, b: JointPose?, color: Vector4f) {
    if (showBones && a?.positionValid == true && b?.positionValid == true)
        mark(a.position, b.position, color, lineWidth, false)
}

fun reportSection(title: String) {
    rows +=
        "\n### $title\n\n| Observation | Position | Orientation | World XYZ (m) | Radius (m) |\n|---|---|---|---|---|"
}

fun joint(label: String, pose: JointPose?, color: Vector4f, visible: Boolean = true) {
    val valid = pose?.positionValid == true
    rows +=
        "| $label | ${if (valid) "valid" else "unavailable"} | ${if (pose?.orientationValid == true) "valid" else "—"} | ${if (valid) xyz(pose!!.position) else "—"} | ${if (valid) pose!!.radius.f3 else "—"} |"
    if (!valid || !visible) return
    val p = pose!!
    mark(p.position, p.position, color, markerRadius, true)
    if (showAxes && p.orientationValid) {
        val axes: List<Pair<Vector3f, Vector4f>> = [
            vec(axisLength, 0f, 0f) to vec(1f, .15f, .1f, .8f),
            vec(0f, axisLength, 0f) to vec(.15f, 1f, .2f, .8f),
            vec(0f, 0f, axisLength) to vec(.15f, .4f, 1f, .8f),
        ]
        for ((direction, tint) in axes) {
            p.orientation.transform(direction).add(p.position)
            mark(p.position, direction, tint, lineWidth * .6f, false)
        }
    }
}

// OpenXR hand order: wrist → metacarpal → finger joints → tip; palm joins the wrist.
val handChains: List<List<Int>> = [
    [1, 2, 3, 4, 5],
    [1, 6, 7, 8, 9, 10],
    [1, 11, 12, 13, 14, 15],
    [1, 16, 17, 18, 19, 20],
    [1, 21, 22, 23, 24, 25],
    [0, 1],
]

// The right palm/controller is the query; without either, use a point ahead of the head.
// Query and result markers use the same optional inspection offset as the skeleton.
fun updateBodyQueries() {
    trackedBody = xr.bodyGeometry()
    val hand = xr.handState(Hand.RIGHT)
    val controller = xr.controllerState(Hand.RIGHT)
    val palm = hand?.get(HandJoint.PALM)
    when {
        hand?.isActive == true && palm?.positionValid == true -> queryWorld.set(palm.position)
        controller.poseValid -> queryWorld.set(controller.position)
        else -> {
            queryWorld.set(0f, 0f, -.6f)
            headOrientation.transform(queryWorld).add(headPosition)
        }
    }
    val lr = if (roundedQueries) limbRadius else 0f
    val hr = if (roundedQueries) headRadius else 0f
    queryHit = trackedBody.closestPoint(queryWorld, lr, hr, includeHead)
    queryJoint = trackedBody.closestJoint(queryWorld)
    if (showClosestPoint) {
        mark(queryWorld, queryWorld, vec(1f, .2f, .8f, 1f), markerRadius * 1.5f, true)
        queryHit?.let { hit ->
            mark(hit.point, hit.point, vec(.3f, 1f, .2f, 1f), markerRadius * 1.5f, true)
            mark(queryWorld, hit.point, vec(.3f, 1f, .2f, .8f), lineWidth, false)
        }
    }
    if (showBounds)
        trackedBody.bounds(lr, hr, includeHead)?.let { bounds ->
            val corners =
                (0..7).map { i ->
                    vec(
                        if (i and 1 == 0) bounds.min.x else bounds.max.x,
                        if (i and 2 == 0) bounds.min.y else bounds.max.y,
                        if (i and 4 == 0) bounds.min.z else bounds.max.z,
                    )
                }
            for (i in 0..7) for (axis in 0..2) {
                val bit = 1 shl axis
                if (i and bit == 0)
                    mark(corners[i], corners[i or bit], vec(.55f, .65f, .8f, .55f), lineWidth * .6f, false)
            }
        }
}

var lastReport = 0L

// Sample after XR locates the eyes, before recording the stereo target.
val updateTracking = Target { _, _ ->
    rows.clear()
    used = 0
    reportSection("Head")
    val headValid = xr.headPose(headPosition, headOrientation)
    joint("HEAD", JointPose(headPosition, headOrientation, 0f, headValid, headValid), vec(1f, 1f, 1f, .8f))
    for (hand in Hand.entries) {
        val tint = if (hand == Hand.LEFT) vec(.2f, .65f, 1f, .9f) else vec(1f, .6f, .15f, .9f)
        val state = xr.handState(hand)
        reportSection(
            "$hand hand · active=${state?.isActive ?: false} · pinch=${state?.pinchStrength?.f2 ?: "unavailable"}",
        )
        for (j in HandJoint.entries) joint("$hand $j", state?.get(j), tint, showHands && state?.isActive == true)
        if (showHands && state?.isActive == true)
            for (chain in handChains) for ((a, b) in chain.zipWithNext()) segment(
                state.joints[a],
                state.joints[b],
                tint,
            )
        val controller = xr.controllerState(hand)
        rows +=
            "\n$hand controller: active=${controller.active}, trigger=${controller.triggerValue.f2}, squeeze=${controller.squeezeValue.f2}, stick=(${controller.thumbstickX.f2}, ${controller.thumbstickY.f2}), A=${controller.aPressed}, B=${controller.bPressed}, menu=${controller.menuPressed}\n"
        reportSection("$hand controller pose")
        joint(
            "$hand controller grip",
            JointPose(controller.position, controller.rotation, 0f, controller.poseValid, controller.poseValid),
            vec(.9f, .35f, 1f, .9f),
            showControllers,
        )
    }
    val body = xr.bodyState()
    reportSection("Body · active=${body?.isActive ?: false} · raw trackers=${xr.bodyTrackerCount()}")
    val bodyTint = vec(.8f, 1f, .85f, .9f)
    for (j in BodyJoint.entries) joint(j.name, body?.get(j), bodyTint, showBody)
    for ((a, b) in BodyGeometry.CONNECTIONS) if (showBody) segment(body?.get(a), body?.get(b), bodyTint)

    // WiVRn reports lower-arm joints, not a complete arm skeleton. Join each reported
    // lower arm to the corresponding live wrist; a valid controller grip is a fallback.
    if (showBody)
        for (hand in Hand.entries) {
            val arm = if (hand == Hand.LEFT) BodyJoint.LEFT_ARM_LOWER else BodyJoint.RIGHT_ARM_LOWER
            val handState = xr.handState(hand)
            val wrist = if (showHands && handState?.isActive == true) handState[HandJoint.WRIST] else null
            val controller = xr.controllerState(hand)
            val endpoint =
                if (wrist?.positionValid == true) wrist
                else if (showControllers && controller.poseValid)
                    JointPose(controller.position, controller.rotation, 0f, true, true)
                else null
            segment(body?.get(arm), endpoint, bodyTint)
        }
    for (i in 0 until xr.bodyTrackerCount()) joint(
        "raw tracker $i",
        xr.bodyTrackerPose(i),
        vec(1f, .2f, .6f, .8f),
        showBody && body?.trackedJoints.isNullOrEmpty(),
    )
    updateBodyQueries()
    for (i in used until trackingMarks.size) trackingMarks[i][2].w = 0f
    val now = System.nanoTime()
    if (now - lastReport > 250_000_000L) {
        lastReport = now
        markdown(
            "query result",
            """
            ### Body queries

            Pink: query point. Green: nearest skeleton/surface point.
            The query follows the right palm or controller, with a head-forward fallback.

            | Query | Result |
            |---|---|
            | World point | ${xyz(queryWorld)} |
            | Nearest reported joint | ${queryJoint?.joint ?: "unavailable"} |
            | Skeleton/surface distance | ${queryHit?.distance?.f3 ?: "unavailable"} m |
            | Signed primitive distance | ${queryHit?.signedDistance?.f3 ?: "unavailable"} m |
            | Body segment | ${queryHit?.segment ?: "none"} |
            | Head selected | ${queryHit?.head ?: false} |

            Negative signed distance means inside the selected primitive.
            Cylinders and the sphere are independent surfaces; overlapping end caps remain queryable.
        """,
            width = 650,
        )
        markdown(
            "tracking status",
            """
            ## Live WiVRn observations

            $used draw marks · XR ticks ${xr.xrFrameCounter} · scene submissions ${renderer.submittedSceneFrames}.

            Last render failure: ${mdEscape(renderer.lastRenderFailure ?: "none")}.

            Blue = left hand; orange = right; pale green = body; purple = controllers; pink = unmapped trackers.
            Local axes: red X, green Y, blue Z. Connections are guides between reported joints, not additional tracked bones.

            Raw positions below are in world-space metres. Inspection only offsets the drawing.
            Invalid positions are hidden; Quest 3 supplies no eye tracking.

            ${rows.joinToString("\n")}
        """,
            width = 960,
            open = false,
        )
    }
}

// Keep the render loop and sampling callback in one owner.
stereo.beforeScene["body-one-tracking"] = updateTracking
loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()
    frame()
}
    .end { stereo.beforeScene.remove("body-one-tracking", updateTracking) }
```

