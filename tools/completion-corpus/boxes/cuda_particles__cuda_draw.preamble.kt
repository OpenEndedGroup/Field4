import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Particles:Particles*/
class Particles(
    members: List<Introspected.Member> = ParticlesLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var p by Structure.FromStructure<afield.math.linalg.Vector4f>()
}
fun ParticlesByteSize(): Long = 16L
fun ParticlesLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("p", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asParticles(): Particles =
    asStructure(Particles(), ParticlesLayoutMembers())
fun BufferParameters.asParticles(): Particles =
    buffer.asParticles()
/*end*/
/*graphics-pc-structure:particles*/ var Structure.particles by Structure.FromStructure<Any>() // shared CUDA/Vulkan float4 buffer
/*graphics-pc-binding:particles*/ var Mesh.particles by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // shared CUDA/Vulkan float4 buffer
/*graphics-pc-binding-group:particles*/ var MeshGroup.particles by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // shared CUDA/Vulkan float4 buffer
/*graphics-pc-structure:aspectRatio*/ var Structure.aspectRatio by Structure.FromStructure<Any>() // window width / height
/*graphics-pc-binding:aspectRatio*/ var Mesh.aspectRatio by Mesh.BoundFromStructure<Number>() // window width / height
/*graphics-pc-binding-group:aspectRatio*/ var MeshGroup.aspectRatio by MeshGroup.BoundFromStructure<Number>() // window width / height
var Box.cudaParticles by Inheritance<VulkanBuffer>() /*from:cuda_flow*/ /**/
val Once.cudaParticles by Onceing<VulkanBuffer>() /*from:cuda_flow*/ /**/
val Why.cudaParticles by Whying() /*from:cuda_flow*/ /**/
var Box.cudaParticleCount by Inheritance<Int>() /*from:cuda_flow*/ /**/
val Once.cudaParticleCount by Onceing<Int>() /*from:cuda_flow*/ /**/
val Why.cudaParticleCount by Whying() /*from:cuda_flow*/ /**/
var Box.window by Inheritance<Window>() /*from:cuda_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:cuda_renderer*/ /**/
val Why.window by Whying() /*from:cuda_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:cuda_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:cuda_renderer*/ /**/
val Why.renderer by Whying() /*from:cuda_renderer*/ /**/
