import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Boids", initialDimensions = Rect(80.0, 80.0, 960.0, 720.0))
    Renderer(window)
}
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
