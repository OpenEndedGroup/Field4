import afield.four.targets.RenderToTexture
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK10.*

val source = fromContextWithNameOrNull<RenderToTexture>("paintSource")!!
prop<RenderToTexture>(paintBroad) = RenderToTexture {
    width = (source.width/4).coerceAtLeast(1)
    height = (source.height/4).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0,0,0,0)
    debugName = "paintBroad"
}
paintBroad.setDefaultSampler(anisotropyEnable = false)
val input = fromContextWithNameOrNull<RenderToTexture>("paintQuarter")!!
var module = moduleHere {
    textures("paintQuarter" to input)
    paintBroad.apply()
    blend = false
    depthTest = false
    depthWrite = false
}
var mesh = Mesh()
module += "filter" to mesh
mesh.resize(3)
paintBroad += "filter" to module
// Ordered before the screen pass, after the parent texture's producer.
renderer.renderList.preTargets["paintBroad"] = paintBroad

// x/y stretch the aperture; z changes its rim softness; w is gain.
mesh.aperture = { vec(1.0,1.0,1.0,1.0) }
