import org.lwjgl.vulkan.VK10.*

// Screen composite: apertureControl.x is gain; y selects the display mode.
// Edit and rerun controls.set below to update the vector read by the push supplier.
val controls = fromContextWithNameOrNull<afield.math.linalg.Vec4>("apertureControl")!!
val source = paintSource
val medium = paintMedium
val broad = paintBroad
var module = moduleHere {
    textures("paintSource" to source, "paintMedium" to medium, "paintBroad" to broad)
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
mesh.displayControl = { controls }
renderer.renderList += "paint-composite" to module
println("paint composite ready")

// y: 0 aperture, 1 sharp, 2 radius guide. Edit here and re-execute.
controls.set(1.0f, 0.0f, 0.0f, 0.0f)
