import afield.four.Renderer
import afield.four.util.Window
import afield.four.buffers.FrameBufferGroup
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*start*/
/*compute-structure-type:inData:InData*/
class InData(
    members: List<Introspected.Member> = InDataLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var a by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun InDataByteSize(): Long = 16L
fun InDataLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("a", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asInData(): InData =
    asStructure(InData(), InDataLayoutMembers())
fun BufferParameters.asInData(): InData =
    buffer.asInData()
/*end*/
/*start*/
/*compute-structure-type:outData:OutData*/
class OutData(
    members: List<Introspected.Member> = OutDataLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var a by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun OutDataByteSize(): Long = 16L
fun OutDataLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("a", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asOutData(): OutData =
    asStructure(OutData(), OutDataLayoutMembers())
fun BufferParameters.asOutData(): OutData =
    buffer.asOutData()
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
/*compute-pc-structure:inData*/ var Structure.inData by Structure.FromStructure<Any>()
/*compute-pc-binding:inData*/ var ComputeMesh.inData by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-binding-group:inData*/ var ComputeMeshGroup.inData by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-structure:outData*/ var Structure.outData by Structure.FromStructure<Any>()
/*compute-pc-binding:outData*/ var ComputeMesh.outData by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-binding-group:outData*/ var ComputeMeshGroup.outData by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-structure:something*/ var Structure.something by Structure.FromStructure<Any>()
/*compute-pc-binding:something*/ var ComputeMesh.something by ComputeMesh.BoundFromStructure<Float>()
/*compute-pc-binding-group:something*/ var ComputeMeshGroup.something by ComputeMeshGroup.BoundFromStructure<Float>()
/*compute-ssbo:FieldProbeBuffer*/ val ComputeModulePointer.FieldProbeBuffer by ComputeMesh.FromUniform()
var Box.outputPositions by Inheritance<FrameBufferGroup>() /**/
val Once.outputPositions by Onceing<FrameBufferGroup>() /**/
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
