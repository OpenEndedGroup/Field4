import afield.four.targets.RenderToTexture
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK10.*

// Full-resolution RGBA16F source. RGBA hold light at radii 0, 4, 10, 24 px.
// Alpha is the fourth light band, so every intermediate filter must use blend=false.
// Re-run the downstream boxes after changing the dimensions.
prop<RenderToTexture>(paintSource)
thisBox.paintSource = RenderToTexture {
    width = window.width
    height = window.height
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0,0,0,0)
    debugName = "paint-source"
}
thisBox.paintSource.setDefaultSampler(anisotropyEnable = false)
renderer.renderList.preTargets["paint-source"] = thisBox.paintSource
prop<afield.stdlib.Vec4>(apertureControl)
thisBox.apertureControl = vec(1.5,0,0,0)
println("paint source RTT ready: ${thisBox.paintSource.width}x${thisBox.paintSource.height}")

var module = moduleHere {
    paintSource.apply()
    blend = false
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "source" to mesh
mesh.resize(3)
paintSource += "source" to module
// x = defocus radius in framebuffer pixels, y = energy, z = sprite radius, w = guide mix.
mesh.look = { vec(24.0,1.0,0.0,0.0) }
mesh.size = { vec(paintSource.width,paintSource.height,0,0) }

// Nest the two editable reduction helpers without hiding their shaders.
thisBox.frame = Rect(360.0,70.0,260.0,270.0)
thisBox.inside.boxes.getValue("aperture_half").nestedIn = thisBox
thisBox.inside.boxes.getValue("aperture_half").frame = Rect(15.0,70.0,240.0,55.0)
thisBox.inside.boxes.getValue("aperture_quarter").nestedIn = thisBox
thisBox.inside.boxes.getValue("aperture_quarter").frame = Rect(15.0,150.0,240.0,55.0)
