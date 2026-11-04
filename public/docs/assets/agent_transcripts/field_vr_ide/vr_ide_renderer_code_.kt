import afield.graphics.vulkan.targets.OpenXRHmdTarget
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
// Start with the desktop companion. Connect OpenXR, set true and restart Field for VR.
val useHeadset = false
Options.enableVR = useHeadset
Options.enableHDR = false
once.renderer {
    window = Window("Field · VR IDE", initialDimensions = Rect(80.0, 80.0, 1440.0, 800.0))
    val preview = Window("Field · headset preview", initialDimensions = Rect(1540.0, 80.0, 1000.0, 800.0))
    // Let the headset advance even when the desktop preview cannot present.
    preview.bestEffortPresentation = true
    val created = Renderer(window, additionalWindows = listOf(preview))
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
    Point with either controller; trigger or A/X clicks, stick scrolls in both axes.<br>
    Aim at the panel and hold grip to move and rotate it; release to leave it there.<br>
    Hold both grips and spread your hands to resize the panel.<br>
    Tracked hands do not point or click; use a controller or mouse.<br>
    Keyboard opens text and editing keys; Commands opens the palette.<br>
    Select the Playground, edit its code, then Run box (Alt+Up).<br>
    The bottom toolbar Recenter button brings the panel in front of you and keeps its size.<br>
    The desktop companion accepts normal keyboard and mouse input when focused.<br>
    A separate headset preview shows one eye; box 2 can switch to both eyes.<br>
    Close the preview to hide it, then use Show headset preview to reopen it.<br>
    Quest keyboard and mouse use WiVRn HID forwarding after setup; see the saved guide.<br>
    Another browser can edit this same document with its own input focus.<br>
    Stop box 2 to close the browser. Restart Field to switch VR mode or eye size.
    </div>
    """
        .trimIndent(),
)
displayOpen("guide")
