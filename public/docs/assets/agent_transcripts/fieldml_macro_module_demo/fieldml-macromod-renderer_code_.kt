// Kotlin owns the renderer loop: FieldML cannot call the suspend member Renderer.run.
// The FieldML child builds the scene; launch this parent first to publish its renderer.
prop<Window>(window)
prop<Renderer>(renderer)
once.renderer {
    window = Window("FieldML - a scene built by moduleHere")
    Renderer(window)
}
println("renderer ready - now run the scene box")
start(renderer::run)