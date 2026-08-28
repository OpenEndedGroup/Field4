import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field UI on a texture")
    Renderer(window)
}

println("Field UI texture renderer ready")
start(renderer::run)
