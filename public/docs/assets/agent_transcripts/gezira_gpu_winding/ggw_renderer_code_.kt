import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("gezira on the gpu", initialDimensions = Rect(80.0, 80.0, 400.0, 300.0))
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
