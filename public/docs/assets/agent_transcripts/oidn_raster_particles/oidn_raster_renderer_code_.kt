import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Denoising a raster render")
    Renderer(window)
}
start(renderer::run)
