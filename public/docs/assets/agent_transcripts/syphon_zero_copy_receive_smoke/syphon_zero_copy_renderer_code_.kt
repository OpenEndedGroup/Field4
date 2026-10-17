import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Syphon zero-copy receive smoke")
    Renderer(window)
}

println("syphon zero-copy renderer ready")
start(renderer::run)
