import afield.graphics.scene.reading.*
import afield.graphics.vulkan.util.Options
import afield.media.browser.*

/*
Set this code switch BEFORE the first Vulkan renderer is created.
Moving between desktop-only and OpenXR mode requires a fresh Field JVM.
The browser texture size below is independent of the headset render resolution.
*/
val headsetMode = false // true for WiVRn / OpenXR; false for desktop preview
Options.enableVR = headsetMode
Options.enableHDR = false
prop<Window>(window) = Window("text_one · a reading space", initialDimensions = Rect(60.0, 60.0, 1440.0, 1000.0))
prop<Renderer>(renderer) = Renderer(window)
val ownedRenderer = renderer
var workspace: FieldVrWorkspace? = null
if (headsetMode) {
    renderer.openXRHmdTarget = OpenXRHmdTarget(renderer)
    repeat(8) { if (renderer.openXRHmdTarget?.hasEyeViews == false) renderer.render() }
    check(renderer.openXRHmdTarget?.hasEyeViews == true) { "Connect WiVRn before launching the headset mode" }
    workspace = FieldVrWorkspace(
        renderer,
        FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
        width = 2560,
        height = 1600,
        readyBoxUid = thisBox.id,
        withDepth = false,
        widthMeters = 1.8f,
        distanceMeters = 1.7f,
    )
}
// FieldVrWorkspace takes eye dimensions from OpenXR, exactly as dust_six does.
// Only the disconnected desktop preview uses this fixed fallback.
prop<MultiviewStereoTarget>(stereo) =
    workspace?.stereo ?: MultiviewStereoTarget(renderer, 1600, 1200, withDepth = false)
val ownedStereo = stereo
if (!headsetMode) {
    stereo.stereoCamera.base.at = vec(0f, 0f, 2.35f)
    stereo.stereoCamera.base.target = vec(0f, 0f, 0f)
}
stereo.layered.parameters.clearColor = vec(.003f, .005f, .010f, 1f)
display(
    "resolution",
    "<div style='padding:12px'>${if (headsetMode) "OpenXR runtime" else "Desktop fallback"}: ${stereo.eyeWidth} × ${stereo.eyeHeight} per eye. Browser: 2560 × 1600. Restart Field after changing WiVRn resolution.</div>",
)
prop<Boolean>(showStereoPair) = false
val mirror = stereo.mirrorToScreen(name = "reading-preview", stereoPair = { showStereoPair })
controlGroup("reading space") {
    checkbox("Show both eyes side by side", initialValue = false) { showStereoPair = it }
    checkbox("Desktop preview", initialValue = true) { renderer.desktopPresentationEnabled = it }
}
display(
    "mode",
    "<div style='padding:12px;width:440px'>${if(headsetMode)"OpenXR: current predicted pose in both eyes + sharp Field browser" else "Desktop stereo preview: the same two-eye scene, without a headset"}. No motion history or temporal antialiasing. Launch the five example boxes, Scores, the shaders, then Drawing.</div>",
)
loop { frame() }
    .end {
        workspace?.close()
        ownedRenderer.renderList.preTargets.remove("stereo-multiview", ownedStereo)
        ownedRenderer.openXRHmdTarget?.clearEyeSource(ownedStereo.layered)
        ownedRenderer.cleanup()
    }
