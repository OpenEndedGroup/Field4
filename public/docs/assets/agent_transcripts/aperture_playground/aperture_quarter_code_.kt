import org.lwjgl.vulkan.VK10.*

propDown<RenderToTexture>(paintQuarter) = RenderToTexture {
    width = (paintSource.width / 4).coerceAtLeast(1)
    height = (paintSource.height / 4).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0, 0, 0, 0)
    debugName = "paintQuarter"
}
paintQuarter.setDefaultSampler(anisotropyEnable = false)
var module = moduleHere {
    textures("paintHalf" to paintHalf)
    paintQuarter.apply()
    blend = false
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "filter" to mesh
mesh.resize(3)
paintQuarter += "filter" to module
// Ordered before the screen pass, after the parent texture's producer.
renderer.renderList.preTargets["paintQuarter"] = paintQuarter
