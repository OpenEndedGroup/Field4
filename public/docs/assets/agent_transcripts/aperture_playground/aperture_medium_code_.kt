import org.lwjgl.vulkan.VK10.*

propDown<RenderToTexture>(paintMedium) = RenderToTexture {
    width = (paintSource.width / 2).coerceAtLeast(1)
    height = (paintSource.height / 2).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearColor = vec(0, 0, 0, 0)
    debugName = "paintMedium"
}
paintMedium.setDefaultSampler(anisotropyEnable = false)
var module = moduleHere {
    textures("paintHalf" to paintHalf)
    +paintMedium
    blend = false
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "filter" to mesh
mesh.resize(3)
paintMedium += "filter" to module
// Ordered before the screen pass, after the parent texture's producer.
renderer.renderList.preTargets["paintMedium"] = paintMedium

// x/y stretch the aperture; z changes its rim softness; w is gain.
mesh.aperture = { vec(1.0, 1.0, 1.0, 1.0) }
