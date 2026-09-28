import afield.four.Renderer
import afield.four.util.Window
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.buffers.FrameBufferGroup
/*start*/
/*compute-structure-type:Spins:Spins*/
class Spins(
    members: List<Introspected.Member> = SpinsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var s by Structure.FromStructure<Float>()
}
fun SpinsByteSize(): Long = 4L
fun SpinsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("s", 0, PrimitiveType.float.left(), 4, false),
    )
fun HasBuffer.asSpins(): Spins =
    asStructure(Spins(), SpinsLayoutMembers())
fun BufferParameters.asSpins(): Spins =
    buffer.asSpins()
/*end*/
/*start*/
/*compute-structure-type:Bonds:Bonds*/
class Bonds(
    members: List<Introspected.Member> = BondsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var j by Structure.FromStructure<Float>()
}
fun BondsByteSize(): Long = 4L
fun BondsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("j", 0, PrimitiveType.float.left(), 4, false),
    )
fun HasBuffer.asBonds(): Bonds =
    asStructure(Bonds(), BondsLayoutMembers())
fun BufferParameters.asBonds(): Bonds =
    buffer.asBonds()
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
/*compute-pc-structure:spins*/ var Structure.spins by Structure.FromStructure<Any>()
/*compute-pc-binding:spins*/ var ComputeMesh.spins by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-binding-group:spins*/ var ComputeMeshGroup.spins by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-structure:jRight*/ var Structure.jRight by Structure.FromStructure<Any>()
/*compute-pc-binding:jRight*/ var ComputeMesh.jRight by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-binding-group:jRight*/ var ComputeMeshGroup.jRight by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-structure:jDown*/ var Structure.jDown by Structure.FromStructure<Any>()
/*compute-pc-binding:jDown*/ var ComputeMesh.jDown by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-binding-group:jDown*/ var ComputeMeshGroup.jDown by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*compute-pc-structure:L*/ var Structure.L by Structure.FromStructure<Any>()
/*compute-pc-binding:L*/ var ComputeMesh.L by ComputeMesh.BoundFromStructure<UInt>()
/*compute-pc-binding-group:L*/ var ComputeMeshGroup.L by ComputeMeshGroup.BoundFromStructure<UInt>()
/*compute-pc-structure:field*/ var Structure.field by Structure.FromStructure<Any>()
/*compute-pc-binding:field*/ var ComputeMesh.field by ComputeMesh.BoundFromStructure<Float>()
/*compute-pc-binding-group:field*/ var ComputeMeshGroup.field by ComputeMeshGroup.BoundFromStructure<Float>()
/*compute-ssbo:FieldProbeBuffer*/ val ComputeModulePointer.FieldProbeBuffer by ComputeMesh.FromUniform()
var Box.sgL by Inheritance<Int>() /*from:sg_update*/ /**/
val Once.sgL by Onceing<Int>() /*from:sg_update*/ /**/
var Box.sgTemperature by Inheritance<Double>() /*from:sg_update*/ /**/
val Once.sgTemperature by Onceing<Double>() /*from:sg_update*/ /**/
var Box.sgField by Inheritance<Double>() /*from:sg_update*/ /**/
val Once.sgField by Onceing<Double>() /*from:sg_update*/ /**/
var Box.sgMode by Inheritance<Int>() /*from:sg_update*/ /**/
val Once.sgMode by Onceing<Int>() /*from:sg_update*/ /**/
var Box.sgModel by Inheritance<Int>() /*from:sg_update*/ /**/
val Once.sgModel by Onceing<Int>() /*from:sg_update*/ /**/
var Box.sgSweeps by Inheritance<Int>() /*from:sg_update*/ /**/
val Once.sgSweeps by Onceing<Int>() /*from:sg_update*/ /**/
var Box.sgAnneal by Inheritance<Boolean>() /*from:sg_update*/ /**/
val Once.sgAnneal by Onceing<Boolean>() /*from:sg_update*/ /**/
var Box.sgSpins by Inheritance<FrameBufferGroup>() /*from:sg_update*/ /**/
val Once.sgSpins by Onceing<FrameBufferGroup>() /*from:sg_update*/ /**/
var Box.sgJRight by Inheritance<FrameBufferGroup>() /*from:sg_update*/ /**/
val Once.sgJRight by Onceing<FrameBufferGroup>() /*from:sg_update*/ /**/
var Box.sgJDown by Inheritance<FrameBufferGroup>() /*from:sg_update*/ /**/
val Once.sgJDown by Onceing<FrameBufferGroup>() /*from:sg_update*/ /**/
var Box.window by Inheritance<Window>() /*from:sg_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:sg_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:sg_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:sg_renderer*/ /**/
