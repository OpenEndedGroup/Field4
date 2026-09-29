// The window the polytope draws in. Launch this box first, then run the polytope box beneath it.
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Polytope", initialDimensions = Rect(80.0, 80.0, 720.0, 720.0))
    Renderer(window)
}

start(renderer::run)
