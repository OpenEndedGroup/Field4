// The window the programs beneath this box draw in. Its main language is StackSL, so each child is a
// StackSL program: run one and it appears here, following every edit that compiles; the study tools
// render their evidence on this window's GPU. It opens with the document (autorun).
// It is square because a study frame is: uv runs 0..1 across both axes.
prop<Window>(window)
prop<Renderer>(renderer)

once.renderer {
    window = Window("StackSL study", initialDimensions = Rect(80.0, 80.0, 720.0, 720.0))
    Renderer(window)
}

start(renderer::run)
