import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · A Petri net that throws sparks")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
