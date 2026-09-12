import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · Cloth through Manifold")
    Renderer(window)
}
start(renderer::run)
