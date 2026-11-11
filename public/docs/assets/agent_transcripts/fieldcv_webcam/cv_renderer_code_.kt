import afield.graphics.vulkan.util.Options

// 0 · the native window everything below draws into. Run this first.
prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("fieldcv")
    Renderer(window)
}

start(renderer::run)
