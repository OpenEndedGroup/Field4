// ImGui as a VR panel.
//
// ImGuiVrPanelDemo wires the whole pipeline: an ImGuiRenderToTexture drawn into an RTT,
// textured onto a world-space ImGuiPanel quad, rendered once per eye by
// SideBySideStereoTarget, packed side-by-side, and blitted to the window. The panel is
// interactive: the mock controller (here driven by the mouse) casts a ray, the hit is
// converted to panel UV, and injected into ImGui.
//
// Move the mouse to aim the ray; left-click to press. To bind the panel to a hand instead
// of a fixed point, call panel.place(handPos, handRight, handUp) each frame (see ImGuiPanel).
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.targets.SideBySideStereoTarget
import afield.examples.four.ImGuiVrPanelDemo

val renderer: Renderer = fromContext()
val sbs = SideBySideStereoTarget(renderer)
val demo = ImGuiVrPanelDemo(renderer.window, renderer, sbs)
demo.build()

// Mouse drives the mock controller ray on the desktop (a real controller drives it in XR).
sbs.enableMouseController(distanceMeters = 0.001f)

println("imgui vr panel ready — move the mouse to aim the ray, click to press")
