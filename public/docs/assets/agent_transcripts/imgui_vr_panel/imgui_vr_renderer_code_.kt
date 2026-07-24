import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("ImGui VR panel")
    Renderer(window)
}

println("imgui vr renderer ready")
start(renderer::run)
