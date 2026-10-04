// Launch this box first: Alt+Up (Option+Up on macOS).
prop<Window>(window)
prop<Renderer>(renderer)
once.renderer {
    window = Window("Field · first native graphics")
    Renderer(window)
}
start(renderer::run)
