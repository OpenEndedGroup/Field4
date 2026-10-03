import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · polytope_layer_stack_path_trace")
    Renderer(window)
}
start(renderer::run)
