import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.enableVR = false

once.renderer {
    window = Window("WebRTC shader jam", initialDimensions = Rect(80.0, 80.0, 960.0, 540.0))
    Renderer(window)
}

println("WebRTC renderer ready")
start(renderer::run)
