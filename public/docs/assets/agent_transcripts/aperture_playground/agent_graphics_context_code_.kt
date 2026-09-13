import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field — aperture playground", initialDimensions = Rect(80.0,80.0,960.0,720.0))
    Renderer(window)
}
start(renderer::run)
