# Body and hand tracking

See what your headset is tracking with `body_one`. It draws the body joints, hand joints and
controller poses exposed by WiVRn, with orientation axes and a desktop mirror of the headset image.
You can also use world-space bounds and closest-point queries to bring that tracking into your own
graphics and interactions.

[Open body_one →](offline.html?file=assets/agent_transcripts/body_one/metadata.edn)

Connect the headset to WiVRn and launch **1 · Headset / preview / Field pane**,
**2 · Tracking markers and connections**, then **3 · Tracking / body queries**.
Use the supplied launch macro to run them in order. Start with a fresh Field JVM if another
document has already created a Vulkan renderer with different OpenXR settings.


```diffstatic_runner {data-lang=kotlin document="body_one" capture="native-window" capture-box="body1_preview_capture" macro-file="../../dev_docs/agents/transcripts/docs_capture/body_one_live.yaml" screenshot="assets/body_tracking/headset.png" native-wait-frames="3" capture-profile="linux-wivrn-live" timeout="120" embed=true hidden=true name="body-one-live-headset" alt="Live Quest body and hand observations in the native headset preview, with orientation axes and a body-bounds overlay."}
// Capture the live stereo image; the macro requires active tracking and never starts another renderer.
```

These captures come from a live Quest 3 / WiVRn session. The native preview follows the wearer's
head pose; the control panels show the same running document in a browser. The values shown are
observations from that session.

## What is being drawn?

| Observation | Display | Availability |
|---|---|---|
| Left hand | Blue joints and finger connections | 26 joint slots while hand tracking is active |
| Right hand | Orange joints and finger connections | 26 joint slots while hand tracking is active |
| Body | Pale green joints and sparse connections | Up to eight named slots in Field's current WiVRn mapping |
| Controllers | Purple grip markers and orientation axes | Valid controller poses, with analog/button values in the status panel |
| Head | White marker and orientation axes | Valid OpenXR head pose |
| Unmapped trackers | Pink markers | Raw trackers that have no named body mapping |

Local orientation axes use red for X, green for Y and blue for Z. A joint with no valid position
is hidden immediately. An unavailable orientation does not produce axes. Connecting lines are
visual guides between reported points: they do not imply additional tracked bones or a complete
anatomical skeleton. Each lower arm connects to its tracked wrist, falling back to the controller
grip when hand tracking is inactive.

The named body slots are **hips, chest, left/right lower arm, left/right lower leg and left/right
foot**. A runtime or WiVRn configuration can expose a subset. The status disclosure lists the
actual mapping and raw tracker count. The runtime estimates body positions; it does not independently
measure every joint. Quest 3 has no eye tracking.

Hand tracking can change as controllers are put down or picked up. The panel reports hand
activity, per-joint position/orientation validity, radii and pinch strength. Controller input
includes trigger, grip, thumbstick, A/B and menu values.

## Bounds and closest points

`BodyGeometry` copies valid, finite body positions and an optional head pose. The snapshot stays
fixed as later tracking updates arrive. Empty observations produce `null` bounds or hits.
Queries do not modify their input vectors. The geometry helper uses the sparse body slots and
optional head; fingers, wrist/controller endpoints and the inspection-copy offset are separate.

```diffstatic {data-lang=kotlin}
import afield.graphics.scene.body.*

val body = renderer.openXRHmdTarget!!.bodyGeometry()
val query = vec(.2f, 1f, -.5f)

// Reported joint centres only, excluding the head.
val joint = body.closestJoint(query)

// Points and connected segments, without a head shape.
val skeleton = body.closestPoint(query, includeHead = false)

// Finite limb cylinders and a head sphere, in metres.
val surface = body.closestPoint(query, limbRadius = .04f, headRadius = .09f)
val bounds = body.bounds(limbRadius = .04f, headRadius = .09f, padding = .01f)
```

A `BodyHit` contains the selected world-space `point`, outward `normal`, nonnegative `distance`
and `signedDistance`, together with the source `joint`, `segment` or `head` flag. Negative signed
distance means the query lies inside the selected primitive. A `BodyBounds` contains `min`, `max`,
`center` and `size`.

Zero radii reduce the query to the skeleton. Positive limb radius makes each connected segment a
finite cylinder, including its flat end caps. An isolated joint or zero-length limb uses a sphere
of that radius. Head radius is independent; `includeHead = false` removes it from both queries and
bounds. The bounds enclose the same primitives, with optional extra padding.

Each shape is an independent surface: cylinder end caps remain queryable where shapes overlap.
Use them for proximity, attraction and other creative forces. They do not form a watertight union
or reconstruct a body mesh, and missing joints are never inferred to complete the shape.

### Try the query overlay

The pink query follows the right palm, otherwise a valid right controller grip. Without either,
it uses a point ahead of the head. A green marker shows the closest point and a green segment joins
the two. The box overlay shows the bounds.

**Head sphere / limb cylinders** switches between volume surfaces and the bare skeleton.
**Include head in bounds and queries** is separate, as are the head and limb radii. The query panel
reports the nearest joint, selected segment, surface distance and signed distance. Drawing can be
translated into the inspection copy while those numbers remain in raw world coordinates.


## Controls and Markdown panels

These panels let you adjust the tracking display and try a body query while the headset runs.

### Headset preview

The native preview samples the same rendered eye image as the headset. **Both eyes side by side**
shows the two eye images together in the desktop mirror; leave it
off for a larger single-eye view. **Desktop preview enabled** controls the native mirror window's
presentation. Turn it off when working entirely in the headset; tracking and VR rendering continue.

```diffstatic_runner {data-lang=kotlin capture="ui" macro-file="../../dev_docs/agents/transcripts/docs_capture/body_one_restore.yaml" document="body_one" region="window" viewport="1600x1200" clip-selector="#docs-body-panel" capture-box="body1_context" screenshot="assets/body_tracking/preview-controls.png" capture-profile="linux-wivrn-live" timeout="120" padding="12" embed=true hidden=true name="body-one-preview-controls" alt="Both-eye layout and desktop preview controls."}
ui.waitForSelector(".disclosure", 15000.0)
ui.eval(
    """
    // Frame the actual output in place: moving an iframe would reload its contents.
    document.documentElement.style.backgroundColor = getComputedStyle(document.body).backgroundColor;
    document.getElementById("field-shot-isolation")?.remove();
    document.querySelectorAll("[data-docs-body-box]").forEach(e => e.removeAttribute("data-docs-body-box"));
    document.querySelectorAll("[id='docs-body-panel']").forEach(e => e.removeAttribute("id"));
    const panel = Array.from(document.querySelectorAll(".disclosure")).find(
        e => e.querySelector(".title")?.textContent.trim() === "preview"
    );
    if (!panel) throw new Error("Missing live output: preview");
    const box = panel.closest(".box");
    box.setAttribute("data-docs-body-box", "true");
    panel.id = "docs-body-panel";
    let style = document.getElementById("docs-body-framing");
    if (!style) { style = document.createElement("style"); style.id = "docs-body-framing"; document.head.appendChild(style); }
    style.textContent = `
        .box[data-docs-body-box] { position:fixed!important;left:24px!important;top:24px!important;transform:none!important; }
        .box[data-docs-body-box] .outputArea { position:absolute!important;left:0!important;top:0!important;transform:none!important; }
        .box[data-docs-body-box] .disclosure:not(#docs-body-panel) { display:none!important; }
        #docs-body-panel { background:rgb(20,20,20); }
    `;
    if (!panel.classList.contains("open")) panel.querySelector(".disclosure-header").click();
    return true;
    """,
)
ui.pause(800)
```

### Field browser pane

The optional [Field browser pane](field_vr_ide.md) shares the document with the desktop editor.
**Show Field pane in headset** toggles that browser surface in VR.
**Bring Field pane in front** places it back in front of your current head pose, useful after
moving around or leaving the pane behind. These controls move or hide the editor surface and leave
the tracking drawing in place.

```diffstatic_runner {data-lang=kotlin capture="ui" document="body_one" region="window" viewport="1600x1200" clip-selector="#docs-body-panel" capture-box="body1_context" screenshot="assets/body_tracking/browser-controls.png" capture-profile="linux-wivrn-live" timeout="120" padding="12" embed=true hidden=true name="body-one-browser-controls" alt="Visibility and recenter controls for the browser pane in VR."}
ui.waitForSelector(".disclosure", 15000.0)
ui.eval(
    """
    // Frame the actual output in place: moving an iframe would reload its contents.
    document.documentElement.style.backgroundColor = getComputedStyle(document.body).backgroundColor;
    document.getElementById("field-shot-isolation")?.remove();
    document.querySelectorAll("[data-docs-body-box]").forEach(e => e.removeAttribute("data-docs-body-box"));
    document.querySelectorAll("[id='docs-body-panel']").forEach(e => e.removeAttribute("id"));
    const panel = Array.from(document.querySelectorAll(".disclosure")).find(
        e => e.querySelector(".title")?.textContent.trim() === "Field browser"
    );
    if (!panel) throw new Error("Missing live output: Field browser");
    const box = panel.closest(".box");
    box.setAttribute("data-docs-body-box", "true");
    panel.id = "docs-body-panel";
    let style = document.getElementById("docs-body-framing");
    if (!style) { style = document.createElement("style"); style.id = "docs-body-framing"; document.head.appendChild(style); }
    style.textContent = `
        .box[data-docs-body-box] { position:fixed!important;left:24px!important;top:24px!important;transform:none!important; }
        .box[data-docs-body-box] .outputArea { position:absolute!important;left:0!important;top:0!important;transform:none!important; }
        .box[data-docs-body-box] .disclosure:not(#docs-body-panel) { display:none!important; }
        #docs-body-panel { background:rgb(20,20,20); }
    `;
    if (!panel.classList.contains("open")) panel.querySelector(".disclosure-header").click();
    return true;
    """,
)
ui.pause(800)
```

### Eye-image precision

This read-only panel reports the actual per-eye dimensions selected by the OpenXR runtime and
the formats of the scene target and headset swapchain. Use it to check the rendering setup after
changing WiVRn settings. The 16-bit float entries describe Field's images; WiVRn's encoded stream
has its own codec and bit-depth settings in the headset.

```diffstatic_runner {data-lang=kotlin capture="ui" document="body_one" region="window" viewport="1600x1200" clip-selector="#docs-body-panel" capture-box="body1_context" screenshot="assets/body_tracking/eye-precision.png" capture-profile="linux-wivrn-live" timeout="120" padding="12" embed=true hidden=true name="body-one-eye-precision" alt="Markdown table showing the actual scene and OpenXR image formats."}
ui.waitForSelector(".disclosure", 15000.0)
ui.eval(
    """
    // Frame the actual output in place: moving an iframe would reload its contents.
    document.documentElement.style.backgroundColor = getComputedStyle(document.body).backgroundColor;
    document.getElementById("field-shot-isolation")?.remove();
    document.querySelectorAll("[data-docs-body-box]").forEach(e => e.removeAttribute("data-docs-body-box"));
    document.querySelectorAll("[id='docs-body-panel']").forEach(e => e.removeAttribute("id"));
    const panel = Array.from(document.querySelectorAll(".disclosure")).find(
        e => e.querySelector(".title")?.textContent.trim() === "precision"
    );
    if (!panel) throw new Error("Missing live output: precision");
    const box = panel.closest(".box");
    box.setAttribute("data-docs-body-box", "true");
    panel.id = "docs-body-panel";
    let style = document.getElementById("docs-body-framing");
    if (!style) { style = document.createElement("style"); style.id = "docs-body-framing"; document.head.appendChild(style); }
    style.textContent = `
        .box[data-docs-body-box] { position:fixed!important;left:24px!important;top:24px!important;transform:none!important; }
        .box[data-docs-body-box] .outputArea { position:absolute!important;left:0!important;top:0!important;transform:none!important; }
        .box[data-docs-body-box] .disclosure:not(#docs-body-panel) { display:none!important; }
        #docs-body-panel { background:rgb(20,20,20); }
    `;
    if (!panel.classList.contains("open")) panel.querySelector(".disclosure-header").click();
    return true;
    """,
)
ui.pause(800)
```

### Tracking view

The default **Inspection copy ahead of me** translates the drawing about 1.6 metres ahead.
It makes the body easier to inspect without looking down through your own headset. It does not
change the raw coordinates, body-query inputs or distances. **Recenter inspection copy** sets
the offset from the current horizontal headset heading.

Turn the copy off to draw at the actual tracked positions. Hands, body, controllers, orientation
axes and connections can
be shown independently, making it easier to distinguish missing tracking from overlapping marks.

The three size sliders are in metres. **Joint radius** changes the circular markers, **Line
half-width** changes the connecting strips, and **Axis length** changes the orientation guides.
They change the drawing only; the query shapes below have separate radii.

```diffstatic_runner {data-lang=kotlin capture="ui" document="body_one" region="window" viewport="1600x1200" clip-selector="#docs-body-panel" capture-box="body1_tracking" screenshot="assets/body_tracking/tracking-controls.png" capture-profile="linux-wivrn-live" timeout="120" padding="12" embed=true hidden=true name="body-one-tracking-controls" alt="Controls for the inspection copy, tracked observations, joint axes, connections and drawing sizes."}
ui.waitForSelector(".disclosure", 15000.0)
ui.eval(
    """
    // Frame the actual output in place: moving an iframe would reload its contents.
    document.documentElement.style.backgroundColor = getComputedStyle(document.body).backgroundColor;
    document.getElementById("field-shot-isolation")?.remove();
    document.querySelectorAll("[data-docs-body-box]").forEach(e => e.removeAttribute("data-docs-body-box"));
    document.querySelectorAll("[id='docs-body-panel']").forEach(e => e.removeAttribute("id"));
    const panel = Array.from(document.querySelectorAll(".disclosure")).find(
        e => e.querySelector(".title")?.textContent.trim() === "tracking view"
    );
    if (!panel) throw new Error("Missing live output: tracking view");
    const box = panel.closest(".box");
    box.setAttribute("data-docs-body-box", "true");
    panel.id = "docs-body-panel";
    let style = document.getElementById("docs-body-framing");
    if (!style) { style = document.createElement("style"); style.id = "docs-body-framing"; document.head.appendChild(style); }
    style.textContent = `
        .box[data-docs-body-box] { position:fixed!important;left:24px!important;top:24px!important;transform:none!important; }
        .box[data-docs-body-box] .outputArea { position:absolute!important;left:0!important;top:0!important;transform:none!important; }
        .box[data-docs-body-box] .disclosure:not(#docs-body-panel) { display:none!important; }
        #docs-body-panel { background:rgb(20,20,20); }
    `;
    if (!panel.classList.contains("open")) panel.querySelector(".disclosure-header").click();
    return true;
    """,
)
ui.pause(800)
```

### Body-query shapes

**Show body bounds** and **Show closest point** enable the corresponding overlays. **Head sphere /
limb cylinders** gives the query a surface with thickness; switch it off to query joint centres
and connected segments. The independent **Include head** toggle adds or removes the head from
both bounds and closest-point queries.

Adjust **Head sphere radius** and **Limb cylinder radius** in metres to choose the interaction
envelope. These values do not resize the tracked skeleton markers. Try moving your right hand
towards your chest while switching between bare segments and cylinders: the green hit moves
from the skeleton to the chosen surface.

```diffstatic_runner {data-lang=kotlin capture="ui" document="body_one" region="window" viewport="1600x1200" clip-selector="#docs-body-panel" capture-box="body1_tracking" screenshot="assets/body_tracking/query-controls.png" capture-profile="linux-wivrn-live" timeout="120" padding="12" embed=true hidden=true name="body-one-query-controls" alt="Bounds and closest-point toggles, sphere and cylinder selection, and independent head and limb radii."}
ui.waitForSelector(".disclosure", 15000.0)
ui.eval(
    """
    // Frame the actual output in place: moving an iframe would reload its contents.
    document.documentElement.style.backgroundColor = getComputedStyle(document.body).backgroundColor;
    document.getElementById("field-shot-isolation")?.remove();
    document.querySelectorAll("[data-docs-body-box]").forEach(e => e.removeAttribute("data-docs-body-box"));
    document.querySelectorAll("[id='docs-body-panel']").forEach(e => e.removeAttribute("id"));
    const panel = Array.from(document.querySelectorAll(".disclosure")).find(
        e => e.querySelector(".title")?.textContent.trim() === "body queries"
    );
    if (!panel) throw new Error("Missing live output: body queries");
    const box = panel.closest(".box");
    box.setAttribute("data-docs-body-box", "true");
    panel.id = "docs-body-panel";
    let style = document.getElementById("docs-body-framing");
    if (!style) { style = document.createElement("style"); style.id = "docs-body-framing"; document.head.appendChild(style); }
    style.textContent = `
        .box[data-docs-body-box] { position:fixed!important;left:24px!important;top:24px!important;transform:none!important; }
        .box[data-docs-body-box] .outputArea { position:absolute!important;left:0!important;top:0!important;transform:none!important; }
        .box[data-docs-body-box] .disclosure:not(#docs-body-panel) { display:none!important; }
        #docs-body-panel { background:rgb(20,20,20); }
    `;
    if (!panel.classList.contains("open")) panel.querySelector(".disclosure-header").click();
    return true;
    """,
)
ui.pause(800)
```

### Closest-point readout

The readout puts numbers to the pink-to-green query overlay. **World point** is the raw
query position; **Nearest reported joint** searches the available body joints, while **Body
segment** or **Head selected** identifies the primitive chosen by the surface query. The nearest
joint and nearest surface need not belong to the same part of the body.

**Skeleton/surface distance** is nonnegative. **Signed primitive distance** becomes negative
inside the selected cylinder or sphere. A joint or segment can be unavailable when only head
tracking is active, as in this capture.

```diffstatic_runner {data-lang=kotlin capture="ui" document="body_one" region="window" viewport="1600x1200" clip-selector="#docs-body-panel" capture-box="body1_tracking" screenshot="assets/body_tracking/query-result.png" capture-profile="linux-wivrn-live" timeout="120" padding="12" embed=true hidden=true name="body-one-query-result" alt="Live Markdown result table with query position, nearest joint, distances and selected body segment."}
ui.waitForSelector(".disclosure", 15000.0)
ui.eval(
    """
    // Frame the actual output in place: moving an iframe would reload its contents.
    document.documentElement.style.backgroundColor = getComputedStyle(document.body).backgroundColor;
    document.getElementById("field-shot-isolation")?.remove();
    document.querySelectorAll("[data-docs-body-box]").forEach(e => e.removeAttribute("data-docs-body-box"));
    document.querySelectorAll("[id='docs-body-panel']").forEach(e => e.removeAttribute("id"));
    const panel = Array.from(document.querySelectorAll(".disclosure")).find(
        e => e.querySelector(".title")?.textContent.trim() === "query result"
    );
    if (!panel) throw new Error("Missing live output: query result");
    const box = panel.closest(".box");
    box.setAttribute("data-docs-body-box", "true");
    panel.id = "docs-body-panel";
    let style = document.getElementById("docs-body-framing");
    if (!style) { style = document.createElement("style"); style.id = "docs-body-framing"; document.head.appendChild(style); }
    style.textContent = `
        .box[data-docs-body-box] { position:fixed!important;left:24px!important;top:24px!important;transform:none!important; }
        .box[data-docs-body-box] .outputArea { position:absolute!important;left:0!important;top:0!important;transform:none!important; }
        .box[data-docs-body-box] .disclosure:not(#docs-body-panel) { display:none!important; }
        #docs-body-panel { background:rgb(20,20,20); }
    `;
    if (!panel.classList.contains("open")) panel.querySelector(".disclosure-header").click();
    return true;
    """,
)
ui.pause(800)
```

## How the three boxes fit together

The drawing box allocates a fixed pool of marker meshes;
world positions, sizes, shape choice and colors reach the vertex shader through push constants.
The shader expands each marker into an eye-facing circle or thick strip, then the fragment shader
uses `fwidth` coverage for antialiasing. There is one shared multiview stereo target.

Tracking is sampled in `stereo.beforeScene`, after OpenXR locates this frame's eyes and before the
stereo target records its geometry. Thus the markers and both eye views use the same frame's tracking.
The tracking box owns the only render loop. The context box owns renderer/workspace cleanup;
the drawing box attaches and retires its module explicitly.

The editable source is in `templates/body_one/`. Its builder emits the starting-point transcript
and create/launch/stop operations without hand-editing a document's persistence files.

## Editable source

```diffstatic {data-lang=kotlin}
:::fold Headset, browser and preview
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
:::endfold
```

```diffstatic {data-lang=kotlin}
:::fold Marker pool and draw attachment
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
:::endfold
```

```diffstatic {data-lang=kotlin}
:::fold Tracking, controls and geometric queries
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
:::endfold
```

```diffstatic {data-lang=glsl}
:::fold GPU thickening
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
:::endfold
```

```diffstatic {data-lang=glsl}
:::fold Analytic coverage
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
:::endfold
```
