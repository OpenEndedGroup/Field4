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
/*start*/
/*graphics-structure-type:Wall:Wall*/
class Wall(
    members: List<Introspected.Member> = WallLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var d by Structure.FromStructure<Float>()
}
fun WallByteSize(): Long = 4L
fun WallLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("d", 0, PrimitiveType.float.left(), 4, false),
    )
fun HasBuffer.asWall(): Wall =
    asStructure(Wall(), WallLayoutMembers())
fun BufferParameters.asWall(): Wall =
    buffer.asWall()
/*end*/
/*graphics-pc-structure:state*/ var Structure.state by Structure.FromStructure<Any>()
/*graphics-pc-binding:state*/ var Mesh.state by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:state*/ var MeshGroup.state by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:wall*/ var Structure.wall by Structure.FromStructure<Any>()
/*graphics-pc-binding:wall*/ var Mesh.wall by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:wall*/ var MeshGroup.wall by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:w*/ var Structure.w by Structure.FromStructure<Any>()
/*graphics-pc-binding:w*/ var Mesh.w by Mesh.BoundFromStructure<Int>()
/*graphics-pc-binding-group:w*/ var MeshGroup.w by MeshGroup.BoundFromStructure<Int>()
/*graphics-pc-structure:h*/ var Structure.h by Structure.FromStructure<Any>()
/*graphics-pc-binding:h*/ var Mesh.h by Mesh.BoundFromStructure<Int>()
/*graphics-pc-binding-group:h*/ var MeshGroup.h by MeshGroup.BoundFromStructure<Int>()
/*graphics-pc-structure:zoom*/ var Structure.zoom by Structure.FromStructure<Any>()
/*graphics-pc-binding:zoom*/ var Mesh.zoom by Mesh.BoundFromStructure<Int>()
/*graphics-pc-binding-group:zoom*/ var MeshGroup.zoom by MeshGroup.BoundFromStructure<Int>()
var Box.ofStateA by Inheritance<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Once.ofStateA by Onceing<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Why.ofStateA by Whying() /*from:ofg_sim*/ /**/
var Box.ofStateB by Inheritance<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Once.ofStateB by Onceing<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Why.ofStateB by Whying() /*from:ofg_sim*/ /**/
var Box.ofWall by Inheritance<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Once.ofWall by Onceing<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Why.ofWall by Whying() /*from:ofg_sim*/ /**/
var Box.ofWidth by Inheritance<Int>() /*from:ofg_sim*/ /**/
val Once.ofWidth by Onceing<Int>() /*from:ofg_sim*/ /**/
val Why.ofWidth by Whying() /*from:ofg_sim*/ /**/
var Box.ofHeight by Inheritance<Int>() /*from:ofg_sim*/ /**/
val Once.ofHeight by Onceing<Int>() /*from:ofg_sim*/ /**/
val Why.ofHeight by Whying() /*from:ofg_sim*/ /**/
var Box.addOutline by Inheritance<(FLine) -> Unit>() /*from:ofg_sim*/ /**/
val Once.addOutline by Onceing<(FLine) -> Unit>() /*from:ofg_sim*/ /**/
val Why.addOutline by Whying() /*from:ofg_sim*/ /**/
var Box.window by Inheritance<Window>() /*from:ofg_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:ofg_renderer*/ /**/
val Why.window by Whying() /*from:ofg_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:ofg_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:ofg_renderer*/ /**/
val Why.renderer by Whying() /*from:ofg_renderer*/ /**/
