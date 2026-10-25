import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
once.renderer {
    window = Window("Field · photo_mount_path_trace")
    Renderer(window)
}
start(renderer::run)
