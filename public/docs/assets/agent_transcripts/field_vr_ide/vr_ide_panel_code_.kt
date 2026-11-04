import afield.media.browser.FieldUiBrowserTexture
import afield.media.browser.FieldVrWorkspace
import afield.media.browser.PuppetBrowser

prop<PuppetBrowser>(browserControl)
val ownedRenderer = renderer
val vr = FieldVrWorkspace(
    renderer = ownedRenderer,
    url = FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
    width = 1600,
    height = 1000,
    readyBoxUid = thisBox.id,
    widthMeters = 2.0f,
    distanceMeters = 1.35f,
)
browserControl = vr.browser
// Sample the eye layers already rendered for OpenXR; do not render the scene again.
var stereoPreview = false
val previewModule = vr.stereo.mirrorToScreen(
    windowIndex = 1,
    name = "headset-preview",
    stereoPair = { stereoPreview },
)
val previewWindow = ownedRenderer.windows[1]
controlGroup("desktop preview") {
    checkbox("Side-by-side eyes", initialValue = false) { stereoPreview = it }
    button("Show headset preview") { down ->
        if (down) {
            org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose(previewWindow.windowHandle, false)
            org.lwjgl.glfw.GLFW.glfwShowWindow(previewWindow.windowHandle)
            previewWindow.presentationEnabled = true
        }
    }
}
println("Live IDE: ${vr.url}; ${vr.stereo.eyeWidth} × ${vr.stereo.eyeHeight} per eye")
// Capture the renderer before entering the asynchronous scope.
loop {
    ownedRenderer.windows.forEach { it.pollEvents() }
    if (previewWindow.shouldClose() && previewWindow.presentationEnabled) {
        previewWindow.presentationEnabled = false
        org.lwjgl.glfw.GLFW.glfwHideWindow(previewWindow.windowHandle)
    }
    ownedRenderer.render()
    frame()
}
    .end {
        ownedRenderer.renderTargetFor(1).modules.remove("headset-preview", previewModule)
        vr.close()
    }
