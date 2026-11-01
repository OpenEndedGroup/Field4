/*
The renderer window: the parent box every graphics lesson runs beneath.

It opens a native Window, builds a Renderer for it and runs the frame loop. Connect a lesson box below
this one and that box can use `renderer` directly; the two `prop` lines below publish `window` and
`renderer` to every box beneath this one in the graph.
*/
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

// Ask for an HDR swapchain where the display supports one.
Options.enableHDR = true

// `once` builds the Window and Renderer the first time this box runs and keeps them on later runs, so
// re-running the box does not open a second window.
once.renderer {
    window = Window("Field")
    Renderer(window)
}

// Run the renderer's frame loop until this box is stopped.
start(renderer::run)
