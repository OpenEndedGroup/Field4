import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.enableVR = !Options.isMac
once.renderer {
    window = Window("Eye rays · LOCAL to world", initialDimensions = Rect(80.0, 80.0, 720.0, 480.0))
    val created = Renderer(window)
    if (Options.enableVR) {
        created.openXRHmdTarget = OpenXRHmdTarget(created)
        created.openXRHmdTarget!!.setClearColor(0f, 0f, 0f)
    }
    created
}
println(if (Options.enableVR) "OpenXR renderer ready" else "Desktop reference preview — no eye tracking is simulated")
start(renderer::run)
