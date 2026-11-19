import afield.simulation.agents.BondedWorld
import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.simulation.agents.BondedClock
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.bonded by Inheritance<BondedWorld>() /**/
val Once.bonded by Onceing<BondedWorld>() /**/
val Why.bonded by Whying() /**/
var Box.bondedFactory by Inheritance<() -> BondedWorld>() /**/
val Once.bondedFactory by Onceing<() -> BondedWorld>() /**/
val Why.bondedFactory by Whying() /**/
/*start*/
/*compute-structure-type:Cell:Cell*/
class Cell(
    members: List<Introspected.Member> = CellLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var pv by Structure.FromStructure<afield.math.linalg.Vector4f>() // world position xy, velocity zw
    var e by Structure.FromStructure<afield.math.linalg.Vector4f>() // energy, cell kind, organism id, padding
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
/*compute-structure-type:Bonds:Bonds*/
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
/*compute-structure-type:CellBuf:CellBuf*/
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
/*compute-structure-type:BondBuf:BondBuf*/
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
/*compute-pc-structure:cells*/ var Structure.cells by Structure.FromStructure<Any>() // two capacity-sized halves; step parity selects the source
/*compute-pc-binding:cells*/ var ComputeMesh.cells by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // two capacity-sized halves; step parity selects the source
/*compute-pc-binding-group:cells*/ var ComputeMeshGroup.cells by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // two capacity-sized halves; step parity selects the source
/*compute-pc-structure:bonds*/ var Structure.bonds by Structure.FromStructure<Any>() // four neighbour slots per cell; -1 marks an empty slot
/*compute-pc-binding:bonds*/ var ComputeMesh.bonds by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // four neighbour slots per cell; -1 marks an empty slot
/*compute-pc-binding-group:bonds*/ var ComputeMeshGroup.bonds by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // four neighbour slots per cell; -1 marks an empty slot
/*compute-pc-structure:tick*/ var Structure.tick by Structure.FromStructure<Any>() // the step number; -1 freezes the world
/*compute-pc-binding:tick*/ var ComputeMesh.tick by ComputeMesh.BoundFromStructure<Number>() // the step number; -1 freezes the world
/*compute-pc-binding-group:tick*/ var ComputeMeshGroup.tick by ComputeMeshGroup.BoundFromStructure<Number>() // the step number; -1 freezes the world
/*compute-pc-structure:count*/ var Structure.count by Structure.FromStructure<Any>()
/*compute-pc-binding:count*/ var ComputeMesh.count by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:count*/ var ComputeMeshGroup.count by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:capacity*/ var Structure.capacity by Structure.FromStructure<Any>() // stride between the two cell halves
/*compute-pc-binding:capacity*/ var ComputeMesh.capacity by ComputeMesh.BoundFromStructure<Any>() // stride between the two cell halves
/*compute-pc-binding-group:capacity*/ var ComputeMeshGroup.capacity by ComputeMeshGroup.BoundFromStructure<Any>() // stride between the two cell halves
/*compute-pc-structure:dt*/ var Structure.dt by Structure.FromStructure<Any>() // simulated seconds per step
/*compute-pc-binding:dt*/ var ComputeMesh.dt by ComputeMesh.BoundFromStructure<Number>() // simulated seconds per step
/*compute-pc-binding-group:dt*/ var ComputeMeshGroup.dt by ComputeMeshGroup.BoundFromStructure<Number>() // simulated seconds per step
/*compute-pc-structure:stiffness*/ var Structure.stiffness by Structure.FromStructure<Any>()
/*compute-pc-binding:stiffness*/ var ComputeMesh.stiffness by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:stiffness*/ var ComputeMeshGroup.stiffness by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:bondDamping*/ var Structure.bondDamping by Structure.FromStructure<Any>()
/*compute-pc-binding:bondDamping*/ var ComputeMesh.bondDamping by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:bondDamping*/ var ComputeMeshGroup.bondDamping by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:dragNormal*/ var Structure.dragNormal by Structure.FromStructure<Any>()
/*compute-pc-binding:dragNormal*/ var ComputeMesh.dragNormal by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:dragNormal*/ var ComputeMeshGroup.dragNormal by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:dragTangent*/ var Structure.dragTangent by Structure.FromStructure<Any>()
/*compute-pc-binding:dragTangent*/ var ComputeMesh.dragTangent by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:dragTangent*/ var ComputeMeshGroup.dragTangent by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:omega*/ var Structure.omega by Structure.FromStructure<Any>()
/*compute-pc-binding:omega*/ var ComputeMesh.omega by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:omega*/ var ComputeMeshGroup.omega by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:repulsionRadius*/ var Structure.repulsionRadius by Structure.FromStructure<Any>()
/*compute-pc-binding:repulsionRadius*/ var ComputeMesh.repulsionRadius by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:repulsionRadius*/ var ComputeMeshGroup.repulsionRadius by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:repulsionStiffness*/ var Structure.repulsionStiffness by Structure.FromStructure<Any>()
/*compute-pc-binding:repulsionStiffness*/ var ComputeMesh.repulsionStiffness by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:repulsionStiffness*/ var ComputeMeshGroup.repulsionStiffness by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:energyDiffusion*/ var Structure.energyDiffusion by Structure.FromStructure<Any>()
/*compute-pc-binding:energyDiffusion*/ var ComputeMesh.energyDiffusion by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:energyDiffusion*/ var ComputeMeshGroup.energyDiffusion by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:muscleCost*/ var Structure.muscleCost by Structure.FromStructure<Any>()
/*compute-pc-binding:muscleCost*/ var ComputeMesh.muscleCost by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:muscleCost*/ var ComputeMeshGroup.muscleCost by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:fullEnergy*/ var Structure.fullEnergy by Structure.FromStructure<Any>()
/*compute-pc-binding:fullEnergy*/ var ComputeMesh.fullEnergy by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:fullEnergy*/ var ComputeMeshGroup.fullEnergy by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:wallX*/ var Structure.wallX by Structure.FromStructure<Any>()
/*compute-pc-binding:wallX*/ var ComputeMesh.wallX by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:wallX*/ var ComputeMeshGroup.wallX by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:wallY*/ var Structure.wallY by Structure.FromStructure<Any>()
/*compute-pc-binding:wallY*/ var ComputeMesh.wallY by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:wallY*/ var ComputeMeshGroup.wallY by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:wallStiffness*/ var Structure.wallStiffness by Structure.FromStructure<Any>()
/*compute-pc-binding:wallStiffness*/ var ComputeMesh.wallStiffness by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:wallStiffness*/ var ComputeMeshGroup.wallStiffness by ComputeMeshGroup.BoundFromStructure<Number>()
var Box.bondedCells by Inheritance<FrameBufferGroup>() /**/
val Once.bondedCells by Onceing<FrameBufferGroup>() /**/
val Why.bondedCells by Whying() /**/
var Box.bondedBonds by Inheritance<FrameBufferGroup>() /**/
val Once.bondedBonds by Onceing<FrameBufferGroup>() /**/
val Why.bondedBonds by Whying() /**/
var Box.bondedClock by Inheritance<BondedClock>() /**/
val Once.bondedClock by Onceing<BondedClock>() /**/
val Why.bondedClock by Whying() /**/
var Box.bondedUpload by Inheritance<() -> Unit>() /**/
val Once.bondedUpload by Onceing<() -> Unit>() /**/
val Why.bondedUpload by Whying() /**/
var Box.window by Inheritance<Window>() /*from:bonded_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:bonded_renderer*/ /**/
val Why.window by Whying() /*from:bonded_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:bonded_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:bonded_renderer*/ /**/
val Why.renderer by Whying() /*from:bonded_renderer*/ /**/
