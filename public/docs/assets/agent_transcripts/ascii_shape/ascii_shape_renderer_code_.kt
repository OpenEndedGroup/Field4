import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Shape ASCII", initialDimensions = Rect(80.0, 80.0, 640.0, 456.0))
    Renderer(window)
}
start(renderer::run)
