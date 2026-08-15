import afield.four.Renderer
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.buffers.FrameBufferGroup
var Box.sgL by Inheritance<Int>() /**/
val Once.sgL by Onceing<Int>() /**/
var Box.sgTemperature by Inheritance<Double>() /**/
val Once.sgTemperature by Onceing<Double>() /**/
var Box.sgField by Inheritance<Double>() /**/
val Once.sgField by Onceing<Double>() /**/
var Box.sgMode by Inheritance<Int>() /**/
val Once.sgMode by Onceing<Int>() /**/
var Box.sgModel by Inheritance<Int>() /**/
val Once.sgModel by Onceing<Int>() /**/
var Box.sgSweeps by Inheritance<Int>() /**/
val Once.sgSweeps by Onceing<Int>() /**/
var Box.sgAnneal by Inheritance<Boolean>() /**/
val Once.sgAnneal by Onceing<Boolean>() /**/
var Box.sgSpins by Inheritance<FrameBufferGroup>() /**/
val Once.sgSpins by Onceing<FrameBufferGroup>() /**/
var Box.sgJRight by Inheritance<FrameBufferGroup>() /**/
val Once.sgJRight by Onceing<FrameBufferGroup>() /**/
var Box.sgJDown by Inheritance<FrameBufferGroup>() /**/
val Once.sgJDown by Onceing<FrameBufferGroup>() /**/
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
/*compute-pc-structure:color*/ var Structure.color by Structure.FromStructure<Any>()
/*compute-pc-binding:color*/ var ComputeMesh.color by ComputeMesh.BoundFromStructure<UInt>()
/*compute-pc-binding-group:color*/ var ComputeMeshGroup.color by ComputeMeshGroup.BoundFromStructure<UInt>()
/*compute-pc-structure:seed*/ var Structure.seed by Structure.FromStructure<Any>()
/*compute-pc-binding:seed*/ var ComputeMesh.seed by ComputeMesh.BoundFromStructure<UInt>()
/*compute-pc-binding-group:seed*/ var ComputeMeshGroup.seed by ComputeMeshGroup.BoundFromStructure<UInt>()
/*compute-pc-structure:mode*/ var Structure.mode by Structure.FromStructure<Any>()
/*compute-pc-binding:mode*/ var ComputeMesh.mode by ComputeMesh.BoundFromStructure<UInt>()
/*compute-pc-binding-group:mode*/ var ComputeMeshGroup.mode by ComputeMeshGroup.BoundFromStructure<UInt>()
/*compute-pc-structure:temperature*/ var Structure.temperature by Structure.FromStructure<Any>()
/*compute-pc-binding:temperature*/ var ComputeMesh.temperature by ComputeMesh.BoundFromStructure<Float>()
/*compute-pc-binding-group:temperature*/ var ComputeMeshGroup.temperature by ComputeMeshGroup.BoundFromStructure<Float>()
/*compute-pc-structure:field*/ var Structure.field by Structure.FromStructure<Any>()
/*compute-pc-binding:field*/ var ComputeMesh.field by ComputeMesh.BoundFromStructure<Float>()
/*compute-pc-binding-group:field*/ var ComputeMeshGroup.field by ComputeMeshGroup.BoundFromStructure<Float>()
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
