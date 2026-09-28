import afield.four.Renderer
import afield.four.util.Window
import afield.four.buffers.FrameBufferGroup
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import java.nio.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:inData:InData*/
class InData(
    members: List<Introspected.Member> = InDataLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var pos by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun InDataByteSize(): Long = 16L
fun InDataLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("pos", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asInData(): InData =
    asStructure(InData(), InDataLayoutMembers())
fun BufferParameters.asInData(): InData =
    buffer.asInData()
/*end*/
/*graphics-pc-structure:inData*/ var Structure.inData by Structure.FromStructure<Any>()
/*graphics-pc-binding:inData*/ var Mesh.inData by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:inData*/ var MeshGroup.inData by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:aspectRatio*/ var Structure.aspectRatio by Structure.FromStructure<Any>()
/*graphics-pc-binding:aspectRatio*/ var Mesh.aspectRatio by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:aspectRatio*/ var MeshGroup.aspectRatio by MeshGroup.BoundFromStructure<Float>()
var Box.outputPositions by Inheritance<FrameBufferGroup>() /*from:tb_compute*/ /**/
val Once.outputPositions by Onceing<FrameBufferGroup>() /*from:tb_compute*/ /**/
var Box.window by Inheritance<Window>() /*from:tb_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:tb_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:tb_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:tb_renderer*/ /**/
