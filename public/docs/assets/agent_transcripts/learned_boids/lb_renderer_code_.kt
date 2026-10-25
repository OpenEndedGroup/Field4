import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// One window and renderer, published to every child as `renderer`.
once.renderer {
    window = Window("Learned boids")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
