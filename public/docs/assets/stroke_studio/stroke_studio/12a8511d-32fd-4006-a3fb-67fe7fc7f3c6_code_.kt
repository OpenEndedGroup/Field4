import afield.app.protocol.Rect
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false
Options.maxFrameRate = 60.0

once.renderer {
    window = Window("stroke_three", initialDimensions = Rect(0.0, 0.0, 1600.0, 1000.0))
    Renderer(window)
}

start(renderer::run)
