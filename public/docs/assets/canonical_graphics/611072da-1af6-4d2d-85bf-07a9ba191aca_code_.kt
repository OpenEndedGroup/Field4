import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = true

once.renderer {
    window = Window("Field")
    Renderer(window)
}


start(renderer::run)