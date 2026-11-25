import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Discrete energy", initialDimensions = Rect(80.0, 80.0, 480.0, 320.0))
    Renderer(window)
}
start(renderer::run)
