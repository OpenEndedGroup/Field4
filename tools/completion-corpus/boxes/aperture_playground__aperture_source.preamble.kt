import afield.graphics.vulkan.targets.RenderToTexture
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
var Box.paintSource by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /**/
val Once.paintSource by Onceing<RenderToTexture>() /**/
val Why.paintSource by Whying() /**/
var Box.apertureControl by Inheritance<afield.math.linalg.Vec4>() /**/
val Once.apertureControl by Onceing<afield.math.linalg.Vec4>() /**/
val Why.apertureControl by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:look*/ var Structure.look by Structure.FromStructure<Any>()
/*graphics-pc-binding:look*/ var Mesh.look by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:look*/ var MeshGroup.look by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-structure:size*/ var Structure.size by Structure.FromStructure<Any>()
/*graphics-pc-binding:size*/ var Mesh.size by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:size*/ var MeshGroup.size by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
