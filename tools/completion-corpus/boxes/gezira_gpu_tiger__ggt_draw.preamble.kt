import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Image:ImageBlock*/
// GLSL block `Image` is ImageBlock here: Image would hide afield.graphics.texture.Image.
class ImageBlock(
    members: List<Introspected.Member> = ImageBlockLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var p by Structure.FromStructure<afield.math.linalg.Vector4f>()
}
fun ImageBlockByteSize(): Long = 16L
fun ImageBlockLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("p", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asImageBlock(): ImageBlock =
    asStructure(ImageBlock(), ImageBlockLayoutMembers())
fun BufferParameters.asImageBlock(): ImageBlock =
    buffer.asImageBlock()
/*end*/
/*graphics-pc-structure:image*/ var Structure.image by Structure.FromStructure<Any>()
/*graphics-pc-binding:image*/ var Mesh.image by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:image*/ var MeshGroup.image by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:n*/ var Structure.n by Structure.FromStructure<Any>()
/*graphics-pc-binding:n*/ var Mesh.n by Mesh.BoundFromStructure<Int>()
/*graphics-pc-binding-group:n*/ var MeshGroup.n by MeshGroup.BoundFromStructure<Int>()
/*graphics-pc-structure:win*/ var Structure.win by Structure.FromStructure<Any>()
/*graphics-pc-binding:win*/ var Mesh.win by Mesh.BoundFromStructure<Int>()
/*graphics-pc-binding-group:win*/ var MeshGroup.win by MeshGroup.BoundFromStructure<Int>()
var Box.tigerImage by Inheritance<FrameBufferGroup>() /*from:ggt_tiger*/ /**/
val Once.tigerImage by Onceing<FrameBufferGroup>() /*from:ggt_tiger*/ /**/
val Why.tigerImage by Whying() /*from:ggt_tiger*/ /**/
var Box.tigerSize by Inheritance<Int>() /*from:ggt_tiger*/ /**/
val Once.tigerSize by Onceing<Int>() /*from:ggt_tiger*/ /**/
val Why.tigerSize by Whying() /*from:ggt_tiger*/ /**/
var Box.window by Inheritance<Window>() /*from:ggt_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:ggt_renderer*/ /**/
val Why.window by Whying() /*from:ggt_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:ggt_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:ggt_renderer*/ /**/
val Why.renderer by Whying() /*from:ggt_renderer*/ /**/
