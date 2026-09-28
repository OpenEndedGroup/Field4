import afield.four.Renderer
import afield.four.util.Window
import afield.four.buffers.FrameBufferGroup
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import java.nio.*
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
    var a by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun StateByteSize(): Long = 16L
fun StateLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("a", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asState(): State =
    asStructure(State(), StateLayoutMembers())
fun BufferParameters.asState(): State =
    buffer.asState()
/*end*/
/*start*/
/*graphics-structure-type:Heat:Heat*/
class Heat(
    members: List<Introspected.Member> = HeatLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var a by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun HeatByteSize(): Long = 16L
fun HeatLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("a", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asHeat(): Heat =
    asStructure(Heat(), HeatLayoutMembers())
fun BufferParameters.asHeat(): Heat =
    buffer.asHeat()
/*end*/
/*graphics-pc-structure:state*/ var Structure.state by Structure.FromStructure<Any>()
/*graphics-pc-binding:state*/ var Mesh.state by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:state*/ var MeshGroup.state by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:heat*/ var Structure.heat by Structure.FromStructure<Any>()
/*graphics-pc-binding:heat*/ var Mesh.heat by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:heat*/ var MeshGroup.heat by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:aspectRatio*/ var Structure.aspectRatio by Structure.FromStructure<Any>()
/*graphics-pc-binding:aspectRatio*/ var Mesh.aspectRatio by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:aspectRatio*/ var MeshGroup.aspectRatio by MeshGroup.BoundFromStructure<Float>()
var Box.gsStateA by Inheritance<FrameBufferGroup>() /*from:gs_sim*/ /**/
val Once.gsStateA by Onceing<FrameBufferGroup>() /*from:gs_sim*/ /**/
var Box.gsStateB by Inheritance<FrameBufferGroup>() /*from:gs_sim*/ /**/
val Once.gsStateB by Onceing<FrameBufferGroup>() /*from:gs_sim*/ /**/
var Box.gsHeat by Inheritance<FrameBufferGroup>() /*from:gs_sim*/ /**/
val Once.gsHeat by Onceing<FrameBufferGroup>() /*from:gs_sim*/ /**/
var Box.window by Inheritance<Window>() /*from:gs_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:gs_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:gs_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:gs_renderer*/ /**/
