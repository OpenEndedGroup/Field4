import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Field:Field*/
class Field(
    members: List<Introspected.Member> = FieldLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var v by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun FieldByteSize(): Long = 16L
fun FieldLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("v", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asField(): Field =
    asStructure(Field(), FieldLayoutMembers())
fun BufferParameters.asField(): Field =
    buffer.asField()
/*end*/
/*start*/
/*graphics-structure-type:History:History*/
class History(
    members: List<Introspected.Member> = HistoryLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var frame by Structure.FromStructure<Any>()
}
fun HistoryByteSize(): Long = 272L
fun HistoryLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("frame", 0, listOf(
                        Introspected.Member("current", 0, PrimitiveType.mat4.left(), 0, false),
                        Introspected.Member("previous", 64, PrimitiveType.mat4.left(), 0, false),
                        Introspected.Member("inverseCurrent", 128, PrimitiveType.mat4.left(), 0, false),
                        Introspected.Member("reprojection", 192, PrimitiveType.mat4.left(), 0, false),
                        Introspected.Member("viewport", 256, PrimitiveType.vec4.left(), 0, false),
                    ).right(), 0, false),
    )
fun HasBuffer.asHistory(): History =
    asStructure(History(), HistoryLayoutMembers())
fun BufferParameters.asHistory(): History =
    buffer.asHistory()
/*end*/
/*graphics-pc-structure:particles*/ var Structure.particles by Structure.FromStructure<Any>() // Pairs of position/generation and smoothed velocity.
/*graphics-pc-binding:particles*/ var Mesh.particles by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // Pairs of position/generation and smoothed velocity.
/*graphics-pc-binding-group:particles*/ var MeshGroup.particles by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // Pairs of position/generation and smoothed velocity.
/*graphics-pc-structure:history*/ var Structure.history by Structure.FromStructure<Any>() // Camera matrices and target resolution.
/*graphics-pc-binding:history*/ var Mesh.history by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // Camera matrices and target resolution.
/*graphics-pc-binding-group:history*/ var MeshGroup.history by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // Camera matrices and target resolution.
/*graphics-pc-structure:viewport*/ var Structure.viewport by Structure.FromStructure<Any>() // xy current window dimensions; zw reserved.
/*graphics-pc-binding:viewport*/ var Mesh.viewport by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // xy current window dimensions; zw reserved.
/*graphics-pc-binding-group:viewport*/ var MeshGroup.viewport by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // xy current window dimensions; zw reserved.
/*graphics-pc-structure:look*/ var Structure.look by Structure.FromStructure<Any>() // x seconds of stroke; y half-width pixels; z gain; w reserved.
/*graphics-pc-binding:look*/ var Mesh.look by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // x seconds of stroke; y half-width pixels; z gain; w reserved.
/*graphics-pc-binding-group:look*/ var MeshGroup.look by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // x seconds of stroke; y half-width pixels; z gain; w reserved.
var Box.window by Inheritance<Window>() /*from:flow_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:flow_renderer*/ /**/
val Why.window by Whying() /*from:flow_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:flow_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:flow_renderer*/ /**/
val Why.renderer by Whying() /*from:flow_renderer*/ /**/
