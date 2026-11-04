// The window and the renderer, from FieldML.
//
// `once "renderer"` runs its function the first time this box asks for that key and hands
// back the same Renderer on every later run, so running this box again does NOT open a
// second window. `Renderer.run` is a `suspend` member; `hostLaunch` starts it on Field's
// runloop and calls the closure when it returns (when the window closes).
let r = once "renderer" (fun u ->
  let w = hostNew {title = "FieldML - a frames body that waits"} (hostClass "afield.graphics.vulkan.util.Window") in
  let r = hostNew {mainWindow = w} (hostClass "afield.graphics.vulkan.Renderer") in
  let _ = hostLaunch "run" () r (fun ignored -> print "renderer stopped") in
  r)

// Publish it where the box below looks for it.
let _ = setProp "renderer" r

show r
