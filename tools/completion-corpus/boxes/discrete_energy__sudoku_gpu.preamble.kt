import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.simulation.agents.BondedClock
import afield.math.energy.DiscreteEnergy
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*start*/
/*compute-structure-type:Pairwise:Pairwise*/
class Pairwise(
    members: List<Introspected.Member> = PairwiseLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var w by Structure.FromStructure<Float>()
}
fun PairwiseByteSize(): Long = 4L
fun PairwiseLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("w", 0, PrimitiveType.float.left(), 4, false),
    )
fun HasBuffer.asPairwise(): Pairwise =
    asStructure(Pairwise(), PairwiseLayoutMembers())
fun BufferParameters.asPairwise(): Pairwise =
    buffer.asPairwise()
/*end*/
/*start*/
/*compute-structure-type:State:State*/
class State(
    members: List<Introspected.Member> = StateLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var s by Structure.FromStructure<Float>()
}
fun StateByteSize(): Long = 4L
fun StateLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("s", 0, PrimitiveType.float.left(), 4, false),
    )
fun HasBuffer.asState(): State =
    asStructure(State(), StateLayoutMembers())
fun BufferParameters.asState(): State =
    buffer.asState()
/*end*/
/*compute-pc-structure:pairwise*/ var Structure.pairwise by Structure.FromStructure<Any>() // units × units costs W, row-major
/*compute-pc-binding:pairwise*/ var ComputeMesh.pairwise by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // units × units costs W, row-major
/*compute-pc-binding-group:pairwise*/ var ComputeMeshGroup.pairwise by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // units × units costs W, row-major
/*compute-pc-structure:state*/ var Structure.state by Structure.FromStructure<Any>() // DiscreteEnergy.gpuState: header, beliefs A, beliefs B, v, unary, then offset/size/fixed per variable
/*compute-pc-binding:state*/ var ComputeMesh.state by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // DiscreteEnergy.gpuState: header, beliefs A, beliefs B, v, unary, then offset/size/fixed per variable
/*compute-pc-binding-group:state*/ var ComputeMeshGroup.state by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>() // DiscreteEnergy.gpuState: header, beliefs A, beliefs B, v, unary, then offset/size/fixed per variable
/*compute-pc-structure:tick*/ var Structure.tick by Structure.FromStructure<Any>() // step number; negative freezes the descent
/*compute-pc-binding:tick*/ var ComputeMesh.tick by ComputeMesh.BoundFromStructure<Number>() // step number; negative freezes the descent
/*compute-pc-binding-group:tick*/ var ComputeMeshGroup.tick by ComputeMeshGroup.BoundFromStructure<Number>() // step number; negative freezes the descent
/*compute-pc-structure:units*/ var Structure.units by Structure.FromStructure<Any>()
/*compute-pc-binding:units*/ var ComputeMesh.units by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:units*/ var ComputeMeshGroup.units by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:variables*/ var Structure.variables by Structure.FromStructure<Any>()
/*compute-pc-binding:variables*/ var ComputeMesh.variables by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:variables*/ var ComputeMeshGroup.variables by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:eta*/ var Structure.eta by Structure.FromStructure<Any>()
/*compute-pc-binding:eta*/ var ComputeMesh.eta by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:eta*/ var ComputeMeshGroup.eta by ComputeMeshGroup.BoundFromStructure<Number>()
/*compute-pc-structure:beta*/ var Structure.beta by Structure.FromStructure<Any>()
/*compute-pc-binding:beta*/ var ComputeMesh.beta by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:beta*/ var ComputeMeshGroup.beta by ComputeMeshGroup.BoundFromStructure<Number>()
var Box.energyGpuState by Inheritance<FrameBufferGroup>() /**/
val Once.energyGpuState by Onceing<FrameBufferGroup>() /**/
val Why.energyGpuState by Whying() /**/
var Box.energyClock by Inheritance<BondedClock>() /**/
val Once.energyClock by Onceing<BondedClock>() /**/
val Why.energyClock by Whying() /**/
var Box.energyUpload by Inheritance<() -> Unit>() /**/
val Once.energyUpload by Onceing<() -> Unit>() /**/
val Why.energyUpload by Whying() /**/
var Box.sudokuEnergy by Inheritance<DiscreteEnergy>() /*from:sudoku_rules*/ /**/
val Once.sudokuEnergy by Onceing<DiscreteEnergy>() /*from:sudoku_rules*/ /**/
val Why.sudokuEnergy by Whying() /*from:sudoku_rules*/ /**/
var Box.sudokuBoard by Inheritance<(EnergyState, String) -> String>() /*from:sudoku_rules*/ /**/
val Once.sudokuBoard by Onceing<(EnergyState, String) -> String>() /*from:sudoku_rules*/ /**/
val Why.sudokuBoard by Whying() /*from:sudoku_rules*/ /**/
var Box.window by Inheritance<Window>() /*from:energy_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:energy_renderer*/ /**/
val Why.window by Whying() /*from:energy_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:energy_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:energy_renderer*/ /**/
val Why.renderer by Whying() /*from:energy_renderer*/ /**/
