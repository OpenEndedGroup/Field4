// The window and the renderer, from FieldML -- and the line that used to have to be Kotlin.
//
// `Renderer.run` is a `suspend` member: on the JVM it is `run(Continuation)`. `hostLaunch`
// starts it as its own coroutine on Field's runloop and calls the closure when it returns
// (when the window closes). Run this box once; running it again opens a second window.
let w = hostNew {title = "FieldML - a mesh written every frame"} (hostClass "afield.graphics.vulkan.util.Window")
let r = hostNew {mainWindow = w} (hostClass "afield.graphics.vulkan.Renderer")

// Publish it where the box below looks for it.
let _ = setProp "renderer" r

let job = hostLaunch "run" () r (fun ignored -> print "renderer stopped")

show job
