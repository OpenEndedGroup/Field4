import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false // GPU timings remain in Field's existing renderer profiler.
once.renderer {
    window = Window("Flow grain playground", initialDimensions = Rect(80.0, 80.0, 960.0, 720.0))
    Renderer(window)
}
start(renderer::run)
