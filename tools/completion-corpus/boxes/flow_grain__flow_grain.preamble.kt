import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Field:Field*/
class Field(
    members: List<Introspected.Member> = FieldLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var v by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun FieldByteSize(): Long = 16L
fun FieldLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("v", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asField(): Field =
    asStructure(Field(), FieldLayoutMembers())
fun BufferParameters.asField(): Field =
    buffer.asField()
/*end*/
/*start*/
/*graphics-structure-type:Tiles:Tiles*/
class Tiles(
    members: List<Introspected.Member> = TilesLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var v by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun TilesByteSize(): Long = 16L
fun TilesLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("v", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asTiles(): Tiles =
    asStructure(Tiles(), TilesLayoutMembers())
fun BufferParameters.asTiles(): Tiles =
    buffer.asTiles()
/*end*/
/*start*/
/*graphics-structure-type:History:History*/
class History(
    members: List<Introspected.Member> = HistoryLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var frame by Structure.FromStructure<Any>()
}
fun HistoryByteSize(): Long = 272L
fun HistoryLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("frame", 0, listOf(
                        Introspected.Member("current", 0, PrimitiveType.mat4.left(), 0, false),
                        Introspected.Member("previous", 64, PrimitiveType.mat4.left(), 0, false),
                        Introspected.Member("inverseCurrent", 128, PrimitiveType.mat4.left(), 0, false),
                        Introspected.Member("reprojection", 192, PrimitiveType.mat4.left(), 0, false),
                        Introspected.Member("viewport", 256, PrimitiveType.vec4.left(), 0, false),
                    ).right(), 0, false),
    )
fun HasBuffer.asHistory(): History =
    asStructure(History(), HistoryLayoutMembers())
fun BufferParameters.asHistory(): History =
    buffer.asHistory()
/*end*/
/*graphics-pc-structure:item*/ var Structure.item by Structure.FromStructure<Any>() // Current pose and MotionItems previous affine/validity.
/*graphics-pc-binding:item*/ var Mesh.item by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // Current pose and MotionItems previous affine/validity.
/*graphics-pc-binding-group:item*/ var MeshGroup.item by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // Current pose and MotionItems previous affine/validity.
/*graphics-pc-structure:velocity*/ var Structure.velocity by Structure.FromStructure<Any>() // Current projected fluid velocity.
/*graphics-pc-binding:velocity*/ var Mesh.velocity by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // Current projected fluid velocity.
/*graphics-pc-binding-group:velocity*/ var MeshGroup.velocity by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // Current projected fluid velocity.
/*graphics-pc-structure:tiles*/ var Structure.tiles by Structure.FromStructure<Any>() // Resident world coordinates and generations.
/*graphics-pc-binding:tiles*/ var Mesh.tiles by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // Resident world coordinates and generations.
/*graphics-pc-binding-group:tiles*/ var MeshGroup.tiles by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // Resident world coordinates and generations.
/*graphics-pc-structure:history*/ var Structure.history by Structure.FromStructure<Any>() // FrameHistory's complete 68-float layout.
/*graphics-pc-binding:history*/ var Mesh.history by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // FrameHistory's complete 68-float layout.
/*graphics-pc-binding-group:history*/ var MeshGroup.history by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // FrameHistory's complete 68-float layout.
/*graphics-pc-structure:clock*/ var Structure.clock by Structure.FromStructure<Any>() // x time; y dt; zw reserved.
/*graphics-pc-binding:clock*/ var Mesh.clock by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // x time; y dt; zw reserved.
/*graphics-pc-binding-group:clock*/ var MeshGroup.clock by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // x time; y dt; zw reserved.
/*graphics-pc-structure:look*/ var Structure.look by Structure.FromStructure<Any>() // x world grain cell; y stroke seconds; z gain; w debug view.
/*graphics-pc-binding:look*/ var Mesh.look by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // x world grain cell; y stroke seconds; z gain; w debug view.
/*graphics-pc-binding-group:look*/ var MeshGroup.look by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>() // x world grain cell; y stroke seconds; z gain; w debug view.
var Box.window by Inheritance<Window>() /**/
val Once.window by Onceing<Window>() /**/
var Box.renderer by Inheritance<Renderer>() /**/
val Once.renderer by Onceing<Renderer>() /**/
