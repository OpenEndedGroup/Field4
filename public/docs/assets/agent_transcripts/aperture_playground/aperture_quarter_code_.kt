import afield.four.targets.RenderToTexture
import afield.four.modules.fromContextWithNameOrNull
import org.lwjgl.vulkan.VK10.*

val source = fromContextWithNameOrNull<RenderToTexture>("paintSource")!!
prop<RenderToTexture>(paintQuarter) = RenderToTexture {
    width = (source.width/4).coerceAtLeast(1)
    height = (source.height/4).coerceAtLeast(1)
    format = VK_FORMAT_R16G16B16A16_SFLOAT
    useDepth = false
    clearFirst = true
    clearColor = vec(0,0,0,0)
    debugName = "paintQuarter"
}
paintQuarter.setDefaultSampler(anisotropyEnable = false)
val input = fromContextWithNameOrNull<RenderToTexture>("paintHalf")!!
var module = moduleHere {
    textures("paintHalf" to input)
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
