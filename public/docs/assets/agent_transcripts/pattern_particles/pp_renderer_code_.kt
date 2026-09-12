import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · A pattern that moves particles")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
