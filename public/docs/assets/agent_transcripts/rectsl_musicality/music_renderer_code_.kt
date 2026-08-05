import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("RectSL — musicality")
    Renderer(window)
}

println("musicality renderer ready")
start(renderer::run)
