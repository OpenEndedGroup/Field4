import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.simulation.agents.ShaderLatticeBoltzmannSystem
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:center*/ var Structure.center by Structure.FromStructure<Any>()
/*graphics-pc-binding:center*/ var Mesh.center by Mesh.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-binding-group:center*/ var MeshGroup.center by MeshGroup.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-structure:radius*/ var Structure.radius by Structure.FromStructure<Any>()
/*graphics-pc-binding:radius*/ var Mesh.radius by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:radius*/ var MeshGroup.radius by MeshGroup.BoundFromStructure<Number>()
/*graphics-pc-structure:height*/ var Structure.height by Structure.FromStructure<Any>()
/*graphics-pc-binding:height*/ var Mesh.height by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:height*/ var MeshGroup.height by MeshGroup.BoundFromStructure<Number>()
var Box.lbm by Inheritance<ShaderLatticeBoltzmannSystem>() /*from:lbm_fluid*/ /**/
val Once.lbm by Onceing<ShaderLatticeBoltzmannSystem>() /*from:lbm_fluid*/ /**/
val Why.lbm by Whying() /*from:lbm_fluid*/ /**/
var Box.window by Inheritance<Window>() /*from:lbm_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:lbm_renderer*/ /**/
val Why.window by Whying() /*from:lbm_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:lbm_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:lbm_renderer*/ /**/
val Why.renderer by Whying() /*from:lbm_renderer*/ /**/
