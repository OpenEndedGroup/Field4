// The window and the Vulkan renderer every other box in this document inherits.
// `once.renderer` means re-running this box does not build a second window.
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Path tracer instrument")
    Renderer(window)
}
start(renderer::run)
