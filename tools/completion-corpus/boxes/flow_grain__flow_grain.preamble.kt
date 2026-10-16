import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.vulkan.targets.ComputeTarget
import afield.ui.controls.EffectiveParameter
import afield.runtime.lifecycle.SimulationReset
import afield.graphics.vulkan.buffers.FrameBufferGroup
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.graphics.shader.*
import afield.graphics.scene.*
import afield.graphics.render.*
import afield.document.context.*
import afield.graphics.vulkan.buffers.*
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
    var v by Structure.FromStructure<afield.math.linalg.Vector4f>()
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
/*graphics-structure-type:Tiles:Tiles*/
class Tiles(
    members: List<Introspected.Member> = TilesLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var v by Structure.FromStructure<afield.math.linalg.Vector4f>()
}
fun TilesByteSize(): Long = 16L
fun TilesLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("v", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asTiles(): Tiles =
    asStructure(Tiles(), TilesLayoutMembers())
fun BufferParameters.asTiles(): Tiles =
    buffer.asTiles()
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
/*graphics-pc-structure:item*/ var Structure.item by Structure.FromStructure<Any>() // Current pose and MotionItems previous affine/validity.
/*graphics-pc-binding:item*/ var Mesh.item by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // Current pose and MotionItems previous affine/validity.
/*graphics-pc-binding-group:item*/ var MeshGroup.item by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // Current pose and MotionItems previous affine/validity.
/*graphics-pc-structure:velocity*/ var Structure.velocity by Structure.FromStructure<Any>() // Current projected fluid velocity.
/*graphics-pc-binding:velocity*/ var Mesh.velocity by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // Current projected fluid velocity.
/*graphics-pc-binding-group:velocity*/ var MeshGroup.velocity by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // Current projected fluid velocity.
/*graphics-pc-structure:tiles*/ var Structure.tiles by Structure.FromStructure<Any>() // Resident world coordinates and generations.
/*graphics-pc-binding:tiles*/ var Mesh.tiles by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // Resident world coordinates and generations.
/*graphics-pc-binding-group:tiles*/ var MeshGroup.tiles by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // Resident world coordinates and generations.
/*graphics-pc-structure:history*/ var Structure.history by Structure.FromStructure<Any>() // FrameHistory's complete 68-float layout.
/*graphics-pc-binding:history*/ var Mesh.history by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // FrameHistory's complete 68-float layout.
/*graphics-pc-binding-group:history*/ var MeshGroup.history by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // FrameHistory's complete 68-float layout.
/*graphics-pc-structure:clock*/ var Structure.clock by Structure.FromStructure<Any>() // x time; y dt; zw reserved.
/*graphics-pc-binding:clock*/ var Mesh.clock by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // x time; y dt; zw reserved.
/*graphics-pc-binding-group:clock*/ var MeshGroup.clock by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // x time; y dt; zw reserved.
/*graphics-pc-structure:look*/ var Structure.look by Structure.FromStructure<Any>() // x world grain cell; y stroke seconds; z gain; w debug view.
/*graphics-pc-binding:look*/ var Mesh.look by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>() // x world grain cell; y stroke seconds; z gain; w debug view.
/*graphics-pc-binding-group:look*/ var MeshGroup.look by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>() // x world grain cell; y stroke seconds; z gain; w debug view.
var Box.flowA by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.flowA by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.flowA by Whying() /*from:flow_resources*/ /**/
var Box.flowB by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.flowB by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.flowB by Whying() /*from:flow_resources*/ /**/
var Box.advected by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.advected by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.advected by Whying() /*from:flow_resources*/ /**/
var Box.divergence by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.divergence by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.divergence by Whying() /*from:flow_resources*/ /**/
var Box.pressureA by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.pressureA by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.pressureA by Whying() /*from:flow_resources*/ /**/
var Box.pressureB by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.pressureB by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.pressureB by Whying() /*from:flow_resources*/ /**/
var Box.particleA by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.particleA by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.particleA by Whying() /*from:flow_resources*/ /**/
var Box.particleB by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.particleB by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.particleB by Whying() /*from:flow_resources*/ /**/
var Box.tileHeaders by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.tileHeaders by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.tileHeaders by Whying() /*from:flow_resources*/ /**/
var Box.cameraHistory by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.cameraHistory by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.cameraHistory by Whying() /*from:flow_resources*/ /**/
var Box.itemHistory by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.itemHistory by Onceing<FrameBufferGroup>() /*from:flow_resources*/ /**/
val Why.itemHistory by Whying() /*from:flow_resources*/ /**/
var Box.resetFlow by ReactiveInheritance<SimulationReset>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.resetFlow by Onceing<SimulationReset>() /*from:flow_resources*/ /**/
val Why.resetFlow by Whying() /*from:flow_resources*/ /**/
var Box.force by ReactiveInheritance<EffectiveParameter>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.force by Onceing<EffectiveParameter>() /*from:flow_resources*/ /**/
val Why.force by Whying() /*from:flow_resources*/ /**/
var Box.grainCell by ReactiveInheritance<EffectiveParameter>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.grainCell by Onceing<EffectiveParameter>() /*from:flow_resources*/ /**/
val Why.grainCell by Whying() /*from:flow_resources*/ /**/
var Box.strokeTime by ReactiveInheritance<EffectiveParameter>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.strokeTime by Onceing<EffectiveParameter>() /*from:flow_resources*/ /**/
val Why.strokeTime by Whying() /*from:flow_resources*/ /**/
var Box.flowLook by ReactiveInheritance<afield.math.linalg.Vec4>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.flowLook by Onceing<afield.math.linalg.Vec4>() /*from:flow_resources*/ /**/
val Why.flowLook by Whying() /*from:flow_resources*/ /**/
var Box.flowClock by ReactiveInheritance<afield.math.linalg.Vec4>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.flowClock by Onceing<afield.math.linalg.Vec4>() /*from:flow_resources*/ /**/
val Why.flowClock by Whying() /*from:flow_resources*/ /**/
var Box.flowEmitter by ReactiveInheritance<afield.math.linalg.Vec4>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.flowEmitter by Onceing<afield.math.linalg.Vec4>() /*from:flow_resources*/ /**/
val Why.flowEmitter by Whying() /*from:flow_resources*/ /**/
var Box.flowSimulation by ReactiveInheritance<ComputeTarget>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.flowSimulation by Onceing<ComputeTarget>() /*from:flow_resources*/ /**/
val Why.flowSimulation by Whying() /*from:flow_resources*/ /**/
var Box.flowStep by ReactiveInheritance<java.util.concurrent.atomic.AtomicLong>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.flowStep by Onceing<java.util.concurrent.atomic.AtomicLong>() /*from:flow_resources*/ /**/
val Why.flowStep by Whying() /*from:flow_resources*/ /**/
var Box.travel by ReactiveInheritance<java.util.concurrent.atomic.AtomicBoolean>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.travel by Onceing<java.util.concurrent.atomic.AtomicBoolean>() /*from:flow_resources*/ /**/
val Why.travel by Whying() /*from:flow_resources*/ /**/
var Box.flowWidth by ReactiveInheritance<EffectiveParameter>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.flowWidth by Onceing<EffectiveParameter>() /*from:flow_resources*/ /**/
val Why.flowWidth by Whying() /*from:flow_resources*/ /**/
var Box.flowSurface by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /*from:flow_resources*/ /**/
val Once.flowSurface by Onceing<RenderToTexture>() /*from:flow_resources*/ /**/
val Why.flowSurface by Whying() /*from:flow_resources*/ /**/
var Box.window by Inheritance<Window>() /*from:flow_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:flow_renderer*/ /**/
val Why.window by Whying() /*from:flow_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:flow_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:flow_renderer*/ /**/
val Why.renderer by Whying() /*from:flow_renderer*/ /**/
