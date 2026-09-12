import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Polytope to path tracer")
    Renderer(window)
}
start(renderer::run)
