import afield.simulation.orbits.SatelliteCatalogue
import afield.simulation.orbits.SkyClock
import afield.graphics.vulkan.util.Camera
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
var Box.sky by Inheritance<SatelliteCatalogue>() /**/
val Once.sky by Onceing<SatelliteCatalogue>() /**/
val Why.sky by Whying() /**/
var Box.clock by Inheritance<SkyClock>() /**/
val Once.clock by Onceing<SkyClock>() /**/
val Why.clock by Whying() /**/
var Box.camera by Inheritance<Camera>() /**/
val Once.camera by Onceing<Camera>() /**/
val Why.camera by Whying() /**/
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*graphics-input:col*/ val Mesh.col by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var pos : FloatBuffer; lateinit var col : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("col") { col = this ; mesh.write("pos") { pos = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } } ;            };         }
/*graphics-pc-structure:p*/ var Structure.p by Structure.FromStructure<Any>()
/*graphics-pc-binding:p*/ var Mesh.p by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-binding-group:p*/ var MeshGroup.p by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-structure:m*/ var Structure.m by Structure.FromStructure<Any>()
/*graphics-pc-binding:m*/ var Mesh.m by Mesh.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-binding-group:m*/ var MeshGroup.m by MeshGroup.BoundFromStructure<afield.math.linalg.Matrix4f>()
/*graphics-pc-structure:eye*/ var Structure.eye by Structure.FromStructure<Any>()
/*graphics-pc-binding:eye*/ var Mesh.eye by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:eye*/ var MeshGroup.eye by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-structure:spin*/ var Structure.spin by Structure.FromStructure<Any>()
/*graphics-pc-binding:spin*/ var Mesh.spin by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:spin*/ var MeshGroup.spin by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
