import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false
// OpenXR is the default on its supported desktop platforms. macOS remains the stereo
// preview/development path because it has no system OpenXR runtime.
Options.enableVR = !Options.isMac

once.renderer {
    window = Window("YouTube · puppet browser")
    val created = Renderer(window)
    if (Options.enableVR) {
        created.openXRHmdTarget = afield.four.targets.OpenXRHmdTarget(created)
    }
    created
}

println("YouTube VR renderer ready")
start(renderer::run)
