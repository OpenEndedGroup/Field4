import afield.Rect
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.enableVR = false
Options.profiling = false

once.renderer {
    window = Window(
        "YouTube · Vulkan kaleidoscope",
        initialDimensions = Rect(80.0, 80.0, 1280.0, 720.0)
    )
    Renderer(window)
}

println("YouTube kaleidoscope renderer ready")
start(renderer::run)
