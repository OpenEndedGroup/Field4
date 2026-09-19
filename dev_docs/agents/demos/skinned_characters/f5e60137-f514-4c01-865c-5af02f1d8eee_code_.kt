import afield.four.util.Options

// The window and renderer that box 4 needs.
//
// Box 4 draws, and a drawing box needs a Renderer in scope. Every other GPU demo in this folder
// ships one of these -- frame_history_reprojection, path_tracer_instrument, path_tracing_emitters
// and path_tracing_medium each have exactly one box declaring `prop<Renderer>(renderer)`. This
// demo did not, so box 4 only ran under `field_backdoor.py --graphics-context`, where the agent's
// own context box supplied a Renderer by type. Opened the ordinary way -- `./field.sh` on this
// document -- box 4 failed with "must pass in a value for context parameter afield.four.Renderer".
//
// `prop<Renderer>(renderer)` publishes it to this box's children, which is why box 4 is connected
// beneath this one and can now say `renderer` by name.
prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
once.renderer {
    window = Window("Field · skinned characters")
    Renderer(window)
}
println("renderer ready — now run box 4")
start(renderer::run)
