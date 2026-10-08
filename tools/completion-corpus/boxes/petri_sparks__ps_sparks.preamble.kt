import afield.four.Renderer
import afield.four.util.Window
import afield.petri.PetriRun
import afield.petri.PetriNet
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Bursts:Bursts*/
class Bursts(
    members: List<Introspected.Member> = BurstsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var b by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun BurstsByteSize(): Long = 16L
fun BurstsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("b", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asBursts(): Bursts =
    asStructure(Bursts(), BurstsLayoutMembers())
fun BufferParameters.asBursts(): Bursts =
    buffer.asBursts()
/*end*/
/*graphics-pc-structure:bursts*/ var Structure.bursts by Structure.FromStructure<Any>()
/*graphics-pc-binding:bursts*/ var Mesh.bursts by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:bursts*/ var MeshGroup.bursts by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:aspectRatio*/ var Structure.aspectRatio by Structure.FromStructure<Any>()
/*graphics-pc-binding:aspectRatio*/ var Mesh.aspectRatio by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:aspectRatio*/ var MeshGroup.aspectRatio by MeshGroup.BoundFromStructure<Float>()
/*graphics-pc-structure:charge*/ var Structure.charge by Structure.FromStructure<Any>()
/*graphics-pc-binding:charge*/ var Mesh.charge by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:charge*/ var MeshGroup.charge by MeshGroup.BoundFromStructure<Float>()
/*graphics-pc-structure:token0*/ var Structure.token0 by Structure.FromStructure<Any>()
/*graphics-pc-binding:token0*/ var Mesh.token0 by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:token0*/ var MeshGroup.token0 by MeshGroup.BoundFromStructure<Float>()
/*graphics-pc-structure:token1*/ var Structure.token1 by Structure.FromStructure<Any>()
/*graphics-pc-binding:token1*/ var Mesh.token1 by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:token1*/ var MeshGroup.token1 by MeshGroup.BoundFromStructure<Float>()
/*graphics-pc-structure:token2*/ var Structure.token2 by Structure.FromStructure<Any>()
/*graphics-pc-binding:token2*/ var Mesh.token2 by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:token2*/ var MeshGroup.token2 by MeshGroup.BoundFromStructure<Float>()
var Box.sparkNet by Inheritance<PetriNet>() /*from:ps_net*/ /**/
val Once.sparkNet by Onceing<PetriNet>() /*from:ps_net*/ /**/
val Why.sparkNet by Whying() /*from:ps_net*/ /**/
var Box.sparkRun by Inheritance<PetriRun>() /*from:ps_net*/ /**/
val Once.sparkRun by Onceing<PetriRun>() /*from:ps_net*/ /**/
val Why.sparkRun by Whying() /*from:ps_net*/ /**/
var Box.window by Inheritance<Window>() /*from:ps_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:ps_renderer*/ /**/
val Why.window by Whying() /*from:ps_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:ps_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:ps_renderer*/ /**/
val Why.renderer by Whying() /*from:ps_renderer*/ /**/
