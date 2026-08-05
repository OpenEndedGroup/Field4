import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("RectSL SDF")
    Renderer(window)
}

println("rectsl sdf renderer ready")
start(renderer::run)
