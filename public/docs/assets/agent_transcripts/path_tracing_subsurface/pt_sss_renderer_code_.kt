import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · subsurface scattering", initialDimensions = Rect(80.0, 80.0, 1280.0, 400.0))
    Renderer(window)
}
start(renderer::run)
