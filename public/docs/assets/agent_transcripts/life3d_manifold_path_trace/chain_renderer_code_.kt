import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · 3D Life through Manifold")
    Renderer(window)
}
start(renderer::run)
