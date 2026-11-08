import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*start*/
/*compute-structure-type:ncaNetWeights:NcaNetWeights*/
class NcaNetWeights(
    members: List<Introspected.Member> = NcaNetWeightsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var w by Structure.FromStructure<Float>()
}
fun NcaNetWeightsByteSize(): Long = 4L
fun NcaNetWeightsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("w", 0, PrimitiveType.float.left(), 4, false),
    )
fun HasBuffer.asNcaNetWeights(): NcaNetWeights =
    asStructure(NcaNetWeights(), NcaNetWeightsLayoutMembers())
fun BufferParameters.asNcaNetWeights(): NcaNetWeights =
    buffer.asNcaNetWeights()
/*end*/
/*start*/
/*compute-structure-type:NcaState:NcaState*/
class NcaState(
    members: List<Introspected.Member> = NcaStateLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var v by Structure.FromStructure<afield.math.linalg.Vector4f>()
}
fun NcaStateByteSize(): Long = 16L
fun NcaStateLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("v", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asNcaState(): NcaState =
    asStructure(NcaState(), NcaStateLayoutMembers())
fun BufferParameters.asNcaState(): NcaState =
    buffer.asNcaState()
/*end*/
/*compute-pc-structure:cells*/ var Structure.cells by Structure.FromStructure<Any>()
/*compute-pc-binding:cells*/ var ComputeMesh.cells by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:cells*/ var ComputeMeshGroup.cells by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:nw*/ var Structure.nw by Structure.FromStructure<Any>()
/*compute-pc-binding:nw*/ var ComputeMesh.nw by ComputeMesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-binding-group:nw*/ var ComputeMeshGroup.nw by ComputeMeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*compute-pc-structure:size*/ var Structure.size by Structure.FromStructure<Any>()
/*compute-pc-binding:size*/ var ComputeMesh.size by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:size*/ var ComputeMeshGroup.size by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:parity*/ var Structure.parity by Structure.FromStructure<Any>()
/*compute-pc-binding:parity*/ var ComputeMesh.parity by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:parity*/ var ComputeMeshGroup.parity by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:seed*/ var Structure.seed by Structure.FromStructure<Any>()
/*compute-pc-binding:seed*/ var ComputeMesh.seed by ComputeMesh.BoundFromStructure<Any>()
/*compute-pc-binding-group:seed*/ var ComputeMeshGroup.seed by ComputeMeshGroup.BoundFromStructure<Any>()
/*compute-pc-structure:brush*/ var Structure.brush by Structure.FromStructure<Any>()
/*compute-pc-binding:brush*/ var ComputeMesh.brush by ComputeMesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*compute-pc-binding-group:brush*/ var ComputeMeshGroup.brush by ComputeMeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
/*compute-pc-structure:angle*/ var Structure.angle by Structure.FromStructure<Any>()
/*compute-pc-binding:angle*/ var ComputeMesh.angle by ComputeMesh.BoundFromStructure<Number>()
/*compute-pc-binding-group:angle*/ var ComputeMeshGroup.angle by ComputeMeshGroup.BoundFromStructure<Number>()
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:NcaCells:NcaCells*/
class NcaCells(
    members: List<Introspected.Member> = NcaCellsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var v by Structure.FromStructure<afield.math.linalg.Vector4f>()
}
fun NcaCellsByteSize(): Long = 16L
fun NcaCellsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("v", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asNcaCells(): NcaCells =
    asStructure(NcaCells(), NcaCellsLayoutMembers())
fun BufferParameters.asNcaCells(): NcaCells =
    buffer.asNcaCells()
/*end*/
/*graphics-pc-structure:state*/ var Structure.state by Structure.FromStructure<Any>()
/*graphics-pc-binding:state*/ var Mesh.state by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:state*/ var MeshGroup.state by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:shown*/ var Structure.shown by Structure.FromStructure<Any>()
/*graphics-pc-binding:shown*/ var Mesh.shown by Mesh.BoundFromStructure<Any>()
/*graphics-pc-binding-group:shown*/ var MeshGroup.shown by MeshGroup.BoundFromStructure<Any>()
/*graphics-pc-structure:viewport*/ var Structure.viewport by Structure.FromStructure<Any>()
/*graphics-pc-binding:viewport*/ var Mesh.viewport by Mesh.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-binding-group:viewport*/ var MeshGroup.viewport by MeshGroup.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-structure:gridSize*/ var Structure.gridSize by Structure.FromStructure<Any>()
/*graphics-pc-binding:gridSize*/ var Mesh.gridSize by Mesh.BoundFromStructure<Any>()
/*graphics-pc-binding-group:gridSize*/ var MeshGroup.gridSize by MeshGroup.BoundFromStructure<Any>()
var Box.window by Inheritance<Window>() /*from:nca_renderer_fish*/ /**/
val Once.window by Onceing<Window>() /*from:nca_renderer_fish*/ /**/
val Why.window by Whying() /*from:nca_renderer_fish*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:nca_renderer_fish*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:nca_renderer_fish*/ /**/
val Why.renderer by Whying() /*from:nca_renderer_fish*/ /**/
