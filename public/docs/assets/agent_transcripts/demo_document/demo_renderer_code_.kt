import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Field demo document")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
