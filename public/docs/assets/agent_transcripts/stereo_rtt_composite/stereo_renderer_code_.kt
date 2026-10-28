// Launch 1, 2, 2b, 3, 4 in order; stop box 4 to pause.
// Desktop preview first. For OpenXR: connect the headset, set true, restart Field.
import afield.graphics.vulkan.targets.OpenXRHmdTarget
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
val useHeadset = false
Options.enableVR = useHeadset
Options.enableHDR = false
once.renderer {
    window = Window("Stereo RTT composite", initialDimensions = Rect(80.0, 80.0, 1280.0, 640.0))
    val r = Renderer(window)
    if (useHeadset) r.openXRHmdTarget = OpenXRHmdTarget(r)
    r
}
// Initialize the swapchain and locate the initial eye views; box 4 owns the loop.
renderer.render()
display(
    "guide",
    """
    <div style="width:460px;font:14px/1.6 system-ui">
    <b>Scene → stereo RTT → FXAA → headset + desktop</b><br>
    Launch 1, 2, 2b, 3, 4 in order. Box 4 starts rendering.<br>
    Edit geometry in 2, shaders in 3, and toggle FXAA in 3's output.<br>
    Desktop preview: 1200 × 1200 pixels per eye.<br>
    For VR, connect your OpenXR runtime first, set useHeadset = true above,
    then restart Field and launch the boxes again. Eye size comes from the runtime.<br>
    Stop box 4 to pause the render loop. Restart Field to change mode or eye size.
    </div>
    """
        .trimIndent(),
)
displayOpen("guide")