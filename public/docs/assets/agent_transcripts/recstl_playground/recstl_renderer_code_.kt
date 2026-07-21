import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("RectSL playground")
    Renderer(window)
}

println("recstl renderer ready")
start(renderer::run)
