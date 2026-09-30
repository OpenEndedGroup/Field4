import afield.four.Renderer
import afield.four.util.Window
import afield.four.synth.PatSynthLaunch
import afield.four.buffers.FrameBufferGroup
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import java.nio.*
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:Positions:Positions*/
class Positions(
    members: List<Introspected.Member> = PositionsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var pos by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun PositionsByteSize(): Long = 16L
fun PositionsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("pos", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asPositions(): Positions =
    asStructure(Positions(), PositionsLayoutMembers())
fun BufferParameters.asPositions(): Positions =
    buffer.asPositions()
/*end*/
/*graphics-pc-structure:inData*/ var Structure.inData by Structure.FromStructure<Any>()
/*graphics-pc-binding:inData*/ var Mesh.inData by Mesh.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-binding-group:inData*/ var MeshGroup.inData by MeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>()
/*graphics-pc-structure:aspectRatio*/ var Structure.aspectRatio by Structure.FromStructure<Any>()
/*graphics-pc-binding:aspectRatio*/ var Mesh.aspectRatio by Mesh.BoundFromStructure<Float>()
/*graphics-pc-binding-group:aspectRatio*/ var MeshGroup.aspectRatio by MeshGroup.BoundFromStructure<Float>()
var Box.outputPositions by Inheritance<FrameBufferGroup>() /*from:pp_compute*/ /**/
val Once.outputPositions by Onceing<FrameBufferGroup>() /*from:pp_compute*/ /**/
val Why.outputPositions by Whying() /*from:pp_compute*/ /**/
var Box.bandEnergy by Inheritance<DoubleArray>() /*from:pp_audio*/ /**/
val Once.bandEnergy by Onceing<DoubleArray>() /*from:pp_audio*/ /**/
val Why.bandEnergy by Whying() /*from:pp_audio*/ /**/
var Box.patternSynth by Inheritance<PatSynthLaunch>() /*from:pp_audio*/ /**/
val Once.patternSynth by Onceing<PatSynthLaunch>() /*from:pp_audio*/ /**/
val Why.patternSynth by Whying() /*from:pp_audio*/ /**/
var Box.window by Inheritance<Window>() /*from:pp_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pp_renderer*/ /**/
val Why.window by Whying() /*from:pp_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pp_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pp_renderer*/ /**/
val Why.renderer by Whying() /*from:pp_renderer*/ /**/
