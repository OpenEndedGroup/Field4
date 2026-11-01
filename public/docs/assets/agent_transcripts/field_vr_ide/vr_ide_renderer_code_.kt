import afield.graphics.vulkan.targets.OpenXRHmdTarget
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
// Start with the desktop companion. Connect WiVRn, set true and restart Field for VR.
val useHeadset = false
Options.enableVR = useHeadset
Options.enableHDR = false
once.renderer {
    window = Window("Field · VR IDE", initialDimensions = Rect(80.0, 80.0, 1440.0, 800.0))
    val created = Renderer(window)
    if (useHeadset) created.openXRHmdTarget = OpenXRHmdTarget(created)
    created
}
renderer.render()
display(
    "guide",
    """
    <div style="width:420px;font:16px/1.6 system-ui">
    <b>Field in your headset</b><br>
    Launch 1, then 2. The browser opens this same document.<br>
    Point with either controller; trigger clicks and drags, stick scrolls.<br>
    Aim at the panel and hold grip to move and rotate it; release to leave it there.<br>
    With tracked hands, point the hand and pinch to click.<br>
    Keyboard opens text and editing keys; Commands opens the palette.<br>
    Select the Playground, edit its code, then Run box (Alt+Up).<br>
    Recenter brings the panel in front of your current head position.<br>
    The desktop companion accepts normal keyboard and mouse input when focused.<br>
    Quest keyboard and mouse use WiVRn HID forwarding after setup; see the saved guide.<br>
    Another browser can edit this same document with its own input focus.<br>
    Stop box 2 to close the browser. Restart Field to switch VR mode or eye size.
    </div>
    """
        .trimIndent(),
)
displayOpen("guide")
