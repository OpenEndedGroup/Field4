import afield.four.util.Options

// `afield.four` has no headless mode: a compute-only dispatch still needs a Window for
// the device to exist, and it needs an awake display. So every box below is a child of
// this one and resolves `renderer` from it.
//
// RUN THIS BOX FIRST. Before it has run there is no Renderer anywhere and the others
// fail with a context error rather than a GPU one.
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · gpuProbe bench")
    Renderer(window)
}
start(renderer::run)
