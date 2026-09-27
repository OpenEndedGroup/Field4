// The window the scenes beneath this box play in. Its main language is RectSL, so each child is a
// RectSL scene: run one and it plays here, looping, following every edit that compiles; the study
// tools draw their evidence on this window's GPU. It opens with the document (autorun).
// A scene's wall is fitted into the window, with black around it when their shapes differ.
prop<Window>(window)
prop<Renderer>(renderer)

once.renderer {
    window = Window("RectSL study", initialDimensions = Rect(80.0, 80.0, 960.0, 540.0))
    Renderer(window)
}

start(renderer::run)
