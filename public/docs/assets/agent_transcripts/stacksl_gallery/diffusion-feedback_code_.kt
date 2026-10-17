// A feedback StackSL box. The fragment shader has a `{{ stack.auto.program }}`
// slot, and the program in this box's `stackSL` property samples `source`: the
// frame this box drew last time, so the picture feeds back into itself.
//
// A window cannot be read back like that, so this box draws into a ping-pong
// target instead. It holds two images, draws into one while `source` reads the
// other, and swaps them every frame; `blitToScreen` puts the newest in the window.
//
// [[property stackSL]]

import afield.graphics.vulkan.targets.PingPongRenderToTexture

prop<afield.graphics.vulkan.targets.PingPongRenderToTexture>(target)
prop<afield.graphics.texture.HasImageView>(source)

// `once`, so re-running the box keeps the images and the trails already in them.
once.target {
    PingPongRenderToTexture(renderer) { useDepth = false }
}
source = target.otherImageView

val module = moduleHere { target.apply() }
val m = Mesh()
module += m named "fullscreen"
m.resize(3) // three vertices, no attributes: the vertex shader makes the triangle
target += "fullscreen" to module

// pc.params.x advances per rendered frame. Edit the scale below to change its rate;
// a faster window frame rate also advances more program time per wall-clock second.
val first = renderer.frameCounter
m.params = { vec((renderer.frameCounter - first) * 1.0 / 48, 0.0, 0.0, 0.0) }

// Bind this box's StackSL source into the `program` slot (compiles + hot-reloads).
// `stackSL` is null when no box up the graph has one; bind the empty program then.
module.program = stackSL ?: ""

// Every box in this gallery draws the whole window, so they all register under one
// name: launching a box replaces whichever one the window was showing.
target.blitToScreen(name = "stacksl-gallery")
