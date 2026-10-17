import afield.graphics.vulkan.util.Options

// Publish the window and renderer for the connected drawing boxes.
prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
once.renderer {
    window = Window("Field · skinned characters", initialDimensions = Rect(80.0, 80.0, 1100.0, 700.0))
    Renderer(window)
}
println("renderer ready — now run box 4")
start(renderer::run)
