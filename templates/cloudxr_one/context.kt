import afield.graphics.vulkan.targets.*
import afield.graphics.vulkan.util.Options
import afield.media.browser.*

// Select before constructing the first Renderer. A mode change needs a fresh JVM.
val headsetMode = System.getenv("FIELD_CLOUDXR_DESKTOP") != "1"
val withFieldBrowser = System.getenv("FIELD_CLOUDXR_IDE") == "1"

Options.enableVR = headsetMode
Options.enableHDR = false

prop<Window>(window) = Window(
    (if (withFieldBrowser) "cloudxr_one · Field" else "cloudxr_one · red cube"), initialDimensions = Rect(60.0, 60.0, 1200.0, 900.0))
val companionWindow = if (withFieldBrowser)
    Window("cloudxr_one · red cube", initialDimensions = Rect(1280.0, 60.0, 1100.0, 900.0))
else null

prop<Renderer>(renderer) = Renderer(window, additionalWindows = listOfNotNull(companionWindow))
val ownedRenderer = renderer

// Bootstrap the runtime first, then use its actual recommended eye dimensions.
if (headsetMode) {
    renderer.openXRHmdTarget = OpenXRHmdTarget(renderer, handInteractionActions = true)
    repeat(8) { if (renderer.openXRHmdTarget?.hasEyeViews == false) renderer.render() }
    check(renderer.openXRHmdTarget?.hasEyeViews == true) { "Connect the streaming client before launching" }
} else renderer.render()

// The initial cube test creates no Chromium surface. --ide enables the next stage.
val workspace = if (withFieldBrowser) FieldVrWorkspace(
    renderer, FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
    width = 3200, height = 2000, readyBoxUid = thisBox.id,
    widthMeters = 1.6f, distanceMeters = 1.7f,
    headsetInput = false,
) else null

val xr = renderer.openXRHmdTarget
// The UUID is stable so the client can discover this test channel on every launch.
val messageChannel = if (headsetMode) runCatching {
    xr?.createDataChannel(java.util.UUID.fromString("f4e1d000-0000-4000-8000-000000000001"))
}.onFailure { println("Message channel unavailable: ${it.message}") }.getOrNull() else null
var messagePollAt = 0L
var messageReady = false
var messageFailed = false

val eye = xr?.let { renderer.runOnQueueThread { it.sideBySideRTTParameters() } }
prop<MultiviewStereoTarget>(stereo) = workspace?.stereo ?: MultiviewStereoTarget(
    renderer, eyeWidth = eye?.width?.div(2) ?: 1600,
    eyeHeight = eye?.height ?: 1200, openXR = xr)
val ownedStereo = stereo

// Black clear keeps the editor surrounded by darkness.
stereo.layered.parameters.clearColor = vec(0f, 0f, 0f, 1f)
stereo.stereoCamera.base.lookAt(vec(0f, 0f, 0f), vec(0f, 0f, -1f))
var bothEyes = false
stereo.mirrorToScreen(windowIndex = if (companionWindow != null) 1 else 0, stereoPair = { bothEyes })

// FieldVrWorkspace centres the panel on the current head pose, then leaves it world-locked.

controlGroup("view", restoreValues = false) {
    checkbox("Both eyes in desktop preview", initialValue = false) { bothEyes = it }
    if (workspace != null) {
        val visibleInput = renderer.renderList.preTargets.getValue("field-vr-browser")
        val hiddenInput = afield.graphics.vulkan.Target { command, _ ->
            workspace.browser.prepareSampledImagesFor(command)
        }
        checkbox("Show Field surface", initialValue = true) { visible ->
            workspace.input.cancel()
            workspace.grab.cancel()
            workspace.companion.input.releasePointer()
            stereo.layered.modules.getValue(workspace.panel.name).current.enabled = visible
            renderer.renderList.preTargets["field-vr-browser"] = if (visible) visibleInput else hiddenInput
        }
        button("Bring Field in front") { down -> if (down) workspace.recenter() }
        button("Focus desktop keyboard") { down -> if (down) workspace.companion.input.takeFocus() }
    }
}
displayOpen("view")
markdown("status", """
### CloudXR reading / coding room

${if (headsetMode) "OpenXR" else "Desktop stereo"} · ${stereo.eyeWidth} × ${stereo.eyeHeight} per eye.

${if (withFieldBrowser) "Live Field IDE: 3200 × 2000 browser pixels on a 1.6 m panel. The red cube is to its right." else "Cube centre is fixed at (0, 0, −1.5) metres in the local reference space."}
Move your head to check stereo and world locking. The cube itself stays still.

The **Red cube shader** box owns the editable vertex and fragment shaders (Alt+R to reload).
""")
displayOpen("status")

loop {
    // Bound messaging work and poll at 10 Hz, independently of headset refresh rate.
    val now = System.nanoTime()
    if (messageChannel != null && !messageFailed && now >= messagePollAt) {
        messagePollAt = now + 100_000_000L
        runCatching {
            renderer.runOnQueueThread {
                if (messageChannel.state() == OpenXROpaqueDataChannel.State.CONNECTED) {
                    if (!messageReady) {
                        messageChannel.send("Field echo channel ready".toByteArray())
                        messageReady = true
                    }
                    for (i in 0 until 4) {
                        val bytes = messageChannel.receive() ?: break
                        messageChannel.send(bytes)
                        println("Echoed ${bytes.size} message bytes")
                    }
                }
            }
        }.onFailure {
            messageFailed = true
            println("Message channel stopped: ${it.message}")
        }
    }
    frame()
}.end {
    workspace?.close()
    ownedRenderer.renderList.preTargets.remove("stereo-multiview", ownedStereo)
    ownedRenderer.openXRHmdTarget?.clearEyeSource(ownedStereo.layered)
    ownedRenderer.cleanup()
}
