/*
Scaffolding, and Kotlin for two reasons named in the FieldML box next door:
`prop`/`once` are SOURCE MACROS (mechanism A), and `Renderer.run` is a suspend
*member*, which the bridge can satisfy the type of but cannot call into.
*/
prop<Window>(window)
prop<Renderer>(renderer)
once.renderer {
    window = Window("FieldML - vertex data, written every frame")
    Renderer(window)
}
println("renderer ready - now run the FieldML box")
start(renderer::run)
