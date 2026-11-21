import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("outlines filled into a buffer", initialDimensions = Rect(80.0, 80.0, 1000.0, 360.0))
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
