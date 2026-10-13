import afield.graphics.vulkan.util.Options
import afield.app.protocol.Rect
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableVR=false
Options.enableHDR=false
Options.profiling=false
once.renderer {
    window=Window("Field · neural cubes",initialDimensions=Rect(40.0,40.0,2304.0,832.0))
    Renderer(window)
}
println("agent graphics renderer ready")
start(renderer::run)
