import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.enableVR = false

once.renderer {
    window = Window("ShaderTrinket", initialDimensions = Rect(80.0, 80.0, 960.0, 540.0))
    Renderer(window)
}

println("ShaderTrinket window ready")
start(renderer::run)
