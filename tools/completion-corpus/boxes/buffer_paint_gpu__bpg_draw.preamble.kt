import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:State:State*/
class State(
    members: List<Introspected.Member> = StateLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var a by Structure.FromStructure<afield.math.linalg.Vector2f>()
}
fun StateByteSize(): Long = 8L
fun StateLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("a", 0, PrimitiveType.vec2.left(), 8, false),
    )
fun HasBuffer.asState(): State =
    asStructure(State(), StateLayoutMembers())
fun BufferParameters.asState(): State =
    buffer.asState()
/*end*/
/*graphics-pc-structure:state*/ var Structure.state by Structure.FromStructure<Any>()
/*graphics-pc-binding:state*/ var Mesh.state by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:state*/ var MeshGroup.state by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:w*/ var Structure.w by Structure.FromStructure<Any>()
/*graphics-pc-binding:w*/ var Mesh.w by Mesh.BoundFromStructure<Int>()
/*graphics-pc-binding-group:w*/ var MeshGroup.w by MeshGroup.BoundFromStructure<Int>()
/*graphics-pc-structure:h*/ var Structure.h by Structure.FromStructure<Any>()
/*graphics-pc-binding:h*/ var Mesh.h by Mesh.BoundFromStructure<Int>()
/*graphics-pc-binding-group:h*/ var MeshGroup.h by MeshGroup.BoundFromStructure<Int>()
/*graphics-pc-structure:zoom*/ var Structure.zoom by Structure.FromStructure<Any>() // whole framebuffer pixels per cell
/*graphics-pc-binding:zoom*/ var Mesh.zoom by Mesh.BoundFromStructure<Int>() // whole framebuffer pixels per cell
/*graphics-pc-binding-group:zoom*/ var MeshGroup.zoom by MeshGroup.BoundFromStructure<Int>() // whole framebuffer pixels per cell
var Box.bpStateA by Inheritance<FrameBufferGroup>() /*from:bpg_sim*/ /**/
val Once.bpStateA by Onceing<FrameBufferGroup>() /*from:bpg_sim*/ /**/
val Why.bpStateA by Whying() /*from:bpg_sim*/ /**/
var Box.bpStateB by Inheritance<FrameBufferGroup>() /*from:bpg_sim*/ /**/
val Once.bpStateB by Onceing<FrameBufferGroup>() /*from:bpg_sim*/ /**/
val Why.bpStateB by Whying() /*from:bpg_sim*/ /**/
var Box.bpWidth by Inheritance<Int>() /*from:bpg_sim*/ /**/
val Once.bpWidth by Onceing<Int>() /*from:bpg_sim*/ /**/
val Why.bpWidth by Whying() /*from:bpg_sim*/ /**/
var Box.bpHeight by Inheritance<Int>() /*from:bpg_sim*/ /**/
val Once.bpHeight by Onceing<Int>() /*from:bpg_sim*/ /**/
val Why.bpHeight by Whying() /*from:bpg_sim*/ /**/
var Box.window by Inheritance<Window>() /*from:bpg_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:bpg_renderer*/ /**/
val Why.window by Whying() /*from:bpg_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:bpg_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:bpg_renderer*/ /**/
val Why.renderer by Whying() /*from:bpg_renderer*/ /**/
