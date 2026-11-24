import afield.graphics.vulkan.util.Options

// Set BEFORE the first renderer in this Field JVM; switching modes needs a fresh JVM.
val headsetMode = false // true for OpenXR (WiVRn, CloudXR); false for the desktop stereo mirror
Options.enableVR = headsetMode
if (headsetMode) Options.validateOpenXR = false
prop<Window>(window) = Window("morphing text by key", initialDimensions = Rect(60.0, 60.0, 1280.0, 900.0))
prop<Renderer>(renderer) = Renderer(window)
val ownedRenderer = renderer
if (headsetMode) {
    renderer.openXRHmdTarget = OpenXRHmdTarget(renderer)
    repeat(8) { if (renderer.openXRHmdTarget?.hasEyeViews == false) renderer.render() }
}
prop<MultiviewStereoTarget>(stereo) = MultiviewStereoTarget(renderer, 1600, 1200, withDepth = false)
val ownedStereo = stereo
if (!headsetMode) {
    stereo.stereoCamera.base.at = vec(0f, 0f, 2.35f)
    stereo.stereoCamera.base.target = vec(0f, 0f, 0f)
}
stereo.layered.parameters.clearColor = vec(.003f, .005f, .010f, 1f)
prop<Boolean>(showStereoPair) = false
stereo.mirrorToScreen(name = "pointing-preview", stereoPair = { showStereoPair })
println("context ready: " + stereo.eyeWidth + " x " + stereo.eyeHeight + " per eye")
loop { frame() }
    .end {
        ownedRenderer.renderList.preTargets.remove("stereo-multiview", ownedStereo)
        ownedRenderer.cleanup()
    }
