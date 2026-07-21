import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Spin Glass - 2D Edwards-Anderson")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
