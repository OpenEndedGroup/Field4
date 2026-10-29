import org.lwjgl.vulkan.VK10.*

// Screen composite: apertureControl.x is gain; y selects the display mode.
// Edit and rerun apertureControl.set below to update the vector read by the push supplier.
var module = moduleHere {
    textures("paintSource" to paintSource, "paintMedium" to paintMedium, "paintBroad" to paintBroad)
    depthTest = false
    depthWrite = false
    blend = true
    srcColor = VK_BLEND_FACTOR_ONE
    dstColor = VK_BLEND_FACTOR_ONE
    srcAlpha = VK_BLEND_FACTOR_ZERO
    dstAlpha = VK_BLEND_FACTOR_ONE
}
var mesh = Mesh()
module += "composite" to mesh
mesh.resize(3)
mesh.displayControl = { apertureControl }
renderer.renderList += "paint-composite" to module
println("paint composite ready")

// y: 0 aperture, 1 sharp, 2 radius guide. Edit here and re-execute.
apertureControl.set(1.0f, 0.0f, 0.0f, 0.0f)
