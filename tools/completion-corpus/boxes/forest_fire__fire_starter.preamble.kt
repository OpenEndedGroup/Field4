import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.simulation.agents.ShaderForestFireSystem
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:red*/ var Structure.red by Structure.FromStructure<Any>()
/*graphics-pc-binding:red*/ var Mesh.red by Mesh.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-binding-group:red*/ var MeshGroup.red by MeshGroup.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-structure:green*/ var Structure.green by Structure.FromStructure<Any>()
/*graphics-pc-binding:green*/ var Mesh.green by Mesh.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-binding-group:green*/ var MeshGroup.green by MeshGroup.BoundFromStructure<afield.math.linalg.Vector2f>()
/*graphics-pc-structure:radius*/ var Structure.radius by Structure.FromStructure<Any>()
/*graphics-pc-binding:radius*/ var Mesh.radius by Mesh.BoundFromStructure<Number>()
/*graphics-pc-binding-group:radius*/ var MeshGroup.radius by MeshGroup.BoundFromStructure<Number>()
var Box.fire by Inheritance<ShaderForestFireSystem>() /*from:fire_forest*/ /**/
val Once.fire by Onceing<ShaderForestFireSystem>() /*from:fire_forest*/ /**/
val Why.fire by Whying() /*from:fire_forest*/ /**/
var Box.window by Inheritance<Window>() /*from:fire_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:fire_renderer*/ /**/
val Why.window by Whying() /*from:fire_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:fire_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:fire_renderer*/ /**/
val Why.renderer by Whying() /*from:fire_renderer*/ /**/
