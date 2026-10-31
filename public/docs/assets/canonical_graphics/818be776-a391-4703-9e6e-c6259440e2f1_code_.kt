// Render to texture: drawing offscreen.
//
// A RenderToTexture is an offscreen image a module can draw into instead of the window. This box
// publishes it as `target`; a child box can then republish it under the sampler name its own
// fragment shader declares and draw it. Run this box beneath the renderer (`window`) box.
import org.lwjgl.vulkan.VK10.*

prop<RenderToTexture>(target) = RenderToTexture {
    clearFirst = false
    useDepth = true
    // A disabled alternative: a 16-bit float colour format for the target.
    //  format = VK_FORMAT_R16G16B16A16_SFLOAT
}

// Schedule the offscreen pass as part of every frame.
renderer.renderList += "target" to target

var module = moduleHere {
    // A disabled alternative: a 16-bit float colour format for the module.
    //  imageFormat = VK_FORMAT_R16G16B16A16_SFLOAT
}
var m = Mesh()

module += "mainMesh" to m
// Draw into the target rather than into the window.
target += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m
// finalColor is a push-constant member; the fragment shader declares it but currently draws white.
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
