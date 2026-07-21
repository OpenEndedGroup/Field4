import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("RectSL — plotting layer")
    Renderer(window)
}

println("plot renderer ready")
start(renderer::run)
