// Ping-pong render to texture: feedback from one frame to the next.
//
// A pass cannot read the image it is writing, so PingPongRenderToTexture keeps two images and swaps
// their roles every frame. The fragment shader (open "fragment" in the property switcher) samples
// last frame's image as `otherView`, offsets and recolours it, and writes the result, so the image
// drifts over time. The child box `5. draw pingpoing` displays it.
// Run this box beneath the renderer (`window`) box.
import org.lwjgl.vulkan.VK10.*

prop<PingPongRenderToTexture>(target) = PingPongRenderToTexture {
    clearFirst = false
    useDepth = false
}

// The image that is not being drawn into this frame, i.e. the previous frame's result.
prop<HasImageView>(otherView) = target.otherImageView

renderer.renderList += "target" to target

var module = moduleHere {
    // A disabled alternative: a 16-bit float colour format for the module.
    //  imageFormat = VK_FORMAT_R16G16B16A16_SFLOAT
    // Match this module's attachments to the target it draws into.
    +target
}
var m = Mesh()

module += "mainMesh" to m
target += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m
// finalColor is a push-constant member; the fragment shader declares it but currently ignores it.
m.finalColor = { vec(Math.random(), Math.random(), Math.random(), 1.0) }

// Target parameters stay live: this can be changed while the box runs.
target.parameters.clearFirst = true

var kc by keyCamera(camera = camera)
// loop runs its body once per frame, rebuilding one large triangle each time. The body's value
// (the trailing `true`) is not used.
loop {
    m.set(3, 1) {
        val z = Math.random() * 0.0
        pos.add(-3, 1, -1 + z, 1.0, -20.3, z, 3, 3, z)
        tri.add(0, 1, 2)
    }
    true
}
