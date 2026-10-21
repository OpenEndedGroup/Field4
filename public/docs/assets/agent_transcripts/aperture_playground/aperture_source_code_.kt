import org.lwjgl.vulkan.VK10.*

/*
Full-resolution RGBA16F source. RGBA hold light at radii 0, 4, 10, 24 px.
Alpha is the fourth light band, so every intermediate filter must use blend=false.
propDown: re-running this box with new dimensions re-runs the passes that read paintSource.
*/
propDown<RenderToTexture>(paintSource)
paintSource = RenderToTexture {
    width = window.width
    height = window.height
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0, 0, 0, 0)
    debugName = "paint-source"
}
paintSource.setDefaultSampler(anisotropyEnable = false)
renderer.renderList.preTargets["paint-source"] = paintSource
prop<afield.math.linalg.Vec4>(apertureControl)
apertureControl = vec(1.5, 0, 0, 0)
println("paint source RTT ready: ${paintSource.width}x${paintSource.height}")

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
// x = requested maximum defocus pixels (capped at 24), y = source energy; z/w are unused.
mesh.look = { vec(24.0, 1.0, 0.0, 0.0) }
mesh.size = { vec(paintSource.width, paintSource.height, 0, 0) }

// Nest the two editable reduction helpers without hiding their shaders.
frame = Rect(360.0, 70.0, 260.0, 270.0)
inside.boxes.getValue("aperture_half").nestedIn = thisBox
inside.boxes.getValue("aperture_half").frame = Rect(15.0, 70.0, 240.0, 55.0)
inside.boxes.getValue("aperture_quarter").nestedIn = thisBox
inside.boxes.getValue("aperture_quarter").frame = Rect(15.0, 150.0, 240.0, 55.0)
