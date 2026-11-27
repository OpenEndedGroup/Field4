import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.render.FLineLayers
import afield.graphics.vulkan.util.Camera
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:FLineVertex:FLineVertex*/
class FLineVertex(
    members: List<Introspected.Member> = FLineVertexLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var position by Structure.FromStructure<afield.math.linalg.Vector4f>() // xyz: world position; w: dot radius in pixels (pointSize * 3)
    var color by Structure.FromStructure<afield.math.linalg.Vector4f>() // straight-alpha RGBA, 0..1
}
fun FLineVertexByteSize(): Long = 32L
fun FLineVertexLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("position", 0, PrimitiveType.vec4.left(), 0, false),
        Introspected.Member("color", 16, PrimitiveType.vec4.left(), 0, false),
    )
fun HasBuffer.asFLineVertex(): FLineVertex =
    asStructure(FLineVertex(), FLineVertexLayoutMembers())
fun BufferParameters.asFLineVertex(): FLineVertex =
    buffer.asFLineVertex()
/*end*/
/*start*/
/*graphics-structure-type:FLineVertices:FLineVertices*/
class FLineVertices(
    members: List<Introspected.Member> = FLineVerticesLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    val v: FLineVertex
        get() = childStructure("v") { members, baseOffset, pathPrefix, root ->
            FLineVertex(members, baseOffset, pathPrefix, root)
        }
}
fun FLineVerticesByteSize(): Long = 32L
fun FLineVerticesLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("v", 0, FLineVertexLayoutMembers().right(), 32, false),
    )
fun HasBuffer.asFLineVertices(): FLineVertices =
    asStructure(FLineVertices(), FLineVerticesLayoutMembers())
fun BufferParameters.asFLineVertices(): FLineVertices =
    buffer.asFLineVertices()
/*end*/
/*graphics-pc-structure:p*/ var Structure.p by Structure.FromStructure<Any>() // projection
/*graphics-pc-binding:p*/ var Mesh.p by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>() // projection
/*graphics-pc-binding-group:p*/ var MeshGroup.p by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>() // projection
/*graphics-pc-structure:m*/ var Structure.m by Structure.FromStructure<Any>() // model-view
/*graphics-pc-binding:m*/ var Mesh.m by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>() // model-view
/*graphics-pc-binding-group:m*/ var MeshGroup.m by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>() // model-view
/*graphics-pc-structure:vertices*/ var Structure.vertices by Structure.FromStructure<Any>()
/*graphics-pc-binding:vertices*/ var Mesh.vertices by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:vertices*/ var MeshGroup.vertices by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:count*/ var Structure.count by Structure.FromStructure<Any>() // vertices in the layer
/*graphics-pc-binding:count*/ var Mesh.count by Mesh.BoundFromStructure<Any>() // vertices in the layer
/*graphics-pc-binding-group:count*/ var MeshGroup.count by MeshGroup.BoundFromStructure<Any>() // vertices in the layer
/*graphics-pc-structure:viewport*/ var Structure.viewport by Structure.FromStructure<Any>() // framebuffer size, pixels
/*graphics-pc-binding:viewport*/ var Mesh.viewport by Mesh.BoundFromStructure<afield.math.linalg.Vector2f>() // framebuffer size, pixels
/*graphics-pc-binding-group:viewport*/ var MeshGroup.viewport by MeshGroup.BoundFromStructure<afield.math.linalg.Vector2f>() // framebuffer size, pixels
/*graphics-pc-structure:sizeScale*/ var Structure.sizeScale by Structure.FromStructure<Any>() // multiplies every radius
/*graphics-pc-binding:sizeScale*/ var Mesh.sizeScale by Mesh.BoundFromStructure<Number>() // multiplies every radius
/*graphics-pc-binding-group:sizeScale*/ var MeshGroup.sizeScale by MeshGroup.BoundFromStructure<Number>() // multiplies every radius
var Box.flineLayers by Inheritance<FLineLayers>() /*from:flg_layers*/ /**/
val Once.flineLayers by Onceing<FLineLayers>() /*from:flg_layers*/ /**/
val Why.flineLayers by Whying() /*from:flg_layers*/ /**/
var Box.flineCamera by Inheritance<Camera>() /*from:flg_layers*/ /**/
val Once.flineCamera by Onceing<Camera>() /*from:flg_layers*/ /**/
val Why.flineCamera by Whying() /*from:flg_layers*/ /**/
var Box.window by Inheritance<Window>() /*from:flg_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:flg_renderer*/ /**/
val Why.window by Whying() /*from:flg_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:flg_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:flg_renderer*/ /**/
val Why.renderer by Whying() /*from:flg_renderer*/ /**/
