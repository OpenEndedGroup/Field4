// Exact disc/aperture overlap: no texture or blur pass for this column.
var module = moduleHere {
    blend = true
    srcColor = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    dstColor = org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "sprites" to mesh
mesh.resize(24)
mesh.size = { vec(window.width,window.height,0,0) }
// x: maximum aperture pixels; y: outline; z: intensity; w: solid fill.
mesh.look = { vec(24.0,0.0,1.0,1.0) }
renderer.renderList += "aperture-sprites" to module
