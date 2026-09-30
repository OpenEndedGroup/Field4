import afield.four.Renderer
import afield.four.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
import afield.four.targets.RenderToTexture
var Box.plumbing by Inheritance<AutoCloseable>() /**/
val Once.plumbing by Onceing<AutoCloseable>() /**/
val Why.plumbing by Whying() /**/
var Box.source by Inheritance<RenderToTexture>() /**/
val Once.source by Onceing<RenderToTexture>() /**/
val Why.source by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:sizeJitter*/ var Structure.sizeJitter by Structure.FromStructure<Any>()
/*graphics-pc-binding:sizeJitter*/ var Mesh.sizeJitter by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-binding-group:sizeJitter*/ var MeshGroup.sizeJitter by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-structure:times*/ var Structure.times by Structure.FromStructure<Any>()
/*graphics-pc-binding:times*/ var Mesh.times by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-binding-group:times*/ var MeshGroup.times by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-structure:settings*/ var Structure.settings by Structure.FromStructure<Any>()
/*graphics-pc-binding:settings*/ var Mesh.settings by Mesh.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
/*graphics-pc-binding-group:settings*/ var MeshGroup.settings by MeshGroup.BoundFromStructure<afield.stdlib.linalg.Vector4f>()
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
