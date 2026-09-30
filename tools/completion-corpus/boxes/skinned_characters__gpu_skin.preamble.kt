import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:VertexBuffer:VertexBuffer*/
class VertexBuffer(
    members: List<Introspected.Member> = VertexBufferLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var vertexData by Structure.FromStructure<Float>()
}
fun VertexBufferByteSize(): Long = 4L
fun VertexBufferLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("vertexData", 0, PrimitiveType.float.left(), 4, false),
    )
fun HasBuffer.asVertexBuffer(): VertexBuffer =
    asStructure(VertexBuffer(), VertexBufferLayoutMembers())
fun BufferParameters.asVertexBuffer(): VertexBuffer =
    buffer.asVertexBuffer()
/*end*/
/*start*/
/*graphics-structure-type:IndexBuffer:IndexBuffer*/
class IndexBuffer(
    members: List<Introspected.Member> = IndexBufferLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var indexData by Structure.FromStructure<UInt>()
}
fun IndexBufferByteSize(): Long = 4L
fun IndexBufferLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("indexData", 0, PrimitiveType.uint.left(), 4, false),
    )
fun HasBuffer.asIndexBuffer(): IndexBuffer =
    asStructure(IndexBuffer(), IndexBufferLayoutMembers())
fun BufferParameters.asIndexBuffer(): IndexBuffer =
    buffer.asIndexBuffer()
/*end*/
/*graphics-pc-structure:modelView*/ var Structure.modelView by Structure.FromStructure<Any>() // view * model
/*graphics-pc-binding:modelView*/ var Mesh.modelView by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // view * model
/*graphics-pc-binding-group:modelView*/ var MeshGroup.modelView by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // view * model
/*graphics-pc-structure:proj*/ var Structure.proj by Structure.FromStructure<Any>() // projection
/*graphics-pc-binding:proj*/ var Mesh.proj by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // projection
/*graphics-pc-binding-group:proj*/ var MeshGroup.proj by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Matrix4f>() // projection
/*graphics-pc-structure:materialBaseColor*/ var Structure.materialBaseColor by Structure.FromStructure<Any>()
/*graphics-pc-binding:materialBaseColor*/ var Mesh.materialBaseColor by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-binding-group:materialBaseColor*/ var MeshGroup.materialBaseColor by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-structure:vertexOffset*/ var Structure.vertexOffset by Structure.FromStructure<Any>() // in floats
/*graphics-pc-binding:vertexOffset*/ var Mesh.vertexOffset by Mesh.BoundFromStructure<UInt>() // in floats
/*graphics-pc-binding-group:vertexOffset*/ var MeshGroup.vertexOffset by MeshGroup.BoundFromStructure<UInt>() // in floats
/*graphics-pc-structure:indexOffset*/ var Structure.indexOffset by Structure.FromStructure<Any>() // in indices
/*graphics-pc-binding:indexOffset*/ var Mesh.indexOffset by Mesh.BoundFromStructure<UInt>() // in indices
/*graphics-pc-binding-group:indexOffset*/ var MeshGroup.indexOffset by MeshGroup.BoundFromStructure<UInt>() // in indices
/*graphics-pc-structure:vertexStride*/ var Structure.vertexStride by Structure.FromStructure<Any>() // in floats
/*graphics-pc-binding:vertexStride*/ var Mesh.vertexStride by Mesh.BoundFromStructure<UInt>() // in floats
/*graphics-pc-binding-group:vertexStride*/ var MeshGroup.vertexStride by MeshGroup.BoundFromStructure<UInt>() // in floats
/*graphics-pc-structure:useVertexColor*/ var Structure.useVertexColor by Structure.FromStructure<Any>() // bool as uint
/*graphics-pc-binding:useVertexColor*/ var Mesh.useVertexColor by Mesh.BoundFromStructure<UInt>() // bool as uint
/*graphics-pc-binding-group:useVertexColor*/ var MeshGroup.useVertexColor by MeshGroup.BoundFromStructure<UInt>() // bool as uint
/*graphics-pc-structure:vertexBuffer*/ var Structure.vertexBuffer by Structure.FromStructure<Any>()
/*graphics-pc-binding:vertexBuffer*/ var Mesh.vertexBuffer by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:vertexBuffer*/ var MeshGroup.vertexBuffer by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:indexBuffer*/ var Structure.indexBuffer by Structure.FromStructure<Any>()
/*graphics-pc-binding:indexBuffer*/ var Mesh.indexBuffer by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:indexBuffer*/ var MeshGroup.indexBuffer by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
var Box.window by Inheritance<Window>() /*from:skin_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:skin_renderer*/ /**/
val Why.window by Whying() /*from:skin_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:skin_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:skin_renderer*/ /**/
val Why.renderer by Whying() /*from:skin_renderer*/ /**/
