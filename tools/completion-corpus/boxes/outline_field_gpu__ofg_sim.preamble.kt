import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*start*/
/*compute-structure-type:State:State*/
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
/*compute-structure-type:Wall:Wall*/
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
/*compute-pc-structure:state*/ var Structure.state by Structure.FromStructure<Any>() // written this frame
/*compute-pc-binding:state*/ var ComputeMesh.state by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // written this frame
/*compute-pc-binding-group:state*/ var ComputeMeshGroup.state by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // written this frame
/*compute-pc-structure:prevState*/ var Structure.prevState by Structure.FromStructure<Any>() // read this frame
/*compute-pc-binding:prevState*/ var ComputeMesh.prevState by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // read this frame
/*compute-pc-binding-group:prevState*/ var ComputeMeshGroup.prevState by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // read this frame
/*compute-pc-structure:wall*/ var Structure.wall by Structure.FromStructure<Any>()
/*compute-pc-binding:wall*/ var ComputeMesh.wall by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:wall*/ var ComputeMeshGroup.wall by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:w*/ var Structure.w by Structure.FromStructure<Any>()
/*compute-pc-binding:w*/ var ComputeMesh.w by ComputeMesh.BoundFromStructure<Int>()
/*compute-pc-binding-group:w*/ var ComputeMeshGroup.w by ComputeMeshGroup.BoundFromStructure<Int>()
/*compute-pc-structure:h*/ var Structure.h by Structure.FromStructure<Any>()
/*compute-pc-binding:h*/ var ComputeMesh.h by ComputeMesh.BoundFromStructure<Int>()
/*compute-pc-binding-group:h*/ var ComputeMeshGroup.h by ComputeMeshGroup.BoundFromStructure<Int>()
/*compute-pc-structure:run*/ var Structure.run by Structure.FromStructure<Any>() // 0 while the CPU is editing the state
/*compute-pc-binding:run*/ var ComputeMesh.run by ComputeMesh.BoundFromStructure<Int>() // 0 while the CPU is editing the state
/*compute-pc-binding-group:run*/ var ComputeMeshGroup.run by ComputeMeshGroup.BoundFromStructure<Int>() // 0 while the CPU is editing the state
/*compute-pc-structure:feed*/ var Structure.feed by Structure.FromStructure<Any>()
/*compute-pc-binding:feed*/ var ComputeMesh.feed by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:feed*/ var ComputeMeshGroup.feed by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:kill*/ var Structure.kill by Structure.FromStructure<Any>()
/*compute-pc-binding:kill*/ var ComputeMesh.kill by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:kill*/ var ComputeMeshGroup.kill by ComputeMeshGroup.BoundFromStructure<Number>()
var Box.ofStateA by Inheritance<FrameBufferGroup>() /**/
val Once.ofStateA by Onceing<FrameBufferGroup>() /**/
val Why.ofStateA by Whying() /**/
var Box.ofStateB by Inheritance<FrameBufferGroup>() /**/
val Once.ofStateB by Onceing<FrameBufferGroup>() /**/
val Why.ofStateB by Whying() /**/
var Box.ofWall by Inheritance<FrameBufferGroup>() /**/
val Once.ofWall by Onceing<FrameBufferGroup>() /**/
val Why.ofWall by Whying() /**/
var Box.ofWidth by Inheritance<Int>() /**/
val Once.ofWidth by Onceing<Int>() /**/
val Why.ofWidth by Whying() /**/
var Box.ofHeight by Inheritance<Int>() /**/
val Once.ofHeight by Onceing<Int>() /**/
val Why.ofHeight by Whying() /**/
var Box.addOutline by Inheritance<(FLine) -> Unit>() /**/
val Once.addOutline by Onceing<(FLine) -> Unit>() /**/
val Why.addOutline by Whying() /**/
var Box.window by Inheritance<Window>() /*from:ofg_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:ofg_renderer*/ /**/
val Why.window by Whying() /*from:ofg_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:ofg_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:ofg_renderer*/ /**/
val Why.renderer by Whying() /*from:ofg_renderer*/ /**/
