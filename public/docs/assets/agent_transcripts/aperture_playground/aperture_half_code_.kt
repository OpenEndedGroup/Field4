import org.lwjgl.vulkan.VK10.*

propDown<RenderToTexture>(paintHalf) = RenderToTexture {
    width = (paintSource.width / 2).coerceAtLeast(1)
    height = (paintSource.height / 2).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0, 0, 0, 0)
    debugName = "paintHalf"
}
paintHalf.setDefaultSampler(anisotropyEnable = false)
var module = moduleHere {
    textures("paintSource" to paintSource)
    paintHalf.apply()
    blend = false
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "filter" to mesh
mesh.resize(3)
paintHalf += "filter" to module
// Ordered before the screen pass, after the parent texture's producer.
renderer.renderList.preTargets["paintHalf"] = paintHalf
