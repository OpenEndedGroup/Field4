import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · a deadlock, sculpted and traced")
    Renderer(window)
}
start(renderer::run)
