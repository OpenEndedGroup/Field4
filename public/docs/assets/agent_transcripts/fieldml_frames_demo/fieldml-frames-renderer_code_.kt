// Scaffolding, and it is Kotlin for two reasons worth naming.
//
// `prop` and `once` are SOURCE MACROS -- mechanism A of dev_docs/FIELDML_HOST_BRIDGE.md.
// They rewrite Kotlin source before it is compiled, so there is no JVM member for a
// reflective bridge to reach. And `Renderer.run` is a `suspend` member: on the JVM it is
// `run(Continuation)`, and FieldML can now *satisfy* a suspend function type but still
// cannot *call into* one, because it has no continuation to pass and nowhere to resume.
//
// Everything that actually draws, and everything that moves, is in the FieldML box.
prop<Window>(window)
prop<Renderer>(renderer)
once.renderer {
    window = Window("FieldML - a triangle that moves")
    Renderer(window)
}
println("renderer ready - now run the FieldML box")
start(renderer::run)
