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
/*graphics-structure-type:Mask:Mask*/
class Mask(
    members: List<Introspected.Member> = MaskLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var a by Structure.FromStructure<afield.math.linalg.Vector2f>()
}
fun MaskByteSize(): Long = 8L
fun MaskLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("a", 0, PrimitiveType.vec2.left(), 8, false),
    )
fun HasBuffer.asMask(): Mask =
    asStructure(Mask(), MaskLayoutMembers())
fun BufferParameters.asMask(): Mask =
    buffer.asMask()
/*end*/
/*graphics-pc-structure:mask*/ var Structure.mask by Structure.FromStructure<Any>()
/*graphics-pc-binding:mask*/ var Mesh.mask by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:mask*/ var MeshGroup.mask by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:w*/ var Structure.w by Structure.FromStructure<Any>()
/*graphics-pc-binding:w*/ var Mesh.w by Mesh.BoundFromStructure<Int>()
/*graphics-pc-binding-group:w*/ var MeshGroup.w by MeshGroup.BoundFromStructure<Int>()
/*graphics-pc-structure:h*/ var Structure.h by Structure.FromStructure<Any>()
/*graphics-pc-binding:h*/ var Mesh.h by Mesh.BoundFromStructure<Int>()
/*graphics-pc-binding-group:h*/ var MeshGroup.h by MeshGroup.BoundFromStructure<Int>()
/*graphics-pc-structure:zoom*/ var Structure.zoom by Structure.FromStructure<Any>()
/*graphics-pc-binding:zoom*/ var Mesh.zoom by Mesh.BoundFromStructure<Int>()
/*graphics-pc-binding-group:zoom*/ var MeshGroup.zoom by MeshGroup.BoundFromStructure<Int>()
var Box.fcMask by Inheritance<FrameBufferGroup>() /*from:fcg_fill*/ /**/
val Once.fcMask by Onceing<FrameBufferGroup>() /*from:fcg_fill*/ /**/
val Why.fcMask by Whying() /*from:fcg_fill*/ /**/
var Box.fcWidth by Inheritance<Int>() /*from:fcg_fill*/ /**/
val Once.fcWidth by Onceing<Int>() /*from:fcg_fill*/ /**/
val Why.fcWidth by Whying() /*from:fcg_fill*/ /**/
var Box.fcHeight by Inheritance<Int>() /*from:fcg_fill*/ /**/
val Once.fcHeight by Onceing<Int>() /*from:fcg_fill*/ /**/
val Why.fcHeight by Whying() /*from:fcg_fill*/ /**/
var Box.window by Inheritance<Window>() /*from:fcg_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:fcg_renderer*/ /**/
val Why.window by Whying() /*from:fcg_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:fcg_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:fcg_renderer*/ /**/
val Why.renderer by Whying() /*from:fcg_renderer*/ /**/
