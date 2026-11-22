// Launch this box first (Alt+Up; Option+Up on macOS): it opens a window and draws whatever is
// connected beneath it. Boxes under it read `window` and `renderer` by name.
prop<Window>(window)
prop<Renderer>(renderer)
once.renderer {
    window = Window("Field")
    Renderer(window)
}
start(renderer::run)
