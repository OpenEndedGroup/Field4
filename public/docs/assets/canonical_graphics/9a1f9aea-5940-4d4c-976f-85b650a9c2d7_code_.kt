/*
Producer for the blur chain (window -> 5. Bluring -> 5. kernel -> 5. draw blur).

Renders a checkerboard into an offscreen RenderToTexture, published as `target` so the child box
`5. kernel` can sample it. The checkerboard comes from the fragment shader (open "fragment" in the
property switcher), which colours by gl_FragCoord; hard edges make the blur easy to see.
Run this box beneath the renderer (`window`) box.
*/
import org.lwjgl.vulkan.VK10.*

prop<RenderToTexture>(target) = RenderToTexture {
    clearFirst = false
    useDepth = true
}

// Schedule the offscreen pass as part of every frame.
renderer.renderList += "target" to target

var module = moduleHere {
    /*
    A disabled alternative: a 16-bit float colour format for the module.
     imageFormat = VK_FORMAT_R16G16B16A16_SFLOAT
    Match this module's colour and depth attachments to the target it draws into.
    */
    +target
}
var m = Mesh()

module += "mainMesh" to m
// Draw into the target rather than into the window.
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
