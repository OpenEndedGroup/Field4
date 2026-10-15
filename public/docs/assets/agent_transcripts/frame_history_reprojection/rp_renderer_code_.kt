import afield.graphics.vulkan.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · frame history reprojection")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
