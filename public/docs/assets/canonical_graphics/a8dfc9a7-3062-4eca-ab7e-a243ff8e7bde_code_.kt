/*
Ping-pong render to texture: feedback from one frame to the next.

A pass cannot read the image it is writing, so PingPongRenderToTexture keeps two images and swaps
their roles every frame. The fragment shader (open "fragment" in the property switcher) samples
last frame's image as `otherView`, blurs and fades it a little, and stamps a moving dot into it, so
the dot leaves a trail that spreads out and dies away. The child box `5. draw pingpoing` displays it.
Run this box beneath the renderer (`window`) box.
*/
import org.lwjgl.vulkan.VK10.*

prop<PingPongRenderToTexture>(target) = PingPongRenderToTexture {
    clearFirst = false
    useDepth = false
}

// The image that is not being drawn into this frame, i.e. the previous frame's result.
prop<HasImageView>(otherView) = target.otherImageView

renderer.renderList += "target" to target

var module = moduleHere {
    /*
    A disabled alternative: a 16-bit float colour format for the module.
     imageFormat = VK_FORMAT_R16G16B16A16_SFLOAT
    Match this module's attachments to the target it draws into.
    */
    +target
}
var m = Mesh()
module += "mainMesh" to m
target += "boo" to module

// The shaders read this push constant to move the dot. It advances a fixed 1/60 s each frame rather
// than following the clock, so a slow frame slows the animation instead of making it jump.
var time = 0f
m.time = {
    time += 1f / 60
    time
}

// One triangle covering the target in clip space (the vertex shader ignores any camera).
m.set(3, 1) {
    pos.add(-1, -1, 0, 3, -1, 0, -1, 3, 0)
    tri.add(0, 1, 2)
}
