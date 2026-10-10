// Scaffolding, and there is now exactly ONE reason it is Kotlin.
//
// `Renderer.run` is a `suspend` member: on the JVM it is `run(Continuation)`, and
// FieldML can *satisfy* a suspend function type but still cannot *call into* one --
// it has no continuation to pass and nowhere to resume. That is the whole of it.
//
// `prop` and `once` used to be the other reason, and they are not any more: they are
// source macros, mechanism A of dev_docs/FIELDML_HOST_BRIDGE.md, and reachable from
// FieldML since 2026-08-20. They are still written in Kotlin here only because this
// box has to exist anyway.
prop<Window>(window)
prop<Renderer>(renderer)
once.renderer {
    window = Window("FieldML - a scene built by moduleHere")
    Renderer(window)
}
println("renderer ready - now run the scene box")
start(renderer::run)