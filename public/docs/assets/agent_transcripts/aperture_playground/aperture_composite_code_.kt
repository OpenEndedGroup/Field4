import afield.four.targets.RenderToTexture
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK10.*

// Screen composite. B and G in the paint box control this shared vector.
val controls = fromContextWithNameOrNull<afield.stdlib.Vec4>("apertureControl")!!
val source = fromContextWithNameOrNull<RenderToTexture>("paintSource")!!
val medium = fromContextWithNameOrNull<RenderToTexture>("paintMedium")!!
val broad = fromContextWithNameOrNull<RenderToTexture>("paintBroad")!!
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
controls.set(1.0f,0.0f,0.0f,0.0f)
