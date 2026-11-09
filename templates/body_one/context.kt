import afield.graphics.vulkan.targets.*
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
