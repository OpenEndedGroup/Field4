import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
Options.vsync = false
once.renderer {
    window = Window("the tiger, through Gezira on the GPU", initialDimensions = Rect(80.0, 80.0, 900.0, 900.0))
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
