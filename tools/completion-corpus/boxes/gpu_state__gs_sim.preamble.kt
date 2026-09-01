import afield.four.Renderer
import afield.four.util.Window
import afield.four.buffers.FrameBufferGroup
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*start*/
/*compute-structure-type:State:State*/
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
/*compute-structure-type:Anchors:Anchors*/
class Anchors(
    members: List<Introspected.Member> = AnchorsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var a by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun AnchorsByteSize(): Long = 16L
fun AnchorsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("a", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asAnchors(): Anchors =
    asStructure(Anchors(), AnchorsLayoutMembers())
fun BufferParameters.asAnchors(): Anchors =
    buffer.asAnchors()
/*end*/
/*start*/
/*compute-structure-type:Heat:Heat*/
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
/*start*/
/*compute-structure-type:FieldProbeBuffer:FieldProbeBuffer*/
class FieldProbeBuffer(
    members: List<Introspected.Member> = FieldProbeBufferLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var enabled by Structure.FromStructure<UInt>()
    var capacity by Structure.FromStructure<UInt>()
    var ordinalStart by Structure.FromStructure<UInt>()
    var ordinalEndExclusive by Structure.FromStructure<UInt>()
    var invocationCount by Structure.FromStructure<UInt>()
    var acceptedCount by Structure.FromStructure<UInt>()
    var finiteCount by Structure.FromStructure<UInt>()
    var typeTag by Structure.FromStructure<UInt>()
    var nanCount by Structure.FromStructure<UInt>()
    var infCount by Structure.FromStructure<UInt>()
    var nonZeroCount by Structure.FromStructure<UInt>()
    var typeMismatchCount by Structure.FromStructure<UInt>()
    var minOrderedX by Structure.FromStructure<UInt>()
    var minOrderedY by Structure.FromStructure<UInt>()
    var minOrderedZ by Structure.FromStructure<UInt>()
    var minOrderedW by Structure.FromStructure<UInt>()
    var maxOrderedX by Structure.FromStructure<UInt>()
    var maxOrderedY by Structure.FromStructure<UInt>()
    var maxOrderedZ by Structure.FromStructure<UInt>()
    var maxOrderedW by Structure.FromStructure<UInt>()
    var samples by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun FieldProbeBufferByteSize(): Long = 4194384L
fun FieldProbeBufferLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("enabled", 0, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("capacity", 4, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("ordinalStart", 8, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("ordinalEndExclusive", 12, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("invocationCount", 16, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("acceptedCount", 20, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("finiteCount", 24, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("typeTag", 28, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("nanCount", 32, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("infCount", 36, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("nonZeroCount", 40, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("typeMismatchCount", 44, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("minOrderedX", 48, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("minOrderedY", 52, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("minOrderedZ", 56, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("minOrderedW", 60, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("maxOrderedX", 64, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("maxOrderedY", 68, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("maxOrderedZ", 72, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("maxOrderedW", 76, PrimitiveType.uint.left(), 0, false),
        Introspected.Member("samples", 80, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asFieldProbeBuffer(): FieldProbeBuffer =
    asStructure(FieldProbeBuffer(), FieldProbeBufferLayoutMembers())
fun BufferParameters.asFieldProbeBuffer(): FieldProbeBuffer =
    buffer.asFieldProbeBuffer()
/*end*/
/*start*/
/*stack-auto:force*/
var ModulePointer.force: String
    get() = afield.stdlib.stackAutoShaderSource(this, "force")
    set(value) { afield.stdlib.stackAutoShaderSource(this, "force", value) }
var ComputeModulePointer.force: String
    get() = afield.stdlib.stackAutoShaderSource(this, "force")
    set(value) { afield.stdlib.stackAutoShaderSource(this, "force", value) }
var MeshModulePointer.force: String
    get() = afield.stdlib.stackAutoShaderSource(this, "force")
    set(value) { afield.stdlib.stackAutoShaderSource(this, "force", value) }
/*end*/
/*compute-pc-structure:state*/ var Structure.state by Structure.FromStructure<Any>() // this frame's half of the ping-pong — written
/*compute-pc-binding:state*/ var ComputeMesh.state by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // this frame's half of the ping-pong — written
/*compute-pc-binding-group:state*/ var ComputeMeshGroup.state by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // this frame's half of the ping-pong — written
/*compute-pc-structure:prevState*/ var Structure.prevState by Structure.FromStructure<Any>() // last frame's half — read
/*compute-pc-binding:prevState*/ var ComputeMesh.prevState by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // last frame's half — read
/*compute-pc-binding-group:prevState*/ var ComputeMeshGroup.prevState by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // last frame's half — read
/*compute-pc-structure:anchors*/ var Structure.anchors by Structure.FromStructure<Any>() // written once, read every frame, device-local
/*compute-pc-binding:anchors*/ var ComputeMesh.anchors by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // written once, read every frame, device-local
/*compute-pc-binding-group:anchors*/ var ComputeMeshGroup.anchors by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // written once, read every frame, device-local
/*compute-pc-structure:heat*/ var Structure.heat by Structure.FromStructure<Any>() // zero-initialised, device-local, shader-owned
/*compute-pc-binding:heat*/ var ComputeMesh.heat by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // zero-initialised, device-local, shader-owned
/*compute-pc-binding-group:heat*/ var ComputeMeshGroup.heat by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // zero-initialised, device-local, shader-owned
/*compute-pc-structure:knobs*/ var Structure.knobs by Structure.FromStructure<Any>() // .x seconds, .y force gain
/*compute-pc-binding:knobs*/ var ComputeMesh.knobs by ComputeMesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // .x seconds, .y force gain
/*compute-pc-binding-group:knobs*/ var ComputeMeshGroup.knobs by ComputeMeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // .x seconds, .y force gain
/*compute-pc-structure:count*/ var Structure.count by Structure.FromStructure<Any>()
/*compute-pc-binding:count*/ var ComputeMesh.count by ComputeMesh.BoundFromStructure<UInt>()
/*compute-pc-binding-group:count*/ var ComputeMeshGroup.count by ComputeMeshGroup.BoundFromStructure<UInt>()
/*compute-pc-structure:dt*/ var Structure.dt by Structure.FromStructure<Any>()
/*compute-pc-binding:dt*/ var ComputeMesh.dt by ComputeMesh.BoundFromStructure<Float>()
/*compute-pc-binding-group:dt*/ var ComputeMeshGroup.dt by ComputeMeshGroup.BoundFromStructure<Float>()
/*compute-ssbo:FieldProbeBuffer*/ val ComputeModulePointer.FieldProbeBuffer by ComputeMesh.FromUniform()
var Box.gsStateA by Inheritance<FrameBufferGroup>() /**/
val Once.gsStateA by Onceing<FrameBufferGroup>() /**/
var Box.gsStateB by Inheritance<FrameBufferGroup>() /**/
val Once.gsStateB by Onceing<FrameBufferGroup>() /**/
var Box.gsHeat by Inheritance<FrameBufferGroup>() /**/
val Once.gsHeat by Onceing<FrameBufferGroup>() /**/
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
