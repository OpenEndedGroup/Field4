import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.simulation.orbits.SatelliteCatalogue
import afield.simulation.orbits.SkyClock
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*start*/
/*graphics-structure-type:OrbitData:OrbitData*/
class OrbitData(
    members: List<Introspected.Member> = OrbitDataLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var v by Structure.FromStructure<afield.math.linalg.Vector4f>()
}
fun OrbitDataByteSize(): Long = 16L
fun OrbitDataLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("v", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asOrbitData(): OrbitData =
    asStructure(OrbitData(), OrbitDataLayoutMembers())
fun BufferParameters.asOrbitData(): OrbitData =
    buffer.asOrbitData()
/*end*/
/*graphics-pc-structure:orbits*/ var Structure.orbits by Structure.FromStructure<Any>()
/*graphics-pc-binding:orbits*/ var Mesh.orbits by Mesh.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-binding-group:orbits*/ var MeshGroup.orbits by MeshGroup.BoundFromStructure<afield.graphics.vulkan.buffers.HasBuffer>()
/*graphics-pc-structure:p*/ var Structure.p by Structure.FromStructure<Any>()
/*graphics-pc-binding:p*/ var Mesh.p by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-binding-group:p*/ var MeshGroup.p by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-structure:m*/ var Structure.m by Structure.FromStructure<Any>()
/*graphics-pc-binding:m*/ var Mesh.m by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-binding-group:m*/ var MeshGroup.m by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-structure:eye*/ var Structure.eye by Structure.FromStructure<Any>()
/*graphics-pc-binding:eye*/ var Mesh.eye by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:eye*/ var MeshGroup.eye by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-structure:clock*/ var Structure.clock by Structure.FromStructure<Any>()
/*graphics-pc-binding:clock*/ var Mesh.clock by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:clock*/ var MeshGroup.clock by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
var Box.sky by Inheritance<SatelliteCatalogue>() /*from:satellite-earth*/ /**/
val Once.sky by Onceing<SatelliteCatalogue>() /*from:satellite-earth*/ /**/
val Why.sky by Whying() /*from:satellite-earth*/ /**/
var Box.clock by Inheritance<SkyClock>() /*from:satellite-earth*/ /**/
val Once.clock by Onceing<SkyClock>() /*from:satellite-earth*/ /**/
val Why.clock by Whying() /*from:satellite-earth*/ /**/
var Box.camera by Inheritance<Camera>() /*from:satellite-earth*/ /**/
val Once.camera by Onceing<Camera>() /*from:satellite-earth*/ /**/
val Why.camera by Whying() /*from:satellite-earth*/ /**/
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
