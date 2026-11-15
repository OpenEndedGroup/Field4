import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.simulation.agents.BondedWorld
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.simulation.agents.BondedClock
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Cell:Cell*/
class Cell(
    members: List<Introspected.Member> = CellLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var pv by Structure.FromStructure<afield.math.linalg.Vector4f>()
    var e by Structure.FromStructure<afield.math.linalg.Vector4f>()
}
fun CellByteSize(): Long = 32L
fun CellLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("pv", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("e", 16, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asCell(): Cell =
    asStructure(Cell(), CellLayoutMembers())
fun BufferParameters.asCell(): Cell =
    buffer.asCell()
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
    var partner by Structure.FromStructure<afield.math.linalg.Vector4f>()
    var rest by Structure.FromStructure<afield.math.linalg.Vector4f>()
    var amp by Structure.FromStructure<afield.math.linalg.Vector4f>()
    var phase by Structure.FromStructure<afield.math.linalg.Vector4f>()
    var limit by Structure.FromStructure<afield.math.linalg.Vector4f>()
    var axis by Structure.FromStructure<afield.math.linalg.Vector4f>()
}
fun BondsByteSize(): Long = 96L
fun BondsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("partner", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("rest", 16, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("amp", 32, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("phase", 48, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("limit", 64, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("axis", 80, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asBonds(): Bonds =
    asStructure(Bonds(), BondsLayoutMembers())
fun BufferParameters.asBonds(): Bonds =
    buffer.asBonds()
/*end*/
/*start*/
/*graphics-structure-type:CellBuf:CellBuf*/
class CellBuf(
    members: List<Introspected.Member> = CellBufLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val c: Cell
        get() = childStructure("c") { members, baseOffset, pathPrefix, root ->
            Cell(members, baseOffset, pathPrefix, root)
        }
}
fun CellBufByteSize(): Long = 32L
fun CellBufLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("c", 0, CellLayoutMembers().right(), 32, false),
    )
fun HasBuffer.asCellBuf(): CellBuf =
    asStructure(CellBuf(), CellBufLayoutMembers())
fun BufferParameters.asCellBuf(): CellBuf =
    buffer.asCellBuf()
/*end*/
/*start*/
/*graphics-structure-type:BondBuf:BondBuf*/
class BondBuf(
    members: List<Introspected.Member> = BondBufLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val b: Bonds
        get() = childStructure("b") { members, baseOffset, pathPrefix, root ->
            Bonds(members, baseOffset, pathPrefix, root)
        }
}
fun BondBufByteSize(): Long = 96L
fun BondBufLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("b", 0, BondsLayoutMembers().right(), 96, false),
    )
fun HasBuffer.asBondBuf(): BondBuf =
    asStructure(BondBuf(), BondBufLayoutMembers())
fun BufferParameters.asBondBuf(): BondBuf =
    buffer.asBondBuf()
/*end*/
/*graphics-pc-structure:cells*/ var Structure.cells by Structure.FromStructure<Any>()
/*graphics-pc-binding:cells*/ var Mesh.cells by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:cells*/ var MeshGroup.cells by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:bonds*/ var Structure.bonds by Structure.FromStructure<Any>()
/*graphics-pc-binding:bonds*/ var Mesh.bonds by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:bonds*/ var MeshGroup.bonds by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:latest*/ var Structure.latest by Structure.FromStructure<Any>()
/*graphics-pc-binding:latest*/ var Mesh.latest by Mesh.BoundFromStructure<Any>()
/*graphics-pc-binding-group:latest*/ var MeshGroup.latest by MeshGroup.BoundFromStructure<Any>()
/*graphics-pc-structure:capacity*/ var Structure.capacity by Structure.FromStructure<Any>()
/*graphics-pc-binding:capacity*/ var Mesh.capacity by Mesh.BoundFromStructure<Any>()
/*graphics-pc-binding-group:capacity*/ var MeshGroup.capacity by MeshGroup.BoundFromStructure<Any>()
/*graphics-pc-structure:viewX*/ var Structure.viewX by Structure.FromStructure<Any>()
/*graphics-pc-binding:viewX*/ var Mesh.viewX by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:viewX*/ var MeshGroup.viewX by MeshGroup.BoundFromStructure<Number>()
/*graphics-pc-structure:viewY*/ var Structure.viewY by Structure.FromStructure<Any>()
/*graphics-pc-binding:viewY*/ var Mesh.viewY by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:viewY*/ var MeshGroup.viewY by MeshGroup.BoundFromStructure<Number>()
/*graphics-pc-structure:viewHalfHeight*/ var Structure.viewHalfHeight by Structure.FromStructure<Any>()
/*graphics-pc-binding:viewHalfHeight*/ var Mesh.viewHalfHeight by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:viewHalfHeight*/ var MeshGroup.viewHalfHeight by MeshGroup.BoundFromStructure<Number>()
/*graphics-pc-structure:aspect*/ var Structure.aspect by Structure.FromStructure<Any>()
/*graphics-pc-binding:aspect*/ var Mesh.aspect by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:aspect*/ var MeshGroup.aspect by MeshGroup.BoundFromStructure<Number>()
var Box.bonded by Inheritance<BondedWorld>() /*from:bonded_world*/ /**/
val Once.bonded by Onceing<BondedWorld>() /*from:bonded_world*/ /**/
val Why.bonded by Whying() /*from:bonded_world*/ /**/
var Box.bondedFactory by Inheritance<() -> BondedWorld>() /*from:bonded_world*/ /**/
val Once.bondedFactory by Onceing<() -> BondedWorld>() /*from:bonded_world*/ /**/
val Why.bondedFactory by Whying() /*from:bonded_world*/ /**/
var Box.bondedCells by Inheritance<FrameBufferGroup>() /*from:bonded_world*/ /**/
val Once.bondedCells by Onceing<FrameBufferGroup>() /*from:bonded_world*/ /**/
val Why.bondedCells by Whying() /*from:bonded_world*/ /**/
var Box.bondedBonds by Inheritance<FrameBufferGroup>() /*from:bonded_world*/ /**/
val Once.bondedBonds by Onceing<FrameBufferGroup>() /*from:bonded_world*/ /**/
val Why.bondedBonds by Whying() /*from:bonded_world*/ /**/
var Box.bondedClock by Inheritance<BondedClock>() /*from:bonded_world*/ /**/
val Once.bondedClock by Onceing<BondedClock>() /*from:bonded_world*/ /**/
val Why.bondedClock by Whying() /*from:bonded_world*/ /**/
var Box.bondedUpload by Inheritance<() -> Unit>() /*from:bonded_world*/ /**/
val Once.bondedUpload by Onceing<() -> Unit>() /*from:bonded_world*/ /**/
val Why.bondedUpload by Whying() /*from:bonded_world*/ /**/
var Box.window by Inheritance<Window>() /*from:bonded_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:bonded_renderer*/ /**/
val Why.window by Whying() /*from:bonded_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:bonded_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:bonded_renderer*/ /**/
val Why.renderer by Whying() /*from:bonded_renderer*/ /**/
