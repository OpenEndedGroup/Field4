import afield.four.Renderer
import afield.four.util.Window
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.buffers.FrameBufferGroup
import java.nio.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Spins:Spins*/
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
/*graphics-structure-type:Bonds:Bonds*/
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
/*graphics-pc-structure:spins*/ var Structure.spins by Structure.FromStructure<Any>()
/*graphics-pc-binding:spins*/ var Mesh.spins by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:spins*/ var MeshGroup.spins by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:jRight*/ var Structure.jRight by Structure.FromStructure<Any>()
/*graphics-pc-binding:jRight*/ var Mesh.jRight by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:jRight*/ var MeshGroup.jRight by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:jDown*/ var Structure.jDown by Structure.FromStructure<Any>()
/*graphics-pc-binding:jDown*/ var Mesh.jDown by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:jDown*/ var MeshGroup.jDown by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:L*/ var Structure.L by Structure.FromStructure<Any>()
/*graphics-pc-binding:L*/ var Mesh.L by Mesh.BoundFromStructure<UInt>()
/*graphics-pc-binding-group:L*/ var MeshGroup.L by MeshGroup.BoundFromStructure<UInt>()
/*graphics-pc-structure:aspect*/ var Structure.aspect by Structure.FromStructure<Any>()
/*graphics-pc-binding:aspect*/ var Mesh.aspect by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:aspect*/ var MeshGroup.aspect by MeshGroup.BoundFromStructure<Float>()
/*graphics-pc-structure:showFrustration*/ var Structure.showFrustration by Structure.FromStructure<Any>()
/*graphics-pc-binding:showFrustration*/ var Mesh.showFrustration by Mesh.BoundFromStructure<UInt>()
/*graphics-pc-binding-group:showFrustration*/ var MeshGroup.showFrustration by MeshGroup.BoundFromStructure<UInt>()
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
