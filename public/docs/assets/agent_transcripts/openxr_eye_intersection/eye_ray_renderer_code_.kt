import afield.four.util.Options
import afield.four.targets.OpenXRHmdTarget

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.enableVR = !Options.isMac
once.renderer {
    window = Window("Eye rays · LOCAL to world")
    val created = Renderer(window)
    if (Options.enableVR) {
        created.openXRHmdTarget = OpenXRHmdTarget(created)
        created.openXRHmdTarget!!.setClearColor(0f, 0f, 0f)
    }
    created
}
println(if (Options.enableVR) "OpenXR renderer ready" else "Desktop reference preview — no eye tracking is simulated")
start(renderer::run)
