import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · RectSL as a solid")
    Renderer(window)
}
start(renderer::run)
