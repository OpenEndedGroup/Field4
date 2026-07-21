import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("RectSL — data visualization")
    Renderer(window)
}

println("dataviz renderer ready")
start(renderer::run)
