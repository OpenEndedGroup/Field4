import afield.graphics.shader.learning.GpuShaderLearningView
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
var Box.lab by Inheritance<GpuShaderLearningView>() /**/
val Once.lab by Onceing<GpuShaderLearningView>() /**/
val Why.lab by Whying() /**/
/*supermap*/ class MappedStructure {;     lateinit var tri : IntBuffer; ; }; fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;     this.resize(numVert, numTriangle);     val mesh = this;     MappedStructure().apply {;          mesh.t?.mapAndWrite_ints { tri = this ; builder() } ;    }; }
/*graphics-pc-structure:state*/ var Structure.state by Structure.FromStructure<Any>()
/*graphics-pc-binding:state*/ var Mesh.state by Mesh.BoundFromStructure<afield.math.linalg.Vector4f>()
/*graphics-pc-binding-group:state*/ var MeshGroup.state by MeshGroup.BoundFromStructure<afield.math.linalg.Vector4f>()
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
