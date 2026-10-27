import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import java.nio.*
import arrow.core.right
import arrow.core.left
import afield.graphics.shader.*
import afield.graphics.scene.*
import afield.graphics.render.*
import afield.document.context.*
import afield.graphics.vulkan.buffers.*
/*graphics-input:col*/ val Mesh.col by FromInput()
/*graphics-input:pos*/ val Mesh.pos by FromInput()
/*supermap*/ class MappedStructure {;             lateinit var tri : IntBuffer; lateinit var col : FloatBuffer; lateinit var pos : FloatBuffer; ;         };         fun Mesh.set(numVert : Int, numTriangle : Int = 0, builder : MappedStructure.() -> Unit = {}) {;             this.resize(numVert, numTriangle);             val mesh = this;             MappedStructure().apply {;                  mesh.write("pos") { pos = this ; mesh.write("col") { col = this ; mesh.t?.mapAndWrite_ints { tri = this ; builder() } } } ;            };         }
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
